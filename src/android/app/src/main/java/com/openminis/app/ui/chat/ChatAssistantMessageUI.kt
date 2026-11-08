package com.openminis.app.ui.chat

// [T-android-split-chat] Assistant-message + tool-pill + thinking rendering
// extracted verbatim from ChatScreen.kt: AssistantHeader, BoundsTrackedBlock,
// InlineErrorBanner, ToolStopButton, formatToolDetailsForClipboard,
// ToolCallPill, ThinkingBlock. (The legacy AssistantMessageView renderer that
// also came across was deleted as unreachable dead code — the FlatChatItem
// path in ChatMessageListSection is the only assistant renderer.)

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import com.openminis.app.R
import com.openminis.app.cottranslate.CotTranslationPrefs
import com.openminis.app.cottranslate.OkHttpTranslationHttp
import com.openminis.app.cottranslate.ReasoningTranslationController
import com.openminis.app.cottranslate.ThinkingTranslationHandle
import com.openminis.app.ui.settings.autoExpandThinkingEnabled
import com.openminis.app.ui.components.MinisMenu
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.m3Icon
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.decorLinearSweep

@Composable
internal fun AssistantHeader(speakerName: String? = null, speakerVendor: String? = null) {
    // [T-soul-md] Identity header = icon + SOUL.md-driven `name`.
    //
    // [T-android-soul-custom-icon] The icon is now the user-settable
    // `SoulMetadata.icon` (emoji or transparent PNG), falling back to the
    // canonical sparkle gradient when unset — so a user who never touches it
    // sees exactly the previous rendering.
    //
    // Deliberately the SAME composable the settings card uses. On iOS these
    // two surfaces were written separately and the chat one silently failed
    // to pick up image icons; sharing the renderer makes that class of
    // divergence impossible rather than merely unlikely.
    val namedSpeaker = speakerName?.takeIf { it.isNotBlank() }
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsStateWithLifecycle()
    val displayName = namedSpeaker ?: soulMeta.name.ifBlank { com.openminis.app.agent.SoulMetadata.DEFAULT.name }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier,
        // [P1-3] Inter-row gaps are owned by the row rhythm
        // (flatItemTopGap): 16dp above this header after a user bubble, 4dp
        // from header to the turn's first content row. No self-padding —
        // stacking paddings would double the rhythm gap.
    ) {
        val sparkleGradient = Brush.linearGradient(
            colors = listOf(SparkleColor1, SparkleColor2),
        )
        // 18.dp, matching the previous Icon exactly: the row height feeds a
        // measured-height estimate in the message list, so the icon stays
        // square and same-sized whichever branch renders.
        if (namedSpeaker != null) {
            VendorMark(
                vendor = speakerVendor?.takeIf { it.isNotBlank() }
                    ?: com.openminis.app.tools.GroupChat.vendorKey(null, namedSpeaker),
                fallbackName = namedSpeaker,
            )
        } else {
        // [T-soul-avatar-model-icon] 「模型图标」模式需要当前模型身份。
        SoulChatAvatar(soulMeta.icon, speakerVendor, namedSpeaker, sparkleGradient)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = displayName,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Wraps a per-message LazyColumn item, registering its bounds (in window
 * coordinates) into [LocalMessageBoundsRegistry] so the selection toolbar
 * can look up which message a selection rect belongs to. The slot key
 * disambiguates multiple items belonging to the same message id (e.g. a
 * message with several text blocks).
 */
@Composable
internal fun BoundsTrackedBlock(
    messageId: String,
    slotKey: String,
    markdown: String,
    content: @Composable () -> Unit,
) {
    val registry = LocalMessageBoundsRegistry.current
    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            registry?.put(messageId, slotKey, coords.boundsInWindow(), markdown)
        },
    ) {
        content()
    }
    androidx.compose.runtime.DisposableEffect(messageId, slotKey) {
        onDispose { registry?.remove(messageId, slotKey) }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InlineErrorBanner(error: String, onRetry: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = 200.dp)
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFF3B30).copy(alpha = 0.12f))
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(error))
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = m3Icon(Icons.Default.Error),
            contentDescription = null,
            tint = Color(0xFFFF3B30),
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = error,
            color = Color(0xFFFF3B30),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFFF3B30).copy(alpha = 0.15f))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = m3Icon(Icons.Default.Refresh),
                    contentDescription = null,
                    tint = Color(0xFFFF3B30),
                    modifier = Modifier.size(10.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.chat_longpress_retry), color = Color(0xFFFF3B30), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * T14: per-card stop affordance shown on running/streaming tool blocks.
 * Mirrors iOS `ToolCapsuleView`'s small red square that appears trailing
 * the tool title when `block.toolStatus == .running`. Tapping it routes to
 * the same global `cancelStream()` callback iOS uses for `onStop?()` —
 * iOS also has no per-tool cancellation API; the per-card button is purely
 * an affordance-discoverability win. Resume banner (T13) makes the global
 * cancel UX recoverable.
 *
 * Visual: 14×14 red rounded square (Color 0xFFFF3B30 = iOS systemRed).
 */
@Composable
private fun ToolStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.stop_tool)
    // Outer Box keeps the *layout* footprint at 18×18 (unchanged capsule width).
    // The inner clickable Box is 24×24 and overflows the outer bounds equally on
    // all sides (requiredSize ignores the parent's 18dp constraint), enlarging the
    // touch target to 24 while the visual 10×10 red square stays identical.
    Box(
        modifier = modifier.size(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .requiredSize(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    onClickLabel = label,
                    onClick = onStop,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFFF3B30)),
            )
        }
    }
}

// ─── Tool Call Capsule (iOS: Capsule(), inline, tool-colored icon + title + duration) ─

/**
 * [T-android-tool-bubble-longpress-menu] Render a tool_use block as a
 * human-readable, paste-back-friendly clipboard string: the tool call
 * (name + id + pretty-printed input JSON) followed by the tool result
 * (char count + status + the result text). Input JSON is pretty-printed
 * when it parses as a JSON object/array; otherwise it's emitted verbatim
 * so a malformed / partial args string still copies usefully.
 */
internal fun formatToolDetailsForClipboard(block: AssistantBlock): String {
    val prettyInput = run {
        val raw = block.toolArgs
        if (raw.isBlank()) return@run "(none)"
        try {
            when (raw.trimStart().firstOrNull()) {
                '{' -> org.json.JSONObject(raw).toString(2)
                '[' -> org.json.JSONArray(raw).toString(2)
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }
    val statusLabel = when (block.toolStatus) {
        ToolBlockStatus.SUCCESS -> "success"
        ToolBlockStatus.FAILED -> "error"
        ToolBlockStatus.TIMEOUT -> "timeout"
        ToolBlockStatus.CANCELLED -> "cancelled"
        ToolBlockStatus.RUNNING, ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING -> "running"
        null -> "unknown"
    }
    val resultText = block.content
    return buildString {
        append("## Tool Call\n")
        append("name: ").append(block.toolName).append('\n')
        append("id: ").append(block.id).append('\n')
        append("input:\n").append(prettyInput).append('\n')
        append('\n')
        append("## Tool Result\n")
        append("(").append(resultText.length).append(" chars, ").append(statusLabel).append(")\n")
        if (resultText.isNotEmpty()) append(resultText)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolCallPill(
    block: AssistantBlock,
    allToolBlocks: List<AssistantBlock> = listOf(block),
    onRetry: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    // Nullable so callers that have no terminal surface (sub-agent bar,
    // legacy call sites) simply hide the menu item instead of showing a
    // no-op entry. The ChatMessageListSection path passes the real
    // onOpenTerminalWithCommand callback.
    onOpenTerminalWithCommand: ((String) -> Unit)? = null,
    // T261: detail open routes through ChatViewModel so the sheet survives
    // LazyColumn item disposal. Default no-op (the sub-agent bar routes
    // detail-open to its own inline expansion instead).
    onOpenDetail: (String) -> Unit = {},
    // [T-android-tool-bubble-longpress-menu] Long-press actions. Null
    // disables the corresponding menu item (e.g. re-run is null while
    // streaming or when there's no preceding user turn to re-run from).
    onRerunFromHere: (() -> Unit)? = null,
    onCopyDetails: (() -> Unit)? = null,
) {

    // PENDING shares RUNNING's spinner affordance — tool JSON is received but
    // execution hasn't flipped the block to RUNNING yet (brief gap). TIMEOUT
    // shares FAILED's error styling but the icon mapping distinguishes them.
    val isRunning = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    val isDone = block.toolStatus == ToolBlockStatus.SUCCESS
    val isFailed = block.toolStatus == ToolBlockStatus.FAILED ||
        block.toolStatus == ToolBlockStatus.TIMEOUT
    val isCancelled = block.toolStatus == ToolBlockStatus.CANCELLED

    val toolAccent = toolAccentColor(block.toolName)
    val toolIcon = m3Icon(toolIconFor(block.toolName))

    // Icon color: tool color when running/done, error/cancel colors on failure
    val iconTint = when {
        isFailed -> ToolErrorColor
        isCancelled -> ToolCancelColor
        isDone -> ToolCheckColor
        else -> toolAccent
    }

    // iOS: always shows tool-type icon, only changes color based on status
    val displayIcon = toolIcon

    // Duration text (iOS: "0.4s" format)
    val durationText = if (block.durationMs > 0 && !isRunning) {
        val seconds = block.durationMs / 1000.0
        if (seconds < 10) String.format("%.1fs", seconds)
        else String.format("%.0fs", seconds)
    } else null

    // T125: drop the spinner that used to replace the tool icon while
    // running. iOS only animates a left→right shimmer sweep across the
    // pill background and keeps the typed icon visible — the spinner
    // both fought the icon for attention and looked stylistically off
    // next to the iOS counterpart. The bottom ToolActivityPill does keep a
    // CircularProgressIndicator — that is the running-tool status surface,
    // where a spinner reads correctly (the bar this row sits in does not).
    // [FloatingToolStatusBar was removed in e6beebb; the pill replaced it.]
    val shimmerTranslate = if (isRunning) {
        // [T-decor-shared-clock] shimmer 平移走共享时钟（原 2800ms linear Restart）。
        decorLinearSweep(from = -1f, to = 2f, periodMs = 2800)
    } else null

    // [T-android-tool-bubble-longpress-menu] Long-press menu state, scoped
    // to this pill. The DropdownMenu is anchored to the pill via the Box
    // wrapper below so it opens beneath the tapped bubble.
    var showToolMenu by remember { mutableStateOf(false) }

    // [T-android-tool-pill-menu-consume-callbacks] The "Open in terminal"
    // menu item mirrors the ToolDetailSheet action: a shell_execute pill
    // can hand its command to the in-app terminal (prefilled, no auto-run).
    // Resolved once per args/content change with the same tolerant
    // extractShellCommand fallbacks; null hides the item (non-shell tools,
    // placeholder command, or a caller with no terminal surface).
    val pillShellCommand = remember(block.toolName, block.toolArgs, block.content) {
        if (block.toolName != "shell_execute") null
        else {
            val args = try { org.json.JSONObject(block.toolArgs) } catch (_: Exception) { org.json.JSONObject() }
            extractShellCommand(args, block).takeIf { it.isNotBlank() && it != "Shell command" }
        }
    }
    val hasToolMenuActions = onRerunFromHere != null || onCopyDetails != null ||
        onRetry != null || (pillShellCommand != null && onOpenTerminalWithCommand != null)

    // Pill stretches up to the full row width so long titles can ellipsize
    // without pushing the duration out of view. Title takes the remaining
    // space via weight(1f), duration stays fixed-width (softWrap=false).
    Row(modifier = Modifier.fillMaxWidth()) {
      // [P1-3] vertical self-padding removed: the row rhythm owns
      // inter-row gaps (2.5dp between consecutive process rows).
      Box(modifier = Modifier.weight(1f, fill = false)) {
        Row(
            modifier = Modifier
                .background(
                    ChatColors.toolCapsuleBg,
                    CircleShape,
                )
                .border(0.5.dp, ChatColors.toolBorder, CircleShape)
                .clip(CircleShape)
                .then(
                    if (shimmerTranslate != null) {
                        Modifier.drawWithContent {
                            drawContent()
                            val w = size.width
                            val band = w * 0.6f
                            val x = shimmerTranslate * w
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0f),
                                        Color.White.copy(alpha = 0.18f),
                                        Color.White.copy(alpha = 0f),
                                    ),
                                    start = Offset(x, 0f),
                                    end = Offset(x + band, 0f),
                                ),
                            )
                        }
                    } else Modifier,
                )
                .combinedClickable(
                    onClick = { onOpenDetail(block.id) },
                    onLongClick = if (hasToolMenuActions) {
                        { showToolMenu = true }
                    } else null,
                )
                .padding(horizontal = 12.dp)
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status icon — always the typed tool icon. Color shifts to
            // reflect terminal status (success / failed / cancelled); while
            // running it stays in the tool's accent color so the user can
            // still recognize the tool at a glance.
            Icon(
                displayIcon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp),
            )

            Spacer(modifier = Modifier.width(8.dp))

            // [T-step-timestamp v2 aa8b1128] Inline HH:mm:ss prefix removed
            // — user found it visually noisy on every tool pill. Start
            // time + elapsed duration now live in the tool detail bottom
            // sheet header instead (ToolDetailSheet, this file ~line
            // 5209). formatStepTimestamp() is still defined further down
            // because the detail sheet calls it.

            // Tool title + streaming dots after title (iOS: Text + bouncing "...").
            // weight(1f) lets the title absorb leftover width, ellipsis trims overflow.
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.toolTitle.ifEmpty { block.toolName },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    // [P-process-card] A failed call tints its OWN row's title
                    // (matching the icon) — the reference card colours the
                    // error line and leaves every other row untouched.
                    color = if (isFailed) ToolErrorColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isRunning) {
                    // iOS streaming: "..." bouncing dots after title text
                    StreamingDotsText()
                }
            }

            // Duration badge (iOS: monospaced gray text after title) — fixed width,
            // never compressed by the title. The HH:mm:ss start time is
            // surfaced inside the tool detail bottom sheet's bottom bar
            // instead of here — the inline pill list stays clean.
            if (durationText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = durationText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    softWrap = false,
                    maxLines = 1,
                )
            }
            // T168: restore per-tool stop button (reverts T117). Renders only
            // for running/streaming/pending blocks so completed pills stay
            // clean. Routes to the same global cancelStream() — there is no
            // per-tool cancellation API on either platform.
            if (isRunning && onStop != null) {
                Spacer(modifier = Modifier.width(8.dp))
                ToolStopButton(onStop = onStop)
            }
        }
        // [T-android-tool-bubble-longpress-menu] Long-press menu anchored to
        // the pill. Items mirror the user-bubble menu's style (MinisMenu +
        // DropdownMenuItem + leading icon). Each item no-ops gracefully if
        // its callback is null (re-run is gated while streaming / when no
        // preceding user turn exists).
        // [T-android-tool-menu-minwidth] Minimum width = min(220dp, screen
        // width) — the menu wants to be 220dp wide, but must never exceed the
        // device width on a narrow screen. screenWidthDp is the usable width in
        // dp; cap max to the same value so the widthIn(min,max) range is always
        // valid (min <= max) even on a sub-220dp display.
        val toolMenuWidthDp = minOf(220, LocalConfiguration.current.screenWidthDp).dp
        MinisMenu(
            expanded = showToolMenu,
            onDismissRequest = { showToolMenu = false },
            offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp),
            modifier = Modifier.widthIn(max = toolMenuWidthDp),
            minWidth = toolMenuWidthDp,
        ) {
            if (onRerunFromHere != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_rerun_from_here)) },
                    onClick = { showToolMenu = false; onRerunFromHere() },
                    leadingIcon = { Icon(m3Icon(Icons.Default.Refresh), contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (onCopyDetails != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_copy_details)) },
                    onClick = { showToolMenu = false; onCopyDetails() },
                    leadingIcon = { Icon(m3Icon(Icons.Default.ContentCopy), contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            // [T-android-tool-pill-menu-consume-callbacks] Retry: consumes the
            // onRetry callback the caller already passes (gated there on
            // last-cancelled && idle && !canResume). The inline retry button
            // was removed in T251 — the long-press menu is this pill's retry
            // surface now.
            if (onRetry != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_retry)) },
                    onClick = { showToolMenu = false; onRetry() },
                    leadingIcon = { Icon(m3Icon(Icons.Default.Replay), contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            // "Open in terminal": same dispatch the ToolDetailSheet's
            // terminal action performs — hand the shell command to the
            // in-app terminal with the prompt pre-filled (no auto-run).
            if (pillShellCommand != null && onOpenTerminalWithCommand != null) {
                val cmd = pillShellCommand
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_open_in_terminal)) },
                    onClick = { showToolMenu = false; onOpenTerminalWithCommand(cmd) },
                    leadingIcon = { Icon(m3Icon(Icons.Default.Terminal), contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
      }
        // T251: removed inline Retry affordance next to cancelled/failed pills —
        // the pill's own status icon (yellow on FAILED, gray on CANCELLED) is
        // already the unified failure tip. The button was visually noisy and
        // redundant. ToolCallPill keeps the `onRetry` parameter and surfaces
        // it through the long-press menu ("Retry" item, see above); callers
        // that pass null simply don't get the item. retryLast() /
        // retryFromMessage() remain reachable from other entry points too.
        // iOS: Spacer(minLength: 0) — pill stays content-width, not full-row-width
    }
}

// iOS-style bouncing dots (3 dots, easeInOut, staggered delay)
// [T-android-split-chat] BouncingDots / StreamingDotsText / TypingIndicator
// moved verbatim to ChatIndicators.kt (same package, now `internal`).

// ─── Thinking Block (iOS: collapsible "Deep Thinking" section, blue tint) ────

@Composable
internal fun ThinkingBlock(block: AssistantBlock, isStreaming: Boolean, isLast: Boolean = true) {
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
    val context = LocalContext.current
    val autoExpandThinking = remember { autoExpandThinkingEnabled(context) }
    val showFullThinking = remember { com.openminis.app.ui.settings.showFullThinkingEnabled(context) }
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
    val thinkingBlue = Color(0xFF007AFF)
    val charCount = block.content.length
    val charLabel = when {
        charCount >= 1000 -> "${charCount / 1000}K"
        else -> "$charCount"
    }

    val thinkingHardCap = 100_000
    val overHardCap = charCount > thinkingHardCap
    var showFullContent by remember(block.id) { mutableStateOf(false) }
    // [T-cot-translate] Reasoning-translation UI state: toggle + published text.
    val transScope = rememberCoroutineScope()
    val transContext = LocalContext.current
    val transEnabled = remember { CotTranslationPrefs.enabled(transContext) }
    var showTranslated by remember(block.id) { mutableStateOf(false) }
    var transState by remember(block.id) {
        mutableStateOf<ReasoningTranslationController.CotTranslationState?>(null)
    }
    val blockKey = "${block.id}:${block.content.hashCode()}"
    val transHandle = remember(blockKey) {
        if (!transEnabled) null else ThinkingTranslationHandle(
            blockKey, true, CotTranslationPrefs.provider(transContext),
            CotTranslationPrefs.targetLanguage(transContext), transScope,
            OkHttpTranslationHttp,
        ).onState { s -> transState = s }
    }
    DisposableEffect(blockKey) { onDispose { transHandle?.dispose() } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // [P1-3] vertical self-padding removed: the row rhythm owns
            // inter-row gaps (2.5dp between consecutive process rows).
            .background(thinkingBlue.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    modifier = Modifier.size(13.dp),
                    color = thinkingBlue,
                    strokeWidth = 1.5.dp,
                )
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                Icon(
                    imageVector = m3Icon(Icons.Default.Psychology),
                    contentDescription = null,
                    tint = thinkingBlue,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = stringResource(R.string.chat_thinking_header),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = thinkingBlue,
            )
            Spacer(modifier = Modifier.weight(1f))
            // [T-cot-translate] Explicit per-block "译" toggle — the ONLY
            // trigger for translation (the streaming block is expanded by
            // default; expansion itself never sends anything). No-op when
            // the feature is disabled in Settings.
            if (transEnabled && !overHardCap && block.content.isNotBlank()) {
                Text(
                    text = stringResource(
                        if (showTranslated) R.string.cot_translate_show_original
                        else R.string.cot_translate_show_translation,
                    ),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (showTranslated) thinkingBlue else thinkingBlue.copy(alpha = 0.75f),
                    modifier = Modifier.clickable {
                        transHandle?.toggle(transContext, block.content, isStreaming)
                        showTranslated = !showTranslated
                    },
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            // [P-process-card] Thinking duration, settled by the agent loop
            // when the first text/tool call ends the reasoning phase. Same
            // slot family as the char count so the header reads
            // "思考 · 4m 59s · 11k" like the reference card.
            formatProcessDuration(block.durationMs)?.let { dur ->
                Text(
                    text = dur,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = thinkingBlue.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (charCount > 0) {
                Text(
                    text = charLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = thinkingBlue.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (overHardCap) {
                // [T-thinking-render-perf-android] No expand/collapse chevron —
                // the content is too large for the inline Compose scroller.
                // Offer the native full-content viewer instead.
                Text(
                    text = stringResource(R.string.thinking_view_full),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = thinkingBlue,
                )
            } else {
                Icon(
                    imageVector = if (expanded) m3Icon(Icons.Default.KeyboardArrowUp) else m3Icon(Icons.Default.KeyboardArrowDown),
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = thinkingBlue.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
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
            val effectiveWindow = if (showFullThinking) thinkingHardCap else 8000 // 8k tail window
            val isTruncated = renderedContent.length > effectiveWindow
            val displayContent = visibleThinkingTail(renderedContent, effectiveWindow)
            // [T-cot-translate] Feed the growing source while streaming; the
            // controller only translates COMPLETE paragraphs.
            if (showTranslated) {
                LaunchedEffect(block.content.length) {
                    transHandle?.update(block.content, isStreaming)
                }
            }
            ThinkingAutoFollow(
                blockId = block.id,
                contentVersionValue = block.content.length,
                // [T-cot-translate] auto-follow pauses in the translated view —
                // two competing scroll targets would fight.
                isStreaming = isStreaming && !showTranslated,
                scrollState = scrollState,
            )
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .heightIn(max = 300.dp)
                    .verticalScroll(scrollState),
            ) {
                if (isTruncated) {
                    Text(
                        text = stringResource(
                            R.string.thinking_truncated_hint,
                            displayContent.length / 1000,
                            charCount / 1000,
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (showTranslated && transState != null) {
                    Column {
                        if (transState!!.failed) {
                            Text(
                                text = stringResource(R.string.cot_translate_failed),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                modifier = Modifier
                                    .clickable {
                                        transHandle?.retry(block.content, isStreaming)
                                    }
                                    .padding(vertical = 2.dp),
                            )
                        }
                        Text(
                            text = transState!!.text,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            lineHeight = 19.sp,
                        )
                    }
                } else {
                    Text(
                        text = displayContent,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        lineHeight = 19.sp,
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
