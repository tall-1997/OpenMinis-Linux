package com.openminis.app.data.body

/**
 * Absolute ceilings for untrusted input. Not a row count, and not a fraction
 * of device RAM. A 12 GB tablet with gigabytes free and a phone with 66 MB
 * free share these numbers.
 */
object ResourceLimits {
    const val PREVIEW_BYTES = 8 * 1024
    const val SQL_CELL_BYTES = 64 * 1024
    const val PARSE_OUTPUT_BYTES = 256 * 1024
    const val MAX_PARSE_NODES = 256
    const val INLINE_BODY_BYTES = 2048
    const val MAX_EXPANSION_RATIO = 32
    const val MAX_DECLARED_UNCOMPRESSED = 8 * 1024 * 1024
    /** One session load may materialize at most this many preview bytes. */
    const val SESSION_PREVIEW_BUDGET = 1024 * 1024
    /**
     * Process-wide budget for concurrent untrusted expansions. A full-size
     * BodyStore read holds the returned prefix and one bounded I/O chunk at
     * the same time, so the budget must cover both without rejecting a body
     * that is otherwise within MAX_DECLARED_UNCOMPRESSED.
     */
    const val ADMIT_BUDGET_BYTES =
        MAX_DECLARED_UNCOMPRESSED.toLong() + 64L * 1024L
    const val SUBSTR_CHUNK_CHARS = 65536
    const val HEALTHY_TICK_MS = 60_000L
    /**
     * No guest address-space cap. RLIMIT_AS is the host's decision: the
     * memory-pressure policies clamp the hard limit and it survives an app
     * restart, so setting it here was either redundant or a brick.
     *
     * [T-compile-oom-dynamic] The fixed NODE_OLD_SPACE_MB=192 moved to
     * GuestLimits as a per-device formula (RAM/12, clamped 192..768) —
     * fixed caps starved big devices and never protected small ones.
     */
}
