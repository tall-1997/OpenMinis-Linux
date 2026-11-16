package com.openminis.app.data

import com.openminis.app.data.model.ThinkingLevel

/**
 * [T-thinking-dormant-restore] (P2 1-8) Dormant thinking level: the pure core
 * of the ceiling-sync park/restore cycle.
 *
 * The problem it exists for: the model-parameter page can lower the ACTIVE
 * model's thinking ceiling (or switch thinking off). The session's own level
 * (`sessions.thinking_override`) must then never exceed that ceiling, so
 * ceiling-sync clamps the session state down and persists the clamped value —
 * which USED to destroy the user's choice: lowering the ceiling rewrote a
 * persisted HIGH to OFF, and raising the ceiling back left OFF in place. The
 * user had to re-pick their level by hand. The fix keeps the demotion but
 * parks the original choice as a DORMANT level; when the ceiling later
 * widens past it, the parked choice is restored exactly once.
 *
 * Precedence, stated once (mirrors the in-VM slot discipline):
 *  - An explicit user decision (level picker, thinking toggle, group default,
 *    session load) is the newest authority — it CLEARS the park, so a parked
 *    value can only ever beat ceiling-sync's own demotion, never a newer
 *    choice.
 *  - Only a NON-OFF level is parked: parking OFF would encode nothing (OFF is
 *    the rank floor and the level the demotion produces), and encode/decode
 *    both fold it to null.
 *
 * Pure and JVM-testable by design ([ThinkingDormancyTest]); the ChatViewModel
 * side is minimal wiring — [com.openminis.app.ui.chat.ChatViewModelThinkingSync]
 * calls [sync] in its collector and persists both columns through one helper.
 */
object ThinkingDormancy {

    /**
     * The wire/storage form of a parked level: the enum name, or null when
     * there is nothing to park (no level, or OFF — see the class doc).
     */
    fun encode(level: ThinkingLevel?): String? =
        level?.takeIf { it != ThinkingLevel.OFF }?.name

    /**
     * Inverse of [encode], tolerant of stale enum names from a future rename:
     * unknown values decode to null (no park) rather than throwing, the same
     * runCatching discipline the session load uses for thinking_override.
     */
    fun decode(raw: String?): ThinkingLevel? =
        raw?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
            ?.takeIf { it != ThinkingLevel.OFF }

    /**
     * One ceiling-sync decision. [level] is what the session's live state
     * becomes, [dormant] is what the park slot becomes, and [changed] says
     * whether anything moved (false = no persistence is needed — the old
     * collector fired a DB write on every config emission).
     */
    data class SyncResult(
        val level: ThinkingLevel,
        val dormant: ThinkingLevel?,
        val changed: Boolean,
    )

    /**
     * Reconcile the session level against the ACTIVE model's ceiling.
     *
     *  - Demotion: the current level exceeds the ceiling — clamp down and
     *    park the original (non-OFF) choice. This is the write path that used
     *    to permanently erase the user's level (P2 1-8).
     *  - Restore: nothing to clamp, a park exists, and the ceiling now
     *    reaches it — the parked choice comes back and the park clears. The
     *    ceiling rank comparison is the same downward-only discipline as the
     *    clamp: a ceiling that still cannot reach the park leaves both sides
     *    untouched.
     *  - Otherwise: pass both through unchanged.
     */
    fun sync(
        current: ThinkingLevel,
        ceiling: ThinkingLevel,
        dormant: ThinkingLevel?,
    ): SyncResult {
        val clamped = if (current.rank > ceiling.rank) ceiling else current
        return when {
            clamped != current -> SyncResult(
                level = clamped,
                dormant = current.takeIf { it != ThinkingLevel.OFF },
                changed = true,
            )
            dormant != null && dormant.rank <= ceiling.rank -> SyncResult(
                level = dormant,
                dormant = null,
                changed = true,
            )
            else -> SyncResult(level = current, dormant = dormant, changed = false)
        }
    }
}
