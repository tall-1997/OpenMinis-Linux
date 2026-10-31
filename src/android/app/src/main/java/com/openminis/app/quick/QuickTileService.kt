package com.openminis.app.quick

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * [E-batch-quick-invocation] Quick-settings tile: taps open the half-screen
 * quick panel. The panel itself decides whether to reopen the last session,
 * so the tile stays stateless.
 *
 * The tile is stateless and always available: sending needs no special
 * grants, and the session/permission checks happen on send.
 */
class QuickTileService : TileService() {

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(this, QuickActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The PendingIntent overload is API 34+; the Intent overload throws on
        // 34+ but is the only one before it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
