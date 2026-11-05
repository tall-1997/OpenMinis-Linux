package com.openminis.app.ui.settings
import com.openminis.app.ui.theme.m3Icon

import com.openminis.app.R
import com.openminis.app.text.BoundedText
import com.openminis.app.ui.components.MinisTextButton

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
// Log management screen: Logging switch, daily Log Files list, and Delete All Logs. Mirrors iOS LogManagementView.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogManagementScreen(
    onBack: () -> Unit,
    onLogFileClick: (fileName: String) -> Unit = {},
    onOpenLogcat: () -> Unit = {},
) {
    val context = LocalContext.current
    // T-logs-perf: stat once on Dispatchers.IO (capped daily/crash lists), render LogFileMeta directly.
    val dailyLogs = remember { mutableStateListOf<AppLogger.LogFileMeta>() }
    val crashLogs = remember { mutableStateListOf<AppLogger.LogFileMeta>() }
    var totalSize by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var loggingEnabled by remember { mutableStateOf(AppLogger.isEnabled(context)) }

    // T-config: Logs / Config Changes segmented control; `?tab=config-audit` deep-link lands on the audit list.
    val initialTab = remember {
        com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingLogsTab() ?: "logs"
    }
    var tab by remember { mutableStateOf(initialTab) }

    val refreshTrigger = remember { mutableStateOf(0) }
    LaunchedEffect(refreshTrigger.value) {
        loading = true
        // Daily logs `minis-*.log`; crash files (`crash-…` + `native-crash-…`) merged into one chronological list.
        val (daily, crash, total) = withContext(Dispatchers.IO) {
            val d = AppLogger.listLogFileMetas(prefix = "minis-", limit = 100)
            val cJ = AppLogger.listLogFileMetas(prefix = "crash-", limit = 100)
            val cN = AppLogger.listLogFileMetas(prefix = "native-crash-", limit = 100)
            // Merge Java + native crashes, newest-first by name, cap.
            val merged = (cJ + cN).sortedByDescending { it.name }.take(100)
            // totalSize sums only the displayed rows (over-cap files stay on disk, excluded from the view).
            val sum = (d.sumOf { it.sizeBytes }) + (merged.sumOf { it.sizeBytes })
            Triple(d, merged, sum)
        }
        dailyLogs.clear(); dailyLogs.addAll(daily)
        crashLogs.clear(); crashLogs.addAll(crash)
        totalSize = total
        loading = false
    }
    fun refresh() { refreshTrigger.value++ }

    SettingsScaffold(
        title = stringResource(R.string.log_title),
        onBack = null,
        scrollable = false,
    ) {
        // Segmented selector stays visible as the body scrolls.
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SegmentedButton(
                selected = tab == "logs",
                onClick = { tab = "logs" },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.log_title)) }
            SegmentedButton(
                selected = tab == "config-audit",
                onClick = { tab = "config-audit" },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.logs_tab_config_changes)) }
        }

        if (tab == "config-audit") {
            // Audit body. Owns its own scrolling.
            ConfigAuditScreen(modifier = Modifier.fillMaxSize())
        } else {
            // Logs body — original layout.
            LogsBody(
                context = context,
                dailyLogs = dailyLogs,
                crashLogs = crashLogs,
                totalSize = totalSize,
                loading = loading,
                loggingEnabled = loggingEnabled,
                onToggleLogging = {
                    loggingEnabled = it
                    AppLogger.setEnabled(context, it)
                },
                onLogFileClick = onLogFileClick,
                onOpenLogcat = onOpenLogcat,
                onDeleteAll = { showDeleteAllConfirm = true },
            )
        }
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text(stringResource(R.string.log_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.log_delete_confirm_text, Formatter.formatFileSize(context, totalSize)))
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    AppLogger.clearLogs()
                    refresh()
                    showDeleteAllConfirm = false
                }) {
                    Text(stringResource(R.string.log_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Original logs-tab body, extracted so the segmented selector can switch into the audit view. */
@Composable
private fun LogsBody(
    context: Context,
    dailyLogs: List<AppLogger.LogFileMeta>,
    crashLogs: List<AppLogger.LogFileMeta>,
    totalSize: Long,
    loading: Boolean,
    loggingEnabled: Boolean,
    onToggleLogging: (Boolean) -> Unit,
    onLogFileClick: (fileName: String) -> Unit,
    onOpenLogcat: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val anyFiles = dailyLogs.isNotEmpty() || crashLogs.isNotEmpty()
    var showContextSnapshot by remember { mutableStateOf(false) }
    var showAnswerVersions by remember { mutableStateOf(false) }
    var selectedVersionFile by remember { mutableStateOf<java.io.File?>(null) }
    var showStreamTraces by remember { mutableStateOf(false) }
    var selectedTraceFile by remember { mutableStateOf<java.io.File?>(null) }
    var streamTraceEnabled by remember {
        mutableStateOf(com.openminis.app.provider.StreamTraceRecorder.enabled)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        // ── Logging ─────────────────────────────────────────────────────
        LoggingSection(
            loggingEnabled = loggingEnabled,
            onToggleLogging = onToggleLogging,
            onOpenLogcat = onOpenLogcat,
        )

        // [T-stream-trace-live] 轨迹录制开关与回放入口。
        SettingsSection(
            header = stringResource(R.string.log_stream_trace_section),
            footer = stringResource(R.string.log_stream_trace_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.log_stream_trace_record),
                checked = streamTraceEnabled,
                onCheckedChange = { value ->
                    streamTraceEnabled = value
                    com.openminis.app.provider.StreamTraceRecorder.setEnabled(value, context)
                    if (value) com.openminis.app.provider.StreamTraceRecorder.newSession()
                },
                showDivider = true,
            )
            SettingsRow(
                title = stringResource(R.string.log_stream_trace_browse),
                subtitle = stringResource(R.string.log_stream_trace_browse_sub),
                onClick = { showStreamTraces = true },
                showDivider = false,
            )
        }

        if (showStreamTraces) {
            // [T-android-sessionlist-prefs-io] Empty first frame; real list from Dispatchers.IO.
            var traceFiles by remember { mutableStateOf<List<File>>(emptyList()) }
            var traceFilesLoaded by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                traceFiles = withContext(Dispatchers.IO) {
                    val tracesDir = com.openminis.app.provider.StreamTraceRecorder.dir(context)
                    tracesDir.listFiles()
                        ?.sortedByDescending { it.lastModified() }?.take(30)
                        ?: emptyList()
                }
                traceFilesLoaded = true
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showStreamTraces = false },
                title = { Text(stringResource(R.string.log_stream_trace_dialog_title)) },
                text = {
                    if (!traceFilesLoaded) {
                        CircularProgressIndicator()
                    } else if (traceFiles.isEmpty()) {
                        Text(stringResource(R.string.log_stream_trace_empty))
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            traceFiles.forEach { f ->
                                TextButton(onClick = { selectedTraceFile = f }) {
                                    Text(
                                        f.name,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showStreamTraces = false }) { Text(stringResource(R.string.common_close)) }
                },
            )
        }

        selectedTraceFile?.let { f ->
            // [T-android-sessionlist-prefs-io] readLines() + JSON parsing off the main thread.
            var traceBody by remember(f) { mutableStateOf<String?>(null) }
            LaunchedEffect(f) {
                traceBody = withContext(Dispatchers.IO) {
                    runCatching {
                        f.readLines()
                            .takeLast(400)
                            .joinToString("\n") { line ->
                                runCatching {
                                    val obj = org.json.JSONObject(line)
                                    when (obj.optString("kind")) {
                                        "text" -> "text: ${obj.optString("text").take(120)}"
                                        "thinking" -> "thinking: ${obj.optString("text").take(120)}"
                                        "reasoning" -> "reasoning: ${obj.optString("content").take(120)}"
                                        "tool_use_start" -> "tool_use_start: ${obj.optString("name")}"
                                        "tool_call_complete" -> "tool_call_complete: ${obj.optString("name")}"
                                        "finished" -> "finished: ${obj.optString("stopReason")}"
                                        else -> obj.optString("kind")
                                    }
                                }.getOrDefault(line.take(120))
                            }
                    }.getOrDefault(context.getString(R.string.log_read_failed))
                }
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { selectedTraceFile = null },
                title = { Text(f.name) },
                text = {
                    val body = traceBody
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (body == null) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                body,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedTraceFile = null }) { Text(stringResource(R.string.common_close)) }
                },
            )
        }

        // [T-context-assembly-preview] Context-assembly snapshot entry (what the model actually received).
        SettingsSection(header = stringResource(R.string.log_context_snapshot_section)) {
            SettingsRow(
                title = stringResource(R.string.log_context_snapshot_view),
                subtitle = stringResource(R.string.log_context_snapshot_sub),
                onClick = { showContextSnapshot = true },
                showDivider = false,
            )
        }

        // [T-answer-versions] 被重试覆盖的旧回答归档入口：不丢数据的第二份保险。
        SettingsSection(header = stringResource(R.string.log_answer_versions_section)) {
            SettingsRow(
                title = stringResource(R.string.log_answer_versions_browse),
                subtitle = stringResource(R.string.log_answer_versions_sub),
                onClick = { showAnswerVersions = true },
                showDivider = false,
            )
        }

        if (showAnswerVersions) {
            // [T-android-sessionlist-prefs-io] Directory scan off the composition body.
            var versionFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
            var versionFilesLoaded by remember { mutableStateOf(false) }
            var versionDirUnavailable by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // [T-answer-versions-guest-path] Archive lives under the workspace bind (host path); enumerate every session/project candidate and merge.
                val (files, sawDir) = withContext(Dispatchers.IO) {
                    answerVersionArchives(context)
                }
                versionFiles = files
                versionDirUnavailable = !sawDir
                versionFilesLoaded = true
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAnswerVersions = false },
                title = { Text(stringResource(R.string.log_answer_versions_dialog_title)) },
                text = {
                    if (!versionFilesLoaded) {
                        CircularProgressIndicator()
                    } else if (versionFiles.isEmpty()) {
                        Text(
                            stringResource(
                                if (versionDirUnavailable) R.string.log_answer_versions_dir_unavailable
                                else R.string.log_answer_versions_empty
                            )
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            versionFiles.forEach { f ->
                                TextButton(onClick = { selectedVersionFile = f }) {
                                    Text(
                                        f.name,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAnswerVersions = false }) { Text(stringResource(R.string.common_close)) }
                },
            )
        }

        selectedVersionFile?.let { f ->
            // [T-android-sessionlist-prefs-io] One whole-archive read, on IO.
            var versionBody by remember(f) { mutableStateOf<String?>(null) }
            LaunchedEffect(f) {
                versionBody = withContext(Dispatchers.IO) {
                    runCatching {
                        val raw = f.readText()
                        org.json.JSONObject(raw).optString("partsJson", raw).take(3_000)
                    }.getOrDefault(context.getString(R.string.log_read_failed))
                }
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { selectedVersionFile = null },
                title = { Text(f.name) },
                text = {
                    val body = versionBody
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (body == null) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                body,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedVersionFile = null }) { Text(stringResource(R.string.common_close)) }
                },
            )
        }

        if (showContextSnapshot) {
            val snapshot = com.openminis.app.agent.ContextAssemblySnapshot.latest
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showContextSnapshot = false },
                title = { Text(stringResource(R.string.log_context_snapshot_dialog_title)) },
                text = {
                    val body = snapshot?.let { s ->
                        buildString {
                            append(context.getString(R.string.log_context_snapshot_total_chars, s.totalChars)).append("\n\n")
                            s.sections.forEach { (label, len) ->
                                append(context.getString(R.string.log_context_snapshot_line, len, label)).append("\n")
                            }
                            append(context.getString(R.string.log_context_snapshot_full_note))
                        }
                    } ?: context.getString(R.string.log_context_snapshot_none)
                    androidx.compose.foundation.rememberScrollState().let { scroll ->
                        Text(
                            text = body,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(scroll),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showContextSnapshot = false }) { Text(stringResource(R.string.common_close)) }
                },
            )
        }

        // Show a spinner while the IO load is in flight (avoids a brief "no logs yet" flash).
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        // ── Log Files (daily) ───────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.log_section_files),
            footer = stringResource(R.string.log_section_files_footer),
        ) {
            if (dailyLogs.isEmpty()) {
                SettingsValueRow(
                    title = stringResource(R.string.log_no_files),
                    value = "",
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    showDivider = false,
                )
            } else {
                dailyLogs.forEachIndexed { index, meta ->
                    SettingsValueRow(
                        title = meta.name,
                        value = Formatter.formatFileSize(context, meta.sizeBytes),
                        onClick = { onLogFileClick(meta.name) },
                        showDivider = index < dailyLogs.size - 1,
                    )
                }
            }
        }

        // ── Crash Logs (T283) — only show when present ──────────────────
        if (crashLogs.isNotEmpty()) {
            SettingsSection(
                header = stringResource(R.string.log_crash_header),
                footer = stringResource(R.string.log_crash_footer, crashLogs.size),
            ) {
                crashLogs.forEachIndexed { index, meta ->
                    SettingsValueRow(
                        title = meta.name,
                        value = Formatter.formatFileSize(context, meta.sizeBytes),
                        onClick = { onLogFileClick(meta.name) },
                        showDivider = index < crashLogs.size - 1,
                    )
                }
            }
        }

        // ── Storage / Delete All (only when files exist) ───────────────
        if (anyFiles) {
            SettingsSection(
                header = stringResource(R.string.log_section_storage),
                footer = stringResource(R.string.log_section_storage_footer, Formatter.formatFileSize(context, totalSize)),
            ) {
                SettingsRow(
                    title = stringResource(R.string.log_delete_all_title),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = onDeleteAll,
                    showChevron = false,
                    showDivider = false,
                    trailing = {
                        Text(
                            Formatter.formatFileSize(context, totalSize),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─── Log Detail Screen ─────────────────────────────────────────────────────────

// T176: lazy line-index loader — one sequential IO pass records newline byte-offsets; readLine(i) seeks + reads on demand; LazyColumn renders only visible rows; DisposableEffect closes the RAF.
private class LazyLogFile private constructor(
    private val raf: RandomAccessFile,
    val lineOffsets: LongArray,
) {
    val lineCount: Int get() = lineOffsets.size

    @Synchronized
    fun readLine(index: Int): String {
        if (index !in 0 until lineCount) return ""
        return try {
            raf.seek(lineOffsets[index])
            // Read raw bytes up to the next newline and decode UTF-8 (RAF.readLine mangles CJK).
            val buf = ByteArray(8 * 1024)
            val sb = StringBuilder()
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                var done = false
                for (i in 0 until n) {
                    if (buf[i] == '\n'.code.toByte()) {
                        sb.append(String(buf, 0, i, Charsets.UTF_8))
                        done = true
                        break
                    }
                }
                if (done) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    fun close() = try { raf.close() } catch (_: Exception) {}

    companion object {
        fun open(file: File): LazyLogFile {
            val raf = RandomAccessFile(file, "r")
            // First-line offset is 0; subsequent offsets sit after each newline.
            val offsets = mutableListOf(0L)
            val buf = ByteArray(64 * 1024)
            var pos = 0L
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    if (buf[i] == '\n'.code.toByte()) {
                        offsets.add(pos + i + 1)
                    }
                }
                pos += n
            }
            // Drop a trailing empty offset so a file ending in \n doesn't show a blank ghost row.
            val arr = if (offsets.size > 1 && offsets.last() >= raf.length()) {
                offsets.dropLast(1).toLongArray()
            } else {
                offsets.toLongArray()
            }
            // Reset cursor — readLine will seek per call.
            raf.seek(0)
            return LazyLogFile(raf, arr)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDetailScreen(
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var lazyLog by remember(fileName) { mutableStateOf<LazyLogFile?>(null) }
    var loadError by remember(fileName) { mutableStateOf<String?>(null) }
    var loading by remember(fileName) { mutableStateOf(true) }

    LaunchedEffect(fileName) {
        loading = true
        loadError = null
        lazyLog = withContext(Dispatchers.IO) {
            try {
                val file = File(File(context.filesDir, "logs"), fileName)
                if (!file.exists()) {
                    loadError = context.getString(R.string.log_not_found)
                    null
                } else {
                    LazyLogFile.open(file)
                }
            } catch (e: Exception) {
                loadError = context.getString(R.string.log_error_reading, e.message ?: "")
                null
            }
        }
        loading = false
    }

    DisposableEffect(fileName) {
        onDispose { lazyLog?.close() }
    }

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(m3Icon(Icons.AutoMirrored.Filled.ArrowBack), contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val file = File(File(context.filesDir, "logs"), fileName)
                        if (file.exists()) shareLogFile(context, file)
                    }) {
                        Icon(m3Icon(Icons.Default.Share), contentDescription = stringResource(R.string.common_share))
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            loadError != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        loadError ?: "",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            lazyLog != null -> {
                val log = lazyLog!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    // [T-android-log-line-keys] Stable key = line number (the snapshot is immutable, so line-number identity is stable).
                    items(
                        count = log.lineCount,
                        key = { i -> i },
                    ) { i ->
                        Text(
                            text = log.readLine(i),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

// ─── Share Utilities ───────────────────────────────────────────────────────────

// [T-answer-versions-guest-path] Union of every host dir backing `/var/minis/workspace/offloads/answer-versions` (per-project + per-session), newest-first; returns files + whether any dir was present.
internal fun answerVersionArchives(context: Context): Pair<List<File>, Boolean> {
    val filesDir = context.filesDir
    val sw = com.openminis.app.sandbox.SessionWorkspace
    val workspaceRoots = buildList {
        File(filesDir, sw.WORKSPACES_DIR).listFiles()?.forEach { add(File(it, "workspace")) }
        File(filesDir, sw.SESSIONS_DIR).listFiles()?.forEach { add(File(it, "workspace")) }
    }
    var sawDir = false
    val files = workspaceRoots.flatMap { root ->
        val dir = File(root, "offloads/answer-versions")
        val listed = dir.listFiles()
        if (listed != null) {
            sawDir = true
            listed.toList()
        } else {
            emptyList()
        }
    }.sortedByDescending { it.lastModified() }.take(30)
    return files to sawDir
}

private fun shareLogFile(context: Context, file: File) {
    // [T-share-fileprovider-unify] Shared FileProvider helper; EXTRA_TEXT fallback (a log is still useful as text).
    val shared = shareViaFileProvider(
        context, file, "text/plain", context.getString(R.string.log_share_chooser),
    )
    if (shared) return
    // Log the FileProvider failure (previously swallowed, masking config bugs as "share pastes text").
    AppLogger.warning(
        "LogShare",
        "FileProvider share failed for ${file.name} — falling back to EXTRA_TEXT",
    )
    val text = try { BoundedText.readFileTail(file, 100_000) } catch (_: Exception) { return }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.log_share_chooser)))
}
