package com.openminis.app.automation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.automation.AutomationRuleTools
import com.openminis.app.automation.Condition
import com.openminis.app.automation.RuleAction
import com.openminis.app.automation.RuleSpec
import com.openminis.app.automation.Trigger
import com.openminis.app.ui.components.MinisCenterTopBar
import com.openminis.app.ui.settings.SettingsSwitch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * [T-android-automation-rules] Rule editor. Trigger-type selection with the
 * per-type parameters, optional conditions (all must hold), the agent prompt,
 * and the optional tool whitelist. The existing cron edit flow lives in
 * ScheduledTaskEditScreen and is untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationRuleEditScreen(
    ruleId: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: AutomationRulesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AutomationRulesViewModel.factory(context),
    )
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(ruleId == null) }
    var problems by remember { mutableStateOf<List<String>?>(null) }

    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(TriggerKind.DAILY) }
    var at by remember { mutableStateOf("09:00") }
    var everyMinutes by remember { mutableStateOf("30") }
    var onceAt by remember { mutableStateOf("") }
    var ssid by remember { mutableStateOf("") }
    var btName by remember { mutableStateOf("") }
    var batteryPercent by remember { mutableStateOf("20") }
    var packageName by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    var days by remember { mutableStateOf<List<Int>>(emptyList()) }

    var useTimeRange by remember { mutableStateOf(false) }
    var rangeFrom by remember { mutableStateOf("08:00") }
    var rangeTo by remember { mutableStateOf("22:00") }
    var useDays by remember { mutableStateOf(false) }
    var condDays by remember { mutableStateOf<List<Int>>(emptyList()) }
    var useBattery by remember { mutableStateOf(false) }
    var batteryAtLeast by remember { mutableStateOf("50") }
    var useCharging by remember { mutableStateOf(false) }
    var charging by remember { mutableStateOf(true) }

    var prompt by remember { mutableStateOf("") }
    var allowedTools by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }

    LaunchedEffect(ruleId) {
        val id = ruleId ?: return@LaunchedEffect
        val entity = vm.get(id) ?: run { loaded = true; return@LaunchedEffect }
        val spec = entity.spec()
        name = entity.name
        enabled = entity.enabled
        spec?.let { s ->
            prompt = (s.action as? RuleAction.Agent)?.prompt ?: ""
            allowedTools = s.allowedTools.joinToString(", ")
            when (val t = s.trigger) {
                is Trigger.Time -> when {
                    t.everyMinutes > 0 -> {
                        kind = TriggerKind.INTERVAL
                        everyMinutes = t.everyMinutes.toString()
                    }
                    t.onceAtEpochMs != null -> {
                        kind = TriggerKind.ONCE
                        onceAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                            .format(java.util.Date(t.onceAtEpochMs))
                    }
                    else -> {
                        kind = TriggerKind.DAILY
                        t.at?.let { at = it }
                        days = t.days
                    }
                }
                is Trigger.WifiConnect -> { kind = TriggerKind.WIFI; ssid = t.ssid ?: "" }
                is Trigger.BluetoothConnect -> { kind = TriggerKind.BLUETOOTH; btName = t.deviceName ?: "" }
                is Trigger.PowerConnected -> kind = TriggerKind.POWER
                is Trigger.BatteryLow -> { kind = TriggerKind.BATTERY_LOW; batteryPercent = t.percent.toString() }
                is Trigger.ScreenOn -> kind = TriggerKind.SCREEN_ON
                is Trigger.ScreenOff -> kind = TriggerKind.SCREEN_OFF
                is Trigger.ScreenUnlock -> kind = TriggerKind.UNLOCK
                is Trigger.NotificationReceived -> {
                    kind = TriggerKind.NOTIFICATION
                    packageName = t.packageName ?: ""
                    keyword = t.keyword ?: ""
                }
                is Trigger.AppOpened -> { kind = TriggerKind.APP_OPENED; packageName = t.packageName }
                else -> {}
            }
            s.conditions.forEach { c ->
                when (c) {
                    is Condition.TimeRange -> {
                        useTimeRange = true
                        rangeFrom = c.from
                        rangeTo = c.to
                    }
                    is Condition.DaysOfWeek -> {
                        useDays = true
                        condDays = c.days
                    }
                    is Condition.BatteryAtLeast -> {
                        useBattery = true
                        batteryAtLeast = c.percent.toString()
                    }
                    is Condition.Charging -> {
                        useCharging = true
                        charging = c.charging
                    }
                }
            }
        }
        loaded = true
    }

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = {
                    Text(
                        stringResource(R.string.automation_rule_edit_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.automation_rule_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.automation_rule_field_trigger),
                fontWeight = FontWeight.Medium,
            )
            TriggerKindPicker(kind) { kind = it }

            when (kind) {
                TriggerKind.DAILY -> {
                    OutlinedTextField(
                        value = at,
                        onValueChange = { at = it },
                        label = { Text(stringResource(R.string.automation_rule_field_time)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DayChips(days) { days = it }
                }
                TriggerKind.INTERVAL -> OutlinedTextField(
                    value = everyMinutes,
                    onValueChange = { everyMinutes = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.automation_rule_field_every_minutes)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TriggerKind.ONCE -> OutlinedTextField(
                    value = onceAt,
                    onValueChange = { onceAt = it },
                    label = { Text(stringResource(R.string.automation_rule_field_once_at)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TriggerKind.WIFI -> OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text(stringResource(R.string.automation_rule_field_ssid)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TriggerKind.BLUETOOTH -> OutlinedTextField(
                    value = btName,
                    onValueChange = { btName = it },
                    label = { Text(stringResource(R.string.automation_rule_field_bt_device)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TriggerKind.BATTERY_LOW -> OutlinedTextField(
                    value = batteryPercent,
                    onValueChange = { batteryPercent = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.automation_rule_field_battery_percent)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TriggerKind.NOTIFICATION -> {
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text(stringResource(R.string.automation_rule_field_package)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text(stringResource(R.string.automation_rule_field_keyword)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TriggerKind.APP_OPENED -> OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text(stringResource(R.string.automation_rule_field_package)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TriggerKind.POWER, TriggerKind.SCREEN_ON, TriggerKind.SCREEN_OFF, TriggerKind.UNLOCK -> {}
            }

            Text(
                stringResource(R.string.automation_rule_field_conditions),
                fontWeight = FontWeight.Medium,
            )
            ConditionRow(
                label = stringResource(R.string.automation_rule_field_time_range),
                checked = useTimeRange,
                onChecked = { useTimeRange = it },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rangeFrom,
                        onValueChange = { rangeFrom = it },
                        label = { Text("from") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = rangeTo,
                        onValueChange = { rangeTo = it },
                        label = { Text("to") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            ConditionRow(
                label = stringResource(R.string.automation_rule_field_days),
                checked = useDays,
                onChecked = { useDays = it },
            ) {
                DayChips(condDays) { condDays = it }
            }
            ConditionRow(
                label = stringResource(R.string.automation_rule_field_battery_at_least),
                checked = useBattery,
                onChecked = { useBattery = it },
            ) {
                OutlinedTextField(
                    value = batteryAtLeast,
                    onValueChange = { batteryAtLeast = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.automation_rule_field_charging), modifier = Modifier.weight(1f))
                Switch(checked = useCharging, onCheckedChange = { useCharging = it })
            }
            if (useCharging) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (charging) "charging" else "not charging", modifier = Modifier.weight(1f))
                    Switch(checked = charging, onCheckedChange = { charging = it })
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.automation_rule_field_prompt)) },
                placeholder = { Text(stringResource(R.string.automation_rule_field_prompt_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.automation_rule_action_agent_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )

            OutlinedTextField(
                value = allowedTools,
                onValueChange = { allowedTools = it },
                label = { Text(stringResource(R.string.automation_rule_field_allowed_tools)) },
                supportingText = { Text(stringResource(R.string.automation_rule_field_allowed_tools_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.automation_rule_enabled), modifier = Modifier.weight(1f))
                SettingsSwitch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Button(
                onClick = {
                    scope.launch {
                        val spec = buildSpec(
                            name = name, kind = kind, at = at, everyMinutes = everyMinutes,
                            onceAt = onceAt, ssid = ssid, btName = btName,
                            batteryPercent = batteryPercent, packageName = packageName,
                            keyword = keyword, days = days, useTimeRange = useTimeRange,
                            rangeFrom = rangeFrom, rangeTo = rangeTo, useDays = useDays,
                            condDays = condDays, useBattery = useBattery,
                            batteryAtLeast = batteryAtLeast, useCharging = useCharging,
                            charging = charging, prompt = prompt, allowedTools = allowedTools,
                        )
                        if (spec == null) {
                            problems = listOf(
                                context.getString(R.string.automation_rule_missing_fields),
                            )
                            return@launch
                        }
                        val result = vm.save(ruleId, spec, AutomationRuleTools.knownToolNames())
                        if (result.isEmpty()) onBack() else problems = result
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.automation_rule_save))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    problems?.let { list ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { problems = null },
            title = { Text(stringResource(R.string.automation_rule_invalid_title)) },
            text = { Text(list.joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = { problems = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private enum class TriggerKind {
    DAILY, INTERVAL, ONCE, WIFI, BLUETOOTH, POWER, BATTERY_LOW,
    SCREEN_ON, SCREEN_OFF, UNLOCK, NOTIFICATION, APP_OPENED,
}

@Composable
private fun TriggerKindPicker(current: TriggerKind, onSelect: (TriggerKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Text(triggerKindLabel(current))
    }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.automation_rule_field_trigger)) },
            text = {
                Column {
                    TriggerKind.entries.forEach { k ->
                        TextButton(
                            onClick = { onSelect(k); open = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                triggerKindLabel(k),
                                color = if (k == current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun triggerKindLabel(kind: TriggerKind): String = stringResource(
    when (kind) {
        TriggerKind.DAILY -> R.string.automation_rule_trigger_daily
        TriggerKind.INTERVAL -> R.string.automation_rule_trigger_interval
        TriggerKind.ONCE -> R.string.automation_rule_trigger_once
        TriggerKind.WIFI -> R.string.automation_rule_trigger_wifi
        TriggerKind.BLUETOOTH -> R.string.automation_rule_trigger_bluetooth
        TriggerKind.POWER -> R.string.automation_rule_trigger_power
        TriggerKind.BATTERY_LOW -> R.string.automation_rule_trigger_battery_low
        TriggerKind.SCREEN_ON -> R.string.automation_rule_trigger_screen_on
        TriggerKind.SCREEN_OFF -> R.string.automation_rule_trigger_screen_off
        TriggerKind.UNLOCK -> R.string.automation_rule_trigger_unlock
        TriggerKind.NOTIFICATION -> R.string.automation_rule_trigger_notification
        TriggerKind.APP_OPENED -> R.string.automation_rule_trigger_app_opened
    },
)

private val DAY_LABEL_KEYS = listOf(
    R.string.automation_day_mon,
    R.string.automation_day_tue,
    R.string.automation_day_wed,
    R.string.automation_day_thu,
    R.string.automation_day_fri,
    R.string.automation_day_sat,
    R.string.automation_day_sun,
)

/** ISO 1..7 (Mon..Sun) chips. */
@Composable
private fun DayChips(selected: List<Int>, onChange: (List<Int>) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DAY_LABEL_KEYS.forEachIndexed { idx, key ->
            val isoDay = idx + 1
            FilterChip(
                selected = isoDay in selected,
                onClick = {
                    onChange(
                        if (isoDay in selected) selected - isoDay else selected + isoDay,
                    )
                },
                label = { Text(stringResource(key)) },
            )
        }
    }
}

@Composable
private fun ConditionRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChecked)
        }
        if (checked) content()
    }
}

/** Build the RuleSpec from the editor state; null = required fields missing. */
private fun buildSpec(
    name: String,
    kind: TriggerKind,
    at: String,
    everyMinutes: String,
    onceAt: String,
    ssid: String,
    btName: String,
    batteryPercent: String,
    packageName: String,
    keyword: String,
    days: List<Int>,
    useTimeRange: Boolean,
    rangeFrom: String,
    rangeTo: String,
    useDays: Boolean,
    condDays: List<Int>,
    useBattery: Boolean,
    batteryAtLeast: String,
    useCharging: Boolean,
    charging: Boolean,
    prompt: String,
    allowedTools: String,
): RuleSpec? {
    if (name.isBlank() || prompt.isBlank()) return null
    val trigger: Trigger = when (kind) {
        TriggerKind.DAILY -> Trigger.Time(at = at.trim(), days = days)
        TriggerKind.INTERVAL -> Trigger.Time(everyMinutes = everyMinutes.toLongOrNull() ?: 0)
        TriggerKind.ONCE -> {
            val parsed = runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(onceAt.trim())
            }.getOrNull() ?: return null
            Trigger.Time(onceAtEpochMs = parsed.time)
        }
        TriggerKind.WIFI -> Trigger.WifiConnect(ssid = ssid.trim().takeIf { it.isNotEmpty() })
        TriggerKind.BLUETOOTH -> Trigger.BluetoothConnect(deviceName = btName.trim().takeIf { it.isNotEmpty() })
        TriggerKind.POWER -> Trigger.PowerConnected
        TriggerKind.BATTERY_LOW -> Trigger.BatteryLow(percent = batteryPercent.toIntOrNull() ?: 20)
        TriggerKind.SCREEN_ON -> Trigger.ScreenOn
        TriggerKind.SCREEN_OFF -> Trigger.ScreenOff
        TriggerKind.UNLOCK -> Trigger.ScreenUnlock
        TriggerKind.NOTIFICATION -> Trigger.NotificationReceived(
            packageName = packageName.trim().takeIf { it.isNotEmpty() },
            keyword = keyword.trim().takeIf { it.isNotEmpty() },
        )
        TriggerKind.APP_OPENED -> Trigger.AppOpened(packageName.trim())
    }
    val conditions = buildList {
        if (useTimeRange) add(Condition.TimeRange(rangeFrom.trim(), rangeTo.trim()))
        if (useDays) add(Condition.DaysOfWeek(condDays))
        if (useBattery) add(Condition.BatteryAtLeast(batteryAtLeast.toIntOrNull() ?: 0))
        if (useCharging) add(Condition.Charging(charging))
    }
    val tools = allowedTools.split(',', ';', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    return RuleSpec(
        name = name.trim(),
        trigger = trigger,
        conditions = conditions,
        action = RuleAction.Agent(prompt.trim()),
        allowedTools = tools,
        permissionMode = null,
    )
}
