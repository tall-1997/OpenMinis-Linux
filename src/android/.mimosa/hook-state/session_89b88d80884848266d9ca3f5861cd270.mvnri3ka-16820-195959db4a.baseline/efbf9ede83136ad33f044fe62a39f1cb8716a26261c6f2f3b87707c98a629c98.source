package com.openminis.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.openminis.app.ui.sandbox.FilePreviewScreen

/** 文件预览的导航目标。FilePreviewHolder / rememberFilePreviewItem 与本文件同包。 */
@Composable
fun FilePreviewDestination(entry: NavBackStackEntry, navController: NavHostController) {
    DisposableEffect(entry) { onDispose { FilePreviewHolder.currentItem = null } }
    val item = rememberFilePreviewItem(navController, entry)
    if (item != null) {
        FilePreviewScreen(
            item = item,
            onBack = { navController.safePopBackStack() },
            onOpenLink = { previewLink(navController, it) },
        )
    }
}
