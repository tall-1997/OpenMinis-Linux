package com.openminis.app.cottranslate

import java.util.Locale

/**
 * [T-cot-translate] Anonymous machine-translation providers for displayed
 * reasoning. Protocol shape mirrors the public Bing Edge / Google translate
 * endpoints (the same anonymous routes DSH's cot-translation consumes); the
 * request/response formats are public web protocols, no credentials involved.
 */

/** Translation provider choices; [BING] is the default (reachable without a proxy).
 *  [LLM] routes through an already-configured model instance (second-phase route). */
enum class TranslationProvider { BING, GOOGLE, LLM }

/** One resolved translation job: source text + target language. */
data class TranslationRequest(val text: String, val targetLanguage: String)

/** Result of one HTTP round-trip; [TranslationProviderException] on failure. */
sealed interface TranslationResult {
    data class Ok(val text: String) : TranslationResult
    data class Failed(val reason: String) : TranslationResult
}

class TranslationProviderException(message: String) : Exception(message)

/** Bounded, synchronous HTTP execution seam (OkHttp behind; tests fake it). */
fun interface TranslationHttp {
    fun post(url: String, body: String, contentType: String, timeoutMs: Int): String?
}

/**
 * [T-cot-translate-auto] Resolve "auto" to a concrete language tag at
 * request time. Pure so it is unit-testable with an explicit locale; the
 * handle passes `Locale.getDefault().language` (e.g. "zh" on a zh-CN device).
 */
internal fun resolveTargetLanguage(tag: String, localeLanguage: String): String =
    if (tag.trim().lowercase() == "auto") localeLanguage else tag

/** Target-language tags normalized per provider (Google/Bing spellings differ). */
internal fun normalizedLanguage(tag: String, provider: TranslationProvider): String {
    val lower = tag.lowercase().trim()
    if (lower == "auto") return ""
    return when {
        lower in setOf("zh", "zh-cn", "zh-sg", "zh-hans") ->
            if (provider == TranslationProvider.GOOGLE) "zh-CN" else "zh-Hans"
        lower in setOf("zh-tw", "zh-hk", "zh-mo", "zh-hant") ->
            if (provider == TranslationProvider.GOOGLE) "zh-TW" else "zh-Hant"
        else -> tag
    }
}

/**
 * Translate [request.text] via the anonymous endpoint for [provider].
 * Returns [TranslationResult.Failed] on ANY failure (HTTP status, malformed
 * body, IO) — the caller always has the original text to fall back to.
 */
fun translateOnce(
    request: TranslationRequest,
    provider: TranslationProvider,
    http: TranslationHttp,
    timeoutMs: Int = 10_000,
    autoLanguage: String = Locale.getDefault().language,
): TranslationResult {
    val target = normalizedLanguage(
        resolveTargetLanguage(request.targetLanguage, autoLanguage), provider,
    )
    if (target.isEmpty()) return TranslationResult.Failed("empty target language")
    if (provider == TranslationProvider.LLM) {
        return TranslationResult.Failed("LLM route requires llmTranslate — internal routing error")
    }
    val (url, body, contentType) = when (provider) {
        TranslationProvider.LLM -> throw IllegalStateException("LLM route requires llmTranslate")
        TranslationProvider.GOOGLE -> Triple(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$target&dt=t",
            listOf("q" to request.text).joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, Charsets.UTF_8)}" },
            "application/x-www-form-urlencoded",
        )
        TranslationProvider.BING -> Triple(
            "https://edge.microsoft.com/translate/translatetext?to=$target&isEnterpriseClient=false",
            org.json.JSONArray().put(request.text).toString(),
            "application/json",
        )
    }
    val raw = try {
        http.post(url, body, contentType, timeoutMs) ?: return TranslationResult.Failed("null response body")
    } catch (e: TranslationProviderException) {
        throw e
    } catch (e: Exception) {
        return TranslationResult.Failed(e.message ?: e.javaClass.simpleName)
    }
    return parsePayload(raw, provider)?.let { TranslationResult.Ok(it) }
        ?: TranslationResult.Failed("$provider returned an invalid translation response")
}

/** Parse the provider-specific response shape; null = malformed. */
internal fun parsePayload(raw: String, provider: TranslationProvider): String? = try {
    when (provider) {
        TranslationProvider.LLM -> null
        TranslationProvider.BING -> {
            // Microsoft Edge translate: [{"detectedLanguage":…,"translations":
            // [{"text":"…","to":"…"}]}] — the outer ARRAY wraps per-input objects.
            val outer = org.json.JSONArray(raw)
            val first = outer.optJSONObject(0) ?: return null
            val translations = first.optJSONArray("translations") ?: return null
            buildString {
                for (i in 0 until translations.length()) {
                    append(translations.optJSONObject(i)?.safeText("text") ?: return null)
                }
            }.ifEmpty { null }
        }
        TranslationProvider.GOOGLE -> {
            // Google translate_a/single returns one top-level array PER
            // sentence group for multi-sentence input — concatenate every
            // group so nothing after the first batch is dropped.
            val outer = org.json.JSONArray(raw)
            buildString {
                for (g in 0 until outer.length()) {
                    val group = outer.optJSONArray(g) ?: continue
                    for (i in 0 until group.length()) {
                        val seg = group.optJSONArray(i) ?: continue
                        append(seg.optString(0))
                    }
                }
            }.ifEmpty { null }
        }
    }
} catch (_: Exception) {
    null
}

private fun org.json.JSONObject.safeText(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").ifEmpty { null }
}

/** Default request size bound (UTF-16 units), mirroring DSH's translator default. */
internal const val MAX_TEXT_CHARS = 4_000
