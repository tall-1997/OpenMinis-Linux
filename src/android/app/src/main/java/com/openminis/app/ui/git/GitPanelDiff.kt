package com.openminis.app.ui.git

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R

/** Full-screen diff overlay shared by working-tree and commit file diffs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiffOverlay(diff: GitDiffView, onClose: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(diff.title, maxLines = 1, style = MaterialTheme.typography.titleSmall) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.git_panel_close_diff),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                diff.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                diff.content.isBlank() -> Text(
                    stringResource(R.string.git_panel_empty_diff),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        // (#13) colorizeDiff walks the whole diff line-by-line
                        // building spans — it used to re-run on EVERY
                        // recomposition of this panel (it took no remember at
                        // all), so an unrelated state change re-colored an
                        // arbitrarily large diff. Memoize per content.
                        // [T-git-diff-truncation-hint] Both sources clip at
                        // 200k chars (GitCli + GitHostRepo DIFF_MAX_CHARS);
                        // a trailing ellipsis alone read as diff content.
                        // The tail marker (a bare … line appended by
                        // GitHostRepo) tells the user the view is partial.
                        if (diff.content.trimEnd().endsWith("…")) {
                            Text(
                                stringResource(R.string.git_panel_diff_truncated),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        val colored = remember(diff.content) { colorizeDiff(diff.content) }
                        Text(
                            colored,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Unified-diff colouring: additions green, deletions red, hunk headers blue,
 * file/index metadata gray. Whole overlay is one selectable Text so users can
 * copy hunks — same interaction the chat's inline diff preview offers.
 */
internal fun colorizeDiff(diff: String) = buildAnnotatedString {
    diff.lineSequence().forEachIndexed { index, line ->
        if (index > 0) append("\n")
        val style = when {
            line.startsWith("+++") || line.startsWith("---") ->
                SpanStyle(color = GitMetaGrey, fontWeight = FontWeight.Bold)
            line.startsWith("+") -> SpanStyle(color = GitAddGreen)
            line.startsWith("-") -> SpanStyle(color = GitDelRed)
            line.startsWith("@@") -> SpanStyle(color = GitHunkBlue)
            line.startsWith("diff ") || line.startsWith("index ") ||
                line.startsWith("new file") || line.startsWith("deleted file") ||
                line.startsWith("similarity") || line.startsWith("rename") ||
                line.startsWith("old mode") || line.startsWith("new mode") ||
                line.startsWith("Binary files") -> SpanStyle(color = GitMetaGrey)
            else -> SpanStyle()
        }
        withStyle(style) { append(line) }
    }
}
