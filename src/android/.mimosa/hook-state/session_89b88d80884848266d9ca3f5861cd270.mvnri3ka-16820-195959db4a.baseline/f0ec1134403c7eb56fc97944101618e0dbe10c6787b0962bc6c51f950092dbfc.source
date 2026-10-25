package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-slash-picker-fixed-height port from iOS 73f1b94a] Locked popup
 * height for the slash and mention pickers: up to 4 rows are visible,
 * any overflow scrolls. Computed as `rowHeight * visibleRows + 8dp`.
 * [T-android-slash-menu-density] Rows were tightened (vertical padding
 * 10→7dp) so rowHeight ≈ 42dp covers a 14sp title + 11sp subtitle + 7dp
 * vertical padding; 42*4 + 8 ≈ 176dp. Keeps 4 rows visible with no extra
 * blank space at the bottom.
 *
 * This is the height at the DEFAULT font scale only — see
 * [slashPickerHeight], which is what the pickers actually use.
 */
private val SLASH_PICKER_FIXED_HEIGHT: Dp = 176.dp

/**
 * Upper bound for [slashPickerHeight]. At the largest accessibility font
 * sizes an honestly-scaled 4-row band would grow past 300dp and the popup
 * would cover most of a small screen, pushing the composer out of reach.
 * Past this point the band stops growing and the rows scroll instead —
 * which is the same fallback overflow already uses.
 */
private val SLASH_PICKER_MAX_HEIGHT: Dp = 280.dp

/**
 * [T-android-slash-picker-fontscale] The picker band's height, scaled with
 * the system font size.
 *
 * Why this is not a constant any more: the row's text is sized in `sp`, so
 * at fontScale > 1 a 14sp title and 11sp subtitle render TALLER than the
 * 14dp+11dp the fixed 176dp band was budgeted against, while the band
 * itself — being `dp` — does not move, so the last visible row gets
 * clipped mid-glyph. Reported on a Xiaomi 23127PN0CC (Android 16) whose
 * display defaults to a font scale above 1.0.
 *
 * Measured on a Pixel 4a (density 2.75) while fixing this, and worth
 * recording because it contradicts the constant's own arithmetic: the
 * real row pitch is ~61.7dp, not the ~42dp the 176dp figure assumes. So
 * the band only ever showed ~2.8 rows, and the third row's subtitle was
 * already clipped at fontScale 1.0 — font scaling makes an existing bug
 * worse rather than causing it. The 4-row budget is therefore aspirational
 * at every scale; this function keeps the same intent (and the same
 * default-scale pixels) while letting the band grow with the text, and
 * deliberately does NOT re-lock the default-scale height to ~256dp, which
 * would fit 4 real rows but is a visual change nobody asked for.
 *
 * The scaling is applied to the TEXT portion only, because that is the
 * only part that scales: the 7dp vertical padding and the 18dp leading
 * icon are `dp` and stay put. Modelling the row as
 * `max(textHeight, iconHeight) + padding` mirrors the `Row`'s own
 * `CenterVertically` measurement, so the band tracks what is actually
 * drawn instead of a second, independent guess at it.
 *
 * At fontScale 1.0 this returns [SLASH_PICKER_FIXED_HEIGHT] verbatim, so
 * the default-font layout is byte-identical to before — the computed path
 * is only consulted when the user has actually scaled their font up.
 */
@Composable
private fun slashPickerHeight(
    titleSp: TextUnit = 14.sp,
    subtitleSp: TextUnit = 11.sp,
    verticalPadding: Dp = 7.dp,
    iconSize: Dp = 18.dp,
): Dp {
    val fontScale = LocalDensity.current.fontScale
    if (fontScale <= 1f) return SLASH_PICKER_FIXED_HEIGHT

    val density = LocalDensity.current
    return with(density) {
        // Compose applies a ~1.2x line-height multiplier to a bare
        // fontSize; the two Text rows are maxLines=1 so each contributes
        // exactly one line.
        val textHeight = (titleSp.toDp() + subtitleSp.toDp()) * 1.2f
        val rowHeight = maxOf(textHeight, iconSize) + verticalPadding * 2
        (rowHeight * SLASH_PICKER_VISIBLE_ROWS + 8.dp)
            .coerceIn(SLASH_PICKER_FIXED_HEIGHT, SLASH_PICKER_MAX_HEIGHT)
    }
}

/** Rows visible in the picker band before it starts scrolling. */
private const val SLASH_PICKER_VISIBLE_ROWS = 4

/**
 * Slash-command menu + @ file-mention picker popups, extracted verbatim from
 * ChatScreen's composer block (pure move: layout, modifiers, animation params
 * unchanged; composer-local state arrives as parameters).
 *
 * Both popups anchor above the composer column and are dismissed by the
 * chat-list tap-spy, so they must stay composed inside ChatComposerScope —
 * the call site in ChatScreen does exactly that.
 */
@Composable
internal fun ChatComposerSlashAndMentionPopups(
    viewModel: ChatViewModel,
    inputText: String,
    /** Composer text field state (TextFieldValue with selection), hoisted in ChatScreen. */
    inputFieldValue: MutableState<TextFieldValue>,
    /** Laid-out width of the composer column, published via onGloballyPositioned. */
    composerWidthPx: Int,
    inputFocusRequester: FocusRequester,
    keyboardController: SoftwareKeyboardController?,
) {
                // Slash-command menu (mirrors iOS slashCommandMenu) — rendered as
                // a Popup so it overlays content (tool status bar, chat list)
                // instead of pushing them up. Anchored above the composer via
                // PopupProperties so its bottom edge sits just above this Column.
                // Tap-outside dismisses via dismissOnClickOutside.
                val showSlashMenu by viewModel.showSlashMenu.collectAsStateWithLifecycle()
                val filteredSlashCommands = remember(
                    showSlashMenu,
                    viewModel.slashFilter.collectAsStateWithLifecycle().value,
                    viewModel.memoryEnabled.collectAsStateWithLifecycle().value,
                    viewModel.thinkingLevel.collectAsStateWithLifecycle().value,
                ) { viewModel.filteredSlashCommands() }

                if (showSlashMenu && filteredSlashCommands.isNotEmpty()) {
                    val thinkingLevelState by viewModel.thinkingLevel.collectAsStateWithLifecycle()
                    val thinkingSupported = viewModel.currentModelSupportsReasoning
                    val memoryOnState by viewModel.memoryEnabled.collectAsStateWithLifecycle()
                    AnchorPopup(
                        // Anchor: top-edge of the composer column. Place
                        // the popup so its bottom sits 12dp above that edge.
                        // T301: bumped from 6dp — at 6dp the panel was
                        // visually glued to the composer; 12dp gives a
                        // clear breathing gap matching the iOS spacing.
                        anchor = AnchorSpec.AboveCentered(gap = 12),
                        onDismissRequest = {
                            viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                        },
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            // dismissOnClickOutside=false: with focusable=false the popup
                            // never receives focus, so the system "click outside" detector
                            // can't tell a tap on the BasicTextField below from a tap on
                            // the chat list — flipping this off would dismiss the popup
                            // every time the IME caret was moved. Dismiss is driven from
                            // the chat list / topbar tap-spy below instead, which lets
                            // the input field keep focus while still closing the menu
                            // when the user clearly looks elsewhere.
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                        // Locked popup height = 4 rows × 46dp + 8dp = 192dp.
                        // Short lists show empty space below the last row;
                        // long lists scroll inside the same frame with a
                        // visible scroll indicator. Prevents installed
                        // Skills + built-ins from pushing the menu past
                        // the input bar / off the top of the screen.
                        val slashListState = androidx.compose.foundation.lazy.rememberLazyListState()
                        Box(
                            modifier = Modifier
                                // [T-android-slash-popup-width] Match the
                                // composer, not the window. A Popup is its own
                                // window, so fillMaxWidth here filled the whole
                                // tablet; the menu began under the session list
                                // and overhung the composer's right edge. Fall
                                // back to fillMaxWidth only until the first
                                // measurement arrives (width 0 would collapse
                                // the panel on the very first frame).
                                .then(
                                    if (composerWidthPx > 0) {
                                        Modifier.width(
                                            with(LocalDensity.current) { composerWidthPx.toDp() },
                                        )
                                    } else {
                                        Modifier.fillMaxWidth()
                                    },
                                )
                                .padding(horizontal = 12.dp)
                                // T240: keep a thin visible border instead of the
                                // diffuse 8dp halo that bled out past the panel edge.
                                .shadow(elevation = 3.dp, shape = RoundedCornerShape(10.dp))
                                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                state = slashListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // [T-android-slash-picker-fontscale] Defaults
                                    // match this list's own row (14sp/11sp text,
                                    // 7dp padding, 18dp icon).
                                    .height(slashPickerHeight())
                                    .verticalScrollbar(slashListState),
                            ) {
                            itemsIndexed(filteredSlashCommands, key = { _, c -> c.id }) { index, cmd ->
                                // Section divider between builtins and
                                // installed Skills (mirrors iOS divider
                                // at the first skill row). Drawn as the
                                // top of the skill row, not between every
                                // row — keeps the menu visually grouped
                                // without splitting every command.
                                if (cmd.isSkill && index > 0 && !filteredSlashCommands[index - 1].isSkill) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = ChatColors.toolBorder,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                                val isThinking = cmd.id == "thinking"
                                val isThinkingActive = isThinking && thinkingLevelState.isEnabled && thinkingSupported
                                val titleColor = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                                val subtitleColor = if (isThinking && !thinkingSupported) {
                                    ChatColors.secondaryText
                                } else if (isThinkingActive) {
                                    ChatColors.sendButton.copy(alpha = 0.7f)
                                } else ChatColors.secondaryText
                                val iconTint = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .let {
                                            if (!isThinking) {
                                                it.clickable {
                                                    // [T-android-slash-menu-clears-input] Pass the
                                                    // LIVE input so an action command keeps the
                                                    // user's body text instead of wiping it.
                                                    viewModel.setInputText(viewModel.executeSlashCommand(cmd, inputText))
                                                    // For Skill rows, "/<name> "
                                                    // is a typing aid — the user
                                                    // still needs to type
                                                    // arguments. Bring the IME
                                                    // back up + grab focus so
                                                    // they can keep typing
                                                    // without an extra tap on
                                                    // the composer.
                                                    if (cmd.isSkill) {
                                                        try {
                                                            inputFocusRequester.requestFocus()
                                                        } catch (_: IllegalStateException) {
                                                            // FocusRequester not attached yet.
                                                        }
                                                        keyboardController?.show()
                                                    }
                                                }
                                            } else if (thinkingSupported) {
                                                it.clickable {
                                                    val newLevel = if (thinkingLevelState.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
                                                    viewModel.setThinkingLevel(newLevel)
                                                }
                                            } else it
                                        }
                                        // [T-android-slash-menu-density] Tighter
                                        // vertical padding (10→7) so slash rows
                                        // read as compact as iOS, not sparse.
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = m3Icon(cmd.icon),
                                        contentDescription = null,
                                        tint = iconTint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "/${cmd.title.lowercase()}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = titleColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        // Cap to one line + ellipsis (mirrors
                                        // iOS T-slash-picker-product-rules
                                        // 051896e2). Long Skill descriptions
                                        // would otherwise stretch the row,
                                        // breaking the locked 4-row band and
                                        // crowding the menu visually.
                                        Text(
                                            text = cmd.subtitle,
                                            fontSize = 11.sp,
                                            color = subtitleColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (cmd.id == "memory") {
                                        Icon(
                                            imageVector = if (memoryOnState) m3Icon(Icons.Default.CheckCircle) else Icons.Default.Block,
                                            contentDescription = null,
                                            tint = if (memoryOnState) ChatColors.sendButton else ChatColors.secondaryText,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    if (isThinking && thinkingSupported) {
                                        ThinkingLevelPicker(
                                            current = thinkingLevelState,
                                            // [T-android-thinking-level-arch] Only
                                            // offer tiers the bound model supports.
                                            availableLevels = viewModel.availableThinkingLevels,
                                            onSelect = { level -> viewModel.setThinkingLevel(level) },
                                        )
                                    }
                                }
                            }
                            }
                        }
                    }
                }
                // T4: @ file-mention picker — same anchoring + tap-spy
                // contract as the slash popup (mutually exclusive in the VM,
                // so they never both render). Reuses Popup so the bar over
                // the composer is consistent and respects IME inset.
                val showMentionMenu by viewModel.showMentionMenu.collectAsStateWithLifecycle()
                val mentionEntries by viewModel.mentionEntries.collectAsStateWithLifecycle()
                val isMentionScanning by viewModel.isMentionScanning.collectAsStateWithLifecycle()
                val mentionSelectedIndex by viewModel.mentionSelectedIndex.collectAsStateWithLifecycle()
                val groupChatMentioning by viewModel.mentionListsModels.collectAsStateWithLifecycle()
                val groupMentions by viewModel.groupMentions.collectAsStateWithLifecycle()
                if (showMentionMenu) {
                    AnchorPopup(
                        anchor = AnchorSpec.AboveCentered(gap = 6),
                        onDismissRequest = { viewModel.dismissMentionMenu() },
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            // Same rationale as the slash popup: dismissOnClickOutside=false
                            // because the input field below the popup sits in the
                            // "outside" region (focusable=false → caret moves still
                            // count as outside). The chat-list tap-spy that drives
                            // dismissSlashMenu also dismisses this menu via
                            // dismissMentionMenu(); see the LazyColumn pointerInput.
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                // [T-android-slash-popup-width] Same as the
                                // slash menu — match the composer, not the
                                // popup's own window.
                                .then(
                                    if (composerWidthPx > 0) {
                                        Modifier.width(
                                            with(LocalDensity.current) { composerWidthPx.toDp() },
                                        )
                                    } else {
                                        Modifier.fillMaxWidth()
                                    },
                                )
                                .padding(horizontal = 12.dp)
                                .shadow(elevation = 8.dp, shape = RoundedCornerShape(10.dp))
                                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                        ) {
                            if (groupChatMentioning) {
                                Text(
                                    text = stringResource(R.string.mention_model_caption),
                                    fontSize = 11.sp,
                                    color = ChatColors.secondaryText,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                                if (groupMentions.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.mention_no_model),
                                        fontSize = 13.sp,
                                        color = ChatColors.secondaryText,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    )
                                } else {
                                    val modelListState = androidx.compose.foundation.lazy.rememberLazyListState()
                                    LaunchedEffect(mentionSelectedIndex, groupMentions.size) {
                                        val idx = mentionSelectedIndex
                                        if (idx in groupMentions.indices) {
                                            modelListState.animateScrollToItem(idx)
                                        }
                                    }
                                    LazyColumn(
                                        state = modelListState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(
                                                slashPickerHeight(
                                                    titleSp = 13.sp,
                                                    subtitleSp = 11.sp,
                                                    verticalPadding = 8.dp,
                                                    iconSize = 16.dp,
                                                ),
                                            )
                                            .verticalScrollbar(modelListState),
                                    ) {
                                        itemsIndexed(
                                            groupMentions,
                                            key = { _, e -> "${e.host}|${e.modelId}|${e.name}" },
                                        ) { i, entry ->
                                            val isSelected = i == mentionSelectedIndex
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        if (isSelected) {
                                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                        } else {
                                                            Color.Transparent
                                                        },
                                                    )
                                                    .clickable {
                                                        val (newText, newCaret) = viewModel.selectGroupMention(
                                                            entry,
                                                            currentText = inputFieldValue.value.text,
                                                            currentCaret = inputFieldValue.value.selection.end,
                                                        )
                                                        viewModel.setInputText(newText)
                                                        inputFieldValue.value = androidx.compose.ui.text.input.TextFieldValue(
                                                            text = newText,
                                                            selection = androidx.compose.ui.text.TextRange(newCaret),
                                                        )
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                VendorMark(
                                                    vendor = entry.vendor,
                                                    fallbackName = entry.name,
                                                    size = 16.dp,
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = entry.name,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = ChatColors.primaryText,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        text = when {
                                                            entry.host &&
                                                                entry.extra.isNotBlank() &&
                                                                entry.extra != entry.name ->
                                                                entry.extra + " · " + entry.modelId.ifBlank { entry.name }
                                                            entry.modelId.isNotBlank() &&
                                                                entry.modelId != entry.name -> entry.modelId
                                                            else -> stringResource(R.string.mention_model_only)
                                                        },
                                                        fontSize = 11.sp,
                                                        color = ChatColors.secondaryText,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = stringResource(
                                                        if (entry.host) R.string.group_chat_host_suffix
                                                        else R.string.mention_model_badge
                                                    ),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = ChatColors.secondaryText,
                                                    modifier = Modifier
                                                        .background(
                                                            ChatColors.toolCapsuleBg,
                                                            RoundedCornerShape(8.dp),
                                                        )
                                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            } else if (mentionEntries.isEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isMentionScanning) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 1.5.dp,
                                            color = ChatColors.secondaryText,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = stringResource(
                                            if (isMentionScanning) R.string.mention_scanning
                                            else R.string.mention_no_match
                                        ),
                                        fontSize = 13.sp,
                                        color = ChatColors.secondaryText,
                                    )
                                }
                            } else {
                                // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                                // Mention picker shares the slash picker's
                                // locked 192dp height (4 rows × 46dp + 8dp)
                                // so both popups have the same band on screen.
                                val mentionListState = androidx.compose.foundation.lazy.rememberLazyListState()
                                // Keep the highlighted row visible when the user
                                // navigates with a hardware keyboard. iOS gets this
                                // for free from SwiftUI's List/scrollTo binding;
                                // mimic it explicitly here.
                                LaunchedEffect(mentionSelectedIndex, mentionEntries.size) {
                                    val idx = mentionSelectedIndex
                                    if (idx in mentionEntries.indices) {
                                        mentionListState.animateScrollToItem(idx)
                                    }
                                }
                                LazyColumn(
                                    state = mentionListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // [T-android-slash-picker-fontscale] Same
                                        // clipping applies here — this list shares
                                        // the band height but has its OWN row
                                        // metrics (13sp/11sp, 8dp padding, 16dp
                                        // icon), so it passes them rather than
                                        // inheriting the slash row's.
                                        .height(
                                            slashPickerHeight(
                                                titleSp = 13.sp,
                                                subtitleSp = 11.sp,
                                                verticalPadding = 8.dp,
                                                iconSize = 16.dp,
                                            ),
                                        )
                                        .verticalScrollbar(mentionListState),
                                ) {
                                    itemsIndexed(mentionEntries, key = { _, e -> e.linuxPath }) { i, entry ->
                                        val isSelected = i == mentionSelectedIndex
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (isSelected) {
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    } else {
                                                        Color.Transparent
                                                    },
                                                )
                                                .clickable {
                                                    val (newText, newCaret) = viewModel.selectMention(
                                                        entry,
                                                        currentText = inputFieldValue.value.text,
                                                        currentCaret = inputFieldValue.value.selection.end,
                                                    )
                                                    viewModel.setInputText(newText)
                                                    inputFieldValue.value = androidx.compose.ui.text.input.TextFieldValue(
                                                        text = newText,
                                                        selection = androidx.compose.ui.text.TextRange(newCaret),
                                                    )
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Single doc icon for every entry — the scope/mount
                                            // capsule on the right already labels what bucket
                                            // this is (workspace / skills / shared / memory /
                                            // <mountName>). iOS varies the icon per scope but
                                            // we keep it uniform here so the row stays
                                            // visually consistent at small sizes on Pixel 4a.
                                            Icon(
                                                imageVector = m3Icon(Icons.Default.Description),
                                                contentDescription = null,
                                                tint = ChatColors.secondaryText,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = entry.basename,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = ChatColors.primaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = entry.displayPath,
                                                    fontSize = 11.sp,
                                                    color = ChatColors.secondaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            // Scope / mount badge — matches iOS capsule.
                                            Text(
                                                text = entry.mountName ?: entry.scope.displayLabel,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = ChatColors.secondaryText,
                                                modifier = Modifier
                                                    .background(
                                                        ChatColors.toolCapsuleBg,
                                                        RoundedCornerShape(8.dp),
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
}
