package com.openminis.app.vscreen

/**
 * [T-android-vscreen2] Spillover recovery (best effort, DSH
 * AGENT-SCREEN-V2.md §2 item 4), split out of [VirtualScreenManager]
 * (architecture ratchet): within [SPILL_WINDOW_MS] after a launch on an
 * agent screen, if the main display's top task changes to an app the USER
 * did not open themselves, move it back to the agent screen and re-mute it.
 *
 * Runs on a daemon thread; the outcome (or the honest skip reason) lands on
 * [VirtualScreenManager.VirtualScreen.spillbackNote] for the next tool
 * result, and [consumeNote] hands that note to the tool surface exactly once.
 *
 * LIMITATIONS, by design: without an accessibility signal we cannot tell a
 * user-initiated launch from an app-initiated jump in the window, so a user
 * who opens an app themselves within 3s of an agent launch may get that app
 * pulled back (rare; the note says what happened). System chooser / resolver
 * / launcher / systemui tasks are left alone. When the task-move backend is
 * unavailable the watcher skips with a note instead of guessing.
 *
 * Touches NO manager-private state: the mover / muter / controller come in
 * as parameters from the manager's current seams.
 */
internal object SpillbackWatcher {
    /** Watch window after a launch (DSH AGENT-SCREEN-V2.md §2). */
    const val SPILL_WINDOW_MS = 3_000L
    private const val SPILL_POLL_MS = 500L

    /** Tasks the spillover watcher never touches (system surfaces the user interacts with). */
    internal val SPILL_EXCLUDED = setOf(
        "com.android.systemui",
        "com.android.intentresolver",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )

    /**
     * Start the watch for [screen]. The mover/muter/controller are the
     * manager's current components (resolve at call time so test overrides
     * keep working); a null controller simply disables the waiting-handoff
     * bail-out check.
     */
    fun watch(
        screen: VirtualScreenManager.VirtualScreen,
        mover: TaskMover,
        muter: AudioMuter,
        controller: HandoffController?,
    ) {
        screen.spillbackWatching = true
        Thread {
            try {
                val before = when (val o = mover.listTasks()) {
                    is TaskMover.ListOutcome.Rows ->
                        o.rows.firstOrNull { it.displayId == VirtualScreenManager.MAIN_DISPLAY_ID }?.taskId
                    is TaskMover.ListOutcome.Unavailable -> {
                        screen.spillbackNote =
                            "spillover recovery skipped: ${o.message}"
                        return@Thread
                    }
                }
                val deadline = System.currentTimeMillis() + SPILL_WINDOW_MS
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(SPILL_POLL_MS)
                    // A waiting handoff EXPECTS an app on the main display — do not fight it.
                    if (controller?.noteFor(screen.displayId)?.contains("WAITING") == true) return@Thread
                    val rows = when (val o = mover.listTasks()) {
                        is TaskMover.ListOutcome.Rows -> o.rows
                        is TaskMover.ListOutcome.Unavailable -> return@Thread
                    }
                    val top = rows.firstOrNull { it.displayId == VirtualScreenManager.MAIN_DISPLAY_ID } ?: continue
                    if (top.taskId == before) continue
                    if (top.packageName in SPILL_EXCLUDED || top.packageName.contains("launcher")) continue
                    val moved = mover.move(top.taskId, screen.displayId)
                    if (moved is TaskMover.MoveOutcome.Moved) {
                        val muteNote = runCatching { muter.mute(screen.displayId, top.packageName) }.getOrNull()
                        screen.spillbackNote =
                            "${top.packageName} opened on the user's screen during this action and was " +
                                "moved back to display ${screen.displayId}" +
                                (muteNote?.takeIf { it.startsWith("audio muted") }?.let { "; $it" } ?: "")
                    } else {
                        screen.spillbackNote =
                            "${top.packageName} opened on the user's screen during this action; " +
                                "moving it back failed (${moved.javaClass.simpleName})"
                    }
                    return@Thread
                }
            } catch (t: InterruptedException) {
                return@Thread
            } finally {
                screen.spillbackWatching = false
            }
        }.apply { isDaemon = true; name = "minis-vscreen-spillback" }.start()
    }

    /**
     * Consumes the screen's pending spillover note exactly once. The caller
     * (the manager) supplies the screen under its own lock.
     */
    fun consumeNote(screen: VirtualScreenManager.VirtualScreen?): String? {
        screen ?: return null
        return screen.spillbackNote.also { screen.spillbackNote = null }
    }
}
