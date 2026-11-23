package com.openminis.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.ui.settings.rememberShowTurnChangesCardEnabled
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.tokens.Minis
import com.openminis.app.ui.tokens.MinisTypography

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
    // [T-android-message-usage-footer] Flat row key → usage for the LAST row
    // of each assistant reply that has persisted token_usage (see
    // computeUsageFooterRows). Empty map = feature off / nothing parsed.
    usageFooterRows: Map<String, MessageUsageStats> = emptyMap(),
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
                        // [T-android-message-usage-footer] Resolved before the
                        // wrapper: compacted-history rows (rowAlpha < 1f) stay
                        // quiet — usage belongs to the live reading surface.
                        val usageFooterStats =
                            if (rowAlpha < 1f) null else usageFooterRows[item.key]
                        // Single-child Box → Column (layout-equivalent here) so
                        // the usage footer stacks BELOW the row content while
                        // sharing the search-focus background and alpha.
                        // [T-fold-promote-animation] animateContentSize: when the
                        // reply text is promoted from the process card to the
                        // reply body at settle time, this row's height grows
                        // smoothly instead of jumping (the process card shrinks
                        // with its own animateContentSize in ChatProcessCards).
                        Column(
                            modifier = Modifier
                                .padding(top = rhythmGap)
                                .animateContentSize()
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

                            // [T-port-fold-tools] A folded run of tool calls: one row, members behind a tap.
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
                                // [T-p9-revert] Long-press → roll files back to
                                // this point; the tail hides until the next send
                                // commits the cut. Gated like Edit.
                                onRevertToHere = if (isStreaming || item.message.isQueued) null else ({
                                    safeMutate { viewModel.revertToHere(item.message.id) }
                                }),
                                onWithdraw = if (item.message.isQueued) {
                                    { safeMutate { viewModel.withdrawQueuedMessage(item.message.id) } }
                                } else null,
                                // [T-android-queued-message-edit #37] Long-
                                // press → Edit (or the pencil beside withdraw)
                                // on a queued bubble opens the single-field
                                // dialog; confirm rewrites the queued prompt
                                // text in place — queue position and
                                // attachments preserved, memory-only (the
                                // queue never touches the DB until drain).
                                onEditQueued = if (item.message.isQueued) {
                                    { newText ->
                                        safeMutate {
                                            item.message.queuedPromptId?.let { pid ->
                                                viewModel.editQueued(pid, newText)
                                            }
                                        }
                                    }
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
                            // [T-p9-revert] The staged-revert banner rides under
                            // the boundary bubble: the tail is trimmed in memory,
                            // so the boundary is the last visible row and the
                            // banner lands exactly where history was rewound to.
                            // Read as .value (not collectAsState): every registry
                            // change is accompanied by a _messages change that
                            // triggers the flat-items rebuild this branch rides on.
                            val stagedRevert = viewModel.revertStageFlow.value
                            if (stagedRevert != null && stagedRevert.boundaryMessageId == item.message.id) {
                                ChatRevertBanner(
                                    files = stagedRevert.fileCount,
                                    onUndo = { viewModel.undoRevert() },
                                )
                            }
                            } // close UserBubble SideEffect + UserMessageBubble block
                            is FlatChatItem.AssistantHeader -> AssistantHeader(item.speakerName, item.speakerVendor)
                            // Reply text registers copy/selection and exposes translation here.
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
                            is FlatChatItem.AssistantProcessText -> ProcessTextRow(
                                text = item.block.content,
                                modifier = Modifier.padding(horizontal = Minis.dimens.spaceLg.dp),
                            )
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
                                singleToolContent = if (item.usesToolPill) ({ step ->
                                    ChatToolPill(
                                        messageId = step.messageId,
                                        block = step.block,
                                        isLastCancelled = step.isLastCancelled,
                                        isStreaming = isStreaming,
                                        canResume = canResume,
                                        viewModel = viewModel,
                                        coroutineScope = coroutineScope,
                                        tracedScrollToItem = tracedScrollToItem,
                                        safeMutate = safeMutate,
                                        onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                                    )
                                }) else null,
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
                                        llmProvider = viewModel.currentProvider,
                                        llmModelName = viewModel.currentModel?.displayName ?: "",
                                    )
                                }
                            }
                            is FlatChatItem.AssistantToolUse -> ChatToolPill(
                                messageId = item.messageId,
                                block = item.block,
                                allToolBlocks = item.allToolBlocks,
                                isLastCancelled = item.isLastCancelled,
                                isStreaming = isStreaming,
                                canResume = canResume,
                                viewModel = viewModel,
                                coroutineScope = coroutineScope,
                                tracedScrollToItem = tracedScrollToItem,
                                safeMutate = safeMutate,
                                onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                            )
                            is FlatChatItem.AssistantPresentCard ->
                                // [T-android-present-tool] Deliverable card
                                // for a completed present call: Open routes
                                // through the same previewFileOrBrowser
                                // channel the file browser uses (HTML goes
                                // to BrowserSheet, everything else to
                                // FilePreviewScreen); Share lives inside the
                                // card via FileProvider.
                                PresentDeliverableCard(
                                    block = item.block,
                                    sessionId = sessionId,
                                    onOpenFile = previewFileOrBrowser,
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
                            // [T-tool-undo-replay] ZCode-style per-turn
                            // changes card. Reverted keys come from the
                            // ViewModel state flow so already-undone rows
                            // gray out reactively (also across reload).
                            is FlatChatItem.AssistantTurnChanges -> {
                                // [T-turn-card-visibility-toggle] The card is
                                // an opt-in surface (Appearance → Turn changes
                                // card, default OFF). Reactive read: the helper
                                // observes the prefs key, so flipping the
                                // setting recomposes the card on the next
                                // frame — no list rebuild needed.
                                val showTurnCard = rememberShowTurnChangesCardEnabled()
                                if (showTurnCard) {
                                    val reverted by viewModel.toolUndo.revertedKeys.collectAsStateWithLifecycle()
                                    val diffStats by viewModel.toolUndo.diffStats.collectAsStateWithLifecycle()
                                    val completedDiffs by viewModel.toolUndo.completedDiffs.collectAsStateWithLifecycle()
                                    LaunchedEffect(item.messageId, item.changes) {
                                        viewModel.ensureTurnDiffStats(item.messageId, item.changes)
                                    }
                                TurnChangesCard(
                                    paths = item.changes.map { it.path },
                                    stats = diffStats,
                                    statsComplete = item.messageId in completedDiffs,
                                    revertedKeys = reverted,
                                    messageKeyPrefix = item.messageId,
                                    onRevertFile = { path ->
                                        safeMutate { viewModel.undoTurnFileChange(item.messageId, path) }
                                    },
                                    onRevertAll = {
                                        safeMutate { viewModel.undoAllTurnChanges(item.messageId) }
                                    },
                                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                                    )
                                }
                            }
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
                        // [T-android-message-usage-footer] Small secondary
                        // line under the reply's last row: prompt/output
                        // tokens plus the cache hit rate when the provider
                        // reported one. Rendered inside the same wrapper so
                        // row alpha and the search-focus highlight cover it.
                        if (usageFooterStats != null && usageFooterStats.hasData) {
                            MessageUsageFooterLine(stats = usageFooterStats)
                        }
                        } // Column (alpha wrapper)
}

/** Shared actions for inline tools and tools newly folded alongside progress. */
@Composable
private fun ChatToolPill(
    messageId: String,
    block: AssistantBlock,
    isLastCancelled: Boolean,
    isStreaming: Boolean,
    canResume: Boolean,
    viewModel: ChatViewModel,
    coroutineScope: CoroutineScope,
    tracedScrollToItem: suspend (String, Int, Int) -> Unit,
    safeMutate: (() -> Unit) -> Unit,
    onOpenTerminalWithCommand: (String) -> Unit,
    allToolBlocks: List<AssistantBlock> = listOf(block),
) {
    val context = LocalContext.current
    ToolCallPill(
        block = block,
        allToolBlocks = allToolBlocks,
        onRetry = if (isLastCancelled && !isStreaming && !canResume) ({ safeMutate { viewModel.retryLast() } }) else null,
        onStop = { viewModel.cancelStream() },
        onOpenTerminalWithCommand = onOpenTerminalWithCommand,
        // Keep the detail sheet hoisted out of the row's composition.
        onOpenDetail = { viewModel.openToolDetail(it) },
        // [T-android-rerun-from-tool-block-position] Use the actual message id
        // and tool id. A process card's composite key is only a UI anchor.
        onRerunFromHere = if (!isStreaming) ({
            coroutineScope.launch { tracedScrollToItem("RERUN-FROM-TOOL", 0, 0) }
            safeMutate { viewModel.rerunFromToolBlock(messageId, block.id) }
        }) else null,
        onCopyDetails = {
            val text = formatToolDetailsForClipboard(block)
            val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("tool", text))
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.tool_longpress_copied_toast),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        },
    )
}

/**
 * [T-android-message-usage-footer] The small secondary line under an
 * assistant reply: `↑ 1.2k ↓ 340 · cache 87% · 45 tok/s · 01:13`. The cache segment
 * is omitted when the provider reported no cache counters; tok/s is omitted
 * when the turn's streamed-text window was too short to be meaningful; the
 * time tier is omitted on in-memory rows without a DB row ([T-message-usage-footer-time]).
 */
@Composable
private fun MessageUsageFooterLine(stats: MessageUsageStats) {
    val tokensPart = stringResource(
        R.string.chat_usage_footer_tokens,
        formatUsageTokenCount(stats.inputTokens),
        formatUsageTokenCount(stats.outputTokens),
    )
    val cachePart = if (stats.hasCacheData) {
        stringResource(R.string.chat_usage_footer_cache, (stats.cacheHitRatio * 100).toInt())
    } else null
    val speedPart = stats.outputTokensPerSecond?.let {
        stringResource(R.string.chat_usage_footer_speed, it)
    }
    // [T-message-usage-footer-time] Three-tier display time — today `HH:mm`,
    // this year `M/d HH:mm`, older `yyyy/M/d HH:mm`; absent without a DB row.
    val timePart = stats.createdAtMs?.let {
        com.openminis.app.util.IsoTime.formatMessageDisplayTime(it)
    }
    val parts = listOfNotNull(tokensPart, cachePart, speedPart, timePart)
    Text(
        text = parts.joinToString(" · "),
        fontSize = MinisTypography.cap,
        lineHeight = MinisTypography.lineCap,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 3.dp),
    )
}