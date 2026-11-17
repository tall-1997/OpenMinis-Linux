package com.openminis.app.sandbox

import java.io.File

/** Fail closed if procfs is incomplete. Never treat a returned command as proof of idleness. */
internal object ShellIdleSafety {

    /**
     * Proot tracers: each one is the root of a shell tree (a persistent
     * session shell or a terminal). The comm is the executed binary's name,
     * truncated to 15 chars — both spellings occur across devices/paths.
     */
    private val PROOT_COMMS = setOf("proot", "libproot.so")

    /**
     * [T-idle-reap-scope] The old rule was global and whitelist-shaped: ANY
     * own-UID process that was not bash/sh/proot/libproot failed the WHOLE
     * sweep, so one unrelated python3 (a native-offload handler, a cron job,
     * an MCP server) anywhere in the app permanently disabled idle reaping
     * and idle shells accumulated forever. The check's actual job — "is some
     * SHELL's tree busy" — is a per-shell question:
     *
     *  - a shell tree is the descendant tree of a proot/libproot tracer;
     *  - inside a shell tree, proot/libproot is a neutral pass-through;
     *  - bash/sh with children is busy (a command is running);
     *  - ANY OTHER comm inside a shell tree is busy — conservative per shell:
     *    it might be a guest tool, or the tree of a background job the
     *    registry spares from kills, so the shell must stay pinned;
     *  - an own-UID process OUTSIDE every shell tree is none of the reaper's
     *    business and is skipped;
     *  - an own-UID process whose status is unreadable still fails the sweep
     *    (fail closed, unchanged), as does anything /proc cannot answer.
     *
     * ExecutionCoordinator consumes a single global boolean, so the per-shell
     * verdicts are ANDed here: the sweep may reap only while NO shell tree is
     * busy. That is strictly more permissive than the old global whitelist
     * (unrelated processes no longer veto) and never reaps a shell with
     * anything running in it.
     */
    fun canReap(): Boolean = runCatching {
        val own = android.os.Process.myUid()
        val self = android.os.Process.myPid()
        val dirs = File("/proc").listFiles() ?: return false

        // One pass builds the own-UID picture (comm + parentage); the verdict
        // is then evaluated per shell tree below.
        val links = ArrayList<ProcLink>()
        val comms = HashMap<Int, String>()
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            if (pid == self) continue
            val status = try { File(dir, "status").readText() } catch (_: Exception) {
                // Other UIDs are hidden on Android. If the directory belongs to
                // our UID but its status is unreadable, do not reclaim.
                val uid = runCatching { android.system.Os.stat(dir.path).st_uid }.getOrNull()
                if (uid == own || uid == null && dir.exists()) return false
                continue
            }
            if (ProcfsSnapshot.parseStatusUid(status) != own) continue
            val comm = File(dir, "comm").readText().trim()
            val (ppid, tracer) = ProcessTree.parseStatusIds(status)
            links += ProcLink(pid, ppid, tracer)
            comms[pid] = comm
        }

        val shellTrees = HashSet<Int>()
        for ((pid, comm) in comms) {
            if (comm in PROOT_COMMS) shellTrees.addAll(ProcessTree.collect(pid, links))
        }

        for ((pid, comm) in comms) {
            if (pid !in shellTrees) continue // unrelated own-UID process: skip it
            when {
                comm in PROOT_COMMS -> Unit // the tracer itself: neutral pass-through
                comm == "bash" || comm == "sh" -> {
                    if (hasChildren(pid, links)) return false
                }
                // Anything else running inside a sandbox shell — a guest tool,
                // a compiler, a spared background job's python3 — busy.
                else -> return false
            }
        }
        true
    }.getOrDefault(false)

    /** Any process linked to [pid] as child or tracee, zombies included. */
    private fun hasChildren(pid: Int, links: List<ProcLink>): Boolean =
        links.any { it.pid != pid && (it.ppid == pid || it.tracerPid == pid) }
}
