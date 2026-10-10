package com.openminis.app.i18n

import java.util.Locale

/**
 * [T-translate-lang-picker] 实时翻译的语言目录：内置常用语言，设置页用
 * 下拉选择替代手输 BCP-47 代码（手输错码 ML Kit 直接抛 IllegalArgumentException，
 * 用户无从排查）。displayName 走系统 Locale 本地化（zh 设备显示「英语/日语」），
 * 同一函数也把 code 转成喂给 LLM 翻译的自然语言目标名。
 */
object TranslationLanguages {
    /** 常用语言（ML Kit 与主流 LLM 都覆盖）。顺序 = 选择器展示顺序。 */
    val CODES = listOf(
        "en", "zh", "ja", "ko", "de", "fr", "es", "ru",
        "pt", "it", "ar", "hi", "th", "vi", "id", "tr",
    )

    /** code 的本地化显示名（zh 设备「英语」，en 设备 "English"）。 */
    fun displayName(code: String): String {
        val tag = code.trim().lowercase(Locale.ROOT)
        if (tag.isEmpty()) return code
        return runCatching {
            Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault()).ifBlank { tag }
        }.getOrDefault(tag)
    }
}
