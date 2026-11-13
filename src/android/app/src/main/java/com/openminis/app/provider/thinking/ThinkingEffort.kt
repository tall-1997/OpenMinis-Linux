package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject

/**
 * [T-thinking-rules-phase2] Effort-string mapping and clamping for the
 * thinking-rule resolver, extracted for the architecture-policy line ceiling.
 */
internal object ThinkingEffort {
    /** UI level → wire tier, before any per-model clamp. */
    fun wireEffort(level: ThinkingLevel): String = when (level) {
        ThinkingLevel.OFF, ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // ULTRA is a client-side "Max + orchestration" concept and is NEVER a valid
        // server effort string for a model whose set stops at "max" (iOS b38bf3d5).
        // [T-thinking-ultra-declared] The declared-ultra exception is handled
        // by the CALLER: ThinkingRuleResolver checks the model's declared set
        // for "ultra" and emits the literal there; this default fold keeps
        // every other backend from ever seeing the string.
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
    }

    /**
     * [T-thinking-ultra-declared] True when the model's declared effort set
     * literally names "ultra" — the one case where ULTRA is a real wire tier
     * rather than a client-side "max + orchestration" fold. Kept as one
     * helper so the three emitter branches (nested / OpenAI-native /
     * generic) cannot drift apart on the spelling or the case rule.
     */
    fun declaresUltraTier(declared: List<String>?): Boolean =
        declared?.any { it.equals("ultra", ignoreCase = true) } == true

    /**
     * [T-thinking-ultra-declared] Case-insensitive "the declared set names this
     * tier". `absent = true` inverts the null case: a NULL set (the catalog
     * never heard of this model) reads as "not declaring against it", so the
     * caller keeps sending the tier — the same permissive semantics the old
     * `contains(...) != false` spelled.
     */
    fun declaresTier(declared: List<String>?, tier: String, absent: Boolean = false): Boolean =
        when {
            declared == null -> absent
            else -> declared.any { it.equals(tier, ignoreCase = true) }
        }

    /**
     * [OpenMinis#356] Legal effort set of DeepSeek V4, used for the
     * `deepseek-flash` alias whose catalog row predates the model. Values
     * outside it are clamped down by [clampEffort] (xhigh → high).
     */
    internal val DEEPSEEK_V4_EFFORT_VALUES = listOf("low", "medium", "high", "max")

    /**
     * [OpenMinis#377] Snap an explicit OFF tier onto a value the model's
     * protocol actually accepts.
     *
     * Returns [offEffort] unchanged when the catalog declares no set (nothing
     * to clamp against — preserves the existing allowlist behaviour) or when
     * the value is not "none". "minimal" is Volcano Ark's documented off tier
     * and survives a declared set that lists only higher reasoning tiers.
     * For "none": the value itself when declared, else the lowest DECLARED tier
     * at or below "low". When the model only declares high/max (nothing at or
     * below low) the field is omitted: sending "high" on an OFF request would
     * be the opposite of what the caller asked for, and no legal off tier
     * exists.
     */
    fun clampOffEffort(offEffort: String?, declared: List<String>?): String? {
        if (offEffort == null) return null
        if (declared.isNullOrEmpty()) return offEffort
        // [T-thinking-ultra-declared] Same case-insensitive rule as
        // clampEffort: a capitalized "None"/"Low" in the declared set is the
        // same tier as its lowercase spelling.
        declared.firstOrNull { it.equals(offEffort, ignoreCase = true) }
            ?.let { return it.lowercase() }
        // [OpenMinis#377] Scoped to "none": see the doc above.
        if (offEffort != "none") return offEffort
        val ladder = listOf("none", "minimal", "low")
        if (ladder.none { l -> declared.any { d -> d.equals(l, ignoreCase = true) } }) return null
        return clampEffort(offEffort, declared)
    }

    /**
     * [OpenMinis#306] Write [value] at a dotted [path], creating intermediate
     * objects. Used by the user-authored [ThinkingWireFormat.BooleanToggle] /
     * [ThinkingWireFormat.ExtraBodyToggle] / [ThinkingWireFormat.CustomPath]
     * emitters. A blank path is a no-op rather than an exception.
     */
    internal fun putDotted(body: JSONObject, path: String, value: Any) {
        val segments = path.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return
        var cursor = body
        for (segment in segments.dropLast(1)) {
            cursor = cursor.optJSONObject(segment) ?: JSONObject().also { cursor.put(segment, it) }
        }
        cursor.put(segments.last(), value)
    }

    /**
     * [T-android-xhigh-effort-clamp] MiMo/Agnes reject xhigh (400/422); their ladder tops
     * out at high. Matches the FAMILY substring, not one spelling — the live API serves
     * `mimo-v2.5` while docs say `mimo-2.5` (iOS 72968c4f).
     */
    fun clampEffortForModel(effort: String, lid: String): String =
        if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort

    /** Snap a requested tier onto the model's declared set, walking DOWN then up. */
    fun clampEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty()) return effort
        // [T-thinking-ultra-declared] Case-insensitive throughout: the catalog
        // layer (selectableThinkingLevels) already lowercases the declared set,
        // but a relay can publish "Max"/"Ultra" capitalized — a case-sensitive
        // ladder lookup silently dropped them and clamped ULTRA down to "high"
        // (CI: declared ultra tier matches case-insensitively on the wire).
        // The emitted spelling is normalized to lowercase, the wire convention
        // every real API uses.
        values.firstOrNull { it.equals(effort, ignoreCase = true) }
            ?.let { return it.lowercase() }
        // [T-thinking-ultra-declared] "ultra" sits above "max" on the ladder
        // so a declared ["high","max","ultra"] set clamps a ULTRA request to
        // "ultra" (exact hit above) and an undeclared set still walks DOWN to
        // "max". For every model whose set stops at "max", wireEffort has
        // already folded ULTRA→"max" before this runs, so the extra rung
        // changes nothing for them.
        val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
        val want = ladder.indexOf(effort)
        if (want < 0) return effort
        val declared = values.mapNotNull { v ->
            val i = ladder.indexOf(v.lowercase())
            if (i >= 0) i to v.lowercase() else null
        }.sortedBy { it.first }
        if (declared.isEmpty()) return effort
        return declared.lastOrNull { it.first <= want }?.second ?: declared.first().second
    }
}

