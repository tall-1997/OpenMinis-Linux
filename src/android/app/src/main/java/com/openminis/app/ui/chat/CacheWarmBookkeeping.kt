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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

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
 * dark) + streaming idle + replayable ([PromptCacheWarmer
 * .isCacheWarmingReplayable] — Anthropic thinking shapes are banned because
 * the warm's maxTokens=1 replay derives a different thinking budget, and so
 * are budget-forced providers) + TTL known AND due (90% elapsed) + not aged
 * out + economics pay for themselves (real models.dev rates — no invented
 * numbers). The warm action replays the last successful request's prompt
 * through [LLMProvider.sendMessage] with the hygiene triple (maxTokens = 1,
 * no continuation, no retries) — sendMessage takes a per-call maxTokens
 * parameter, so no provider-side change is needed — with thinking forced OFF
 * and two race/safety guards: a process-wide in-flight mutex (the scheduled
 * refresh point and the foreground hook must not double-bill one idle
 * window) and a wall-clock deadline so an abandoned warm never sits on the
 * single ProviderKeyGate permit for the 15-minute non-stream deadline.
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
        /** Warming family key ([PromptCacheWarmer.familyOf]); null = unknown family. */
        providerFamily: String?,
        /** Whether the recorded request ran with an enabled thinking level. */
        thinkingEnabled: Boolean,
        rates: PromptCacheWarmRates.Rates?,
        promptTokens: Int,
        /** Ledger-reported remaining fraction (0..1); null = pair never recorded / TTL unknown. */
        ttlRemainingPct: Double?,
        lastRequestAtMs: Long,
        /** Ledger-recorded prompt-cache TTL for the pair; null = never recorded / TTL unknown. */
        recordedTtlMs: Long?,
        /** Next refresh due point from the ledger; null = TTL unknown or too short to warm. */
        dueAtMs: Long?,
        nowMs: Long,
    ): WarmDecision {
        if (!enabled) return WarmDecision.Stop("prefs-disabled")
        if (streamingActive) return WarmDecision.Stop("streaming-active")
        if (!PromptCacheWarmer.isCacheWarmingReplayable(providerFamily, thinkingEnabled, forceThinking)) {
            // One shared rule, two diagnostic tags: a forced provider is the
            // budget-style ban (pi's forceAdaptiveThinking gap), everything
            // else reaching here is the Anthropic request-shape ban.
            return WarmDecision.Stop(
                if (forceThinking) "budget-thinking-replay-ban" else "thinking-replay-ban",
            )
        }
        if (dueAtMs == null) {
            // One null, two different realities — kept as two diagnostic tags.
            // [recordedTtlMs] null: the ledger never learned a TTL (pair never
            // recorded, or the family has no known lifetime). [recordedTtlMs]
            // known with a null due point: [PromptCacheWarmer
            // .getCacheWarmingDelayMs] refused the KNOWN TTL — it sits at or
            // below [PromptCacheWarmer.MIN_WARMABLE_TTL_MS]. Collapsing both
            // into "ttl-unknown" misdiagnosed the too-short case as a lookup
            // gap and sent debugging toward the TTL table, not the value.
            return WarmDecision.Stop(if (recordedTtlMs == null) "ttl-unknown" else "ttl-too-short")
        }
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

    /**
     * Wall-clock ceiling for one warm replay. A warm runs inside the SAME
     * request pipeline as user turns ([LLMProvider.sendMessage] ->
     * ProviderKeyGate.withPermit), whose (host, credential, model) bucket has
     * permits = 1 ([ProviderKeyGate.DEFAULT_PERMITS]) and whose caller-side
     * acquire has NO timeout of its own — the 15-minute non-stream deadline
     * (LLMProvider.nonStreamDeadlineMs) is the only thing that would ever free
     * the permit. A background refresh must not hold the single permit that
     * long; 30 s comfortably covers a one-token cache-read answer, and an
     * overrun abandons the refresh (log only, ledger NOT re-recorded — the
     * next real request re-arms warming as usual).
     */
    private const val WARM_DEADLINE_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ledger = PromptCacheTtlLedger()
    private val lock = Any()
    private var warmJob: Job? = null

    /**
     * In-flight mutex for warm dispatch (test-and-set at [sendWarm] entry):
     * the scheduled refresh point and the foreground hook can both fire inside
     * the same idle window, and without this both would send a billable
     * replay. Reset on EVERY outcome path (success, failure, deadline,
     * cancellation) in the warm coroutine's finally block.
     */
    private val warmInFlight = AtomicBoolean(false)

    @Volatile
    private var pending: PendingWarm? = null

    @Volatile
    private var foregroundHookInstalled = false

    // ── Test seams (JVM unit tests have no SharedPreferences / models.dev
    // registry / real clock slack; null always means "consult the production
    // lookup"). Cleared by [resetForTest]. ───────────────────────────────────

    /** Overrides [CacheWarmingPrefs.enabled] when non-null. */
    @Volatile
    internal var warmingEnabledForTest: Boolean? = null

    /** Overrides [PromptCacheWarmRates.forModel] when non-null. */
    @Volatile
    internal var warmRatesForTest: PromptCacheWarmRates.Rates? = null

    /** Shrinks the warm deadline below [WARM_DEADLINE_MS] when non-null. */
    @Volatile
    internal var warmDeadlineMsForTest: Long? = null

    /** Test observation of the in-flight mutex without racing it. */
    internal fun isWarmInFlightForTest(): Boolean = warmInFlight.get()

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

    /** Test seam: drop all scheduled state and test overrides. */
    internal fun resetForTest() {
        synchronized(lock) {
            warmJob?.cancel()
            warmJob = null
            pending = null
            warmingEnabledForTest = null
            warmRatesForTest = null
            warmDeadlineMsForTest = null
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
        if (!(warmingEnabledForTest ?: CacheWarmingPrefs.enabled())) return
        val instanceId = snapshot.instanceId
        val modelId = snapshot.modelId
        val decision = PromptCacheWarmPlanner.decide(
            enabled = true,
            streamingActive = runCatching { snapshot.isBusy() }.getOrDefault(true),
            forceThinking = snapshot.forceThinking,
            providerFamily = PromptCacheWarmer.familyOf(snapshot.providerTypeRaw),
            thinkingEnabled = snapshot.thinkingLevel.isEnabled,
            rates = warmRatesForTest ?: PromptCacheWarmRates.forModel(modelId),
            promptTokens = snapshot.promptTokens,
            ttlRemainingPct = ledger.ttlRemainingPct(instanceId, modelId, nowMs),
            lastRequestAtMs = snapshot.requestedAtMs,
            recordedTtlMs = ledger.recordedTtlMs(instanceId, modelId),
            dueAtMs = ledger.nextWarmDueAtMs(instanceId, modelId),
            nowMs = nowMs,
        )
        when (decision) {
            is WarmDecision.Stop -> {
                AppLogger.info(TAG, "[cache-warm] stop (${decision.reason}) model=$modelId")
            }
            is WarmDecision.Warm -> {
                if (!sendWarm(snapshot, decision)) {
                    // The refresh dispatch lost the in-flight race — the warm
                    // already sent on the other trigger; this one skips so the
                    // idle window never double-bills.
                    AppLogger.info(TAG, "[cache-warm] trigger skipped: refresh in flight model=$modelId")
                }
            }
        }
    }

    /**
     * The warm request itself: replay the last successful request's prompt
     * with the hygiene triple — maxTokens = 1 ([PromptCacheWarmer]
     * .warmRequestOptions), no continuation (there is none in this API), no
     * retries ([sendMessage] does not loop transient failures; the loop's
     * retry ladder belongs to real turns) — plus thinking forced OFF: the
     * replay exists to refresh the cache entry, not to reproduce output, and
     * a thinking level derived from request parameters (Anthropic budget
     * tokens, reasoning effort) would either change the request shape or be
     * rejected outright under the one-token output cap. The planner-level
     * replay ban ([PromptCacheWarmer.isCacheWarmingReplayable]) already
     * excludes thinking shapes; OFF is the defense in depth that keeps any
     * future family whose thinking derives from request parameters from
     * turning every warm into a guaranteed 400.
     *
     * Two guards make a refresh safe to race and safe to wait on:
     *  - One in-flight warm per process: [warmInFlight] is test-and-set at
     *    entry, and this call returns false when a refresh is already running
     *    — the loser trigger (scheduled point vs foreground hook) skips
     *    instead of double-billing. The flag is released on EVERY outcome
     *    path in the coroutine's finally block.
     *  - A wall-clock deadline ([WARM_DEADLINE_MS]): the replay holds the
     *    single ProviderKeyGate permit of its (host, credential, model)
     *    bucket, and the user-path acquire has no timeout of its own, so an
     *    abandoned warm must not sit on that permit for the 15-minute
     *    non-stream deadline. Deadline overrun abandons THIS refresh: log,
     *    release the slot, do NOT re-record the ledger.
     *
     * Best-effort overall: any failure is logged and ends warming until the
     * next real request re-records the ledger.
     *
     * @return true when this call dispatched the refresh; false when a warm
     *   was already in flight and the trigger was skipped.
     */
    private fun sendWarm(snapshot: PendingWarm, decision: WarmDecision.Warm): Boolean {
        if (!warmInFlight.compareAndSet(false, true)) {
            AppLogger.info(TAG, "[cache-warm] refresh already in flight; duplicate trigger skipped model=${snapshot.modelId}")
            return false
        }
        AppLogger.info(
            TAG,
            "[cache-warm] sending refresh model=${snapshot.modelId} promptTokens=${snapshot.promptTokens} delayMs=${decision.delayMs}",
        )
        scope.launch {
            try {
                val deadlineMs = warmDeadlineMsForTest ?: WARM_DEADLINE_MS
                val startedAt = System.currentTimeMillis()
                val response = withTimeoutOrNull(deadlineMs) {
                    snapshot.provider.sendMessage(
                        messages = snapshot.messages,
                        systemPrompt = snapshot.systemPrompt,
                        maxTokens = PromptCacheWarmer.warmRequestOptions().maxTokens,
                        tools = snapshot.tools,
                        thinkingLevel = ThinkingLevel.OFF,
                    )
                }
                if (response == null) {
                    AppLogger.warning(
                        TAG,
                        "[cache-warm] refresh exceeded ${deadlineMs}ms deadline; abandoned model=${snapshot.modelId}",
                    )
                    return@launch
                }
                val finishedAt = System.currentTimeMillis()
                AppLogger.info(TAG, "[cache-warm] warm ok in ${finishedAt - startedAt}ms model=${snapshot.modelId}")
                // The replay refreshed the cache entry: reset the clock and
                // keep pi's continuous warming chain alive while enabled.
                val ttlMs = PromptCacheWarmer.promptCacheTtlMs(snapshot.providerTypeRaw)
                ledger.recordRequest(snapshot.instanceId, snapshot.modelId, ttlMs, finishedAt)
                scheduleNextWarm(snapshot.instanceId, snapshot.modelId, finishedAt)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                AppLogger.warning(TAG, "[cache-warm] warm failed: ${e.message}")
            } finally {
                // Success, failure, deadline overrun and cancellation all end
                // here — a stuck false would disable warming forever.
                warmInFlight.set(false)
            }
        }
        return true
    }

    /**
     * Register the foreground hook once; removed implicitly when the process
     * dies. The hook is a best-effort idle opportunity — the scheduled refresh
     * path works without it — so dispatcher/lifecycle unavailability (plain
     * JVM unit tests have no Main dispatcher and the launch call itself
     * throws) downgrades to a log, never to a crash of the warm scheduling.
     */
    private fun ensureForegroundHook() {
        if (foregroundHookInstalled) return
        synchronized(this) {
            if (foregroundHookInstalled) return
            foregroundHookInstalled = true
            // Launch on the scope's IO dispatcher — NOT on Dispatchers.Main.
            // Plain JVM tests have no Main dispatcher; coroutines-test installs
            // a TestMainDispatcher whose scheduler closes when its runTest ends,
            // and a launch scheduled on it AFTER the test has finished throws
            // IllegalStateException that leaks into the NEXT runTest as an
            // UncaughtExceptionsBeforeTest. The observer registration itself
            // is main-thread-safe on Android (withContext(Main) below); a
            // cancelled dispatcher inside withContext is caught by runCatching
            // and degrades to a log, never an uncaught exception.
            scope.launch {
                runCatching {
                    withContext(Dispatchers.Main) {
                        ProcessLifecycleOwner.get().lifecycle.addObserver(
                            object : DefaultLifecycleObserver {
                                override fun onStart(owner: LifecycleOwner) {
                                    onAppForeground()
                                }
                            },
                        )
                    }
                }.onFailure { e ->
                    AppLogger.warning(TAG, "[cache-warm] foreground hook unavailable: ${e.message}")
                    foregroundHookInstalled = false
                }
            }
        }
    }
}
