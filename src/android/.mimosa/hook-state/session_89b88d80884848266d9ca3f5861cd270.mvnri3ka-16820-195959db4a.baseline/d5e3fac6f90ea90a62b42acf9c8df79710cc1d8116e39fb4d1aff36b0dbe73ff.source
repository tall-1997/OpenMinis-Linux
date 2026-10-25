package com.openminis.app.evolution

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Evolution master switch. Default **off** — harvesting and Be-ACTIVE
 * never run until the user opts in from Settings.
 */
class EvolutionPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    fun dailyCap(): Int = prefs.getInt(KEY_DAILY_CAP, DEFAULT_DAILY_CAP)

    fun tryConsumeLlmCall(): Boolean {
        val today = dayStamp()
        val storedDay = prefs.getString(KEY_LLM_DAY, "")
        val count = if (storedDay == today) prefs.getInt(KEY_LLM_COUNT, 0) else 0
        if (count >= dailyCap()) return false
        prefs.edit()
            .putString(KEY_LLM_DAY, today)
            .putInt(KEY_LLM_COUNT, count + 1)
            .apply()
        return true
    }

    fun recordLlmFailure() {
        val fails = prefs.getInt(KEY_LLM_FAILS, 0) + 1
        prefs.edit().putInt(KEY_LLM_FAILS, fails).apply()
    }

    fun recordLlmSuccess() {
        prefs.edit().putInt(KEY_LLM_FAILS, 0).apply()
    }

    fun llmFused(): Boolean = prefs.getInt(KEY_LLM_FAILS, 0) >= 3

    fun harvestCooldownElapsed(now: Long = System.currentTimeMillis()): Boolean {
        val last = prefs.getLong(KEY_LAST_HARVEST, 0L)
        return now - last >= HARVEST_COOLDOWN_MS
    }

    fun markHarvested(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_HARVEST, now).apply()
    }

    fun reflectCooldownElapsed(now: Long = System.currentTimeMillis()): Boolean {
        val last = prefs.getLong(KEY_LAST_REFLECT, 0L)
        return now - last >= REFLECT_COOLDOWN_MS
    }

    fun markReflected(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_REFLECT, now).apply()
    }

    companion object {
        private const val PREFS = "minis_evolution_prefs"
        private const val KEY_ENABLED = "evolution.enabled"
        private const val KEY_DAILY_CAP = "evolution.daily_cap"
        private const val KEY_LLM_DAY = "evolution.llm_day"
        private const val KEY_LLM_COUNT = "evolution.llm_count"
        private const val KEY_LLM_FAILS = "evolution.llm_fails"
        private const val KEY_LAST_HARVEST = "evolution.last_harvest_at"
        private const val KEY_LAST_REFLECT = "evolution.last_reflect_at"
        const val DEFAULT_DAILY_CAP = 8
        const val HARVEST_COOLDOWN_MS = 30L * 60 * 1000
        const val REFLECT_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000
        const val MIN_BELIEF_HITS = 2
        const val SKILL_FAIL_THRESHOLD = 3
        const val MAX_TRANSCRIPT_CHARS = 8_192
        const val MAX_EVIDENCE_CHARS = 1_200

        private fun dayStamp(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
