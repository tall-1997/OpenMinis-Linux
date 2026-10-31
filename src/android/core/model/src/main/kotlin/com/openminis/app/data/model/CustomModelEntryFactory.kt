package com.openminis.app.data.model

/**
 * [T-custom-model-enrich-wiring] Shared factory for the Add Custom Model
 * screen's save action.
 *
 * The screen used to build the [LLMModel] inline and stamp it with
 * [applyUnrecognizedModelDefaults] directly, skipping the models.dev →
 * DataLearner enrich chain entirely. Catalog-exact ids like `kimi-k3`
 * therefore landed with the unknown-model stamp (256k ctx / 128k out)
 * even though the catalog declares 1048576 / 131072, and every save path
 * that wanted the enrich chain had to remember the order by hand.
 *
 * The enrich step is injected: the screen passes [com.openminis.app.provider
 * .ModelsDevApi]::enrichModel, while tests inject a plain passthrough. The
 * enriched result — not the raw input — is what gets persisted.
 */
fun buildCustomModelEntry(
    modelId: String,
    displayName: String,
    providerInstanceId: String,
    providerTypeDisplayName: String,
    enrich: (LLMModel) -> LLMModel,
): ModelEntry {
    val name = displayName.ifBlank { modelId }
    // [T-custom-model-enrich-wiring] enrich first (catalog-exact ids win), then
    // stamp the unknown-model defaults: the stamp only fills values the enrich
    // chain left empty, so a models.dev hit keeps its catalog numbers while an
    // unrecognized model keeps the historical 256k/128k unknown defaults.
    val model = applyUnrecognizedModelDefaults(
        enrich(
            LLMModel(
                id = modelId.trim(),
                displayName = name.trim(),
                provider = providerTypeDisplayName,
            ),
        ),
    )
    return ModelEntry(
        providerInstanceId = providerInstanceId,
        baseModel = model,
        isCustom = true,
    )
}
