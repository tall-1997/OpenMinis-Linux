package com.openminis.app.harness.checkpoint

import java.util.concurrent.ConcurrentHashMap

/**
 * Adapted from taixu CheckpointStore (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 文件快照的按会话存储与重放规划。不触碰 git：
 * - 每个用户轮次 [beginTurn] 开启一个 checkpoint，仅记录该轮被 write/edit 触碰文件在触碰前的轮初内容；
 * - 同一轮同一路径去重（只保留第一次触碰的轮初内容）；
 * - [beginTurn] 切换时关闭上一轮 checkpoint，并保留最近 [MAX_KEPT] 轮；
 * - [planCodeRewind] 生成从目标轮起逐 path 取最早快照的恢复方案，供 RewindController 落盘。
 *
 * 落盘由 [Persistence] 接缝承担（默认实现见 [FileCheckpointPersistence]）：
 * 关轮时异步写入（失败仅放弃持久化，不影响内存态；进程在写入前被杀会丢最近一轮的持久化——
 * 安全网特性可接受，且该轮大概率会被重做）；启动后首次访问该会话时从磁盘恢复。
 * 未配置根目录时退化为纯内存（进程被杀后 rewind 丢失），行为与旧版一致。
 */
class CheckpointStore() {

    /** 持久化配置；null = 纯内存模式。由宿主在初始化期一次性注入。 */
    @Volatile
    var persistence: Persistence? = null

    /**
     * 落盘执行器：单线程串行（写索引与内容文件、超龄清理无并发竞争）。写入绝不能在
     * [closeTurn] 的实例锁内同步执行——@Synchronized 覆盖全部方法，一次慢盘（listFiles +
     * deleteRecursively 清理）会串行阻塞其他会话每次 write/edit 前的 capture 路径。
     */
    @Volatile
    internal var diskWriteExecutor: java.util.concurrent.Executor =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "checkpoint-disk").apply { isDaemon = true }
        }

    /** 磁盘恢复标记：会话首次访问时懒恢复，避免启动期全量 IO。 */
    private val restoredSessions = ConcurrentHashMap.newKeySet<String>()

    private val sessions = ConcurrentHashMap<String, SessionState>()

    /** 每次 rewind 的撤销记录（单层级）；新写入/新 rewind/会话删除时失效。 */
    private val rewindUndoRecords = ConcurrentHashMap<String, RewindUndoRecord>()

    /** 磁盘持久化接缝：把轮 checkpoint 以索引 + 内容文件形式落盘/读回。 */
    interface Persistence {
        /** 落盘一个已关闭的轮 checkpoint（content 为 null 的快照不产生内容文件）。 */
        fun write(sessionId: String, checkpoint: Checkpoint)

        /** 读取该会话已落盘的全部轮 checkpoint（升序）。 */
        fun readAll(sessionId: String): List<Checkpoint>

        /** 删除该会话的全部持久化数据（会话删除时）。 */
        fun delete(sessionId: String)
    }

    private class SessionState {
        val checkpoints = mutableListOf<Checkpoint>()
        var active: MutableMap<String, FileSnap>? = null
        var activeTurn = 0
        var activePrompt = ""
        var activeAnchorMessageId: String? = null
        var open = false
        /** 已分配的最大轮号；内存裁剪（MAX_KEPT）后 size 会回绕，轮号必须独立单调递增。 */
        var lastTurn = -1
    }

    /** 开启一个新的用户轮次 checkpoint，并关闭上一轮（若有）。 */
    @Synchronized
    fun beginTurn(sessionId: String, prompt: String, anchorMessageId: String? = null) {
        val state = stateOf(sessionId)
        closeTurn(sessionId, state)
        state.activeTurn = state.lastTurn + 1
        state.activePrompt = prompt.ifBlank { "（空白输入）" }
        state.activeAnchorMessageId = anchorMessageId
        state.active = LinkedHashMap()
        state.open = true
    }

    /** 记录某路径在该轮"触碰前"的内容；无活动轮或路径已记录时忽略。 */
    @Synchronized
    fun capture(sessionId: String, path: String, before: String?): Boolean {
        val state = sessions[sessionId] ?: return false
        val active = state.active ?: return false
        if (path in active) return false
        active[path] = FileSnap(path, before)
        // rewind 之后智能体又开始写文件：撤销窗口关闭（再 undo 会覆盖新写入）
        rewindUndoRecords.remove(sessionId)
        return true
    }

    /** 覆盖式记录某次 rewind 的撤销快照（同会话重复 rewind 以最后一次为准）。 */
    @Synchronized
    fun recordRewindUndo(sessionId: String, record: RewindUndoRecord) {
        rewindUndoRecords[sessionId] = record
    }

    /** 取出（消费）撤销记录；null = 当前没有可撤销的 rewind。 */
    @Synchronized
    fun takeRewindUndo(sessionId: String): RewindUndoRecord? = rewindUndoRecords.remove(sessionId)

    /**
     * 记录某路径**成功写入后**的内容（改动后凭据）。同轮同路径多次写入时后者覆盖前者，
     * 关轮时落盘的是最后一次写入后的状态。无活动轮或该路径无 pre-image（超大文件跳过）
     * 时忽略——改动后凭据永远与轮初快照成对。
     */
    @Synchronized
    fun captureAfterImage(sessionId: String, path: String, content: String): Boolean {
        val state = sessions[sessionId] ?: return false
        val active = state.active ?: return false
        val snap = active[path] ?: return false
        active[path] = snap.copy(afterContent = content)
        return true
    }

    /**
     * 某路径在本 store 记录中的**最后改动后凭据**：活动轮优先，其余按关闭轮新→旧取第一个。
     * restore 的冲突检测用它判断"当前文件是否被外部改动过"；null = 无凭据（不检测）。
     */
    @Synchronized
    fun latestAfterImage(sessionId: String, path: String): String? {
        val state = stateOf(sessionId)
        state.active?.get(path)?.afterContent?.let { return it }
        for (checkpoint in state.checkpoints.asReversed()) {
            checkpoint.files.firstOrNull { it.path == path }?.afterContent?.let { return it }
        }
        return null
    }

    /** 强制关闭当前活动轮（无触碰则丢弃空轮）。 */
    @Synchronized
    fun endTurn(sessionId: String) {
        sessions[sessionId]?.let { closeTurn(sessionId, it) }
    }

    /** 会话删除/重建时清理。 */
    @Synchronized
    fun dropSession(sessionId: String) {
        sessions.remove(sessionId)
        restoredSessions.remove(sessionId)
        rewindUndoRecords.remove(sessionId)
        // 删除走同一单线程执行器排队：先于它的待写任务先落盘、随后被整体删除——
        // 消除"异步写在 dropSession 之后执行、write() 的 mkdirs 复活已删目录"的竞态
        persistence?.let { disk -> diskWriteExecutor.execute { runCatching { disk.delete(sessionId) } } }
    }

    @Synchronized
    fun checkpoints(sessionId: String): List<CheckpointMeta> =
        stateOf(sessionId).checkpoints.map {
            CheckpointMeta(it.turn, it.time, it.prompt, it.files.map { snap -> snap.path }, it.anchorMessageId)
        }

    /** 查询某轮 checkpoint 的用户消息锚点（供对话 fork 定位）。 */
    @Synchronized
    fun anchorMessageIdOf(sessionId: String, turn: Int): String? =
        stateOf(sessionId).checkpoints.firstOrNull { it.turn == turn }?.anchorMessageId

    /**
     * 规划"代码回滚"：撤回到 [turn]，即撤销该轮及之后的所有写改动。
     * 对每路径取 [turn] 起最早的轮初内容；路径本身是 [turn] 前创建的、
     * 之后仅被改动，则该最早快照即 [turn] 的轮初内容。
     */
    @Synchronized
    fun planCodeRewind(sessionId: String, turn: Int): List<FileSnap> {
        val state = stateOf(sessionId)
        val open = state.active
        // 轮号全局单调（活动轮 = activeTurn = lastTurn+1），MAX_KEPT 裁剪或空轮缺号后
        // 与 checkpoints.size/下标错位；必须按轮号比较，否则目标轮/活动轮会被错误纳入或漏掉。
        val newestClosedTurn = state.checkpoints.lastOrNull()?.turn ?: -1
        val newestTurn = if (open != null) maxOf(newestClosedTurn, state.activeTurn) else newestClosedTurn
        if (turn < 0 || turn > newestTurn) return emptyList()
        val merged = LinkedHashMap<String, FileSnap>()
        for (checkpoint in state.checkpoints) {
            if (checkpoint.turn >= turn) {
                for (snap in checkpoint.files) {
                    merged.putIfAbsent(snap.path, snap)
                }
            }
        }
        // 当前（尚未关闭）的轮次也纳入回滚范围：用 activeTurn 判断而非 checkpoints.size
        //（活动轮轮号是 lastTurn+1，裁剪后远大于 checkpoints.size，原条件会漏掉当前进行轮的改动）
        if (open != null && turn <= state.activeTurn) {
            for (snap in open.values) merged.putIfAbsent(snap.path, snap)
        }
        return merged.values.toList()
    }

    /** 首次访问时从磁盘恢复该会话的已关闭轮；后续访问直接用内存态。 */
    private fun stateOf(sessionId: String): SessionState {
        val state = sessions.getOrPut(sessionId) { SessionState() }
        val disk = persistence
        if (disk != null && restoredSessions.add(sessionId)) {
            runCatching {
                val restored = disk.readAll(sessionId)
                // 内存态非空说明本进程已有新轮（恢复发生在运行中），只补齐磁盘里更早的轮
                val existingTurns = state.checkpoints.mapTo(hashSetOf()) { it.turn }
                val missing = restored.filter { it.turn !in existingTurns }
                if (missing.isNotEmpty()) {
                    state.checkpoints.addAll(0, missing.sortedBy { it.turn })
                }
                // 恢复后裁剪到 MAX_KEPT（保留最新），与 write() 的 keptFloor 磁盘清理窗口对齐，
                // 避免恢复列表超过 100 项且与磁盘清理错位
                while (state.checkpoints.size > MAX_KEPT) state.checkpoints.removeAt(0)
                restored.maxOfOrNull { it.turn }?.let { if (it > state.lastTurn) state.lastTurn = it }
            }.onFailure {
                restoredSessions.remove(sessionId)
                System.err.println("Checkpoint restore failed for $sessionId; will retry: ${it.message}")
            }
        }
        return state
    }

    private fun closeTurn(sessionId: String, state: SessionState) {
        val active = state.active ?: return
        if (active.isNotEmpty()) {
            val checkpoint = Checkpoint(
                turn = state.activeTurn,
                time = System.currentTimeMillis(),
                prompt = state.activePrompt,
                files = active.values.toList(),
                anchorMessageId = state.activeAnchorMessageId,
            )
            state.checkpoints.add(checkpoint)
            while (state.checkpoints.size > MAX_KEPT) state.checkpoints.removeAt(0)
            if (checkpoint.turn > state.lastTurn) state.lastTurn = checkpoint.turn
            // 异步落盘（checkpoint 不可变，可安全移交）：失败只放弃持久化不影响内存态
            persistence?.let { disk ->
                diskWriteExecutor.execute { runCatching { disk.write(sessionId, checkpoint) } }
            }
        }
        state.active = null
        state.activeAnchorMessageId = null
        state.open = false
    }

    companion object {
        const val MAX_KEPT = 100

        /** 单文件快照上限：超过即整体跳过捕获（pre-image 与改动后凭据同规则）。 */
        const val SNAPSHOT_MAX_BYTES = 1L * 1024L * 1024L
    }
}
