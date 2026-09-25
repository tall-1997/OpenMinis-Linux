package com.openminis.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs a preset sandbox command for the session that posted a task-complete
 * notification. [exported] is false in the manifest; extras never include a
 * free-form shell string.
 */
class SandboxNotifyActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SandboxNotifyActions.ACTION) return
        val sessionId = intent.getStringExtra(SandboxNotifyActions.EXTRA_SESSION_ID).orEmpty()
        val action = intent.getStringExtra(SandboxNotifyActions.EXTRA_ACTION).orEmpty()
        val command = SandboxNotifyActions.commandFor(action, context)
        if (sessionId.isBlank() || command == null) {
            AppLogger.warning(TAG, "ignored action=$action sessionBlank=${sessionId.isBlank()}")
            return
        }
        val pending = goAsync()
        (context.applicationContext as com.openminis.app.MinisApp)
            .appCoroutineScopes.io.launch {
            try {
                AppLogger.info(TAG, "execute action=$action session=$sessionId")
                ExecutionCoordinator.execute(sessionId, command)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "execute failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SandboxNotifyAction"
    }
}
