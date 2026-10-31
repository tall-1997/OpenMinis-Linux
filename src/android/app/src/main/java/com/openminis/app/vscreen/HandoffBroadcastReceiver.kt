package com.openminis.app.vscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openminis.app.logging.AppLogger
import com.openminis.app.vscreen.HandoffStateMachine.Action

/**
 * [T-android-vscreen2] Receives the three action-button taps from the
 * hand_to_user "needs you" notification and applies them through
 * [VirtualScreenManager.resolveHandoff] (the real move-back work runs on a
 * worker thread via goAsync — a Shizuku move can take seconds).
 *
 * Registered at RUNTIME by [HandoffNotifier.ensureReceiver] (works without a
 * manifest change while the process lives). The manifest entry requested
 * from the main agent (`.vscreen.HandoffBroadcastReceiver`, exported=false,
 * intent-filter for the three ACTION_HANDOFF_* strings) adds process-death
 * durability; double delivery after that is inert because the state machine
 * rejects handoffs that are no longer WAITING.
 */
class HandoffBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val displayId = intent.getIntExtra(HandoffNotifier.EXTRA_DISPLAY_ID, -1)
        val handoffAction = when (action) {
            HandoffNotifier.ACTION_HANDOFF_DONE -> Action.MOVE_BACK_DONE
            HandoffNotifier.ACTION_HANDOFF_KEEP -> Action.KEEP
            HandoffNotifier.ACTION_HANDOFF_ABORT -> Action.ABORT
            else -> return
        }
        if (displayId < 0) {
            AppLogger.warning(TAG, "handoff action $action without a valid display id")
            return
        }
        val pending = goAsync()
        Thread {
            try {
                val outcome = VirtualScreenManager.resolveHandoff(displayId, handoffAction)
                AppLogger.info(TAG, "handoff display=$displayId action=$handoffAction → $outcome")
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "handoff resolve failed: ${t.message}")
            } finally {
                runCatching { HandoffNotifier.cancel(context, displayId) }
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "HandoffBR"
    }
}
