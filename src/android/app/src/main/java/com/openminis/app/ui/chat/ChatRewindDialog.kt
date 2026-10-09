package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.harness.checkpoint.RewindScope

/**
 * [T-checkpoint-rewind] rewind 的 UI 宿主：事件提示（带「撤销回滚」动作的
 * Snackbar）+ 撤回范围选择对话框。
 *
 * 独立成文件的原因：ChatScreen 与 ChatUserMessageUI 都已顶满行数棘轮基线
 * （7572 / 556），把这块 UI 单独放可以让两个文件各自只增加一两行调用。
 */
@Composable
internal fun ChatRewindHost(
    viewModel: ChatViewModel,
    targetMessageId: String?,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    // 成功 / 失败 / 无锚点的提示都走这里；成功时附带「撤销回滚」动作
    //（Snackbar 而不是 Toast —— Toast 承载不了动作）。
    LaunchedEffect(viewModel, snackbarHostState) {
        RewindEvents.events.collect { event ->
            val outcome = snackbarHostState.showSnackbar(
                message = event.text,
                actionLabel = context.getString(R.string.chat_rewind_undo),
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            // 撤销必须查发起会话：CONVERSATION/BOTH 回溯后 UI 已切到 fork，
            // activeSessionId 是 fork，undo 记录却记在原会话名下
            //（[T-p1-8-undo-session-binding]）。
            if (outcome == SnackbarResult.ActionPerformed) viewModel.undoLastRewind(event.initiatorSessionId)
        }
    }

    val messageId = targetMessageId ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rewind_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.chat_rewind_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RewindScopeOption(
                    label = stringResource(R.string.chat_rewind_code),
                    description = stringResource(R.string.chat_rewind_code_desc),
                    icon = Icons.Default.Code,
                    onClick = { onDismiss(); viewModel.rewindToMessage(messageId, RewindScope.CODE, onOpenSession) },
                )
                RewindScopeOption(
                    label = stringResource(R.string.chat_rewind_conversation),
                    description = stringResource(R.string.chat_rewind_conversation_desc),
                    icon = Icons.Default.History,
                    onClick = { onDismiss(); viewModel.rewindToMessage(messageId, RewindScope.CONVERSATION, onOpenSession) },
                )
                RewindScopeOption(
                    label = stringResource(R.string.chat_rewind_both),
                    description = stringResource(R.string.chat_rewind_both_desc),
                    icon = Icons.AutoMirrored.Filled.Undo,
                    onClick = { onDismiss(); viewModel.rewindToMessage(messageId, RewindScope.BOTH, onOpenSession) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * [T-checkpoint-rewind] 长按菜单里的「撤回到此轮」条目。
 *
 * 放在本文件的原因：ChatUserMessageUI 顶满行数棘轮基线，内联一个
 * DropdownMenuItem 会超线；这里不受该约束，菜单项的实现细节也集中在一处。
 */
@Composable
internal fun RewindMenuEntry(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_rewind_menu)) },
        onClick = onClick,
        leadingIcon = {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

/** 撤回范围选项行：图标 + 标题 + 说明，整行可点。 */
@Composable
private fun RewindScopeOption(
    label: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
