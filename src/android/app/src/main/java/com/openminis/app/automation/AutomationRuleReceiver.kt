package com.openminis.app.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * [T-android-automation-rules] Manifest receiver for the automation engine:
 *
 *  - [AutomationRuleManager.ACTION_FIRE] — an AlarmManager fire for a Time
 *    trigger. This is a HANDOFF, not a run (same discipline as
 *    [com.openminis.app.scheduled.ScheduledTaskAlarmReceiver], GH#197): the
 *    next occurrence is re-armed first, the fire is dispatched
 *    fire-and-forget (waitForCompletion semantics), and the agent loop runs
 *    on app-scoped coroutines kept alive by the foreground service.
 *  - [Intent.ACTION_BOOT_COMPLETED] / ACTION_MY_PACKAGE_REPLACED — re-register
 *    every enabled rule (alarms + monitor service) so persisted rules survive
 *    a reboot or an app update.
 *
 * MANIFEST OWNER: this class must be declared in AndroidManifest.xml (see the
 * automation report for the exact entries).
 */
class AutomationRuleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AutomationRuleManager.ACTION_FIRE -> {
                val ruleId = intent.getStringExtra(AutomationRuleManager.EXTRA_RULE_ID) ?: return
                AppLogger.info(TAG, "fire received: rule=$ruleId")
                val pending = goAsync()
                val appContext = context.applicationContext
                val startedAt = SystemClock.elapsedRealtime()
                val scope = (appContext as? com.openminis.app.MinisApp)
                    ?.appCoroutineScopes?.application
                    ?: run {
                        // Half-built app (GH#156 shape): nothing we can do, but
                        // the broadcast must still be released.
                        pending.finish()
                        return
                    }
                scope.launch {
                    try {
                        withTimeout(HANDOFF_TIMEOUT_MS) {
                            val manager = AutomationEvents.manager(appContext)
                            val entity = manager.get(ruleId) ?: run {
                                AppLogger.warning(TAG, "rule $ruleId not found — skipping fire")
                                return@withTimeout
                            }
                            if (!entity.enabled) {
                                AppLogger.info(TAG, "rule $ruleId disabled — skipping fire")
                                return@withTimeout
                            }
                            // Re-arm FIRST (mirrors ScheduledTaskManager.
                            // rescheduleNext ordering), then match + dispatch:
                            // onEventSuspend re-arms the next occurrence, runs
                            // the gate and hands the action off fire-and-forget
                            // (the agent loop keeps running on the manager's
                            // app-scoped scope + the foreground service).
                            val spec = entity.spec()
                            if (spec?.trigger is Trigger.Time) {
                                manager.onEventSuspend(AutomationEvent.Time(ruleId))
                            }
                        }
                    } catch (t: Throwable) {
                        // Includes TimeoutCancellationException. The alarm stays
                        // armed by the re-arm above, so a wedged fire self-heals.
                        AppLogger.error(TAG, "rule $ruleId fire failed: ${t.message}")
                    } finally {
                        pending.finish()
                        AppLogger.info(
                            TAG,
                            "rule $ruleId handoff done in ${SystemClock.elapsedRealtime() - startedAt}ms",
                        )
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AppLogger.info(TAG, "${intent.action} — re-registering automation rules")
                val pending = goAsync()
                val appContext = context.applicationContext
                val scope = (appContext as? com.openminis.app.MinisApp)?.appCoroutineScopes?.application
                if (scope == null) {
                    pending.finish()
                    return
                }
                scope.launch {
                    try {
                        withTimeout(HANDOFF_TIMEOUT_MS) {
                            AutomationEvents.manager(appContext).sync()
                        }
                    } catch (t: Throwable) {
                        AppLogger.error(TAG, "boot resync failed: ${t.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "AutomationRuleRecv"

        /**
         * Ceiling for the whole handoff — a manifest-declared receiver gets
         * roughly 10s of background-broadcast budget before the system ANRs
         * it; 8s leaves headroom (same constant as the scheduled receiver).
         */
        private const val HANDOFF_TIMEOUT_MS = 8_000L
    }
}
