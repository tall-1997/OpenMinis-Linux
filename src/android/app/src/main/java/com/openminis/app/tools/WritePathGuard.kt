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
        private val normalized = prefixes.map(::normalize).filter { it.startsWith("/") || it.equals(NO_WRITE, true) }

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
            allowed.set(prefixes.map(::normalize).filter { it.startsWith("/") })
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
        val ok = prefixes.any { n == it || n.startsWith("$it/") }
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
        return parts.map { normalize(it) }.filter { it.startsWith("/") }.distinct()
    }

    fun normalize(path: String): String {
        var p = path.trim().replace('\\', '/')
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }
}
