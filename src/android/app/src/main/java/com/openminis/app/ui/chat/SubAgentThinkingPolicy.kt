package com.openminis.app.ui.chat

import com.openminis.app.data.model.ThinkingLevel

/**
 * [T-thinking-three-faces-unified] (P2 3-4 / P2 3-10) The ONE thinking-level
 * contract every helper face of the main session shares. Three historically
 * separate call sites resolved their own rule — spawn lanes followed the
 * session level (#353) while group-chat members and supervisor seats used the
 * raw model ceiling, so the same session ran hosts, speakers and workers at
 * three different thinking depths. All of them now route through these two
 * pure functions, which makes the contract JVM-testable and greppable: any
 * future face must call these, and a semantics change happens in exactly one
 * place.
 *
 * The contract, stated once:
 *   1. A helper face follows the SESSION's live thinking level — the user's
 *      chosen depth governs everything the session spawns, not per-face
 *      defaults.
 *   2. The session level is clamped DOWN to each face's OWN model ceiling
 *      (force-thinking lift included via [forcedThinkingCeilingFor]) — a
 *      helper model that cannot reach the session's level never receives a
 *      tier it cannot honour.
 *   3. The clamp is downward-only, in both directions of "downward": a
 *      ceiling BELOW the session level caps it, and a ceiling ABOVE it never
 *      lifts the request (see [clampThinkingToCeiling]).
 */

/**
 * The thinking level for one helper face (spawn lane, group-chat host or
 * speaker, supervisor seat or worker): the session's live level clamped to
 * THIS face's model ceiling. Never lifts — a stronger ceiling does not turn
 * a session-level OFF into thinking.
 */
internal fun laneSessionThinkingLevel(
    sessionLevel: ThinkingLevel,
    faceCeiling: ThinkingLevel,
): ThinkingLevel = clampThinkingToCeiling(sessionLevel, faceCeiling)

/**
 * [T-subagent-retry-reclamp] (P2 3-10) The thinking level for one lane retry
 * attempt. A lane's level was resolved against its ORIGINAL model; when the
 * retry rotation lands on a DIFFERENT entry, the level must be shallowly
 * re-clamped against the NEW entry's ceiling — otherwise a rotation to a
 * weaker model sends a tier it cannot honour (provider clamps silently) and
 * a rotation to a stronger one under-uses nothing (never lifts: the session's
 * ask stays the authority). A retry on the SAME entry keeps the resolved
 * level verbatim — re-clamping is a no-op there, and the passthrough keeps
 * the first-attempt path byte-identical.
 */
internal fun laneRetryThinkingLevel(
    requested: ThinkingLevel,
    entryChanged: Boolean,
    newEntryCeiling: ThinkingLevel,
): ThinkingLevel =
    if (entryChanged) clampThinkingToCeiling(requested, newEntryCeiling) else requested
