package com.openminis.app.tools

import android.content.Context

/**
 * User-configurable search backend for [WebSearchTool].
 * DuckDuckGo stays the default (no key). SearXNG / Bing / custom are opt-in.
 */
object WebSearchSettings {
    const val PREFS = "web_search_prefs"
    const val KEY_ENGINE = "engine"
    const val KEY_SEARXNG_URL = "searxng_url"
    const val KEY_BING_KEY = "bing_key"
    const val KEY_FALLBACK = "fallback_ddg"
    const val KEY_CUSTOM_URL = "custom_url"
    const val KEY_CUSTOM_KEY = "custom_key"
    const val KEY_CUSTOM_KEY_HEADER = "custom_key_header"
    const val KEY_TAVILY = "tavily_key"
    const val KEY_BOCHA = "bocha_key"
    const val KEY_EXA = "exa_key"
    const val KEY_BRAVE = "brave_key"
    const val KEY_JINA = "jina_key"
    const val KEY_ZHIPU = "zhipu_key"

    enum class Engine(val id: String, val needsKey: Boolean = false) {
        DDG("ddg"),
        SEARXNG("searxng"),
        BING("bing", needsKey = true),
        TAVILY("tavily", needsKey = true),
        BOCHA("bocha", needsKey = true),
        EXA("exa", needsKey = true),
        BRAVE("brave", needsKey = true),
        JINA("jina", needsKey = true),
        ZHIPU("zhipu", needsKey = true),
        CUSTOM("custom"),
        ;

        companion object {
            fun fromId(raw: String?): Engine =
                entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: DDG
        }
    }

    fun engine(context: Context): Engine {
        val raw = prefs(context).getString(KEY_ENGINE, null)
        if (!raw.isNullOrBlank()) return Engine.fromId(raw)
        return firstConfigured(context) ?: Engine.DDG
    }

    /** Keyed backends the user has already filled in, in enum order. */
    fun configuredKeyed(context: Context): List<Engine> =
        Engine.entries.filter { it.needsKey && apiKey(context, it).isNotEmpty() }

    private fun firstConfigured(context: Context): Engine? = configuredKeyed(context).firstOrNull()

    fun setEngine(context: Context, engine: Engine) {
        prefs(context).edit().putString(KEY_ENGINE, engine.id).apply()
    }

    fun searxngUrl(context: Context): String =
        prefs(context).getString(KEY_SEARXNG_URL, "")?.trim().orEmpty()

    fun setSearxngUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SEARXNG_URL, url.trim()).apply()
    }

    fun bingKey(context: Context): String =
        prefs(context).getString(KEY_BING_KEY, "")?.trim().orEmpty()

    fun setBingKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_BING_KEY, key.trim()).apply()
    }

    fun apiKey(context: Context, engine: Engine): String {
        val key = when (engine) {
            Engine.BING -> KEY_BING_KEY
            Engine.TAVILY -> KEY_TAVILY
            Engine.BOCHA -> KEY_BOCHA
            Engine.EXA -> KEY_EXA
            Engine.BRAVE -> KEY_BRAVE
            Engine.JINA -> KEY_JINA
            Engine.ZHIPU -> KEY_ZHIPU
            else -> return ""
        }
        return prefs(context).getString(key, "")?.trim().orEmpty()
    }

    fun setApiKey(context: Context, engine: Engine, value: String) {
        val key = when (engine) {
            Engine.BING -> KEY_BING_KEY
            Engine.TAVILY -> KEY_TAVILY
            Engine.BOCHA -> KEY_BOCHA
            Engine.EXA -> KEY_EXA
            Engine.BRAVE -> KEY_BRAVE
            Engine.JINA -> KEY_JINA
            Engine.ZHIPU -> KEY_ZHIPU
            else -> return
        }
        prefs(context).edit().putString(key, value.trim()).apply()
    }

    fun customUrl(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_URL, "")?.trim().orEmpty()

    fun setCustomUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_CUSTOM_URL, url.trim()).apply()
    }

    fun customKey(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_KEY, "")?.trim().orEmpty()

    fun setCustomKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_CUSTOM_KEY, key.trim()).apply()
    }

    fun customKeyHeader(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_KEY_HEADER, "")?.trim().orEmpty()

    fun setCustomKeyHeader(context: Context, header: String) {
        prefs(context).edit().putString(KEY_CUSTOM_KEY_HEADER, header.trim()).apply()
    }

    fun fallbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FALLBACK, true)

    fun setFallbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FALLBACK, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
