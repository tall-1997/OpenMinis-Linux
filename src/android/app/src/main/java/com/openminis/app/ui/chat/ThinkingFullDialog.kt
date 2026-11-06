package com.openminis.app.ui.chat

// [T-android-split-chat] The full-screen thinking viewer, extracted from
// ChatAssistantMessageUI.kt so the chunked lazy renderer (and its
// [ThinkingChunking] tuning) has one home instead of growing that file.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

/**
 * [T-thinking-render-perf-android] / [#467] Full-screen viewer for thinking
 * content that exceeds the inline hard cap.
 *
 * The whole reasoning text is drawn here (the inline block shows only a tail),
 * and Compose `Text` measures its entire string, so it is cut into chunks by
 * [ThinkingChunking] and rendered as lazy items: the first batch is bounded
 * (10 KB) and more follow as the end scrolls into view. A batch whose frame
 * took too long switches the pacing to one chunk at a time, so a device that
 * cannot keep up is never frozen by automatic loading.
 */
@Composable
internal fun ThinkingFullContentDialog(content: String, onDismiss: () -> Unit) {
    val textColor = MaterialTheme.colorScheme.onSurface
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_thinking_header),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF007AFF),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    MinisTextButton(onClick = onDismiss) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                }
                val chunks = remember(content) { ThinkingChunking.chunks(content) }
                var revealed by remember(content) {
                    mutableStateOf(ThinkingChunking.initialCount(chunks))
                }
                var pacing by remember(content) { mutableStateOf(ThinkingChunking.Pacing()) }
                val listState = rememberLazyListState()
                val nearEnd by remember {
                    derivedStateOf {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        last >= revealed - 2
                    }
                }
                LaunchedEffect(nearEnd, revealed) {
                    if (!nearEnd) return@LaunchedEffect
                    if (!ThinkingChunking.hasMore(revealed, chunks.size)) return@LaunchedEffect
                    val started = withFrameMillis { it }
                    revealed = ThinkingChunking.nextCount(revealed, chunks.size, pacing)
                    val drawn = withFrameMillis { it }
                    pacing = ThinkingChunking.paceAfter(pacing, drawn - started)
                }
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    LazyColumn(state = listState) {
                        items(revealed) { index ->
                            Text(
                                text = ThinkingChunking.chunkText(content, chunks, index),
                                fontSize = 13.sp,
                                color = textColor,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
