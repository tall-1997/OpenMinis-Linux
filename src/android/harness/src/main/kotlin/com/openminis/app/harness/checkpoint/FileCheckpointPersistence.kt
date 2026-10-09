package com.openminis.app.harness.checkpoint

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Adapted from taixu FileCheckpointPersistence (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 默认文件系统持久化：`<root>/<sessionId>/<turn>.index.json` + `<root>/<sessionId>/<turn>/<seq>.snap`。
 * 根目录由宿主指定（应用私有目录，不经 SAF；不会出现在 PRoot 工作区中，模型不可见）。
 */
class FileCheckpointPersistence(
    private val root: File,
    /** 单会话快照总量预算；默认值见 [DEFAULT_MAX_SESSION_BYTES]，测试可注入更小预算。 */
    private val maxSessionBytes: Long = DEFAULT_MAX_SESSION_BYTES,
) : CheckpointStore.Persistence {

    @Serializable
    private data class IndexEntry(
        val turn: Int,
        val time: Long,
        val prompt: String,
        val anchorMessageId: String? = null,
        /** seq -> 快照内容文件名；content 为 null（文件不存在）的快照无条目。 */
        val files: Map<Int, String> = emptyMap(),
        /** seq -> 快照原始路径。 */
        val paths: Map<Int, String> = emptyMap(),
        /** 记录 content==null 的 seq（恢复时区分"不存在"与"空内容文件"）。 */
        val absent: List<Int> = emptyList(),
        /** seq -> 改动后凭据文件名（旧索引无此字段，恢复时按无凭据处理）。 */
        val afterFiles: Map<Int, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun write(sessionId: String, checkpoint: Checkpoint) {
        val sessionDir = File(root, sessionId)
        val turnDir = File(sessionDir, checkpoint.turn.toString())
        turnDir.mkdirs()
        val entries = mutableMapOf<Int, String>()
        val paths = mutableMapOf<Int, String>()
        val afterEntries = mutableMapOf<Int, String>()
        val absent = mutableListOf<Int>()
        checkpoint.files.forEachIndexed { seq, snap ->
            paths[seq] = snap.path
            val content = snap.content ?: run { absent += seq; return@forEachIndexed }
            val file = File(turnDir, "$seq.snap")
            file.writeText(content, Charsets.UTF_8)
            entries[seq] = file.name
            // 改动后凭据与 pre-image 同规则落盘（null = 无凭据，restore 时不做冲突检测）
            val afterContent = snap.afterContent ?: return@forEachIndexed
            val afterFile = File(turnDir, "$seq.after")
            afterFile.writeText(afterContent, Charsets.UTF_8)
            afterEntries[seq] = afterFile.name
        }
        File(sessionDir, "${checkpoint.turn}.index.json").writeText(
            json.encodeToString(
                IndexEntry(
                    turn = checkpoint.turn,
                    time = checkpoint.time,
                    prompt = checkpoint.prompt,
                    anchorMessageId = checkpoint.anchorMessageId,
                    files = entries,
                    paths = paths,
                    absent = absent,
                    afterFiles = afterEntries,
                ),
            ),
        )
        // 与内存保留窗口对齐：超龄轮的索引与内容目录一并清掉
        val keptFloor = (checkpoint.turn - CheckpointStore.MAX_KEPT + 1).coerceAtLeast(0)
        sessionDir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { dir ->
                dir.name.toIntOrNull()?.let { turn -> if (turn < keptFloor) dir.deleteRecursively() }
            }
        sessionDir.listFiles { file -> file.name.endsWith(".index.json") }
            ?.forEach { index ->
                index.name.removeSuffix(".index.json").toIntOrNull()?.let { turn ->
                    if (turn < keptFloor) index.delete()
                }
            }
        enforceByteBudget(sessionDir, checkpoint.turn, keptFloor)
    }

    /**
     * 字节预算（对齐 Reasonix 的 blob quota）：MAX_KEPT 只限轮数，快照是整文件 pre-image，
     * 长会话反复编辑大文件时总量可能轻松破百 MB——移动端私有目录必须加总量护栏。
     * 超预算按轮号从最旧开始整轮删除（索引 + 内容目录），永不触碰当前轮；
     * 内存态未同步裁剪：本轮内 rewind 仍可用内存快照，重启后按磁盘实况恢复。
     */
    private fun enforceByteBudget(sessionDir: File, currentTurn: Int, keptFloor: Int) {
        var totalBytes = sessionDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        if (totalBytes <= maxSessionBytes) return
        val candidateTurns = sessionDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { it.name.toIntOrNull() }
            ?.filter { it >= keptFloor && it != currentTurn }
            ?.sorted()
            .orEmpty()
        for (turn in candidateTurns) {
            if (totalBytes <= maxSessionBytes) break
            val turnDir = File(sessionDir, turn.toString())
            val index = File(sessionDir, "$turn.index.json")
            val freed = turnDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } + index.length()
            if (turnDir.deleteRecursively()) index.delete()
            totalBytes -= freed
        }
    }

    override fun readAll(sessionId: String): List<Checkpoint> {
        val sessionDir = File(root, sessionId)
        val indexFiles = sessionDir.listFiles { file -> file.name.endsWith(".index.json") } ?: return emptyList()
        return indexFiles.mapNotNull { indexFile ->
            runCatching {
                val entry = json.decodeFromString<IndexEntry>(indexFile.readText(Charsets.UTF_8))
                val snaps = entry.paths.keys.sorted().mapNotNull { seq ->
                    val path = entry.paths.getValue(seq)
                    if (seq in entry.absent) {
                        // 显式记录的"文件当时不存在"：null 内容是 rewind 执行删除的合法信号
                        return@mapNotNull FileSnap(path, null)
                    }
                    // 索引条目或内容文件缺失 = 快照损坏：整体跳过（路径不进回滚方案，文件保持原样）。
                    // 绝不能映射为 null 内容——那会被 RewindController 当作"当时不存在"而误删现存文件。
                    val fileName = entry.files[seq] ?: return@mapNotNull null
                    val snapFile = File(sessionDir, "${entry.turn}/$fileName")
                    if (!snapFile.isFile) return@mapNotNull null
                    val content = runCatching { snapFile.readText(Charsets.UTF_8) }.getOrNull()
                        ?: return@mapNotNull null
                    // 改动后凭据缺失不视为损坏（旧索引没有该文件；凭据只影响冲突检测的覆盖面）
                    val afterContent = entry.afterFiles[seq]?.let { afterName ->
                        runCatching { File(sessionDir, "${entry.turn}/$afterName").readText(Charsets.UTF_8) }.getOrNull()
                    }
                    FileSnap(path, content, afterContent)
                }
                Checkpoint(
                    turn = entry.turn,
                    time = entry.time,
                    prompt = entry.prompt,
                    files = snaps,
                    anchorMessageId = entry.anchorMessageId,
                )
            }.getOrNull()
        }.sortedBy { it.turn }
    }

    override fun delete(sessionId: String) {
        File(root, sessionId).deleteRecursively()
    }

    private companion object {
        /** 单会话快照总量预算：超过即按最旧整轮淘汰（当前轮与 MAX_KEPT 窗口保护见 [enforceByteBudget]）。 */
        const val DEFAULT_MAX_SESSION_BYTES = 64L * 1024 * 1024
    }
}
