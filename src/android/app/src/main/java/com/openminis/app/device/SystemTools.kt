package com.openminis.app.device

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * System group of the device tool set: device status, settings read, sound /
 * volume / DND / brightness, flashlight, alarms, and the connectivity toggles
 * (Wi-Fi / Bluetooth / mobile data — privileged via Shizuku, system-panel
 * fallback otherwise). All descriptions are static English; live state goes
 * into the execution result text only.
 */
internal object SystemTools {

    private const val TOOL_TITLE_PARAM = "tool_title"
    private const val TOOL_TITLE_DESC = "A concise 5-10 word summary shown to the user."

    private fun titleParam() = AgentToolParam("string", TOOL_TITLE_DESC)

    fun definitions(): List<AgentToolDefinition> = listOf(
        DeviceStatusTool.definition(),
        SettingGetTool.definition(),
        SoundModeTool.definition(),
        VolumeSetTool.definition(),
        DndSetTool.definition(),
        BrightnessSetTool.definition(),
        FlashlightTool.definition(),
        AlarmSetTool.definition(),
        WifiToggleTool.definition(),
        BluetoothToggleTool.definition(),
        MobileDataToggleTool.definition(),
    )

    fun execute(name: String, argsJson: String, context: Context): String = when (name) {
        DeviceStatusTool.NAME -> DeviceStatusTool.execute(argsJson, context)
        SettingGetTool.NAME -> SettingGetTool.execute(argsJson, context)
        SoundModeTool.NAME -> SoundModeTool.execute(argsJson, context)
        VolumeSetTool.NAME -> VolumeSetTool.execute(argsJson, context)
        DndSetTool.NAME -> DndSetTool.execute(argsJson, context)
        BrightnessSetTool.NAME -> BrightnessSetTool.execute(argsJson, context)
        FlashlightTool.NAME -> FlashlightTool.execute(argsJson, context)
        AlarmSetTool.NAME -> AlarmSetTool.execute(argsJson, context)
        WifiToggleTool.NAME -> WifiToggleTool.execute(argsJson, context)
        BluetoothToggleTool.NAME -> BluetoothToggleTool.execute(argsJson, context)
        MobileDataToggleTool.NAME -> MobileDataToggleTool.execute(argsJson, context)
        else -> DeviceArgs.fail("unknown_tool", "system group does not handle '$name'")
    }

    // ── shared plumbing ──

    private fun audio(ctx: Context): AudioManager? =
        ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    internal fun ringerName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        else -> "unknown($mode)"
    }

    internal fun networkName(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "other"
            else -> "offline"
        }
    }

    /** Common key names surfaced to the model; also covered by unit tests. */
    internal val COMMON_KEYS: Map<String, List<String>> = mapOf(
        "global" to listOf(
            "airplane_mode_on", "bluetooth_on", "wifi_on", "mobile_data", "auto_time", "stay_on_while_plugged_in",
        ),
        "system" to listOf(
            "screen_off_timeout", "screen_brightness", "screen_brightness_mode", "accelerometer_rotation", "font_scale",
        ),
        "secure" to listOf("location_mode", "default_input_method", "ui_night_mode", "sleep_timeout"),
    )

    internal fun sanitizeKey(raw: String): String = raw.filter { it.isLetterOrDigit() || it == '_' || it == '.' }

    private fun panelIntent(action: String): Intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openPanel(context: Context, action: String, what: String, verify: String): String {
        return runCatching { context.startActivity(panelIntent(action)) }.fold(
            {
                "no Shizuku: opened the system $what; ask the user to toggle it there " +
                    "(verify with $verify before relying on it)."
            },
            { DeviceArgs.fail("failed", "no Shizuku and the system $what could not be opened: ${it.message}") },
        )
    }

    // ── device_status ──

    internal object DeviceStatusTool {
        const val NAME = "device_status"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Snapshot of this device's live state: battery level and charging, screen on/off and " +
                "lock state, brightness mode, network (wifi/cellular/offline), storage free/total, ringer mode " +
                "and volumes, Do-Not-Disturb, location enabled, current time. Call it before anything that " +
                "depends on connectivity, battery, or sound state, and after toggles to verify. Read-only.",
            parameters = mapOf(TOOL_TITLE_PARAM to titleParam()),
            required = listOf(TOOL_TITLE_PARAM),
            propertyOrdering = listOf(TOOL_TITLE_PARAM),
        )

        fun execute(argsJson: String, context: Context): String {
            val battery = runCatching {
                context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            val level: Int? = battery?.let {
                val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (l >= 0 && s > 0) (l * 100 / s) else null
            }
            val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

            val audio = audio(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val dndOn = runCatching {
                nm?.currentInterruptionFilter != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            }.getOrDefault(false)
            val screenOn = pm?.isInteractive
            val locked = km?.isKeyguardLocked

            val brightnessMode = runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
            }.getOrDefault(-1)
            val brightnessValue = runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            }.getOrDefault(-1)

            val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
            val storage = if (stat != null) {
                "${gb(stat.availableBytes)} GB free of ${gb(stat.totalBytes)} GB"
            } else "unknown"

            val locationEnabled = runCatching {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                lm?.let { if (Build.VERSION.SDK_INT >= 28) it.isLocationEnabled
                    else it.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                        it.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) }
            }.getOrNull()

            val musicVol = audio?.let { "${it.getStreamVolume(AudioManager.STREAM_MUSIC)}/${it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" }
            val ringVol = audio?.let { "${it.getStreamVolume(AudioManager.STREAM_RING)}/${it.getStreamMaxVolume(AudioManager.STREAM_RING)}" }

            return buildString {
                append("battery: ").append(level?.let { "$it%" } ?: "unknown").append(if (charging) " (charging)" else "").append('\n')
                append("screen: ").append(screenOn?.let { if (it) "on" else "off" } ?: "unknown")
                if (locked != null) append(when { locked && km!!.isDeviceSecure -> ", locked"; locked -> ", locked (no secure lock set)"; else -> "" })
                append('\n')
                append("brightness: ").append(
                    when (brightnessMode) {
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "adaptive"
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> "manual"
                        else -> "unknown"
                    } + if (brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL && brightnessValue >= 0) " ($brightnessValue/255)" else "",
                ).append('\n')
                append("network: ").append(networkName(context)).append('\n')
                append("storage: ").append(storage).append('\n')
                append("ringer: ").append(audio?.let { ringerName(it.ringerMode) } ?: "unknown")
                if (musicVol != null) append(" (volume music $musicVol, ring $ringVol)")
                append('\n')
                append("do_not_disturb: ").append(if (dndOn) "on" else "off").append('\n')
                append("location_enabled: ").append(locationEnabled?.toString() ?: "unknown").append('\n')
                append("time: ").append(java.time.ZonedDateTime.now())
            }
        }
    }

    // ── setting_get ──

    internal object SettingGetTool {
        const val NAME = "setting_get"

        private val COMMON_KEYS_TEXT = COMMON_KEYS.entries.joinToString("; ") { (ns, keys) -> "$ns ${keys.joinToString(", ")}" }

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Read one Android setting value. namespace is system, secure or global; common keys: " +
                "$COMMON_KEYS_TEXT. Omit key to LIST every key in the namespace (needs Shizuku; narrow with " +
                "filter). Values are returned raw (strings stay strings, integers as integers). Read-only.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "namespace" to AgentToolParam("string", "Settings table to read.", enumValues = listOf("system", "secure", "global")),
                "key" to AgentToolParam("string", "Setting key, e.g. screen_off_timeout. Omit to list all keys (needs Shizuku)."),
                "filter" to AgentToolParam("string", "When listing: only keys containing this substring."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "namespace"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "namespace", "key", "filter"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val ns = obj.strArg("namespace") ?: return DeviceArgs.fail("invalid_args", "missing 'namespace' (system/secure/global)")
            if (ns !in listOf("system", "secure", "global")) {
                return DeviceArgs.fail("invalid_args", "namespace must be system, secure or global (got '$ns')")
            }
            val key = obj.strArg("key")?.let(::sanitizeKey)
            val cr = context.contentResolver

            if (key == null) {
                if (!DeviceArgs.shizukuReady()) {
                    return DeviceArgs.fail(
                        "shizuku_unavailable",
                        "listing all settings needs Shizuku; pass a specific key instead, or set up Shizuku.",
                    )
                }
                val out = DeviceArgs.shizukuShell(arrayOf("settings", "list", ns))
                    ?: return DeviceArgs.fail("failed", "settings list $ns failed via Shizuku shell")
                val filter = obj.strArg("filter")?.lowercase()
                val lines = out.lines().map { it.trim() }
                    .filter { it.isNotEmpty() && (filter == null || it.lowercase().contains(filter)) }
                return if (lines.isEmpty()) "No matching keys."
                else lines.take(300).joinToString("\n") + if (lines.size > 300) "\n…(${lines.size - 300} more; use filter)" else ""
            }

            val direct = runCatching {
                when (ns) {
                    "system" -> Settings.System.getString(cr, key)
                    "secure" -> Settings.Secure.getString(cr, key)
                    else -> Settings.Global.getString(cr, key)
                }
            }
            if (direct.isSuccess && direct.getOrNull() != null) return "$ns.$key = ${direct.getOrNull()}"
            if (DeviceArgs.shizukuReady()) {
                val out = DeviceArgs.shizukuShell(arrayOf("settings", "get", ns, key))
                if (out != null && out.isNotEmpty()) return "$ns.$key = $out (via Shizuku shell)"
                if (out != null && out.isEmpty() && direct.getOrNull() == null) return "$ns.$key is not set (null)."
            }
            return if (direct.getOrNull() == null) "$ns.$key is not set (null)."
            else "$ns.$key = ${direct.getOrNull()}"
        }
    }

    // ── sound_mode ──

    internal object SoundModeTool {
        const val NAME = "sound_mode"
        private val MODES = listOf("normal", "vibrate", "silent")

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Set the ringer mode to normal (sound), vibrate or silent. Switching to or away from " +
                "silent also touches Do Not Disturb, which needs the user's Do Not Disturb access grant. " +
                "Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "mode" to AgentToolParam("string", "Target ringer mode.", enumValues = MODES),
            ),
            required = listOf(TOOL_TITLE_PARAM, "mode"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "mode"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val mode = obj.strArg("mode") ?: return DeviceArgs.fail("invalid_args", "missing 'mode' (normal/vibrate/silent)")
            if (mode !in MODES) return DeviceArgs.fail("invalid_args", "mode must be one of normal, vibrate, silent (got '$mode')")
            val audio = audio(context) ?: return DeviceArgs.fail("unsupported", "no audio service on this device")
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val target = when (mode) {
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            // Entering or leaving silent toggles DND on Android 7+; that needs the policy-access grant.
            val touchesSilent = mode == "silent" || audio.ringerMode == AudioManager.RINGER_MODE_SILENT
            if (touchesSilent && nm?.isNotificationPolicyAccessGranted != true) {
                return DevicePermission.missingError(DevicePermission.SPECIAL_NOTIFICATION_POLICY)
            }
            try {
                audio.ringerMode = target
            } catch (e: SecurityException) {
                return DevicePermission.missingError(DevicePermission.SPECIAL_NOTIFICATION_POLICY)
            }
            return "Ringer mode set to $mode."
        }
    }

    // ── volume_set ──

    internal object VolumeSetTool {
        const val NAME = "volume_set"
        private val STREAMS = listOf("music", "ring", "alarm", "notification", "call")

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Set a volume stream (music, ring, alarm, notification or call) to a percentage 0-100. " +
                "Setting ring or notification to 0 while Do Not Disturb is on can be refused by the system. " +
                "Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "stream" to AgentToolParam("string", "Which volume stream.", enumValues = STREAMS),
                "percent" to AgentToolParam("integer", "Target volume as 0-100."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "stream", "percent"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "stream", "percent"),
        )

        private fun streamOf(name: String): Int = when (name) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val stream = obj.strArg("stream") ?: return DeviceArgs.fail("invalid_args", "missing 'stream'")
            if (stream !in STREAMS) return DeviceArgs.fail("invalid_args", "stream must be one of ${STREAMS.joinToString("/")}")
            val percent = obj.intArg("percent") ?: return DeviceArgs.fail("invalid_args", "missing 'percent' (0-100)")
            if (percent !in 0..100) return DeviceArgs.fail("invalid_args", "percent must be 0-100 (got $percent)")
            val audio = audio(context) ?: return DeviceArgs.fail("unsupported", "no audio service on this device")
            val s = streamOf(stream)
            val max = audio.getStreamMaxVolume(s)
            try {
                audio.setStreamVolume(s, (max * percent + 50) / 100, 0)
            } catch (e: SecurityException) {
                return DevicePermission.missingError(DevicePermission.SPECIAL_NOTIFICATION_POLICY)
            }
            return "Volume $stream set to $percent% (${audio.getStreamVolume(s)}/$max)."
        }
    }

    // ── dnd_set ──

    internal object DndSetTool {
        const val NAME = "dnd_set"
        private val MODES = listOf("off", "priority", "alarms", "all")

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Turn Do Not Disturb off, or on in a filter mode: priority (only priority " +
                "interrupts), alarms (alarms and media only), or all (mutes everything). Requires the " +
                "user's Do Not Disturb access grant. Sensitive: changes how the device alerts.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "mode" to AgentToolParam("string", "DND filter to apply; off disables DND.", enumValues = MODES),
            ),
            required = listOf(TOOL_TITLE_PARAM, "mode"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "mode"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val mode = obj.strArg("mode")
                ?: when (obj.boolArg("on")) {
                    true -> "priority"
                    false -> "off"
                    null -> return DeviceArgs.fail("invalid_args", "missing 'mode' (off/priority/alarms/all)")
                }
            if (mode !in MODES) return DeviceArgs.fail("invalid_args", "mode must be one of ${MODES.joinToString("/")}")
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return DeviceArgs.fail("unsupported", "no notification service on this device")
            if (!nm.isNotificationPolicyAccessGranted) {
                return DevicePermission.missingError(DevicePermission.SPECIAL_NOTIFICATION_POLICY)
            }
            val filter = when (mode) {
                "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
                else -> NotificationManager.INTERRUPTION_FILTER_NONE
            }
            return runCatching { nm.setInterruptionFilter(filter) }.fold(
                {
                    when (mode) {
                        "off" -> "Do Not Disturb is off."
                        "all" -> "Do Not Disturb set to 'all' (total silence)."
                        else -> "Do Not Disturb set to $mode."
                    }
                },
                { DeviceArgs.fail("failed", "could not set interruption filter: ${it.message}") },
            )
        }
    }

    // ── brightness_set ──

    internal object BrightnessSetTool {
        const val NAME = "brightness_set"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Set screen brightness to a raw level 0-255 (0 is usually not reachable; the system " +
                "clamps to its minimum), or enable/disable adaptive (auto) brightness. Requires the user's " +
                "Modify system settings grant. Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "level" to AgentToolParam("integer", "Raw brightness 0-255 (pass this or adaptive)."),
                "adaptive" to AgentToolParam("boolean", "true = adaptive/auto brightness, false = manual mode."),
            ),
            required = listOf(TOOL_TITLE_PARAM),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "level", "adaptive"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val adaptive = obj.boolArg("adaptive")
            val level = obj.intArg("level")
            if (adaptive == null && level == null) {
                return DeviceArgs.fail("invalid_args", "missing 'level' (0-255) or 'adaptive' (true/false)")
            }
            if (!Settings.System.canWrite(context)) {
                return DevicePermission.missingError(DevicePermission.SPECIAL_WRITE_SETTINGS)
            }
            val cr = context.contentResolver
            if (adaptive == true) {
                runCatching {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                }.getOrElse { return DeviceArgs.fail("failed", "could not enable adaptive brightness: ${it.message}") }
                return "Adaptive brightness enabled."
            }
            if (level != null && level !in 0..255) {
                return DeviceArgs.fail("invalid_args", "level must be 0-255 (got $level)")
            }
            val target = level ?: return DeviceArgs.fail("invalid_args", "missing 'level' (0-255)")
            val applied = runCatching {
                if (adaptive == false) {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                }
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
            }.getOrElse { return DeviceArgs.fail("failed", "could not set brightness: ${it.message}") }
            return if (applied) "Brightness set to $target/255." else "Brightness write was ignored by the system."
        }
    }

    // ── flashlight ──

    internal object FlashlightTool {
        const val NAME = "flashlight"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Turn the camera flash / flashlight torch on or off. Fails clearly on devices " +
                "without a flash or when the camera is in use by another app. Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "on" to AgentToolParam("boolean", "true = turn on, false = turn off."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "on"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "on"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val on = obj.boolArg("on") ?: return DeviceArgs.fail("invalid_args", "missing 'on' (true/false)")
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return DeviceArgs.fail("unsupported", "no camera service on this device")
            val id = runCatching {
                cm.cameraIdList.firstOrNull {
                    runCatching { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) }.getOrNull() == true
                }
            }.getOrNull() ?: return DeviceArgs.fail("unsupported", "this device has no camera flash")
            return try {
                cm.setTorchMode(id, on)
                if (on) "Flashlight on." else "Flashlight off."
            } catch (e: CameraAccessException) {
                DeviceArgs.fail("failed", "the flash is unavailable right now (the camera may be in use by another app); close it and retry.")
            } catch (e: Exception) {
                DeviceArgs.fail("failed", "could not set torch: ${e.message}")
            }
        }
    }

    // ── alarm_set ──

    internal object AlarmSetTool {
        const val NAME = "alarm_set"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Create an alarm in the system clock app (hour 0-23, minute 0-59, optional label and " +
                "repeat weekdays 1=Monday…7=Sunday), or start a countdown timer with timer_seconds (1-86400) " +
                "instead. Handled by the clock app; some clocks show a confirmation screen. Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "hour" to AgentToolParam("integer", "Alarm hour 0-23 (omit when timer_seconds is used)."),
                "minute" to AgentToolParam("integer", "Alarm minute 0-59 (default 0)."),
                "label" to AgentToolParam("string", "Optional alarm label."),
                "days" to AgentToolParam(
                    "array",
                    "Optional repeat weekdays, 1=Monday … 7=Sunday; omit for a one-time alarm.",
                    items = AgentToolParam("integer", "Weekday number 1-7."),
                ),
                "timer_seconds" to AgentToolParam("integer", "Start a countdown timer of this many seconds (1-86400) instead of an alarm."),
            ),
            required = listOf(TOOL_TITLE_PARAM),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "hour", "minute", "label", "days", "timer_seconds"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val label = obj.strArg("label")
            val timer = obj.intArg("timer_seconds")
            val intent: Intent
            var what: String
            if (timer != null) {
                if (timer !in 1..86_400) return DeviceArgs.fail("invalid_args", "timer_seconds must be 1-86400 (got $timer)")
                intent = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, timer)
                what = "a ${timer}s timer"
            } else {
                val hour = obj.intArg("hour") ?: return DeviceArgs.fail("invalid_args", "missing 'hour' (0-23), or pass timer_seconds for a timer")
                val minute = obj.intArg("minute") ?: 0
                if (hour !in 0..23 || minute !in 0..59) {
                    return DeviceArgs.fail("invalid_args", "hour must be 0-23 and minute 0-59 (got $hour:$minute)")
                }
                intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                what = "an alarm at %02d:%02d".format(hour, minute)
                val days = obj.strListArg("days")?.mapNotNull { it.toIntOrNull() }
                if (!days.isNullOrEmpty()) {
                    if (days.any { it !in 1..7 }) return DeviceArgs.fail("invalid_args", "days must be 1-7 (1=Monday … 7=Sunday)")
                    intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                }
            }
            label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                "Sent $what to the clock app. Some clock apps show a confirmation page instead of saving " +
                    "silently; verify in the clock app if it matters."
            } catch (e: ActivityNotFoundException) {
                DeviceArgs.fail("unsupported", "no clock app on this device handles alarms/timers")
            } catch (e: SecurityException) {
                DeviceArgs.fail("failed", "the clock app refused the request: ${e.message}")
            }
        }
    }

    // ── wifi_toggle ──

    internal object WifiToggleTool {
        const val NAME = "wifi_toggle"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Turn Wi-Fi on or off. Uses the Shizuku privileged shell when Shizuku is ready; " +
                "otherwise opens the system internet panel and the user flips the switch. Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "on" to AgentToolParam("boolean", "true = enable Wi-Fi, false = disable."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "on"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "on"),
        )

        @Suppress("DEPRECATION")
        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val on = obj.boolArg("on") ?: return DeviceArgs.fail("invalid_args", "missing 'on' (true/false)")
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wm != null && runCatching { wm.isWifiEnabled }.getOrNull() == on) {
                return "Wi-Fi is already ${if (on) "on" else "off"}; nothing to do."
            }
            val via = DeviceArgs.shizukuShell(arrayOf("svc", "wifi", if (on) "enable" else "disable"))
            if (via != null) {
                return "Wi-Fi switched ${if (on) "on" else "off"} via Shizuku shell." + if (via.isEmpty()) "" else " Output: $via"
            }
            val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            else Settings.ACTION_WIFI_SETTINGS
            return openPanel(context, action, "internet panel", "device_status")
        }
    }

    // ── bluetooth_toggle ──

    internal object BluetoothToggleTool {
        const val NAME = "bluetooth_toggle"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Turn Bluetooth on or off. Uses the Shizuku privileged shell when Shizuku is ready; " +
                "otherwise shows the system Bluetooth enable prompt or settings page for the user. " +
                "Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "on" to AgentToolParam("boolean", "true = enable Bluetooth, false = disable."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "on"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "on"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val on = obj.boolArg("on") ?: return DeviceArgs.fail("invalid_args", "missing 'on' (true/false)")
            val adapter = runCatching {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            }.getOrNull()
            if (adapter == null) return DeviceArgs.fail("unsupported", "this device has no Bluetooth adapter")
            if (runCatching { adapter.isEnabled }.getOrNull() == on) {
                return "Bluetooth is already ${if (on) "on" else "off"}; nothing to do."
            }
            val via = DeviceArgs.shizukuShell(arrayOf("svc", "bluetooth", if (on) "enable" else "disable"))
            if (via != null) {
                return "Bluetooth switched ${if (on) "on" else "off"} via Shizuku shell." + if (via.isEmpty()) "" else " Output: $via"
            }
            val canAskSystem = on && (Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
            val action = if (canAskSystem) android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE
            else Settings.ACTION_BLUETOOTH_SETTINGS
            return openPanel(context, action, "Bluetooth page", "device_status")
        }
    }

    // ── mobile_data_toggle ──

    internal object MobileDataToggleTool {
        const val NAME = "mobile_data_toggle"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Turn mobile data on or off. Uses the Shizuku privileged shell when Shizuku is " +
                "ready; otherwise opens the system internet panel and the user flips the switch. " +
                "Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "on" to AgentToolParam("boolean", "true = enable mobile data, false = disable."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "on"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "on"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val on = obj.boolArg("on") ?: return DeviceArgs.fail("invalid_args", "missing 'on' (true/false)")
            val via = DeviceArgs.shizukuShell(arrayOf("svc", "data", if (on) "enable" else "disable"))
            if (via != null) {
                return "Mobile data switched ${if (on) "on" else "off"} via Shizuku shell." + if (via.isEmpty()) "" else " Output: $via"
            }
            val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            else Settings.ACTION_WIRELESS_SETTINGS
            return openPanel(context, action, "internet panel", "device_status")
        }
    }
}
