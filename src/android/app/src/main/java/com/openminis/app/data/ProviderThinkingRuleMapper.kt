package com.openminis.app.data

import com.openminis.app.data.db.ProviderThinkingRuleEntity
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingRuleCoding

fun ThinkingRule.toEntity(
    id: String,
    instanceId: String,
    sortOrder: Int,
): ProviderThinkingRuleEntity {
    val (kind, pattern) = when (val s = scope) {
        is ThinkingRule.Scope.AllModels -> "allModels" to null
        is ThinkingRule.Scope.ModelPattern -> "modelPattern" to s.pattern
    }
    return ProviderThinkingRuleEntity(
        id = id,
        providerInstanceId = instanceId,
        label = label,
        scopeKind = kind,
        scopePattern = pattern,
        wireFormatJson = ThinkingRuleCoding.encodeWireFormat(wireFormat),
        reasoningEchoJson = ThinkingRuleCoding.encodeEcho(reasoningEcho),
        sortOrder = sortOrder,
    )
}

fun ProviderThinkingRuleEntity.toRule(): ThinkingRule {
    val scope = when (scopeKind) {
        "modelPattern" -> ThinkingRule.Scope.ModelPattern(scopePattern ?: "*")
        else -> ThinkingRule.Scope.AllModels
    }
    return ThinkingRule(
        kind = ThinkingRule.Kind.CUSTOM,
        scope = scope,
        wireFormat = ThinkingRuleCoding.decodeWireFormat(wireFormatJson),
        reasoningEcho = ThinkingRuleCoding.decodeEcho(reasoningEchoJson),
        label = label,
    )
}
