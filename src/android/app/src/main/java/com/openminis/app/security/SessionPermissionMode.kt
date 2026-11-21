package com.openminis.app.security

/**
 * Session tool-gate tier - ZCode parity, three modes only.
 *
 * ZCode exposes exactly three session permission modes
 * (`appClientSessionModeSchema = z.enum(["build", "guarded", "yolo"])`,
 * `packages/session-control/src/contract/app.ts`), labelled变更前确认 /
 *自主模式 /完全访问 (`mode.label.glm.*` in `packages/ui/src/i18n`).
 *
 * This app shipped four overlapping tiers (OFF / ASK / SAFE_AUTO / YOLO) on
 * top of a six-value gate enum, so "which mode am I in" and "what will happen
 * to this call" had two different answers on the same screen. The mapping
 * below is one to one with ZCode:
 *
 * - [ASK_BEFORE_CHANGES] (ZCode `build`,变更前确认, "改文件前先问我") -
 * read-only and low-risk calls run; anything that changes files, runs
 * commands, or needs elevated privileges asks first.
 * - [AUTONOMOUS] (ZCode `guarded`,自主模式, "在有风险时询问") - the agent
 * works unattended; only a RISKY command (the destructive shell risk table,
 * root deletion, host privilege escalation) stops to ask.
 * - [FULL_ACCESS] (ZCode `yolo`,完全访问, "减少确认次数") - everything runs;
 * only the absolute fatal set (format / wipe / partition writes) still asks.
 *
 * Persistence is the free-form `sessions.permission_mode` String column, so
 * [fromStored] keeps reading every token this app ever wrote.
 */
enum class SessionPermissionMode {
    ASK_BEFORE_CHANGES,
    AUTONOMOUS,
    FULL_ACCESS,
    ;

    /** The gate-level mode this tier compiles to. Single mapping point. */
    fun toGateMode(): PermissionMode = when (this) {
        ASK_BEFORE_CHANGES -> PermissionMode.ASK
        AUTONOMOUS -> PermissionMode.GUARDED
        FULL_ACCESS -> PermissionMode.ALLOW_ALL
    }

    /** Canonical token written to the `permission_mode` column. */
    fun toStoredToken(): String = name

    /** True when the session may run changes without a per-call question. */
    fun isAutomatic(): Boolean = this != ASK_BEFORE_CHANGES

    /** True when the session is the top tier (ZCode `yolo`). */
    fun isFullAccess(): Boolean = this == FULL_ACCESS

    /**
     * One-tap cycle order for the chat-menu pill: ascending autonomy, wrapping
     * through the safest tier (变更前确认 ->自主模式 ->完全访问 ->变更前确认).
     */
    fun next(): SessionPermissionMode = when (this) {
        ASK_BEFORE_CHANGES -> AUTONOMOUS
        AUTONOMOUS -> FULL_ACCESS
        FULL_ACCESS -> ASK_BEFORE_CHANGES
    }

    companion object {
        /** The most restrictive tier: what an unreadable row and a new chat get. */
        val SAFEST: SessionPermissionMode = ASK_BEFORE_CHANGES

        /**
         * Read a persisted token. Every token this app has ever written maps onto
         * the nearest of the three tiers, so an upgrade never silently widens a
         * session beyond what its row said:
         *
         * - `YOLO` / `YOYO` / `FULL_ACCESS` -> [FULL_ACCESS]
         * - `AUTONOMOUS` -> [AUTONOMOUS]
         * - `SAFE_AUTO` / `ASK` (the two-tier era: reads auto-allow, every
         *   other call asks - exactly today's [ASK_BEFORE_CHANGES]) and
         *   `ASK_ALL` -> [ASK_BEFORE_CHANGES]
         * - `OFF` / `DENY_ALL` -> [ASK_BEFORE_CHANGES]
         * - null, blank, `ALLOW_ALL`, `READ_ONLY`, `PLAN`, anything unknown
         * -> [ASK_BEFORE_CHANGES]
         *
         * `OFF` ("deny every tool, reads included") has no counterpart in the
         * three-mode model: it left the agent unable to even read a file. It lands
         * on [ASK_BEFORE_CHANGES]: file changes, shell commands, MCP and remote
         * SSH all ask first (reads and low-risk calls still auto-run - that is
         * what the tier means), without the unusable-agent half.
         */
        fun fromStored(raw: String?): SessionPermissionMode = when (raw?.trim()?.uppercase()) {
            "YOLO", "YOYO", "FULL_ACCESS" -> FULL_ACCESS
            "AUTONOMOUS" -> AUTONOMOUS
            "SAFE_AUTO", "ASK", "ASK_ALL", "ASK_BEFORE_CHANGES" -> ASK_BEFORE_CHANGES
            "OFF", "DENY_ALL" -> ASK_BEFORE_CHANGES
            else -> SAFEST
        }
    }
}
