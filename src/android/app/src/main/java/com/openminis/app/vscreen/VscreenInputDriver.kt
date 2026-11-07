package com.openminis.app.vscreen

import com.openminis.app.offload.ShizukuManager

/**
 * [T-android-vscreen-tier] The command-lane input driver, extracted from
 * [VirtualScreenManager] (architecture line ceiling). Public surface is
 * unchanged: the actions stay object-extension members, so callers keep
 * writing `VirtualScreenManager.tap(...)` verbatim.
 *
 * The display-targeting form of `input` is probed at runtime (see
 * [VscreenDisplayProbes]) and cached; a ROM without display targeting gets
 * [VirtualScreenManager.CommandOutcome.NotSupported] with the
 * accessibility-stack fallback hint instead of a guess.
 */

/** back / home / enter mapped to the universally-safe numeric keycodes. */
private val KEYCODES = mapOf("back" to 4, "home" to 3, "enter" to 66)

fun VirtualScreenManager.tap(displayId: Int, x: Int, y: Int): VirtualScreenManager.CommandOutcome =
    driveInput(displayId, listOf("tap", x.toString(), y.toString()))

fun VirtualScreenManager.swipe(
    displayId: Int,
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
    durationMs: Int?,
): VirtualScreenManager.CommandOutcome {
    val sub = mutableListOf("swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString())
    if (durationMs != null && durationMs > 0) sub.add(durationMs.toString())
    return driveInput(displayId, sub)
}

fun VirtualScreenManager.text(displayId: Int, text: String): VirtualScreenManager.CommandOutcome {
    if (text.isBlank()) {
        return VirtualScreenManager.CommandOutcome.Failed("text is required for the text action")
    }
    // `input text` maps %s to a space on older ROMs where a literal space
    // breaks the argv; newer ROMs accept both forms.
    return driveInput(displayId, listOf("text", text.replace(" ", "%s")))
}

fun VirtualScreenManager.key(displayId: Int, key: String): VirtualScreenManager.CommandOutcome {
    val code = KEYCODES[key.trim().lowercase()] ?: key.trim().toIntOrNull()
    if (code == null) {
        return VirtualScreenManager.CommandOutcome.Failed(
            "key must be back, home, enter, or a numeric Android keycode",
        )
    }
    return driveInput(displayId, listOf("keyevent", code.toString()))
}

/**
 * Inject input through Shizuku `input`. The display-targeting flag drifted
 * across Android versions (`input -d <id> <cmd>` vs `--display` vs none), so
 * the form is PROBED at runtime (usage output first; one harmless corner tap
 * on OUR display when the ROM prints no usage) and cached. A ROM without
 * display targeting returns [VirtualScreenManager.CommandOutcome.NotSupported]
 * with the accessibility-stack fallback hint instead of guessing.
 */
private fun VirtualScreenManager.driveInput(
    displayId: Int,
    sub: List<String>,
): VirtualScreenManager.CommandOutcome {
    val s = screen(displayId) ?: return VirtualScreenManager.CommandOutcome.NotFound(notFoundMessage(displayId))
    touch(s)
    shizukuUnavailableMessage()?.let {
        return VirtualScreenManager.CommandOutcome.ShizukuUnavailable(it)
    }
    val outOfBounds = boundsMessage(s, sub)
    if (outOfBounds != null) return VirtualScreenManager.CommandOutcome.Failed(outOfBounds)
    val form = inputDisplayForm(displayId)
    if (form == VscreenDisplayProbes.InputDisplayForm.NONE) {
        return VirtualScreenManager.CommandOutcome.NotSupported(
            "this ROM's `input` command has no display targeting (probed via its usage " +
                "output and a probe tap) — injections always land on the physical screen, " +
                "so they were refused. Fallbacks: the accessibility stack (ui_read / " +
                "ui_action) if the Accessibility permission is granted, or " +
                "shell_execute -> android-shizuku-cli exec 'input -d <id> <cmd>'.",
        )
    }
    val argv = when (form) {
        VscreenDisplayProbes.InputDisplayForm.DASH_D ->
            (arrayOf("input", "-d", displayId.toString()) + sub.toTypedArray())
        VscreenDisplayProbes.InputDisplayForm.LONG_DISPLAY ->
            (arrayOf("input", "--display", displayId.toString()) + sub.toTypedArray())
        else -> return VirtualScreenManager.CommandOutcome.NotSupported("input display form unresolved")
    }
    val r = runShell(argv, 8_000L)
    return if (r.exitCode == 0) {
        VirtualScreenManager.CommandOutcome.Ok(displayId, "injected ${sub.joinToString(" ")} on display $displayId")
    } else {
        VirtualScreenManager.CommandOutcome.Failed(
            "input on display $displayId failed: ${r.combined.ifBlank { "exitCode=${r.exitCode}" }}",
        )
    }
}

/**
 * Coordinates are model-supplied and must be plain integers in range —
 * the virtual display's coordinate space is exactly the size it was
 * created with, so an out-of-range tap is a wasted round trip reported
 * honestly instead of silently clamped by the display.
 */
private fun boundsMessage(s: VirtualScreenManager.VirtualScreen, sub: List<String>): String? {
    var i = 0
    while (i < sub.size) {
        if (sub[i] == "tap" || sub[i] == "swipe") {
            val count = if (sub[i] == "tap") 2 else 4
            if (i + count >= sub.size) return null
            // Coordinates alternate x/y starting at j=1: odd = x (width), even = y (height).
            for (j in 1..count) {
                val v = sub[i + j].toIntOrNull() ?: return null
                val axis = if (j % 2 == 1) s.width else s.height
                if (v < 0 || v > axis) {
                    return "coordinate ${sub[i + j]} falls outside virtual screen " +
                        "${s.width}x${s.height} (display ${s.displayId}) — " +
                        "check the latest screenshot's dimensions"
                }
            }
            i += count
        }
        i++
    }
    return null
}
