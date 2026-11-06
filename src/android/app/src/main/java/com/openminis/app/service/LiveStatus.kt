package com.openminis.app.service

/**
 * [T-live-status-model] The single semantic description of what the status
 * surface is doing, shared by both islands this app speaks.
 *
 * Two surfaces render it — AOSP Android 16 Live Updates (promoted
 * ProgressStyle chip) and Xiaomi HyperOS 小米超级岛 (`miui.focus.param`) — and
 * they used to derive their strings independently: the AOSP chip was the
 * hardcoded literal "Minis", while the HyperOS big island was handed the long
 * shade-row title ("Minis is using Shell"), which the 模板库 explicitly says
 * degrades to a smaller font and then clips. One model keeps them honest and
 * puts the platform caps in one place.
 *
 * Pure on purpose: every rule below is unit-tested, and nothing here reads
 * Android resources.
 */
internal data class LiveStatus(
    val kind: Kind,
    /** Localized app name — the neutral subject when no tool is running. */
    val appLabel: String,
    /**
     * Localized short tool label ("Shell", "Browser"). Null when the turn is
     * not inside a tool call, or on a surface that strips tool identity.
     */
    val toolShort: String?,
    /** Localized "3 sessions running" line. */
    val sessionLabel: String,
    /** The shorter status word used where width is scarce. */
    val subtitle: String,
) {

    enum class Kind {
        /** A session is open but nothing is executing yet. */
        WAITING,

        /** Work is executing right now: the run clock counts up. */
        RUNNING,

        /** The run finished and the row is resting. */
        DONE,
    }

    val busy: Boolean get() = kind == Kind.RUNNING
    val ending: Boolean get() = kind == Kind.DONE

    /**
     * The big-island headline: the shortest true word.
     *
     * HyperOS renders this as the large word inside the island, and the
     * template guide caps it (≥4 CJK chars renders at a smaller size, then
     * clips). A tool turn therefore names the tool, not the sentence around it.
     */
    fun islandHeadline(): String = when {
        // The app name is identity, not decoration: truncating it to the tool
        // budget turned "Minis Ultra" into "Minis U…" on every idle island.
        // Only the tool label is capped here.
        ending -> appLabel
        toolShort != null -> cap(toolShort, HEAD_MAX)
        else -> appLabel
    }

    /** The trailing small word in the big island. */
    fun islandCaption(): String = cap(subtitle, CAPTION_MAX)

    /** Status-bar ticker; AOD title. Never the long shade title. */
    fun ticker(): String = appLabel

    /**
     * Words for the AOSP chip (`setShortCriticalText`), or null to leave the
     * chip to the system.
     *
     * Null while the run clock counts up: the chip already shows that clock,
     * and a word beside it is what pushed the text over the edge in the
     * seven-ish characters Android renders. A finished run keeps its app name
     * so the chip does not read like a permanent timer.
     */
    fun chipWords(): String? = when {
        busy -> null
        else -> cap(islandHeadline(), CHIP_MAX)
    }

    /** Shade-row title: the long form is fine here, the row wraps. */
    fun rowTitle(completedLabel: String, toolLabel: String?): String = when {
        ending -> completedLabel
        toolLabel != null -> toolLabel
        else -> subtitle
    }

    private fun cap(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    companion object {
        /** Android shows a chip's text whole up to about seven characters. */
        const val CHIP_MAX = 12

        /** Big-island headline budget; the 模板库's "≤4 CJK" in latin width. */
        const val HEAD_MAX = 8

        /** Trailing small word in the big island. */
        const val CAPTION_MAX = 16

        /** [ForegroundNotificationState] → the semantic model both surfaces read. */
        fun from(
            state: ForegroundNotificationState,
            appLabel: String,
            toolShort: String?,
            sessionLabel: String,
            subtitle: String,
        ): LiveStatus = LiveStatus(
            kind = when {
                state.completed -> Kind.DONE
                state.isToolRunning -> Kind.RUNNING
                else -> Kind.WAITING
            },
            appLabel = appLabel,
            // PROMOTED deliberately strips tool identity (its comment: the
            // surface shows a run, not a tool), and a finished run names the app.
            toolShort = toolShort.takeIf {
                it != null && state.surface != NotificationSurface.OEM_QUIET &&
                    state.isToolRunning && !state.completed
            },
            sessionLabel = sessionLabel,
            subtitle = subtitle,
        )
    }
}
