package com.openminis.app.ui.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.newFixedThreadPoolContext

/**
 * [T-android-entry-frame-parse-isolation] Dedicated dispatcher for
 * VIEWPORT-RENDERING markdown work: the per-row block/inline parse that a
 * LazyColumn item runs when it first composes (the placeholder-then-swap
 * path in StreamingMarkdownBlockBody / StreamingMarkdownText /
 * StreamingMarkdownTextBody).
 *
 * Why this exists — the cold-entry blank-viewport root cause (2026-11-14
 * field trace, session with 2320 messages): a session entry fires
 * drainMissingTail, which mutates _messages once per 50-row chunk; every
 * mutation re-runs buildFlatChatItems off-main, plus the cold prewarmer.
 * All of that work shares Dispatchers.Default with the per-row parse
 * coroutines, so the row parses queued BEHIND dozens of whole-transcript
 * rebuilds. Field measurement: the prewarmer alone reported
 * `coldPrewarm.done elapsedMs=45081` while every viewport row sat on its
 * blank placeholder — the first frame had content at 132ms and then nothing
 * readable for ~46 seconds. The earlier fixes (#297 sync flatten, #317 DB
 * gate) each cleared one queue contributor, but the structure stayed: any
 * new entry-time background task re-crowds Default and starves the frame
 * again.
 *
 * Contract:
 *  - ONLY viewport-rendering parse belongs here (row-visible work whose
 *    result the user is already looking at). It must stay small-bounded.
 *  - Session-entry bulk work (buildFlatChatItems off-main, prewarmer,
 *    drain hydration) stays on the shared Dispatchers.Default — it must
 *    never take a slot from this pool.
 *  - PHYSICALLY SEPARATE pool (not `Default.limitedParallelism`): a
 *    limitedParallelism view is only a concurrency cap — its tasks still
 *    queue on Default's own workers, so a saturated Default would starve
 *    the frame exactly like before. Two dedicated threads guarantee the
 *    handful of rows composing in one frame start immediately regardless
 *    of what the entry pipeline is doing.
 */
internal object MarkdownParseDispatcher {
    val forViewport: CoroutineDispatcher =
        newFixedThreadPoolContext(2, "viewport-parse")
}
