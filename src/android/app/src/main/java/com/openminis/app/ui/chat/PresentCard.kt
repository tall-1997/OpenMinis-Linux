package com.openminis.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.sandbox.SessionWorkspace
import com.openminis.app.tools.PresentTool
import com.openminis.app.ui.sandbox.FileItem
import org.json.JSONObject
import java.io.File

/**
 * [T-android-present-tool] Deliverable card for a completed `present` call.
 * The tool copied the agent's output into `filesDir/minis-global/shared/`
 * (system Files app: Minis Ultra > shared); this card surfaces the copy with
 * Open (in-app [FilePreviewScreen] via the shared previewFileOrBrowser
 * channel) and Share (FileProvider + ACTION_SEND, mirroring
 * FilePreviewScreen.shareFile) actions.
 *
 * The card is pure presentation over the AssistantBlock: the target file is
 * recovered from the tool result's `Path:` line (see
 * [PresentTool.parseSharedGuestPath]) because ToolExecutionResult has no
 * attachment field and the ChatViewModel layer is not on this feature's
 * allowed-file list. Same parse-from-content pattern as
 * ToolOutputSpill.parseGuestPath.
 */
@Composable
internal fun PresentDeliverableCard(
    block: AssistantBlock,
    sessionId: String,
    onOpenFile: (FileItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val guestPath = remember(block.id, block.content) {
        PresentTool.parseSharedGuestPath(block.content)
    }
    // Global shared/ is session-independent, so resolveGuestPath's sessionId
    // parameter is unused on this branch — any chat's card resolves the same
    // host file.
    val hostFile = remember(guestPath, sessionId) {
        guestPath?.let {
            SessionWorkspace.resolveGuestPath(context.filesDir, sessionId, it)
        }?.takeIf { it.isFile }
    }
    val args = remember(block.toolArgs) {
        runCatching { JSONObject(block.toolArgs) }.getOrDefault(JSONObject())
    }
    val title = args.optString("title").trim()
    val note = args.optString("note").trim()
    val fileName = hostFile?.name
        ?: guestPath?.substringAfterLast('/')
        ?: args.optString("path").substringAfterLast('/').trim()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    toolIconFor(PresentTool.NAME),
                    contentDescription = null,
                    tint = toolAccentColor(PresentTool.NAME),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title.ifBlank { fileName },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (title.isNotBlank() && fileName.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.present_card_shared_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = hostFile != null,
                    onClick = {
                        val f = hostFile ?: return@TextButton
                        onOpenFile(
                            FileItem(
                                file = f,
                                name = f.name,
                                isDirectory = false,
                                isSymlink = false,
                                size = f.length(),
                                modifiedMs = f.lastModified(),
                            )
                        )
                    },
                ) { Text(stringResource(R.string.browser_downloads_open)) }
                TextButton(
                    enabled = hostFile != null,
                    onClick = { hostFile?.let { sharePresentedFile(context, it) } },
                ) { Text(stringResource(R.string.common_share)) }
            }
        }
    }
}

/**
 * Share sheet for the archived copy. The shared directory sits under
 * filesDir/minis-global/, already exposed by the FileProvider "global"
 * files-path root, so getUriForFile cannot throw "configured root" here.
 */
private fun sharePresentedFile(context: android.content.Context, file: File) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(
                intent,
                context.getString(R.string.file_share_chooser_title),
            ),
        )
    } catch (e: Exception) {
        com.openminis.app.logging.AppLogger.warning(
            "PresentCard",
            "share failed for ${file.name}: ${e.message}",
        )
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.file_share_failed_toast, e.message ?: ""),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}
