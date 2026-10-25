package com.openminis.app.sandbox

import android.content.Context
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
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
        repeat(KILL_PASSES) {
            val now = discover(pid)
            victims = sticky.observe(now, now)
            for (victim in victims) {
                if (victim == pid) continue
                signal(victim)
            }
        }
        Log.w(TAG, "kill reason=$reason root=$pid victims=${victims.joinToString(",")}")
        runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
        for (victim in sticky.snapshot()) signal(victim)
    }

    private fun signal(pid: Int) {
        if (pid <= 0) return
        runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        runCatching { AndroidProcess.killProcess(pid) }
    }

    private fun adopt(pid: Int) {
        val failed = runCatching {
            val method = Os::class.java.getMethod("setpgid", Integer.TYPE, Integer.TYPE)
            method.invoke(null, pid, pid)
        }.exceptionOrNull()
        if (failed != null) Log.w(TAG, "setpgid $pid failed: ${failed.message}")
    }

    private fun confine(pid: Int) {
        runCatching {
            val method = AndroidProcess::class.java.getMethod(
                "setProcessGroup",
                Integer.TYPE,
                Integer.TYPE,
            )
            method.invoke(null, pid, 0)
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

    private fun discover(rootPid: Int): Set<Int> {
        val links = ArrayList<ProcLink>()
        val groups = HashMap<Int, Int>()
        val proc = File("/proc")
        val dirs = proc.listFiles() ?: return setOf(rootPid)
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val status = runCatching { File(dir, "status").readText() }.getOrNull() ?: continue
            val (ppid, tracer) = ProcessTree.parseStatusIds(status)
            links += ProcLink(pid, ppid, tracer)
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull()
            if (stat != null) groups[pid] = ProcessTree.parseStatPgrp(stat)
        }
        return ProcessTree.collect(rootPid, links) + ProcessTree.sameGroup(rootPid, groups)
    }

    private fun ensureSupervisor() {
        if (supervisorStarted) return
        synchronized(this) {
            if (supervisorStarted) return
            supervisorStarted = true
            thread(name = "SandboxWorkload", isDaemon = true) {
                while (true) {
                    try {
                        Thread.sleep(SWEEP_MS)
                        sweep()
                    } catch (t: Throwable) {
                        Log.w(TAG, "supervisor: ${t.message}")
                    }
                }
            }
        }
    }

    private fun sweep() {
        val live = roots.values.toList()
        if (live.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        var owned = 0
        for (root in live) {
            if (!File("/proc/${root.pid}").exists()) {
                roots.remove(root.pid)
                continue
            }
            val current = discover(root.pid)
            val alive = current.filter { File("/proc/$it").exists() }.toSet()
            val set = root.sticky.observe(current, alive)
            owned += set.size
            for (pid in set) confine(pid)
            if (root.deadlineAt > 0L && now >= root.deadlineAt) {
                Log.w(TAG, "deadline pid=${root.pid} label=${root.label}")
                releasePid(root.pid, kill = true, reason = "deadline:${root.label}")
            }
        }
        if (GuestWorkloadPolicy.exceedsProcessCap(owned)) {
            stopUnprotected("process-cap owned=$owned")
            return
        }
        val ctx = appContext
        if (ctx != null && SandboxMemoryPressure.isCritical(ctx) && roots.isNotEmpty()) {
            stopUnprotected("memory-pressure")
        }
    }
}
