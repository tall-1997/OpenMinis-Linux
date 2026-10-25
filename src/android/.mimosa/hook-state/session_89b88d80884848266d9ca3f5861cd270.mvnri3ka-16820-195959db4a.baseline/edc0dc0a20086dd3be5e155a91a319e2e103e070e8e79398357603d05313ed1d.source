package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.ui.components.CodeEditorField
import com.openminis.app.ui.components.CodeEditorLogic
import com.openminis.app.ui.components.CodeSearchBar
import com.openminis.app.ui.components.MinisCenterTopBar
import com.openminis.app.ui.components.UndoableEditorState

/**
 * Full-screen composer editor with the desktop-class editing affordances:
 * line numbers, syntax highlighting (language auto-detected, overridable),
 * incremental search with match navigation, soft-wrap toggle and undo/redo.
 *
 * The parent stays the source of truth for the raw text ([onTextChange] is
 * called on every edit); selection, search and undo state live here so the
 * collapsed composer is unaffected.
 *
 * [codeMode] selects the identity: `true` is the code editor (gutter,
 * monospace, syntax highlighting, language picker); `false` is a plain
 * large text input — the expanded composer — which keeps undo/redo, search
 * and the wrap toggle but drops every code affordance. The composer expand
 * button passes `false`: users tapping it want a bigger box to type prose
 * in, not an IDE.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongTextEditorDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    codeMode: Boolean = true,
) {
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text, selection = TextRange(text.length)))
    }
    // Adopt external text changes (shouldn't normally happen while open).
    LaunchedEffect(text) {
        if (text != fieldValue.text) {
            val caret = fieldValue.selection.start.coerceIn(0, text.length)
            fieldValue = TextFieldValue(text, selection = TextRange(caret))
        }
    }

    val undoState = remember { UndoableEditorState() }
    var searchVisible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(-1) }
    var softWrap by remember { mutableStateOf(!codeMode) }
    var language by remember {
        mutableStateOf(if (codeMode) CodeEditorLogic.detectLanguage(text) else "text")
    }
    var languageMenuOpen by remember { mutableStateOf(false) }

    val matches = remember(fieldValue.text, query) {
        CodeEditorLogic.findMatches(fieldValue.text, query)
    }

    // Re-anchor the match cursor whenever the query changes.
    LaunchedEffect(query) {
        val found = CodeEditorLogic.findMatches(fieldValue.text, query)
        if (found.isEmpty()) {
            matchIndex = -1
        } else {
            matchIndex = 0
            fieldValue = CodeEditorLogic.selectRange(fieldValue, found[0])
        }
    }

    fun goToMatch(forward: Boolean) {
        if (matches.isEmpty()) return
        matchIndex = CodeEditorLogic.wrapIndex(matchIndex, matches.size, forward)
        fieldValue = CodeEditorLogic.selectRange(fieldValue, matches[matchIndex])
    }

    fun applyEdit(next: TextFieldValue) {
        undoState.record(fieldValue, android.os.SystemClock.uptimeMillis())
        fieldValue = next
        onTextChange(next.text)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    MinisCenterTopBar(
                        title = { Text(stringResource(R.string.composer_expand_editor)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.common_back),
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    undoState.undo(fieldValue)?.let {
                                        fieldValue = it
                                        onTextChange(it.text)
                                    }
                                },
                                enabled = undoState.canUndo,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = stringResource(R.string.code_editor_undo),
                                )
                            }
                            IconButton(
                                onClick = {
                                    undoState.redo(fieldValue)?.let {
                                        fieldValue = it
                                        onTextChange(it.text)
                                    }
                                },
                                enabled = undoState.canRedo,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Redo,
                                    contentDescription = stringResource(R.string.code_editor_redo),
                                )
                            }
                            IconButton(onClick = { softWrap = !softWrap }) {
                                Icon(
                                    Icons.Filled.WrapText,
                                    contentDescription = stringResource(R.string.code_editor_wrap),
                                    tint = if (softWrap) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            IconButton(onClick = { searchVisible = !searchVisible }) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.code_editor_search),
                                    tint = if (searchVisible) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (codeMode) {
                                IconButton(onClick = { languageMenuOpen = true }) {
                                    Icon(
                                        Icons.Filled.Code,
                                        contentDescription = stringResource(R.string.code_editor_language),
                                    )
                                    DropdownMenu(
                                        expanded = languageMenuOpen,
                                        onDismissRequest = { languageMenuOpen = false },
                                    ) {
                                        CodeEditorLogic.LANGUAGE_OPTIONS.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option) },
                                                onClick = {
                                                    language = option
                                                    languageMenuOpen = false
                                                },
                                                trailingIcon = {
                                                    if (option == language) {
                                                        Icon(
                                                            Icons.Filled.Code,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary,
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    if (searchVisible) {
                        CodeSearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            matchIndex = matchIndex,
                            matchCount = matches.size,
                            onPrev = { goToMatch(false) },
                            onNext = { goToMatch(true) },
                            onClose = {
                                searchVisible = false
                                query = ""
                                matchIndex = -1
                            },
                        )
                    }
                    CodeEditorField(
                        value = fieldValue,
                        onValueChange = { next -> applyEdit(next) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        language = language,
                        softWrap = softWrap,
                        searchMatches = matches,
                        currentMatchIndex = matchIndex,
                        showLineNumbers = codeMode,
                        monospace = codeMode,
                    )
                    Spacer(Modifier.height(4.dp))
                    EditorStatusBar(fieldValue, language, showLanguage = codeMode)
                }
            }
        }
    }
}

@Composable
private fun EditorStatusBar(
    fieldValue: TextFieldValue,
    language: String,
    showLanguage: Boolean = true,
) {
    val (line, column) = CodeEditorLogic.lineAndColumn(fieldValue.text, fieldValue.selection.start)
    val lines = CodeEditorLogic.lineCount(fieldValue.text)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.code_editor_status_position, line, column),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(start = 12.dp))
        Text(
            text = stringResource(R.string.code_editor_status_size, lines, fieldValue.text.length),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (showLanguage) {
            Text(
                text = language,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
