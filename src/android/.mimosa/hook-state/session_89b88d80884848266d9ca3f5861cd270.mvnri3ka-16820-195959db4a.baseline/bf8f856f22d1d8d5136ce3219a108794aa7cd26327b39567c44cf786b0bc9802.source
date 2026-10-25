package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

/**
 * One rendered chat row: the alpha/search-focus wrapper Box plus the
 * FlatChatItem -> card dispatch, extracted verbatim from ChatScreen's
 * LazyColumn item lambda (pure move; row-level state arrives as parameters).
 */
@Composable
internal fun ChatFlatItemRow(
    item: FlatChatItem,
    /** Top rhythm gap from the OLDER neighbour (see flatItemTopGap). */
    rhythmGap: Dp,
    /** 0.4f for compacted-history rows, else 1f. */
    rowAlpha: Float,
    flatItems: List<FlatChatItem>,
    searchFocusMessageId: String?,
    searchFocusActive: Boolean,
    perfFirstItemPlacedFired: java.util.concurrent.atomic.AtomicBoolean,
    sessionId: String,
    messages: List<ChatMessage>,
    lastColdPrewarmMs: Long,
    coldOpenSummaryEmitted: MutableState<Boolean>,
    screenMountAtMs: Long,
    viewModel: ChatViewModel,
    isStreaming: Boolean,
    canResume: Boolean,
    coroutineScope: CoroutineScope,
    tracedScrollToItem: suspend (String, Int, Int) -> Unit,
    safeMutate: (() -> Unit) -> Unit,
    deleteFromHereTargetId: MutableState<String?>,
    inputFocusRequester: FocusRequester,
    previewFileOrBrowser: (com.openminis.app.ui.sandbox.FileItem) -> Unit,
    onOpenTerminalWithCommand: (String) -> Unit,
    onToggleProcessCard: (String) -> Unit,
    selectionController: SelectionController,
) {
    val context = LocalContext.current
                        val isNewestItem = item == flatItems.lastOrNull()
                        // [T-android-search-jump] Pulse highlight for the
                        // search-focus row(s). A message flattens into several
                        // rows (text / mdblock / thinking / tool) — matching on
                        // the parsed messageId lights the whole message, which
                        // reads as one highlighted block. animateFloatAsState
                        // gives a soft 0→0.3→0 fade; the modifier is only
                        // attached while visible so idle rows pay nothing.
                        val searchFocusId = searchFocusMessageId
                        val isSearchFocusRow = searchFocusId != null &&
                            FlatKeys.parse(item.key)?.messageId == searchFocusId
                        val searchFocusAlpha by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (isSearchFocusRow && searchFocusActive) 0.30f else 0f,
                            animationSpec = androidx.compose.animation.core.tween(
                                durationMillis = 600,
                            ),
                            label = "searchFocusHighlight",
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = rhythmGap)
                                .alpha(rowAlpha)
                                .then(
                                    if (searchFocusAlpha > 0.01f) {
                                        Modifier.background(
                                            MaterialTheme.colorScheme.primary.copy(
                                                alpha = searchFocusAlpha,
                                            ),
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(
                                    if (isNewestItem) {
                                        Modifier.onPlaced {
                                            if (perfFirstItemPlacedFired.compareAndSet(false, true)) {
                                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                                    sessionId,
                                                    "lazyColumn.firstItem.placed",
                                                    "size=${it.size.width}x${it.size.height}",
                                                )
                                            }
                                            // [T-android-jank-diag-logging]
                                            // One quotable line per session
                                            // open, after the first frame's
                                            // newest row has laid out.
                                            if (!coldOpenSummaryEmitted.value) {
                                                coldOpenSummaryEmitted.value = true
                                                val totalChars = messages.sumOf { m -> m.content.length }
                                                val maxChars = messages.maxOfOrNull { m -> m.content.length } ?: 0
                                                AppLogger.info(
                                                    "JankDiag",
                                                    "[JankDiag] coldOpen summary session=$sessionId msgs=${messages.size} rows=${flatItems.size} " +
                                                        "totalChars=$totalChars maxChars=$maxChars prewarmMs=$lastColdPrewarmMs " +
                                                        "sinceMountMs=${System.currentTimeMillis() - screenMountAtMs} " +
                                                        "hangCount=${com.openminis.app.diagnostics.HangDetector.currentHangCount(context)}",
                                                )
                                                // [T-android-content-perf-diag] Per-large-message structural
                                                // fingerprint so a future hang report maps straight to "which
                                                // message, what structure" without re-querying the DB. Gated at
                                                // 5000 chars — small messages never drive a render hang.
                                                messages.forEachIndexed { idx, m ->
                                                    if (m.content.length >= com.openminis.app.diagnostics.CONTENT_DIAG_MIN_CHARS) {
                                                        val s = com.openminis.app.diagnostics.ContentDiag.summarize(m.content)
                                                        AppLogger.info(
                                                            "Perf",
                                                            "[Perf][ContentDiag] session=$sessionId msgIdx=$idx role=${m.role} " +
                                                                "streaming=${m.isStreaming} ${s.asLogFields()}",
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                        when (item) {
                            is FlatChatItem.UserBubble -> {
                                // User bubbles intentionally don't register
                                // MinisTextKit shards — long-press on a user
                                // bubble shows its own action menu (Copy /
                                // Retry / Edit) instead of starting text
                                // selection, matching iOS UX.
                                UserMessageBubble(
                                message = item.message,
                                onCopy = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", item.message.content))
                                },
                                // T119: pass null while a turn is in flight so
                                // the long-press menu hides Retry; once the
                                // stream stops (cancel or natural end) the
                                // option reappears. Gating execution alone
                                // wasn't enough — users still saw a tappable
                                // Retry that silently no-op'd.
                                onRetry = if (isStreaming) null else ({
                                    coroutineScope.launch {
                                        tracedScrollToItem("RETRY-FROM-MSG", 0, 0)
                                    }
                                    safeMutate { viewModel.retryFromMessage(item.message.id) }
                                }),
                                // [T-android-delete-from-here] Gated while
                                // streaming for the same reason as Retry:
                                // truncating rows under a live agent loop
                                // leaves agentHistory describing messages that
                                // no longer exist. Opens a confirmation rather
                                // than cutting straight away — there is no undo.
                                onDeleteFromHere = if (isStreaming) null else ({
                                    deleteFromHereTargetId.value = item.message.id
                                }),
                                // T187: long-press → Edit pulls the user message
                                // text into the composer; the next send truncates
                                // from this turn (inclusive) before persisting
                                // the edited content. Gated on isStreaming the
                                // same way Retry is.
                                onEdit = if (isStreaming || item.message.isQueued) null else ({
                                    val prefill = viewModel.editMessage(item.message.id)
                                    if (prefill != null) {
                                        viewModel.setInputText(prefill)
                                        coroutineScope.launch {
                                            tracedScrollToItem("EDIT-MSG", 0, 0)
                                        }
                                        inputFocusRequester.requestFocus()
                                    }
                                }),
                                onWithdraw = if (item.message.isQueued) {
                                    { safeMutate { viewModel.withdrawQueuedMessage(item.message.id) } }
                                } else null,
                                onPreviewFile = { uri, name ->
                                    // T150: turn the persisted file:// URI back
                                    // into a FileItem and hand off to the host
                                    // navigator (FilePreviewScreen). Mirrors
                                    // FileBrowser's onPreviewFile contract so
                                    // both entry points share one screen.
                                    val file = uri.path?.let { java.io.File(it) }
                                    if (file != null && file.exists()) {
                                        previewFileOrBrowser(
                                            com.openminis.app.ui.sandbox.FileItem(
                                                file = file,
                                                name = name,
                                                isDirectory = false,
                                                isSymlink = false,
                                                size = file.length(),
                                                modifiedMs = file.lastModified(),
                                            )
                                        )
                                    }
                                },
                            )
                            } // close UserBubble SideEffect + UserMessageBubble block
                            is FlatChatItem.AssistantHeader -> AssistantHeader(item.speakerName, item.speakerVendor)
                            // Assistant text renders ONLY here: the
                            // FlatChatItem.AssistantText row class was deleted
                            // (nothing ever built one), and its branch below is
                            // the one every text block reaches.
                            // T-android-gc-storm-issue17: LargeContentGuard
                            // collapses oversized frozen assistant text before
                            // feeding the markdown parser — the GC-storm hotspot
                            // for legacy sessions.
                            is FlatChatItem.AssistantMarkdownBlock -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                                markdown = item.messageMarkdown,
                            ) {
                                LargeContentGuard(
                                    content = item.rawText,
                                    isStreaming = item.isStreaming,
                                    stableKey = "mdblock:${item.messageId}:${item.parentBlockId}:${item.blockIndex}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    if (item.showTranslate) {
                                        TranslateCorner(
                                            button = {
                                                AssistantTranslateButton(
                                                    source = item.segmentText.ifBlank { item.rawText },
                                                    taskScope = coroutineScope,
                                                    onTranslated = {
                                                        if (item.translateWholeReply) {
                                                            viewModel.replaceAssistantOutput(item.messageId, it)
                                                        } else {
                                                            viewModel.replaceAssistantTextBlock(item.messageId, item.parentBlockId, it)
                                                        }
                                                    },
                                                )
                                            },
                                        ) {
                                            MarkdownBlock(
                                                rawText = item.rawText,
                                                isStreaming = item.isStreaming,
                                                shardId = TextShardId(
                                                    messageId = item.messageId,
                                                    shardId = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                                                ),
                                            )
                                        }
                                    } else {
                                        MarkdownBlock(
                                            rawText = item.rawText,
                                            isStreaming = item.isStreaming,
                                            shardId = TextShardId(
                                                messageId = item.messageId,
                                                shardId = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                                            ),
                                        )
                                    }
                                }
                            }
                            is FlatChatItem.AssistantProcessSummary -> ProcessSummaryBar(
                                thinkingCount = item.thinkingCount,
                                toolCount = item.toolCount,
                                expanded = item.expanded,
                                hasFailure = item.hasFailure,
                                totalMs = item.totalMs,
                                steps = item.steps,
                                running = item.running,
                                activity = item.activity,
                                activityTitle = item.activityTitle,
                                startedAtMs = item.startedAtMs,
                                onOpenStep = { viewModel.openToolDetail(it) },
                                onToggle = { onToggleProcessCard(item.messageId) },
                            )
                            is FlatChatItem.AssistantThinking -> {
                                // [T-android-thinking-forced-visible] Render
                                // the block whenever it carries CONTENT,
                                // regardless of the session's thinking level.
                                // T300 hid OFF-level thinking entirely, but
                                // forced-reasoning models (xAI Grok 4.x,
                                // MiniMax M2, several OpenRouter routes)
                                // stream reasoning_content even when the
                                // request carries no reasoning field — that
                                // thinking was silently swallowed, and the
                                // user saw "model isn't thinking". The level
                                // is a REQUEST knob (do we ask the model to
                                // reason), not a DISPLAY filter; content that
                                // actually arrived is always shown. The OFF
                                // case still gets the collapsed default
                                // (isStreaming=false → ThinkingBlock's own
                                // collapsed presentation), which preserves
                                // T300's intent of not splashing unsolicited
                                // reasoning across the chat.
                                if (item.block.content.isNotBlank() || item.messageIsStreaming) {
                                    // [T-android-thinking-stable-expand] The
                                    // builder folds the complete signal into
                                    // messageIsStreaming (streaming AND no
                                    // reply text after this block — see
                                    // hasReplyTextAfter); it flips only when
                                    // the reply starts or the stream ends, so
                                    // the 500ms auto-collapse fires once per
                                    // reasoning phase, not per tool round.
                                    ThinkingBlock(
                                        block = item.block,
                                        isStreaming = item.messageIsStreaming,
                                        isLast = item.isLast,
                                    )
                                }
                            }
                            is FlatChatItem.AssistantToolUse -> ToolCallPill(
                                block = item.block,
                                allToolBlocks = item.allToolBlocks,
                                onRetry = if (item.isLastCancelled && !isStreaming && !canResume) ({ safeMutate { viewModel.retryLast() } }) else null,
                                // T14: route per-card stop to the global
                                // cancelStream(). The button only renders
                                // when the block is RUNNING/STREAMING — see
                                // ToolCallPill `isRunning && onStop != null`
                                // — so passing it unconditionally is safe.
                                onStop = { viewModel.cancelStream() },
                                onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                                // T261: route detail open through ViewModel so
                                // the sheet is hoisted out of LazyColumn item
                                // scope (otherwise the sheet snaps shut when
                                // the pill scrolls off-screen and Compose
                                // disposes the item).
                                onOpenDetail = { viewModel.openToolDetail(it) },
                                // [T-android-rerun-from-tool-block-position]
                                // Re-run cuts at THIS tool_use block: keep the
                                // blocks before it in the same turn, drop it +
                                // everything after, then regenerate. The block
                                // id (== tool_use id for a tool_use block) is
                                // the stable anchor. Gated off while streaming
                                // (mutating an in-flight turn corrupts agent
                                // state, same rule as Retry on the user bubble).
                                // safeMutate tears down the selection toolbar
                                // before the truncation reshuffles the list.
                                onRerunFromHere = if (!isStreaming) ({
                                    coroutineScope.launch {
                                        tracedScrollToItem("RERUN-FROM-TOOL", 0, 0)
                                    }
                                    safeMutate { viewModel.rerunFromToolBlock(item.messageId, item.block.id) }
                                }) else null,
                                onCopyDetails = {
                                    val text = formatToolDetailsForClipboard(item.block)
                                    val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cb.setPrimaryClip(android.content.ClipData.newPlainText("tool", text))
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.tool_longpress_copied_toast),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                            is FlatChatItem.AssistantInfo -> FallbackInfoBlock(
                                block = item.block,
                                // Only the compact-divider info block should
                                // surface a "Revert Compact" button on its
                                // detail sheet — other info rows (slash
                                // notices, fallback notices) have nothing
                                // to revert.
                                onRevert = if (item.block.toolName == "compact") {
                                    { viewModel.revertCompact() }
                                } else null,
                            )
                            is FlatChatItem.AssistantTyping -> TypingIndicator()
                            is FlatChatItem.AssistantError -> InlineErrorBanner(
                                error = item.error,
                                onRetry = {
                                    coroutineScope.launch { tracedScrollToItem("INLINE-RETRY-LAST", 0, 0) }
                                    safeMutate { if (canResume) viewModel.resume() else viewModel.retryLast() }
                                },
                            )
                            is FlatChatItem.AssistantLegacyContent -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "legacy",
                                markdown = item.messageMarkdown,
                            ) {
                                LargeContentGuard(
                                    content = item.content,
                                    isStreaming = item.isStreaming,
                                    stableKey = "legacy:${item.messageId}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    StreamingMarkdownText(
                                        content = item.content,
                                        isStreaming = item.isStreaming,
                                        shardId = TextShardId(
                                            messageId = item.messageId,
                                            shardId = "legacy",
                                        ),
                                    )
                                }
                            }
                        }
                        } // Box (alpha wrapper)
}
