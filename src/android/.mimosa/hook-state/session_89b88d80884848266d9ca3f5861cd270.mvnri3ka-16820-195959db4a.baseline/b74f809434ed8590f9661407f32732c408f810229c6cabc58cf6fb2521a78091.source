package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

@Composable
internal fun StreamingMarkdownTextBody(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // While streaming, sample `content` at the adaptive throttle interval.
    // produceState + snapshotFlow.conflate() makes the upstream value collection
    // suspend-safe and frees the runtime to drop intermediate values when the
    // collector falls behind. When streaming ends, emit the final value
    // unconditionally so we don't render a stale half-block.
    val displayContent by produceState(initialValue = content, content, isStreaming) {
        if (!isStreaming) {
            value = content
            return@produceState
        }
        snapshotFlow { content }
            .conflate()
            .collect { latest ->
                value = latest
                delay(streamingThrottleFor(latest))
            }
    }
    // [T-android-inline-parse-offmain] Theme snapshot for off-main prewarm.
    val mdColors = currentMdColors()
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(displayContent) {
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownBlocks(displayContent).also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        // If the LE was cancelled while parseMarkdownBlocks was still running
        // (a newer chunk arrived), don't publish stale blocks.
        coroutineContext.ensureActive()
        blocks = computed
    }

    ShardSubIndexScope {
        Column(modifier = modifier) {
            // [T-android-stream-fade] Last block during a live stream gets
            // LocalAppendOnlyFade=true so MdText fades in newly-appended
            // word ranges (mirrors iOS TextFadeAnimator). Every other block
            // — completed prefix, non-streaming sessions — renders opaque.
            val lastIdx = blocks.size - 1
            blocks.forEachIndexed { idx, block ->
                if (isStreaming && idx == lastIdx) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalAppendOnlyFade provides true,
                    ) { RenderBlock(block) }
                } else {
                    RenderBlock(block)
                }
            }
        }
    }
}

