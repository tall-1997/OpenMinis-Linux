package com.openminis.app.ui.chat.voice

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// [T-voice-panel-extract] Split out of InlineVoiceInputPanel to keep that file
// under the architecture line ceiling. Pure presentation: every input arrives
// as a parameter, so the panel keeps owning capture state.

@Composable
internal fun TranscriptArea(
    transcript: String,
    isEditing: Boolean,
    onTranscriptChange: (String) -> Unit,
    onBeginEdit: () -> Unit,
    onClearAll: () -> Unit,
) {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val bandMax = screenH * 0.6f - ExpandedBase
    // [T-android-voice-dbltap-select] Word range computed by the double-tap
    // below, consumed once when the editor composes; lives here so display
    // and editor states share it across one recomposition.
    var pendingSelection by remember { mutableStateOf<TextRange?>(null) }
    if (isEditing) {
        val focus = remember { FocusRequester() }
        // TextFieldValue overload (not the String one) so entering edit mode
        // can arrive with the double-tapped word already selected. Keyed on
        // isEditing: re-seeded on every entry, preserved across keystrokes.
        var editValue by remember(isEditing) {
            mutableStateOf(
                TextFieldValue(transcript, pendingSelection ?: TextRange(transcript.length))
            )
        }
        // Mirror external transcript changes (input-text carry) into the
        // field — the String overload did this implicitly. Keystrokes round
        // trip as no-ops because editValue.text already equals transcript.
        LaunchedEffect(transcript) {
            if (editValue.text != transcript) {
                val sel = editValue.selection
                editValue = TextFieldValue(
                    transcript,
                    TextRange(
                        sel.start.coerceIn(0, transcript.length),
                        sel.end.coerceIn(0, transcript.length),
                    ),
                )
            }
        }
        BasicTextField(
            value = editValue,
            onValueChange = { new ->
                editValue = new
                onTranscriptChange(new.text)
            },
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp, max = bandMax)
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState())
                .focusRequester(focus),
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
    } else {
        var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = bandMax)
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            // Tap detection lives on the Text itself (not the band) so the
            // reported position is already in text-layout space and maps
            // straight onto getOffsetForPosition — no coordinate algebra
            // across the band's padding/scroll.
            Text(
                transcript,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                onTextLayout = { textLayout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(transcript) {
                        detectTapGestures(
                            onDoubleTap = { pos ->
                                val layout = textLayout
                                val charOffset = if (layout != null) {
                                    runCatching { layout.getOffsetForPosition(pos) }
                                        .getOrDefault(transcript.length)
                                } else {
                                    transcript.length
                                }
                                pendingSelection = voiceWordRangeAt(transcript, charOffset)
                                onBeginEdit()
                            },
                            onLongPress = { onClearAll() },
                        )
                    },
            )
        }
    }
}
