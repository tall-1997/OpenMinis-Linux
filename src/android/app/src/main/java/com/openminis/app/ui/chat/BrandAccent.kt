package com.openminis.app.ui.chat

import com.openminis.app.ui.tokens.RawColors
import com.openminis.app.ui.tokens.asColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-brand-accent-color] Brand accent used for the usage-page share bars.
 *
 * Two problems this solves, both invisible in a screenshot of a single theme:
 *
 *  1. **Black brands vanish on dark.** Kimi, OpenAI, Grok, Qwen and Hailuo all
 *     ship a pure-black mark. Reusing that literal as a 3dp bar fill makes the
 *     bar disappear against a dark surface — the number is there, the visual is
 *     not. Dark mode therefore uses per-brand LIGHT variants chosen to stay
 *     recognisably on-brand (Kimi keeps its cool blue-grey family, Qwen borrows
 *     its own purple, Hailuo its teal).
 *  2. **Brand must NOT follow the wallpaper.** In dynamic-colour mode the scheme
 *     recolours surfaces and accents; a brand bar that followed it would paint
 *     every vendor the same hue and destroy the only cue telling rows apart.
 *     Brand accents are deliberately identical across the static and dynamic
 *     paths, keyed only on light/dark.
 *
 * Unknown slugs have no brand colour — callers get a deterministic hash hue so
 * two unrecognised rows still differ from each other.
 */
internal object BrandAccent {
    /** Light-theme brand accents: the vendors' own primary hues. */
    private val LIGHT: Map<String, Color> = mapOf(
        "zhipu" to RawColors.Brand.zhipu.asColor,
        "deepseek" to RawColors.Brand.deepseek.asColor,
        "claude" to RawColors.Brand.claude.asColor,
        "gemini" to RawColors.Brand.gemini.asColor,
        "wenxin" to RawColors.Brand.wenxin.asColor,
        "minimax" to RawColors.Brand.minimax.asColor,
        "hailuo" to RawColors.Brand.hailuo.asColor,
        "kimi" to RawColors.Brand.kimi.asColor,
        "openai" to RawColors.Brand.openai.asColor,
        "grok" to RawColors.Brand.grok.asColor,
        "qwen" to RawColors.Brand.qwen.asColor,
        "moonshot" to RawColors.Brand.moonshot.asColor,
        "meta" to RawColors.Brand.meta.asColor,
        "mistral" to RawColors.Brand.mistral.asColor,
        "cohere" to RawColors.Brand.cohere.asColor,
        "perplexity" to RawColors.Brand.perplexity.asColor,
        "openrouter" to RawColors.Brand.openrouter.asColor,
        "groq" to RawColors.Brand.groq.asColor,
        "baichuan" to RawColors.Brand.baichuan.asColor,
        "hunyuan" to RawColors.Brand.hunyuan.asColor,
    )

    /**
     * Dark-theme overrides. Only NEAR-BLACK brands strictly need one — the
     * coloured brands keep their hue and simply read fine on a dark surface;
     * a few are nudged brighter so a 3dp bar does not look muddy. Kept as a
     * separate map so the light table stays the vendors' literal colours.
     */
    private val DARK_OVERRIDES: Map<String, Color> = mapOf(
        "kimi" to RawColors.BrandDark.kimi.asColor,
        "openai" to RawColors.BrandDark.openai.asColor,
        "grok" to RawColors.BrandDark.grok.asColor,
        "qwen" to RawColors.BrandDark.qwen.asColor,
        "hailuo" to RawColors.BrandDark.hailuo.asColor,
        "moonshot" to RawColors.BrandDark.moonshot.asColor,
        "cohere" to RawColors.BrandDark.cohere.asColor,
        "mistral" to RawColors.BrandDark.mistral.asColor,
        "wenxin" to RawColors.BrandDark.wenxin.asColor,
        "hunyuan" to RawColors.BrandDark.hunyuan.asColor,
        "baichuan" to RawColors.BrandDark.baichuan.asColor,
        "meta" to RawColors.BrandDark.meta.asColor,
        "groq" to RawColors.BrandDark.groq.asColor,
        "minimax" to RawColors.BrandDark.minimax.asColor,
        "zhipu" to RawColors.BrandDark.zhipu.asColor,
        "deepseek" to RawColors.BrandDark.deepseek.asColor,
        "claude" to RawColors.BrandDark.claude.asColor,
        "gemini" to RawColors.BrandDark.gemini.asColor,
    )

    /** Palette for brands with no known accent (deterministic per name). */
    private val LIGHT_HASH = listOf(
        0xFF5B8DEFL.asColor,
        0xFF3FA36AL.asColor,
        0xFFD4654AL.asColor,
        0xFF8A6AD6L.asColor,
        0xFFC48A2AL.asColor,
        0xFF3A9CA8L.asColor,
    )

    private val DARK_HASH = listOf(
        0xFF8AB4F8L.asColor,
        0xFF7BC98FL.asColor,
        0xFFE89478L.asColor,
        0xFFB79CE8L.asColor,
        0xFFE4B65CL.asColor,
        0xFF6FC4D0L.asColor,
    )

    /** Brand accent for a lobe slug, or null when the slug has no entry. */
    fun of(slug: String?, dark: Boolean): Color? {
        if (slug.isNullOrBlank()) return null
        if (dark) DARK_OVERRIDES[slug]?.let { return it }
        return LIGHT[slug]
    }

    /**
     * Deterministic accent for a row with no resolvable brand. The SAME name
     * always yields the same hue, so a model never changes colour between
     * recompositions, theme toggles or app launches (the offline-hash rule the
     * avatar fallback already follows).
     */
    fun fallback(name: String, dark: Boolean): Color {
        val palette = if (dark) DARK_HASH else LIGHT_HASH
        return palette[name.hashCode().ushr(1) % palette.size]
    }

    /**
     * The composed entry point: brand accent when the model resolves to a known
     * logo, otherwise the deterministic hash hue.
     */
    fun forModel(modelId: String?, displayName: String?, dark: Boolean): Color {
        val slug = ModelBrandResolver.resolveExact(modelId, displayName)
        return of(slug, dark) ?: fallback(modelId ?: displayName ?: "?", dark)
    }
}

/**
 * Theme-aware brand accent for a model row.
 *
 * [ChatColors.isDark] follows the app's RESOLVED theme — the in-app override
 * (Settings → Appearance) included — and is the single source of truth the
 * rest of the UI already reads. A system-level dark check would miss a user
 * who forces dark in-app while the system stays light, painting light brand
 * accents on a dark surface.
 */
@Composable
internal fun brandAccentFor(modelId: String?, displayName: String?): Color {
    val dark = ChatColors.isDark
    return remember(modelId, displayName, dark) {
        BrandAccent.forModel(modelId, displayName, dark)
    }
}
