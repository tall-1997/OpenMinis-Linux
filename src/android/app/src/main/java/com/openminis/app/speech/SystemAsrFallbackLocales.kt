package com.openminis.app.speech

import java.util.Locale

/**
 * [T-android-asr-script-subtag] The locale ladder the System engine walks
 * down when the saved locale is unusable, extracted from
 * [SystemSpeechRecognitionEngine] so the engine file stays under the
 * architecture ceiling. No behaviour change.
 *
 * Script-stripped: the raw default on a Simplified-Chinese device is
 * zh-Hans-CN, which the recognizer rejects. Since this list is also what
 * SpeechRecognitionManager re-validates the saved locale against, leaving
 * the raw default here made the broken locale look "supported" and the
 * fallback never fired.
 */
internal fun systemAsrFallbackLocales(defaultTag: String): List<Locale> {
    return listOf(
        Locale.forLanguageTag(defaultTag),
        Locale.forLanguageTag("en-US"),
        Locale.forLanguageTag("zh-CN"),
        Locale.forLanguageTag("zh-TW"),
        Locale.forLanguageTag("ja-JP"),
        Locale.forLanguageTag("ko-KR"),
        Locale.forLanguageTag("es-ES"),
        Locale.forLanguageTag("fr-FR"),
        Locale.forLanguageTag("de-DE"),
        Locale.forLanguageTag("it-IT"),
        Locale.forLanguageTag("pt-BR"),
        Locale.forLanguageTag("ru-RU"),
        Locale.forLanguageTag("ar-SA"),
        Locale.forLanguageTag("hi-IN"),
        Locale.forLanguageTag("id-ID"),
        Locale.forLanguageTag("th-TH"),
        Locale.forLanguageTag("vi-VN"),
        Locale.forLanguageTag("tr-TR"),
    ).distinctBy { it.toLanguageTag() }
}
