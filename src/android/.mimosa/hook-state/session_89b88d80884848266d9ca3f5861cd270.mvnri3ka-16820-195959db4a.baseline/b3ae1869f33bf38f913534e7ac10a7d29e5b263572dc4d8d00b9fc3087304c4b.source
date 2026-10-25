package com.openminis.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.DatabaseHealthCheck
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-db-startup-integrity-gate] One-shot notice shown after the startup gate
 * quarantined a corrupt `minis.db`. The app is already running on a fresh,
 * empty database — this dialog exists so the user is not left wondering
 * where their history went, and so the corrupt copy is THEIR call to keep
 * or delete (never silently erased).
 */
@Composable
fun DatabaseQuarantineDialog(
    report: DatabaseHealthCheck.StartupFailureReport,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .format(Date(report.occurredAtMs))
    val quarantinePath = report.quarantineDir?.let { File(context.filesDir, it).absolutePath }

    AlertDialog(
        onDismissRequest = {
            DatabaseHealthCheck.clearReport(context)
            onDismiss()
        },
        title = { Text(stringResource(R.string.db_quarantine_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.db_quarantine_body, whenText),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (quarantinePath != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.db_quarantine_copy_at, quarantinePath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                DatabaseHealthCheck.clearReport(context)
                onDismiss()
            }) {
                Text(stringResource(R.string.db_quarantine_keep))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                report.quarantineDir?.let { DatabaseHealthCheck.deleteQuarantine(context, it) }
                DatabaseHealthCheck.clearReport(context)
                onDismiss()
            }) {
                Text(
                    stringResource(R.string.db_quarantine_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}
