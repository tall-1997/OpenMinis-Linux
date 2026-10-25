package com.openminis.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.openminis.app.MainActivity
import com.openminis.app.R

/**
 * Home-screen 1×2 widget that opens a new chat (Operit-style launcher entry,
 * no LSPosed). Uses the existing minis://action/new_chat deep link.
 */
class MinisChatWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("minis://action/new_chat")).apply {
            setClass(context, MainActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(context, 0, intent, flags)
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_minis_chat)
            views.setOnClickPendingIntent(R.id.widget_minis_root, pending)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
