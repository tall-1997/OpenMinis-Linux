package com.openminis.app.sandbox

import android.util.Log

/**
 * [T-shell-stall-yes-head] Starts agent shell processes with SIGPIPE unblocked.
 *
 * ART blocks SIGPIPE in every app thread (Runtime::BlockSignals), and
 * ProcessBuilder's child keeps the spawning thread's signal mask across
 * execve, through proot, into every guest process. Nothing on the way unblocks
 * it (busybox sh does not), so a writer whose reader had exited got EPIPE
 * instead of being killed. Most tools then exit on the error, but busybox
 * `yes STRING | head -c N` never notices it and spins forever: head exits,
 * yes keeps holding the merged stderr pipe open, and the shell_execute call
 * hangs until its timeout (15 min by default). `busybox ip addr` in a pipeline
 * stalls the same way. Seen on a Pixel 4a as
 * "yes ... | head -c 50000 stuck for 6+ minutes"; reproduced on stock
 * Alpine 3.21 busybox by blocking SIGPIPE in the parent.
 *
 * The interactive terminal is unaffected: its PTY is forked from native code
 * that starts from a clean mask. ProcessBuilder offers no such hook, so the
 * mask is cleared on the SPAWNING thread for the duration of start() and
 * restored right after. start() does not suspend, so the whole window stays on
 * one thread; the parent side only reads the exec-failure pipe, so no write of
 * this thread can raise SIGPIPE in the window. SIGQUIT / SIGUSR1 stay blocked
 * on purpose: they are process-directed, and an unblocked thread could steal
 * one meant for ART's signal catcher.
 *
 * [port-upstream-1.16] Port of OpenMinis SpawnSignalMask.kt; the native bits
 * live in sigpipe_bridge.c (upstream keeps them in pty_bridge.c, which this
 * tree does not carry).
 */
object SpawnSignalMask {
    private const val TAG = "SpawnSignalMask"

    /** Bit set returned by [nativeUnblockSigpipe]. */
    private const val WAS_BLOCKED = 1
    private const val DISPOSITION_IGNORED = 2

    private var nativeLoaded = false

    init {
        nativeLoaded = runCatching { System.loadLibrary("sigpipe_bridge") }.isSuccess
        if (!nativeLoaded) Log.w(TAG, "libsigpipe_bridge unavailable; children keep the inherited mask")
    }

    private external fun nativeUnblockSigpipe(): Int

    private external fun nativeBlockSigpipe(): Int

    /** Start [pb] with SIGPIPE unblocked in the child. */
    fun start(pb: ProcessBuilder): Process = withSigpipeUnblocked { pb.start() }

    /**
     * Run [block] with SIGPIPE unblocked on this thread, then restore the
     * previous mask. A native failure never stops the spawn: the command
     * then runs as it always did.
     */
    fun <T> withSigpipeUnblocked(block: () -> T): T {
        var state = -1
        if (nativeLoaded) {
            state = runCatching { nativeUnblockSigpipe() }.getOrElse {
                Log.w(TAG, "SIGPIPE unblock threw: ${it.javaClass.simpleName}")
                -1
            }
        }
        if (state < 0) {
            Log.w(TAG, "SIGPIPE unblock failed ($state); spawning with the inherited mask")
        } else if (state and DISPOSITION_IGNORED != 0) {
            Log.w(TAG, "SIGPIPE is SIG_IGN in this process; children inherit it and `yes | head` can still hang")
        }
        try {
            return block()
        } finally {
            if (state >= 0 && state and WAS_BLOCKED != 0) {
                val rc = runCatching { nativeBlockSigpipe() }.getOrElse { -1 }
                if (rc != 0) Log.e(TAG, "SIGPIPE re-block failed ($rc)")
            }
        }
    }
}
