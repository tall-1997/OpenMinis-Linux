package com.openminis.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.openminis.app.ui.browser.HomeBrowserRoute

/** 应用内浏览器的导航目标。抽出来让 AppNavigation 不超上限。 */
@Composable
fun HomeBrowserDestination(entry: NavBackStackEntry, navController: NavHostController) {
    val url = entry.arguments?.getString("url")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    HomeBrowserRoute(settings = false, initialUrl = url, onBack = { navController.safePopBackStack() })
}
