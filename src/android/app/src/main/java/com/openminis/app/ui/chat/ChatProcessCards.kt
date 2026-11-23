package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.m3Icon
import com.openminis.app.ui.tokens.Minis
import com.openminis.app.ui.tokens.MinisTypography
import kotlinx.coroutines.delay

/**
 * [T-provider-detail-ceiling-analogue] Process-card composables extracted from
 * ChatAssistantMessageUI when its architecture ceiling left no room for the
 * reasoning-translation UI. Pure display, no state beyond local toggles.
 */

// ─── [T-shared-second-clock] One 1Hz clock for every running process card ────
//
// Each running card used to run its OWN `while(true) { delay(1000) }` loop
// writing a per-card `nowMs` state — N running cards meant N independent 1Hz
// snapshot writes per second (plus N wakeups), all recomposing the same
// duration text. Now every card reads one shared `nowMs`, written once a
// second by the single [SharedSecondClockAnchor] loop (mounted in
// ChatScreen), which runs only while ≥1 card is subscribed.
// Same package as ChatMiscViews.kt / ChatIndicators.kt (com.openminis.app.ui.chat),
// so the other 1Hz consumers in this package can adopt it without a cross-
// package cycle.
internal object ProcessCardSecondClock {

    /** Shared "now", written once a second while [subscribers] > 0. */
    val nowMs = mutableLongStateOf(System.currentTimeMillis())

    /** Active subscriber count — snapshot state so 0↔1 drives the loop. */
    val subscribers = mutableIntStateOf(0)

    val hasSubscribers: Boolean get() = subscribers.intValue > 0

    fun register() {
        subscribers.intValue += 1
    }

    /** Defensive underflow guard: register/unregister must pair, but a stray
     *  extra unregister must never wedge the clock on permanently. */
    fun unregister() {
        if (subscribers.intValue > 0) subscribers.intValue -= 1
    }
}

/**
 * [T-shared-second-clock] The shared second clock as a [State<Long>].
 *
 * [active] is the caller's "I need fresh seconds right now" flag (for a
 * process card: `running`). Subscribing while inactive is harmless but keeps
 * the loop alive with nothing reading the value, so pass the real condition —
 * the loop then stops the moment the last active subscriber leaves (the same
 * consumer-gate pattern as DecorClock in ui/theme/DecorClock.kt). Reads of
 * the returned state should sit behind the same condition so settled cards
 * do not recompose on every shared tick.
 *
 * This helper deliberately does NOT tick — [SharedSecondClockAnchor] is the
 * one writer. Starting a loop per caller would recreate the N-writers problem.
 */
@Composable
internal fun rememberSharedSecondClock(active: Boolean): State<Long> {
    DisposableEffect(active) {
        if (active) ProcessCardSecondClock.register()
        onDispose { if (active) ProcessCardSecondClock.unregister() }
    }
    return ProcessCardSecondClock.nowMs
}

/**
 * [T-shared-second-clock] Mount ONCE per screen (ChatScreen): the single 1Hz
 * writer for [ProcessCardSecondClock]. Runs only while ≥1 card is subscribed
 * — with zero running cards there are no wakeups and no snapshot writes at
 * all. Contained in its own composable so the subscriber-count read never
 * invalidates a larger scope.
 */
@Composable
internal fun SharedSecondClockAnchor() {
    if (ProcessCardSecondClock.hasSubscribers) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                ProcessCardSecondClock.nowMs.longValue = System.currentTimeMillis()
            }
        }
    }
}

@Composable
internal fun ProcessSummaryBar(
    thinkingCount: Int,
    toolCount: Int,
    expanded: Boolean,
    hasFailure: Boolean,
    totalMs: Long = 0L,
    steps: List<ProcessStepRef> = emptyList(),
    running: Boolean = false,
    activity: String = "",
    activityTitle: String = "",
    startedAtMs: Long = 0L,
    onToggle: () -> Unit,
    onOpenStep: (String) -> Unit = {},
    singleToolContent: (@Composable (ProcessStepRef) -> Unit)? = null,
) {
    // [P-process-card] Outlined capsule (collapsed) that grows into a card
    // listing every step (expanded). Replaces the centred hairline divider:
    // the reference treats a process run as one tappable object sitting
    // between reply paragraphs, not as a section rule.
    // [P-process-card] Reuse the thinking card's look so enabling folding is
    // not a visual break: same blue, same 12dp radius, same 6%/15% tint and
    // 0.5dp hairline, same 12×8 padding as ThinkingBlock. The old 22dp
    // backgroundless capsule looked nothing like the blocks it replaced.
    val thinkingBlue = Color(0xFF007AFF)
    val shape = RoundedCornerShape(12.dp)
    // [P-process-card] Failure tints ONLY the failed tool rows inside the
    // card (user direction): the header keeps its normal blue title — the
    // Error icon shape alone signals that something in the run failed.
    val iconTint = thinkingBlue
    // A running card ticks once a second so the duration walks. Bounded by
    // the run: the moment it settles, running flips false and the loop ends.
    // [T-shared-second-clock] The per-card 1Hz loop is gone — the duration
    // now derives from the one shared second clock, subscribed only while
    // this card is running. The `.value` read stays inside the running
    // branch so a settled card never recomposes on the shared tick.
    val sharedNowMs = rememberSharedSecondClock(active = running)
    val duration = if (running && startedAtMs > 0L) {
        formatProcessDuration((sharedNowMs.value - startedAtMs).coerceAtLeast(0L))
    } else {
        formatProcessDuration(totalMs)
    }
    val aiProcess = stringResource(R.string.process_card_ai_process)
    val header = if (thinkingCount == 0 && toolCount == 0) aiProcess else processCardHeader(
        thinkingCount, toolCount, running, expanded, activity, activityTitle, duration,
        labels = ProcessCardHeaderLabels(
            thinking = stringResource(R.string.process_card_thinking),
            tools = stringResource(R.string.process_card_tools),
            aiProcess = aiProcess,
            thinkingNow = stringResource(R.string.process_card_thinking_now),
            callingTool = stringResource(R.string.subagent_status_calling_tool),
        ),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.5.dp)
            .background(thinkingBlue.copy(alpha = 0.06f), shape)
            .border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), shape)
            .clip(shape)
            .clickable(onClick = onToggle)
            // [P-process-card] Animate the expand/collapse height change:
            // without this the row list appears in one frame and the whole
            // screen visibly jumps (user: "让用户感觉整个屏幕闪一下"). A
            // gentle spring keeps the card growing/shrinking in place while
            // the list reflows around it.
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                    color = iconTint,
                )
            } else {
                Icon(
                    imageVector = if (hasFailure) m3Icon(Icons.Default.Error) else m3Icon(Icons.Default.CheckCircle),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = header,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = thinkingBlue,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (expanded) m3Icon(Icons.Default.KeyboardArrowUp) else m3Icon(Icons.Default.KeyboardArrowDown),
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = thinkingBlue.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded && steps.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                thickness = 0.5.dp,
                color = thinkingBlue.copy(alpha = 0.15f),
            )
            // Keep the compact rows for existing multi-step runs. When progress
            // folds a formerly inline tool, reuse its pill so the same actions
            // are available inside the expanded card.
            steps.forEachIndexed { idx, step ->
                if (idx > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = thinkingBlue.copy(alpha = 0.10f),
                    )
                }
                key(step.messageId, step.id) {
                    when {
                        step.block.isProcessText -> ProcessTextRow(text = step.block.content)
                        step.kind == "tool_use" && singleToolContent != null -> singleToolContent(step)
                        else -> ProcessStepRow(step = step, onOpen = { onOpenStep(step.id) })
                    }
                }
            }
        }
    }
}

/**
 * [T-midturn-prose-inline] Mid-turn assistant prose renders as ordinary
 * markdown text, in place — NOT as a collapsed "AI process" disclosure.
 *
 * The folding was the wrong fix for fragmentation: it hid the words instead of
 * removing the reason the model produced them. Prose the model wrote while
 * working is real content the user wants to read, so it is shown with the same
 * body typography as a reply (the reply/process split still decides what Copy
 * Markdown and the final answer contain, and the history still stores the
 * original bytes). The same row renders inside a card and in the unfolded transcript.
 */
@Composable
internal fun ProcessTextRow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = MinisTypography.body,
        lineHeight = MinisTypography.lineBody,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Minis.dimens.spaceLg.dp, vertical = Minis.dimens.spaceXs.dp),
    )
}

/**
 * [P-process-card] One compact row inside an expanded process card.
 *
 * Tool row: the tool's own icon (toolIconFor — the same mapping the
 * unfolded pill uses) in its accent color, title, duration, and a 查看
 * link that opens the shared detail sheet. A FAILED tool tints its icon
 * AND title in the error colour — the only place failure is coloured.
 *
 * Thinking row: the Psychology icon, 思考, char count, duration, and a
 * chevron that unfolds the reasoning text inline (300dp cap, scrollable).
 * A running thinking step auto-expands and settles 500ms after it
 * finishes; a step the user collapsed by hand is never reopened by the
 * card expanding — the two layers are independent, keyed on step.id.
 */
@Composable
private fun ProcessStepRow(step: ProcessStepRef, onOpen: () -> Unit) {
    val thinkingBlue = Color(0xFF007AFF)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val isThinking = step.kind == "thinking"
    val isFailed = !isThinking && step.status == ToolBlockStatus.FAILED
    val block = step.block

    var stepExpanded by remember(step.id) { mutableStateOf(step.running) }
    var userTouched by remember(step.id) { mutableStateOf(false) }
    LaunchedEffect(step.id, step.running) {
        if (step.running) return@LaunchedEffect
        // [T-step-collapse-tap-guard] The settle window must not clobber a tap
        // that lands DURING it: the !userTouched / !running / stepExpanded
        // guards are re-checked AFTER the delay (mirroring the card-level
        // pattern in ChatScreen), so a tap inside the 500ms window that pins
        // the step open wins over the auto-collapse. Only collapse what the
        // user did not pin open themselves.
        delay(500)
        if (!userTouched && !step.running && stepExpanded) stepExpanded = false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (isThinking) {
                        userTouched = true
                        stepExpanded = !stepExpanded
                    } else {
                        onOpen()
                    }
                }
                .padding(vertical = 5.dp),
        ) {
            if (isThinking) {
                Icon(
                    imageVector = m3Icon(Icons.Default.Psychology),
                    contentDescription = null,
                    tint = thinkingBlue,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Icon(
                    imageVector = m3Icon(toolIconFor(block.toolName)),
                    contentDescription = null,
                    tint = if (isFailed) ToolErrorColor else toolAccentColor(block.toolName),
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isThinking) {
                Text(
                    text = stringResource(R.string.chat_thinking_header),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                // [T-tool-card-row-single-line v338] The collapsed row shows
                // the semantic headline only — the argument line moved to the
                // detail sheet (user: the path under the headline hurt the
                // list's visual rhythm). Single settlement point:
                // collapsedRowLabel / showDetail=false.
                ToolCardLabel(
                    presentation = presentToolCard(block.toolName, block.toolArgs, block.toolTitle),
                    modifier = Modifier.weight(1f),
                    failed = isFailed,
                    showDetail = false,
                )
            }
            if (isThinking) {
                val charCount = block.content.length
                if (charCount > 0) {
                    Text(
                        text = if (charCount >= 1000) "${charCount / 1000}K" else "$charCount",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = muted,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
            formatProcessDuration(step.durationMs)?.let { dur ->
                Text(
                    text = dur,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = muted,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            if (!isThinking) {
                Text(
                    text = stringResource(R.string.chat_process_step_view),
                    fontSize = 12.sp,
                    color = thinkingBlue,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Icon(
                imageVector = if (isThinking) {
                    if (stepExpanded) m3Icon(Icons.Default.KeyboardArrowUp) else m3Icon(Icons.Default.KeyboardArrowDown)
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = null,
                tint = if (isThinking) thinkingBlue.copy(alpha = 0.5f) else muted,
                modifier = Modifier.size(14.dp),
            )
        }
        if (isThinking) {
            AnimatedVisibility(visible = stepExpanded && block.content.isNotEmpty()) {
                val scrollState = rememberScrollState()
                val isTruncated = block.content.length > 8000
                val displayContent = visibleThinkingTail(block.content, 8000)
                ThinkingAutoFollow(
                    blockId = block.id,
                    contentVersionValue = block.content.length,
                    isStreaming = step.running,
                    scrollState = scrollState,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, top = 2.dp, bottom = 6.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(scrollState),
                ) {
                    if (isTruncated) {
                        Text(
                            text = stringResource(
                                R.string.thinking_truncated_hint,
                                displayContent.length / 1000,
                                block.content.length / 1000,
                            ),
                            fontSize = 11.sp,
                            color = muted.copy(alpha = 0.75f),
                        )
                    }
                    Text(
                        text = displayContent,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = muted,
                    )
                }
            }
        }
    }
}
