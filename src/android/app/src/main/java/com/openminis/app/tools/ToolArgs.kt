package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * [P2-convergence] Shared JSON-argument readers for the agent tool families.
 * The device group (device/DeviceArgs.kt), the knowledge tools
 * (AppKnowledgeTools), the virtual-screen tool and the automation-rule tools
 * each used to hand-roll the same `has/isNull/optString/trim` dance; the
 * variants below are those shapes, once.
 *
 * Two numeric dialects exist on purpose — do not "unify" them:
 *  - [int]/[long] are LENIENT, matching the device tools' historic contract:
 *    a numeric string goes through `toDoubleOrNull`, so "12.5" truncates to 12.
 *  - [strictInt] is STRICT, matching the virtual-screen tools: a string must
 *    be a plain integer (`toIntOrNull`), so "12.5" is absent — model-supplied
 *    coordinates/display ids are refused before they can reach the manager.
 *
 * Every reader returns null for a missing key, an explicit JSON null, or an
 * unparseable value; callers own the default/fallback.
 */
internal object ToolArgs {

    /** String arg: present, non-null and non-blank (trimmed). */
    fun str(args: JSONObject, key: String): String? {
        if (!args.has(key) || args.isNull(key)) return null
        return args.optString(key).trim().takeIf { it.isNotEmpty() }
    }

    /** Int arg: accepts a JSON number or a numeric string ("12"); anything else is absent. */
    fun int(args: JSONObject, key: String): Int? {
        if (!args.has(key) || args.isNull(key)) return null
        return when (val v = args.get(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    /** Long arg: same leniency contract as [int]. */
    fun long(args: JSONObject, key: String): Long? {
        if (!args.has(key) || args.isNull(key)) return null
        return when (val v = args.get(key)) {
            is Number -> v.toLong()
            is String -> v.trim().toDoubleOrNull()?.toLong()
            else -> null
        }
    }

    /** Double arg: JSON number or numeric string; absent/null/unparseable is null. */
    fun optDouble(args: JSONObject, key: String): Double? {
        if (!args.has(key) || args.isNull(key)) return null
        return when (val v = args.get(key)) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
    }

    /** Bool arg: JSON boolean, "true"/"1"/"yes" / "false"/"0"/"no" strings, non-zero numbers. */
    fun bool(args: JSONObject, key: String): Boolean? {
        if (!args.has(key) || args.isNull(key)) return null
        return when (val v = args.get(key)) {
            is Boolean -> v
            is String -> v.trim().lowercase().let {
                when (it) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> null
                }
            }
            is Number -> v.toInt() != 0
            else -> null
        }
    }

    /** String list arg: a JSON array of strings, or a single string treated as one element. */
    fun strList(args: JSONObject, key: String): List<String>? {
        if (!args.has(key) || args.isNull(key)) return null
        return when (val v = args.get(key)) {
            is JSONArray -> (0 until v.length()).mapNotNull { i -> v.optString(i).trim().takeIf { s -> s.isNotEmpty() } }
                .takeIf { it.isNotEmpty() }
            is String -> v.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) }
            else -> null
        }
    }

    /**
     * STRICT integer: a string must parse with [String.toIntOrNull], so "12.5"
     * is absent. This is the virtual-screen `coordinate`/`display_id` dialect —
     * [T-ui-action-arg-quoting] precedent: coordinates are model-supplied, so
     * accept a plain integer only, anything else is refused before it can
     * reach the manager.
     */
    fun strictInt(args: JSONObject, key: String): Int? {
        if (!args.has(key) || args.isNull(key)) return null
        return when (val v = args.get(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }
}
