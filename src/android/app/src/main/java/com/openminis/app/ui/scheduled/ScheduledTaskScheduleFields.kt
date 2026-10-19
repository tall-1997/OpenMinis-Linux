package com.openminis.app.ui.scheduled

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledRepeatMode
import java.util.Calendar

/** Section heading shared by the scheduled-task forms. */
@Composable
internal fun SectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
}

/**
 * [T-android-scheduled-triggers] The calendar repeat picker and the weekday
 * chips it reveals.
 *
 * Extracted from the edit screen (whose line ceiling only tightens) because it
 * is one self-contained control: it owns no screen state beyond the two values
 * passed in. Only calendar tasks show it — a relative trigger states its own
 * schedule instead of offering fields that do not describe it.
 */
@Composable
internal fun RepeatSelector(
    repeatMode: ScheduledRepeatMode,
    onRepeatModeChange: (ScheduledRepeatMode) -> Unit,
    customDays: Set<Int>,
    onCustomDaysChange: (Set<Int>) -> Unit,
) {
    Column {
        SectionLabel(stringResource(R.string.scheduled_task_field_repeat))
        Spacer(Modifier.height(8.dp))
        val options = listOf(
            ScheduledRepeatMode.ONCE to R.string.scheduled_task_repeat_once,
            ScheduledRepeatMode.DAILY to R.string.scheduled_task_repeat_daily,
            ScheduledRepeatMode.WEEKDAYS to R.string.scheduled_task_repeat_weekdays,
            ScheduledRepeatMode.CUSTOM to R.string.scheduled_task_repeat_custom,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { idx, (mode, resId) ->
                SegmentedButton(
                    selected = repeatMode == mode,
                    onClick = { onRepeatModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(idx, options.size),
                ) { Text(stringResource(resId), fontSize = 13.sp) }
            }
        }
        if (repeatMode == ScheduledRepeatMode.CUSTOM) {
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val days = listOf(
                    Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue",
                    Calendar.WEDNESDAY to "Wed", Calendar.THURSDAY to "Thu",
                    Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat",
                    Calendar.SUNDAY to "Sun",
                )
                days.forEach { (dow, name) ->
                    FilterChip(
                        selected = dow in customDays,
                        onClick = {
                            val next = customDays.toMutableSet().apply {
                                if (dow in this) remove(dow) else add(dow)
                            }
                            onCustomDaysChange(next)
                        },
                        label = { Text(name, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
