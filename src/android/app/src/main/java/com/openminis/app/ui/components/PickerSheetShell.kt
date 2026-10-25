package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.m3Icon

/**
 * [R4+R10] Shared skeleton for the model-picker family of sheets — the MAIN
 * picker (ChatModelPickerSheet.ModelPickerSheet) and the unified voice
 * pickers (UnifiedModelPickerSheet), which were byte-for-byte copies of each
 * other in everything but their list bodies.
 *
 * Owns, top to bottom, verbatim from the main picker:
 *  - the slim [MinisSheetDragHandle],
 *  - the 0.9f container column with navigationBarsPadding ALONE,
 *  - the centered title bar with the trailing "Done" text button,
 *  - the 42dp capsule search field ([T-android-search-height] recipe),
 *  - the LazyColumn scaffold the caller fills through [content].
 *
 * The callers keep only their difference layer (see each file's header
 * comment). No layout value, modifier link, color or padding was altered in
 * the move — only the title string and the search-text state are parameters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerSheetShell(
    title: String,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Slim drag handle, same as StandardChatSheet — the Material default
        // puts ~44dp of whitespace above the title.
        dragHandle = { MinisSheetDragHandle() },
    ) {
        // 0.9f fixed height + nav-bar inset, exactly like the main picker —
        // a content-wrapping column here let the voice pickers (whose provider
        // sections seed expanded) grow to cover the full screen.
        //
        // [T-android-model-picker-polish] navigationBarsPadding ALONE.
        //
        // This also carried a fixed `padding(bottom = 32.dp)`, so the
        // gesture bar was accounted for twice: 32dp on top of the
        // system inset the modifier already reserves (~24-48dp
        // depending on device). The result was a dead band under the
        // sheet far deeper than the chat composer's, which uses the
        // inset on its own — the mismatch that reads as "the picker
        // floats too high off the home indicator".
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding(),
        ) {
            // ── Title bar (iOS: "Choose Model" + Done) — CENTERED title +
            // trailing Done, byte-for-byte the main picker's layout. ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    MinisTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.model_picker_done))
                    }
                }
            }

            // ── Search bar (iOS: capsule rounded) ──
            //
            // [T-android-search-height] BasicTextField + DecorationBox rather
            // than a plain OutlinedTextField, so contentPadding is ours to set.
            //
            // The plain component cannot be made 42dp tall: its intrinsic
            // height is 56dp, so `heightIn(min=42)` never binds, and forcing
            // `height(42)` squeezes the frame while its own 16dp vertical
            // contentPadding stays put — which clipped the placeholder to its
            // top half on device. Owning contentPadding is the only way to
            // shrink the field without cutting the text; same pattern
            // SectionTextField already uses for this reason.
            //
            // Outlined 50%-radius container so focus draws the primary ring,
            // trailing ✕ clears. The previous filled-capsule stand-in had no
            // focus feedback and no clear affordance — visibly different the
            // moment the field was tapped.
            val searchInteraction = remember { MutableInteractionSource() }
            BasicTextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(42.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = searchInteraction,
                decorationBox = { innerTextField ->
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = searchText,
                        visualTransformation = VisualTransformation.None,
                        innerTextField = innerTextField,
                        placeholder = { Text(stringResource(R.string.model_picker_search_placeholder)) },
                        label = null,
                        leadingIcon = {
                            Icon(
                                m3Icon(Icons.Default.Search),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(onClick = { onSearchTextChange("") }) {
                                    Icon(
                                        m3Icon(Icons.Default.Close),
                                        contentDescription = stringResource(R.string.model_picker_search_clear),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        enabled = true,
                        isError = false,
                        interactionSource = searchInteraction,
                        colors = OutlinedTextFieldDefaults.colors(),
                        // Zero vertical: the 42dp frame plus the icons already
                        // give the text room; any inset here re-clips it.
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        container = {
                            OutlinedTextFieldDefaults.Container(
                                enabled = true,
                                isError = false,
                                interactionSource = searchInteraction,
                                colors = OutlinedTextFieldDefaults.colors(),
                                shape = RoundedCornerShape(50),
                            )
                        },
                    )
                },
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                content = content,
            )
        }
    }
}

/**
 * Section card: header + rows live in the same surface so the visual unit is
 * unambiguous. 16dp/6dp inset on surfaceContainerHigh with 14dp corners.
 *
 * [clipChildren] — the unified pickers clip the card's children to the card
 * corners (`.clip` right after `.background`); the main picker instead gives
 * its bottom rows their own bottom-corner shapes. Kept as a flag so both
 * behaviors stay exactly as they were.
 */
@Composable
internal fun PickerSectionCard(
    clipChildren: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(14.dp),
            )
            .then(
                if (clipChildren) Modifier.clip(RoundedCornerShape(14.dp)) else Modifier,
            ),
        content = content,
    )
}

/**
 * Card-embedded section header, main-picker typography: titleMedium SemiBold
 * on onSurfaceVariant so it reads as a section label rather than another row,
 * with an optional trailing slot (the group card's "Edit" text button).
 *
 * [T-android-model-picker-polish] Section headers outrank the rows beneath
 * them. titleSmall is 14sp Medium while the group and model names are
 * bodyMedium SemiBold — same size, HEAVIER weight — so the header read as the
 * weaker of the two and the hierarchy inverted. titleMedium (16sp) + SemiBold
 * puts it a step above.
 */
@Composable
internal fun PickerSectionHeader(
    label: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
        )
        trailing?.invoke()
    }
}

/**
 * [T-android-model-picker-polish] Expand/collapse chevron shared by the
 * group rows and the provider card headers, matched to iOS
 * (UnifiedModelPicker.swift:688-691): a NEUTRAL tertiarySystemFill circle
 * with a .secondary glyph.
 *
 * T295 had pushed this to secondaryContainer to escape surfaceContainerHigh,
 * which on the light theme is #F7F7FA and vanished against the white card.
 * That fixed the visibility but overshot: a tinted container reads as an
 * accented ACTION, competing with the group name beside it, where this is
 * only a disclosure control. onSurface at low alpha separates from the card
 * without claiming that emphasis.
 *
 * 28dp -> 24dp: at 28 the circle stood taller than the row's own text and
 * drew the eye first.
 */
@Composable
internal fun PickerCollapseChevron(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                CircleShape,
            )
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (expanded) m3Icon(Icons.Default.KeyboardArrowUp) else m3Icon(Icons.Default.KeyboardArrowDown),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Provider card header, embedded in the section card: the provider label and
 * a 24dp circled collapse chevron ([PickerCollapseChevron]), then the
 * hairline that separates the header from the model rows below it.
 *
 * T236: tightened provider header padding (top=10/bottom=8) so the gap to
 * the first model row reads as ~8dp rather than the earlier 12dp+row-padding
 * stack.
 *
 * The hairline under the provider name: the rows below it are models, not
 * more provider chrome, and without a rule the header read as the first list
 * item — the same separation the group card gets between its own header and
 * its members.
 */
@Composable
internal fun PickerProviderCardHeader(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            // Same rank as the "Model Groups" header above — see
            // [PickerSectionHeader].
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        PickerCollapseChevron(expanded = expanded, onToggle = onToggle)
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

/**
 * The circle check/radio selection mark used across the pickers' rows.
 * Default: 20dp, iOS blue. The group rows pass the 22dp green variant
 * (Color(0xFF34C759)) that marks a selected GROUP.
 */
@Composable
internal fun PickerSelectionMark(
    selected: Boolean,
    size: Dp = 20.dp,
    selectedTint: Color = Color(0xFF007AFF),
) {
    Icon(
        if (selected) m3Icon(Icons.Default.CheckCircle) else Icons.Default.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (selected) selectedTint
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.size(size),
    )
}

/**
 * The main picker's green "Active" badge — 9sp Medium on a 10%-green capsule
 * with 5/1 padding, marking the entry that actually serves the next request.
 */
@Composable
internal fun PickerActiveBadge() {
    Text(
        stringResource(R.string.model_picker_active_badge),
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFF34C759),
        modifier = Modifier
            .background(
                Color(0xFF34C759).copy(alpha = 0.1f),
                RoundedCornerShape(50),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
