package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.logging.LogcatFilter
import com.openminis.app.logging.LogcatLevels
import com.openminis.app.logging.LogcatLine
import com.openminis.app.logging.LogcatLiveBuffer
import com.openminis.app.ui.chat.MarkdownClipboard
import com.openminis.app.ui.components.MinisCenterTopBar
import com.openminis.app.ui.theme.m3Icon
import kotlinx.coroutines.delay

/**
 * Live log panel (T-logcat-panel) — the in-app view of this process's own
 * logcat stream.
 *
 * Why a panel rather than "turn logging on and export the file": by the time
 * a person wants the stream, the failure already happened. Tailing the file
 * means scroll-hunting a multi-megabyte daily log; this screen keeps the last
 * 2000 lines in memory, filters by level and text, and never leaves the app.
 *
 * The buffer keeps filling while paused — pause freezes the *view* so a line
 * can be read, and resuming does not skip anything that arrived meanwhile.
 *
 * Buffered rows are polled at 250 ms (see [LogcatLiveBuffer]) instead of being
 * pushed into state per line: a chatty session emits tens of lines a second,
 * and per-line state writes would recompose the whole list for each one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatPanelScreen(onBack: () -> Unit) {
    val buffer = remember { LogcatLiveBuffer() }
    DisposableEffect(Unit) {
        buffer.start()
        onDispose { buffer.stop() }
    }

    var lines by remember { mutableStateOf<List<LogcatLine>>(emptyList()) }
    var dropped by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var minLevel by remember { mutableStateOf('V') }
    var query by remember { mutableStateOf("") }
    var seenVersion by remember { mutableLongStateOf(-1L) }
    val context = LocalContext.current

    LaunchedEffect(paused) {
        while (true) {
            if (!paused) {
                val v = buffer.version
                if (v != seenVersion) {
                    seenVersion = v
                    lines = buffer.snapshot()
                    dropped = buffer.droppedCount
                }
            }
            delay(250)
        }
    }

    val visible = remember(lines, minLevel, query) {
        LogcatFilter.visible(lines, minLevel, query)
    }
    val listState = rememberLazyListState()
    var previousCount by remember { mutableIntStateOf(0) }

    // Follow the tail unless the reader scrolled away. `previousCount` is
    // updated after the decision so "was I at the bottom?" is answered against
    // the list as the reader last saw it, not the list that just grew.
    LaunchedEffect(visible.size) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisible < 0 || lastVisible >= previousCount - 2
        if (!paused && nearBottom && visible.isNotEmpty()) {
            listState.scrollToItem(visible.lastIndex)
        }
        previousCount = visible.size
    }

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = { Text(stringResource(R.string.logcat_panel_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            m3Icon(Icons.AutoMirrored.Filled.ArrowBack),
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { paused = !paused }) {
                        Icon(
                            m3Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause),
                            contentDescription = stringResource(
                                if (paused) R.string.logcat_panel_resume else R.string.logcat_panel_pause,
                            ),
                        )
                    }
                    IconButton(
                        onClick = {
                            // [T-logcat-panel] Plain-text paste through the same
                            // helper the chat bubble uses; the raw logcat bytes
                            // are what a bug report needs, so this is the
                            // "copy markdown" flavour rather than a stripped one.
                            MarkdownClipboard.copyMarkdown(
                                context,
                                LogcatFilter.toClipboard(visible),
                                "logcat",
                            )
                        },
                        enabled = visible.isNotEmpty(),
                    ) {
                        Icon(
                            m3Icon(Icons.Default.ContentCopy),
                            contentDescription = stringResource(R.string.logcat_panel_copy),
                        )
                    }
                    IconButton(
                        onClick = {
                            buffer.clear()
                            lines = emptyList()
                            dropped = 0
                            seenVersion = buffer.version
                            previousCount = 0
                        },
                    ) {
                        Icon(
                            m3Icon(Icons.Default.Delete),
                            contentDescription = stringResource(R.string.logcat_panel_clear),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Filters ────────────────────────────────────────────────
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val levels = LogcatLevels.ORDER.toList()
                levels.forEachIndexed { index, level ->
                    SegmentedButton(
                        selected = minLevel == level,
                        onClick = { minLevel = level },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = levels.size),
                        label = { Text(level.toString(), fontWeight = FontWeight.SemiBold) },
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = {
                    Text(
                        stringResource(R.string.logcat_panel_search_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingIcon = { Icon(m3Icon(Icons.Default.Search), contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(m3Icon(Icons.Default.Close), contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (paused) {
                        stringResource(R.string.logcat_panel_paused_badge)
                    } else {
                        stringResource(R.string.logcat_panel_line_count, visible.size)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (dropped > 0) {
                    Text(
                        text = stringResource(R.string.logcat_panel_dropped, dropped),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Stream ─────────────────────────────────────────────────
            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.logcat_panel_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = 24.dp,
                    ),
                ) {
                    items(visible, key = { it.seq }) { line -> LogcatRow(line) }
                }
            }
        }
    }
}

/** Apple-ish severity palette; readable on both light and dark surfaces. */
private val LevelBlue = Color(0xFF3B82F6)
private val LevelGreen = Color(0xFF34C759)
private val LevelOrange = Color(0xFFFF9F0A)
private val LevelRed = Color(0xFFFF3B30)

@Composable
private fun LogcatRow(line: LogcatLine) {
    val levelColor = when (line.level) {
        'V' -> MaterialTheme.colorScheme.onSurfaceVariant
        'D' -> LevelBlue
        'I' -> LevelGreen
        'W' -> LevelOrange
        'E', 'F', 'A' -> LevelRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = buildAnnotatedString {
            if (line.time.isNotEmpty()) {
                withStyle(SpanStyle(color = muted)) {
                    append(line.time)
                    append(' ')
                }
            }
            withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Bold)) {
                append(line.level)
            }
            if (line.tag.isNotEmpty()) {
                withStyle(SpanStyle(color = muted)) { append('/') }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(line.tag) }
                withStyle(SpanStyle(color = muted)) { append(": ") }
            }
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                append(line.message)
            }
        },
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        ),
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
    )
}
