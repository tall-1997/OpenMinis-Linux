package com.openminis.app.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [T-android-automation-rules] Automation engine — A-batch semantics after
 * jackwener/DeepseekHarness-Android's TriggerHub: keeps OS registrations in
 * sync with the enabled rules, matches incoming [AutomationEvent]s, and runs
 * the rule's action (unattended agent session; Steps are stored-only in v1).
 *
 *  - Time triggers → AlarmManager (exact with inexact fallback, same policy
 *    as [com.openminis.app.scheduled.ScheduledTaskManager]).
 *  - Everything else → [RuleMonitorService] broadcasts (Wi-Fi / Bluetooth /
 *    power / battery / screen) or the notification-listener forward.
 *  - Every fire passes [AutomationGate] (60 s debounce + 24 h / 50 cap) and
 *    is recorded in automation_runs.
 *
 * A failure in one rule must never take the process down (C1): each fire
 * runs in its own job under a SupervisorJob + CoroutineExceptionHandler.
 */
class AutomationRuleManager(
    private val context: Context,
    /** Injectable for tests that drive scheduling decisions on a fake clock. */
    internal val clock: () -> Long = System::currentTimeMillis,
) {

    /** App-scoped scope so an agent run outlives the receiver that started it. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e ->
                AppLogger.error(TAG, "automation task failed: ${e.message}")
            },
    )

    /** Test seam: when non-null, takes precedence over the MinisApp DB lookup. */
    @Volatile
    internal var daoOverride: AutomationRuleDao? = null

    /** Room handle; null while the app is half-initialized (GH#156 pattern). */
    internal val daoOrNull: AutomationRuleDao?
        get() = daoOverride
            ?: (context.applicationContext as? com.openminis.app.MinisApp)
                ?.let { app -> runCatching { app.database }.getOrNull() }
                ?.automationRuleDao()

    @Volatile
    private var cache: List<Pair<AutomationRuleEntity, RuleSpec>> = emptyList()

    /**
     * [v3 P1-11] Process-level fast path: true when the last sync found at
     * least one enabled, parseable rule. Every CRUD path ends in [sync], and
     * [rebuildCache] refreshes it too, so the flag is the honest "are there
     * rules at all" answer. When false, [onEventSuspend] returns before the
     * per-event DB probe and the battery poll — a no-rules device pays one
     * volatile read per notification instead of a query.
     */
    @Volatile
    internal var hasActiveRules: Boolean = false
        private set

    /** True once this process has probed the DB for rules at least once. */
    @Volatile
    private var probedRulesOnce: Boolean = false

    private val syncLock = Mutex()

    /**
     * Gate wired to the live DAO when available; the debounce memory lives
     * in-process, so a process restart naturally forgets recent fires (the
     * 24 h cap keeps working through the runs table).
     */
    val gate: AutomationGate = AutomationGate(clock) { ruleId, sinceMs ->
        daoOrNull?.countActionableSinceBlocking(ruleId, sinceMs) ?: 0
    }

    // ── CRUD (suspend; the tools wrap these in runBlocking) ──────────────

    suspend fun all(): List<AutomationRuleEntity> = daoOrNull?.all() ?: emptyList()

    suspend fun get(ruleId: String): AutomationRuleEntity? = daoOrNull?.get(ruleId)

    /**
     * Validate + persist + re-sync. Returns the saved entity, or the list of
     * validation problems (entity null). `knownTools` gates the whitelist.
     */
    suspend fun createValidated(
        spec: RuleSpec,
        knownTools: Set<String>,
    ): Pair<AutomationRuleEntity?, List<String>> {
        val result = RuleValidation.validate(spec, knownTools, clock())
        val valid = result.spec ?: return null to result.problems
        val dao = daoOrNull ?: return null to listOf("database not ready")
        val now = clock()
        val entity = AutomationRuleEntity(
            id = java.util.UUID.randomUUID().toString(),
            name = valid.name,
            specJson = valid.toJsonString(),
            enabled = true,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(entity)
        sync()
        return entity to emptyList()
    }

    /**
     * Replace fields of an existing rule. Null arguments keep the stored
     * value. Returns the saved entity (re-validated as a whole) or problems.
     */
    suspend fun updateValidated(
        ruleId: String,
        name: String? = null,
        trigger: Trigger? = null,
        conditions: List<Condition>? = null,
        action: RuleAction? = null,
        allowedTools: List<String>? = null,
        permissionMode: String? = null,
        enabled: Boolean? = null,
        knownTools: Set<String>,
    ): Pair<AutomationRuleEntity?, List<String>> {
        val dao = daoOrNull ?: return null to listOf("database not ready")
        val current = dao.get(ruleId) ?: return null to listOf("no rule with id $ruleId")
        val currentSpec = current.spec() ?: return null to listOf("stored spec is corrupt; delete the rule")
        val merged = RuleSpec(
            name = name ?: currentSpec.name,
            trigger = trigger ?: currentSpec.trigger,
            conditions = conditions ?: currentSpec.conditions,
            action = action ?: currentSpec.action,
            allowedTools = allowedTools ?: currentSpec.allowedTools,
            permissionMode = permissionMode ?: currentSpec.permissionMode,
        )
        val result = RuleValidation.validate(merged, knownTools, clock())
        val valid = result.spec ?: return null to result.problems
        val updated = current.copy(
            name = valid.name,
            specJson = valid.toJsonString(),
            enabled = enabled ?: current.enabled,
            updatedAt = clock(),
        )
        dao.upsert(updated)
        gate.forget(ruleId)
        sync()
        return updated to emptyList()
    }

    suspend fun setEnabled(ruleId: String, enabled: Boolean): Boolean {
        val dao = daoOrNull ?: return false
        val current = dao.get(ruleId) ?: return false
        dao.upsert(current.copy(enabled = enabled, updatedAt = clock()))
        sync()
        return true
    }

    suspend fun delete(ruleId: String): Boolean {
        val dao = daoOrNull ?: return false
        cancelAlarm(ruleId)
        val removed = dao.delete(ruleId) > 0
        dao.deleteRunsFor(ruleId)
        gate.forget(ruleId)
        sync()
        return removed
    }

    suspend fun recentRuns(ruleId: String, limit: Int = 20): List<AutomationRunEntity> =
        daoOrNull?.recentRuns(ruleId, limit) ?: emptyList()

    // ── registration sync ────────────────────────────────────────────────

    /**
     * Re-register every OS hook for the enabled rules. Called at boot, after
     * any rule change, and defensively from the automation UI. Never throws.
     *
     * Also enforces the automation_runs retention (v3 二-9): rows older than
     * [RUN_RETENTION_MS] are deleted here, so the history tables grow with
     * actual rule activity instead of forever.
     */
    suspend fun sync() = syncLock.withLock {
        val dao = daoOrNull ?: return@withLock
        val all = runCatching { dao.all() }
            .getOrElse { AppLogger.error(TAG, "cannot read rules: ${it.message}"); return@withLock }
        cache = all.filter { it.enabled }.mapNotNull { e ->
            e.spec()?.let { e to it } ?: run {
                AppLogger.warning(TAG, "rule ${e.id}: spec unparseable — ignored until re-saved")
                null
            }
        }
        hasActiveRules = cache.isNotEmpty()
        runCatching { dao.deleteRunsBefore(clock() - RUN_RETENTION_MS) }
            .onFailure { AppLogger.warning(TAG, "automation_runs retention failed: ${it.message}") }
        for (e in all) runCatching { cancelAlarm(e.id) }
        for ((e, s) in cache) {
            val t = s.trigger as? Trigger.Time ?: continue
            runCatching { scheduleAlarm(e, t) }
                .onFailure { AppLogger.warning(TAG, "rule ${e.id}: cannot schedule: ${it.message}") }
        }
        val needsMonitor = cache.any { (e, s) -> needsMonitorService(s.trigger) && e.enabled }
        runCatching { RuleMonitorService.setRunning(context, needsMonitor) }
            .onFailure { AppLogger.warning(TAG, "monitor service toggle failed: ${it.message}") }
    }

    /** Triggers that need the runtime-registered broadcast monitor. */
    private fun needsMonitorService(t: Trigger): Boolean = when (t) {
        is Trigger.WifiConnect, is Trigger.BluetoothConnect, is Trigger.PowerConnected,
        is Trigger.BatteryLow, is Trigger.ScreenOn, is Trigger.ScreenOff, is Trigger.ScreenUnlock,
        -> true
        // NotificationReceived rides the existing NotificationListenerService;
        // AppOpened rides the accessibility service's foreground-package
        // transition (MinisAccessibilityService.maybeEmitAppOpened).
        else -> false
    }

    // ── event matching ───────────────────────────────────────────────────

    /**
     * Match [event] against the enabled rules and fire each match in its own
     * job, so a slow rule neither delays other rules nor blocks the caller.
     */
    fun onEvent(event: AutomationEvent) {
        scope.launch { onEventSuspend(event) }
    }

    suspend fun onEventSuspend(event: AutomationEvent) {
        // [v3 P1-11] No-rules fast path. The flag is refreshed by sync() (every
        // CRUD path) — when it says "no rules", skip straight out: no rebuild
        // probe, no battery poll, no DB. The one-shot probe below only covers
        // a process that started after the rules were created and has not
        // synced yet (e.g. the alarm receiver fires first); afterwards sync()
        // keeps the flag honest.
        if (!hasActiveRules) {
            if (!probedRulesOnce) {
                probedRulesOnce = true
                rebuildCache()
            }
            if (cache.isEmpty()) return
        }
        // Re-arm the time alarm FIRST so a long agent run cannot push the
        // next occurrence out (same order as ScheduledTaskAlarmReceiver).
        if (event is AutomationEvent.Time) {
            daoOrNull?.get(event.ruleId)?.let { e ->
                val spec = e.spec()
                (spec?.trigger as? Trigger.Time)?.let { t ->
                    if (e.enabled) runCatching { scheduleAlarm(e, t) }
                }
            }
        }
        val now = clock()
        val snap = batterySnapshot()
        val env = RuleEnv(nowMs = now, batteryPercent = snap.first, charging = snap.second)
        for ((e, s) in cache) {
            val hit = runCatching {
                matches(s.trigger, event) &&
                    (event !is AutomationEvent.Time || event.ruleId == e.id) &&
                    s.conditions.all { it.holds(env) }
            }.onFailure { AppLogger.warning(TAG, "rule ${e.id}: cannot evaluate: ${it.message}") }
                .getOrDefault(false)
            if (hit) scope.launch { fire(e, s, event) }
        }
    }

    private suspend fun rebuildCache() {
        val dao = daoOrNull ?: return
        cache = runCatching { dao.enabled().mapNotNull { e -> e.spec()?.let { e to it } } }
            .getOrElse { emptyList() }
        hasActiveRules = cache.isNotEmpty()
    }

    // ── firing ───────────────────────────────────────────────────────────

    internal suspend fun fire(entity: AutomationRuleEntity, spec: RuleSpec, event: AutomationEvent) {
        val now = clock()
        val decision = gate.tryAcquire(entity.id)
        when {
            !decision.allowed && decision.reason == "debounced" -> {
                AppLogger.info(TAG, "rule ${entity.id} debounced — dropped")
                return
            }
            !decision.allowed && decision.reason == "rate_limited" -> {
                AppLogger.warning(TAG, "rule ${entity.id} hit the 24h/50 cap — recording skipped")
                daoOrNull?.insertRun(
                    AutomationRunEntity(
                        ruleId = entity.id, firedAt = now,
                        outcome = OUTCOME_SKIPPED_RATE_LIMIT, preview = event.describe().take(200),
                    ),
                )
                return
            }
        }

        val dao = daoOrNull
        val runId: Long = dao?.insertRun(
            AutomationRunEntity(ruleId = entity.id, firedAt = now, outcome = OUTCOME_STARTED),
        ) ?: 0L
        dao?.get(entity.id)?.let { dao.upsert(it.copy(lastFiredAt = now)) }

        val outcome: String
        val sessionId: String?
        when (val action = spec.action) {
            is RuleAction.Agent -> {
                val started = runCatching {
                    AutomationAgentRunner.start(
                        context, entity, spec, action, runId,
                        eventDescription = event.describe(),
                    )
                }.getOrElse {
                    AppLogger.warning(TAG, "rule ${entity.id} agent start failed: ${it.message}")
                    null
                }
                if (started != null) {
                    outcome = OUTCOME_STARTED
                    sessionId = started
                } else {
                    outcome = "${OUTCOME_ERROR_PREFIX}could not start unattended run"
                    sessionId = null
                }
            }
            is RuleAction.Steps -> {
                // [T-android-automation-rules] v1: stored + displayed only.
                outcome = OUTCOME_SKIPPED_STEPS_TODO
                sessionId = null
            }
        }

        if (runId != null && runId > 0) {
            dao?.updateRun(
                runId = runId,
                outcome = outcome,
                sessionId = sessionId,
                preview = event.describe().take(200),
            )
        } else if (outcome != OUTCOME_STARTED || sessionId == null) {
            // No row id (no DB) — nothing to update, log-only.
            AppLogger.info(TAG, "rule ${entity.id}: outcome=$outcome (no run row)")
        }
        AppLogger.info(TAG, "rule ${entity.id} '${spec.name}' fired by ${event.describe()}: $outcome")
    }

    // ── time alarms ──────────────────────────────────────────────────────

    internal fun scheduleAlarm(entity: AutomationRuleEntity, trigger: Trigger.Time) {
        val next = trigger.nextTriggerMs(clock(), entity.lastFiredAt ?: entity.createdAt) ?: run {
            AppLogger.info(TAG, "rule ${entity.id}: no next trigger")
            // One-shot fired out or dead schedule: disable in place so the
            // list card agrees the rule is finished (mirrors rescheduleNext).
            if (trigger.onceAtEpochMs != null || trigger.at != null) {
                scope.launch {
                    daoOrNull?.get(entity.id)?.let {
                        if (it.enabled) daoOrNull?.upsert(it.copy(enabled = false))
                    }
                }
            }
            return
        }
        val pi = buildPendingIntent(entity.id)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            AppLogger.info(TAG, "registered rule=${entity.id} name=\"${entity.name}\" triggerAt=$next")
        } catch (e: SecurityException) {
            // S+ users may have revoked SCHEDULE_EXACT_ALARM — fall back to
            // inexact so we still fire eventually rather than dropping.
            AppLogger.warning(TAG, "exact-alarm denied for rule=${entity.id}; falling back to inexact")
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } catch (t2: Throwable) {
                AppLogger.error(TAG, "inexact fallback failed for rule=${entity.id}: ${t2.message}")
            }
        }
    }

    fun cancelAlarm(ruleId: String) {
        val pi = buildPendingIntent(ruleId)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun buildPendingIntent(ruleId: String): PendingIntent {
        val intent = Intent(context, AutomationRuleReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_RULE_ID, ruleId)
        }
        // Namespace the request code away from the scheduled-task manager's
        // task-id hashes: "automation_" salts the same 31-bit space.
        val requestCode = ("automation_$ruleId").hashCode() and 0x7FFFFFFF
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    // ── device state ─────────────────────────────────────────────────────

    /** (percent, charging) from the sticky battery broadcast; (null, null) when absent. */
    fun batterySnapshot(): Pair<Int?, Boolean?> = runCatching {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to null
        val pct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 /
            i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        pct to charging
    }.getOrDefault(null to null)

    companion object {
        private const val TAG = "AutomationRuleMgr"
        const val ACTION_FIRE = "com.openminis.app.automation.FIRE"
        const val EXTRA_RULE_ID = "rule_id"

        const val OUTCOME_STARTED = "started"
        const val OUTCOME_OK = "ok"
        const val OUTCOME_ERROR_PREFIX = "error: "
        const val OUTCOME_SKIPPED_RATE_LIMIT = "skipped_rate_limit"
        const val OUTCOME_SKIPPED_STEPS_TODO = "skipped_steps_todo"

        /** [v3 二-9] automation_runs rolling retention window: 30 days. */
        const val RUN_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * Static funnel from Android components (which construct no manager) into the
 * process-wide engine — the no-DI counterpart of DSH's TriggerEvents.
 */
object AutomationEvents {

    @Volatile
    private var hub: AutomationRuleManager? = null

    private val lock = Any()

    /** Get-or-create the process-wide manager rooted at [context]. */
    fun manager(context: Context): AutomationRuleManager {
        hub?.let { return it }
        synchronized(lock) {
            hub?.let { return it }
            val m = AutomationRuleManager(context.applicationContext)
            hub = m
            return m
        }
    }

    /** Replace the hub (used by tests / explicit re-init). */
    fun init(manager: AutomationRuleManager?) {
        synchronized(lock) { hub = manager }
    }

    fun emit(event: AutomationEvent) {
        runCatching { hub?.onEvent(event) }
    }

    fun notificationPosted(packageName: String, title: String, text: String) =
        emit(AutomationEvent.Notification(packageName, title, text))

    fun appOpened(packageName: String) = emit(AutomationEvent.AppOpened(packageName))
}
