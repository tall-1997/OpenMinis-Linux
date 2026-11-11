package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.lifecycle.LifecycleHooks
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisTextButton

/**
 * [GH#131] 生命周期 Hook 设置页。
 *
 * 三个事件各一行；点开编辑一条 guest 命令（脚本路径 / 单行命令）。总开关
 * 默认关：只有用户主动打开，脚本才会在会话事件上执行。页面同时展示每个
 * 事件最近一次执行结果，失败无需去日志页翻。
 *
 * 扩展点：SKILL.md frontmatter 双轨（见 [LifecycleHooks] KDoc）落地时，这里
 * 增加一个只读的“来自技能”分组即可，不需要改存储格式。
 */
@Composable
fun LifecycleHooksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(LifecycleHooks.isEnabled()) }
    var editing by remember { mutableStateOf<LifecycleHooks.LifecycleEvent?>(null) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    SettingsScaffold(title = stringResource(R.string.lifecycle_hooks_title), onBack = onBack) {
        SettingsSection(
            header = stringResource(R.string.lifecycle_hooks_section_master),
            footer = stringResource(R.string.lifecycle_hooks_master_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.lifecycle_hooks_enable),
                subtitle = stringResource(R.string.lifecycle_hooks_enable_sub),
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    error = LifecycleHooks.setEnabled(context, on)
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.lifecycle_hooks_section_events),
            footer = stringResource(R.string.lifecycle_hooks_events_footer),
        ) {
            val events = LifecycleHooks.LifecycleEvent.entries
            // Resolve composable strings OUTSIDE buildString: a @Composable call
            // inside a non-composable receiver lambda is not guaranteed to be
            // accepted by the Compose compiler.
            val notSetLabel = stringResource(R.string.lifecycle_hooks_not_set)
            events.forEachIndexed { index, event ->
                val command = LifecycleHooks.commandFor(event)
                val last = LifecycleHooks.lastRunStatus(event)
                SettingsRow(
                    title = event.displayName,
                    subtitle = buildString {
                        append(if (command.isBlank()) notSetLabel else command)
                        if (last != null) append(" · ").append(last)
                    },
                    showDivider = index < events.lastIndex,
                    onClick = {
                        editing = event
                        draft = command
                        error = null
                    },
                )
            }
        }

        error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        SettingsSection(header = stringResource(R.string.lifecycle_hooks_help_header)) {
            Text(
                stringResource(R.string.lifecycle_hooks_help_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    editing?.let { event ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(event.displayName) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.lifecycle_hooks_command_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    DialogTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            error = null
                        },
                        singleLine = false,
                        maxLines = 6,
                        placeholder = "bash /var/minis/hooks/on-response.sh",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    error?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    val failure = LifecycleHooks.setCommand(context, event, draft)
                    error = failure
                    if (failure == null) {
                        editing = null
                    }
                }) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { editing = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
