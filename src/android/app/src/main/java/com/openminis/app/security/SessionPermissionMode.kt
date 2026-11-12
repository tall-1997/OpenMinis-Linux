package com.openminis.app.security

/**
 * [#31] Session-facing tool-gate tier. Four levels of agent autonomy, from
 * most to least restrictive:
 *
 *  - [OFF]      — every tool call is refused outright (including reads).
 *  - [ASK]      — every tool call asks the user first (strict asking; reads
 *                 included). "每次询问".
 *  - [SAFE_AUTO]— read-only / explicitly-safe tools run without prompting;
 *                 everything else asks. This tier reproduces the behaviour
 *                 that the two-tier era called "ASK" (read-only auto-allow +
 *                 low-risk shell auto-run), so it is the faithful
 *                 interpretation of a legacy row.
 *  - [YOLO]     — everything runs automatically except fatal-confirm.
 *
 * The gate itself still decides in terms of [PermissionMode]; [toGateMode]
 * is the only mapping point. Persistence is the `sessions.permission_mode`
 * String column — no DB migration needed, the tokens are free-form.
 */
enum class SessionPermissionMode {
    OFF,
    ASK,
    SAFE_AUTO,
    YOLO,
    ;

    fun isYolo(): Boolean = this == YOLO

    /** Gate-level mode this tier compiles to. Single mapping point. */
    fun toGateMode(): PermissionMode = when (this) {
        OFF -> PermissionMode.DENY_ALL
        ASK -> PermissionMode.ASK_ALL
        SAFE_AUTO -> PermissionMode.ASK
        YOLO -> PermissionMode.ALLOW_ALL
    }

    /** Canonical token written to the `permission_mode` column. */
    fun toStoredToken(): String = when (this) {
        // "ASK" already means the LEGACY auto-allow-reads behaviour on disk;
        // the strict ask-everything tier gets its own token so a write-read
        // round trip never widens or narrows the tier.
        ASK -> "ASK_ALL"
        else -> name
    }

    /**
     * One-tap cycle order for the chat-menu pill: ASK → SAFE_AUTO → YOLO →
     * OFF → ASK. Ascending autonomy, wrapping through the fail-safe tier.
     */
    fun next(): SessionPermissionMode = when (this) {
        ASK -> SAFE_AUTO
        SAFE_AUTO -> YOLO
        YOLO -> OFF
        OFF -> ASK
    }

    companion object {
        /**
         * Backward-compatible read of a persisted token. "YOLO" and the
         * historical "YOYO" spelling both mean YOLO. The two-tier-era "ASK"
         * token meant "reads auto-allow, the rest asks" — exactly today's
         * [SAFE_AUTO] — so legacy rows land there, keeping every existing
         * session's behaviour unchanged. The strict tier writes "ASK_ALL".
         * "SAFE_AUTO" / "OFF" read back literally. Null, blank, the retired
         * global "ALLOW_ALL" token and any unknown value land on [ASK] so an
         * unknown token can never widen the gate (same rule the two-tier
         * `PermissionMode.sessionDefault` applied).
         */
        fun fromStored(raw: String?): SessionPermissionMode = when (raw?.trim()?.uppercase()) {
            "YOLO", "YOYO" -> YOLO
            "SAFE_AUTO" -> SAFE_AUTO
            "OFF", "DENY_ALL" -> OFF
            "ASK_ALL" -> ASK
            "ASK" -> SAFE_AUTO // legacy token = today's SAFE_AUTO semantics
            else -> ASK
        }
    }
}
