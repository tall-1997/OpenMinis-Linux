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
import com.openminis.app.ui.settings.LogDetailScreen
import com.openminis.app.ui.settings.LogManagementScreen
import com.openminis.app.ui.settings.MemoryFileEditScreen
import com.openminis.app.ui.settings.MemoryManagementScreen
import com.openminis.app.ui.settings.OffloadPermissionScreen
import com.openminis.app.ui.settings.ShizukuPermissionScreen
import com.openminis.app.ui.settings.SuPermissionScreen
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.ui.terminal.TerminalScreen
import com.openminis.app.ui.onboarding.OnboardingModelSelectionScreen

// T342: Material 3 motion easing curves. Compose-Material3 (1.3.x) ships
// `MotionScheme` only in 1.4-alpha; mirror the spec values directly so we
// don't take a dependency-bump tax just for two CubicBezierEasing instances.
// Source: m3.material.io/styles/motion/easing-and-duration/tokens-specs
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

@Composable
fun AppNavigation(
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    envVarRepository: EnvVarRepository? = null,
    skillRepository: SkillRepository? = null,
    mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
    memoryRepository: MemoryRepository? = null,
    navController: NavHostController = rememberNavController(),
    initialDeepLink: DeepLinkAction? = null,
) {
    val context = LocalContext.current

    // T219-5: use the application-scoped singleton from MinisApp so UI
    // add/remove shares state with PRootKernel and the lifecycle re-probe
    // path. Pre-T219-5 this `remember { MountedFoldersStore(...) }` created
    // a SECOND independent instance — UI list updated but PRoot never
    // saw the change because PRootKernel.mountedFoldersStore pointed at
    // the application-scoped singleton in MinisApp.
    val mountedFoldersStore = remember {
        (context.applicationContext as com.openminis.app.MinisApp).mountedFoldersStore
    }

    // Handle initial deep link after composition
    LaunchedEffect(initialDeepLink) {
        when (initialDeepLink) {
            is DeepLinkAction.OpenTerminal -> {
                navController.safeNavigate(Routes.terminal(initialDeepLink.initCommand))
            }
            is DeepLinkAction.OpenSession -> {
                // T-double-chat-fix: on process-death recreation NavController
                // auto-restores [SESSION_LIST, chat/<id>] AND MainActivity
                // synthesizes an OpenSession deep-link from saved state. A
                // bare navigate() pushed a SECOND chat entry, forcing the
                // user to press back twice. launchSingleTop collapses the
                // duplicate; popUpTo(SESSION_LIST, saveState=true) +
                // restoreState=true preserves chat-screen state across the
                // hop. When the synthesized deep-link is a no-op (chat
                // already on top) launchSingleTop short-circuits.
                navController.safeNavigate(Routes.chat(initialDeepLink.sessionId)) {
                    popUpTo(Routes.SESSION_LIST) {
                        inclusive = false
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            is DeepLinkAction.CreateEnvironmentVariable -> {
                DeepLinkCoordinator.setPendingEnvVarCreate(
                    initialDeepLink.key,
                    initialDeepLink.value,
                    initialDeepLink.note,
                )
                navController.safeNavigate(Routes.ENV_VARS)
            }
            // T183: any settings screen reachable by route string; the
            // parser already resolved the path → route mapping.
            is DeepLinkAction.OpenSettingsScreen -> navController.safeNavigate(initialDeepLink.route)
            is DeepLinkAction.OpenPermissionSettings -> {
                navController.safeNavigate(Routes.PERMISSIONS)
            }
            // OpenHtmlPreview is handled by setting startDestination
            // (see below) so the NavHost mounts directly into the right
            // chat — no safeNavigate dance, no sessions-list flash.
            is DeepLinkAction.OpenHtmlPreview -> {}
            // App-icon quick actions: all three open a fresh draft chat.
            // Voice/camera additionally seed DeepLinkCoordinator.pendingChatAction
            // (done up-front in startDestination block below so the seed
            // lands before ChatScreen's first compose).
            is DeepLinkAction.NewChat,
            is DeepLinkAction.NewVoiceChat,
            is DeepLinkAction.NewCameraChat -> {
                // Navigation handled by startDestination = chat/<__new__…>
                // when the launch intent carries one of these actions.
                // Nothing to do here — see startDestination block below.
            }
            else -> {}
        }
    }

    // Resolve launch session preference once into a deferred navigation target.
    // T314: this LaunchedEffect fires the same frame the NavHost mounts, so
    // the SESSION_LIST start-destination's NavBackStackEntry is still in
    // STARTED state when we'd otherwise call navigate(). safeNavigate() —
    // designed to defang the back-then-tap race — early-returns whenever
    // the current entry isn't RESUMED, which silently dropped every
    // launch-session navigation and left the user on the home screen
    // regardless of mode 1 / 2 / 0. Wait for the start destination to
    // settle into RESUMED before navigating; for mode 3 (Home) we don't
    // need to navigate at all so we can skip the wait entirely.
    LaunchedEffect(Unit) {
        val hasDeepLink = initialDeepLink != null && initialDeepLink !is DeepLinkAction.Unknown
        if (hasDeepLink) return@LaunchedEffect
        val hasPendingShare =
            com.openminis.app.share.ShareCoordinator.bufferVersion.value > 0
        // [T-android-sessionlist-prefs-io] The Launch Session preference plus
        // all four circuit breakers are plain SharedPreferences files, and every
        // one of them was loaded synchronously on the main dispatcher — in the
        // effect that decides the first screen the user ever sees. Same storage-
        // stall shape the note measures at 119s; the reads move to
        // Dispatchers.IO and the application context (this coroutine can outlive
        // the Activity it was composed in).
        val (rawMode, mode) = withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val raw = getAppearancePrefs(app).getInt(KEY_LAUNCH_SESSION, 0)
            // Hang-detector circuit breaker: if the previous launches racked up
            // ≥3 main-thread hangs, force mode = 3 (home) so we don't reopen
            // the session/chat that was hanging. The user clears this either
            // by completing a chat session quietly (HangDetector.markHealthyTick
            // on ChatScreen) or by tapping the manual reset in Settings.
            //
            // Crash-frequency circuit breaker: same effect, different trigger.
            // When CrashFrequencyDetector tripped on the previous boot it set
            // a 1-hour force-home grace window — within that window every
            // launch lands on Home regardless of the user's Launch Session
            // preference, even after they dismissed the share dialog. Avoids
            // re-entering the session that may have been the crash trigger.
            //
            // An auto-entered route that did not survive a 60s healthy tick is not
            // entered again. silent_kill and a tombstone-less reboot do not clear
            // that fuse, and there is no "wait for 3 restarts" exception.
            val effective = if (
                com.openminis.app.diagnostics.HangDetector.shouldForceHomeOnLaunch(app) ||
                com.openminis.app.crash.CrashFrequencyDetector.shouldForceHomeOnLaunch(app) ||
                com.openminis.app.diagnostics.LaunchCycleBeacon.shouldForceHomeOnLaunch() ||
                com.openminis.app.diagnostics.RouteFuse.blocksAnyUnhealthy(app)
            ) 3 else raw
            raw to effective
        }
        val autoThresholdMs = 15L * 60 * 1000
        // [T-android-startup-splash-hang] The workspace-owner warmup no longer
        // blocks Application.onCreate, so the ordering guarantee it used to
        // provide ("populated before the first file tool or shell") is this
        // await. It sits before any navigation, so no chat screen can mount —
        // and therefore no shell or file tool can resolve a workspace path —
        // until the folder map is loaded. Bounded and normally instantaneous:
        // the warmup is one indexed two-column query.
        com.openminis.app.sandbox.SessionWorkspace.awaitWarmup()
        // [T-android-first-launch-lands-home] Read once, before the mode
        // branches: "is this device set up at all?" is the question that
        // outranks the launch preference.
        //
        // [T-android-startup-splash-hang] `hasAnySession` was
        // `listSessions().isNotEmpty()` — a full hydration of the widest table
        // in the schema, including every session's `last_message` preview, to
        // answer a yes/no question. This resolver ran that same full query up to
        // three times per cold start (emptiness, then `firstOrNull()` for the
        // target session), all on the main-dispatcher LaunchedEffect.
        val hasAnySession = chatRepository.dao.hasAnySession()
        val hasAnyProvider = providerRepository.instances.isNotEmpty()
        // [XSessionDiag] Hypothesis 1 context: which launch mode actually applied.
        // rawMode is the user's preference (0 = auto, the default); mode is what
        // survived the force-home circuit breakers. Without this line a log that
        // lacks the "launch/auto" entry below is ambiguous — it could mean the
        // auto branch was not taken, or that this whole resolver never ran.
        com.openminis.app.logging.AppLogger.info(
            "XSessionDiag",
            "[XSessionDiag] launch/mode: rawMode=$rawMode effectiveMode=$mode " +
                "hasPendingShare=$hasPendingShare hasAnySession=$hasAnySession " +
                "hasAnyProvider=$hasAnyProvider",
        )
        val target: String? = when {
            // T185: if a system share is buffered and the configured launch
            // mode would leave us on the session list (mode 3 = Home), the
            // ChatScreen consumer never mounts and the buffer expires —
            // exactly the symptom from the bug report. Force a new draft
            // chat so the share lands in a composer.
            hasPendingShare && mode == 3 -> Routes.chat("__new__${java.util.UUID.randomUUID()}")
            // [T-android-first-launch-lands-home] Nothing configured AND no
            // history: land on Home whatever the mode says. Mode 2 ("always a
            // new chat") is a real preference, but on a device with no provider
            // it opens a composer that cannot send — and hides the onboarding
            // steps that explain why. Once the user has either a session or a
            // provider, their preference is honoured again.
            !hasAnySession && !hasAnyProvider -> null
            mode == 1 -> chatRepository.dao.newestSession()?.let { Routes.chat(it.id) }
            mode == 2 -> Routes.chat("__new__${java.util.UUID.randomUUID()}")
            mode == 3 -> null
            else -> {
                // [T-android-first-launch-lands-home] No history → Home, not a
                // fresh draft. Auto mode is the DEFAULT, and it used to fall
                // through to a new chat here: a device with providers but no
                // sessions yet opened straight into an empty composer instead
                // of the list. "Resume what I was doing" has nothing to resume
                // when nothing was ever done.
                val latest = chatRepository.dao.newestSession()
                    ?: return@LaunchedEffect
                val fresh = System.currentTimeMillis() - latest.updatedAt < autoThresholdMs
                // [XSessionDiag] Hypothesis 1: auto mode silently RESUMES the most
                // recently updated session, so a restored backup whose updatedAt is
                // the iPhone's own timestamp can look "fresh" and be presented as
                // the landing chat — the user believes they are in a new chat.
                // Logs the exact inputs to the freshness test and where it navigated.
                // Runs once per cold start; no measurable cost.
                run {
                    val now = System.currentTimeMillis()
                    com.openminis.app.logging.AppLogger.info(
                        "XSessionDiag",
                        "[XSessionDiag] launch/auto: candidate=${latest.id.take(8)} " +
                            "title=${latest.title?.take(24)} " +
                            "updatedAt=${latest.updatedAt} now=$now " +
                            "ageMs=${now - latest.updatedAt} thresholdMs=$autoThresholdMs " +
                            "fresh=$fresh -> ${if (fresh) "RESUME_EXISTING" else "NEW_DRAFT"}",
                    )
                }
                if (fresh) Routes.chat(latest.id) else Routes.chat("__new__${java.util.UUID.randomUUID()}")
            }
        }
        if (target != null) {
            val autoSession = target.removePrefix("chat/").substringBefore("?")
            if (autoSession.isNotEmpty() && !autoSession.startsWith("__new__")) {
                com.openminis.app.diagnostics.RouteFuse.noteAutoEnter(context, autoSession)
            }
            // T314: navigate directly without the safeNavigate guard.
            // safeNavigate exists to defang back-then-tap-settings races
            // where a popped destination is mid-tear-down — it requires
            // RESUMED to be safe. But this LaunchedEffect fires on the
            // first composition pass, when the start destination is
            // STARTED but not yet RESUMED. There's no race here: we're
            // the deterministic startup dispatcher, the start destination
            // hasn't even rendered, and we want to navigate to it BEFORE
            // it shows. Calling navigate() unconditionally produces the
            // intended cold-start dispatch (mode 1 → last session, mode 2
            // → new chat, mode 0 → auto). Pre-T314 the safeNavigate guard
            // silently dropped this navigation and stranded the user on
            // the SESSION_LIST start destination regardless of mode.
            navController.navigate(target) {
                popUpTo(Routes.SESSION_LIST) { inclusive = false }
            }
        }
    }

    // T185: warm-share fallback — if a share lands while the user is sitting
    // on the session list (cold-start with mode 3 that raced past the launch
    // resolver, or onNewIntent re-fires processPendingShare), route into a
    // fresh chat so ChatScreen's existing LaunchedEffect(shareBufferVersion)
    // can drain it. The drain itself is idempotent: consumeBuffer is one-shot,
    // so a ChatScreen already in the backstack won't double-inject.
    //
    // [T-android-highfreq-read-isolation] The collect lives in a dedicated
    // child composable now: ShareCoordinator.bufferVersion ticks on EVERY
    // share, and collecting it in AppNavigation's body re-invalidated this
    // whole 1.5k-line function (and with it the NavHost call) per share.
    // AppNavigationShareHost reads the flow internally, so a share tick
    // recomposes only that child.
    AppNavigationShareHost(navController)

    // Pinned-shortcut / quick-action cold start: start NavHost directly at
    // the matching chat so ChatScreen's LaunchedEffect consumes the pending
    // state on first composition — no sessions-list flash, no launch-session
    // preference detour, no duplicate back-stack entry.
    //
    // [T-android-deeplink-reseed] Both seeds used to run in the COMPOSITION
    // BODY. DeepLinkCoordinator's pending slots are one-shot — consume*() puts
    // them back to null — so once ChatScreen drained one, any later
    // recomposition of AppNavigation RE-SEEDED it and the preview sheet (or a
    // seeded quick-action) popped open again by itself. AppNavigation used to
    // recompose readily — `htmlBrowserUrl` flipped when an in-app file preview
    // opened/closed, and ShareCoordinator.bufferVersion ticked on every share
    // (both isolated into child composables since, see
    // [T-android-highfreq-read-isolation]) — and remember(initialDeepLink)
    // remains the guard that runs this block exactly once per deep link,
    // which also freezes the `__new__<uuid>` draft id.
    val startDestination = remember(initialDeepLink) {
        val htmlShortcut = initialDeepLink as? DeepLinkAction.OpenHtmlPreview
        val quickActionStart: String? = when (initialDeepLink) {
            is DeepLinkAction.NewVoiceChat -> {
                DeepLinkCoordinator.setPendingChatAction(DeepLinkCoordinator.ChatAction.START_VOICE)
                Routes.chat("__new__${java.util.UUID.randomUUID()}")
            }
            is DeepLinkAction.NewCameraChat -> {
                DeepLinkCoordinator.setPendingChatAction(DeepLinkCoordinator.ChatAction.OPEN_CAMERA)
                Routes.chat("__new__${java.util.UUID.randomUUID()}")
            }
            is DeepLinkAction.NewChat -> Routes.chat("__new__${java.util.UUID.randomUUID()}")
            else -> null
        }
        when {
            htmlShortcut != null -> {
                // Seed coordinator before NavHost composition so ChatScreen sees
                // the pending state on its very first LaunchedEffect tick.
                DeepLinkCoordinator.setPendingHtmlPreview(
                    htmlShortcut.sessionId,
                    htmlShortcut.resourcePath,
                    htmlShortcut.title,
                )
                Routes.chat(htmlShortcut.sessionId)
            }
            quickActionStart != null -> quickActionStart
            else -> Routes.SESSION_LIST
        }
    }
    // [T-android-highfreq-read-isolation] The in-app HTML preview URL used to
    // be a `mutableStateOf` remembered in THIS body, which meant every open/
    // close of a file preview recomposed all of AppNavigation (and re-ran the
    // NavHost call). The state now lives in [HtmlBrowserState]: the FILE_BROWSER
    // / CHAT_FILES closures only WRITE it (writes do not subscribe), and the
    // reader — [HtmlBrowserHost] at the bottom — subscribes internally. A
    // preview open/close now invalidates just the host child.
    val htmlBrowserState = remember { HtmlBrowserState() }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // T153: paint the in-app theme color underneath every transition
        // frame. Without this the NavHost's transition surface is
        // transparent and the window background bleeds through during
        // enter/exit animations — on dark mode the base Light window
        // background flashes white between screens. Tying the host to
        // the active Material colorScheme also keeps the first frame
        // correct on cold start.
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        // T342: Material 3 motion — shared-axis X transition.
        // Spec (m3.material.io/styles/motion/transitions):
        //   - enter uses EmphasizedDecelerate (cubic-bezier 0.05, 0.7, 0.1, 1.0)
        //     so the new destination eases in confidently
        //   - exit uses EmphasizedAccelerate (cubic-bezier 0.3, 0.0, 0.8, 0.15)
        //     so the leaving destination clears out fast
        //   - both legs together feel like a single 300ms motion (200ms
        //     exit overlapping 300ms enter), short enough that a rapid second
        //     tap still lands on the next destination once safeNavigate's
        //     RESUMED guard releases
        //   - the small slide distance (~SlideDirection default ≈ container
        //     width ÷ N — Compose's slideIntoContainer already picks a
        //     subtle distance) plus fade reads as a single coordinated
        //     motion rather than a hard cut, matching Settings/Files in
        //     Material You system apps.
        // RESUMED guard via safeNavigate + the colorScheme.background
        // modifier above (T333) still defend against rapid-tap white
        // flashes; the slightly longer enter spec (300ms vs 220ms) is
        // covered by the same guard.
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(300, easing = EmphasizedDecelerate),
            ) + fadeIn(animationSpec = tween(300, easing = EmphasizedDecelerate))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(200, easing = EmphasizedAccelerate),
            ) + fadeOut(animationSpec = tween(200, easing = EmphasizedAccelerate))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(300, easing = EmphasizedDecelerate),
            ) + fadeIn(animationSpec = tween(300, easing = EmphasizedDecelerate))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(200, easing = EmphasizedAccelerate),
            ) + fadeOut(animationSpec = tween(200, easing = EmphasizedAccelerate))
        },
    ) {
        // [T-android-tablet-split] SESSION_LIST and CHAT both render the same
        // ChatSplitScaffold; they differ only in which session it opens with.
        //
        // Keeping BOTH routes (rather than collapsing to one) is deliberate:
        // `Routes.chat(id)` is navigated to from many places — deep links,
        // share handoff, Move-to, the widget, notifications — and every one of
        // them keeps working unchanged. On a phone the scaffold resolves to a
        // single pane, so those pushes look and behave exactly as before.
        composable(Routes.SESSION_LIST) {
            ChatSplitScaffoldRoute(
                initialSessionId = null,
                navController = navController,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            ChatSplitScaffoldRoute(
                initialSessionId = sessionId,
                navController = navController,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.safePopBackStack() },
                onProvidersClick = { navController.safeNavigate(Routes.PROVIDER_LIST) },
                onModelGroupsClick = { navController.safeNavigate(Routes.MODEL_GROUPS) },
                onRootfsClick = { navController.safeNavigate(Routes.STORAGE) },
                onBackupClick = { navController.safeNavigate(Routes.BACKUP) },
                onEnvVarsClick = { navController.safeNavigate(Routes.ENV_VARS) },
                onSkillsClick = { navController.safeNavigate(Routes.SKILLS) },
                onMultiAgentClick = { navController.safeNavigate(Routes.MULTI_AGENT) },
                onTerminalClick = { navController.safeNavigate(Routes.terminal()) },
                onMemoryClick = { navController.safeNavigate(Routes.MEMORY) },
                onMcpClick = { navController.safeNavigate(Routes.MCP) },
                onPluginMarketClick = { navController.safeNavigate(Routes.PLUGIN_MARKET) },
                onSoulClick = { navController.safeNavigate(Routes.SOUL) },
                onEvolutionClick = { navController.safeNavigate(Routes.EVOLUTION) },
                onPermissionsClick = { navController.safeNavigate(Routes.PERMISSIONS) },
                onUsageClick = { navController.safeNavigate(Routes.USAGE_STATS) },
                onAppearanceClick = { navController.safeNavigate(Routes.APPEARANCE) },
                onBackgroundClick = { navController.safeNavigate(Routes.BACKGROUND) },
                onWebSearchClick = { navController.safeNavigate(Routes.WEB_SEARCH) },
                onCharacterExtrasClick = { navController.safeNavigate(Routes.CHARACTER_EXTRAS) },
                onTranslateClick = { navController.safeNavigate(Routes.TRANSLATE) },
                onOpenBrowser = { navController.safeNavigate(Routes.HOME_BROWSER) },
                onBrowserSettings = { navController.safeNavigate(Routes.HOME_BROWSER_SETTINGS) },

                onLogsClick = { navController.safeNavigate(Routes.LOGS) },
                onAboutClick = { navController.safeNavigate(Routes.ABOUT) },
                onMountedFoldersClick = { navController.safeNavigate(Routes.MOUNTED_FOLDERS) },
                onSharedFoldersClick = { navController.safeNavigate(Routes.SHARED_FOLDERS) },
                onA2aClick = { navController.safeNavigate(Routes.A2A) },
            )
        }

        // [T-android-a2a-native] The two agent gateways (see GatewayRoutes.kt).
        gatewayRoutes(navController)

        // [T-android-nav-split] Destination groups with no cross-domain
        // coupling live in their own NavGraphBuilder extensions (the
        // automationRuleEntries pattern): BackupNavGraph.kt, ProviderNavGraph.kt
        // and StorageNavGraph.kt. Route strings are byte-identical to the
        // pre-split registrations and the composable bodies moved as-is.
        backupRoutes(navController)
        providerRoutes(navController, providerRepository)
        storageRoutes(navController, chatRepository, mountedFoldersStore)

        composable(Routes.FILE_BROWSER) { entry ->
            // [T-android-filepreview-holder-lifetime] Released when this
            // destination leaves the stack — see FilePreviewHolder.
            DisposableEffect(entry) {
                onDispose { FilePreviewHolder.fileBrowserViewModel = null }
            }
            // A null VM here means the process was recreated with this entry
            // already on the stack (process statics do not survive); leave
            // rather than park the user on a blank screen, matching the other
            // destinations that depend on a handed-over argument.
            val vm = FilePreviewHolder.fileBrowserViewModel
            if (vm == null) {
                LaunchedEffect(Unit) { navController.safePopBackStack() }
            } else {
                FileBrowserScreen(
                    viewModel = vm,
                    onBack = { navController.safePopBackStack() },
                    onPreviewFile = { item ->
                        if (item.isHtmlFile) {
                            htmlBrowserState.url = "file://${item.file.absolutePath}"
                        } else {
                            FilePreviewHolder.currentItem = item
                            navController.safeNavigate(Routes.filePreview(item.file.absolutePath))
                        }
                    },
                )
            }
        }

        // Browse Chat Files (iOS parity: open FileBrowser rooted at the full
        // Linux root, focused on /var/minis. Matches AIChatView.swift L490:
        //   FileBrowserView(rootPath: dataPath, initialPath: dataPath/var/minis,
        //                   rootLabel: "/")
        // so the user can navigate up out of /var/minis into the broader rootfs.
        composable(
            route = Routes.CHAT_FILES,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val context = androidx.compose.ui.platform.LocalContext.current
            val rootfs = RootfsManager.getInstance(context.applicationContext)
            val varMinis = java.io.File(rootfs.rootfsDir, "var/minis")
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val vm = remember(rootfs.rootfsDir.absolutePath, varMinis.absolutePath, sessionId) {
                FileBrowserViewModel(
                    rootPath = rootfs.rootfsDir,
                    initialPath = varMinis.takeIf { it.exists() },
                    rootLabel = "/",
                    // T121: route directory listings through PRootKernel bind
                    // mounts so /var/minis/{skills,memory,shared} resolve to
                    // their backing host dirs (filesDir/minis-global/<subdir>).
                    // Without this the browser walks the rootfs tarball
                    // directly and shows the empty placeholder dirs that ship
                    // inside Alpine's var/minis/ — every subdir reads as
                    // "Empty folder" even though the agent has files there.
                    linuxRootPath = "/",
                    // T147: scope per-session subdirs (attachments / workspace
                    // / offloads / browser) to THIS chat's host dir even when
                    // another session was the last to boot a PRoot — that
                    // global bindMounts state is last-writer-wins and would
                    // otherwise hide the agent's generated files for the
                    // session the user is looking at.
                    sessionId = sessionId,
                    appContext = context.applicationContext,
                )
            }
            FileBrowserScreen(
                viewModel = vm,
                onBack = { navController.safePopBackStack() },
                onPreviewFile = { item ->
                    if (item.isHtmlFile) {
                        htmlBrowserState.url = "file://${item.file.absolutePath}"
                    } else {
                        FilePreviewHolder.currentItem = item
                        navController.safeNavigate(Routes.filePreview(item.file.absolutePath))
                    }
                },
            )
        }

        // Rendered as a Dialog destination (not a `composable`) so the
        // underlying screen — typically ChatScreen — stays in the composition
        // while preview is open. With `composable()`, NavHost unmounts the
        // previous entry, which detaches the chat LazyColumn from layout;
        // when the user pops back, LazyListState re-anchors to (0, 0) and
        // the user loses their scroll position (plus a white-flash on the
        // first frame before the list remeasures). `dialog()` keeps the
        // back entry's composition alive — listState retains both
        // firstVisibleItemIndex/Offset and its layoutInfo cache, so the
        // chat paints its previous viewport on the first frame after pop.
        // `usePlatformDefaultWidth=false` lets the dialog fill the screen
        // edge-to-edge, matching the prior full-screen composable feel;
        // `decorFitsSystemWindows=false` lets FilePreviewScreen handle its
        // own insets exactly like before.
        dialog(
            route = Routes.FILE_PREVIEW,
            dialogProperties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
            arguments = listOf(
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry -> FilePreviewDestination(entry, navController) }

        composable(Routes.ENV_VARS) {
            if (envVarRepository != null) {
                EnvironmentVariablesScreen(
                    envVarRepository = envVarRepository,
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.MULTI_AGENT) {
            com.openminis.app.ui.settings.MultiAgentSettingsScreen(
                onBack = { navController.safePopBackStack() },
                onOpenToolLimits = { navController.safeNavigate(Routes.TOOL_LIMITS) },
            )
        }

        composable(Routes.TOOL_LIMITS) {
            com.openminis.app.ui.settings.ToolLimitsSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.SKILLS) {
            if (skillRepository != null) {
                SkillsManagementScreen(
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                    onSkillClick = { skillId -> navController.safeNavigate(Routes.skillDetail(skillId)) },
                    onMinisSkillsClick = { navController.safeNavigate(Routes.MINIS_SKILLS_BROWSER) },
                )
            }
        }

        composable(
            route = Routes.SKILL_DETAIL,
            arguments = listOf(navArgument("skillId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
            if (skillRepository != null) {
                SkillDetailScreen(
                    skillId = skillId,
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                    onFileClick = { id, relativePath ->
                        navController.safeNavigate(Routes.skillFile(id, relativePath))
                    },
                )
            }
        }

        composable(
            route = Routes.SKILL_FILE,
            arguments = listOf(
                navArgument("skillId") { type = NavType.StringType },
                navArgument("relativePath") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
            // [T-android-double-decode-percent-crash] java.net.URLDecoder throws
            // IllegalArgumentException on a bare '%'. Routes.skillFile URL-encodes
            // the path once, but NavType.StringType.parseValue() already decodes
            // the segment back, so this value is the plain relative path — a
            // second decode was both redundant and a guaranteed crash on a
            // legitimately named file ("100%_done.md" came back as a composable
            // exception on every open, since URLDecoder rejects the stray '%').
            val relativePath = backStackEntry.arguments?.getString("relativePath") ?: "SKILL.md"
            if (skillRepository != null) {
                SkillFileViewerScreen(
                    skillId = skillId,
                    relativePath = relativePath,
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.MINIS_SKILLS_BROWSER) {
            if (skillRepository != null) {
                MinisSkillsBrowserScreen(
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(
            Routes.TERMINAL,
            arguments = listOf(
                androidx.navigation.navArgument("initCommand") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                androidx.navigation.navArgument("sessionId") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val context = androidx.compose.ui.platform.LocalContext.current
            val initCommand = backStackEntry.arguments?.getString("initCommand")
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            val session = remember { TerminalSession(context.applicationContext) }
            TerminalScreen(
                terminalSession = session,
                onBack = { navController.safePopBackStack() },
                initCommand = initCommand,
                sessionId = sessionId,
            )
        }

        // [P2-git-panel] Session workspace Git panel: changes / commit with
        // AI-generated message / history / diff viewer.
        composable(
            Routes.GIT_PANEL,
            arguments = listOf(
                androidx.navigation.navArgument("sessionId") {
                    type = androidx.navigation.NavType.StringType
                },
            ),
        ) { backStackEntry ->
            val gitSessionId = backStackEntry.arguments?.getString("sessionId")
                ?: return@composable
            com.openminis.app.ui.git.GitPanelRoute(
                sessionId = gitSessionId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.MEMORY) {
            if (memoryRepository != null) {
                MemoryManagementScreen(
                    memoryRepository = memoryRepository,
                    onBack = { navController.safePopBackStack() },
                    onFileClick = { fileName, isGlobal ->
                        navController.safeNavigate(Routes.memoryFileEdit(fileName, isGlobal))
                    },
                    onEvolutionClick = { navController.safeNavigate(Routes.EVOLUTION) },
                )
            }
        }

        // [T-mcp-integration-android] MCP Integrations management screen.
        composable(Routes.MCP) {
            if (mcpRepository != null) {
                com.openminis.app.ui.settings.MCPIntegrationsScreen(
                    mcpRepository = mcpRepository,
                    onBack = { navController.safePopBackStack() },
                    envVarRepository = envVarRepository,
                    onOpenTools = { server -> navController.safeNavigate(Routes.mcpTools(server.id)) },
                )
            }
        }

        composable(
            Routes.MCP_TOOLS,
            arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
        ) { entry ->
            val repo = mcpRepository
            // StringType already Uri.decode()s the segment (see
            // [T-android-double-decode-percent-crash] above), so the second
            // manual Uri.decode here only mangled server ids carrying a real
            // '%'. Routes.mcpTools' Uri.encode is undone by the nav argument.
            val serverId = entry.arguments?.getString("serverId").orEmpty()
            if (repo == null) {
                LaunchedEffect(serverId) { navController.safePopBackStack() }
            } else {
                com.openminis.app.ui.settings.MCPToolsScreen(
                    mcpRepository = repo,
                    serverId = serverId,
                    onBack = { navController.safePopBackStack() },
                    onManage = { navController.safePopBackStack() },
                )
            }
        }
        composable(Routes.PLUGIN_MARKET) {
            com.openminis.app.ui.settings.PluginMarketScreen(
                mcpRepository = mcpRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        // [T-soul-md] SOUL.md editor.
        composable(Routes.SOUL) {
            com.openminis.app.ui.settings.SoulSettingsScreen(
                onBack = { navController.safePopBackStack() },
                providerRepository = providerRepository,
            )
        }

        composable(Routes.EVOLUTION) {
            com.openminis.app.ui.settings.EvolutionSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.MEMORY_FILE_EDIT,
            arguments = listOf(
                navArgument("fileName") { type = NavType.StringType },
                navArgument("isGlobal") { type = NavType.BoolType },
            ),
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
            val isGlobal = backStackEntry.arguments?.getBoolean("isGlobal") ?: false
            if (memoryRepository != null) {
                MemoryFileEditScreen(
                    fileName = fileName,
                    isGlobal = isGlobal,
                    memoryRepository = memoryRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.PERMISSIONS) {
            OffloadPermissionScreen(
                onBack = { navController.safePopBackStack() },
                // [T-android-privileged-backend] Single Shizuku-protocol screen
                // handles both Shizuku and AXManager managers; the old
                // multi-backend screen was retired in favour of a single
                // surface (the two managers share one binder slot, so a
                // multi-backend abstraction was misleading).
                onOpenPrivilegedBackend = { navController.safeNavigate(Routes.SHIZUKU) },
                onOpenHostSu = { navController.safeNavigate(Routes.HOST_SU) },
            )
        }

        composable(Routes.HOST_SU) {
            SuPermissionScreen(
                onBack = { navController.safePopBackStack() },
                onOpenShizuku = { navController.safeNavigate(Routes.SHIZUKU) },
            )
        }

        composable(Routes.SHIZUKU) {
            ShizukuPermissionScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.USAGE_STATS) {
            // collectAsState, NOT `.value`: this reads a StateFlow inside the
            // composition body, so nothing observes it and the screen kept
            // showing the config that happened to be loaded when it first
            // composed — usually the empty one, since the async config load is
            // exactly what [T-android-startup-config-stall] describes.
            val providerConfig by providerRepository.config.collectAsStateWithLifecycle()
            UsageStatsScreen(
                chatDao = chatRepository.dao,
                providerConfig = providerConfig,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.APPEARANCE) {
            AppearanceScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.BACKGROUND) {
            BackgroundSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.WEB_SEARCH) {
            com.openminis.app.ui.settings.WebSearchSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.CHARACTER_EXTRAS) {
            com.openminis.app.ui.settings.CharacterExtrasScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.TRANSLATE) {
            com.openminis.app.ui.settings.TranslationSettingsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onOpenPad = { navController.safeNavigate(Routes.TRANSLATE_PAD) },
            )
        }
        composable(Routes.TRANSLATE_PAD) {
            com.openminis.app.ui.settings.TranslateScreen(
                onBack = { navController.safePopBackStack() },
            )
        }
        composable(
            // url 可选：设置页直接导航到 HOME_BROWSER，不带参数。
            "${Routes.HOME_BROWSER}?url={url}",
            arguments = listOf(navArgument("url") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { entry ->
            HomeBrowserDestination(entry, navController)
        }
        composable(Routes.HOME_BROWSER_SETTINGS) {
            com.openminis.app.ui.browser.HomeBrowserRoute(
                settings = true,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.safePopBackStack() })
        }

        composable(Routes.LOGS) {
            LogManagementScreen(
                onBack = { navController.safePopBackStack() },
                onLogFileClick = { fileName ->
                    navController.safeNavigate(Routes.logDetail(fileName))
                },
            )
        }

        composable(
            route = Routes.LOG_DETAIL,
            arguments = listOf(navArgument("fileName") { type = NavType.StringType }),
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
            LogDetailScreen(
                fileName = fileName,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ONBOARDING_MODELS) {
            OnboardingModelSelectionScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        // [T-android-scheduled-tasks-design] Scheduled tasks list + editor.
        // [T-android-automation-rules] The screen is the Automation hub now:
        // Timers tab (legacy cron flow) + Rules tab (conditional triggers),
        // with rule editor / run-records destinations below.
        composable(Routes.SCHEDULED_TASKS) {
            com.openminis.app.ui.scheduled.ScheduledTasksScreen(
                onBack = { navController.safePopBackStack() },
                onEditTask = { taskId ->
                    navController.safeNavigate(Routes.scheduledTaskEdit(taskId))
                },
                onViewRuns = { taskId ->
                    navController.safeNavigate(Routes.scheduledTaskRuns(taskId))
                },
                onOpenSession = { sessionId ->
                    navController.safeNavigate(Routes.chat(sessionId))
                },
                onEditRule = { ruleId ->
                    navController.safeNavigate(automationRuleEdit(ruleId))
                },
                onViewRuleRuns = { ruleId ->
                    navController.safeNavigate("automation_rules/runs/$ruleId")
                },
            )
        }
        // [T-android-automation-rules] Rule editor + per-rule run records.
        automationRuleEntries(navController)
        // [T-android-scheduled-tasks-run-records] per-task run records.
        composable(
            route = Routes.SCHEDULED_TASK_RUNS,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId") ?: return@composable
            com.openminis.app.ui.scheduled.ScheduledTaskRunsScreen(
                taskId = taskId,
                onBack = { navController.safePopBackStack() },
                onOpenSession = { sessionId ->
                    navController.safeNavigate(Routes.chat(sessionId))
                },
            )
        }
        composable(
            route = Routes.SCHEDULED_TASK_EDIT,
            arguments = listOf(
                navArgument("taskId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId")
            com.openminis.app.ui.scheduled.ScheduledTaskEditScreen(
                taskId = taskId,
                onBack = { navController.safePopBackStack() },
                onOpenSession = { sessionId ->
                    navController.safeNavigate(Routes.chat(sessionId))
                },
            )
        }
    }

    // [T-android-highfreq-read-isolation] The whole BrowserSheet host moved
    // into the child composable below; AppNavigation no longer reads the
    // preview state, so a preview open/close does not re-run this body.
    HtmlBrowserHost(htmlBrowserState)
}

/**
 * [T-android-highfreq-read-isolation] Holder for the in-app HTML preview URL
 * (the old `htmlBrowserUrl` state). Producers (FILE_BROWSER / CHAT_FILES
 * closures) only WRITE `url` — a snapshot write from a callback does not
 * subscribe the writer to the state — and the single reader lives in
 * [HtmlBrowserHost], so a preview open/close invalidates exactly that child
 * composable instead of the whole AppNavigation body (which re-ran the
 * NavHost call and every destination lambda recreation before).
 */
private class HtmlBrowserState {
    var url by androidx.compose.runtime.mutableStateOf<String?>(null)
}

/**
 * [T-android-highfreq-read-isolation] Host for the in-app HTML preview: reads
 * [HtmlBrowserState.url], opens the shared-pool [BrowserSheet] for it and
 * clears the state on dismiss. Same behavior as the previous inline tail
 * block, just isolated so only this composable recomposes on url flips.
 */
@Composable
private fun HtmlBrowserHost(state: HtmlBrowserState) {
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
private fun AppNavigationShareHost(navController: NavHostController) {
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
