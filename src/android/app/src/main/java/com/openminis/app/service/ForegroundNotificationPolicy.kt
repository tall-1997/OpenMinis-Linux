package com.openminis.app.service

internal enum class NotificationSurface {
    /** AOSP SystemUI. Tool identity is stripped; run boundaries still publish. */
    PROMOTED,
    /**
     * [T-android-hyperos-island] Xiaomi HyperOS speaking the first-party
     * `miui.focus.param` island protocol. The shade row keeps OEM_QUIET
     * discipline (no chronometer, no progress, static text) — the island
     * itself renders from the JSON extras, and updates flow through the
     * official same-id + `updatable=true` path, so unlike [PROMOTED] the
     * tool name is *kept*: it is the island's big-island text, and the
     * policy's 20s cosmetic gap already throttles multi-tool churn.
     */
    HYPER_OS_FOCUS,
    /**
     * Forked SystemUI. One static row per run. A chronometer, progress bar,
     * or promoted template is what their island re-inflates until SystemUI dies.
     */
    OEM_QUIET,
    PLAIN,
}

/** The immutable, *visible* notification state. No streaming text or wall-clock ticks. */
internal data class ForegroundNotificationState(
    val activeCount: Int,
    val toolName: String?,
    val isToolRunning: Boolean,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val promoted: Boolean,
    val subtitle: String,
    val surface: NotificationSurface = NotificationSurface.PLAIN,
) {
    val completed: Boolean get() = activeCount == 0 && finishedAtMs != null

    /**
     * Collapse fields that must not cause a SystemUI rebind.
     * OEM rows also ignore session-count and subtitle churn: those are
     * cosmetic, and each rebind is another island inflation.
     */
    fun normalized(): ForegroundNotificationState = when (surface) {
        NotificationSurface.PROMOTED -> copy(toolName = null, isToolRunning = false)
        NotificationSurface.HYPER_OS_FOCUS -> copy(isToolRunning = false)
        NotificationSurface.OEM_QUIET -> copy(
            activeCount = if (activeCount > 0) 1 else 0,
            toolName = null,
            isToolRunning = false,
            subtitle = if (activeCount > 0) "running" else "idle",
        )
        NotificationSurface.PLAIN -> this
    }
}

/**
 * A publication ledger, not a timer: decisions compare the current visible state
 * against the last *successfully posted* state. A delayed callback must re-evaluate
 * with a fresh snapshot, never replay the state that originally scheduled it.
 */
internal class ForegroundNotificationPolicy {
    sealed interface Decision {
        data object Skip : Decision
        data object Publish : Decision
        data class Wait(val delayMs: Long) : Decision
    }

    private var published: ForegroundNotificationState? = null
    private var publishedAtMs: Long = 0L

    fun decide(state: ForegroundNotificationState, nowMs: Long): Decision {
        val last = published ?: return Decision.Publish
        if (state == last) return Decision.Skip
        // Starting, finishing, changing the run clock, or entering/leaving
        // Live Updates must never wait behind a cosmetic tool-name change.
        if (state.activeCount != last.activeCount ||
            state.startedAtMs != last.startedAtMs ||
            state.finishedAtMs != last.finishedAtMs ||
            state.promoted != last.promoted
        ) return Decision.Publish

        // A tool switch is visible, but should not re-inflate an OEM island
        // for every tool in a rapid multi-tool turn. Plain FGS rows have a
        // shorter budget; neither mode posts simply because time passed.
        val gap = if (state.promoted || last.promoted) 20_000L else 5_000L
        val remaining = gap - (nowMs - publishedAtMs).coerceAtLeast(0L)
        return if (remaining <= 0L) Decision.Publish else Decision.Wait(remaining)
    }

    fun markPublished(state: ForegroundNotificationState, nowMs: Long) {
        published = state
        publishedAtMs = nowMs
    }
}
