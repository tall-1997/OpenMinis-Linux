package com.openminis.app.ui.settings.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.backup.BackupCategory
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.theme.ChatColors

// The restore report's two severity sections. Moved out of
// BackupAndRestoreScreen, whose architecture-policy baseline pins it at a
// ceiling it may shrink under but never grow past: the screen has to give a
// line for every line it takes. Same package, same code.

@Composable
internal fun RestoreReport(
    report: com.openminis.app.backup.BackupImporter.Report,
    onDone: () -> Unit,
) {
    SettingsSection(header = stringResource(R.string.backup_restore_complete)) {
        Column(Modifier.padding(16.dp)) {
            InfoLine(stringResource(R.string.backup_report_restored), report.totalImported.toString())
            InfoLine(stringResource(R.string.backup_report_updated), report.totalUpdated.toString())
            InfoLine(stringResource(R.string.backup_report_up_to_date), report.totalSkipped.toString())
            if (report.totalMissingBlobs > 0) {
                InfoLine(stringResource(R.string.backup_report_missing_files), report.totalMissingBlobs.toString())
            }
        }
    }

    // Per-category outcomes. The totals above say a restore had issues; these
    // say WHICH data was affected and why, which is the difference between
    // "something went wrong" and knowing whether it matters. Each line names
    // the category, as on iOS.
    //
    // [T-android-restore-report-severity] Split by severity. Everything used to
    // render in the error colour, so a file the SOURCE device deliberately left
    // out — over the size cap, or never downloaded there — read as if the
    // restore had broken. Those are the backup's own policy, recorded as
    // tombstones in the index, and they get their own amber section.
    //
    // Which counter is which severity is decided in restoreIssues(), not here,
    // so the rule is tested as data rather than inferred from colours.
    val issues = restoreIssues(report)
    val lines = issues.map { issue ->
        val name = BackupCategory.fromKey(issue.categoryKey)
            ?.let { stringResource(categoryNameRes(it)) } ?: issue.categoryKey
        issue.severity to when (issue.kind) {
            RestoreIssueKind.FAILED ->
                stringResource(R.string.backup_report_failed, name, issue.detail.orEmpty())
            RestoreIssueKind.MISSING_FROM_PACKAGE ->
                stringResource(R.string.backup_report_missing_from_package, name, issue.count)
            RestoreIssueKind.UNREADABLE ->
                stringResource(R.string.backup_report_unreadable, name, issue.count)
            RestoreIssueKind.SIZE_SKIPPED ->
                stringResource(R.string.backup_report_size_skipped, name, issue.count)
            RestoreIssueKind.NOT_DOWNLOADED ->
                stringResource(R.string.backup_report_not_downloaded, name, issue.count)
        }
    }
    val errors = lines.filter { it.first == RestoreIssueSeverity.ERROR }.map { it.second }
    // report.warnings are advisories (count mismatches and the like), already
    // sentences rather than counts; they read as notices, not failures.
    val notices = lines.filter { it.first == RestoreIssueSeverity.NOTICE }.map { it.second } +
        report.warnings
    if (errors.isNotEmpty()) {
        SettingsSection(header = stringResource(R.string.backup_report_issues)) {
            Column(Modifier.padding(16.dp)) {
                errors.forEach {
                    ReportLine(it, Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (notices.isNotEmpty()) {
        SettingsSection(
            header = stringResource(R.string.backup_report_notices),
            footer = stringResource(R.string.backup_report_notices_footer),
        ) {
            Column(Modifier.padding(16.dp)) {
                val amber = reportNoticeColor()
                notices.forEach { ReportLine(it, Icons.Outlined.Warning, amber) }
            }
        }
    }

    // 3-way credentials message, keyed on the providers category (iOS parity).
    val prov = report.categories.firstOrNull {
        it.category == BackupCategory.PROVIDERS.key
    }
    if (prov != null) {
        val msg = when {
            prov.credentialsRestored > 0 ->
                stringResource(R.string.backup_creds_restored, prov.credentialsRestored)
            prov.credentialsKept > 0 ->
                stringResource(R.string.backup_creds_kept)
            else ->
                stringResource(R.string.backup_creds_none)
        }
        SettingsSection(footer = msg) {}
    }

    Column(Modifier.padding(16.dp)) {
        MinisButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.backup_done))
        }
    }
}

@Composable
private fun ReportLine(text: String, icon: ImageVector, tint: Color) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            // Nudged down to sit on the first text line rather than centring on
            // a paragraph that may wrap.
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/**
 * [T-android-restore-report-severity] Amber for "left out on purpose".
 *
 * Two values because no single amber works on both grounds at body-text size.
 * The commonly used #E6A23C measures 2.19:1 against a light surface — well
 * under the 4.5:1 small text needs — so it would be close to unreadable in
 * light mode. #B45309 gives 5.02:1 on light; #FFB74D gives 9.83:1 on dark.
 *
 * Keyed on ChatColors.isDark, the theme the app actually resolved, so it
 * follows the in-app Appearance setting rather than the OS one (GH#187).
 */
@Composable
private fun reportNoticeColor(): Color =
    if (ChatColors.isDark) {
        Color(0xFFFFB74D)
    } else {
        Color(0xFFB45309)
    }
