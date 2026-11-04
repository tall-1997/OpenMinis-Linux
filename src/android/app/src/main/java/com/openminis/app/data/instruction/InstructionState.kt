package com.openminis.app.data.instruction

/**
 * Pure functions for instruction snapshots - the P1 half of the memory-system
 * rebuild. Mirrors the observe/apply/commit contract of opencode's
 * `instruction-state.ts` (evidence: /var/minis/shared/memory-research/opencode
 * instruction-state.ts) without its session-event bus.
 *
 * ## Contract
 * - Instructions are modeled as a `Map<String, String>` of stable keys
 *   ("goal", "app-global", "session-global") to their current text values.
 * - [observe] diffs an incoming read of the instruction sources against the
 *   frozen row in `instruction_states` and produces an [Observation] with an
 *   initial flag and a [InstructionDelta].
 * - A [InstructionDelta] maps a key to its NEW value, or to null when the key
 *   disappeared from the sources (goal cleared, GLOBAL.md emptied).
 * - [applyDelta] folds a delta onto a previous values map - the deterministic
 *   inverse of [observe], so snapshots can be replayed.
 * - [renderUpdate]/[renderInitial] produce the frozen, human-auditable text
 *   committed into the append-only `instruction_updates` event row.
 * - The empty-delta case on a non-initial state is a no-op: nothing is frozen
 *   when nothing changed.
 *
 * ## Deliberately deferred (anchor discipline, see report v4)
 * - epoch advancement ([advanceEpoch]) belongs to P2 (compaction elasticity).
 * - rewinding event streams ([rewind]) belongs to P9 (point-in-time revert).
 * - volatile runtime progress (goal tokensUsed / elapsedMs) is intentionally
 *   NOT part of instruction values: it changes every turn and would flood the
 *   event stream. Injection keeps reading the live entity for those fields.
 */
object InstructionState {

    const val KEY_GOAL = "goal"
    const val KEY_APP_GLOBAL = "app-global"
    const val KEY_SESSION_GLOBAL = "session-global"

    /**
     * [T-memory-project-rules] Project-scoped rules (`AGENTS.md` /
     * `AGENTS.override.md` in the project workspace). Third instruction layer,
     * between the app-wide file and the session file.
     */
    const val KEY_PROJECT_RULES = "project-rules"

    /** Longest single frozen value we commit verbatim (guards event-row size). */
    const val RENDER_VALUE_LIMIT = 240

    /** Keys an instruction-value map may contain; anything else is dropped. */
    val KNOWN_KEYS = setOf(KEY_GOAL, KEY_APP_GLOBAL, KEY_SESSION_GLOBAL, KEY_PROJECT_RULES)

    data class InstructionDelta(
        val changes: Map<String, String?>,
    ) {
        val isEmpty: Boolean get() = changes.isEmpty()

        companion object {
            val EMPTY = InstructionDelta(emptyMap())
        }
    }

    data class Observation(
        val initial: Boolean,
        val previousValues: Map<String, String>,
        val currentValues: Map<String, String>,
        val delta: InstructionDelta,
    )

    /**
     * Diffs [current] (a fresh read of the instruction sources) against
     * [storedValues] (the frozen `current_values` of the state row, or null
     * when no snapshot exists yet). Deterministic: both maps are compared by
     * sorted key order, so a changed ordering never produces a spurious delta.
     */
    fun observe(storedValues: Map<String, String>?, current: Map<String, String>): Observation {
        val sanitized = sanitizeValues(current)
        if (storedValues == null) {
            return Observation(
                initial = true,
                previousValues = emptyMap(),
                currentValues = sanitized,
                delta = InstructionDelta(sanitized),
            )
        }
        val previous = sanitizeValues(storedValues)
        val changes = linkedMapOf<String, String?>()
        for (key in KNOWN_KEYS) {
            val had = previous.containsKey(key)
            val has = sanitized.containsKey(key)
            when {
                has && (!had || previous[key] != sanitized[key]) -> changes[key] = sanitized[key]
                had && !has -> changes[key] = null
            }
        }
        return Observation(
            initial = false,
            previousValues = previous,
            currentValues = sanitized,
            delta = InstructionDelta(changes),
        )
    }

    /** Folds [delta] onto [values]; null delta entries remove the key. */
    fun applyDelta(values: Map<String, String>, delta: InstructionDelta): Map<String, String> {
        val next = sanitizeValues(values).toMutableMap()
        for ((key, value) in delta.changes) {
            if (value == null) next.remove(key) else next[key] = value
        }
        return sanitizeValues(next)
    }

    /**
     * Line-diff style frozen text for an update event. Every changed key gets
     * a `- key: previous` / `+ key: current` pair; removals get a lone `- key`.
     * Values are truncated to [RENDER_VALUE_LIMIT] (marker `[truncated]`) so
     * event rows stay bounded while the full value lives in delta JSON.
     */
    fun renderUpdate(
        previous: Map<String, String>,
        current: Map<String, String>,
        delta: InstructionDelta,
    ): String {
        val lines = ArrayList<String>(delta.changes.size * 2)
        for ((key, newValue) in delta.changes) {
            if (newValue == null) {
                lines += "- $key"
            } else {
                if (previous.containsKey(key)) lines += "- $key: ${clip(previous[key].orEmpty())}"
                lines += "+ $key: ${clip(newValue)}"
            }
        }
        return lines.joinToString("\n")
    }

    /** Frozen baseline text for the initial event of a session snapshot. */
    fun renderInitial(current: Map<String, String>): String {
        val lines = current.toSortedMap().map { (key, value) -> "+ $key: ${clip(value)}" }
        return lines.joinToString("\n")
    }

    /**
     * Serializes values to a single-line JSON object; keys sorted, values
     * escaped. Pure serialization only: no semantic key filtering, so nested
     * JSON values (e.g. the goal instruction value is itself a JSON object)
     * round-trip intact through [parseValues]. Filtering is the callers'
     * concern ([observe] / [applyDelta] sanitize at their entry points).
     */
    fun valuesJson(values: Map<String, String>): String {
        val entries = values.toSortedMap().entries.joinToString(",") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
        return "{$entries}"
    }

    /**
     * Serializes a delta to JSON; null entries (removed keys) serialize as
     * JSON null literals. [parseDelta] is the inverse.
     */
    fun deltaJson(delta: InstructionDelta): String {
        val entries = delta.changes.toSortedMap().entries.joinToString(",") { (k, v) ->
            if (v == null) "\"${escapeJson(k)}\":null" else "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
        return "{$entries}"
    }

    /** Inverse of [deltaJson]. */
    fun parseDelta(json: String): InstructionDelta {
        val parsed = parseNullableValues(json) ?: return InstructionDelta.EMPTY
        return InstructionDelta(parsed)
    }

    /**
     * Shared parser that admits JSON null literal values.
     * Pure JSON decoding only: no key filtering here. Nested values (e.g. the
     * goal instruction value is itself a JSON object) must round-trip intact;
     * semantic key filtering belongs to [sanitizeValues], which sits on the
     * observe/applyDelta paths.
     */
    private fun parseNullableValues(json: String): Map<String, String?>? {
        val result = linkedMapOf<String, String?>()
        var i = 0
        fun skipWs() { while (i < json.length && json[i].isWhitespace()) i++ }
        fun readString(): String? {
            skipWs()
            if (i >= json.length || json[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < json.length) {
                when (val c = json[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        if (i + 1 >= json.length) return null
                        when (val e = json[i + 1]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 5 >= json.length) return null
                                val hex = json.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                        i++
                    }
                    else -> sb.append(c)
                }
                i++
            }
            return null
        }
        fun readValue(): Pair<Boolean, String?> {
            skipWs()
            if (i < json.length && json[i] == 'n') {
                if (json.startsWith("null", i)) {
                    i += 4
                    return true to null
                }
                return false to null
            }
            val s = readString() ?: return false to null
            return true to s
        }
        skipWs()
        if (i >= json.length || json[i] != '{') return null
        i++
        skipWs()
        if (i < json.length && json[i] == '}') return result
        while (true) {
            skipWs()
            val key = readString() ?: return null
            skipWs()
            if (i >= json.length || json[i] != ':') return null
            i++
            val (ok, value) = readValue()
            if (!ok) return null
            result[key] = value
            skipWs()
            if (i >= json.length) return null
            when (json[i]) {
                ',' -> { i++; continue }
                '}' -> return result
                else -> return null
            }
        }
    }

    /**
     * Inverse of [valuesJson]; tolerates trailing whitespace. Returns empty on
     * parse failure. Pure decoding: unknown keys are preserved, so nested JSON
     * values round-trip intact. Callers that need semantic filtering apply
     * [sanitizeValues].
     */
    fun parseValues(json: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var i = 0
        fun skipWs() { while (i < json.length && json[i].isWhitespace()) i++ }
        fun fail(): Map<String, String> = emptyMap()
        fun readString(): String? {
            skipWs()
            if (i >= json.length || json[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < json.length) {
                when (val c = json[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        if (i + 1 >= json.length) return null
                        when (val e = json[i + 1]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 5 >= json.length) return null
                                val hex = json.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                        i++
                    }
                    else -> sb.append(c)
                }
                i++
            }
            return null
        }
        skipWs()
        if (i >= json.length || json[i] != '{') return fail()
        i++
        skipWs()
        if (i < json.length && json[i] == '}') return result
        while (true) {
            skipWs()
            val key = readString() ?: return fail()
            skipWs()
            if (i >= json.length || json[i] != ':') return fail()
            i++
            val value = readString() ?: return fail()
            result[key] = value
            skipWs()
            if (i >= json.length) return fail()
            when (json[i]) {
                ',' -> { i++; continue }
                '}' -> return result
                else -> return fail()
            }
        }
    }

    fun escapeJson(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    fun clip(value: String): String =
        if (value.length <= RENDER_VALUE_LIMIT) value
        else value.take(RENDER_VALUE_LIMIT) + "[truncated]"

    /** Drops unknown keys and keys with empty values (absence = removal semantics). */
    fun sanitizeValues(values: Map<String, String>): Map<String, String> =
        values.filterKeys { it in KNOWN_KEYS }.filterValues { it.isNotEmpty() }
}
