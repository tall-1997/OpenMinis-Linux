package com.openminis.app.i18n

import android.content.Context
import android.content.SharedPreferences

/** Bubble translation preferences. Not the model-group default slot. */
object TranslationPrefs {
    private const val PREFS = "translate_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LANG = "lang"
    private const val KEY_ENTRY = "entry_id"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun lang(context: Context): String = prefs(context).getString(KEY_LANG, "中文")?.ifBlank { "中文" } ?: "中文"

    fun entryId(context: Context): String? = prefs(context).getString(KEY_ENTRY, null)?.ifBlank { null }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setLang(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_LANG, lang.trim().ifBlank { "中文" }).apply()
    }

    fun setEntryId(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_ENTRY, id).apply()
    }
}
