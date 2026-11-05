package com.openminis.app.ui.chat

import com.openminis.app.tools.AgentReportStore

/**
 * [T-work-report-ui] What the conversation should say about the run's state.
 *
 * The stage already exists as data ([AgentReportStore.Stage]) and is injected
 * into every request, but nothing showed it to the person. A run that stopped to
 * ask a question therefore looked exactly like a run that was still thinking.
 *
 * This is the decision half only: given a stage and the report behind it, which
 * banner (if any) belongs on screen and what line of detail does it carry. Pure,
 * so the rules are testable without a Compose host, and the UI layer stays a thin
 * renderer.
 */
internal object WorkStageBannerModel {

    enum class Kind {
        /** The run cannot continue until the person answers [Model.detail]. */
        ANSWER,

        /** There is a finished result waiting to be looked at. */
        REVIEW,

        /** The run is stuck on something concrete the person must clear. */
        BLOCKED,
    }

    data class Model(val kind: Kind, val detail: String)

    /** Longer detail is clipped: a banner is one line, not a report. */
    const val MAX_DETAIL_CHARS = 160

    /**
     * Null for every stage that needs nothing from the person: working, waiting
     * for new input, or idle after a dropped report.
     */
    fun of(
        stage: AgentReportStore.Stage,
        summary: String,
        question: String,
        blocker: String,
    ): Model? {
        val model = when (stage) {
            AgentReportStore.Stage.NEEDS_ANSWER ->
                Model(Kind.ANSWER, question.ifBlank { summary })
            AgentReportStore.Stage.NEEDS_REVIEW ->
                Model(Kind.REVIEW, summary)
            AgentReportStore.Stage.BLOCKED, AgentReportStore.Stage.FAILED ->
                Model(Kind.BLOCKED, blocker.ifBlank { summary })
            else -> null
        } ?: return null
        val detail = model.detail.replace('\n', ' ').trim().take(MAX_DETAIL_CHARS)
        if (detail.isBlank()) return null
        return Model(model.kind, detail)
    }
}
