// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens a sign-in's authorize page in a Custom Tab (title shown) - the one
 * launch every browser sign-in shares (OpenAI, Claude, xAI, OpenRouter, MCP).
 *
 * Launched from the caller's context WITHOUT FLAG_ACTIVITY_NEW_TASK, so from
 * an Activity the tab sits above singleTask MainActivity in MainActivity's own
 * task. OAuthRedirectActivity relies on that (T-oauth-return-to-app): when the
 * browser hands the redirect to the app it brings MainActivity forward with
 * CLEAR_TOP, which closes the tab only because the tab is in that task.
 * [newTask] is for callers that may hold a non-Activity context.
 *
 * No CustomTabsSession / bindCustomTabsService here on purpose: a session was
 * only ever used to observe navigation events for diagnostics, and binding it
 * delayed opening the tab by up to a second.
 */
object OAuthTabLauncher {
    fun launch(context: Context, uri: Uri, newTask: Boolean = false) {
        val tabs = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        if (newTask) tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tabs.launchUrl(context, uri)
    }
}
