package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.cottranslate.CotTranslationPrefs
import com.openminis.app.cottranslate.OkHttpTranslationHttp
import com.openminis.app.cottranslate.ReasoningTranslationController
import com.openminis.app.cottranslate.ThinkingTranslationHandle
import com.openminis.app.ui.settings.autoExpandThinkingEnabled
import com.openminis.app.ui.theme.m3Icon
import com.openminis.app.ui.tokens.MinisTypography
import com.openminis.app.ui.tokens.RawSpace
import kotlinx.coroutines.delay
import com.openminis.app.ui.settings.rememberAppearanceBool
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-thinking-block-extract] The collapsible "Deep Thinking" block, split
 * out of ChatAssistantMessageUI.kt because that file sits at its architecture
 * size ceiling — the ceiling never rises, so growth happens by extraction.
 */
@Composable
internal fun ThinkingBlock(
    block: AssistantBlock,
    isStreaming: Boolean,
    isLast: Boolean = true,
    /** [T-cot-translate-llm] The chat's live provider for the LLM translate
     *  route; null (sub-agent bar, unavailable VM) = anonymous routes only. */
    llmProvider: com.openminis.app.provider.LLMProvider? = null,
    llmModelName: String = "",
) {
    // Per-block expand state, keyed by block.id so the user's manual toggle on
    // an earlier (finished) thinking block survives recomposition while a
    // later block is still streaming. The previous LaunchedEffect snapped
    // every non-last block back to collapsed on each `isLast` flip, which
    // fought the user's tap and produced a flicker that read as "tapping the
    // earlier block shows the streaming block's content."
    // [T-thinking-auto-expand-toggle] The initial auto-expand of a new
    // streaming block is gated on the Appearance setting (default ON =
    // historical behavior). When the user turned it off, a new streaming block
    // starts collapsed; a manual header tap still expands it (setting
    // userTouched, so neither the stream-end auto-collapse nor anything else
    // fights the user). Read once at mount — mirrors iOS ThinkingBlockView,
    // where the same UserDefaults gate sits at the one-shot auto-expand site.
    // [T-pref-reactive-read] Reactive: these two are read via a keyless
    // `remember` before, so toggling either setting in Appearance had no
    // effect until the transcript was rebuilt.
    val autoExpandThinking = rememberAppearanceBool(
        com.openminis.app.ui.settings.KEY_AUTO_EXPAND_THINKING, true,
    )
    val showFullThinking = rememberAppearanceBool(
        com.openminis.app.ui.settings.KEY_SHOW_FULL_THINKING, false,
    )
    var expanded by remember(block.id) {
        mutableStateOf(if (isStreaming) showFullThinking || (autoExpandThinking && isLast) else false)
    }
    var userTouched by remember(block.id) { mutableStateOf(false) }
    var wasStreaming by remember(block.id) { mutableStateOf(isStreaming) }
    LaunchedEffect(block.id, isStreaming) {
        // Collapse only on a real active->finished edge (showFullThinking keeps it open).
        if (wasStreaming && !isStreaming && !userTouched && !showFullThinking) {
            delay(500)
            expanded = false
        }
        wasStreaming = isStreaming
    }
    // [T-apple-accent-pair] Was RawC.systemBlue (#007AFF, static): a third
    // accent that also ignored dark mode. The palette accent is 5.57 / 5.76:1.
    val thinkingBlue = ChatColors.thinking
    val charCount = block.content.length
    val charLabel = when {
        charCount >= 1000 -> "${charCount / 1000}K"
        else -> "$charCount"
    }

    val thinkingHardCap = 100_000
    val overHardCap = charCount > thinkingHardCap
    var showFullContent by remember(block.id) { mutableStateOf(false) }
    // [T-cot-translate] Reasoning translation state. The handle is keyed on
    // block.id only — a content-hash suffix rebuilt it on every chunk and
    // killed streaming translation mid-flight.
    val transScope = rememberCoroutineScope()
    val transContext = LocalContext.current
    // [T-cot-translate-live-gate] The old one-shot `remember` froze the gate at
    // first composition of this block: enabling the feature in Settings never
    // reached an already-rendered thinking block ("打开了也没反应"). Re-read
    // when the settings revision bumps, like every other settings-driven UI.
    val transRevision = CotTranslationPrefs.revision.collectAsStateWithLifecycle().value
    val transEnabled = remember(transRevision) { CotTranslationPrefs.enabled(transContext) }
    var showTranslated by remember(block.id) { mutableStateOf(false) }
    var transState by remember(block.id) {
        mutableStateOf<ReasoningTranslationController.CotTranslationState?>(null)
    }
    val transHandle = remember(block.id) {
        ThinkingTranslationHandle(
            block.id, transScope, OkHttpTranslationHttp,
        ).onState { s -> transState = s }
    }
    DisposableEffect(block.id) { onDispose { transHandle.dispose() } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // [P1-3] vertical self-padding removed: the row rhythm owns
            // inter-row gaps (2.5dp between consecutive process rows).
            .background(thinkingBlue.copy(alpha = 0.06f), RoundedCornerShape(RawSpace.lg.dp))
            .border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), RoundedCornerShape(RawSpace.lg.dp))  // token-literal-ok: 发丝描边，不是 4dp 网格步长
            .clip(RoundedCornerShape(RawSpace.lg.dp))
            .padding(horizontal = RawSpace.lg.dp, vertical = RawSpace.md.dp),
    ) {
        // Header row — only the header reacts to taps. Mirrors iOS, where
        // .onTapGesture is on the header HStack, not the whole VStack. With
        // clickable on the outer Column, a release after dragging in the
        // inner scroller registered as a tap and toggled `expanded`.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    userTouched = true
                    // [T-thinking-render-perf-android] Over the hard cap the
                    // inline scroller is bypassed entirely; tapping the header
                    // opens the native full-content viewer instead of toggling
                    // the (never-shown) inline expansion.
                    if (overHardCap) showFullContent = true
                    else expanded = !expanded
                },
        ) {
            if (isStreaming && block.toolStatus != ToolBlockStatus.SUCCESS) {
                // iOS: ProgressView().controlSize(.mini) while streaming
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),  // token-literal-ok: 图标字形尺寸，与 1.5dp 描边成对
                    color = thinkingBlue,
                    strokeWidth = 1.5.dp,  // token-literal-ok: 图标描边宽度
                )
                Spacer(modifier = Modifier.width(RawSpace.sm.dp))
            } else {
                Icon(
                    imageVector = m3Icon(Icons.Default.Psychology),
                    contentDescription = null,
                    tint = thinkingBlue,
                    modifier = Modifier.size(RawSpace.lg2.dp),
                )
                Spacer(modifier = Modifier.width(RawSpace.sm.dp))
            }
            Text(
                text = stringResource(R.string.chat_thinking_header),
                fontSize = MinisTypography.body,
                fontWeight = FontWeight.SemiBold,
                color = thinkingBlue,
            )
            Spacer(modifier = Modifier.weight(1f))
            // [T-cot-translate] Explicit per-block trigger — expansion never sends.
            if (transEnabled && !overHardCap && block.content.isNotBlank()) {
                Text(
                    text = stringResource(
                        if (showTranslated) R.string.cot_translate_show_original
                        else R.string.cot_translate_show_translation,
                    ),
                    fontSize = MinisTypography.cap,
                    fontWeight = FontWeight.Medium,
                    color = if (showTranslated) thinkingBlue else thinkingBlue.copy(alpha = 0.75f),
                    modifier = Modifier.clickable {
                        showTranslated = transHandle.toggle(
                            transContext, block.content, isStreaming, llmProvider, llmModelName,
                        )
                    },
                )
                Spacer(modifier = Modifier.width(RawSpace.sm.dp))
            }
            // [P-process-card] Thinking duration, settled by the agent loop
            // when the first text/tool call ends the reasoning phase. Same
            // slot family as the char count so the header reads
            // "思考 · 4m 59s · 11k" like the reference card.
            formatProcessDuration(block.durationMs)?.let { dur ->
                Text(
                    text = dur,
                    fontSize = MinisTypography.cap,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    // [T-chat-text-contrast] thinking@0.6 on the 6% blue card
                    // measured 2.54:1 (light) — same tier collapse as the body.
                    color = ChatColors.tertiaryText,
                )
                Spacer(modifier = Modifier.width(RawSpace.xs.dp))
            }
            if (charCount > 0) {
                Text(
                    text = charLabel,
                    fontSize = MinisTypography.cap,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    // [T-chat-text-contrast] thinking@0.6 on the 6% blue card
                    // measured 2.54:1 (light) — same tier collapse as the body.
                    color = ChatColors.tertiaryText,
                )
                Spacer(modifier = Modifier.width(RawSpace.xs.dp))
            }
            if (overHardCap) {
                // [T-thinking-render-perf-android] No expand/collapse chevron —
                // the content is too large for the inline Compose scroller.
                // Offer the native full-content viewer instead.
                Text(
                    text = stringResource(R.string.thinking_view_full),
                    fontSize = MinisTypography.cap,
                    fontWeight = FontWeight.Medium,
                    color = thinkingBlue,
                )
            } else {
                Icon(
                    imageVector = if (expanded) m3Icon(Icons.Default.KeyboardArrowUp) else m3Icon(Icons.Default.KeyboardArrowDown),
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = thinkingBlue.copy(alpha = 0.5f),
                    modifier = Modifier.size(RawSpace.lg2.dp),
                )
            }
        }

        // Expanded content. Mirrors iOS ThinkingBlockView (AssistantBlockView.swift:648):
        // an inner scroller capped at 300dp, auto-follow to the bottom while the
        // block is streaming, manual drag at any time, and pause-on-user-scroll
        // so a user reading earlier reasoning isn't yanked back to the tail by
        // the next token.
        AnimatedVisibility(visible = expanded && !overHardCap) {
            val scrollState = rememberScrollState()
            // [T-thinking-render-perf-android] Render only the tail window (O(window) not O(total));
            // UiUpdateBatcher already paces recomposition at 200-500ms.
            val renderedContent = block.content
            // [T-cot-translate] Keep the translation in sync with the stream —
            // gated on `expanded` because a collapsed block shows nothing, so
            // there is no reason to spend translate round-trips on it.
            LaunchedEffect(block.content.length, isStreaming) {
                if (transEnabled) transHandle.update(block.content, isStreaming)
            }
            val effectiveWindow = if (showFullThinking) thinkingHardCap else 8000 // 8k tail window
            val isTruncated = renderedContent.length > effectiveWindow
            val displayContent = visibleThinkingTail(renderedContent, effectiveWindow)
            ThinkingAutoFollow(
                blockId = block.id,
                contentVersionValue = block.content.length,
                isStreaming = isStreaming,
                scrollState = scrollState,
            )
            Column(
                modifier = Modifier
                    .padding(top = RawSpace.sm.dp)
                    .heightIn(max = 300.dp)  // token-literal-ok: 思考滚区最大高度，布局意图
                    .verticalScroll(scrollState),
            ) {
                if (isTruncated) {
                    Text(
                        text = stringResource(
                            R.string.thinking_truncated_hint,
                            displayContent.length / 1000,
                            charCount / 1000,
                        ),
                        fontSize = MinisTypography.cap,
                        color = ChatColors.tertiaryText,
                        modifier = Modifier.padding(horizontal = RawSpace.lg.dp, vertical = RawSpace.xs.dp),
                    )
                }
                if (showTranslated && transState != null) {
                    // [T-cot-translate] Translated view. `failed` keeps the
                    // original text on screen and offers a retry instead of
                    // silently showing nothing.
                    val ts = transState!!
                    if (ts.failed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.cot_translate_failed),
                                fontSize = MinisTypography.cap,
                                color = ChatColors.tertiaryText,
                            )
                            Spacer(modifier = Modifier.width(RawSpace.md.dp))
                            Text(
                                text = stringResource(R.string.cot_translate_retry),
                                fontSize = MinisTypography.cap,
                                fontWeight = FontWeight.Medium,
                                color = thinkingBlue,
                                modifier = Modifier.clickable {
                                    transHandle.retry(block.content, isStreaming)
                                },
                            )
                        }
                        if (ts.text.isNotBlank()) {
                            Text(
                                text = ts.text,
                                fontSize = MinisTypography.body,
                                color = ChatColors.secondaryText,
                                lineHeight = MinisTypography.lineBody,
                            )
                        }
                    } else {
                        Text(
                            text = ts.text,
                            fontSize = MinisTypography.body,
                            color = ChatColors.secondaryText,
                            lineHeight = MinisTypography.lineBody,
                        )
                    }
                } else {
                    Text(
                        text = displayContent,
                        fontSize = MinisTypography.body,
                        color = ChatColors.secondaryText,
                        lineHeight = MinisTypography.lineBody,
                    )
                }
            }
        }

        // [T-thinking-render-perf-android] Hard-cap full-content viewer. A
        // native TextView (selectable, scrollable) renders arbitrarily large
        // thinking text without the Compose `Text` measure freeze.
        if (overHardCap && showFullContent) {
            ThinkingFullContentDialog(
                content = block.content,
                onDismiss = { showFullContent = false },
            )
        }
    }
}

