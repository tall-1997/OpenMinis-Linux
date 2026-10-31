package com.openminis.app.vscreen

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger

/**
 * [T-android-vscreen2] The "needs you" (需要你处理) notification for the
 * hand_to_user flow, with the three DSH action buttons:
 *
 *  - **处理好了 (Done)** — the app is moved back to the agent screen
 *    (re-muted) and the model is told to re-read the screen.
 *  - **我自己做完了 (I did it myself)** — the app stays on the user's screen;
 *    the model verifies only, no more operating.
 *  - **不做了 (Give up)** — the handoff ends; the model skips the step.
 *
 * Buttons target [HandoffBroadcastReceiver], which is registered AT RUNTIME
 * (application context, RECEIVER_NOT_EXPORTED) — notification-action
 * PendingIntents with an explicit component + package resolve against a
 * registered receiver without any manifest entry. A manifest entry for the
 * same class would ALSO work (requested from the main agent for
 * process-death durability); registering twice is safe because the state
 * machine rejects already-resolved handoffs.
 *
 * All user-facing strings are resolved by resource name
 * ([str] via getIdentifier) with the English text below as fallback, so this
 * file compiles before the main agent adds the keys to strings.xml; once the
 * keys exist the resources win automatically.
 */
object HandoffNotifier {
    private const val TAG = "HandoffNotifier"

    const val CHANNEL_ID = "minis_vscreen_handoff"

    /** App-internal actions carried by the three notification buttons. */
    const val ACTION_HANDOFF_DONE = "com.openminis.app.vscreen.HANDOFF_DONE"
    const val ACTION_HANDOFF_KEEP = "com.openminis.app.vscreen.HANDOFF_KEEP"
    const val ACTION_HANDOFF_ABORT = "com.openminis.app.vscreen.HANDOFF_ABORT"

    /** Broadcast fired after every handoff state change (package-scoped). */
    const val ACTION_STATE_CHANGED = "com.openminis.app.vscreen.HANDOFF_STATE_CHANGED"

    const val EXTRA_DISPLAY_ID = "vscreen_handoff_display_id"
    const val EXTRA_STATE = "vscreen_handoff_state"

    private const val REQ_DONE = 0x51A
    private const val REQ_KEEP = 0x51B
    private const val REQ_ABORT = 0x51C
    private const val NOTIF_BASE = 0x3D100

    @Volatile private var receiverRegistered: Boolean = false

    fun notificationId(displayId: Int): Int = NOTIF_BASE + displayId

    /**
     * Resolves a vscreen string resource by name, falling back to the
     * English literal until the main agent adds the keys to strings.xml.
     */
    fun str(ctx: Context, name: String, fallback: String): String {
        val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else fallback
    }

    /** Creates the notification channel (idempotent). */
    fun ensureChannel(ctx: Context) {
        val mgr = ContextCompat.getSystemService(ctx, NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ID,
                str(ctx, "notif_vscreen_handoff_channel_name", "Agent needs you (virtual screen)"),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = str(
                    ctx,
                    "notif_vscreen_handoff_channel_desc",
                    "Asks you to handle a step the agent cannot do: login, captcha, payment or a choice.",
                )
                setShowBadge(false)
            },
        )
    }

    /**
     * Registers [HandoffBroadcastReceiver] for the three action broadcasts on
     * the application context (once per process). Runtime registration keeps
     * the feature working without a manifest change; the manifest entry
     * reported to the main agent adds process-death durability on top.
     */
    fun ensureReceiver(ctx: Context) {
        if (receiverRegistered) return
        synchronized(HandoffNotifier) {
            if (receiverRegistered) return
            runCatching {
                val filter = IntentFilter().apply {
                    addAction(ACTION_HANDOFF_DONE)
                    addAction(ACTION_HANDOFF_KEEP)
                    addAction(ACTION_HANDOFF_ABORT)
                }
                val receiver: BroadcastReceiver = HandoffBroadcastReceiver()
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    ContextCompat.registerReceiver(
                        ctx.applicationContext,
                        receiver,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                }
                receiverRegistered = true
                AppLogger.info(TAG, "handoff receiver registered at runtime")
            }.onFailure { AppLogger.warning(TAG, "receiver registration failed: ${it.message}") }
        }
    }

    /** Posts the "needs you" notification for [record]. Returns whether it was posted. */
    fun post(ctx: Context, record: HandoffRecord): Boolean {
        ensureChannel(ctx)
        ensureReceiver(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return false
        }
        val what = record.reason.ifBlank { kindLabel(record.kind) }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(str(ctx, "notif_vscreen_handoff_title", "The agent needs you"))
            .setContentText(
                str(ctx, "notif_vscreen_handoff_text", "Open %1\$s and handle: %2\$s")
                    .let { if (it.contains("%1\$s")) String.format(it, record.packageName, what) else it },
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        str(ctx, "notif_vscreen_handoff_text", "Open %1\$s and handle: %2\$s")
                            .let { if (it.contains("%1\$s")) String.format(it, record.packageName, what) else it },
                    ),
            )
            .setContentIntent(contentIntent(ctx))
            .addAction(0, str(ctx, "notif_vscreen_handoff_action_done", "Done (move it back)"), actionIntent(ctx, record.displayId, ACTION_HANDOFF_DONE, REQ_DONE))
            .addAction(0, str(ctx, "notif_vscreen_handoff_action_keep", "I did it myself"), actionIntent(ctx, record.displayId, ACTION_HANDOFF_KEEP, REQ_KEEP))
            .addAction(0, str(ctx, "notif_vscreen_handoff_action_abort", "Not now"), actionIntent(ctx, record.displayId, ACTION_HANDOFF_ABORT, REQ_ABORT))
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        return runCatching { nm.notify(notificationId(record.displayId), notification) }
            .onFailure { AppLogger.warning(TAG, "notify failed: ${it.message}") }
            .isSuccess
    }

    /** Cancels the handoff notification for [displayId]. */
    fun cancel(ctx: Context, displayId: Int) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(notificationId(displayId)) }
    }

    /** Package-scoped state-change broadcast (informational — readers load from prefs). */
    fun broadcastStateChanged(ctx: Context, displayId: Int, state: HandoffStateMachine.State) {
        runCatching {
            ctx.sendBroadcast(
                Intent(ACTION_STATE_CHANGED)
                    .setPackage(ctx.packageName)
                    .putExtra(EXTRA_DISPLAY_ID, displayId)
                    .putExtra(EXTRA_STATE, state.name),
            )
        }
    }

    private fun actionIntent(ctx: Context, displayId: Int, action: String, reqCode: Int): PendingIntent {
        // Implicit action + package scope: this is what delivers to the
        // RUNTIME-registered receiver. (An explicit component would only
        // resolve against a manifest entry — after the main agent adds the
        // manifest receiver this same PendingIntent reaches both; the state
        // machine rejects the double delivery as NotWaiting, which is inert.)
        val intent = Intent(action).apply {
            putExtra(EXTRA_DISPLAY_ID, displayId)
            setPackage(ctx.packageName)
        }
        return PendingIntent.getBroadcast(
            ctx, reqCode xor displayId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun contentIntent(ctx: Context): PendingIntent {
        val launch = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("minis://chat")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            ctx, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "login" -> "sign in"
        "captcha" -> "solve the captcha"
        "payment" -> "complete the payment"
        "secure_page" -> "view the protected page"
        "choose" -> "make a choice"
        else -> "handle this step"
    }
}
