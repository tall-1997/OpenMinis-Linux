package com.openminis.app.device

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Comms + location group of the device tool set: contacts, calendar, SMS and
 * call-log reads, SMS send, and a one-shot location fix. Every tool checks its
 * runtime permission first via [DevicePermission] and returns the standard
 * `Error: permission_missing — …` line instead of throwing. Approval gating
 * (what the model may call without asking) belongs to the session permission
 * layer — this file never prompts.
 */
internal object CommsTools {

    private const val TOOL_TITLE_PARAM = "tool_title"
    private const val TOOL_TITLE_DESC = "A concise 5-10 word summary shown to the user."

    private fun titleParam() = AgentToolParam("string", TOOL_TITLE_DESC)

    fun definitions(): List<AgentToolDefinition> = listOf(
        ContactsSearchTool.definition(),
        CalendarReadTool.definition(),
        CalendarAddTool.definition(),
        SmsReadTool.definition(),
        CallLogReadTool.definition(),
        SmsSendTool.definition(),
        LocationGetTool.definition(),
    )

    fun execute(name: String, argsJson: String, context: Context): String = when (name) {
        ContactsSearchTool.NAME -> ContactsSearchTool.execute(argsJson, context)
        CalendarReadTool.NAME -> CalendarReadTool.execute(argsJson, context)
        CalendarAddTool.NAME -> CalendarAddTool.execute(argsJson, context)
        SmsReadTool.NAME -> SmsReadTool.execute(argsJson, context)
        CallLogReadTool.NAME -> CallLogReadTool.execute(argsJson, context)
        SmsSendTool.NAME -> SmsSendTool.execute(argsJson, context)
        LocationGetTool.NAME -> LocationGetTool.execute(argsJson, context)
        else -> DeviceArgs.fail("unknown_tool", "comms group does not handle '$name'")
    }

    // ── shared plumbing ──

    private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /**
     * ISO-8601 (with or without offset; a bare date = local midnight; a space
     * may replace the T) or epoch ms.
     */
    internal fun parseTime(args: JSONObject, isoKey: String, msKey: String): Long? {
        args.strArg(isoKey)?.let { v ->
            val zone = java.time.ZoneId.systemDefault()
            runCatching { return java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() }
            runCatching { return java.time.ZonedDateTime.parse(v).toInstant().toEpochMilli() }
            runCatching { return java.time.LocalDateTime.parse(v.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }
            runCatching { return java.time.LocalDateTime.parse(v.replace(' ', 'T') + ":00").atZone(zone).toInstant().toEpochMilli() }
            runCatching { return java.time.LocalDate.parse(v).atStartOfDay(zone).toInstant().toEpochMilli() }
            return null
        }
        return args.longArg(msKey)
    }

    /** All-day events (bare date) are stored at UTC midnight per CalendarContract rules. */
    private fun utcDay(iso: String): Long? = runCatching {
        java.time.LocalDate.parse(iso).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrNull()

    private fun smsErrorText(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off / airplane mode"
        SmsManager.RESULT_ERROR_NULL_PDU -> "invalid message"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure (no SIM, or the carrier refused)"
        else -> "error $code"
    }

    // ── contacts_search ──

    internal object ContactsSearchTool {
        const val NAME = "contacts_search"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Search the device contacts by name or number fragment; returns up to 'limit' " +
                "'Name: number' lines. Needs the Contacts permission. Sensitive: reads personal data.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "query" to AgentToolParam("string", "Name or number fragment to match."),
                "limit" to AgentToolParam("integer", "Max results (default 20, max 50)."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "query"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "query", "limit"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val query = obj.strArg("query") ?: return DeviceArgs.fail("invalid_args", "missing 'query'")
            if (!DevicePermission.runtimeGranted(context, Manifest.permission.READ_CONTACTS)) {
                return DevicePermission.missingError("READ_CONTACTS")
            }
            val limit = (obj.intArg("limit") ?: 20).coerceIn(1, 50)
            val rows = ArrayList<String>()
            runCatching {
                // The filter URI matches names and numbers regardless of formatting.
                val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI
                val cr = context.contentResolver
                cr.query(
                    uri.buildUpon().appendPath(java.net.URLEncoder.encode(query, "UTF-8")).build(),
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext() && rows.size < limit) {
                        val name = c.getString(0) ?: continue
                        rows.add("$name: ${c.getString(1) ?: "?"}")
                    }
                }
            }.getOrElse { return DeviceArgs.fail("failed", "contacts query failed: ${it.message}") }
            return rows.distinct().joinToString("\n").ifEmpty { "No contacts found." }
        }
    }

    // ── calendar_read ──

    internal object CalendarReadTool {
        const val NAME = "calendar_read"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Read calendar events in a time range (default: now through the next 7 days). " +
                "from/to accept ISO-8601 local times like 2026-10-30T15:00 (or bare dates), or epoch " +
                "milliseconds via from_ms/to_ms. Needs the Calendar permission. Sensitive: reads personal data.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "from" to AgentToolParam("string", "Range start, ISO-8601 local time or date."),
                "to" to AgentToolParam("string", "Range end, ISO-8601 local time or date."),
                "from_ms" to AgentToolParam("integer", "Range start (epoch ms), alternative to 'from'."),
                "to_ms" to AgentToolParam("integer", "Range end (epoch ms), alternative to 'to'."),
                "limit" to AgentToolParam("integer", "Max events (default 50, max 100)."),
            ),
            required = listOf(TOOL_TITLE_PARAM),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "from", "to", "from_ms", "to_ms", "limit"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            if (!DevicePermission.runtimeGranted(context, Manifest.permission.READ_CALENDAR)) {
                return DevicePermission.missingError("READ_CALENDAR")
            }
            val from = parseTime(obj, "from", "from_ms")
                ?: return DeviceArgs.fail("invalid_args", "'from' is not ISO-8601 (e.g. 2026-10-30T15:00) and from_ms is missing/invalid")
            val to = parseTime(obj, "to", "to_ms") ?: (from + 7 * 86_400_000L)
            if (to < from) return DeviceArgs.fail("invalid_args", "'to' is before 'from'")
            val limit = (obj.intArg("limit") ?: 50).coerceIn(1, 100)
            val rows = ArrayList<String>()
            runCatching {
                val uri = CalendarContract.Instances.CONTENT_URI
                    .buildUpon().appendPath(from.toString()).appendPath(to.toString()).build()
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END,
                        CalendarContract.Instances.EVENT_LOCATION,
                    ),
                    null, null, CalendarContract.Instances.BEGIN,
                )?.use { c ->
                    while (c.moveToNext() && rows.size < limit) {
                        val title = c.getString(0) ?: "(untitled)"
                        val loc = c.getString(3)
                        rows.add(
                            "${FMT.format(Date(c.getLong(1)))}–${FMT.format(Date(c.getLong(2)))} $title" +
                                (loc?.takeIf { it.isNotBlank() }?.let { " @$it" } ?: ""),
                        )
                    }
                }
            }.getOrElse { return DeviceArgs.fail("failed", "calendar query failed: ${it.message}") }
            return rows.joinToString("\n").ifEmpty { "No events in this range." }
        }
    }

    // ── calendar_add ──

    internal object CalendarAddTool {
        const val NAME = "calendar_add"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Add an event to the user's primary writable calendar. start/end accept ISO-8601 " +
                "local times like 2026-10-30T15:00 (a bare date creates an all-day event), or epoch " +
                "milliseconds via start_ms/end_ms; end defaults to start + 1h. Needs the write-Calendar " +
                "permission. Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "title" to AgentToolParam("string", "Event title."),
                "start" to AgentToolParam("string", "Start, ISO-8601 local time (or a bare date for an all-day event)."),
                "end" to AgentToolParam("string", "End, ISO-8601 local time (default start + 1 hour)."),
                "start_ms" to AgentToolParam("integer", "Start (epoch ms), alternative to 'start'."),
                "end_ms" to AgentToolParam("integer", "End (epoch ms), alternative to 'end'."),
                "location" to AgentToolParam("string", "Optional event location."),
                "description" to AgentToolParam("string", "Optional event description."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "title", "start"),
            propertyOrdering = listOf(
                TOOL_TITLE_PARAM, "title", "start", "end", "start_ms", "end_ms", "location", "description",
            ),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val title = obj.strArg("title") ?: return DeviceArgs.fail("invalid_args", "missing 'title'")
            if (!DevicePermission.runtimeGranted(context, Manifest.permission.WRITE_CALENDAR)) {
                return DevicePermission.missingError("WRITE_CALENDAR")
            }
            val startIso = obj.strArg("start")
            val allDay = startIso != null && startIso.length == 10
            val start = (if (allDay) startIso?.let(::utcDay) else null)
                ?: startIso?.let { parseTime(obj, "start", "start_ms") }
                ?: obj.longArg("start_ms")
                ?: return DeviceArgs.fail("invalid_args", "missing/invalid 'start' (ISO-8601 like 2026-10-30T15:00)")
            val end = (if (allDay) obj.strArg("end")?.takeIf { it.length == 10 }?.let(::utcDay)?.let {
                it + if (obj.strArg("end") == startIso) 86_400_000L else 0L
            } else null)
                ?: parseTime(obj, "end", "end_ms")
                ?: (start + if (allDay) 86_400_000L else 3_600_000L)
            if (end < start) return DeviceArgs.fail("invalid_args", "'end' is before 'start'")

            val cr = context.contentResolver
            // Writable (contributor+) and visible calendars only; prefer the primary one — the first
            // row used to be a Holidays/Birthdays subscription where inserts fail or stay invisible.
            val cal = runCatching {
                cr.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Calendars._ID,
                        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                        CalendarContract.Calendars.ACCOUNT_NAME,
                    ),
                    "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}" +
                        " AND ${CalendarContract.Calendars.VISIBLE} = 1",
                    null,
                    "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC",
                )?.use { c -> if (c.moveToFirst()) Triple(c.getLong(0), c.getString(1), c.getString(2)) else null }
            }.getOrNull() ?: return DeviceArgs.fail(
                "unsupported",
                "no writable calendar on this device (the user can add an account in the Calendar app)",
            )

            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, cal.first)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
                if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
                obj.strArg("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                obj.strArg("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            }
            val uri = runCatching { cr.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
                ?: return DeviceArgs.fail("failed", "the calendar provider refused the event")
            return "Event \"$title\" created ${FMT.format(Date(start))}–${FMT.format(Date(end))} " +
                "in calendar \"${cal.second}\" (${cal.third}). id=${uri.lastPathSegment}"
        }
    }

    // ── sms_read ──

    internal object SmsReadTool {
        const val NAME = "sms_read"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Read recent SMS messages (inbox and sent), newest first, optionally filtered by " +
                "number or body text. Needs the SMS permission. Sensitive: reads personal data.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "query" to AgentToolParam("string", "Filter on number or message body."),
                "limit" to AgentToolParam("integer", "Max messages (default 20, max 100)."),
            ),
            required = listOf(TOOL_TITLE_PARAM),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "query", "limit"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            if (!DevicePermission.runtimeGranted(context, Manifest.permission.READ_SMS)) {
                return DevicePermission.missingError("READ_SMS")
            }
            val q = obj.strArg("query")
            val limit = (obj.intArg("limit") ?: 20).coerceIn(1, 100)
            val rows = ArrayList<String>()
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                    q?.let { "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?" },
                    q?.let { arrayOf("%$it%", "%$it%") },
                    "${Telephony.Sms.DATE} DESC",
                )?.use { c ->
                    while (c.moveToNext() && rows.size < limit) {
                        val dir = when (c.getInt(3)) {
                            Telephony.Sms.MESSAGE_TYPE_INBOX -> "<-"
                            Telephony.Sms.MESSAGE_TYPE_SENT -> "->"
                            Telephony.Sms.MESSAGE_TYPE_DRAFT -> "(draft) ->"
                            Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> "(sending) ->"
                            Telephony.Sms.MESSAGE_TYPE_FAILED -> "(failed) ->"
                            else -> "?"
                        }
                        rows.add("${FMT.format(Date(c.getLong(2)))} $dir ${c.getString(0)}: ${c.getString(1)}")
                    }
                }
            }.getOrElse { return DeviceArgs.fail("failed", "sms query failed: ${it.message}") }
            return rows.joinToString("\n").ifEmpty { "No messages." }
        }
    }

    // ── call_log_read ──

    internal object CallLogReadTool {
        const val NAME = "call_log_read"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Read recent calls (incoming/outgoing/missed) with name, number, time and " +
                "duration, newest first. Needs the Call log permission. Sensitive: reads personal data.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "limit" to AgentToolParam("integer", "Max entries (default 20, max 100)."),
            ),
            required = listOf(TOOL_TITLE_PARAM),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "limit"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            if (!DevicePermission.runtimeGranted(context, Manifest.permission.READ_CALL_LOG)) {
                return DevicePermission.missingError("READ_CALL_LOG")
            }
            val limit = (obj.intArg("limit") ?: 20).coerceIn(1, 100)
            val rows = ArrayList<String>()
            runCatching {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.DATE,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DURATION,
                    ),
                    null, null, "${CallLog.Calls.DATE} DESC",
                )?.use { c ->
                    while (c.moveToNext() && rows.size < limit) {
                        val type = when (c.getInt(3)) {
                            CallLog.Calls.INCOMING_TYPE -> "in"
                            CallLog.Calls.OUTGOING_TYPE -> "out"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            else -> "other"
                        }
                        rows.add(
                            "${FMT.format(Date(c.getLong(2)))} $type ${c.getString(0) ?: "?"} " +
                                "${c.getString(1)} ${c.getLong(4)}s",
                        )
                    }
                }
            }.getOrElse { return DeviceArgs.fail("failed", "call log query failed: ${it.message}") }
            return rows.joinToString("\n").ifEmpty { "No calls." }
        }
    }

    // ── sms_send ──

    internal object SmsSendTool {
        const val NAME = "sms_send"
        private const val DELIVERY_TIMEOUT_MS = 30_000L

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Send an SMS to a phone number. Long texts are split into multipart messages " +
                "automatically; the result reports the carrier's send status per part. Dangerous: sends a " +
                "message on your behalf — confirm recipient and text with the user first.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "to" to AgentToolParam("string", "Destination phone number."),
                "text" to AgentToolParam("string", "Message text to send."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "to", "text"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "to", "text"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val to = obj.strArg("to") ?: return DeviceArgs.fail("invalid_args", "missing 'to'")
            val text = obj.strArg("text") ?: return DeviceArgs.fail("invalid_args", "missing 'text'")
            if (!DevicePermission.runtimeGranted(context, Manifest.permission.SEND_SMS)) {
                return DevicePermission.missingError("SEND_SMS")
            }
            @Suppress("DEPRECATION")
            val sms = (if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else null)
                ?: SmsManager.getDefault()
            val parts = runCatching { sms.divideMessage(text) }.getOrNull()
                ?: return DeviceArgs.fail("failed", "the message could not be divided for sending")
            if (parts.isEmpty()) return DeviceArgs.fail("invalid_args", "message text is empty")

            val action = "${context.packageName}.device.SMS_SENT.${java.util.UUID.randomUUID()}"
            val results = java.util.concurrent.ConcurrentHashMap<Int, Int>()
            val latch = CountDownLatch(1)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    results[i.getIntExtra("part", -1)] = resultCode
                    if (results.size >= parts.size) latch.countDown()
                }
            }
            val filter = IntentFilter(action)
            runCatching {
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            }.getOrElse {
                return DeviceArgs.fail("failed", "could not register the delivery receiver: ${it.message}")
            }
            try {
                val sent = ArrayList(parts.indices.map { idx ->
                    android.app.PendingIntent.getBroadcast(
                        context,
                        idx,
                        Intent(action).setPackage(context.packageName).putExtra("part", idx),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_ONE_SHOT,
                    )
                })
                runCatching { sms.sendMultipartTextMessage(to, null, parts, sent, null) }
                    .onFailure { return DeviceArgs.fail("failed", "sending failed: ${it.message}") }
            } finally {
                // Give the radio a moment for the delivery report, then always clean up the receiver.
                latch.await(DELIVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                runCatching { context.unregisterReceiver(receiver) }
            }
            val failed = results.values.filter { it != android.app.Activity.RESULT_OK }
            return when {
                results.isEmpty() ->
                    "SMS to $to was handed to the phone (${parts.size} part(s)) but no delivery report came " +
                        "within 30s; check the Messages app before resending."
                failed.isEmpty() && results.size >= parts.size ->
                    "SMS sent to $to (${parts.size} part(s))."
                failed.isEmpty() ->
                    "SMS to $to: ${results.size}/${parts.size} part(s) confirmed sent, the rest unconfirmed; " +
                        "check the Messages app before resending."
                else ->
                    DeviceArgs.fail(
                        "sms_failed",
                        "SMS to $to failed (${failed.map(::smsErrorText).distinct().joinToString()}). " +
                            "Do not retry blindly; tell the user.",
                    )
            }
        }
    }

    // ── location_get ──

    internal object LocationGetTool {
        const val NAME = "location_get"
        private const val FRESH_WINDOW_MS = 30_000L
        private const val FIX_TIMEOUT_MS = 20_000L

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "One-shot device location: latitude/longitude, accuracy, fix age and provider. " +
                "Returns the last known fix when fresh, otherwise requests a single update (up to 20s). " +
                "Needs the Location permission and location services on. Sensitive: reads personal data.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "timeout_seconds" to AgentToolParam("integer", "Max wait for a fresh fix (default 20, max 60)."),
            ),
            required = listOf(TOOL_TITLE_PARAM),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "timeout_seconds"),
        )

        private fun permissionLabel(context: Context): String? {
            val fine = DevicePermission.runtimeGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = DevicePermission.runtimeGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            return when {
                fine -> "ACCESS_FINE_LOCATION"
                coarse -> "ACCESS_COARSE_LOCATION"
                else -> null
            }
        }

        private fun enabledProviders(lm: LocationManager): List<String> =
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        @Suppress("DEPRECATION")
        private fun freshFix(lm: LocationManager, timeoutMs: Long): Location? {
            // 1) last-known fix across enabled providers, if fresh enough.
            val last = enabledProviders(lm).asSequence()
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (last != null && System.currentTimeMillis() - last.time <= FRESH_WINDOW_MS) return last

            // 2) request a single update on the best enabled provider, bounded by a latch.
            val provider = when {
                enabledProviders(lm).contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> enabledProviders(lm).firstOrNull() ?: return last
            }
            val latch = CountDownLatch(1)
            var received: Location? = null
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    received = location
                    latch.countDown()
                }

                override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            runCatching { lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper()) }
                .getOrElse { return last }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            runCatching { lm.removeUpdates(listener) }
            return received ?: last
        }

        private fun addressOf(context: Context, loc: Location): String? = runCatching {
            val geocoder = android.location.Geocoder(context, Locale.getDefault())
            if (!android.location.Geocoder.isPresent()) return@runCatching null
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.getAddressLine(0)?.takeIf { it.isNotBlank() }
        }.getOrNull()

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val granted = permissionLabel(context)
                ?: return DevicePermission.missingError("ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION")
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return DeviceArgs.fail("unsupported", "no location service on this device")
            val enabled = runCatching {
                if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
                else enabledProviders(lm).isNotEmpty()
            }.getOrDefault(false)
            if (!enabled) {
                return DeviceArgs.fail(
                    "unavailable",
                    "location services are off. Ask the user to turn location on (quick settings), then retry.",
                )
            }
            val timeoutMs = ((obj.intArg("timeout_seconds") ?: 20).coerceIn(1, 60)) * 1000L
            val loc = freshFix(lm, timeoutMs)
                ?: return DeviceArgs.fail(
                    "timeout",
                    "no location fix within ${timeoutMs / 1000}s. The device may be indoors or have no " +
                        "GPS/cell coverage; retry, or ask the user.",
                )
            val ageS = ((System.currentTimeMillis() - loc.time) / 1000).coerceAtLeast(0)
            val address = addressOf(context, loc)
            return buildString {
                append("lat=")
                append("%.6f".format(loc.latitude))
                append(" lng=")
                append("%.6f".format(loc.longitude))
                append(" accuracy=${loc.accuracy.toInt()}m age=${ageS}s provider=${loc.provider} (permission: $granted)")
                if (address != null) append("\naddress: ").append(address)
            }
        }
    }
}
