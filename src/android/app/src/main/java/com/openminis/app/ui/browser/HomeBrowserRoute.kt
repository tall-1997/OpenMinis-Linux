package com.openminis.app.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.browser.BrowserTabPool

/** Settings entry for the home browser sheet and its settings sheet. */
@Composable
fun HomeBrowserRoute(
    settings: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val pool = remember { BrowserTabPool(context) }
    androidx.compose.runtime.DisposableEffect(pool) {
        pool.isVisible = true
        onDispose { pool.dispose() }
    }
    LaunchedEffect(Unit) { pool.ensureTabForUI() }
    if (settings) {
        BrowserSettingsSheet(tabPool = pool, onDismiss = onBack)
    } else {
        BrowserSheet(tabPool = pool, onDismiss = onBack)
    }
}
