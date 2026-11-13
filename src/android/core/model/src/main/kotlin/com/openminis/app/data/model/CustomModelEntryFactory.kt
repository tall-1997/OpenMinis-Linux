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
 * .ModelsDevApi]::enrichModelNow, while tests inject a plain passthrough. The
 * enriched result — not the raw input — is what gets persisted.
 *
 * [T-custom-model-enrich-wiring] NO unknown-model stamp is persisted: the
 * baseModel carries catalog truth (nulls included). The read path
 * ([ModelEntry.model] → [applyUnrecognizedModelDefaults]) stamps at read time,
 * so the picker/wire/UI still see completed params — but a persisted guess
 * can no longer mask later catalog data (enrichWithDevData prefers a
 * non-null model field over the dev row) or present itself as fact in the
 * params page.
 */
fun buildCustomModelEntry(
    modelId: String,
    displayName: String,
    providerInstanceId: String,
    providerTypeDisplayName: String,
    enrich: (LLMModel) -> LLMModel,
): ModelEntry {
    val name = displayName.ifBlank { modelId }
    val model = enrich(
        LLMModel(
            id = modelId.trim(),
            displayName = name.trim(),
            provider = providerTypeDisplayName,
        ),
    )
    return ModelEntry(
        providerInstanceId = providerInstanceId,
        baseModel = model,
        isCustom = true,
    )
}
