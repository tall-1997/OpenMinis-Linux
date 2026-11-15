package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Behavioral hints for one tool — the port of pi coding-agent
 * `extensions/types.ts` ToolAnnotations (readOnlyHint / destructiveHint /
 * idempotentHint / openWorldHint, types.ts:543-552). Every field is nullable
 * Boolean: `true`/`false` are explicit claims, `null` means "unknown" and is
 * treated as unknown by consumers (never as an implicit false).
 *
 * Consumers: the approval layer can phrase confirmations from
 * [AgentToolDefinition.annotations] (destructiveHint), and prompt builders
 * may soften/harden wording from readOnlyHint. The field set is metadata
 * only — it never reaches the provider wire format (see to*Json below),
 * so adding it is wire-compatible.
 */
data class ToolAnnotations(
    /** True: the tool does not mutate its environment. */
    val readOnlyHint: Boolean? = null,
    /** True: the tool may delete or irreversibly change data. */
    val destructiveHint: Boolean? = null,
    /** True: repeating the call with the same arguments has no extra effect. */
    val idempotentHint: Boolean? = null,
    /** True: the tool interacts with an unbounded outside world (web, carrier, external apps). */
    val openWorldHint: Boolean? = null,
)

/**
 * Provider-agnostic tool definition. Each tool registers with this structure,
 * and providers convert it to their native format (Anthropic input_schema,
 * Gemini function_declarations, OpenAI function calling).
 *
 * Metadata fields (exposure / annotations / promptSnippet) are nullable with
 * default values so every existing constructor call and any future
 * serialized form stays backward-compatible: instances built before these
 * fields existed deserialize conceptually as exposure=null ("direct"),
 * annotations=null, promptSnippet=null. None of the fields are persisted in
 * Room and none are emitted to provider JSON — they are runtime metadata for
 * the registry, the prompt assembler, and the approval layer.
 */
data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParam>,
    val required: List<String> = emptyList(),
    val propertyOrdering: List<String>? = null,
    /**
     * Exposure level (pi extensions/types.ts:524-570, reduced to the levels
     * this registry needs). Null means "direct": present in the schema and
     * user-visible. Other planned values: "model_only", "deferred"
     * (find_tools on-demand), "hidden" (dispatchable, never advertised).
     * Phase D (codemode/deferred loading) will consume this; today it is
     * pure data so new tools can already declare their intent.
     */
    val exposure: String? = null,
    /** Behavioral hints for the approval/prompt layers; null = unknown. */
    val annotations: ToolAnnotations? = null,
    /**
     * Optional per-tool guidance bullet for the system prompt's
     * "Available tools" section. When set, the prompt assembler can read it
     * straight from the definition; ToolPromptFragments stays as the
     * compatibility fallback for tools that still register there.
     */
    val promptSnippet: String? = null,
) {
    /** Anthropic format: {name, description, input_schema: {type:object, properties, required}} */
    fun toAnthropicJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("input_schema", schema)
        }
    }

    /** Gemini format: {name, description, parameters: {type:OBJECT, properties, required}} */
    fun toGeminiJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toGeminiJson())
        }
        val params = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
            if (propertyOrdering != null) put("propertyOrdering", JSONArray(propertyOrdering))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    /** OpenAI format: {type:function, function: {name, description, parameters: {type:object, ...}}} */
    fun toOpenAIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", params)
            })
        }
    }

    /** True only when annotations explicitly claim the tool is destructive. */
    fun isDestructive(): Boolean = annotations?.destructiveHint == true

    /** True only when annotations explicitly claim the tool is read-only. */
    fun isReadOnly(): Boolean = annotations?.readOnlyHint == true
}

data class AgentToolParam(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null,
    val items: AgentToolParam? = null,
    val properties: Map<String, AgentToolParam>? = null,
    val required: List<String>? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("description", description)
        if (enumValues != null) put("enum", JSONArray(enumValues))
        if (items != null) put("items", items.toJson())
        writeObjectFields(this, gemini = false)
    }

    fun toGeminiJson(): JSONObject = JSONObject().apply {
        put("type", type.uppercase())
        put("description", description)
        if (enumValues != null) put("enum", JSONArray(enumValues))
        if (items != null) put("items", items.toGeminiJson())
        writeObjectFields(this, gemini = true)
    }

    private fun writeObjectFields(target: JSONObject, gemini: Boolean) {
        if (properties != null) {
            val props = JSONObject()
            for ((key, param) in properties) {
                props.put(key, if (gemini) param.toGeminiJson() else param.toJson())
            }
            target.put("properties", props)
        }
        if (!required.isNullOrEmpty()) {
            target.put("required", JSONArray(required))
        }
    }
}
