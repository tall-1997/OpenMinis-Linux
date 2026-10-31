package com.openminis.app.offload

import com.openminis.app.sandbox.offload.SuCommand

/**
 * [T-host-su-fallback] The second execution lane behind
 * [ShizukuManager.runProcess]: when the Shizuku binder is not READY, a
 * privileged command runs through the host Magisk/KernelSU `su` binary
 * instead of failing with exit 126.
 *
 * Single choke point so every Shizuku consumer — the Shizuku offload handler
 * (pm/am/input/settings/dumpsys and the rest), the vscreen command chain,
 * accessibility recovery and restricted-settings put/get — inherits the
 * fallback with no per-caller changes. Elevation is decided by the host su
 * wrapper at execution time: an installed-but-unauthorized su returns the
 * wrapper's refusal text (e.g. "not allowed to su"), which callers surface
 * as the honest error it is.
 *
 * The guest-side `android-shizuku-cli` family keeps its existing
 * permission gates: this lane only replaces HOW a command reaches the host
 * shell, never WHICH commands may run.
 */
object HostSuFallback {

    /** Execution lane a privileged command takes. */
    enum class Lane { BINDER, HOST_SU, NONE }

    /**
     * Test seam: overridable su-binary probe. Production value walks the
     * standard Magisk/KernelSU paths; JVM tests point it at a fixture (or
     * null) because a CI runner has no host su.
     */
    @Volatile
    var suProbe: () -> String? = { SuCommand.findSuBinary() }

    /**
     * Pure decision for [ShizukuManager.runProcess]: a live Shizuku binder
     * wins, a found su binary is the fallback, and with neither the command
     * reports 126. Binary presence is NOT proof of granted elevation — a
     * found binary can still refuse at execution time; that refusal arrives
     * as the command's output/exit code.
     */
    fun lane(binderReady: Boolean, suAvailable: Boolean): Lane = when {
        binderReady -> Lane.BINDER
        suAvailable -> Lane.HOST_SU
        else -> Lane.NONE
    }

    /**
     * Pure decision for the vscreen guard: which state blocks a
     * Shizuku-routed command, and what honest message the user gets. A
     * su-equipped device is never blocked — [ShizukuManager.runProcess]
     * routes its commands through [Lane.HOST_SU] automatically.
     */
    fun guardDecision(state: ShizukuManager.State, suAvailable: Boolean): String? = when {
        state == ShizukuManager.State.READY -> null
        suAvailable -> null
        state == ShizukuManager.State.NOT_INSTALLED ->
            "Shizuku is not installed and no host su was found. Install Shizuku via " +
                "Settings -> Permissions -> Shizuku, or root with Magisk/KernelSU."
        state == ShizukuManager.State.NOT_RUNNING ->
            "Shizuku is not running and no host su was found. Start Shizuku via adb or " +
                "root, or root with Magisk/KernelSU."
        else ->
            "Minis is not authorized for Shizuku and no host su was found. Grant " +
                "permission in Settings -> Permissions -> Shizuku, or root with Magisk/KernelSU."
    }

    fun isAvailable(): Boolean = suProbe() != null

    fun suBinary(): String? = suProbe()

    /**
     * POSIX-quoted join of argv into the single command string handed to
     * `su -c`. [SuCommand.quoteArg] quotes exactly the tokens that the host
     * shell would re-split, so an argument containing spaces/quotes arrives
     * on the host as the same single argv slot it was here.
     */
    fun joinCommand(argv: Array<String>): String =
        argv.joinToString(" ") { SuCommand.quoteArg(it) }

    /**
     * Runs [argv] as root via `su -c`, confined by the same budget/guardian
     * wrapper the sandbox su path uses. Output is head+tail bounded at the
     * same 64KiB-character spec as [SuCommand.runHost] — wide enough for
     * chunked vscreen screencap base64 payloads.
     *
     * Exit 126 only when the binary vanished between [isAvailable] and
     * execution; any other code, including elevation refusals, is the host
     * wrapper's honest answer and is returned verbatim.
     */
    fun run(
        argv: Array<String>,
        timeoutMs: Long,
    ): ShizukuManager.ProcessResult {
        val su = suProbe()
            ?: return ShizukuManager.ProcessResult(
                exitCode = 126,
                stdout = "",
                stderr = "host su binary not found (Magisk/KernelSU)",
            )
        val command = SuCommand.confineHost(joinCommand(argv))
        val (exitCode, output) = SuCommand.runHost(listOf(su, "-c", command), timeoutMs)
        return ShizukuManager.ProcessResult(
            exitCode = exitCode,
            stdout = output,
            stderr = "",
        )
    }
}
