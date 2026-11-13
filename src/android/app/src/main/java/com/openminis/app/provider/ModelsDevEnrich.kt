package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel

/**
 * [T-custom-model-enrich-wiring] The models.dev → DataLearner enrich chain,
 * extracted from ModelsDevApi (architecture-policy line ceiling).
 *
 * NO unknown-model stamp on any of these: the persisted baseModel carries
 * catalog TRUTH (nulls included) — a stamped one masks later catalog data
 * and presents the guess as fact. The read path (ModelEntry.model →
 * applyUnrecognizedModelDefaults) stamps at read time, so runtime behavior
 * is unchanged while persisted data stays honest.
 */

/** Cache-only enrich: called from Compose / the send path where a
 *  synchronous HTML scrape would ANR. Misses are filled in the background;
 *  the next enrich sees the overlay. */
fun ModelsDevApi.enrichModel(model: LLMModel): LLMModel {
    val fromDev = applyDevMatch(model)
    val supplemented = DataLearnerApi.supplement(fromDev, fetchIfMissing = false)
    DataLearnerApi.scheduleSupplement(supplemented)
    return supplemented
}

/** Save-path variant: fetch the DataLearner overlay SYNCHRONOUSLY
 *  (fetchIfMissing = true) so a first-time custom model — whose cache is
 *  necessarily cold — persists the supplemented numbers instead of freezing
 *  the un-supplemented ones. Background dispatcher only; the screen wraps it
 *  in withTimeout. */
fun ModelsDevApi.enrichModelNow(model: LLMModel): LLMModel {
    val fromDev = applyDevMatch(model)
    val supplemented = DataLearnerApi.supplement(fromDev, fetchIfMissing = true)
    DataLearnerApi.scheduleSupplement(supplemented)
    return supplemented
}

/** Bulk enrich for the /v1/models refresh path; same no-stamp rule. */
fun ModelsDevApi.enrichModels(models: List<LLMModel>): List<LLMModel> {
    val fromDev = models.map { applyDevMatch(it) }
    return DataLearnerApi.supplementAll(fromDev)
}
