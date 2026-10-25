package com.openminis.app.sandbox

/**
 * Who belongs to a sandbox root, independent of how PRoot reparents them.
 *
 * On the 2026-09-27 tablet, `python3`/`tee`/`head` were recorded with the
 * app as parent, not the proot tracer. A walk of `/proc/pid/task/children`
 * starting at proot therefore misses the workload. A child is owned when
 * its parent *or* its tracer is already in the set.
 */
internal data class ProcLink(
    val pid: Int,
    val ppid: Int,
    val tracerPid: Int,
)

/**
 * Pids that have ever belonged to a root. A child that calls setsid and is
 * reparented drops out of the live tree; it stays owned until /proc says
 * it is gone, so the kill still reaches it.
 */
internal class StickyTree(val rootPid: Int) {
    private val seen = LinkedHashSet<Int>()

    init {
        if (rootPid > 0) seen.add(rootPid)
    }

    fun observe(current: Set<Int>, alive: Set<Int>): Set<Int> = synchronized(this) {
        seen.addAll(current)
        seen.retainAll { it == rootPid || it in alive }
        seen.toSet()
    }

    fun snapshot(): Set<Int> = synchronized(this) { seen.toSet() }
}

internal object ProcessTree {

    fun collect(rootPid: Int, links: List<ProcLink>): Set<Int> {
        if (rootPid <= 0) return emptySet()
        val byParent = links.groupBy { it.ppid }
        val byTracer = links.groupBy { it.tracerPid }
        val out = LinkedHashSet<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(rootPid)
        out.add(rootPid)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            val next = ArrayList<ProcLink>()
            byParent[pid]?.let(next::addAll)
            if (pid != 0) byTracer[pid]?.let(next::addAll)
            for (link in next) {
                if (link.pid > 0 && out.add(link.pid)) queue.add(link.pid)
            }
        }
        return out
    }

    /** Processes that share [rootPid]'s process group, including the root. */
    fun sameGroup(rootPid: Int, pgrpByPid: Map<Int, Int>): Set<Int> {
        val group = pgrpByPid[rootPid] ?: return if (rootPid > 0) setOf(rootPid) else emptySet()
        if (group <= 0) return setOf(rootPid)
        return pgrpByPid.filterValues { it == group }.keys
    }

    fun parseStatusIds(status: String): Pair<Int, Int> {
        var ppid = 0
        var tracer = 0
        for (line in status.lineSequence()) {
            when {
                line.startsWith("PPid:") -> ppid = line.substringAfter(':').trim().toIntOrNull() ?: 0
                line.startsWith("TracerPid:") -> tracer = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        return ppid to tracer
    }

    /**
     * `pid (comm) state ppid pgrp ...` — comm may contain spaces and
     * parentheses, so the process group is the field after the last `)`.
     */
    fun parseStatPgrp(stat: String): Int {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return 0
        val fields = stat.substring(close + 2).trim().split(Regex("\\s+"))
        // state, ppid, pgrp
        return fields.getOrNull(2)?.toIntOrNull() ?: 0
    }
}
