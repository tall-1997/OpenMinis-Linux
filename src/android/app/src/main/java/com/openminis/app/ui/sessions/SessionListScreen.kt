package com.openminis.app.ui.sessions

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import android.content.Intent
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.openminis.app.service.SessionActivityTracker
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.minisFabColor
import com.openminis.app.ui.theme.minisFabContentColor
import com.openminis.app.ui.theme.m3Icon
import com.openminis.app.ui.theme.monetIconTint
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.sessionListEntryRevealed
import com.openminis.app.data.repository.sessionListStallSpinner
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit


internal data class CategoryStyle(val icon: ImageVector, val color: Color)

// 16 categories matching iOS (ContentView.swift:1897-1916)
internal fun categoryStyle(category: String?): CategoryStyle {
    return when (category?.lowercase()) {
        "code"         -> CategoryStyle(Icons.Outlined.Code, Color(0xFFF09A37))
        "writing"      -> CategoryStyle(Icons.Outlined.Description, Color(0xFF3478F6))
        "research"     -> CategoryStyle(Icons.Outlined.Language, Color(0xFF30B0C7))
        "analysis"     -> CategoryStyle(Icons.Outlined.BarChart, Color(0xFF5856D6))
        "creative"     -> CategoryStyle(Icons.Outlined.Brush, Color(0xFFFF2D55))
        "chat"         -> CategoryStyle(Icons.Outlined.Forum, Color(0xFF34C759))
        "math"         -> CategoryStyle(Icons.Outlined.Calculate, Color(0xFF9B59B6))
        "translation"  -> CategoryStyle(Icons.Outlined.Translate, Color(0xFF00BCD4))
        "health"       -> CategoryStyle(Icons.Outlined.Favorite, Color(0xFFFF3B30))
        "finance"      -> CategoryStyle(Icons.Outlined.Payments, Color(0xFF00C7BE))
        "travel"       -> CategoryStyle(Icons.Outlined.Map, Color(0xFFF09A37))
        "education"    -> CategoryStyle(Icons.Outlined.Book, Color(0xFF3478F6))
        "design"       -> CategoryStyle(Icons.Outlined.Palette, Color(0xFFFF2D55))
        "productivity" -> CategoryStyle(Icons.Outlined.CalendarMonth, Color(0xFFFFCC00))
        "support"      -> CategoryStyle(Icons.Outlined.Settings, Color(0xFF8B6914))
        "other"        -> CategoryStyle(Icons.Outlined.GridView, Color(0xFF8E8E93))
        else           -> CategoryStyle(Icons.Outlined.Forum, Color(0xFF8E8E93))
    }
}

// Date period for section grouping (matching iOS)
private enum class DatePeriod(val label: String) {
    PINNED("Pinned"),     // labels are i18n'd at render time via sectionLabelFor
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    EARLIER("Earlier"),
}

// Map a session's updatedAt to its display bucket (mirrors iOS groupedSessions): Today/Yesterday calendar-day, This Week 7-day rolling, This Month 30-day rolling, else Earlier.
private fun datePeriod(timestamp: Long): DatePeriod {
    val now = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { time = Date(timestamp) }

    if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    ) return DatePeriod.TODAY

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    ) return DatePeriod.YESTERDAY

    val diffDays = TimeUnit.MILLISECONDS.toDays(now.timeInMillis - timestamp)
    if (diffDays < 7) return DatePeriod.THIS_WEEK

    // iOS uses a rolling `monthAgo = now - 1 month` (a calendar-month match would misbucket e.g. March 30).
    val monthAgo = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis
    if (timestamp > monthAgo) return DatePeriod.THIS_MONTH

    return DatePeriod.EARLIER
}

// [T-android-session-grouping] One rendered group block (group + filed sessions). Holds ids, not session objects (cheap to compare); [totalCount] keeps the real count while collapsed.
// [T-android-group-pause-badge-restamp] Freshness window for badges to pass through to the collapsed group card (24h, iOS parity).
private const val GROUP_BADGE_FRESH_WINDOW_MS = 24L * 60L * 60L * 1000L

data class FolderGroupBlock(
    val folder: FolderEntity,
    val ids: List<String>,
    val totalCount: Int,
    val isCollapsed: Boolean,
    val latestUpdatedAt: Long,
    /** Newest member's title — iOS folderSectionHeader's "N chats · title" summary line. */
    val summaryTitle: String? = null,
    /** Newest member's category — tints the composed folder icon like iOS FolderComposedIcon. */
    val firstCategory: String? = null,
    /** [T-android-folder-category-icon] Every member's category, recency order — the composed icon's seeds. Survives collapse (ids empties, this does not). */
    val memberCategories: List<String?> = emptyList(),
    // [T-android-group-pause-badge-restamp] Any member with a fresh badge (24h), shown only while collapsed (iOS anyPaused); group-card filter only.
    val anyPaused: Boolean = false,
    // [T-android-group-running-ring] Any member currently running its loop; drives the collapsed card's SpinningRing (iOS anyActive). No freshness window.
    val anyActive: Boolean = false,
    // [T-wave-e-group-visuals] Any member carries an unseen reply — red dot on the header.
    val anyUnread: Boolean = false,
)

// [T-android-sessionlist-byid] (#12) Folder blocks + ungrouped remainder + id→session map (O(1) expanded-group row resolution).
private data class SessionPartition(
    val blocks: List<FolderGroupBlock>,
    val remaining: List<ChatSessionEntity>,
    val byId: Map<String, ChatSessionEntity>,
)

// [T-android-session-grouping] Partition into group blocks + ungrouped remainder. Pinned groups float above unpinned (stable partition); membership outranks pin for placement; unknown folder_id and empty groups render rather than vanish.
// `internal` so the accordion invariant can be tested for real rather than by
// matching source text — see SessionGroupAccordionTest.
internal fun partitionByFolder(
    sessions: List<ChatSessionEntity>,
    folders: List<FolderEntity>,
    collapsedIds: Set<String>,
    /**
     * Session ids carrying a corner badge entered within the last 24h,
     * snapshotted ONCE by the caller (iOS computes `freshCornerIds` the same
     * way, once per grouping pass, rather than entering the store per member).
     */
    freshBadgedIds: Set<String> = emptySet(),
    /** Ids whose agent loop is running — see [FolderGroupBlock.anyActive]. */
    activeSessionIds: Set<String> = emptySet(),
    /** [T-wave-e-group-visuals] Ids with an unseen reply — see [FolderGroupBlock.anyUnread]. */
    unreadSessionIds: Set<String> = emptySet(),
): Pair<List<FolderGroupBlock>, List<ChatSessionEntity>> {
    if (folders.isEmpty()) return emptyList<FolderGroupBlock>() to sessions

    val byId = folders.associateBy { it.id }
    val members = LinkedHashMap<String, MutableList<ChatSessionEntity>>()
    val ungrouped = mutableListOf<ChatSessionEntity>()

    for (s in sessions) {
        val fid = s.folderId
        // Presence check against the loaded map — never a DB constraint.
        if (fid != null && byId.containsKey(fid)) {
            members.getOrPut(fid) { mutableListOf() }.add(s)
        } else {
            ungrouped.add(s)
        }
    }

    // First-encounter order = activity order. Groups with no members are
    // appended afterwards so they still render.
    val ordered = members.keys.toMutableList()
    for (f in folders) if (f.id !in members) ordered.add(f.id)

    // [T-android-group-accordion] At most ONE group is open at a time.
    //
    // `collapsedIds` stores the inverse (which groups are shut), so an empty
    // set — a fresh install, or a device whose folders all arrived from a
    // restore — means "nothing is collapsed", i.e. everything unfolds at once.
    // That is the state the user reported. The toggle already enforces the
    // accordion; this makes the invariant hold on the way IN as well, so it
    // cannot be violated by a set that no interaction has touched yet.
    //
    // The survivor is the first in `ordered`, which is activity order — the
    // most recently used group is the one worth having open.
    val openId = ordered.firstOrNull { it !in collapsedIds }

    val blocks = ordered.mapNotNull { fid ->
        val folder = byId[fid] ?: return@mapNotNull null
        val m = members[fid].orEmpty()
        val collapsed = fid != openId
        // Pinned members first, stable partition — the pin is a display
        // affordance inside the group, not a reason to leave it.
        val displayOrdered = m.filter { it.pinnedAt != null } + m.filter { it.pinnedAt == null }
        FolderGroupBlock(
            folder = folder,
            ids = if (collapsed) emptyList() else displayOrdered.map { it.id },
            totalCount = m.size,
            isCollapsed = collapsed,
            // Recency order (not display order) — this means "newest activity".
            latestUpdatedAt = m.firstOrNull()?.updatedAt ?: folder.updatedAt,
            summaryTitle = m.firstOrNull()?.title,
            firstCategory = m.firstOrNull()?.category,
            memberCategories = m.map { it.category },
            // Early-exiting hash lookups over the pre-built snapshot — never a
            // per-member entry into the badge store.
            anyPaused = freshBadgedIds.isNotEmpty() && m.any { it.id in freshBadgedIds },
            anyActive = activeSessionIds.isNotEmpty() && m.any { it.id in activeSessionIds },
            // [T-wave-e-group-visuals] Any member with an unseen reply.
            anyUnread = unreadSessionIds.isNotEmpty() && m.any { it.id in unreadSessionIds },
        )
    }

    val pinnedFirst = blocks.filter { it.folder.isPinned } + blocks.filter { !it.folder.isPinned }
    return pinnedFirst to ungrouped
}

private fun groupSessionsByDate(sessions: List<ChatSessionEntity>): List<Pair<DatePeriod, List<ChatSessionEntity>>> {
    val pinned = sessions.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }
    val unpinned = sessions.filter { it.pinnedAt == null }
    val grouped = unpinned.groupBy { datePeriod(it.updatedAt) }
    val result = mutableListOf<Pair<DatePeriod, List<ChatSessionEntity>>>()
    if (pinned.isNotEmpty()) {
        result.add(DatePeriod.PINNED to pinned)
    }
    for (period in DatePeriod.entries) {
        if (period == DatePeriod.PINNED) continue
        grouped[period]?.let { result.add(period to it) }
    }
    return result
}

private fun relativeDate(context: Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)

    if (seconds < 60) return context.getString(R.string.time_just_now)
    if (minutes < 60) return context.getString(R.string.time_minutes_ago, minutes.toInt())
    if (hours < 24) return context.getString(R.string.time_hours_ago, hours.toInt())

    val dateCal = Calendar.getInstance().apply { time = Date(timestamp) }
    val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (dateCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
        dateCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
    ) {
        return context.getString(R.string.time_yesterday)
    }

    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) {
        // T172: device-locale weekday names via java.text.DateFormatSymbols.
        val dayNames = java.text.DateFormatSymbols(java.util.Locale.getDefault()).weekdays
        return dayNames[dateCal.get(Calendar.DAY_OF_WEEK)]
    }

    val month = dateCal.get(Calendar.MONTH) + 1
    val day = dateCal.get(Calendar.DAY_OF_MONTH)
    return "$month/$day"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    onSessionClick: (String) -> Unit,
    onNewChat: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onAddProviderClick: () -> Unit = {},
    onSelectModelsClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onRootfsClick: () -> Unit = {},
    // [T-android-scheduled-tasks-design] Entry to the scheduled-tasks list.
    onScheduledTasksClick: () -> Unit = {},
    /**
     * [T-android-tablet-split] The session currently shown in the detail pane,
     * highlighted in the list. Non-null only in two-pane (tablet) mode — in
     * single-pane the list is never on screen next to a chat, so there is
     * nothing to reflect and the parameter stays null, leaving phone rendering
     * byte-identical to before.
     *
     * Deliberately NOT read from the nav back stack: in two-pane mode the
     * detail pane is owned by the ListDetail pane navigator, not by a NavHost
     * entry, so the back stack does not know what the detail is showing.
     *
     * A draft ("__new__…") id never matches a persisted row, so a new chat
     * highlights nothing — which is the intended behaviour (iOS parity: the
     * list has no selection until the draft is saved).
     */
    selectedSessionId: String? = null,
    /**
     * [T-android-draft-placeholder-row] Id of an unsaved draft the detail pane
     * is showing. Renders a synthetic "New Chat / No messages yet" row at the
     * top so the two-pane list always has a row matching the chat on screen.
     *
     * The row is a pure view construct with no database backing: a session is
     * created only by `ChatViewModel.ensureSession()` on the first send, so an
     * abandoned draft simply stops being passed here and the row disappears —
     * nothing to clean up. Mirrors iOS `ContentView.displaySessions`, which
     * prepends the same placeholder in split mode.
     */
    draftPlaceholderId: String? = null,
) {
    val context = LocalContext.current
    // T46: hoist VM ownership to the NavBackStackEntry's ViewModelStore so
    // [searchQuery] / [isSearchActive] survive navigation. The previous
    // `remember {}` scoping tied the VM to the composable's lifetime — pushing
    // to chat detail destroyed it, then pop-back rebuilt a fresh VM with
    // empty search state. Mirrors iOS where ContentView's `@State searchText`
    // survives a NavigationLink push because the parent view never unmounts.
    val viewModel: SessionListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        // [T-android-activity-leak-sessionlist-vm] applicationContext, NOT the
        // composition context. The VM keeps the Context it is handed (prefs at
        // SessionListViewModel.uiPrefs, OAuthManager, ProviderFactory, filesDir)
        // and is deliberately NavBackStackEntry-scoped to survive navigation —
        // and the SESSION_LIST entry lives for the whole app lifetime, so an
        // Activity instance passed here was pinned (view tree included) forever,
        // re-pinned on every recreation. Peers already pass the application
        // context (ScheduledTasksViewModel.kt:203, GitPanelScreen.kt:501).
        factory = SessionListViewModel.factory(
            chatRepository,
            providerRepository,
            context.applicationContext,
        ),
    )
    val persistedSessions by viewModel.displayedSessions.collectAsStateWithLifecycle()
    // [#35] Footer "Archived" section — separate flow, hidden from displayedSessions.
    val archivedSessions by viewModel.archivedSessions.collectAsStateWithLifecycle()
    // [T-android-draft-placeholder-row] Prepend the synthetic draft row — built
    // here because it must never reach the DB or repository flows; it lives only
    // as long as the unsaved draft. Mirrors iOS displaySessions.
    val sessions = remember(persistedSessions, draftPlaceholderId) {
        val draftId = draftPlaceholderId
        if (draftId == null || persistedSessions.any { it.id == draftId }) {
            persistedSessions
        } else {
            val now = System.currentTimeMillis()
            listOf(
                com.openminis.app.data.db.ChatSessionEntity(
                    id = draftId,
                    // Left null so the row falls through to the same
                    // "New Chat" default a titleless persisted session uses.
                    title = null,
                    modelId = "",
                    createdAt = now,
                    updatedAt = now,
                    folderId = null,
                ),
            ) + persistedSessions
        }
    }
    val isInitialLoadComplete by viewModel.isInitialLoadComplete.collectAsStateWithLifecycle()
    // [T-android-sessionlist-snapshot-restore] painted from last run's cache.
    val isSnapshotRestored by viewModel.isSnapshotRestored.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchSnippets by viewModel.searchSnippets.collectAsStateWithLifecycle()
    // [T-android-search-jump] Session id → matched message id, collected so
    // the click handler below can read the CURRENT map without a second
    // subscription (the guarded lambda is rebuilt per recomposition anyway).
    val searchAnchors by viewModel.searchAnchors.collectAsStateWithLifecycle()
    val regeneratingIds by viewModel.regeneratingIds.collectAsStateWithLifecycle()
    val providerConfig by providerRepository.config.collectAsStateWithLifecycle()
    val hasProviders = providerConfig.instances.isNotEmpty()
    val hasGroups = providerConfig.modelGroups.isNotEmpty()
    // [T-android-startup-config-stall] Provider config now loads off-thread, so
    // for a brief startup window `providerConfig` is the empty placeholder.
    // Gate the onboarding/list render on this too (alongside the sessions
    // initial-load flag) so an existing user with providers but zero sessions
    // doesn't flash the "add a provider" onboarding before the real config emits.
    val configLoaded by providerRepository.configLoaded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // [T-android-entry-ime-guard] Blank-search collapse + focus release before
    // any navigation into a chat; the policy lives in SessionListFocusGate.kt.
    val focusGate = rememberSessionListFocusGate(viewModel)
    val isDark = ChatColors.isDark

    // [T-android-new-chat-shortcut] Ctrl/⌘+F, raised at the split scaffold's
    // root so it fires from either pane. Opening the search is all that is
    // needed — the field takes focus on its own when it enters composition
    // (see the LaunchedEffect on the search TextField).
    //
    // `drop(1)` skips the counter's current value: collecting a StateFlow
    // replays it immediately, which would spring the search open every time
    // this screen recomposed into view after any earlier shortcut press.
    val searchRequests = com.openminis.app.ui.navigation.SessionSearchRequest.requests
    LaunchedEffect(Unit) {
        searchRequests.drop(1).collect {
            viewModel.isSearchActive.value = true
        }
    }
    // Wrapped navigation callbacks: run the search-collapse check first, then
    // navigate. Used everywhere a session tap / new-chat creation navigates.
    // [T-session-paused-badge-active-false-positive] Do NOT clear the PAUSED
    // badge on open: merely opening an interrupted session does not resolve it.
    // The badge is now driven by ChatViewModel's canResume flow — it clears only
    // when the interruption is actually resolved (Resume tapped / new message
    // sent / loop completed), and the ChatViewModel re-asserts it on load if the
    // session is still interrupted. Clearing here just caused a flicker.
    // [T-android-search-jump] A tap on a CONTENT-matched search result stashes
    // the matched message id before the pane opens, so ChatScreen can page
    // older history, jump to the row and pulse a highlight instead of landing
    // at the tail. Title-only matches (no anchor) and non-search taps pass
    // through untouched. The stash is consumed by ChatScreen's
    // (sessionId, revision)-keyed effect — see DeepLinkCoordinator.SearchFocus.
    val onSessionClickGuarded: (String) -> Unit = { id ->
        searchAnchors[id]?.let { messageId ->
            com.openminis.app.deeplink.DeepLinkCoordinator
                .setPendingSearchFocus(id, messageId)
        }
        focusGate.prepareNavigation()
        onSessionClick(id)
    }
    val onNewChatGuarded: (String) -> Unit = { id ->
        focusGate.prepareNavigation()
        onNewChat(id)
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    // [#35] Archived footer expand state (session-scoped, not persisted).
    var archivedExpanded by remember { mutableStateOf(false) }
    // [T-android-session-grouping] Group management dialogs.
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDissolve by remember { mutableStateOf<FolderEntity?>(null) }
    // iOS "Delete Group & N Sessions" — pair carries the member count so the
    // confirmation can restate the consequence.
    var folderToDelete by remember { mutableStateOf<Pair<FolderEntity, Int>?>(null) }
    var editSession by remember { mutableStateOf<ChatSessionEntity?>(null) }
    // [T-android-session-branch-tree] Session whose branch tree sheet is open.
    var branchTreeSession by remember { mutableStateOf<String?>(null) }
    var showBrowserSheet by remember { mutableStateOf(false) }
    var showBrowserSettings by remember { mutableStateOf(false) }
    val browserTabPool = remember { com.openminis.app.browser.BrowserTabPool(context) }

    // [T-android-session-grouping] Groups are pulled out FIRST; only the
    // leftovers go through date bucketing. Assembly order below is
    // Pinned → group block → date buckets, matching iOS.
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val collapsedFolderIds by viewModel.collapsedFolderIds.collectAsStateWithLifecycle()
    val folderMemberCounts by viewModel.folderMemberCounts.collectAsStateWithLifecycle()
    val groupPickerRequest by viewModel.groupPickerRequest.collectAsStateWithLifecycle()
    // While searching, group cards are suppressed: padding a result set with every non-matching group is noise.
    val showFolderBlock = !isSearchActive || searchQuery.isBlank()
    // [T-android-group-pause-badge-restamp] Snapshot the fresh-badge set ONCE
    // per grouping pass (iOS does the same in computeGroupedSessionIDs). The
    // set itself is the recomposition key: as badges age past the 24h window
    // the set recomputed on the next ambient refresh differs and the cards
    // re-derive — which is exactly the "picked up on the next refresh rather
    // than by a dedicated timer" semantics iOS documents. `sessionBadges` is
    // collected so a push/remove re-enters this block promptly.
    // `revision` is keyed alongside the queue map because a pure RE-STAMP
    // (badge already at the head of its queue) leaves `byId` equals-identical
    // and is therefore conflated away — yet it can flip a card from stale to
    // fresh. See SessionBadgeStore.revision.
    val sessionBadges by com.openminis.app.service.SessionBadgeStore.byId.collectAsStateWithLifecycle()
    val badgeRevision by com.openminis.app.service.SessionBadgeStore.revision.collectAsStateWithLifecycle()
    val freshBadgedIds = remember(sessionBadges, badgeRevision) {
        com.openminis.app.service.SessionBadgeStore
            .freshCornerBadgeSessionIds(GROUP_BADGE_FRESH_WINDOW_MS)
    }
    // [T-android-group-running-ring] Live running set, so a collapsed group
    // can show that one of its members is still working. Keyed into the
    // partition memo — without it the blocks would keep a stale snapshot and
    // the ring would never appear or never clear.
    val activeSessionIds by SessionActivityTracker.activeSessions.collectAsStateWithLifecycle()
    // [T-wave-e-group-visuals] Unseen-reply set, derived per row from
    // UnreadRegistry's persisted read stamps; the revision flow invalidates
    // the memo the moment the user reads a chat.
    val unreadRevision by com.openminis.app.data.UnreadRegistry.revision
        .collectAsStateWithLifecycle()
    val unreadSessionIds = remember(sessions, unreadRevision) {
        sessions.asSequence()
            .filter {
                com.openminis.app.data.UnreadRegistry.isUnread(
                    it.updatedAt,
                    com.openminis.app.data.UnreadRegistry.lastReadAtMs(context, it.id),
                )
            }
            .map { it.id }.toSet()
    }
    val folderPartition = remember(
        sessions, folders, collapsedFolderIds, showFolderBlock, freshBadgedIds, activeSessionIds, unreadSessionIds,
    ) {
        // [T-android-sessionlist-byid] (#12) Also produce an id→session map
        // so the folder-expansion render path resolves each member in O(1)
        // instead of `sessions.firstOrNull { it.id == id }` — O(n×m) inside
        // the LazyColumn builder, re-run on every list rebuild.
        val byId = sessions.associateBy { it.id }
        val (blocks, remaining) = if (showFolderBlock) {
            partitionByFolder(
                sessions, folders, collapsedFolderIds, freshBadgedIds, activeSessionIds, unreadSessionIds,
            )
        } else {
            emptyList<FolderGroupBlock>() to sessions
        }
        SessionPartition(blocks, remaining, byId)
    }
    val folderBlocks = folderPartition.blocks
    val groupedSessions = remember(folderPartition) { groupSessionsByDate(folderPartition.remaining) }

    // [T-android-folder-accordion-anchor] Assembly order (Pinned → groups →
    // date buckets) hoisted OUT of the LazyColumn body so the scroll-anchor
    // effect and the mini-bar can reconstruct each header's flat item index.
    // MUST stay in lockstep with the item builder below.
    val pinnedFirst = groupedSessions.firstOrNull()?.first == DatePeriod.PINNED
    val leadingDateGroups = if (pinnedFirst) groupedSessions.take(1) else emptyList()
    val trailingDateGroups = if (pinnedFirst) groupedSessions.drop(1) else groupedSessions
    val folderHeaderIndices = remember(leadingDateGroups, folderBlocks) {
        buildMap {
            var idx = 0
            leadingDateGroups.forEach { (_, rows) -> idx += 1 + rows.size }
            if (folderBlocks.isNotEmpty()) {
                idx += 1 // the "分组" section header item
                folderBlocks.forEach { b ->
                    put(b.folder.id, idx)
                    idx += 1 + b.ids.size
                }
            }
        }
    }

    // [T-android-newchat-list-autoscroll] The list is ORDER BY updated_at
    // DESC, so a fresh session lands at index 0 — but the LazyColumn keeps
    // its scroll offset across navigation, so a scrolled-down list hides it
    // (Jackson 41429). Detection lives in the VM (the list composable is
    // disposed during the detail push); here: collect the one-shot event and
    // scroll, skipping search (reorders) and selection mode (#765).
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        viewModel.newTopSessionEvent.collect {
            if (!viewModel.isSearchActive.value) {
                listState.animateScrollToItem(0)
            }
        }
    }

    // [T-android-folder-accordion-anchor] iOS toggleFolderCollapsed's scroll
    // correction: opening folder B closes folder A; A's rows vanish and B
    // slides up — often off the top edge. MINIMAL correction: after the
    // change lands (two frames — measuring now reads pre-collapse geometry),
    // scroll B's header back only if it actually LEFT the viewport.
    // Expand-only — collapsing self-anchors under the finger.
    var pendingExpandFolderId by remember { mutableStateOf<String?>(null) }
    val densityForAnchor = LocalDensity.current
    LaunchedEffect(pendingExpandFolderId) {
        val fid = pendingExpandFolderId ?: return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        val key = "folder_$fid"
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == key }
        // 8dp slack so a header sitting exactly on the boundary isn't judged
        // off-screen by a sub-pixel rounding difference.
        val slack = with(densityForAnchor) { 8.dp.toPx() }.toInt()
        val offscreen = item == null ||
            item.offset < layout.viewportStartOffset - slack ||
            item.offset + item.size > layout.viewportEndOffset + slack
        if (offscreen) {
            folderHeaderIndices[fid]?.let { listState.animateScrollToItem(it) }
        }
        pendingExpandFolderId = null
    }

    // [T-android-folder-minibar] Port of iOS folderMiniBar: when an EXPANDED
    // group's header scrolls off the TOP, float a capsule with the group's
    // icon + name (tap → jump back to the header) and a circled chevron (tap
    // → collapse). Derived straight from LazyListState — no visibility probes
    // needed: header offscreen-above ⇔ the first visible item's index is past
    // the header's reconstructed index. Suppressed in select mode (iOS guard).
    val miniBarBlock by remember(folderBlocks, folderHeaderIndices) {
        derivedStateOf {
            val block = folderBlocks.firstOrNull { !it.isCollapsed && it.ids.isNotEmpty() }
                ?: return@derivedStateOf null
            val headerIdx = folderHeaderIndices[block.folder.id] ?: return@derivedStateOf null
            val infos = listState.layoutInfo.visibleItemsInfo
            when {
                infos.isEmpty() -> null
                infos.any { it.key == "folder_${block.folder.id}" } -> null
                infos.first().index > headerIdx -> block
                else -> null
            }
        }
    }

    // [T-android-scheduled-tasks-full] Live count of scheduled tasks for the
    // toolbar clock-icon badge. Observes the SharedPreferences-backed store so
    // the badge updates when tasks are added / removed without a manual refresh.
    // [T-android-scheduled-badge-enabled-only] Count only enabled tasks so the
    // badge reflects what's actually active — disabled tasks don't contribute,
    // and with none enabled the count is 0 (badge hidden by the >0 gate below).
    val scheduledTaskCount by remember {
        com.openminis.app.scheduled.ScheduledTaskStore(context).observe()
            .map { list -> list.count { it.enabled } }
    }.collectAsStateWithLifecycle(initialValue = 0)

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = {
                    // [FEAT-261021-07, iOS a6e6c10d1] Title + the Tailscale /
                    // proxy pills under it (NetworkStatusPills.kt); the pills
                    // drop out entirely while no Tailscale or proxy is
                    // configured, leaving the plain title as before.
                    HomeTitleWithNetworkPills(title = stringResource(R.string.app_name))
                },
                navigationIcon = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(m3Icon(Icons.Default.Settings), contentDescription = stringResource(R.string.sessionlist_settings))
                    }
                },
                actions = {
                        // [T-wave-e-home-search] Top-bar entry into the full
                        // conversation search (title + body, Room LIKE with a
                        // bound pattern; results carry snippets + jump anchors).
                        // Same search state the FAB toggles, so both entries
                        // share the query / results / anchor machinery.
                        IconButton(onClick = { viewModel.isSearchActive.value = true }) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.sessionlist_search_action),
                            )
                        }
                        IconButton(onClick = onScheduledTasksClick) {
                            androidx.compose.material3.BadgedBox(
                                badge = {
                                    if (scheduledTaskCount > 0) {
                                        Badge { Text("$scheduledTaskCount") }
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Outlined.Schedule,
                                    contentDescription = stringResource(R.string.sessionlist_scheduled_tasks),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                },
            )
        },
        // No default FAB — we draw dual FABs manually at bottom
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Main content; sessions.isEmpty() is true until Room delivers — [T-android-sessionlist-snapshot-restore] snapshot reveals it.
            var loadTimedOut by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(3_000L)
                if (!isInitialLoadComplete) loadTimedOut = true
            }
            if (sessionListEntryRevealed(isInitialLoadComplete, loadTimedOut, isSnapshotRestored)) Column(modifier = Modifier.fillMaxSize()) {
                if (sessionListStallSpinner(isInitialLoadComplete, isSnapshotRestored, sessions.isNotEmpty())) {
                    // Transient stall fallback — spin while we wait for the
                    // Room emission. Not a permanent state.
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.sessionlist_recovering),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else
                // [#35] Archive-everything must not flash onboarding — the footer section still has content.
                if (sessions.isEmpty() && archivedSessions.isEmpty()) {
                    if (isSearchActive && searchQuery.isNotBlank()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.search_no_results, searchQuery),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (configLoaded) {
                        // 3-step onboarding when there are no sessions; Step 3
                        // stays the call-to-action even with providers+groups
                        // (iOS ContentView.emptyState).
                        // [T-android-startup-config-stall] Gated on configLoaded so
                        // hasProviders/hasGroups reflect the real persisted config —
                        // otherwise a returning user with providers but no sessions
                        // would briefly see the "add a provider" step. The list
                        // branch is intentionally NOT gated.
                        OnboardingLanding(
                            hasProviders = hasProviders,
                            hasGroups = hasGroups,
                            onAddProvider = onAddProviderClick,
                            onSelectModels = onSelectModelsClick,
                            onStartConversation = {
                                scope.launch {
                                    val sessionId = viewModel.createNewWorkspaceSession()
                                    if (sessionId != null) onNewChatGuarded(sessionId)
                                }
                            },
                        )
                    }
                } else {
                    LazyColumn(
                        // [T-android-newchat-list-autoscroll] Hoisted state so
                        // the new-session autoscroll effect above can drive it.
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Leave space for bottom FAB row
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
                    ) {
                        // T25: search reuses the grouped rendering —
                        // displayedSessions is already VM-filtered.
                        // [T-android-session-grouping] Assembly: Pinned →
                        // groups → date buckets; keep in lockstep with folderHeaderIndices.
                        val existingFolderIds = folders.mapTo(HashSet()) { it.id }

                        fun androidx.compose.foundation.lazy.LazyListScope.renderSessionRows(
                            rows: List<ChatSessionEntity>,
                            // [T-android-folder-card-ios-parity] Folder members
                            // render as MIDDLE/BOTTOM segments of the group's
                            // welded container (iOS FolderMemberRowBackground);
                            // ungrouped rows stay full-bleed.
                            inFolder: Boolean = false,
                        ) {
                            items(rows, key = { it.id }) { session ->
                                val activeQuery =
                                    if (isSearchActive && searchQuery.isNotBlank()) searchQuery else ""
                                val rowModifier = if (inFolder) {
                                    val isLast = session.id == rows.last().id
                                    Modifier
                                        .padding(
                                            start = 6.dp, end = 6.dp,
                                            bottom = if (isLast) 4.dp else 0.dp,
                                        )
                                        .folderSurface(
                                            segment = if (isLast) FolderSegment.BOTTOM
                                            else FolderSegment.MIDDLE,
                                            fill = folderFillColor(),
                                            edge = folderEdgeColor(),
                                        )
                                        // AFTER folderSurface so the drawn
                                        // fill/border stay outside the clip;
                                        // inside it, the row's press ripple is
                                        // shaped to the segment — square for
                                        // middles, bottom-rounded on the last
                                        // row so the highlight can't poke out
                                        // of the container's corners.
                                        .clip(
                                            if (isLast) {
                                                RoundedCornerShape(
                                                    bottomStart = 16.dp, bottomEnd = 16.dp,
                                                )
                                            } else {
                                                RoundedCornerShape(0.dp)
                                            },
                                        )
                                } else {
                                    Modifier
                                }
                                // animateItem: rows fade+slide over 250ms
                                // (monotonic tween — a spring read as jitter on iOS).
                                Box(
                                    modifier = Modifier
                                        .animateItem(
                                            fadeInSpec = tween(250),
                                            fadeOutSpec = tween(250),
                                            placementSpec = tween(250),
                                        )
                                        .then(rowModifier),
                                ) {
                                SessionItemContent(
                                    session = session,
                                    onSessionClick = onSessionClickGuarded,
                                    onPinToggle = { viewModel.togglePin(it) },
                                    onEditRequest = { editSession = it },
                                    onExportRequest = { s, fmt ->
                                        exportSession(context, s, chatRepository, scope, fmt)
                                    },
                                    onRegenerateTitle = { viewModel.regenerateTitle(it) },
                                    onDuplicate = { viewModel.duplicateSession(it) },
                                    onBranchTree = { branchTreeSession = it },
                                    onArchiveToggle = { id, archive -> viewModel.archiveSession(id, archive) },
                                    onDeleteRequest = { id ->
                                        deleteTargetId = id
                                        showDeleteDialog = true
                                    },
                                    onMoveToGroup = { viewModel.requestGroupPicker(it) },
                                    isFiled = session.folderId != null &&
                                        session.folderId in existingFolderIds,
                                    isRegenerating = session.id in regeneratingIds,
                                    searchQuery = activeQuery,
                                    searchSnippet = searchSnippets[session.id],
                                    // [T-wave-e-group-visuals] Per-row unseen-reply dot.
                                    unread = session.id in unreadSessionIds,
                                    // [T-android-sessionrow-hoisted-flows] From the lists' own hoisted collectors, not per-row flow subscriptions.
                                    isActive = session.id in activeSessionIds,
                                    badgeHead = sessionBadges[session.id]?.firstOrNull(),
                                    // Transparent for folder members so the
                                    // container's surface shows through. In
                                    // two-pane mode the row backing the detail
                                    // pane gets the primary@12% selection tint
                                    // (iOS List(selection:) parity; same shade
                                    // the mention picker highlights with).
                                    // Ordering matters: folder members stay
                                    // transparent unless ALSO selected, or a
                                    // selected grouped row would show no
                                    // highlight at all.
                                    rowBackground = when {
                                        session.id == selectedSessionId ->
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        inFolder -> Color.Transparent
                                        else -> null
                                    },
                                    isFolderMember = inFolder,
                                )
                                }
                            }
                        }

                        leadingDateGroups.forEach { (period, periodSessions) ->
                            item(key = "header_${period.name}") {
                                SectionHeader(title = stringResource(R.string.sessionlist_section_pinned))
                            }
                            renderSessionRows(periodSessions)
                        }

                        if (folderBlocks.isNotEmpty()) {
                            item(key = "header_groups") {
                                SectionHeader(title = stringResource(R.string.group_section_header))
                            }
                            folderBlocks.forEach { block ->
                                // [T-wave-e-group-visual] Hoisted once per
                                // block: card + expanded rows share it.
                                val blockMembers = block.ids.mapNotNull(folderPartition.byId::get)
                                item(key = "folder_${block.folder.id}") {
                                    Box(Modifier.animateItem(placementSpec = tween(250))) {
                                    FolderCard(
                                        block = block,
                                        onToggle = {
                                            // Capture BEFORE the toggle — after it
                                            // the block still holds the old state.
                                            val willExpand = block.isCollapsed
                                            viewModel.toggleFolderCollapsed(block.folder.id)
                                            if (willExpand) {
                                                pendingExpandFolderId = block.folder.id
                                            }
                                        },
                                        onTogglePin = { viewModel.toggleFolderPin(block.folder.id) },
                                        onRename = { folderToRename = block.folder },
                                        onDissolve = { folderToDissolve = block.folder },
                                        onNewChatInGroup = {
                                            // iOS newChatInFolder: auto-expand
                                            // first so the new session doesn't
                                            // vanish into a collapsed group.
                                            if (block.isCollapsed) {
                                                viewModel.toggleFolderCollapsed(block.folder.id)
                                            }
                                            val sessionId = viewModel.createNewSession(
                                                folderId = block.folder.id,
                                            )
                                            if (sessionId != null) onNewChatGuarded(sessionId)
                                        },
                                        onDeleteWithSessions = {
                                            folderToDelete = block.folder to block.totalCount
                                        },
                                    )
                                    }
                                }
                                // Collapsed groups contribute no rows; the card
                                // still reports the real member count.
                                // (#12) O(1) map lookup per member instead of a
                                // firstOrNull scan over all sessions per id.
                                renderSessionRows(
                                    blockMembers,
                                    inFolder = true,
                                )
                            }
                        }

                        trailingDateGroups.forEach { (period, periodSessions) ->
                            item(key = "header_${period.name}") {
                                SectionHeader(title = stringResource(when (period) {
                                    DatePeriod.PINNED -> R.string.sessionlist_section_pinned
                                    DatePeriod.TODAY -> R.string.sessionlist_section_today
                                    DatePeriod.YESTERDAY -> R.string.sessionlist_section_yesterday
                                    DatePeriod.THIS_WEEK -> R.string.sessionlist_section_this_week
                                    DatePeriod.THIS_MONTH -> R.string.sessionlist_section_this_month
                                    DatePeriod.EARLIER -> R.string.sessionlist_section_earlier
                                }))
                            }
                            renderSessionRows(periodSessions)
                        }

                        // [#35] 已归档 footer — always last, collapsed by default;
                        // expand to browse and unarchive. Hidden while empty.
                        if (archivedSessions.isNotEmpty()) {
                            item(key = "header_archived") {
                                ArchivedSectionHeader(
                                    count = archivedSessions.size,
                                    expanded = archivedExpanded,
                                    onToggle = { archivedExpanded = !archivedExpanded },
                                )
                            }
                            if (archivedExpanded) renderSessionRows(archivedSessions)
                        }
                    }
                }
            }

            // [T-android-folder-minibar] Floating quick-nav capsule (iOS
            // folderMiniBar): appears when the expanded group's header scrolls
            // off the top. Two interaction zones — icon+name jumps back to the
            // header, the circled chevron collapses the group. Split on
            // purpose: whole-bar-collapses made "where am I" and "close this"
            // the same target.
            run {
                var lastBar by remember { mutableStateOf<FolderGroupBlock?>(null) }
                miniBarBlock?.let { lastBar = it }
                val bar = lastBar
                AnimatedVisibility(
                    visible = miniBarBlock != null,
                    enter = slideInVertically(tween(200)) { -it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    if (bar != null) {
                        val headerIdx = folderHeaderIndices[bar.folder.id]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                                .widthIn(max = 320.dp)
                                .height(48.dp)
                                .shadow(8.dp, RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(24.dp),
                                )
                                .padding(start = 10.dp, end = 8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = headerIdx != null) {
                                        scope.launch {
                                            headerIdx?.let { listState.animateScrollToItem(it) }
                                        }
                                    },
                            ) {
                                FolderComposedIcon(
                                    category = bar.firstCategory,
                                    diameter = 30.dp,
                                )
                                // Folder names are user data — verbatim.
                                Text(
                                    bar.folder.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    )
                                    .clickable {
                                        viewModel.toggleFolderCollapsed(bar.folder.id)
                                    },
                            ) {
                                Icon(
                                    m3Icon(Icons.Default.KeyboardArrowUp),
                                    contentDescription = stringResource(R.string.group_collapse),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (hasProviders && (sessions.isNotEmpty() || isSearchActive)) {
                // Dual FAB row (matching iOS: New Chat left + Search right).
                // Hidden while the onboarding landing shows — Step 3 is the
                // CTA. T46: stay visible while search is active even on an
                // empty result set, so the query stays editable.
                DualFabRow(
                    isDark = isDark,
                    isSearchActive = isSearchActive,
                    searchQuery = searchQuery,
                    isSearching = isSearching,
                    hasSessions = sessions.isNotEmpty() || isSearchActive,
                    onCreateFolder = { name, description ->
                        scope.launch {
                            viewModel.createWorkspaceFolder(name, description)
                        }
                    },
                    onNewChatWithGroup = { groupId ->
                        scope.launch {
                            val sessionId = viewModel.createNewWorkspaceSession(groupId = groupId)
                            if (sessionId != null) onNewChatGuarded(sessionId)
                        }
                    },
                    modelGroups = providerConfig.modelGroups,
                    onSearchToggle = {
                        if (isSearchActive) {
                            viewModel.searchQuery.value = ""
                            viewModel.isSearchActive.value = false
                        } else {
                            viewModel.isSearchActive.value = true
                        }
                    },
                    onSearchQueryChange = { viewModel.searchQuery.value = it },
                    onSearchDismiss = {
                        viewModel.searchQuery.value = ""
                        viewModel.isSearchActive.value = false
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    // [T-android-session-branch-tree] Branch tree sheet: source/children plus
    // the one-tap branch creation from the latest message.
    branchTreeSession?.let { sid ->
        BranchTreeSheet(
            sessionId = sid,
            viewModel = viewModel,
            onOpenSession = { branchTreeSession = null; onSessionClick(it) },
            onDismiss = { branchTreeSession = null },
        )
    }
    // Single delete confirmation
    if (showDeleteDialog && deleteTargetId != null) {
        MinisAlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                deleteTargetId = null
            },
            title = stringResource(R.string.sessionlist_delete_one_title),
            text = stringResource(R.string.sessionlist_delete_message),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                deleteTargetId?.let { viewModel.deleteSession(it) }
                showDeleteDialog = false
                deleteTargetId = null
            },
        )
    }

    // ─── Session groups ────────────────────────────────────────────────────
    // [T-android-session-grouping]

    groupPickerRequest?.let { request ->
        // [T-android-group-ai-suggest] Suggestion state lives on the VM, not
        // in the sheet, so an in-flight request survives recomposition (and
        // the sheet's own remembered state being torn down).
        val suggesting by viewModel.groupSuggesting.collectAsStateWithLifecycle()
        val suggestFailed by viewModel.groupSuggestFailed.collectAsStateWithLifecycle()
        val suggestion by viewModel.groupSuggestion.collectAsStateWithLifecycle()
        GroupPickerSheet(
            folders = folders,
            memberCounts = folderMemberCounts,
            sessionCount = request.sessionIds.size,
            anyFiled = request.anyFiled,
            onChoose = { viewModel.applyGroupChoice(it) },
            onDismiss = { viewModel.dismissGroupPicker() },
            suggesting = suggesting,
            suggestFailed = suggestFailed,
            suggestion = suggestion,
            onSuggest = { viewModel.suggestGroup() },
        )
    }

    folderToRename?.let { folder ->
        // Both fields are SEEDED from the current group. The rename always
        // writes the description through, so an unseeded field would silently
        // wipe a description the user never touched.
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        var desc by remember(folder.id) { mutableStateOf(folder.description.orEmpty()) }
        // A plain AlertDialog rather than MinisAlertDialog: this one needs two
        // text fields, and MinisAlertDialog is a title/text/buttons component.
        // Widening it for a single caller would push layout complexity into
        // every other dialog in the app.
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.group_rename)) },
            text = {
                Column {
                    // SectionTextField is built for settings screens: it draws
                    // NO border and uses horizontal contentPadding = 0, because
                    // there its parent (SettingsCardBlock) supplies both the
                    // 16dp inset and the card surface that bounds it. A dialog
                    // has neither, so used bare the glyphs sat flush against
                    // the fill and the two fields read as one block. Wrap each
                    // one the way a settings card would, plus a hairline border
                    // so the input edge is visible on the dialog's own surface.
                    DialogTextFieldFrame {
                        SectionTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = stringResource(R.string.group_name_hint),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DialogTextFieldFrame {
                        SectionTextField(
                            value = desc,
                            onValueChange = { desc = it.take(FolderEntity.DESC_MAX_CHARS) },
                            placeholder = stringResource(R.string.group_desc_hint),
                        )
                    }
                }
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    viewModel.renameFolder(folder.id, name, desc)
                    folderToRename = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    folderToDissolve?.let { folder ->
        val count = folderMemberCounts[folder.id] ?: 0
        MinisAlertDialog(
            onDismissRequest = { folderToDissolve = null },
            title = stringResource(R.string.group_dissolve_confirm_title),
            // Spells out that nothing is deleted — dissolve is deliberately NOT
            // styled destructive, because it touches no user data.
            text = stringResource(R.string.group_dissolve_confirm_message, count),
            confirmText = stringResource(R.string.group_dissolve),
            onConfirm = {
                viewModel.dissolveFolder(folder.id)
                folderToDissolve = null
            },
        )
    }

    // iOS "Delete Group & N Sessions" confirmation — the one destructive
    // folder action, so isDestructive here where dissolve deliberately isn't.
    folderToDelete?.let { (folder, count) ->
        MinisAlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = stringResource(R.string.group_delete_confirm_title),
            text = stringResource(R.string.group_delete_confirm_message, count),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteFolderWithSessions(folder.id)
                folderToDelete = null
            },
        )
    }

    // Edit Title & Category sheet (matching iOS SessionEditSheet)
    editSession?.let { session ->
        // Track the live DB-backed row for this session so a Regenerate-Title
        // run (which writes title/category to the DB) flows back into the sheet
        // without the user reopening it. displayedSessions observes the DB.
        val liveSession = sessions.firstOrNull { it.id == session.id } ?: session
        SessionEditSheet(
            session = session,
            liveSession = liveSession,
            isRegenerating = session.id in regeneratingIds,
            onRegenerate = { viewModel.regenerateTitle(session.id) },
            onDismiss = { editSession = null },
            onSave = { title, category ->
                viewModel.updateTitleAndCategory(session.id, title, category)
                editSession = null
            },
        )
    }

    // Browser sheet
    if (showBrowserSheet) {
        com.openminis.app.ui.browser.BrowserSheet(
            tabPool = browserTabPool,
            onDismiss = { showBrowserSheet = false },
        )
    }

    // Browser Settings sheet
    if (showBrowserSettings) {
        com.openminis.app.ui.browser.BrowserSettingsSheet(
            tabPool = browserTabPool,
            onDismiss = { showBrowserSettings = false },
        )
    }
}

// ─── Dual FAB Row (matching iOS fabRow) ─────────────────────────────────────

/** Persisted preference key for FAB order swap. */
private const val PREF_FAB_SWAPPED = "fab_swapped"

@Composable
private fun DualFabRow(
    isDark: Boolean,
    isSearchActive: Boolean,
    searchQuery: String,
    isSearching: Boolean,
    hasSessions: Boolean,
    onCreateFolder: (String, String?) -> Unit,
    onNewChatWithGroup: (String) -> Unit,
    modelGroups: List<com.openminis.app.data.model.ModelGroup>,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE) }
    // [T-android-sessionlist-prefs-io] The FIRST getBoolean on a cold
    // process synchronously loads the XML on the calling thread — and this
    // `remember` runs inside a composition frame. Same storage-stall
    // exposure as SessionListViewModel's constructor read; default to the
    // unswapped layout and let the persisted value land off-frame.
    var isSwapped by remember { mutableStateOf(false) }
    LaunchedEffect(prefs) {
        isSwapped = withContext(Dispatchers.IO) {
            prefs.getBoolean(PREF_FAB_SWAPPED, false)
        }
    }

    // T120: focus + IME control for the inline search field. The field appears
    // inside an AnimatedVisibility, so we drive focus from the parent and
    // request it when isSearchActive flips true. Showing the keyboard
    // explicitly via the SoftwareKeyboardController covers devices where
    // requestFocus() alone doesn't trigger the IME (e.g. some Pixel + Gboard
    // combinations under edge-to-edge layouts).
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            // AnimatedVisibility runs a 200ms enter animation; the TextField
            // isn't attached to the composition tree until the first frame of
            // that animation lands. Yield once so requestFocus() targets a
            // composed node rather than throwing IllegalStateException.
            kotlinx.coroutines.delay(50)
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    // Drag offset for the currently-dragged FAB
    var chatDragX by remember { mutableFloatStateOf(0f) }
    var searchDragX by remember { mutableFloatStateOf(0f) }

    // Threshold to trigger swap (half screen width roughly)
    val density = LocalDensity.current
    val swapThreshold = with(density) { 100.dp.toPx() }

    var showGroupMenu by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    val topGroups = remember(modelGroups) { modelGroups.take(10) }

    val chatFab: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .offset { IntOffset(chatDragX.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (kotlin.math.abs(chatDragX) > swapThreshold) {
                                isSwapped = !isSwapped
                                prefs.edit().putBoolean(PREF_FAB_SWAPPED, isSwapped).apply()
                            }
                            chatDragX = 0f
                        },
                        onDragCancel = { chatDragX = 0f },
                        onHorizontalDrag = { _, dragAmount -> chatDragX += dragAmount },
                    )
                },
        ) {
            FloatingActionButton(
                onClick = { showNewFolderDialog = true },
                shape = CircleShape,
                containerColor = minisFabColor(),
                modifier = Modifier
                    .size(56.dp)
                    // [T-android-fab-square-ripple] Clip BEFORE combinedClickable. FloatingActionButton's own `shape = CircleShape` only bounds
                    // the ripple it draws internally; this extra clickable layer (added for the long-press group menu) is a separate interaction source and draws its own indication, which
                    // without a clip spreads to the square 56dp bounds and shows
                    // as a grey box behind the round button. Same ordering as the
                    // circular voice button in ChatComposerWidgets.
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { showNewFolderDialog = true },
                        onLongClick = {
                            if (topGroups.isNotEmpty()) showGroupMenu = true
                        },
                    )
                    .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f)),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
            ) {
                // The icon was ALREADY CreateNewFolder while the action was
                // "new chat". Making the action match the icon is the fix.
                Icon(
                    Icons.Outlined.CreateNewFolder,
                    contentDescription = stringResource(R.string.sessionlist_fab_new_folder),
                    tint = minisFabContentColor(),
                    modifier = Modifier.size(24.dp),
                )
            }
            DropdownMenu(
                expanded = showGroupMenu,
                onDismissRequest = { showGroupMenu = false },
            ) {
                topGroups.forEach { group ->
                    DropdownMenuItem(
                        text = { Text(group.name) },
                        leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                        onClick = {
                            showGroupMenu = false
                            onNewChatWithGroup(group.id)
                        },
                    )
                }
            }
        }

        if (showNewFolderDialog) {
            NewFolderDialog(
                isDark = isDark,
                onDismiss = { showNewFolderDialog = false },
                onCreate = { name, description ->
                    showNewFolderDialog = false
                    onCreateFolder(name, description)
                },
            )
        }
    }

    val searchFab: @Composable () -> Unit = {
        if (hasSessions) {
            AnimatedVisibility(
                visible = !isSearchActive,
                enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f),
                exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
            ) {
                FloatingActionButton(
                    onClick = onSearchToggle,
                    shape = CircleShape,
                    // iOS: UIColor.secondarySystemBackground = #F2F2F7 (light) / #1C1C1E (dark).
                    // ChatColors.secondaryBg already matches these values across themes.
                    containerColor = ChatColors.secondaryBg,
                    modifier = Modifier
                        .size(56.dp)
                        .offset { IntOffset(searchDragX.roundToInt(), 0) }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (kotlin.math.abs(searchDragX) > swapThreshold) {
                                        isSwapped = !isSwapped
                                        prefs.edit().putBoolean(PREF_FAB_SWAPPED, isSwapped).apply()
                                    }
                                    searchDragX = 0f
                                },
                                onDragCancel = { searchDragX = 0f },
                                onHorizontalDrag = { _, dragAmount -> searchDragX += dragAmount },
                            )
                        }
                        .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.15f)),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.sessionlist_search_action), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                }
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // T24: lift the FAB+search row above the IME so the text field
            // remains visible while typing. Compose-managed inset — handles
            // the IME open/close animation in lockstep.
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Render in swapped or normal order
        if (isSwapped) { searchFab(); } else { chatFab() }

        // Middle: Inline search bar (when active)
        AnimatedVisibility(
            visible = isSearchActive,
            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_chats_placeholder)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    // T46: while debounce is in flight, swap the close icon
                    // for an indeterminate progress ring so the user sees the
                    // search is working — avoids the stale-results-then-snap
                    // transition on slow stores. Snaps back to the close
                    // button as soon as results land.
                    if (isSearching) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = onSearchDismiss) {
                            Icon(m3Icon(Icons.Default.Close), contentDescription = stringResource(R.string.sessionlist_dismiss), modifier = Modifier.size(18.dp))
                        }
                    }
                },
                // T46: full-capsule shape mirrors iOS searchable-field style
                // (see ContentView.fabRow — `.clipShape(Capsule())` over a
                // 56pt-tall HStack). RoundedCornerShape(50) is Compose's
                // canonical "pill" radius — guaranteed circular ends at any
                // height. Pair with a fixed 48dp height so the field aligns
                // with the flanking 56dp FABs without overpowering them.
                shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
                // T10: Material3's default OutlinedTextField containerColor is Color.Transparent, which lets the LazyColumn's session rows bleed through and overlap the typed query text. Set both
                // focused and unfocused container colors to surfaceContainerHigh (matches the grouped-section card background already used throughout settings) so the field reads as a discrete
                // surface above the list. Also drop both border colors —
                // capsule shape with no outline reads more like iOS's filled
                // search bar than the M3 outlined field default.
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    // [T-android-search-height] Left at the component's own height. Forcing 42dp here clipped the placeholder: a plain OutlinedTextField keeps its 16dp vertical
                    // contentPadding no matter what the outer frame says, so shrinking the frame cuts the text. The model picker's field was rebuilt on BasicTextField + DecorationBox to
                    // get around that; this one is a simpler inline field and
                    // is not worth the same surgery for a few dp.
                    .heightIn(min = 48.dp)
                    .focusRequester(searchFocusRequester),
            )
        }

        if (isSwapped) { chatFab() } else { searchFab() }
    }
}

// ─── Section Header (matching iOS .subheadline.weight(.semibold)) ───────────

// [v3-P2-3 known duplicate] This private header and the public
// ui/components/SectionDesign.kt SectionHeader are name-twins but NOT
// pixel-equivalent (here: 14sp SemiBold onSurfaceVariant + conditional pin
// glyph; there: titleMedium onSurface card title; ui/chat
// MemoryDetailScreens.kt has a third 11sp uppercase-caption variant).
// Kept as-is per the v3 review report's convergence plan — do not merge
// unless the three call sites agree on one visual spec.

@Composable
private fun SectionHeader(title: String) {
    // T172: title may now be a localized string, so compare against the
    // localized "Pinned" rather than the hardcoded enum label.
    val isPinned = title == stringResource(R.string.sessionlist_section_pinned)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(top = 4.dp),
    ) {
        if (isPinned) {
            Icon(
                imageVector = m3Icon(Icons.Default.PushPin),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(14.dp)
                    .padding(end = 0.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Session Item (context menu replaces swipe-to-delete, matching iOS) ─────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionItemContent(
    session: ChatSessionEntity,
    onSessionClick: (String) -> Unit,
    onPinToggle: (String) -> Unit,
    onEditRequest: (ChatSessionEntity) -> Unit,
    onExportRequest: (ChatSessionEntity, String) -> Unit,
    onRegenerateTitle: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onBranchTree: (String) -> Unit,
    /** [#35] (sessionId, archive) — stamp/clear archived_at, swapping the row in/out of the footer section. */
    onArchiveToggle: (String, Boolean) -> Unit,
    onDeleteRequest: (String) -> Unit,
    /** [T-android-session-grouping] Opens the group picker for this session. */
    onMoveToGroup: (String) -> Unit,
    /**
     * [T-android-session-grouping] True only when the session's group ACTUALLY
     * EXISTS locally — a dangling folder_id renders as ungrouped (see
     * partitionByFolder), and wording from the raw id alone made the row and
     * its menu disagree. The caller resolves membership the way the list does.
     */
    isFiled: Boolean,
    isRegenerating: Boolean = false,
    searchQuery: String = "",
    searchSnippet: String? = null,
    /**
     * [T-android-sessionrow-hoisted-flows] Running state + badge queue head,
     * read once by the list and passed down rather than collected per row.
     */
    isActive: Boolean = false,
    badgeHead: com.openminis.app.service.SessionBadgeStore.SessionBadgeState? = null,
    /** [T-wave-e-group-visuals] Unseen reply — red dot on the row icon. */
    unread: Boolean = false,
    /**
     * [T-android-folder-card-ios-parity] Overrides the row's own surface
     * background. Folder members pass Transparent so the group container's
     * welded fill shows through; null keeps the default surface.
     */
    rowBackground: Color? = null,
    /**
     * [T-android-split-selection-shape] True when this row sits inside a folder
     * container. Kept separate from [rowBackground] because that colour now
     * also carries the two-pane selection tint — see SessionRow.
     */
    isFolderMember: Boolean = false,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    // [T-android-menu-press-side] Which HALF of the row the finger was on.
    // Pressing on the right used to left-anchor the menu at the finger,
    // overflow the window, and get clamped left — so the popup (and its
    // top-LEFT-origin scale animation) visually appeared to the left of
    // the finger. Right-half presses now anchor the menu's RIGHT edge at
    // the press point with a matching top-right animation origin, so the
    // menu hangs off the finger naturally on both sides.
    var menuAlignEnd by remember { mutableStateOf(false) }
    var rowWidthPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val isPinned = session.pinnedAt != null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidthPx = it.width.toFloat() },
    ) {
        SessionRow(
            session = session,
            onClick = { onSessionClick(session.id) },
            searchQuery = searchQuery,
            searchSnippet = searchSnippet,
            isActive = isActive,
            badgeHead = badgeHead,
            unread = unread,
            rowBackground = rowBackground,
            isFolderMember = isFolderMember,
            onLongClick = { offsetPx ->
                pressOffset = with(density) {
                    DpOffset(offsetPx.x.toDp(), offsetPx.y.toDp())
                }
                menuAlignEnd = rowWidthPx > 0f && offsetPx.x > rowWidthPx / 2f
                showContextMenu = true
            },
        )
        // Loading overlay when regenerating title
        if (isRegenerating) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        stringResource(R.string.sessionlist_regenerating_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        // Invisible zero-size anchor at the press position — DropdownMenu
        // will open from here so it follows the touch point.
        Box(
            modifier = Modifier
                .offset(x = pressOffset.x, y = pressOffset.y)
                .size(1.dp),
        ) {
            MinisMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false },
                alignEnd = menuAlignEnd,
            ) {
            // Pin / Unpin
            DropdownMenuItem(
                text = { Text(stringResource(if (isPinned) R.string.sessionlist_unpin else R.string.sessionlist_pin)) },
                onClick = {
                    showContextMenu = false
                    onPinToggle(session.id)
                },
                leadingIcon = {
                    Icon(
                        if (isPinned) m3Icon(Icons.Default.Close) else m3Icon(Icons.Default.PushPin),
                        contentDescription = null,
                    )
                },
            )
            RunNowMenuItem(session.id) { showContextMenu = false }
            // Export submenu (JSON / Plain Text / Markdown).
            var showExportSub by remember { mutableStateOf(false) }
            DropdownMenuItem(
                text = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.sessionlist_export))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                },
                onClick = { showExportSub = !showExportSub },
                leadingIcon = {
                    Icon(m3Icon(Icons.Default.Share), contentDescription = null)
                },
            )
            if (showExportSub) {
                // [T-android-export-markdown] md joins the fmt passthrough trio.
                listOf(
                    R.string.sessionlist_export_json to "json",
                    R.string.sessionlist_export_plain to "text",
                    R.string.sessionlist_export_markdown to "markdown",
                ).forEach { (label, fmt) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, fmt)
                        },
                    )
                }
            }
            // Edit Title & Category
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessionlist_edit_title_category)) },
                onClick = {
                    showContextMenu = false
                    onEditRequest(session)
                },
                leadingIcon = {
                    Icon(m3Icon(Icons.Default.Edit), contentDescription = null)
                },
            )
            // Regenerate Title
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessionlist_regenerate_title)) },
                onClick = {
                    showContextMenu = false
                    onRegenerateTitle(session.id)
                },
                leadingIcon = {
                    Icon(m3Icon(Icons.Default.Refresh), contentDescription = null)
                },
            )
            // Duplicate
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessionlist_duplicate)) },
                onClick = {
                    showContextMenu = false
                    onDuplicate(session.id)
                },
                leadingIcon = {
                    Icon(m3Icon(Icons.Default.ContentCopy), contentDescription = null)
                },
            )
            // Branch tree ([T-android-session-branch-tree])
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessionlist_branch_tree)) },
                onClick = {
                    showContextMenu = false
                    onBranchTree(session.id)
                },
                leadingIcon = {
                    Icon(m3Icon(Icons.Default.AccountTree), contentDescription = null)
                },
            )
            // [#35] Archive / Unarchive (wording follows archivedAt, idiom of Pin/Unpin).
            DropdownMenuItem(
                text = { Text(stringResource(if (session.archivedAt != null) R.string.sessionlist_unarchive else R.string.sessionlist_archive)) },
                onClick = { showContextMenu = false; onArchiveToggle(session.id, session.archivedAt == null) },
                leadingIcon = { Icon(if (session.archivedAt != null) m3Icon(Icons.Default.Unarchive) else m3Icon(Icons.Default.Archive), contentDescription = null) },
            )
            // Move to / Change Group — [T-android-session-grouping] wording
            // follows membership (already filed = moving BETWEEN groups, same
            // idiom as Pin/Unpin). A single item opening a sheet, deliberately
            // NOT an inline submenu: the menu body would cost O(groups) per
            // open and capture the group data into the menu closure.
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (isFiled) R.string.group_change
                            else R.string.group_move_to,
                        ),
                    )
                },
                onClick = {
                    showContextMenu = false
                    onMoveToGroup(session.id)
                },
                leadingIcon = {
                    Icon(
                        if (isFiled) Icons.Default.DriveFileMove
                        else m3Icon(Icons.Default.Folder),
                        contentDescription = null,
                    )
                },
            )
            MinisMenuDivider()
            // Delete
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showContextMenu = false
                    onDeleteRequest(session.id)
                },
                leadingIcon = {
                    Icon(
                        m3Icon(Icons.Default.Delete),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
            }
        }
    }


}

/**
 * [T-android-session-grouping] The card that heads a group's block.
 *
 * Tapping it collapses/expands (accordion — opening one closes the others).
 * Long-pressing opens the management menu: pin, rename, dissolve. Dissolve is
 * deliberately NOT tinted destructive — it moves sessions back to the main list
 * and deletes nothing, and tinting it red would train the eye to read it as the
 * dangerous item.
 */
@OptIn(ExperimentalFoundationApi::class)
// ─── [T-android-folder-card-ios-parity] Folder container surface ────────────
//
// Port of iOS FolderSurface + FolderSegmentBorder (ContentView.swift): the
// folder group renders as ONE floating rounded container. Collapsed = a lone
// 16dp-radius card; expanded = the card becomes the TOP segment and each
// member row a MIDDLE/BOTTOM segment of the same surface, with the hairline
// border tiled around the outer perimeter only — no horizontal lines at row
// boundaries, so the pieces read as a single welded outline. Rows stay
// independent LazyColumn items (the iOS "never merge rows into one view"
// rule); only the background/border segmentation composes them.

private enum class FolderSegment { LONE, TOP, MIDDLE, BOTTOM }

/**
 * Edge highlight for the folder container: bright hairline in dark mode, a
 * subtle dark line in light mode (white would vanish on the light page) —
 * iOS `folderEdgeHighlight` verbatim.
 */
@Composable
private fun folderEdgeColor(): Color =
    if (ChatColors.isDark) Color.White.copy(alpha = 0.30f)
    else Color.Black.copy(alpha = 0.08f)

@Composable
private fun folderFillColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

private fun Modifier.folderSurface(
    segment: FolderSegment,
    fill: Color,
    edge: Color,
): Modifier = drawBehind {
    val r = 16.dp.toPx()
    val w = size.width
    val h = size.height
    val cr = CornerRadius(r, r)

    val fillPath = Path().apply {
        when (segment) {
            FolderSegment.LONE -> addRoundRect(RoundRect(0f, 0f, w, h, cr))
            FolderSegment.TOP -> addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, w, h),
                    topLeft = cr, topRight = cr,
                    bottomLeft = CornerRadius.Zero, bottomRight = CornerRadius.Zero,
                ),
            )
            FolderSegment.MIDDLE -> addRect(Rect(0f, 0f, w, h))
            FolderSegment.BOTTOM -> addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, w, h),
                    topLeft = CornerRadius.Zero, topRight = CornerRadius.Zero,
                    bottomLeft = cr, bottomRight = cr,
                ),
            )
        }
    }
    drawPath(fillPath, fill)

    // Border tiling (iOS FolderSegmentBorder): lone = full outline; top =
    // left edge up + top arcs + right edge down; middle = the two vertical
    // edges only; bottom = the mirror of top. Open paths — never a line
    // across a row boundary.
    val border = Path().apply {
        when (segment) {
            FolderSegment.LONE -> addRoundRect(RoundRect(0f, 0f, w, h, cr))
            FolderSegment.TOP -> {
                moveTo(0f, h)
                lineTo(0f, r)
                arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
                lineTo(w - r, 0f)
                arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
                lineTo(w, h)
            }
            FolderSegment.MIDDLE -> {
                moveTo(0f, 0f); lineTo(0f, h)
                moveTo(w, 0f); lineTo(w, h)
            }
            FolderSegment.BOTTOM -> {
                moveTo(0f, 0f)
                lineTo(0f, h - r)
                arcTo(Rect(0f, h - 2 * r, 2 * r, h), 180f, -90f, false)
                lineTo(w - r, h)
                arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 90f, -90f, false)
                lineTo(w, 0f)
            }
        }
    }
    drawPath(border, edge, style = Stroke(width = 0.75.dp.toPx()))
}

/**
 * The session-group glyph: a classic folder silhouette (tab + rounded body)
 * traced in the same 1024-unit design grid the old grouped-list rings used.
 */
private fun groupGlyphPath(side: Float): Path = Path().apply {
    // Redesigned folder glyph (was: two rings + four list lines, read as a blank
    // gray square at 44dp). Closed contour, no holes -> NonZero fills it solid.
    fillType = PathFillType.NonZero
    val u = side / 1024f
    fun X(v: Float) = v * u
    fun Y(v: Float) = (v - 56f) * u // vertical centering offset

    // Tab: top-left corner down the left side, across the tab top, into the body.
    moveTo(X(205f), Y(866f))
    lineTo(X(205f), Y(305f))
    arcTo(Rect(X(205f), Y(254f), X(307f), Y(356f)), 180f, 90f, true) // tab top-left r51
    lineTo(X(450f), Y(254f))
    // Tab-to-body step: a small concave fillet (reversed sweep) so the tab melts
    // into the body instead of a flat chamfer.
    lineTo(X(494f), Y(337f))
    arcTo(Rect(X(494f), Y(266f), X(626f), Y(393f)), 180f, -90f, false) // concave fillet r66
    lineTo(X(922f), Y(374f))
    arcTo(Rect(X(871f), Y(374f), X(973f), Y(476f)), 270f, 90f, true) // body top-right r51
    lineTo(X(973f), Y(815f))
    arcTo(Rect(X(871f), Y(815f), X(973f), Y(917f)), 0f, 90f, true) // body bottom-right r51
    lineTo(X(205f), Y(866f))
    close()
}

/**
 * Port of iOS FolderComposedIcon: the grouped-list glyph on the SAME circular
 * translucent tint the session rows use, at the same 44dp slot — a group icon
 * and a session icon are the same species at the same size. Tint borrows the
 * newest member's category color (gray when empty); 0.28 vs the session
 * icons' 0.18 so a group circle reads as a different kind of thing.
 */
@Composable
private fun FolderComposedIcon(category: String?, diameter: Dp = 44.dp) {
    val tint = monetIconTint(categoryStyle(category).color) // [T-android-dynamic-accent-icons]
    Box(
        modifier = Modifier
            .size(diameter)
            .background(tint.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter * 0.56f)) {
            drawPath(groupGlyphPath(size.width), tint)
        }
    }
}

@Composable
private fun FolderCard(
    block: FolderGroupBlock,
    onToggle: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
    /** iOS "New Chat in Group": start a chat that files into this folder. */
    onNewChatInGroup: () -> Unit,
    /** iOS "Delete Group & N Sessions": destructive, folder + all members. */
    onDeleteWithSessions: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // [T-android-menu-press-side] Anchor the long-press menu at the FINGER,
    // not the card: detectTapGestures supplies the press offset that
    // combinedClickable lacked (the menu used to always open at the card's
    // left edge). Same press-point + half-side rule as SessionItemContent.
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    var menuAlignEnd by remember { mutableStateOf(false) }
    val headerPressInteractions = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val expandLabel = stringResource(
        if (block.isCollapsed) R.string.group_expand else R.string.group_collapse,
    )
    // Expanded-with-members: the card is the container's TOP segment and
    // welds onto the first member row (no bottom gap). Collapsed or empty:
    // a lone floating card. Mirrors iOS FolderCardBackground.
    val isExpandedWithRows = !block.isCollapsed && block.ids.isNotEmpty()
    val chevronRotation by animateFloatAsState(
        targetValue = if (block.isCollapsed) -90f else 0f,
        animationSpec = tween(250),
        label = "folderChevron",
    )
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dateText = remember(block.latestUpdatedAt, ctx) {
        relativeDate(ctx, block.latestUpdatedAt)
    }
    val headerHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier = Modifier
            // 6dp outer inset floats the rounded card inside the list width;
            // 6 outer + 10 inner = 16 — the folder icon sits exactly on the
            // session rows' alignment grid.
            .padding(
                start = 6.dp, end = 6.dp, top = 4.dp,
                bottom = if (isExpandedWithRows) 0.dp else 4.dp,
            )
            .folderSurface(
                segment = if (isExpandedWithRows) FolderSegment.TOP else FolderSegment.LONE,
                fill = folderFillColor(),
                edge = folderEdgeColor(),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Clip BEFORE the clickable so the press ripple takes the
                // card's own rounded shape. Expanded: only the top corners
                // are round (the card is the container's TOP segment), so the
                // ripple must stay square at the weld.
                .clip(
                    if (isExpandedWithRows) {
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    } else {
                        RoundedCornerShape(16.dp)
                    },
                )
                // detectTapGestures instead of combinedClickable so the
                // long-press OFFSET is available for menu anchoring; the
                // ripple is driven by hand through the InteractionSource
                // (same pattern as SessionRow's press indication).
                .indication(headerPressInteractions, LocalIndication.current)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            val press = PressInteraction.Press(offset)
                            headerPressInteractions.emit(press)
                            val released = tryAwaitRelease()
                            headerPressInteractions.emit(
                                if (released) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press),
                            )
                        },
                        onTap = { onToggle() },
                        onLongPress = { offset ->
                            // detectTapGestures gives no haptic of its own —
                            // see the SessionRow note; fired by hand so the
                            // group header matches every other long-press menu.
                            headerHaptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                            )
                            pressOffset = with(density) {
                                DpOffset(offset.x.toDp(), offset.y.toDp())
                            }
                            menuAlignEnd = offset.x > size.width / 2f
                            menuOpen = true
                        },
                    )
                }
                .semantics(mergeDescendants = true) {
                    onClick(label = expandLabel) { onToggle(); true }
                }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // [T-android-group-pause-badge-restamp] Collapsed-only paused
            // badge in the session rows' bottom-end visual language (an
            // expanded group's members carry their own row badges; `anyPaused`
            // already carries the 24h freshness filter — rows unfiltered).
            Box(modifier = Modifier.size(44.dp)) {
                // [T-android-folder-category-icon] Category panel's own
                // iconography: one member wears that session's glyph, two and
                // more compose a 2/4/9 grid (ten and up truncate).
                FolderCategoryAvatar(categories = block.memberCategories)
                // [T-android-group-running-ring] Collapsed-only, same as the
                // paused badge below and for the same reason: an expanded
                // group's members carry their own rings, so a header copy
                // would leave the user guessing which row it refers to.
                // [T-wave-e-group-visuals] Comet ring on the shared DecorClock
                // phase replaces the self-started SpinningRing frame loop.
                if (block.isCollapsed && block.anyActive) {
                    CometRing(
                        color = monetIconTint(categoryStyle(block.firstCategory).color),
                        modifier = Modifier
                            .size(46.dp)
                            .align(Alignment.Center),
                    )
                }
                if (block.isCollapsed && block.anyPaused) {
                    SessionBadgeOverlay(
                        state = com.openminis.app.service.SessionBadgeStore
                            .SessionBadgeState.PAUSED,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
                if (block.isCollapsed && block.anyUnread) {
                    UnreadDot(modifier = Modifier.align(Alignment.TopEnd))
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                // [T-android-group-header-spacing] Match the session row's
                // title→lastMessage gap (SessionRow uses spacedBy(1.dp)). The
                // group name→summary line is the same 16sp-SemiBold → 14sp-
                // onSurfaceVariant pattern, so a wider 4dp gap here made the
                // group header look inconsistently loose next to plain rows.
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                // User data — rendered verbatim, never a string lookup.
                Text(
                    block.folder.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // totalCount, not ids.size — a collapsed group renders no
                    // rows but must still report its real membership. iOS
                    // summary line: "N chats · <newest member title>".
                    when {
                        block.totalCount > 0 && block.summaryTitle != null ->
                            stringResource(R.string.group_n_chats, block.totalCount) +
                                " · " + block.summaryTitle
                        block.totalCount > 0 ->
                            stringResource(R.string.group_n_chats, block.totalCount)
                        else -> stringResource(R.string.group_empty)
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    dateText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (block.folder.isPinned) {
                        Icon(
                            m3Icon(Icons.Default.PushPin),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Icon(
                        m3Icon(Icons.Default.KeyboardArrowDown),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation),
                    )
                }
            }
        }
        // Invisible zero-size anchor at the press position — the menu opens
        // from the finger, right-edge-anchored when the press was on the
        // card's right half (see menuAlignEnd above).
        Box(
            modifier = Modifier
                .offset(x = pressOffset.x, y = pressOffset.y)
                .size(1.dp),
        ) {
            MinisMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                alignEnd = menuAlignEnd,
            ) {
                // [T-android-folder-menu-icons] One icon FAMILY and one frame
                // for every item: Outlined variants in a 20dp box. The old mix
                // (filled PushPin / filled Edit / filled FolderOff at default
                // 24dp) had three different visual weights and optical sizes
                // in a four-item menu.
                val menuIcon: @Composable (androidx.compose.ui.graphics.vector.ImageVector) -> Unit =
                    { image ->
                        Icon(
                            image,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (block.folder.isPinned) R.string.sessionlist_unpin
                                else R.string.sessionlist_pin,
                            ),
                        )
                    },
                    onClick = { menuOpen = false; onTogglePin() },
                    leadingIcon = { menuIcon(Icons.Outlined.PushPin) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_rename)) },
                    onClick = { menuOpen = false; onRename() },
                    leadingIcon = { menuIcon(Icons.Outlined.Edit) },
                )
                // iOS folder menu parity: "New Chat in Group" (plus.bubble)
                // sits between Rename and the divider.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_new_chat_in)) },
                    onClick = { menuOpen = false; onNewChatInGroup() },
                    leadingIcon = { menuIcon(Icons.Outlined.AddComment) },
                )
                MinisMenuDivider()
                // Dissolve is deliberately NOT destructive-tinted (iOS note):
                // it touches no user data — sessions move back to the main
                // list. Tinting it red would train the eye to read it as the
                // deleting item.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_dissolve)) },
                    onClick = { menuOpen = false; onDissolve() },
                    leadingIcon = { menuIcon(Icons.Outlined.FolderOff) },
                )
                MinisMenuDivider()
                // The one destructive item, last, with the count in the title
                // so the consequence is visible in the menu itself, not only
                // in the confirmation dialog (iOS parity).
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.group_delete_with_sessions, block.totalCount,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { menuOpen = false; onDeleteWithSessions() },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }
    }
}

// ─── Session Row (matching iOS SessionRow) ──────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: ChatSessionEntity,
    onClick: () -> Unit,
    onLongClick: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    searchQuery: String = "",
    searchSnippet: String? = null,
    /**
     * [T-android-sessionrow-hoisted-flows] Running state + badge queue head,
     * hoisted by the caller (once per visible row used to mean one collector
     * EACH against SessionActivityTracker / SessionBadgeStore, so one badge
     * push fan-out-recomposed every row). Same shape as [isRegenerating] /
     * [searchSnippet] above, which the list already hoists.
     */
    isActive: Boolean = false,
    /**
     * [T-android-session-paused-badge] Head of this session's badge queue —
     * null for the common case. Renders as an overlay in the icon's
     * bottom-right corner, mirroring where iOS's iCloud badge sits so future
     * ICLOUD_SYNCING uses the same anchor.
     */
    badgeHead: com.openminis.app.service.SessionBadgeStore.SessionBadgeState? = null,
    /** [T-wave-e-group-visuals] Unseen reply — red dot on the row icon. */
    unread: Boolean = false,
    /** See SessionItemContent — Transparent inside a folder container. */
    rowBackground: Color? = null,
    /**
     * [T-android-split-selection-shape] True when this row is a member of a
     * folder container. Passed explicitly rather than inferred from
     * `rowBackground != null`, which conflates "folder member" with "selected".
     */
    isFolderMember: Boolean = false,
) {
    val style = remember(session.category) { categoryStyle(session.category) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val timeText = remember(session.updatedAt, ctx) { relativeDate(ctx, session.updatedAt) }
    val rowHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // [T-android-sessionrow-press-indication] Long-press uses raw
    // detectTapGestures (it needs the press OFFSET for menu anchoring),
    // which — unlike clickable — carries no indication, so the standard
    // ripple is driven by hand: Press/Release/Cancel emitted into an
    // InteractionSource from onPress, mounted with Modifier.indication.
    // Highlight SHAPE mirrors the folder card: ungrouped rows clip to the
    // same 6dp-inset 16dp-radius rounded rect (6 outside + 10 inside = the
    // 16dp content lead, nothing moves); folder members skip it — their
    // wrapper Box already clips to the container's segment shape.
    val pressInteractions = remember { MutableInteractionSource() }
    // [T-android-split-selection-shape] `inFolder` comes from the FOLDER
    // flag, not "rowBackground is non-null": rowBackground carries two
    // unrelated meanings (Transparent = folder member so the welded container
    // shows through; a primary tint = selected row). The old non-null test
    // made a SELECTED ungrouped row skip the inset/clip and paint edge-to-edge.
    val inFolder = isFolderMember
    // [T-android-split-selection-shape] A selected member gets the same
    // rounded pill as a selected ungrouped row, inset 4dp (the container
    // already contributes its 6) with a 12dp radius so it nests inside the
    // card's corner; the container's own fill/borders stay untouched.
    val selectionTint = rowBackground?.takeIf { inFolder && it != Color.Transparent }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!inFolder) {
                    Modifier
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                } else {
                    Modifier
                }
            )
            // Background AFTER the inset+clip, not before: drawing it first
            // filled the full row width and squared off the corners, so the
            // clip only ever shaped the ripple. Now the tint is the rounded
            // rect itself, matching the folder card's shape and leaving equal
            // 6dp gutters left and right.
            .background(
                if (selectionTint != null) Color.Transparent
                else rowBackground ?: MaterialTheme.colorScheme.surface,
            )
            // [T-android-selection-no-reflow] DRAWN, not padded.
            //
            // The first cut expressed the inset as
            // `.padding(4.dp, 2.dp).clip(...).background(...)`, which is a
            // LAYOUT modifier: it shrank the content box, so selecting a row
            // visibly nudged its icon and text. The row must not move at all —
            // only the tint's own rectangle should inset.
            //
            // `drawBehind` paints inside the row's existing bounds and
            // occupies no space, so the geometry of a selected row is
            // byte-identical to an unselected one. The 4dp/2dp inset and 12dp
            // radius now describe the painted rect alone.
            .then(
                if (selectionTint != null) {
                    Modifier.drawBehind {
                        val insetX = 4.dp.toPx()
                        val insetY = 2.dp.toPx()
                        drawRoundRect(
                            color = selectionTint,
                            topLeft = Offset(insetX, insetY),
                            size = Size(
                                size.width - insetX * 2,
                                size.height - insetY * 2,
                            ),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                        )
                    }
                } else {
                    Modifier
                }
            )
            .then(
                if (onLongClick != null) {
                    Modifier
                        // [T-android-sessionrow-press-shape] Clip the press
                        // ripple to the SAME rounded rect the selection tint
                        // paints, for a folder member.
                        //
                        // Ungrouped rows already got this from the
                        // `.clip(RoundedCornerShape(16.dp))` above, but folder
                        // members deliberately skip that clip (their wrapper
                        // shapes the welded container), so their ripple was
                        // drawn square and edge-to-edge. Under the rounded
                        // selection tint that read as two stacked highlights
                        // with mismatched corners — the reported symptom.
                        //
                        // Radius matches the tint's 12dp so the two agree; the
                        // ungrouped 16dp would not, since the container already
                        // contributes its own 6dp.
                        //
                        // [T-android-selection-no-reflow] Shape only — NO
                        // padding. The tint's 4dp/2dp inset cannot be reproduced
                        // here with `.padding`, because padding is a LAYOUT
                        // modifier: it would shrink the row's content box and
                        // shift every folder member's icon and text, which is
                        // the reflow this row is twice documented as avoiding.
                        // `clip` alone occupies no space, so the ripple is
                        // rounded to the row's full bounds. The 4dp difference
                        // between the ripple's edge and the tint's is not
                        // visible — a ripple is a transient wash, not an edge —
                        // whereas the square corners were.
                        .then(
                            if (inFolder) {
                                Modifier.clip(RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                            },
                        )
                        .indication(pressInteractions, LocalIndication.current)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = { offset ->
                                    val press = PressInteraction.Press(offset)
                                    pressInteractions.emit(press)
                                    val released = tryAwaitRelease()
                                    pressInteractions.emit(
                                        if (released) PressInteraction.Release(press)
                                        else PressInteraction.Cancel(press),
                                    )
                                },
                                onTap = {
                                    com.openminis.app.diagnostics.PerfLongCtx.click(session.id)
                                    onClick()
                                },
                                onLongPress = { offset ->
                                    // Same reason the ripple is driven by hand
                                    // above: raw detectTapGestures carries no
                                    // built-in feedback, so the haptic that
                                    // `combinedClickable` gives for free has to
                                    // be fired explicitly here.
                                    rowHaptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                    )
                                    onLongClick(offset)
                                },
                            )
                        }
                } else {
                    Modifier.clickable {
                        com.openminis.app.diagnostics.PerfLongCtx.click(session.id)
                        onClick()
                    }
                }
            )
            .padding(
                // 10dp in BOTH branches. Ungrouped: 6dp highlight-clip inset
                // + 10 = 16dp lead. In-folder: the wrapper Box already adds
                // the container's 6dp inset, so 16dp here pushed member icons
                // to 22dp — 6dp right of the folder card's own icon (6 outer
                // + 10 inner = 16). 10dp restores one shared 16dp icon grid
                // for the card, its members, and ungrouped rows alike.
                horizontal = 10.dp,
                // [T-android-selection-no-reflow] Back to the original 12dp.
                //
                // This was briefly 16dp and then 10dp while chasing "even
                // padding on the selection pill". Both were the wrong lever:
                // this padding sets the ROW's height, so changing it resized
                // every row in the list — selected or not — to fix how one
                // tinted rectangle looked. Row metrics are left exactly as they
                // were; the pill's proportions are handled by the inset of the
                // drawn tint instead (see the drawBehind above).
                vertical = 12.dp,
            ),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) {
            Box(Modifier.padding(top = 2.dp)) { leadingIcon() }
        }

        // Category icon in colored circle (18% opacity matching iOS)
        // isActive / badgeHead arrive as parameters — see their KDoc. The
        // per-row collectAsStateWithLifecycle() calls that used to sit here are hoisted to
        // the list (activeSessionIds, sessionBadges).
        Box(
            modifier = Modifier.size(44.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = monetIconTint(style.color).copy(alpha = 0.18f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = monetIconTint(style.color),
                    modifier = Modifier.size(20.dp),
                )
            }
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    SpinningRing(
                        color = monetIconTint(style.color),
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
            if (badgeHead != null) {
                SessionBadgeOverlay(
                    state = badgeHead,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            // [T-wave-e-group-visuals] Unseen reply: red dot, top-end (the
            // badge overlay owns bottom-end; the dot never collides with it).
            if (unread) {
                UnreadDot(modifier = Modifier.align(Alignment.TopEnd))
            }
        }

        // Title + last message (or highlighted snippet during search)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            val titleText = session.title ?: stringResource(R.string.sessionlist_untitled)
            if (searchQuery.isNotBlank()) {
                Text(
                    text = highlightedAnnotatedString(titleText, searchQuery),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = titleText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Active search: prefer the matched snippet (content hit) over
            // the generic lastMessage; title-only matches fall back (null).
            if (searchQuery.isNotBlank() && searchSnippet != null) {
                Text(
                    text = highlightedAnnotatedString(searchSnippet, searchQuery),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = lastMessageForDisplay(session.lastMessage)
                        ?: stringResource(R.string.sessionlist_no_messages),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = timeText,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * Card frame for a [SectionTextField] used inside a dialog.
 *
 * The settings screens get this for free from `SettingsCardBlock`: it supplies
 * the 16dp horizontal inset that SectionTextField deliberately omits (its
 * contentPadding is horizontal = 0 so glyphs align with sibling section rows —
 * T352) and the card surface that gives the input an edge. A dialog has no such
 * parent, so a bare SectionTextField renders as text jammed against its fill
 * with no visible boundary.
 *
 * Reuses the same tokens as the settings cards — [SectionDesign.CardShape] and
 * `cardColor()` — so a dialog input reads as the same control as the one on a
 * settings screen, plus a hairline outline: the dialog's surface sits close in
 * luminance to the card fill, and without the outline the field edge is
 * effectively invisible in dark mode.
 */
@Composable
private fun DialogTextFieldFrame(content: @Composable () -> Unit) {
    Surface(
        shape = SectionDesign.CardShape,
        color = SectionDesign.cardColor(),
        // Full-strength outlineVariant, not a faded one: the dialog's surface
        // and the card fill are close in luminance (both are surfaceContainer
        // shades), so anything dimmer than this reads as no border at all in
        // dark mode — verified on device.
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp)) { content() }
    }
}

/**
 * Spinning arc overlaid on the session icon while the agent loop is active.
 * Mirrors iOS `SpinningRing` (ContentView.swift:2405): 1.5dp stroke at 30%
 * opacity, 30% arc length, full rotation every ~1 second. Uses
 * `withFrameNanos` instead of an `animate*` API so recomposition across
 * onAppear calls does not stack multiple rotation animations.
 */
@Composable
private fun SpinningRing(
    color: Color,
    modifier: Modifier = Modifier,
) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsedSec = (now - startNanos) / 1_000_000_000f
                angle = (elapsedSec * 360f) % 360f
            }
        }
    }
    Canvas(modifier = modifier.rotate(angle)) {
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = color.copy(alpha = 0.8f),
            startAngle = 0f,
            sweepAngle = 360f * 0.3f,
            useCenter = false,
            size = Size(size.width, size.height),
            style = stroke,
        )
    }
}

/**
 * [T-android-session-paused-badge] Corner overlay for [SessionBadgeStore]
 * states. Anchored bottom-end inside the 44dp icon Box. Mirrors where the
 * iOS iCloud-sync badge sits so future ICLOUD_SYNCING uses the same anchor.
 *
 * Sizing: 14dp circle, ~2/3 the size of the 20dp category icon — visible
 * but doesn't overwhelm the icon glyph. Translated 2dp down/right so the
 * badge sits *on* the icon edge instead of flush with the row padding
 * (matches the visual weight of iOS's badge offset).
 */
@Composable
private fun SessionBadgeOverlay(
    state: com.openminis.app.service.SessionBadgeStore.SessionBadgeState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED -> {
            Box(
                modifier = modifier
                    .offset(x = 2.dp, y = 2.dp)
                    .size(14.dp)
                    .background(
                        // Solid system-orange. Picked over yellow so the
                        // alert reads as "attention" rather than "info".
                        color = Color(0xFFFF9500),
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Pause glyph (⏸) — mirrors iOS's "pause.fill" badge so the
                // cross-platform "this task was paused" affordance matches.
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.ICLOUD_SYNCING -> {
            // Reserved for the upcoming iCloud-equivalent sync surface;
            // not produced yet. Render nothing rather than a placeholder
            // so a stray persisted entry from a future build doesn't
            // surface a debug-looking icon on the current build.
        }
    }
}

// ─── Onboarding Landing (iOS-style 3-step setup) ───────────────────────────

@Composable
private fun OnboardingLanding(
    hasProviders: Boolean,
    hasGroups: Boolean,
    onAddProvider: () -> Unit,
    onSelectModels: () -> Unit,
    onStartConversation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Bottom padding ≈ top-bar height so the content visually centers
            // relative to the whole screen, not just the Scaffold inner area.
            .padding(horizontal = 32.dp)
            .padding(bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.sessionlist_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sessionlist_welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SetupStepCard(
                number = 1,
                title = stringResource(R.string.sessionlist_welcome_step1_title),
                subtitle = if (hasProviders) {
                    stringResource(R.string.sessionlist_welcome_step_done)
                } else {
                    stringResource(R.string.sessionlist_welcome_step1_subtitle)
                },
                isDone = hasProviders,
                isLocked = false,
                onClick = { if (!hasProviders) onAddProvider() },
            )

            SetupStepCard(
                number = 2,
                title = stringResource(R.string.sessionlist_welcome_step2_title),
                subtitle = when {
                    hasGroups -> stringResource(R.string.sessionlist_welcome_step_done)
                    hasProviders -> stringResource(R.string.sessionlist_welcome_step2_subtitle)
                    else -> stringResource(R.string.sessionlist_welcome_step2_locked)
                },
                isDone = hasGroups,
                isLocked = !hasProviders,
                onClick = { if (hasProviders && !hasGroups) onSelectModels() },
            )

            SetupStepCard(
                number = 3,
                title = stringResource(R.string.sessionlist_welcome_step3_title),
                subtitle = if (hasGroups) {
                    stringResource(R.string.sessionlist_welcome_step3_subtitle)
                } else {
                    stringResource(R.string.sessionlist_welcome_step3_locked)
                },
                isDone = false,
                isLocked = !hasGroups,
                onClick = { if (hasGroups) onStartConversation() },
            )
        }
    }
}

@Composable
private fun SetupStepCard(
    number: Int,
    title: String,
    subtitle: String,
    isDone: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    val isEnabled = !isDone && !isLocked

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = if (isDone) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isDone) {
                Icon(
                    imageVector = m3Icon(Icons.Default.Check),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = "$number",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).height(56.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!isDone && isEnabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

// ─── Edit Title & Category Sheet (matching iOS SessionEditSheet) ──────────

private val allCategories = listOf(
    "Code", "Writing", "Research", "Analysis",
    "Creative", "Chat", "Math", "Translation",
    "Health", "Finance", "Travel", "Education",
    "Design", "Productivity", "Support", "Other",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionEditSheet(
    session: ChatSessionEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, category: String?) -> Unit,
    // [T-android-sessionedit-regenerate-button] Regenerate-Title support,
    // matching iOS SessionEditSheet. `liveSession` is the DB-backed row that
    // updates when regeneration writes a new title/category; `isRegenerating`
    // drives the button's loading/disabled state; `onRegenerate` reuses the
    // existing SessionListViewModel.regenerateTitle logic. Defaults make the
    // button a no-op when a caller doesn't wire them up.
    liveSession: ChatSessionEntity = session,
    isRegenerating: Boolean = false,
    onRegenerate: () -> Unit = {},
) {
    var title by remember { mutableStateOf(session.title ?: "") }
    var selectedCategory by remember { mutableStateOf(session.category) }

    // [T-android-sessionedit-regenerate-button] When a regeneration run writes a
    // new title/category to the DB, `liveSession` updates — mirror those values
    // into the sheet's local edit state so the Title field and Category grid
    // refresh in place (iOS reads the fresh ChatStore session on completion).
    LaunchedEffect(liveSession.title, liveSession.category) {
        liveSession.title?.let { title = it }
        selectedCategory = liveSession.category
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            // Title bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.weight(1f))
                Text(
                    "Edit Session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                MinisTextButton(
                    onClick = { onSave(title.ifBlank { "New Chat" }, selectedCategory) },
                ) { Text(stringResource(R.string.save)) }
            }

            Spacer(Modifier.height(16.dp))

            // Title field
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.label_title)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                "Category",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Category grid (4 columns, matching iOS LazyVGrid)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(240.dp),
            ) {
                // Keyed by the (unique) category name so a selection change
                // rebinds the affected cells instead of recomposing the whole
                // grid from index 0.
                items(allCategories, key = { it }) { cat ->
                    val isSelected = selectedCategory?.equals(cat, ignoreCase = true) == true
                    val style = categoryStyle(cat.lowercase())
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) monetIconTint(style.color).copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable {
                                selectedCategory = if (isSelected) null else cat.lowercase()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = style.icon,
                                contentDescription = null,
                                tint = monetIconTint(style.color),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                cat,
                                fontSize = 11.sp,
                                color = if (isSelected) monetIconTint(style.color)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // [T-android-sessionedit-regenerate-button] Regenerate Title —
            // matches iOS SessionEditSheet's dedicated section below Category.
            // Reuses SessionListViewModel.regenerateTitle; shows a spinner and
            // disables while running (regeneratingIds) to prevent double taps.
            MinisOutlinedButton(
                onClick = onRegenerate,
                enabled = !isRegenerating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRegenerating) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessionlist_regenerating_title))
                } else {
                    Icon(
                        m3Icon(Icons.Default.Refresh),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessionlist_regenerate_title))
                }
            }
        }
    }
}

// ─── Export Session ────────────────────────────────────────────────────────

/**
 * Long-chat export (T-export-optimize b443b54d, iOS sister c9d1087d).
 *
 * Pre-fix: this loaded every [MessageEntity] for the session at once,
 * built the whole JSON / TXT payload in memory, and shoved it into
 * [Intent.EXTRA_TEXT]. Hundreds of messages caused jank, "ghost" frames
 * and OOM crashes — see linked feedback.
 *
 * Now: hand off to [com.openminis.app.share.ChatExporter] which paginates
 * (50 rows / batch) on [kotlinx.coroutines.Dispatchers.IO], streams to a
 * staging file under `cacheDir/export-staging/`, then zips into
 * `cacheDir/shared/` and hands the resulting [android.net.Uri] to the
 * share sheet as a real file attachment. Peak memory stays bounded by
 * batch size regardless of session length.
 */
private fun exportSession(
    context: Context,
    session: ChatSessionEntity,
    chatRepository: ChatRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    format: String,
) {
    scope.launch {
        try {
            val (uri, _) = com.openminis.app.share.ChatExporter.exportToZip(
                context = context,
                session = session,
                repository = chatRepository,
                format = format,
            )
            shareZip(context, uri, session.title ?: "Conversation")
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.export_progress_failed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}

private fun shareZip(context: Context, uri: android.net.Uri, subject: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(
        intent,
        context.getString(R.string.sessionlist_export),
    ).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    context.startActivity(chooser)
}

/**
 * Name + optional description for a new workspace folder.
 *
 * A folder is not just a label — [ChatRepository.createFolder] creates the
 * project directory on disk and every session filed into it mounts that
 * directory, so the name is the only thing the user gets to choose here and
 * the dialog keeps it to exactly that.
 */
