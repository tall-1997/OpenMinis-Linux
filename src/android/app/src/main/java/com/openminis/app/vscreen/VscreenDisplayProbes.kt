package com.openminis.app.vscreen

import com.openminis.app.offload.ShizukuManager

/**
 * [T-android-vscreen-tier] ROM capability probes for the command lane,
 * extracted from VirtualScreenManager (architecture line ceiling). Both
 * probes are runtime-discovered, cached process-wide, and reset by
 * [VirtualScreenManager.resetForTest] like every other cached probe:
 *
 *  - the display-targeting form of `input` drifted across Android versions
 *    (`input -d <id> <cmd>` vs `input --display <id> <cmd>` vs none) — usage
 *    output first, then one harmless corner tap on OUR display;
 *  - whether `screencap` accepts `-d <id>` (usage output only; an
 *    inconclusive help returns null so the caller tries the capture anyway).
 */
internal object VscreenDisplayProbes {

    /** Which display-targeting form the ROM's `input` command accepts. */
    enum class InputDisplayForm { UNKNOWN, DASH_D, LONG_DISPLAY, NONE }

    @Volatile private var inputFormProbe: InputDisplayForm? = null
    @Volatile private var screencapProbe: Boolean? = null

    fun resetForTest() {
        inputFormProbe = null
        screencapProbe = null
    }

    internal fun inputDisplayForm(
        shell: (Array<String>, Long) -> ShizukuManager.ProcessResult,
        displayId: Int,
    ): InputDisplayForm {
        inputFormProbe?.let { return it }
        val help = shell(arrayOf("input"), 5_000L)
        val resolved = formFromHelp(help.combined)
            .takeIf { it != InputDisplayForm.UNKNOWN }
            ?: probeByRun(shell, displayId)
        inputFormProbe = resolved
        return resolved
    }

    /** Usage-output probe: `--display` wins over `-d`, a no-display help is NONE. */
    internal fun formFromHelp(help: String): InputDisplayForm {
        if (help.isBlank()) return InputDisplayForm.UNKNOWN
        val lower = help.lowercase()
        return when {
            lower.contains("--display") -> InputDisplayForm.LONG_DISPLAY
            !lower.contains("display") -> InputDisplayForm.NONE
            Regex("-d\\b").containsMatchIn(help) -> InputDisplayForm.DASH_D
            else -> InputDisplayForm.NONE
        }
    }

    /**
     * The ROM printed no usage text — probe by running one harmless tap at
     * (1,1) on OUR display in each known form; the first that exits 0 wins.
     */
    private fun probeByRun(
        shell: (Array<String>, Long) -> ShizukuManager.ProcessResult,
        displayId: Int,
    ): InputDisplayForm {
        for (form in listOf(InputDisplayForm.DASH_D, InputDisplayForm.LONG_DISPLAY)) {
            val argv = when (form) {
                InputDisplayForm.DASH_D ->
                    arrayOf("input", "-d", displayId.toString(), "tap", "1", "1")
                else ->
                    arrayOf("input", "--display", displayId.toString(), "tap", "1", "1")
            }
            if (shell(argv, 5_000L).exitCode == 0) return form
        }
        return InputDisplayForm.NONE
    }

    /** Probe: does this ROM's screencap accept `-d`? Null = inconclusive (try the capture). */
    internal fun screencapSupportsDisplayFlag(
        shell: (Array<String>, Long) -> ShizukuManager.ProcessResult,
    ): Boolean? {
        screencapProbe?.let { return it }
        val r = shell(arrayOf("screencap", "-h"), 5_000L)
        val text = r.combined
        if (text.isBlank()) return null
        val supported = text.contains("-d") && text.lowercase().contains("display")
        screencapProbe = supported
        return supported
    }
}
