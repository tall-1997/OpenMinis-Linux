package com.openminis.app.vscreen.tier

/**
 * [T-android-vscreen-tier] Input clamping for a create request, extracted
 * verbatim from VirtualScreenManager (line ceiling) as pure functions.
 * Defaults: 1080×1920@320; edges clamp to [320, 4096], dpi clamps to
 * [120, 640] (2000dpi reads as 640) with 0 meaning "unspecified → default".
 */
object CreateRequestSanitizer {
    const val DEFAULT_WIDTH = 1080
    const val DEFAULT_HEIGHT = 1920
    const val DEFAULT_DPI = 320

    private const val MIN_EDGE = 320
    private const val MAX_EDGE = 4096
    private const val MIN_DPI = 120
    private const val MAX_DPI = 640

    fun sanitize(width: Int, height: Int, dpi: Int, label: String?): ProviderCreateRequest =
        ProviderCreateRequest(
            label = (label ?: "minis-virtual-screen").take(64),
            width = clampEdge(width, DEFAULT_WIDTH),
            height = clampEdge(height, DEFAULT_HEIGHT),
            dpi = when {
                dpi == 0 -> DEFAULT_DPI
                dpi < MIN_DPI -> MIN_DPI
                dpi > MAX_DPI -> MAX_DPI
                else -> dpi
            },
        )

    private fun clampEdge(value: Int, default: Int): Int = when {
        value <= 0 -> default
        value < MIN_EDGE -> MIN_EDGE
        value > MAX_EDGE -> MAX_EDGE
        else -> value
    }
}
