package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelParamPriority
import com.openminis.app.provider.ModelsDevApi.ModelDevEntry

/**
 * 用 models.dev 目录数据增强模型参数。
 * 端点 /v1/models 返回值优先于目录；目录只填空缺。
 */
fun enrichWithDevData(model: LLMModel, devModel: ModelDevEntry): LLMModel {
    val (ctx, out) = ModelParamPriority.resolve(
        model.contextWindow, model.maxOutputTokens,
        devModel.contextWindow, devModel.maxOutputTokens,
    )
    return model.copy(
        contextWindow = ctx, maxOutputTokens = out,
        supportsReasoning = devModel.reasoning ?: model.supportsReasoning,
        interleavedReasoningField = devModel.interleavedField ?: model.interleavedReasoningField,
        inputModalities = devModel.inputModalities ?: model.inputModalities,
        outputModalities = devModel.outputModalities ?: model.outputModalities,
        reasoningEffortValues = devModel.reasoningEffortValues ?: model.reasoningEffortValues,
        // [OpenMinis#163] Only carry the AFFIRMATIVE answer forward, so
        // enriching against an entry the catalog is silent about cannot
        // overwrite a prior real answer with a meaningless `false`.
        declaresNoEffortTiers = if (devModel.declaresNoEffortTiers) true else model.declaresNoEffortTiers,
        budgetTokensMin = devModel.budgetTokensMin ?: model.budgetTokensMin,
        budgetTokensMax = devModel.budgetTokensMax ?: model.budgetTokensMax,
    )
}