package com.openminis.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import com.openminis.app.navigation.Routes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.MinisApp
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.settings.KEY_LAUNCH_SESSION
import com.openminis.app.ui.settings.getAppearancePrefs
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.settings.AboutScreen
import com.openminis.app.ui.settings.BackgroundSettingsScreen
import com.openminis.app.ui.sandbox.FileBrowserScreen
import com.openminis.app.ui.sandbox.FileBrowserViewModel
import com.openminis.app.ui.settings.EnvironmentVariablesScreen
import com.openminis.app.ui.settings.AppearanceScreen
import com.openminis.app.ui.settings.SettingsScreen
import com.openminis.app.ui.settings.SkillDetailScreen
import com.openminis.app.ui.settings.SkillFileViewerScreen
import com.openminis.app.ui.settings.UsageStatsScreen
import com.openminis.app.ui.settings.MinisSkillsBrowserScreen
import com.openminis.app.ui.settings.SkillsManagementScreen
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.settings.MemoryFileEditScreen
import com.openminis.app.ui.settings.MemoryManagementScreen
import com.openminis.app.ui.settings.OffloadPermissionScreen
import com.openminis.app.ui.settings.ShizukuPermissionScreen
import com.openminis.app.ui.settings.SuPermissionScreen
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.ui.terminal.TerminalScreen
import com.openminis.app.ui.onboarding.OnboardingModelSelectionScreen


@Composable
internal fun HtmlBrowserHost(state: HtmlBrowserState) {
    val context = LocalContext.current
    val pendingHtml = state.url
    if (pendingHtml != null) {
        val pool = (context.applicationContext as MinisApp).sharedBrowserTabPool
        LaunchedEffect(pendingHtml) { pool.selectOrCreateTabForURL(pendingHtml) }
        BrowserSheet(
            tabPool = pool,
            onDismiss = { state.url = null },
        )
    }
}

/**
 * [T-android-highfreq-read-isolation] Warm-share fallback host: collects
 * [com.openminis.app.share.ShareCoordinator.bufferVersion] and routes a share
 * that lands while the user sits on the session list into a fresh chat.
 * Collecting here (not in AppNavigation's body) means a share tick — which
 * happens on EVERY share, app-wide — recomposes only this child.
 *
 * T185 body unchanged: consumeBuffer is one-shot so a ChatScreen already in
 * the backstack won't double-inject.
 */
@Composable
internal fun AppNavigationShareHost(navController: NavHostController) {
    val shareBufferVersion by com.openminis.app.share.ShareCoordinator.bufferVersion.collectAsStateWithLifecycle()
    LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        val current = navController.currentDestination?.route ?: return@LaunchedEffect
        if (current == Routes.SESSION_LIST) {
            navController.safeNavigate(Routes.chat("__new__${java.util.UUID.randomUUID()}")) {
                popUpTo(Routes.SESSION_LIST) { inclusive = false }
            }
        }
    }
}

/** 文件预览页里的 Markdown 链接：文件用文件预览路由，网页用浏览器路由。 */
internal fun previewLink(navController: NavHostController, target: com.openminis.app.ui.markdown.MarkdownLinkResolver.Target) {
    when (target) {
        is com.openminis.app.ui.markdown.MarkdownLinkResolver.Target.File ->
            navController.safeNavigate(Routes.filePreview(target.absolutePath))
        is com.openminis.app.ui.markdown.MarkdownLinkResolver.Target.Web -> {
            val encoded = java.net.URLEncoder.encode(target.url, "UTF-8")
            navController.safeNavigate("${Routes.HOME_BROWSER}?url=$encoded")
        }
    }
}
