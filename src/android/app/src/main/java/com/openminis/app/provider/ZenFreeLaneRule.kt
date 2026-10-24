package com.openminis.app.provider

/**
 * The Zen free-lane membership RULE — never a list.
 *
 * The retired build shipped eight hardcoded ids; five of them
 * (`space-bunny-free`, `nemotron-3-ultra-free`, `ling-3.1-flash-free`,
 * `longcat-2.5-preview-free`, `fledge-alpha-free`) are gone upstream, so every
 * device that kept them showed dead rows and every model added since was
 * invisible. The live `/models` endpoint answers anonymously with ~85 entries,
 * which is the only catalogue that stays true.
 *
 * Zen's listing carries no free-tier field, so membership is inferred from the
 * id shape. On 2026-10-24 the rule matched 9 of the 9 ids Zen advertised as
 * free (`big-pickle`, `beam-free`, `flint-40b-free`, `mimo-v2.6-flash-free`,
 * `nemotron-3.5-super-vl-free`, `nemotron-3.5-lightning-free`, `jev-1.13-free`,
 * `deepseek-v4.1-flash-free`, `glm-5.3-flash-free`), which is why the rule is
 * kept rather than replaced: it is the authority that says "this id is ours to
 * offer", and [FreeLaneDirectory] refuses to persist a contradictory probe
 * verdict against it.
 *
 * Ids the rule cannot decide are not lost — [FreeLaneDirectory] probes them and
 * a clean answer adds them via [FreeLaneLearned.free]. Zen is the one lane where
 * that is allowed, precisely because its listing makes no declaration.
 *
 * [ZenFreeLaneRuleTest] walks the production sources and fails if any other
 * `-free` literal shows up, so the list cannot creep back in.
 */
internal object ZenFreeLaneRule {
    private const val BIG_PICKLE = "big-pickle"
    private const val FREE_SUFFIX = "-free"

    fun isFreeLaneModelId(id: String): Boolean =
        id.equals(BIG_PICKLE, ignoreCase = true) || id.endsWith(FREE_SUFFIX, ignoreCase = true)
}
