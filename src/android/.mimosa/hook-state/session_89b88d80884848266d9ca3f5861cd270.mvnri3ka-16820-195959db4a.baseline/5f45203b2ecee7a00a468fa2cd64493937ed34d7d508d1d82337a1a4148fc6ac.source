package com.openminis.app.ui.settings

/**
 * Parse a typed stepper field and clamp to [min]..[max].
 * Empty / non-integer returns null so the dialog can keep Confirm disabled.
 */
internal fun parseClampedStepperValue(raw: String, min: Int, max: Int): Int? {
    val n = raw.trim().toIntOrNull() ?: return null
    return n.coerceIn(min, max)
}
