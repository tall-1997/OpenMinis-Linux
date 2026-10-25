package com.openminis.app.evolution

/**
 * P1 evolution types. Native Kotlin — not a vendored metano/LAAP daemon.
 * Proposals never write SOUL.md or GLOBAL.md.
 */
data class EvolutionProposal(
    val id: String,
    val type: Type,
    val status: Status,
    val beliefId: String?,
    val title: String,
    val draftText: String,
    val evidence: String,
    val skillId: String?,
    val rollbackText: String?,
    val createdAt: Long,
    val decidedAt: Long?,
    val scene: SceneTag = SceneTag.GENERAL,
) {
    enum class Type(val raw: String) {
        LEARNED_RULE("learned_rule"),
        SKILL_PATCH("skill_patch"),
        RETRACT("retract");

        companion object {
            fun from(raw: String): Type =
                entries.find { it.raw == raw } ?: LEARNED_RULE
        }
    }

    enum class Status(val raw: String) {
        PENDING("pending"),
        ACCEPTED("accepted"),
        REJECTED("rejected"),
        DEFERRED("deferred"),
        ROLLED_BACK("rolled_back");

        companion object {
            fun from(raw: String): Status =
                entries.find { it.raw == raw } ?: PENDING
        }
    }
}

data class EvolutionBelief(
    val id: String,
    val fingerprint: String,
    val kind: String,
    val summary: String,
    val hitCount: Int,
    val status: String,
    val updatedAt: Long,
    val scene: SceneTag = SceneTag.GENERAL,
    val lastHitAt: Long = updatedAt,
)
