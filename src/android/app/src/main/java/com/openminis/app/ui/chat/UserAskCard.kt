package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.openminis.app.ui.util.expandToggleClickable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.userask.PendingAsk
import com.openminis.app.agent.userask.UserAskAnswer
import com.openminis.app.agent.userask.UserAskAnswerSource
import com.openminis.app.agent.userask.UserAskCenter
import com.openminis.app.agent.userask.UserAskConfirmChoice
import com.openminis.app.agent.userask.UserAskDismissReason
import com.openminis.app.agent.userask.UserAskParser
import com.openminis.app.agent.userask.UserAskQuestion
import com.openminis.app.agent.userask.UserAskQuestionType
import com.openminis.app.agent.userask.UserAskResolver
import com.openminis.app.agent.userask.UserAskResultStatus
import com.openminis.app.agent.userask.UserAskRisk
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

/**
 * [T-port-user-ask] The user_ask card in the chat (iOS `UserAskCardView`, final
 * state).
 *
 * Pending: blue for questions, orange / red for confirms; header with icon,
 * title, chip, i/N and the countdown; a body capped at about half the screen
 * with its own scroll; a fixed action bar. Options questions are a wizard with
 * Previous / Next / Submit; a confirm shows the exact action in monospace plus
 * the impact, with Previous on the left and Cancel / Allow on the right. There
 * is no "Always allow".
 *
 * Answered: collapses to the sub-agent card's shape (TaskCardFrame: round
 * icon, topic line, status + answer digest) and expands to the full Q&A.
 *
 * Text follows the chat font scale (LocalMarkdownFontScale); every button is
 * at least 44dp tall.
 */

// ── Pure rules (unit-tested) ───────────────────────────────────────────────

internal enum class UserAskCardPhase {
    PENDING,
    RESOLVED_LIVE,
    RESOLVED_STORED,
    /** Stopped while it waited: the stored result is Stop's cancel marker, not a user_ask result. */
    STOPPED_STORED,
    /** Not a question that reached the user (rejected or unparseable): the plain tool pill shows instead. */
    NOT_ASKED,
    /** No live state yet (arguments still streaming, or recovery has not run). */
    WAITING;

    companion object {
        fun resolve(
            livePending: Boolean,
            liveTerminal: Boolean,
            hasResults: Boolean,
            requestParses: Boolean,
            status: ToolBlockStatus?,
        ): UserAskCardPhase = when {
            livePending -> PENDING
            liveTerminal -> RESOLVED_LIVE
            hasResults && requestParses -> RESOLVED_STORED
            requestParses && status == ToolBlockStatus.CANCELLED -> STOPPED_STORED
            callEnded(status) -> NOT_ASKED
            else -> WAITING
        }

        fun callEnded(status: ToolBlockStatus?): Boolean = when (status) {
            ToolBlockStatus.SUCCESS, ToolBlockStatus.FAILED, ToolBlockStatus.CANCELLED, ToolBlockStatus.TIMEOUT -> true
            else -> false
        }
    }
}

/**
 * Every text size on the card, derived from the chat body size with iOS's
 * ratios and floors (UserAskTypography), so the card follows the user's font
 * setting instead of fixed sizes.
 */
internal data class UserAskTypography(val body: Float) {
    private fun size(ratio: Float, floor: Float): Float = maxOf(floor, Math.round(body * ratio * 2) / 2f)
    val question get() = size(1.0f, 13f)
    val optionTitle get() = size(0.97f, 13f)
    val optionDetail get() = size(0.82f, 12f)
    val secondary get() = size(0.88f, 12.5f)
    val title get() = size(0.91f, 13f)
    val meta get() = size(0.76f, 11f)
    val chip get() = size(0.66f, 10f)

    /**
     * [T-user-ask-chip-height] The chip's own compact line height, in
     * TaskCardChip's proportion (11sp over 10sp). Without it the chip inherited
     * the chat body's tall line height and its tint block came out far taller
     * than the text. Never below the font size, so glyphs keep their room; a
     * CJK line still grows to its fallback font's metrics, as TaskCardChip's does.
     */
    val chipLineHeight get() = chip * CHIP_LINE_RATIO
    val button get() = size(0.97f, 14f)
    val icon get() = size(1.03f, 15f)

    companion object {
        /** Chat body size at the default scale. */
        const val BODY_REFERENCE = 16.5f
        val MIN_TAP_HEIGHT = 44.dp
        val CHIP_LINE_RATIO = TaskCardMetrics.chipLineHeight.value / TaskCardMetrics.chipSize.value
        fun forScale(scale: Float) = UserAskTypography(BODY_REFERENCE * scale)
    }
}

internal object UserAskTint {
    val question = Color(0xFF0A84FF)
    val medium = Color(0xFFFF9500)
    val high = Color(0xFFFF3B30)
    val muted = Color(0xFF8E8E93)

    fun forQuestion(q: UserAskQuestion?): Color = when {
        q == null || q.type != UserAskQuestionType.CONFIRM -> question
        q.risk == UserAskRisk.HIGH -> high
        else -> medium
    }
}

private val Float.tsp: TextUnit get() = this.sp

// ── Entry point ───────────────────────────────────────────────────────────

/**
 * The card for one user_ask tool block. [fallback] renders the ordinary tool
 * pill for a call that never became a question.
 */
@Composable
internal fun UserAskBlock(block: AssistantBlock, fallback: @Composable () -> Unit) {
    val asks by UserAskCenter.asks.collectAsState()
    val live = asks.values.firstOrNull { it.toolCallId == block.id }
    val request = remember(block.toolArgs) { UserAskParser.parse(block.toolArgs).getOrNull() }
    val stored = remember(block.content) { UserAskResolver.parseResults(block.content) }
    val phase = UserAskCardPhase.resolve(
        livePending = live?.state == com.openminis.app.agent.userask.UserAskState.PENDING,
        liveTerminal = live?.state?.isTerminal == true,
        hasResults = stored.isNotEmpty(),
        requestParses = request != null,
        status = block.toolStatus,
    )
    val t = UserAskTypography.forScale(LocalMarkdownFontScale.current)
    when (phase) {
        UserAskCardPhase.PENDING -> live?.let { UserAskPendingCard(it, t) }
        UserAskCardPhase.RESOLVED_LIVE -> live?.let {
            UserAskResolvedCard(block.id, it.questions, UserAskResolver.parseResults(it.resultJson), it.source, it.dismissReason, t)
        }
        UserAskCardPhase.RESOLVED_STORED -> request?.let {
            UserAskResolvedCard(block.id, it.questions, stored, null, null, t)
        }
        UserAskCardPhase.STOPPED_STORED -> request?.let {
            UserAskResolvedCard(block.id, it.questions, emptyList(), null, UserAskDismissReason.STOPPED, t)
        }
        UserAskCardPhase.NOT_ASKED -> fallback()
        UserAskCardPhase.WAITING -> UserAskWaiting(t)
    }
}

@Composable
private fun UserAskWaiting(t: UserAskTypography) {
    val tint = UserAskTint.question
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.07f))
            .border(0.8.dp, tint.copy(alpha = 0.35f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.QuestionMark, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.user_ask_waiting), fontSize = t.secondary.tsp, color = ChatColors.secondaryText)
    }
}

// ── Pending ───────────────────────────────────────────────────────────────

private enum class InputKind { OTHER, NOTE, DENY_REASON }

/**
 * [T-user-ask-fold-by-layout] When a pending question gets its "Show more" /
 * "Show less" toggle. Decided from the text layout, never from its length.
 */
internal object UserAskQuestionFold {
    const val FOLDED_LINES = 3

    /**
     * Folded: the layout says the text was cut. Open: the full text takes more
     * lines than the folded box holds, so there is something to fold back
     * (and after a rotation that makes it fit, the toggle goes away).
     */
    fun isFoldable(expanded: Boolean, hasVisualOverflow: Boolean, lineCount: Int): Boolean =
        if (expanded) lineCount > FOLDED_LINES else hasVisualOverflow
}

@Composable
private fun UserAskPendingCard(ask: PendingAsk, t: UserAskTypography) {
    val index = ask.currentIndex.coerceIn(0, ask.questions.lastIndex)
    val q = ask.questions[index]
    val answer = ask.answers[index]
    val tint = UserAskTint.forQuestion(q)
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(12.dp)
    var input by remember(ask.requestId, index) { mutableStateOf<InputKind?>(null) }
    var expanded by rememberSaveable(ask.requestId, index) { mutableStateOf(false) }
    // [T-user-ask-fold-by-layout] Whether the question does not fit the folded
    // box, as the text layout reports it. See UserAskQuestionFold.
    var foldable by remember(ask.requestId, index) { mutableStateOf(false) }
    val toggleFold = {
        expanded = !expanded
        UserAskCenter.noteInteraction(ask.requestId)
    }
    val bodyCap = (LocalConfiguration.current.screenHeightDp * 0.5f - 120f).coerceAtLeast(160f).dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.07f))
            .border(1.dp, tint.copy(alpha = 0.45f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PendingHeader(ask, index, q, tint, t)
        val bodyScroll = rememberScrollState()
        val cardBg = tint.copy(alpha = 0.07f).compositeOver(ChatColors.background)
        Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = bodyCap)
                // [T-android-userask-edge-bounce] A fling in the body stops at
                // the body's edge instead of flinging the chat behind it.
                .nestedScroll(StopFlingAtEdgeConnection)
                .verticalScroll(bodyScroll)
                // [T-port-user-ask-fix2] Room under the last row (the note), so
                // a scrolled-to-end body does not end flush on it.
                .padding(bottom = 6.dp),
        ) {
            val isConfirm = q.type == UserAskQuestionType.CONFIRM
            Text(
                q.question,
                fontSize = (if (isConfirm) t.secondary else t.question).tsp,
                fontWeight = if (isConfirm) FontWeight.Normal else FontWeight.Medium,
                fontFamily = if (isConfirm) FontFamily.Monospace else FontFamily.Default,
                // [T-port-user-ask-fix2] The command box is a code block: its
                // own foreground on its own background, as markdown code blocks
                // draw it. primaryText on codeBlockBg was dark on black.
                color = if (isConfirm) ChatColors.codeBlockText else ChatColors.primaryText,
                maxLines = if (expanded) Int.MAX_VALUE else UserAskQuestionFold.FOLDED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { foldable = UserAskQuestionFold.isFoldable(expanded, it.hasVisualOverflow, it.lineCount) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isConfirm) Modifier.clip(RoundedCornerShape(8.dp)).background(ChatColors.codeBlockBg) else Modifier)
                    // A cut-off question opens on a tap anywhere on it, not
                    // only on the small "Show more" below.
                    .then(if (foldable && !expanded) Modifier.expandToggleClickable(pressedShape = RectangleShape, onClick = toggleFold) else Modifier)
                    .then(if (isConfirm) Modifier.padding(8.dp) else Modifier)
                    .padding(bottom = 6.dp),
            )
            // [T-user-ask-fold-by-layout] Shown when the text is actually cut
            // (or was, and is now open) - not by character count: 140 chars
            // never came for a 60-character CJK question that already filled
            // three lines, which then could not be opened at all. The row is a
            // full-height tap target; the negative offset keeps the label
            // aligned with the text above while the padding widens the target.
            if (foldable) {
                Box(
                    modifier = Modifier
                        .offset(x = (-8).dp)
                        .clip(RoundedCornerShape(8.dp))
                        .heightIn(min = UserAskTypography.MIN_TAP_HEIGHT)
                        // [ISS-017] An in-place expansion: flat pressed tint,
                        // no ripple, like every other expand toggle in the list.
                        .expandToggleClickable(pressedShape = RectangleShape, onClick = toggleFold)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        stringResource(if (expanded) R.string.user_ask_show_less else R.string.user_ask_show_more),
                        fontSize = t.meta.tsp,
                        fontWeight = FontWeight.Medium,
                        color = tint,
                    )
                }
            }
            when (q.type) {
                UserAskQuestionType.OPTIONS -> OptionsBody(ask, index, q, answer, tint, t, onInput = { input = it }) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                UserAskQuestionType.CONFIRM -> ConfirmBody(q, answer, tint, t, onInput = { input = it })
            }
        }
        // [T-port-user-ask-fix2] The body is capped at about half the screen;
        // a fade on its lower edge says there is more to scroll, so a row cut
        // by the cap reads as "scroll", not as broken layout.
        if (bodyScroll.canScrollForward) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, cardBg))),
            )
        }
        }
        ActionBar(ask, index, q, answer, tint, t) { heavy ->
            haptic.performHapticFeedback(if (heavy) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        }
    }

    input?.let { kind ->
        InputDialog(
            kind = kind,
            question = q.question,
            initial = if (kind == InputKind.OTHER) answer.otherText.orEmpty() else answer.note.orEmpty(),
            onChange = { saveInput(ask, index, q, kind, it, commit = false) },
            onDone = { saveInput(ask, index, q, kind, it, commit = true); input = null },
            onDismiss = { input = null },
        )
    }
}

/** Drafts are saved as typed (they survive a restart); Done commits. */
private fun saveInput(ask: PendingAsk, index: Int, q: UserAskQuestion, kind: InputKind, raw: String, commit: Boolean) {
    val text = raw.take(UserAskParser.MAX_INPUT_LENGTH)
    val cur = UserAskCenter.asks.value[ask.requestId]?.answers?.getOrNull(index) ?: return
    val next = when (kind) {
        // [T-port-user-ask-fix2] Applied to every keystroke, not only Done:
        // the dialog can also close by back or a tap outside, which used to
        // keep the preselected option next to the typed answer ("Red, Green").
        InputKind.OTHER -> cur.withOtherText(text, q)
        InputKind.NOTE, InputKind.DENY_REASON -> cur.copy(note = text)
    }
    UserAskCenter.updateAnswer(ask.requestId, index, next)
}

@Composable
private fun PendingHeader(ask: PendingAsk, index: Int, q: UserAskQuestion, tint: Color, t: UserAskTypography) {
    // [T-user-ask-header-right-align] Two groups: the leading one (icon, title,
    // chip) takes all the spare width and ellipsizes the title inside it; i/N
    // and the countdown stay pinned to the right edge. A title with
    // weight(fill = false) next to a weight(1f) Spacer split the spare width
    // 1:1, so on a wide screen the trailing group stopped mid-row.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PendingHeaderLeading(q, tint, t, Modifier.weight(1f))
        if (ask.questions.size > 1) {
            Text("${index + 1}/${ask.questions.size}", fontSize = t.meta.tsp, fontWeight = FontWeight.Medium, color = ChatColors.secondaryText)
        }
        ask.expiresAtMs?.let { Countdown(it, t) }
    }
}

@Composable
private fun PendingHeaderLeading(q: UserAskQuestion, tint: Color, t: UserAskTypography, modifier: Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (q.type == UserAskQuestionType.CONFIRM) Icons.Default.Shield else Icons.Default.QuestionMark,
                null, tint = tint, modifier = Modifier.size(14.dp),
            )
        }
        val title = when {
            q.type == UserAskQuestionType.OPTIONS -> stringResource(R.string.user_ask_needs_answer)
            q.risk == UserAskRisk.HIGH -> stringResource(R.string.user_ask_high_risk)
            else -> stringResource(R.string.user_ask_needs_approval)
        }
        Text(
            title, fontSize = t.title.tsp, fontWeight = FontWeight.SemiBold, color = ChatColors.primaryText,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        q.header?.takeIf { it.isNotEmpty() }?.let { Chip(it, tint, t) }
    }
}

@Composable
private fun Countdown(expiresAtMs: Long, t: UserAskTypography) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val left = ((expiresAtMs - now) / 1000).coerceAtLeast(0)
    val color = if (left <= 30) UserAskTint.medium else ChatColors.secondaryText
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(Icons.Default.Timer, stringResource(R.string.user_ask_time_left), tint = color, modifier = Modifier.size(12.dp))
        Text(String.format(java.util.Locale.US, "%d:%02d", left / 60, left % 60), fontSize = t.meta.tsp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun Chip(text: String, tint: Color, t: UserAskTypography) {
    Text(
        text, fontSize = t.chip.tsp, lineHeight = t.chipLineHeight.tsp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun OptionsBody(
    ask: PendingAsk,
    index: Int,
    q: UserAskQuestion,
    answer: UserAskAnswer,
    tint: Color,
    t: UserAskTypography,
    onInput: (InputKind) -> Unit,
    onTap: () -> Unit,
) {
    q.options?.forEachIndexed { i, o ->
        val selected = i in answer.selected && !answer.delegated
        ChoiceRow(
            icon = if (q.multiSelect) {
                if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank
            } else {
                if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked
            },
            selected = selected, tint = tint, t = t,
            onClick = {
                UserAskCenter.updateAnswer(ask.requestId, index, answer.withOption(i, q))
                onTap()
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(o.label, fontSize = t.optionTitle.tsp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = ChatColors.primaryText)
                    if (i == 0) Chip(stringResource(R.string.user_ask_recommended), tint, t)
                }
                o.description?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = t.optionDetail.tsp, color = ChatColors.secondaryText)
                }
            }
        }
    }
    val other = answer.otherText?.trim().orEmpty()
    val otherSelected = other.isNotEmpty() && !answer.delegated
    ChoiceRow(
        icon = when {
            q.isOpenEnded -> Icons.Default.Edit
            otherSelected -> Icons.Default.RadioButtonChecked
            else -> Icons.Default.RadioButtonUnchecked
        },
        selected = otherSelected, tint = tint, t = t, onClick = { UserAskCenter.noteInteraction(ask.requestId); onInput(InputKind.OTHER) },
    ) {
        Text(
            if (otherSelected) other else stringResource(if (q.isOpenEnded) R.string.user_ask_type_answer else R.string.user_ask_other),
            fontSize = t.optionTitle.tsp,
            fontWeight = if (otherSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (otherSelected) ChatColors.primaryText else ChatColors.secondaryText,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
    if (!q.isOpenEnded) {
        ChoiceRow(
            icon = if (answer.delegated) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
            selected = answer.delegated, tint = tint, t = t,
            onClick = {
                UserAskCenter.updateAnswer(ask.requestId, index, UserAskAnswer(delegated = true, note = answer.note))
                onTap()
            },
        ) {
            Text(
                stringResource(R.string.user_ask_you_decide), fontSize = t.optionTitle.tsp,
                fontWeight = if (answer.delegated) FontWeight.SemiBold else FontWeight.Normal,
                color = if (answer.delegated) ChatColors.primaryText else ChatColors.secondaryText,
            )
        }
    }
    val note = answer.note?.trim().orEmpty()
    SecondaryRow(
        icon = if (note.isEmpty()) Icons.Default.Add else Icons.Default.Edit,
        text = note.ifEmpty { stringResource(R.string.user_ask_add_note) },
        filled = note.isNotEmpty(), t = t, onClick = { UserAskCenter.noteInteraction(ask.requestId); onInput(InputKind.NOTE) },
    )
}

@Composable
private fun ConfirmBody(q: UserAskQuestion, answer: UserAskAnswer, tint: Color, t: UserAskTypography, onInput: (InputKind) -> Unit) {
    q.impact?.takeIf { it.isNotEmpty() }?.let { impact ->
        Row(modifier = Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Warning, null, tint = tint, modifier = Modifier.size(14.dp).padding(top = 2.dp))
            Text(impact, fontSize = t.secondary.tsp, color = ChatColors.primaryText)
        }
    }
    val reason = answer.note?.trim().orEmpty()
    SecondaryRow(
        icon = if (reason.isEmpty()) Icons.Default.Add else Icons.Default.Edit,
        text = reason.ifEmpty { stringResource(R.string.user_ask_cancel_with_reason) },
        filled = reason.isNotEmpty(), t = t, onClick = { onInput(InputKind.DENY_REASON) },
    )
}

@Composable
private fun ChoiceRow(
    icon: ImageVector,
    selected: Boolean,
    tint: Color,
    t: UserAskTypography,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = UserAskTypography.MIN_TAP_HEIGHT)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = if (selected) tint else ChatColors.tertiaryText, modifier = Modifier.size(t.icon.dp))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun SecondaryRow(icon: ImageVector, text: String, filled: Boolean, t: UserAskTypography, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = UserAskTypography.MIN_TAP_HEIGHT)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = ChatColors.secondaryText, modifier = Modifier.size(14.dp))
        Text(
            text, fontSize = t.secondary.tsp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (filled) ChatColors.primaryText else ChatColors.secondaryText,
        )
    }
}

@Composable
private fun ActionBar(
    ask: PendingAsk,
    index: Int,
    q: UserAskQuestion,
    answer: UserAskAnswer,
    tint: Color,
    t: UserAskTypography,
    onTap: (heavy: Boolean) -> Unit,
) {
    val showsPrevious = PendingAsk.showsPrevious(index)
    val previous: @Composable (Modifier) -> Unit = { m ->
        CapsuleButton(stringResource(R.string.user_ask_previous), tint, primary = false, t = t, modifier = m) {
            UserAskCenter.goTo(ask.requestId, index - 1)
        }
    }
    when (q.type) {
        UserAskQuestionType.OPTIONS -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showsPrevious) previous(Modifier.weight(1f))
            val last = ask.questions.indices.none { it != index && it !in ask.submitted }
            CapsuleButton(
                stringResource(if (last) R.string.user_ask_submit else R.string.user_ask_next),
                tint, primary = true, t = t, enabled = answer.isComplete(q), modifier = Modifier.weight(1f),
            ) {
                UserAskCenter.submitQuestion(ask.requestId, index)
                onTap(false)
            }
        }
        UserAskQuestionType.CONFIRM -> {
            // Cancel first: the safe choice sits where the thumb lands. Allow
            // acts on one tap, so Previous is kept apart on the left.
            val cancel: @Composable () -> Unit = {
                CapsuleButton(stringResource(R.string.user_ask_cancel), UserAskTint.muted, primary = true, t = t) {
                    UserAskCenter.confirm(ask.requestId, index, UserAskConfirmChoice.DENY, answer.note)
                    onTap(false)
                }
            }
            val allow: @Composable () -> Unit = {
                CapsuleButton(stringResource(R.string.user_ask_allow), tint, primary = false, t = t) {
                    UserAskCenter.confirm(ask.requestId, index, UserAskConfirmChoice.APPROVE)
                    onTap(q.risk == UserAskRisk.HIGH)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (!showsPrevious || maxWidth >= 300.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showsPrevious) previous(Modifier)
                        Spacer(Modifier.weight(1f))
                        cancel()
                        allow()
                    }
                } else {
                    // Narrow: the pair on its own right-aligned row, Previous below on the left.
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            cancel()
                            allow()
                        }
                        previous(Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun CapsuleButton(
    label: String,
    tint: Color,
    primary: Boolean,
    t: UserAskTypography,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .widthIn(min = 72.dp)
            .heightIn(min = UserAskTypography.MIN_TAP_HEIGHT)
            .clip(RoundedCornerShape(50))
            .background(if (primary) tint.copy(alpha = if (enabled) 1f else 0.4f) else tint.copy(alpha = if (enabled) 0.14f else 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = t.button.tsp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (primary) Color.White else tint.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

@Composable
private fun InputDialog(
    kind: InputKind,
    question: String,
    initial: String,
    onChange: (String) -> Unit,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(question, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 15.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.take(UserAskParser.MAX_INPUT_LENGTH)
                        onChange(text)
                    },
                    placeholder = {
                        Text(
                            stringResource(
                                when (kind) {
                                    InputKind.OTHER -> R.string.user_ask_your_answer
                                    InputKind.NOTE -> R.string.user_ask_add_note
                                    InputKind.DENY_REASON -> R.string.user_ask_reason
                                },
                            ),
                        )
                    },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${text.length}/${UserAskParser.MAX_INPUT_LENGTH}",
                    fontSize = 11.sp, color = ChatColors.tertiaryText,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text(stringResource(R.string.user_ask_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.user_ask_cancel)) } },
    )
}

// ── Resolved ──────────────────────────────────────────────────────────────

/** iOS UserAskResolvedCard text rules, pure for tests. */
internal object UserAskResolvedText {
    /** The topic line: the question (the command, for a confirm), or the header tags when there are several. */
    fun topic(questions: List<UserAskQuestion>): String =
        if (questions.size == 1) questions[0].question
        else questions.joinToString(" · ") { q -> q.header?.takeIf { it.isNotEmpty() } ?: q.question }

    /** The answer digest: chosen labels or typed text, "Left to Minis Ultra" when delegated (several only), a cancel reason. */
    fun digest(results: List<UserAskResolver.Parsed>, dismissed: Boolean, leftToMinis: String): String {
        if (dismissed) return ""
        return results.mapNotNull { r ->
            when (r.status) {
                UserAskResultStatus.ANSWERED, UserAskResultStatus.OTHER -> r.answer.joinToString(", ").takeIf { it.isNotEmpty() }
                UserAskResultStatus.DELEGATED -> if (results.size > 1) leftToMinis else null
                UserAskResultStatus.DENIED -> r.note?.trim()?.takeIf { it.isNotEmpty() }
                else -> null
            }
        }.joinToString(" · ")
    }

    fun answeredCount(results: List<UserAskResolver.Parsed>): Int =
        results.count { it.status != UserAskResultStatus.TIMEOUT && it.status != UserAskResultStatus.DISMISSED }
}

@Composable
private fun shortStatus(s: UserAskResultStatus): String = stringResource(
    when (s) {
        UserAskResultStatus.ANSWERED, UserAskResultStatus.OTHER -> R.string.user_ask_answered
        UserAskResultStatus.DELEGATED -> R.string.user_ask_left_to_minis
        UserAskResultStatus.APPROVED -> R.string.user_ask_allowed_once
        UserAskResultStatus.DENIED -> R.string.user_ask_cancelled
        UserAskResultStatus.TIMEOUT -> R.string.user_ask_timed_out
        UserAskResultStatus.DISMISSED -> R.string.user_ask_skipped
        UserAskResultStatus.UNAVAILABLE -> R.string.user_ask_could_not_ask
    },
)

@Composable
private fun summaryLine(r: UserAskResolver.Parsed): String {
    val a = r.answer.joinToString(", ")
    return when (r.status) {
        UserAskResultStatus.ANSWERED -> stringResource(R.string.user_ask_selected, a)
        UserAskResultStatus.OTHER -> stringResource(R.string.user_ask_answered_with, a)
        else -> shortStatus(r.status)
    }
}

@Composable
private fun UserAskResolvedCard(
    blockId: String,
    questions: List<UserAskQuestion>,
    results: List<UserAskResolver.Parsed>,
    source: UserAskAnswerSource?,
    dismissReason: UserAskDismissReason?,
    t: UserAskTypography,
) {
    var expanded by rememberSaveable(blockId) { mutableStateOf(false) }
    val allDismissed = dismissReason != null || (results.isNotEmpty() && results.all { it.status == UserAskResultStatus.DISMISSED })
    val tint = if (allDismissed) UserAskTint.muted else UserAskTint.forQuestion(questions.firstOrNull())
    val anyTimeout = results.any { it.status == UserAskResultStatus.TIMEOUT }
    val anyDenied = results.any { it.status == UserAskResultStatus.DENIED }
    val glyph = when {
        allDismissed -> Icons.Default.Close
        anyTimeout -> Icons.Default.Timer
        anyDenied -> Icons.Default.Close
        else -> Icons.Default.Done
    }
    val statusColor = if (dismissReason != null || anyTimeout || anyDenied) ChatColors.secondaryText else tint
    // HOOK(notification, P2): iOS appends "(from notification)" when source == NOTIFICATION.
    val status = when (dismissReason) {
        UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE -> stringResource(R.string.user_ask_replaced_by_message)
        UserAskDismissReason.STOPPED -> stringResource(R.string.user_ask_stopped)
        UserAskDismissReason.USER_CLOSED_CARD -> stringResource(R.string.user_ask_skipped)
        UserAskDismissReason.REPLACED_BY_NEW_ASK -> stringResource(R.string.user_ask_replaced_by_newer)
        null -> when {
            results.size > 1 -> stringResource(R.string.user_ask_n_of_m_answered, UserAskResolvedText.answeredCount(results), results.size)
            results.size == 1 -> shortStatus(results[0].status)
            else -> stringResource(R.string.user_ask_skipped)
        }
    }
    val digest = UserAskResolvedText.digest(results, dismissReason != null, stringResource(R.string.user_ask_left_to_minis))
    val isConfirm = questions.firstOrNull()?.type == UserAskQuestionType.CONFIRM

    // [T-user-ask-resolved-expand] Opening / shutting keeps the card's top in
    // place (iOS e563158e2): the reverseLayout list would otherwise pin its
    // bottom and grow it upward. The extra rows sit below the head row, so the
    // icon and chevron stay on the title and status lines.
    val anchor = LocalChatToggleAnchor.current
    val resize = remember { TopAnchoredResize() }
    val scope = rememberCoroutineScope()
    TaskCardFrame(
        accent = tint,
        onClick = { resize.toggle(scope, anchor) { expanded = !expanded } },
        expandsInPlace = true,
        onClickLabel = stringResource(if (expanded) R.string.user_ask_show_less else R.string.user_ask_show_more),
        modifier = resize.modifier.onGloballyPositioned { resize.coords = it },
        icon = { Icon(glyph, null, tint = tint, modifier = Modifier.size(TaskCardMetrics.iconGlyph)) },
        below = if (!expanded) null else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    questions.forEachIndexed { i, q ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(q.question, fontSize = (t.secondary - 1.5f).coerceAtLeast(12f).tsp, color = ChatColors.secondaryText)
                            results.getOrNull(i)?.let { r ->
                                Text(
                                    summaryLine(r) + (r.note?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                                    fontSize = t.secondary.tsp, fontWeight = FontWeight.Medium, color = ChatColors.primaryText,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) {
        TaskCardTitleRow {
            if (questions.size == 1) questions[0].header?.takeIf { it.isNotEmpty() }?.let { TaskCardChip(it, tint) }
            Text(
                UserAskResolvedText.topic(questions),
                fontSize = TaskCardMetrics.titleSize,
                lineHeight = TaskCardMetrics.titleLineHeight,
                fontWeight = FontWeight.Medium,
                fontFamily = if (isConfirm) FontFamily.Monospace else FontFamily.Default,
                color = ChatColors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TaskCardStatusRow {
            Text(
                status, fontSize = TaskCardMetrics.subtitleSize, lineHeight = TaskCardMetrics.subtitleLineHeight,
                fontWeight = FontWeight.SemiBold, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (digest.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    digest, fontSize = TaskCardMetrics.subtitleSize, lineHeight = TaskCardMetrics.subtitleLineHeight,
                    color = ChatColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/**
 * [T-port-user-ask-fix2] A tool block's display title. The stored fallback
 * titles are English by design ("Execute Shell", …); user_ask's "Question for
 * you" is shown in the user's language wherever the title is drawn (floating
 * tool bar, tool sheet, the plain pill of a call that was not asked).
 */
@Composable
internal fun displayToolTitle(block: AssistantBlock): String =
    if (block.toolName == com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME) {
        stringResource(R.string.user_ask_question_for_you)
    } else {
        block.toolTitle.ifEmpty { block.toolName }
    }
