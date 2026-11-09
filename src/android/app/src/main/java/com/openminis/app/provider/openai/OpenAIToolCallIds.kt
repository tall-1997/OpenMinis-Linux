package com.openminis.app.provider.openai

/**
 * [T-toolcall-missing-id] Resolve the accumulator id for one `tool_calls`
 * delta of a Chat Completions stream.
 *
 * Contract: one tool_call keeps ONE identity for its whole delta stream.
 * Chat Completions streaming sends `id` only on the first delta of each
 * tool_call; later deltas carry just `{index, function:{arguments}}`. So:
 *
 *  - once the call has started (ToolUseStart emitted) the id is frozen —
 *    a late or repeated id must not rename an identity the UI already
 *    tracks, or every subsequent ToolInputDelta / ToolCallComplete misses
 *    the block lookup and the tool input is lost;
 *  - before start, a non-empty delta id wins (latest sighting, the
 *    pre-regression behaviour);
 *  - only a still-empty id derives a stable `"tool-call-$index"` — the
 *    fallback for relays that never send ids, so the tail drain keeps
 *    seeing a consistent identity instead of dropping the call.
 *
 * Re-deriving on every id-less delta (the 2026-11-09 tool-card input-loss
 * regression) clobbered the real first-delta id: ToolUseStart went out
 * under the real id while ToolInputDelta / ToolCallComplete went out under
 * the derived one, so the persisted tool_use block kept `input={}` while
 * the tool still ran with the args carried by ToolCallComplete.
 */
internal fun resolveToolCallId(
    currentId: String,
    deltaId: String,
    index: Int,
    started: Boolean,
): String = when {
    started -> currentId
    deltaId.isNotEmpty() -> deltaId
    currentId.isNotEmpty() -> currentId
    else -> "tool-call-$index"
}
