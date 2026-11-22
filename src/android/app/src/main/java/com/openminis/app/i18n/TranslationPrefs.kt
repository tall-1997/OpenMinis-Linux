package com.openminis.app.i18n

import android.content.Context
import android.content.SharedPreferences

/** Bubble translation preferences. Not the model-group default slot. */
object TranslationPrefs {
    private const val PREFS = "translate_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LANG = "lang"
    private const val KEY_ENTRY = "entry_id"

    fun prefs(context: Context): com.openminis.app.config.PrefsHandle =
        com.openminis.app.config.Prefs.file(PREFS)

    fun isEnabled(context: Context): Boolean = prefs(context).bool(KEY_ENABLED, true)

    fun lang(context: Context): String = prefs(context).string(KEY_LANG, "中文")?.ifBlank { "中文" } ?: "中文"

    fun entryId(context: Context): String? = prefs(context).stringOrNull(KEY_ENTRY)?.ifBlank { null }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).set(KEY_ENABLED, enabled)
    }

    fun setLang(context: Context, lang: String) {
        prefs(context).set(KEY_LANG, lang.trim().ifBlank { "中文" })
    }

    fun setEntryId(context: Context, id: String?) {
        prefs(context).set(KEY_ENTRY, id)
    }
}
