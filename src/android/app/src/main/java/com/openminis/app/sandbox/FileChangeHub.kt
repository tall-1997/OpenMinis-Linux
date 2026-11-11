package com.openminis.app.sandbox

import android.content.Context
import android.os.FileObserver
import com.openminis.app.di.AppGraph
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [T-file-hub] inotify-backed file change monitoring for a session's guest
 * trees — not only the workspace.
 *
 * The missing piece aicode has (FileChangeHub) and OpenMinis did not: a push
 * channel for "something this session can see changed". Groundwork for
 * file-sync features and live UI refresh — the first consumer is the Git
 * panel, which re-probes status when working-tree files change instead of
 * only on manual pull-to-refresh.
 *
 * Watched roots (per session, see [watchRoots]):
 * - `/var/minis/workspace` — the session/project working tree (the original
 *   root; the registration fails without it, same contract as before);
 * - `/var/minis/memory` — this session's daily logs;
 * - `/var/minis/mounts/<name>` for every user-mounted external folder the
 *   sandbox ALREADY binds (MountedFoldersStore entries with a resolved host
 *   path) — bind-only, nothing here activates or creates a mount;
 * - `/var/minis/mounts/sdcard` — shared storage, but only when the sandbox
 *   actually binds that guest prefix (All Files Access granted AND no user
 *   mount already named "sdcard" — otherwise the prefix belongs to the user's
 *   own mount, which the mounts root above already covers).
 *
 * Mechanics:
 * - [FileObserver] is per-directory, so a watch registers one observer per
 *   subdirectory of each root (host side of the PRoot bind — guest writes
 *   land on the same inodes, so inotify sees agent edits), bounded by
 *   [MAX_OBSERVERS]/[MAX_DEPTH] and extended at runtime when new directories
 *   appear (CREATE + IN_ISDIR). The budget is spent root by root in
 *   [watchRoots] order, so a huge external tree can never starve the
 *   workspace root of observers.
 * - Raw events are noisy (editors emit bursts; AtomicFileWrite emits
 *   tmp-create plus rename). Everything goes through [FileEventCoalescer]:
 *   per-path quiet-window merging, tmp-name drops, DELETE dominance,
 *   create+delete cancellation, pending cap.
 * - One shared ticker drains due events into per-session SharedFlows
 *   (tryEmit + DROP_OLDEST: a watcher that cannot keep up must never apply
 *   backpressure to inotify delivery).
 *
 * Consumer contract — UNCHANGED by the root expansion: [eventsFor] still
 * yields "a guest path changed". Each [FileChangeEvent.guestPath] is a valid
 * guest path under /var/minis/ (workspace, memory, mounts/<name>,
 * mounts/sdcard) for the changed inode — only the set of prefixes grew. The
 * Git panel refreshes on any event with a 2s floor + busy-skip, so broader
 * coverage means more refresh candidates, never a different meaning;
 * consumers that want one subtree can prefix-match on guestPath as before.
 * NB: a KDoc must never contain a bare comment-opener sequence (slash-star)
 * — Kotlin block comments nest, so it silently comments out the rest of the
 * file.
 */
object FileChangeHub {

    const val GUEST_ROOT = "/var/minis/workspace"

    /** `/var/minis/memory` — this chat's daily logs; per-session, never shared. */
    const val GUEST_MEMORY = "/var/minis/memory"

    private const val MAX_OBSERVERS = 64
    private const val MAX_DEPTH = 6
    private const val TICK_MS = 200L

    /** inotify IN_ISDIR — not exposed as a public FileObserver constant. */
    private const val IN_ISDIR = 0x40000000

    private val MASK =
        FileObserver.CREATE or FileObserver.MODIFY or FileObserver.DELETE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

    /**
     * Churn-heavy generated trees — watching them burns the observer cap for
     * nothing. `.git` is skipped deliberately: consumers run git commands
     * (status/commit rewrite index+refs constantly), so watching it would
     * feed the panel's own probes back in as change events. Working-tree
     * edits — the signal that actually matters — still fire.
     */
    private val SKIP_DIR_NAMES = setOf("node_modules", ".gradle", ".cxx", ".git")

    data class FileChangeEvent(
        val sessionId: String,
        /** Guest path, e.g. /var/minis/workspace/src/main.py or /var/minis/mounts/sdcard/DCIM/img.jpg */
        val guestPath: String,
        val kind: FileEventCoalescer.Kind,
        val isDir: Boolean,
        /** How many raw inotify events merged into this one. */
        val mergedCount: Int,
        val atMs: Long,
    )

    /**
     * One watched host directory and the GUEST prefix its events publish
     * under — the hostPath→guestPrefix mapping table. Event translation
     * ([guestPathOf]) reads the prefix from here instead of hardcoding
     * [GUEST_ROOT], so a memory edit surfaces as `/var/minis/memory/...` and
     * an sdcard edit as `/var/minis/mounts/sdcard/...` without any
     * root-specific branches in the event path.
     */
    internal data class WatchRoot(val host: File, val guestPrefix: String)

    private class Registration(
        val sessionId: String,
        val roots: List<WatchRoot>,
        val coalescer: FileEventCoalescer,
        val observers: ConcurrentHashMap<File, FileObserver>,
    )

    private val registrations = ConcurrentHashMap<String, Registration>()
    private val flows = ConcurrentHashMap<String, MutableSharedFlow<FileChangeEvent>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var ticker: Job? = null

    /**
     * Start watching a session's guest trees. Idempotent; returns false when
     * the workspace host directory does not exist (sandbox not provisioned
     * yet) — callers may retry later. The extra roots (memory, mounts,
     * sdcard) are best-effort: each is registered only when its host
     * directory currently exists, and all share the one [MAX_OBSERVERS]
     * budget of the registration, deduplicated by host path.
     */
    fun watch(sessionId: String, context: Context): Boolean {
        if (sessionId.isBlank()) return false
        if (registrations.containsKey(sessionId)) return true
        val roots = watchRoots(sessionId, context)
        if (roots.none { it.guestPrefix == GUEST_ROOT }) return false
        val reg = Registration(sessionId, roots, FileEventCoalescer(), ConcurrentHashMap())
        registrations[sessionId] = reg
        for (root in roots) addTree(reg, root, root.host, 0)
        ensureTicker()
        return true
    }

    fun unwatch(sessionId: String) {
        registrations.remove(sessionId)?.observers?.values?.forEach {
            runCatching { it.stopWatching() }
        }
        // The flow dies with the registration: an entry that only ever grows
        // meant every session ever watched kept a MutableSharedFlow forever.
        flows.remove(sessionId)
        synchronized(this) {
            if (registrations.isEmpty() && ticker?.isActive == true) {
                // No watchers left — park the 200ms ticker. watch() restarts it
                // via ensureTicker(). A cancelled job's isActive is false, so
                // the double-checked relaunch works unchanged.
                ticker?.cancel()
                ticker = null
            }
        }
    }

    /** Change events for one session. Safe to collect before [watch]. */
    fun eventsFor(sessionId: String): Flow<FileChangeEvent> =
        flowFor(sessionId).asSharedFlow()

    private fun flowFor(sessionId: String): MutableSharedFlow<FileChangeEvent> {
        // [T-computeifabsent-agreement] computeIfAbsent (atomic) rather than
        // getOrPut (get→compute→put, NOT atomic) or putIfAbsent (which forces
        // an eager allocation even when the key is already present): the ticker
        // (Default) and eventsFor() (Main) race here — a last-writer-wins put
        // could strand a collector on a flow instance the ticker never emits
        // into. Same guarantee ExecutionCoordinator's mutex map relies on, so
        // the two files now agree on one primitive.
        return flows.computeIfAbsent(sessionId) {
            MutableSharedFlow<FileChangeEvent>(
                replay = 0,
                extraBufferCapacity = 128,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
    }

    // ------------------------------------------------------------ internals

    /**
     * Host roots to watch for one session, in priority order (the observer
     * budget is spent top-down, workspace first):
     *  1. workspace — `/var/minis/workspace`, the original root;
     *  2. memory — `/var/minis/memory`, resolved through the same
     *     [SessionWorkspace.hostDir] the bind uses, so the watch is always on
     *     the exact directory the guest sees;
     *  3. user-mounted external folders — every MountedFoldersStore entry the
     *     sandbox binds at `/var/minis/mounts/<name>`;
     *  4. sdcard — `/var/minis/mounts/sdcard`, only when that bind exists.
     *
     * Deduplicated by host path: a plan that maps two guest prefixes onto one
     * directory registers a single observer tree, published under the first
     * prefix. When binds NEST (a user mount inside the sdcard tree) both
     * trees are watched; a change deep inside may then be reported under the
     * outer prefix — still a valid guest path for the same inode, so the
     * consumer contract above holds.
     */
    private fun watchRoots(sessionId: String, context: Context): List<WatchRoot> {
        val filesDir = context.filesDir
        val byHost = LinkedHashMap<String, WatchRoot>()
        fun add(host: File, guestPrefix: String) {
            if (!host.isDirectory) return
            byHost.putIfAbsent(host.absolutePath, WatchRoot(host, guestPrefix))
        }
        PRootKernel.resolveSessionHostPath(sessionId, GUEST_ROOT, context)?.let { add(it, GUEST_ROOT) }
        add(SessionWorkspace.hostDir(filesDir, sessionId, "memory"), GUEST_MEMORY)
        for ((linuxDir, hostPath) in mountedFolderBinds()) add(File(hostPath), linuxDir)
        PRootKernel
            .shellSharedStorageBinds(context, publish = false)[SharedStorageBindPlan.MOUNTS_SDCARD]
            ?.let { add(File(it), SharedStorageBindPlan.MOUNTS_SDCARD) }
        return byHost.values.toList()
    }

    /**
     * Already-bound external folders as `(guest prefix, host path)` pairs.
     * [MountedFolderCoordinator.bindMountSpecs] is the same snapshot PRoot's
     * `-b` argv is seeded from, so the watch set equals the bind set: entries
     * whose SAF tree URI failed to resolve (cloud providers, ejected volumes)
     * are absent from both. A pure StateFlow read — never mounts, never
     * touches disk beyond the caller's own isDirectory checks.
     */
    private fun mountedFolderBinds(): List<Pair<String, String>> {
        val store = AppGraph.mountedFoldersStoreOrNull() ?: return emptyList()
        return runCatching { MountedFolderCoordinator.bindMountSpecs(store) }.getOrDefault(emptyList())
    }

    private fun addTree(reg: Registration, root: WatchRoot, dir: File, depth: Int) {
        if (depth > MAX_DEPTH) return
        // A queued CREATE-dir expansion can outlive unwatch(); never attach
        // observers to a deregistered session (they would leak forever).
        if (registrations[reg.sessionId] !== reg) return
        attachObserver(reg, root, dir)
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (c in children) {
            if (!c.isDirectory || shouldSkipDir(c)) continue
            if (reg.observers.size >= MAX_OBSERVERS) return
            addTree(reg, root, c, depth + 1)
        }
    }

    private fun attachObserver(reg: Registration, root: WatchRoot, dir: File) {
        if (reg.observers.size >= MAX_OBSERVERS) return
        if (reg.observers.containsKey(dir)) return
        @Suppress("DEPRECATION") // String-path ctor: works on every API level.
        val obs = object : FileObserver(dir.absolutePath, MASK) {
            override fun onEvent(event: Int, path: String?) {
                runCatching { handleEvent(reg, root, dir, event, path) }
            }
        }
        reg.observers[dir] = obs
        obs.startWatching()
    }

    private fun handleEvent(reg: Registration, root: WatchRoot, dir: File, event: Int, path: String?) {
        val isDirEvent = (event and IN_ISDIR) != 0
        if (path == null) {
            // DELETE_SELF / MOVE_SELF: the watched directory itself went away.
            if (dir != root.host) {
                reg.observers.remove(dir)?.let { runCatching { it.stopWatching() } }
            }
            reg.coalescer.offer(guestDirPathOf(root, dir), FileEventCoalescer.Kind.DELETE, true)
            return
        }
        val child = File(dir, path)
        val kind = when {
            (event and FileObserver.CREATE) != 0 -> FileEventCoalescer.Kind.CREATE
            (event and FileObserver.MODIFY) != 0 -> FileEventCoalescer.Kind.MODIFY
            (event and FileObserver.DELETE) != 0 -> FileEventCoalescer.Kind.DELETE
            (event and FileObserver.MOVED_TO) != 0 -> FileEventCoalescer.Kind.CREATE
            (event and FileObserver.MOVED_FROM) != 0 -> FileEventCoalescer.Kind.DELETE
            else -> return
        }
        if (kind == FileEventCoalescer.Kind.CREATE && isDirEvent && !shouldSkipDir(child)) {
            scope.launch { addTree(reg, root, child, depthOf(root, child)) }
        }
        if (kind == FileEventCoalescer.Kind.DELETE && isDirEvent) {
            reg.observers.remove(child)?.let { runCatching { it.stopWatching() } }
        }
        reg.coalescer.offer(guestPathOf(root, dir, path), kind, isDirEvent)
    }

    private fun depthOf(root: WatchRoot, dir: File): Int =
        runCatching { dir.relativeTo(root.host).path.count { it == File.separatorChar } }
            .getOrDefault(MAX_DEPTH + 1)

    private fun relOf(root: WatchRoot, dir: File): String =
        runCatching { dir.relativeTo(root.host).path }.getOrDefault("")

    private fun guestPathOf(root: WatchRoot, dir: File, name: String): String {
        val rel = relOf(root, dir)
        return if (rel.isEmpty()) "${root.guestPrefix}/$name" else "${root.guestPrefix}/$rel/$name"
    }

    private fun guestDirPathOf(root: WatchRoot, dir: File): String {
        val rel = relOf(root, dir)
        return if (rel.isEmpty()) root.guestPrefix else "${root.guestPrefix}/$rel"
    }

    private fun shouldSkipDir(dir: File): Boolean = dir.name in SKIP_DIR_NAMES

    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        synchronized(this) {
            if (ticker?.isActive == true) return
            ticker = scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    if (registrations.isEmpty()) continue
                    for (reg in registrations.values) {
                        val due = reg.coalescer.drainDue()
                        if (due.isEmpty()) continue
                        // Skip a registration removed mid-drain: unwatch() also
                        // removed its flow, and flowFor() here would recreate an
                        // orphan that nothing will ever emit into again.
                        if (registrations[reg.sessionId] !== reg) continue
                        val flow = flowFor(reg.sessionId)
                        for (e in due) {
                            flow.tryEmit(
                                FileChangeEvent(
                                    sessionId = reg.sessionId,
                                    guestPath = e.relPath,
                                    kind = e.kind,
                                    isDir = e.isDir,
                                    mergedCount = e.count,
                                    atMs = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
