package com.openminis.app.i18n

import android.content.Context
import android.content.SharedPreferences

/** Bubble translation preferences. Not the model-group default slot. */
object TranslationPrefs {
    private const val PREFS = "translate_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LANG = "lang"
    private const val KEY_ENTRY = "entry_id"
    // [T-mlkit-stream-translate] 思考流/输出流的实时离线翻译偏好。
    private const val KEY_STREAM_ENABLED = "stream_enabled"
    private const val KEY_STREAM_SOURCE = "stream_source"
    private const val KEY_STREAM_TARGET = "stream_target"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun lang(context: Context): String = prefs(context).getString(KEY_LANG, "中文")?.ifBlank { "中文" } ?: "中文"

    fun entryId(context: Context): String? = prefs(context).getString(KEY_ENTRY, null)?.ifBlank { null }

    /** 实时离线翻译开关（默认关：ML Kit 需要按需下载语言包 + 依赖 Play 基建）。 */
    fun isStreamEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_STREAM_ENABLED, false)

    fun streamSource(context: Context): String = prefs(context).getString(KEY_STREAM_SOURCE, "en")?.ifBlank { "en" } ?: "en"

    fun streamTarget(context: Context): String = prefs(context).getString(KEY_STREAM_TARGET, "zh")?.ifBlank { "zh" } ?: "zh"

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setLang(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_LANG, lang.trim().ifBlank { "中文" }).apply()
    }

    fun setEntryId(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_ENTRY, id).apply()
    }

    fun setStreamEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STREAM_ENABLED, enabled).apply()
    }

    fun setStreamSource(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_STREAM_SOURCE, lang.trim().ifBlank { "en" }).apply()
    }

    fun setStreamTarget(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_STREAM_TARGET, lang.trim().ifBlank { "zh" }).apply()
    }
}
