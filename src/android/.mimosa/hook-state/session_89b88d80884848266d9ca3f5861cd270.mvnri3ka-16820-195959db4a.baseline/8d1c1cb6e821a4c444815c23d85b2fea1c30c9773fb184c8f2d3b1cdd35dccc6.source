package com.openminis.app.sandbox

/**
 * [T-file-hub] Pure coalescing core of [FileChangeHub].
 *
 * inotify is chatty: one logical save fires CREATE + several MODIFY,
 * AtomicFileWrite fires tmp CREATE/MODIFY plus a final rename, and every
 * git command rewrites .git internals. Forwarding raw events would drown
 * consumers — the Git panel would refresh in a loop, because its refresh
 * itself runs git status (feedback). This class merges events per path
 * inside a quiet window and drops noise names outright.
 *
 * No Android types and an injectable clock, so merge rules and window
 * semantics are plain JVM-testable (FileEventCoalescerTest).
 */
class FileEventCoalescer(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    enum class Kind { CREATE, MODIFY, DELETE }

    /** One merged path event, emitted after its quiet window elapsed. */
    data class Due(
        val relPath: String,
        val kind: Kind,
        val isDir: Boolean,
        val lastAt: Long,
        /** How many raw events merged into this one. */
        val count: Int,
    )

    private class Pending(var kind: Kind, var isDir: Boolean, var lastAt: Long, var count: Int)

    private val pending = LinkedHashMap<String, Pending>()

    @Synchronized
    fun offer(relPath: String, kind: Kind, isDir: Boolean) {
        if (isIgnored(relPath)) return
        val now = clock()
        val cur = pending[relPath]
        if (cur == null) {
            pending[relPath] = Pending(kind, isDir, now, 1)
        } else {
            val merged = mergeKinds(cur.kind, kind)
            if (merged == null) {
                // CREATE+DELETE inside one window: the path net-changed
                // nothing (temp churn) — drop it entirely.
                pending.remove(relPath)
                return
            }
            cur.kind = merged
            cur.isDir = cur.isDir || isDir
            cur.lastAt = now
            cur.count++
        }
        enforceCap()
    }

    /** Entries whose quiet window has elapsed, removed from pending. */
    @Synchronized
    fun drainDue(): List<Due> {
        if (pending.isEmpty()) return emptyList()
        val now = clock()
        val due = ArrayList<Due>()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.lastAt >= windowMs) {
                due += Due(e.key, e.value.kind, e.value.isDir, e.value.lastAt, e.value.count)
                it.remove()
            }
        }
        return due
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    @Synchronized
    fun clear() = pending.clear()

    /** Evict oldest-lastAt entries beyond [MAX_PENDING] (bounded memory). */
    private fun enforceCap() {
        while (pending.size > MAX_PENDING) {
            val oldest = pending.entries.minByOrNull { it.value.lastAt }?.key ?: break
            pending.remove(oldest)
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 400L
        const val MAX_PENDING = 512

        /**
         * Noise filter:
         * - AtomicFileWrite artifacts (*.minis-tmp, *.tmp-replace) — the
         *   rename onto the final name is the real signal (MOVED_TO);
         * - editor/lock droppings (*.lock, *.swp);
         * - anything under .git — every git command rewrites .git/index,
         *   which would turn a status-refresh consumer into a feedback
         *   loop. Mutating git calls in-app already reload their own UI,
         *   and FileChangeHub additionally skips descending into .git dirs.
         * Works for both relative and absolute guest paths.
         */
        fun isIgnored(relPath: String): Boolean {
            val name = relPath.substringAfterLast('/')
            if (name.endsWith(".minis-tmp") || name.endsWith(".tmp-replace") ||
                name.endsWith(".lock") || name.endsWith(".swp")
            ) return true
            return relPath == ".git" || relPath.startsWith(".git/") || relPath.contains("/.git/")
        }

        /**
         * Merge two kinds observed for one path inside the window; null
         * means "drop the pending entry" (CREATE followed by DELETE nets
         * to nothing — temp churn).
         *
         * DELETE dominates (the path is gone) except right after CREATE
         * (cancel); anything after DELETE means the path exists again →
         * CREATE; CREATE absorbs MODIFY (file arrived and settled);
         * MODIFY + CREATE → CREATE (recreated).
         */
        fun mergeKinds(existing: Kind, incoming: Kind): Kind? = when {
            existing == incoming -> existing
            incoming == Kind.DELETE -> if (existing == Kind.CREATE) null else Kind.DELETE
            existing == Kind.DELETE -> Kind.CREATE
            existing == Kind.CREATE -> Kind.CREATE
            else -> incoming
        }
    }
}
