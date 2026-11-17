package com.openminis.app.sandbox

import android.content.Context
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.openminis.app.sandbox.jobs.JobRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Host-side owner of every sandbox process we spawn.
 *
 * The brake does not depend on the command string. A guest can rename
 * itself, call setsid, raise a soft rlimit, or be reparented onto the app.
 * Each root is recorded, moved to the background cgroup, and remembered
 * with every descendant ever seen. Stop, deadline, hang, memory pressure
 * and the process cap all kill that set, not just the pid we started.
 */
internal object SandboxWorkload {

    private const val TAG = "SandboxWorkload"
    private const val SWEEP_MS = 400L

    /** [T-sandbox-sweep-backoff] Adaptive ceiling: quiet workloads back off to this. */
    private const val SWEEP_MAX_MS = 2_000L
    private const val KILL_PASSES = 3

    private val CGROUP_FILES = listOf(
        "/dev/cpuctl/bg/cgroup.procs",
        "/dev/cpuctl/bg/tasks",
        "/dev/stune/background/cgroup.procs",
        "/dev/stune/background/tasks",
        "/dev/blkio/bg/cgroup.procs",
        "/dev/blkio/background/tasks",
    )

    private class Root(val pid: Int, val label: String) {
        val sticky = StickyTree(pid)
        val protected: Boolean = label.startsWith("terminal:")
        @Volatile var deadlineAt: Long = 0L
    }

    private val roots = ConcurrentHashMap<Int, Root>()
    @Volatile private var supervisorStarted = false
    @Volatile private var appContext: Context? = null
    @Volatile var lastStopReason: String? = null
        private set

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun hasLiveWork(): Boolean = roots.isNotEmpty()

    fun track(process: java.lang.Process, label: String) {
        val pid = pidOf(process) ?: return
        trackPid(pid, label)
    }

    fun trackPid(pid: Int, label: String) {
        if (pid <= 0) return
        val root = roots.computeIfAbsent(pid) { Root(pid, label) }
        adopt(pid)
        confineTree(root)
        ensureSupervisor()
        Log.i(TAG, "track pid=$pid label=$label live=${roots.size}")
    }

    /** Wall-clock backstop. Zero clears it. The guest cannot extend this. */
    fun armDeadline(process: java.lang.Process?, wallMs: Long) {
        val pid = process?.let { pidOf(it) } ?: return
        val root = roots[pid] ?: return
        root.deadlineAt = if (wallMs <= 0L) 0L else SystemClock.elapsedRealtime() + wallMs
    }

    fun clearDeadline(process: java.lang.Process?) {
        val pid = process?.let { pidOf(it) } ?: return
        roots[pid]?.deadlineAt = 0L
    }

    fun release(process: java.lang.Process?, kill: Boolean, reason: String) {
        val pid = process?.let { pidOf(it) } ?: return
        releasePid(pid, kill, reason)
        if (kill) runCatching { process.destroyForcibly() }
    }

    fun releasePid(pid: Int, kill: Boolean, reason: String) {
        if (pid <= 0) return
        if (kill) killOwned(pid, reason)
        roots.remove(pid)
    }

    fun stopAll(reason: String) {
        val snapshot = roots.keys.toList()
        if (snapshot.isEmpty()) return
        Log.w(TAG, "stopAll reason=$reason roots=${snapshot.size}")
        for (pid in snapshot) releasePid(pid, kill = true, reason = reason)
    }

    /** Hang and memory pressure. A `terminal:` root is the user's shell and is not a victim. */
    fun stopUnprotected(reason: String) {
        val snapshot = roots.entries.filter { !it.value.protected }.map { it.key }
        if (snapshot.isEmpty()) return
        Log.w(TAG, "stopUnprotected reason=$reason roots=${snapshot.size}")
        for (pid in snapshot) releasePid(pid, kill = true, reason = reason)
    }

    fun killOwned(pid: Int, reason: String) {
        lastStopReason = reason
        val sticky = roots[pid]?.sticky ?: StickyTree(pid)
        var victims = emptySet<Int>()
        var links: List<ProcLink> = emptyList()
        repeat(KILL_PASSES) {
            // [T-job-tree-spare] Background jobs the model started on purpose
            // are subtracted from every victim pass: a cap / pressure /
            // deadline sweep must not take them down with the shell's tree.
            val snapshot = snapshotProc()
            links = snapshot.links
            val now = snapshot.collect(pid)
            victims = ProcessTree.exceptTrees(
                sticky.observe(now, now), JobRegistry.activeJobPids(), links,
            )
            for (victim in victims) {
                if (victim == pid) continue
                signal(victim)
            }
        }
        Log.w(TAG, "kill reason=$reason root=$pid victims=${victims.joinToString(",")}")
        // The group signal never reaches a job (setsid put it in its own
        // process group); it kills the root process itself.
        runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
        for (victim in ProcessTree.exceptTrees(
            sticky.snapshot(), JobRegistry.activeJobPids(), links,
        )) signal(victim)
    }

    private fun signal(pid: Int) {
        if (pid <= 0) return
        runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        runCatching { AndroidProcess.killProcess(pid) }
    }

    /**
     * [stop=kill] Kill the RUNNING foreground command tree of a persistent
     * shell while the shell itself stays alive.
     *
     * [killOwned] cannot serve this: its trailing `Os.kill(-pid, SIGKILL)`
     * is a process-GROUP signal, and the shell is that group's leader
     * ([adopt] setpgid'd it at spawn), so the group kill would take the
     * shell down with the command — exactly what a user Stop must not do
     * (the shell carries cwd, env and installed packages; rebuilding it is
     * the expensive fallback, not the design).
     *
     * This walks the same /proc tables and SIGKILLs every descendant of the
     * shell's host process EXCEPT the guest shell bash itself
     * ([ProcessTree.isGuestShellBash] — the `exec /bin/bash --noprofile
     * --norc` incarnation). The command watchdog subshell is a descendant
     * too and dies with the rest, so it cannot later fire its own
     * `kill -TERM -$$` at the group and take the shell down after the fact.
     * [KILL_PASSES] absorbs processes that were still forking during an
     * earlier pass.
     *
     * Returns the pids that were signalled.
     */
    fun killCommandTree(process: java.lang.Process?, reason: String): Set<Int> {
        val rootPid = process?.let { pidOf(it) } ?: return emptySet()
        if (rootPid <= 0) return emptySet()
        val killed = LinkedHashSet<Int>()
        repeat(KILL_PASSES) {
            // [T-job-tree-spare] The walk starts at the shell's HOST process
            // (proot), so without this subtraction a user Stop or a watchdog
            // timeout would also sweep every setsid'd background job that has
            // ever belonged to this shell. Registered job trees are spared.
            val snapshot = snapshotProc()
            val spared = ProcessTree.exceptTrees(
                snapshot.collect(rootPid), JobRegistry.activeJobPids(), snapshot.links,
            )
            for (pid in spared) {
                if (pid == rootPid) continue
                if (ProcessTree.isGuestShellBash(
                        runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull()
                    )
                ) continue
                if (killed.add(pid)) {
                    Log.w(TAG, "interrupt pid=$pid reason=$reason")
                    signal(pid)
                }
            }
        }
        return killed
    }

    private fun adopt(pid: Int) {
        val failed = runCatching {
            val method = Os::class.java.getMethod("setpgid", Integer.TYPE, Integer.TYPE)
            method.invoke(null, pid, pid)
        }.exceptionOrNull()
        if (failed != null) Log.w(TAG, "setpgid $pid failed: ${failed.message}")
    }

    // [T-sandbox-sweep-backoff] confine() used to re-apply group/priority/
    // cgroup writes to EVERY owned pid on EVERY 400ms sweep — reflection
    // lookup + up to 6 cgroup open/write/close per pid per tick. Membership
    // in the confined set is sticky (new pids arrive via the sticky tree);
    // a periodic 30s re-confine catches system-side resets (lmkd priority
    // bumps, cgroup moves) without the per-tick cost.
    private val confinedAt = HashMap<Int, Long>()
    private const val RECONFINE_INTERVAL_MS = 30_000L

    @Volatile
    private var setProcessGroupMethod: java.lang.reflect.Method? = null

    private fun confine(pid: Int) {
        val now = SystemClock.elapsedRealtime()
        synchronized(confinedAt) {
            val last = confinedAt[pid]
            if (last != null && now - last < RECONFINE_INTERVAL_MS) return
            confinedAt[pid] = now
        }
        val method = synchronized(this) {
            setProcessGroupMethod ?: runCatching {
                AndroidProcess::class.java.getMethod(
                    "setProcessGroup",
                    Integer.TYPE,
                    Integer.TYPE,
                )
            }.getOrNull()?.also { setProcessGroupMethod = it }
        }
        if (method != null) {
            runCatching { method.invoke(null, pid, 0) }
        }
        runCatching { AndroidProcess.setThreadPriority(pid, AndroidProcess.THREAD_PRIORITY_LOWEST) }
        val text = pid.toString()
        for (path in CGROUP_FILES) {
            val file = File(path)
            if (!file.isFile) continue
            runCatching { file.writeText(text) }
        }
    }

    private fun pidOf(process: java.lang.Process): Int? = runCatching {
        val raw = process.javaClass.getMethod("pid").invoke(process) as Number
        raw.toInt()
    }.getOrNull()?.takeIf { it > 0 }

    private fun confineTree(root: Root) {
        val current = discover(root.pid)
        val owned = root.sticky.observe(current, current)
        for (pid in owned) confine(pid)
    }

    /**
     * One pass over /proc: the (pid → ppid/tracer) link table plus the
     * (pid → pgrp) table. Building it once and collecting per root keeps the
     * 400ms sweep at ONE /proc walk regardless of how many roots are live —
     * per-root [discover] used to redo the whole walk (status + stat read per
     * pid) for every root, so cost scaled with roots × processes.
     */
    private class ProcSnapshot(val links: List<ProcLink>, val groups: Map<Int, Int>) {
        fun collect(rootPid: Int): Set<Int> =
            ProcessTree.collect(rootPid, links) + ProcessTree.sameGroup(rootPid, groups)
    }

    private fun snapshotProc(): ProcSnapshot {
        val links = ArrayList<ProcLink>()
        val groups = HashMap<Int, Int>()
        val proc = File("/proc")
        val dirs = proc.listFiles() ?: return ProcSnapshot(emptyList(), emptyMap())
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val status = runCatching { File(dir, "status").readText() }.getOrNull() ?: continue
            val (ppid, tracer) = ProcessTree.parseStatusIds(status)
            links += ProcLink(pid, ppid, tracer)
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull()
            if (stat != null) groups[pid] = ProcessTree.parseStatPgrp(stat)
        }
        return ProcSnapshot(links, groups)
    }

    private fun discover(rootPid: Int): Set<Int> = snapshotProc().collect(rootPid)

    private fun ensureSupervisor() {
        if (supervisorStarted) return
        synchronized(this) {
            if (supervisorStarted) return
            supervisorStarted = true
            thread(name = "SandboxWorkload", isDaemon = true) {
                // [T-sandbox-sweep-backoff] The supervisor ticked every 400ms
                // forever — a full /proc walk plus per-pid cgroup writes even
                // when the tracked set had not changed for hours (sustained
                // 600-1000 procfs reads/s for the whole session lifetime).
                // The interval now adapts: a sweep whose observed workload
                // fingerprint (roots, tree sizes, pending deadlines) is
                // unchanged doubles the sleep up to 2s; ANY change resets to
                // the fast 400ms cadence. Deadline and memory-pressure checks
                // ride the same loop — at worst 2s late instead of 400ms,
                // which is immaterial against the 600s tool walls.
                var sleepMs = SWEEP_MS
                var lastFingerprint = -1L
                while (true) {
                    try {
                        Thread.sleep(sleepMs)
                        val fingerprint = sweep()
                        if (fingerprint == lastFingerprint) {
                            sleepMs = (sleepMs * 2).coerceAtMost(SWEEP_MAX_MS)
                        } else {
                            lastFingerprint = fingerprint
                            sleepMs = SWEEP_MS
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "supervisor: ${t.message}")
                    }
                }
            }
        }
    }

    /** [T-sandbox-sweep-backoff] Root/tree/deadline fingerprint for the adaptive sweep interval. */
    private fun sweep(): Long {
        val live = roots.values.toList()
        if (live.isEmpty()) return 0L
        val now = SystemClock.elapsedRealtime()
        var owned = 0
        var fp = 1125899906842597L
        // ONE /proc walk for the whole sweep: every root's collect/sameGroup
        // runs against the same tables (per-root discover() used to redo the
        // full walk). The per-root inputs (rootPid) and outputs (the same
        // collect+sameGroup set union) are identical to the old per-root
        // discover() calls.
        val snapshot = snapshotProc()
        // [T-job-tree-spare] The process cap counts accidental leakage, not
        // the model's own background jobs: owned excludes every registered
        // job tree, so a legitimate background workload cannot trip the cap
        // and nuke the shell with it. confine still applies to jobs — they
        // should also yield CPU to the app.
        val jobTree = LinkedHashSet<Int>()
        for (jobRoot in JobRegistry.activeJobPids()) {
            if (jobRoot > 0) jobTree.addAll(ProcessTree.collect(jobRoot, snapshot.links))
        }
        val ownedPids = HashSet<Int>()
        for (root in live) {
            if (!File("/proc/${root.pid}").exists()) {
                roots.remove(root.pid)
                fp = fp * 31 + root.pid
                continue
            }
            val current = snapshot.collect(root.pid)
            val alive = current.filter { File("/proc/$it").exists() }.toSet()
            val set = root.sticky.observe(current, alive)
            owned += (set - jobTree).size
            ownedPids.addAll(set)
            for (pid in set) confine(pid)
            if (root.deadlineAt > 0L && now >= root.deadlineAt) {
                Log.w(TAG, "deadline pid=${root.pid} label=${root.label}")
                releasePid(root.pid, kill = true, reason = "deadline:${root.label}")
            }
            // [T-sandbox-sweep-backoff] Fingerprint input: root pid + sticky
            // tree size + deadline presence — workload SHAPE, so a quiet
            // session backs off and a spawning one stays fast. Membership
            // churn inside a stable shape is bounded by the 30s confine
            // re-check instead of forcing the fast tick.
            fp = fp * 31 + root.pid
            fp = fp * 31 + set.size.toLong()
            fp = fp * 31 + (if (root.deadlineAt > 0L) 1L else 0L)
        }
        // [T-sandbox-sweep-backoff] Drop confine-stamp entries for pids that
        // left every live tree — hours of short-lived command churn would
        // otherwise grow the map monotonically for the process lifetime.
        synchronized(confinedAt) { confinedAt.keys.retainAll(ownedPids) }
        if (GuestWorkloadPolicy.exceedsProcessCap(owned)) {
            stopUnprotected("process-cap owned=$owned")
            return 0L
        }
        val ctx = appContext
        if (ctx != null && SandboxMemoryPressure.isCritical(ctx) && roots.isNotEmpty()) {
            stopUnprotected("memory-pressure")
        }
        return fp
    }
}
