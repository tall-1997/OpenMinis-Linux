package com.openminis.app.ui.chat

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.data.CacheWarmingPrefs
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.PromptCacheTtlLedger
import com.openminis.app.provider.PromptCacheWarmRates
import com.openminis.app.provider.PromptCacheWarmer
import com.openminis.app.provider.SamplingIdentity
import com.openminis.app.provider.WarmingPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [T-prompt-cache-warming] Wiring of the pi cache-warming port
 * ([PromptCacheWarmer] / [PromptCacheTtlLedger] / [CacheWarmingPrefs]) into the
 * chat agent loop. Two halves:
 *
 *  - **Bookkeeping** ([PromptCacheWarmCoordinator.onStreamTurnSucceeded], called
 *    once per successful streamed turn at the `logSuccess` point): record the
 *    (instance, model) pair into the TTL ledger — the memory-only "when did the
 *    last real request run against this pair" store the warm hook reads.
 *  - **Warm trigger** ([PromptCacheWarmPlanner]): a pure decision gate evaluated
 *    at the two idle opportunities this build owns:
 *      1. the scheduled 90%-of-TTL refresh point after the turn settled, and
 *      2. the app returning to the foreground (ProcessLifecycleOwner ON_START —
 *         the user is back, so a near-expiry cache entry is worth refreshing
 *         before their next send).
 *
 * Trigger conditions (pi cache-warming.ts): prefs enabled (default OFF, ships
 * dark) + streaming idle + not a budget-thinking request (replay ban) + TTL
 * known AND due (90% elapsed) + not aged out + economics pay for themselves
 * (real models.dev rates — no invented numbers). The warm action replays the
 * last successful request's prompt through [LLMProvider.sendMessage] with the
 * hygiene triple (maxTokens = 1, no continuation, no retries) — sendMessage
 * takes a per-call maxTokens parameter, so no provider-side change is needed.
 *
 * NOT a trigger on purpose: `ChatViewModelResumeExt.resume()`. A resume
 * immediately re-enters the agent loop with a REAL request, which itself
 * refreshes the cache entry — scheduling a warm ahead of it would fire two
 * billable requests back to back. The foreground hook covers the "user came
 * back" moment that resume would have represented.
 *
 * Everything in this file is chat-side wiring; the policy math stays in
 * provider/PromptCacheWarmer.kt (pure, JVM-tested).
 */

/** Outcome of one warm-trigger evaluation. */
sealed interface WarmDecision {
    /** Send the refresh after [delayMs] (0 = immediately). */
    data class Warm(val delayMs: Long) : WarmDecision

    /** No warm; [reason] is a stable diagnostic tag, not user copy. */
    data class Stop(val reason: String) : WarmDecision
}

/**
 * Pure warm-or-stop gate over the trigger-condition combination. Order is
 * cheapest-and-most-decisive first; each [WarmDecision.Stop.reason] is unique
 * so logs pinpoint which gate refused.
 */
object PromptCacheWarmPlanner {

    fun decide(
        enabled: Boolean,
        streamingActive: Boolean,
        forceThinking: Boolean,
        rates: PromptCacheWarmRates.Rates?,
        promptTokens: Int,
        /** Ledger-reported remaining fraction (0..1); null = pair never recorded / TTL unknown. */
        ttlRemainingPct: Double?,
        lastRequestAtMs: Long,
        /** Next refresh due point from the ledger; null = TTL unknown or too short to warm. */
        dueAtMs: Long?,
        nowMs: Long,
    ): WarmDecision {
        if (!enabled) return WarmDecision.Stop("prefs-disabled")
        if (streamingActive) return WarmDecision.Stop("streaming-active")
        if (forceThinking) return WarmDecision.Stop("budget-thinking-replay-ban")
        if (dueAtMs == null) return WarmDecision.Stop("ttl-unknown")
        // Idle horizon (pi MAX_IDLE_AGE): past it, continuation estimates are
        // meaningless and the entry is effectively lost anyway.
        if (PromptCacheWarmer.isWarmingTooOld(lastRequestAtMs, nowMs, WarmingPhase.IDLE)) {
            return WarmDecision.Stop("aged-out")
        }
        // Not yet at the 90% refresh point (pi: refresh once 10% of the TTL
        // remains). Unknown remaining counts as due — the dueAtMs null branch
        // above already answered "can we warm at all".
        if (ttlRemainingPct != null && ttlRemainingPct > PromptCacheWarmer.TTL_REMAINING_REFRESH_FRACTION) {
            return WarmDecision.Stop("not-due")
        }
        if (promptTokens <= 0) return WarmDecision.Stop("prompt-tokens-unknown")
        if (rates == null) return WarmDecision.Stop("pricing-unavailable")
        val economics = PromptCacheWarmer.evaluateWarming(
            promptTokens = promptTokens,
            cacheReadPerTokenUsd = rates.cacheReadPerTokenUsd,
            cacheWritePerTokenUsd = rates.cacheWritePerTokenUsd,
            inputPerTokenUsd = rates.inputPerTokenUsd,
            outputPerTokenUsd = rates.outputPerTokenUsd,
            phase = WarmingPhase.IDLE,
        )
        val rawSavings = economics.missCost - economics.warmCost
        val profitable = PromptCacheWarmer.shouldWarm(
            expectedSavingsUsd = rawSavings,
            idleChatProbability = PromptCacheWarmer.IDLE_CONTINUATION_PROBABILITY,
            ttlRemainingPct = ttlRemainingPct,
        )
        if (!profitable) return WarmDecision.Stop("economics-unprofitable")
        return WarmDecision.Warm(delayMs = maxOf(0L, dueAtMs - nowMs))
    }
}

/**
 * Process-wide warm scheduler + TTL bookkeeping owner. One per app (pi hosts
 * own scheduling globally too); keyed by (provider instance, model id) so
 * several sessions sharing a model do not double-book. All mutating paths are
 * serialized on [lock]; the warm request itself runs on [scope] (IO).
 */
object PromptCacheWarmCoordinator {

    private const val TAG = "ChatViewModel"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ledger = PromptCacheTtlLedger()
    private val lock = Any()
    private var warmJob: Job? = null

    @Volatile
    private var pending: PendingWarm? = null

    @Volatile
    private var foregroundHookInstalled = false

    /**
     * The last successful request's replay inputs. Held process-wide (not per
     * ViewModel) so a warm survives the chat screen leaving scope — the cache
     * entry it would refresh belongs to the provider, not to a session.
     */
    private data class PendingWarm(
        val provider: LLMProvider,
        val instanceId: String,
        val modelId: String,
        val providerTypeRaw: String?,
        val messages: List<LLMMessage>,
        val systemPrompt: String?,
        val tools: List<AgentToolDefinition>,
        val thinkingLevel: ThinkingLevel,
        val promptTokens: Int,
        val forceThinking: Boolean,
        val isBusy: () -> Boolean,
        val requestedAtMs: Long,
    )

    /**
     * Stream-success hook (agent loop's `logSuccess` point): record the TTL
     * ledger entry unconditionally (bookkeeping is free; the planner gates the
     * actual warm), then — when the user opted in — schedule the refresh at the
     * ledger's 90%-of-TTL due point. The busy guard is wrapped in a
     * WeakReference so the process-wide [pending] snapshot never pins a
     * destroyed ChatViewModel; a collected VM reads as busy (skip warming —
     * nobody is coming back to that session).
     */
    fun onStreamTurnSucceeded(
        provider: LLMProvider,
        messages: List<LLMMessage>,
        systemPrompt: String?,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        usage: LLMUsage?,
        isBusy: () -> Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val modelId = provider.model.id
        val instanceId = SamplingIdentity.of(provider)
        val providerTypeRaw = SamplingIdentity.providerTypeOf(provider)
        val ttlMs = PromptCacheWarmer.promptCacheTtlMs(providerTypeRaw)
        ledger.recordRequest(instanceId, modelId, ttlMs, nowMs)
        val promptTokens = usage?.let { u ->
            u.inputTokens + (u.cacheReadInputTokens ?: 0) + (u.cacheCreationInputTokens ?: 0)
        } ?: 0
        val busyRef = java.lang.ref.WeakReference(isBusy)
        pending = PendingWarm(
            provider = provider,
            instanceId = instanceId,
            modelId = modelId,
            providerTypeRaw = providerTypeRaw,
            messages = messages.toList(),
            systemPrompt = systemPrompt,
            tools = tools,
            thinkingLevel = thinkingLevel,
            promptTokens = promptTokens,
            forceThinking = provider.forcesThinkingWhenEnabled,
            isBusy = { busyRef.get()?.invoke() ?: true },
            requestedAtMs = nowMs,
        )
        scheduleNextWarm(instanceId, modelId, nowMs)
    }

    /** ProcessLifecycleOwner ON_START: evaluate the due gate as an idle opportunity. */
    internal fun onAppForeground(nowMs: Long = System.currentTimeMillis()) {
        fireIfDue(nowMs)
    }

    /** Test seam: drop all scheduled state. */
    internal fun resetForTest() {
        synchronized(lock) {
            warmJob?.cancel()
            warmJob = null
            pending = null
        }
    }

    private fun scheduleNextWarm(instanceId: String, modelId: String, nowMs: Long) {
        ensureForegroundHook()
        synchronized(lock) {
            warmJob?.cancel()
            val dueAt = ledger.nextWarmDueAtMs(instanceId, modelId) ?: return
            val snapshot = pending ?: return
            warmJob = scope.launch {
                val delay = dueAt - nowMs
                if (delay > 0) kotlinx.coroutines.delay(delay)
                fireIfDue()
            }
        }
    }

    private fun fireIfDue(nowMs: Long = System.currentTimeMillis()) {
        val snapshot = pending ?: return
        if (!CacheWarmingPrefs.enabled()) return
        val instanceId = snapshot.instanceId
        val modelId = snapshot.modelId
        val decision = PromptCacheWarmPlanner.decide(
            enabled = true,
            streamingActive = runCatching { snapshot.isBusy() }.getOrDefault(true),
            forceThinking = snapshot.forceThinking,
            rates = PromptCacheWarmRates.forModel(modelId),
            promptTokens = snapshot.promptTokens,
            ttlRemainingPct = ledger.ttlRemainingPct(instanceId, modelId, nowMs),
            lastRequestAtMs = snapshot.requestedAtMs,
            dueAtMs = ledger.nextWarmDueAtMs(instanceId, modelId),
            nowMs = nowMs,
        )
        when (decision) {
            is WarmDecision.Stop -> {
                AppLogger.info(TAG, "[cache-warm] stop (${decision.reason}) model=$modelId")
            }
            is WarmDecision.Warm -> sendWarm(snapshot, decision)
        }
    }

    /**
     * The warm request itself: replay the last successful request's prompt
     * with the hygiene triple — maxTokens = 1 ([PromptCacheWarmer]
     * .warmRequestOptions), no continuation (there is none in this API), no
     * retries ([sendMessage] does not loop transient failures; the loop's
     * retry ladder belongs to real turns). Best-effort: any failure is logged
     * and ends warming until the next real request re-records the ledger.
     */
    private fun sendWarm(snapshot: PendingWarm, decision: WarmDecision.Warm) {
        AppLogger.info(
            TAG,
            "[cache-warm] sending refresh model=${snapshot.modelId} promptTokens=${snapshot.promptTokens} delayMs=${decision.delayMs}",
        )
        scope.launch {
            val startedAt = System.currentTimeMillis()
            val result = runCatching {
                snapshot.provider.sendMessage(
                    messages = snapshot.messages,
                    systemPrompt = snapshot.systemPrompt,
                    maxTokens = PromptCacheWarmer.warmRequestOptions().maxTokens,
                    tools = snapshot.tools,
                    thinkingLevel = snapshot.thinkingLevel,
                )
            }
            val finishedAt = System.currentTimeMillis()
            result
                .onSuccess {
                    AppLogger.info(TAG, "[cache-warm] warm ok in ${finishedAt - startedAt}ms model=${snapshot.modelId}")
                    // The replay refreshed the cache entry: reset the clock and
                    // keep pi's continuous warming chain alive while enabled.
                    val ttlMs = PromptCacheWarmer.promptCacheTtlMs(snapshot.providerTypeRaw)
                    ledger.recordRequest(snapshot.instanceId, snapshot.modelId, ttlMs, finishedAt)
                    scheduleNextWarm(snapshot.instanceId, snapshot.modelId, finishedAt)
                }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure
                    AppLogger.warning(TAG, "[cache-warm] warm failed: ${e.message}")
                }
        }
    }

    /** Register the foreground hook once; removed implicitly when the process dies. */
    private fun ensureForegroundHook() {
        if (foregroundHookInstalled) return
        synchronized(this) {
            if (foregroundHookInstalled) return
            foregroundHookInstalled = true
            scope.launch(Dispatchers.Main) {
                runCatching {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(
                        object : DefaultLifecycleObserver {
                            override fun onStart(owner: LifecycleOwner) {
                                onAppForeground()
                            }
                        },
                    )
                }.onFailure { e ->
                    AppLogger.warning(TAG, "[cache-warm] foreground hook unavailable: ${e.message}")
                    foregroundHookInstalled = false
                }
            }
        }
    }
}
