// [OpenMinis#377] OFF-tier wire value for OpenAI-compatible endpoints.
package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.thinking.ThinkingEffort

/**
 * [T-thinking-off-explicit] The wire value for "thinking OFF", or null to keep
 * the historical omit-the-field behavior. ALLOWLIST, not blanket (mirrors iOS
 * OpenAIAgentProvider.explicitOffEffort): only vendors whose off tier is
 * DOCUMENTED get an explicit value —
 *   • official OpenAI base (non-Azure) → "none" when the model/protocol
 *     documents it (GPT-5.1+); o-series / original GPT-5 get the lowest
 *     documented floor instead (low / minimal) — [OpenMinis#377];
 *   • Volcano Ark (volces/ark bases, seed/doubao families) → "minimal"
 *     (their smallest tier — Ark's non-off default is what motivated this).
 * Everyone else (relays, NIM, xAI, MiMo, …) keeps field omission = the
 * vendor's own default. Azure stays omission too: its off tier is
 * model-dependent ('none' on gpt-5.1+, 'minimal' on original gpt-5,
 * unsupported on o1/o3), so an explicit value risks a 400.
 *
 * Extracted from OpenAIProvider so the provider file stays inside its
 * architecture-policy line ceiling; the decision is pure and testable here.
 */
internal object OpenAIOffEffort {

    fun resolve(isAzure: Boolean, basePath: String, model: LLMModel): String? {
        if (isAzure) return null
        val base = basePath.lowercase()
        val lid = model.id.lowercase()
        val declared = model.reasoningEffortValues
        if (base.startsWith("https://api.openai.com")) {
            // [OpenMinis#377] `none` is only a documented off tier from GPT-5.1
            // onwards. o-series and the original GPT-5 accept low|medium|high
            // and (for gpt-5) minimal — sending "none" there is a hard 400. The
            // symptom showed up first on the COMPACTION path (thinkingLevel
            // pinned to OFF and routed to those cheaper models), but every OFF
            // request is affected.
            //
            // 1) A declared effort set is authoritative: clamp down onto its
            //    lowest legal tier (or omit when it only declares high/max).
            if (!declared.isNullOrEmpty()) {
                return ThinkingEffort.clampOffEffort("none", declared)
            }
            // 2) Catalog silent: fall back to the family's documented floor.
            val gpt5Minor = if (lid.startsWith("gpt-5.")) {
                lid.removePrefix("gpt-5.").takeWhile { it.isDigit() }.toIntOrNull()
            } else null
            return when {
                lid.startsWith("o") -> "low"          // o1/o3/o4: low|medium|high
                gpt5Minor != null && gpt5Minor >= 1 -> "none"  // gpt-5.1+ documents "none"
                lid.startsWith("gpt-6") -> "none"
                lid.contains("gpt-5") -> "minimal"    // original gpt-5 family
                else -> "none"
            }
        }
        if (base.contains("volces") || base.contains("ark.") ||
            lid.contains("seed-") || lid.contains("doubao")
        ) {
            // Ark's documented off tier is "minimal"; clamp it too in case the
            // model declares a narrower set that does not contain it.
            return ThinkingEffort.clampOffEffort("minimal", declared)
        }
        return null
    }
}
