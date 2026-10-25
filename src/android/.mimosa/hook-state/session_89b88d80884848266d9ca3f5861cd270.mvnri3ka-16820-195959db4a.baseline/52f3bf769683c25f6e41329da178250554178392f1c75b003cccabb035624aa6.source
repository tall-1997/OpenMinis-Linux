package com.openminis.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openminis.app.service.ApprovalGate

/**
 * Receives the approve/deny button taps from the approval notification and
 * forwards them to [ApprovalGate] so the waiting tool dispatch coroutine wakes up.
 *
 * Registered in the manifest as a non-exported broadcast receiver — the system
 * CAN still deliver notification-action PendingIntents to it because
 * [PendingIntent.getBroadcast] explicitly targets this component class, which
 * overrides the exported flag for explicit intents. The action strings are
 * app-internal (not exported to other apps), so keeping this receiver
 * non-exported is safe and prevents spoofed approval intents.
 */
class ApprovalBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Reuse the exact same constants that ApprovalNotifier uses to build
        // the action intents — single source of truth via the shared companion.
        val id = intent.getStringExtra(ApprovalNotifier.EXTRA_REQUEST_ID)
        when (intent.action) {
            ApprovalNotifier.ACTION_APPROVE -> {
                if (id != null) {
                    ApprovalGate.approve(id)
                    Log.d(TAG, "received approve for id=${id.take(8)}…")
                }
            }
            ApprovalNotifier.ACTION_DENY -> {
                if (id != null) {
                    ApprovalGate.deny(id)
                    Log.d(TAG, "received deny for id=${id.take(8)}…")
                }
            }
        }
        // Always clear the notification — even if the id is missing, stale
        // notifications don't help anyone. The clearing responsibility lives
        // here (user-tap path) and on the ApprovalGate.waitFor timeout path
        // (caller-side). See ApprovalNotifier's KDoc for the full contract.
        if (id != null) ApprovalNotifier.cancelApproval(context, id)
    }

    companion object {
        private const val TAG = "ApprovalBR"
    }
}
