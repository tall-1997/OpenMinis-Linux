package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.harness.validation.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-schema-validation-wiring] 把 harness 的 [ToolSchemaValidator] 接进工具派发
 * 入口的解析器与判定函数。
 *
 * schema 单一来源 = [AgentToolDefinition.toAnthropicJson] 的 `input_schema`——
 * 与发给 provider 的是同一份字节形状，校验面和模型可见面不会漂移。取**最大
 * 工具全集**（所有开关打开）做解析域：校验关心的是参数形状，不是本会话是否
 * 启用该工具；未启用的工具在派发分支里自有其错误路径。
 *
 * MCP 工具（`mcp__*`）暂返回 null 跳过校验：客户端工具模型没有可直接解析的
 * parametersJson（服务端 McpToolDispatcher 的 inputSchema 是出向形状），硬凑
 * 会把「校验」变成「猜」。validator 对 null schema 宽容跳过，行为不退化。
 *
 * 判定语义：解析 argsJson 失败 → 返回空（各工具执行器自有 JSON 错误路径）；
 * 问题非空 → 调用方把问题列表写回 ToolResult 让模型自我纠正，**不执行**。
 */
object ToolSchemaResolver {

    private val json = Json { ignoreUnknownKeys = true }

    /** 工具名 → 参数 schema（input_schema 形状）。懒建一次，进程内不变。 */
    val schemas: Map<String, JsonObject> by lazy {
        AgentTools.makeAgentTools(
            visionGroupConfigured = true,
            memoryEnabled = true,
            subAgentEnabled = true,
            codeGraphEnabled = true,
            mcpNativeEnabled = true,
            includeLongTail = true,
        ).associate { def -> def.name to def.inputSchema() }
    }

    private val resolver = ToolSchemaValidator.SchemaResolver { name -> schemas[name] }

    /** 派发入口用的判定：空列表 = 放行（含「无 schema / args 不是合法 JSON」）。 */
    fun problemsFor(canonicalName: String, argsJson: String): List<String> {
        val args = runCatching { json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
            ?: return emptyList()
        return ToolSchemaValidator.problemsFor(canonicalName, args, resolver)
    }

    private fun AgentToolDefinition.inputSchema(): JsonObject =
        toAnthropicJson().getJSONObject("input_schema").toKotlinxObject()

    // ─── org.json → kotlinx.serialization.json ─────────────────────────

    internal fun Any?.toKotlinxElement(): JsonElement = when (this) {
        null, JSONObject.NULL -> JsonNull
        is JSONObject -> toKotlinxObject()
        is JSONArray -> JsonArray((0 until length()).map { i -> get(i).toKotlinxElement() })
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    internal fun JSONObject.toKotlinxObject(): JsonObject =
        JsonObject(keys().asSequence().associateWith { key -> opt(key).toKotlinxElement() })
}
