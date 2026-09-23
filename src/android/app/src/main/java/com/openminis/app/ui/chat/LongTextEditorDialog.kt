package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R

/**
 * Full-screen composer so a long edit stays in the space above the keyboard
 * instead of growing under it. The field fills the column; imePadding shrinks
 * that column when the IME is visible, and BasicTextField scrolls the caret.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongTextEditorDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
