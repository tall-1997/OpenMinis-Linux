package com.openminis.app.tools

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Restricts [FileWriteTool] / [FileEditTool] to prefixes assigned via
 * `spawn_agent.write_paths` (拾忆-style isolation). Empty or absent
 * prefixes mean unrestricted for a solo writer; parallel workers must set them.
 *
 * Implemented as a [ThreadContextElement] so the allow-list survives
 * `withContext` hops and so two parallel sub-agents on the same dispatcher
 * thread cannot overwrite each other's prefixes. [swap]/[restore] remain for
 * tests and any caller that is not inside a coroutine.
 */
object WritePathGuard {

    private val allowed = ThreadLocal<List<String>?>()

    class Scope(
        prefixes: List<String>,
    ) : ThreadContextElement<List<String>?> {
        private val normalized = toGuardList(prefixes)

        companion object Key : CoroutineContext.Key<Scope>

        override val key: CoroutineContext.Key<Scope> get() = Key

        override fun updateThreadContext(context: CoroutineContext): List<String>? {
            val old = allowed.get()
            if (normalized.isEmpty()) allowed.remove() else allowed.set(normalized)
            return old
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: List<String>?) {
            if (oldState.isNullOrEmpty()) allowed.remove() else allowed.set(oldState)
        }
    }

    suspend fun <T> withPaths(prefixes: List<String>, block: suspend () -> T): T =
        withContext(Scope(prefixes)) { block() }

    fun current(): List<String> = allowed.get().orEmpty()

    fun swap(prefixes: List<String>?): List<String>? {
        val previous = allowed.get()
        if (prefixes.isNullOrEmpty()) {
            allowed.remove()
        } else {
            allowed.set(toGuardList(prefixes))
        }
        return previous
    }

    fun restore(previous: List<String>?) {
        if (previous.isNullOrEmpty()) allowed.remove() else allowed.set(previous)
    }

    fun denyReason(linuxPath: String): String? {
        val prefixes = allowed.get() ?: return null
        if (prefixes.any { it.equals(NO_WRITE, true) }) {
            return "Error: this worker declared write_paths=none and cannot file_write or file_edit."
        }
        if (prefixes.isEmpty()) return null
        val n = normalize(linuxPath)
        if (n.isEmpty()) return "Error: path is empty and write_paths is in effect."
        val resolved = resolvedSegment(n)
        if (resolved == null) {
            return "Error: path $linuxPath escapes the root via '..' and write_paths is in effect."
        }
        val ok = prefixes.any { resolved == it || resolved.startsWith("$it/") }
        if (ok) return null
        return "Error: path $linuxPath is outside assigned write_paths (${prefixes.joinToString()})."
    }

    /**
     * Export the allow-list into the guest shell so scripts can honour it.
     * Does not `cd` — compilers and `ls /root` must keep working. File tools
     * remain the hard gate.
     */
    fun wrapShellCommand(command: String): String {
        val prefixes = current()
        if (prefixes.isEmpty()) return command
        val escaped = prefixes.joinToString(":") { it.replace("'", "'\\''") }
        return "export MINIS_WRITE_PATHS='$escaped'\n$command"
    }

    const val NO_WRITE = "none"

    fun isNoWrite(paths: List<String>): Boolean =
        paths.size == 1 && paths[0].equals(NO_WRITE, ignoreCase = true)

    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val parts = raw.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 1 && parts[0].equals(NO_WRITE, ignoreCase = true)) return listOf(NO_WRITE)
        // [T-p2-writelease-relative-scope] 相对路径按工作区根解析：租约拒绝文案
        // 引导协调者用相对路径声明 write_paths，而旧 parse 把不带 `/` 的段静默
        // 丢弃 → writePaths 变空 → 「未声明」永久拒绝——照文案改反而更糟。
        // 解析结果与绝对写法归一（`workspace/reports` == `/var/minis/workspace/reports`）。
        return parts.map { normalize(it) }
            .map {
                if (it.startsWith("/") || it.equals(NO_WRITE, true)) it
                else "${com.openminis.app.harness.subagent.WORKSPACE_ROOT}/$it"
            }
            .mapNotNull { resolvedSegment(it) }
            .filter { it.startsWith("/") }
            .distinct()
    }

    fun normalize(path: String): String {
        var p = path.trim().replace('\\', '/')
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }

    /**
     * [T-p2-writelease-relative-scope] 守卫名单与 parse 同一解析规则：相对段按
     * 工作区根解析、`..` 逐段消解（逃根丢弃）、NO_WRITE 透传。Scope/swap 直接
     * 消费 parse 的输出时等价；直接拿到未解析列表时也不会再静默丢弃相对段。
     */
    private fun toGuardList(prefixes: List<String>): List<String> =
        prefixes.map(::normalize)
            .map {
                if (it.startsWith("/") || it.equals(NO_WRITE, true)) it
                else "${com.openminis.app.harness.subagent.WORKSPACE_ROOT}/$it"
            }
            .mapNotNull { resolvedSegment(it) }
            .filter { it.startsWith("/") || it.equals(NO_WRITE, true) }
            .distinct()

    /**
     * [T-p2-writepathguard-dotdot] 逐段消解中段 `..`（与 harness 侧
     * harness/subagent/WritePaths 同一语义）。旧实现只做字符串折叠——scope
     * `/ws/reports` 时 `/ws/reports/../../shared/x` 前缀匹配通过，纵深防御层形同
     * 虚设（租约层今天先拦住，但任何绕过租约的新调用点只剩这一层）。
     *
     * @return 消解后的绝对路径；`..` 逃出根（如 `/../x`）返回 null —— 调用方
     *   一律拒绝，绝不退回原串。
     */
    private fun resolvedSegment(path: String): String? {
        if (!path.contains("..") && !path.contains("/.")) return path
        val out = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> {
                    if (out.isEmpty()) return null
                    out.removeLast()
                }
                else -> out.addLast(seg)
            }
        }
        return "/" + out.joinToString("/")
    }
}
