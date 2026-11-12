package com.openminis.app

import android.content.Context
import android.util.Log

// T268: replay pre-T266 alarms/timers from prefs through SET_ALARM/SET_TIMER, then clear the blob (idempotent, past-dated dropped).
// 拆分自 MinisApp.kt（架构行数上限），行为逐字节保持。
internal fun MinisApp.migrateGhostAlarmsLegacy() {
        val prefs = getSharedPreferences("minis_alarms_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("alarms_json", null) ?: return
        if (raw.isBlank() || raw == "[]") return
        val arr = org.json.JSONArray(raw)
        if (arr.length() == 0) {
            prefs.edit().remove("alarms_json").apply()
            return
        }
        val now = System.currentTimeMillis()
        var migrated = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val triggerAt = entry.optLong("triggerAtMs", 0L)
            if (triggerAt in 1L..now && entry.optString("type") == "timer") {
                skipped++; continue  // Past timer — nothing to recover.
            }
            if (triggerAt in 1L..now && entry.optString("repeatMode", "ONCE") == "ONCE") {
                skipped++; continue  // Past one-shot alarm.
            }
            val migrationOk = runCatching {
                if (entry.optString("type") == "timer") {
                    val secs = entry.optInt("durationSec", -1)
                    val remaining = ((triggerAt - now) / 1000L).toInt()
                    if (remaining <= 0 && secs <= 0) return@runCatching false
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_LENGTH, if (remaining > 0) remaining else secs)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Timer"))
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                } else {
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, entry.optInt("hour", 0))
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, entry.optInt("minute", 0))
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Alarm"))
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }
            }.getOrDefault(false)
            if (migrationOk) migrated++ else skipped++
        }
        // Clear the blob unconditionally — entries we couldn't replay are
        // still useless ghosts, and leaving the blob would re-trigger
        // migration on every launch.
        prefs.edit().remove("alarms_json").apply()
        Log.i("MinisApp", "T268 ghost alarm migration: migrated=$migrated skipped=$skipped (prefs cleared)")
    }
