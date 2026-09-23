package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.ToolLimitPrefs

@Composable
fun ToolLimitsSettingsScreen(onBack: () -> Unit) {
    val revision by ToolLimitPrefs.revision.collectAsState()
    val shell = remember(revision) { ToolLimitPrefs.shellTimeoutSec() }
    val chars = remember(revision) { ToolLimitPrefs.fileReadMaxChars() }
    val lines = remember(revision) { ToolLimitPrefs.fileReadMaxLines() }
    val turns = remember(revision) { ToolLimitPrefs.subagentMaxTurns() }

    SettingsScaffold(title = stringResource(R.string.tool_limits_title), onBack = null) {
        SettingsSection(
            header = stringResource(R.string.tool_limits_header),
            footer = stringResource(R.string.tool_limits_footer),
        ) {
            LimitStepper(
                title = stringResource(R.string.tool_limits_shell),
                valueLabel = stringResource(R.string.tool_limits_shell_value, shell),
                value = shell,
                min = ToolLimitPrefs.MIN_SHELL_TIMEOUT_SEC,
                max = ToolLimitPrefs.MAX_SHELL_TIMEOUT_SEC,
                step = ToolLimitPrefs.SHELL_STEP_SEC,
                onChange = { ToolLimitPrefs.setShellTimeoutSec(it) },
                showDivider = true,
            )
            LimitStepper(
                title = stringResource(R.string.tool_limits_file_chars),
                valueLabel = chars.toString(),
                value = chars,
                min = ToolLimitPrefs.MIN_FILE_READ_MAX_CHARS,
                max = ToolLimitPrefs.MAX_FILE_READ_MAX_CHARS,
                step = ToolLimitPrefs.FILE_CHARS_STEP,
                onChange = { ToolLimitPrefs.setFileReadMaxChars(it) },
                showDivider = true,
            )
            LimitStepper(
                title = stringResource(R.string.tool_limits_file_lines),
                valueLabel = if (lines == 0) {
                    stringResource(R.string.tool_limits_file_lines_unlimited)
                } else {
                    lines.toString()
                },
                value = lines,
                min = 0,
                max = ToolLimitPrefs.MAX_FILE_READ_MAX_LINES,
                step = ToolLimitPrefs.FILE_LINES_STEP,
                onChange = { ToolLimitPrefs.setFileReadMaxLines(it) },
                showDivider = true,
            )
            LimitStepper(
                title = stringResource(R.string.tool_limits_subagent),
                valueLabel = turns.toString(),
                value = turns,
                min = ToolLimitPrefs.MIN_SUBAGENT_MAX_TURNS,
                max = ToolLimitPrefs.MAX_SUBAGENT_MAX_TURNS,
                step = ToolLimitPrefs.TURNS_STEP,
                onChange = { ToolLimitPrefs.setSubagentMaxTurns(it) },
                showDivider = false,
            )
        }
    }
}

@Composable
private fun LimitStepper(
    title: String,
    valueLabel: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
    showDivider: Boolean,
) {
    SettingsRow(
        title = title,
        subtitle = valueLabel,
        showChevron = false,
        showDivider = showDivider,
        trailing = {
            PlusMinusStepper(
                value = value,
                min = min,
                max = max,
                step = step,
                onValueChange = onChange,
                decreaseContentDescription = stringResource(R.string.tool_limits_decrease),
                increaseContentDescription = stringResource(R.string.tool_limits_increase),
            )
        },
    )
}
