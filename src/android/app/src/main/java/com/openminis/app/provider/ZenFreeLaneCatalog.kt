package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel

/**
 * What probing and real usage taught us about ids that the naming rule cannot
 * decide. [free] means the lane answered without a credential; [paid] means it
 * demanded one, so the id is never shown and never re-probed.
 */
internal data class ZenLearnedFree(
    val free: Set<String> = emptySet(),
    val paid: Set<String> = emptySet(),
) {
    fun withFree(id: String) = copy(free = free + id, paid = paid - id)
    fun withPaid(id: String) = copy(paid = paid + id, free = free - id)
}

/**
 * The free-lane catalogue as a RULE plus learned evidence — never a list.
 *
 * The retired build shipped eight hardcoded ids; five of them
 * (`space-bunny-free`, `nemotron-3-ultra-free`, `ling-3.1-flash-free`,
 * `longcat-2.5-preview-free`, `fledge-alpha-free`) are gone upstream, so every
 * device that kept them showed dead rows and every model added since was
 * invisible. The live `/models` endpoint answers anonymously with ~85 entries,
 * which is the only catalogue that stays true.
 *
 * Membership therefore comes from three places, in this order:
 * 1. the naming rule — a `-free` suffix, case-insensitive;
 * 2. `big-pickle`, the one known id that carries no suffix;
 * 3. [ZenLearnedFree.free], ids a probe saw answered without a credential.
 *
 * [ZenLearnedFree.paid] always wins over the other three, and anything
 * [ZenFreeLaneHealth] recorded as genuinely gone is dropped last.
 *
 * [ZenFreeLaneCatalogTest] walks the production sources and fails if any other
 * `-free` literal shows up, so the list cannot creep back in.
 */
internal object ZenFreeLaneCatalog {
    private const val BIG_PICKLE = "big-pickle"
    private const val FREE_SUFFIX = "-free"

    fun isFreeLaneModelId(id: String): Boolean =
        id.equals(BIG_PICKLE, ignoreCase = true) || id.endsWith(FREE_SUFFIX, ignoreCase = true)

    /** What the user may pick: rule-shaped or proven free, never paid, never dead. */
    fun filterFree(models: List<LLMModel>, learned: ZenLearnedFree): List<LLMModel> =
        models.filter { model ->
            model.id !in learned.paid &&
                (isFreeLaneModelId(model.id) || model.id in learned.free) &&
                !ZenFreeLaneHealth.isDead(model.id)
        }

    /**
     * Ids worth one lightweight probe. Excludes everything already decided and
     * everything the rule already settles, so this stays incremental — the
     * catalogue is ~85 entries and re-probing all of them on every refresh
     * would be both slow and rude.
     */
    fun needsProbe(models: List<LLMModel>, learned: ZenLearnedFree): List<String> =
        models.asSequence()
            .map { it.id }
            .distinct()
            .filter { id ->
                id !in learned.free && id !in learned.paid && !isFreeLaneModelId(id)
            }
            .toList()
}
