package com.openminis.app.ui.settings
import com.openminis.app.ui.theme.m3Icon
import com.openminis.app.ui.theme.IosPurple

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.GroupRoutingState
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.repository.editedQuotaWindow
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ModelGroupBuckets
import com.openminis.app.R
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.ui.components.SettingsRowDivider
import com.openminis.app.ui.RoutingStrategyUi

/**
 * Detail / edit screen for one ModelGroup. Adopted T79 sectioned style:
 * Name, Routing Strategy (+ optional Fallback Trigger sub-section),
 * Members (reorderable), and Actions. Each section gets a header and an
 * explanatory footer caption, matching the iOS-side detail screens.
 *
 * Uses a manually-rolled Scaffold instead of SettingsScaffold because
 * the members list needs LazyColumn for reordering — SettingsScaffold's
 * verticalScroll wrapper would conflict with the lazy list's infinite-
 * height measure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGroupDetailScreen(
    groupId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddModels: () -> Unit,
) {
    val config by providerRepository.config.collectAsStateWithLifecycle()
    val group = config.modelGroups.find { it.id == groupId }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var quotaSheetInstanceId by remember { mutableStateOf<String?>(null) }

    if (group == null) {
        onBack()
        return
    }

    var name by remember { mutableStateOf(group.name) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var strategy by remember { mutableStateOf(group.strategy) }
    var fallbackStrategy by remember { mutableStateOf(group.fallbackStrategy) }
    // T312: keyed on group.id (stable across nav/config-refresh; `group` key would re-fire each recomposition).
    var defaultThinkingLevel by remember(group.id) {
        mutableStateOf(group.defaultThinkingLevel)
    }
    var contextLimitTokens by remember(group.id) {
        mutableStateOf(group.contextLimitTokens)
    }
    // [T-routing-strategy-x-fix] Text buffers for the three scheduling-knob
    // editors. Keyed on group.id so a config refresh (which rebuilds
    // `group`) doesn't clobber an in-progress edit.
    val context = LocalContext.current
    var firstTokenText by remember(group.id) {
        mutableStateOf(group.firstTokenTimeoutMs?.toString() ?: "")
    }
    var quotaTotalText by remember(group.id) {
        mutableStateOf(group.quotaWindowTotal?.toString() ?: "")
    }
    var quotaPeriodHoursText by remember(group.id) {
        mutableStateOf(group.quotaWindowPeriodMs?.div(3_600_000L)?.toString() ?: "")
    }

    // Local copy of member IDs for live reorder
    var memberIds by remember(group.memberEntryIds) { mutableStateOf(group.memberEntryIds.toList()) }
    var entryToRemove by remember { mutableStateOf<String?>(null) }

    val memberBucketById = remember(memberIds, config) {
        buildMap {
            for (id in memberIds) {
                val entry = config.modelEntries.find { it.id == id } ?: continue
                val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
                val secret = providerRepository.usableApiKey(instance)
                put(
                    id,
                    ModelGroupBuckets.bucket(
                        instance.effectiveBaseURL ?: instance.customBaseURL,
                        secret,
                        entry.model.id,
                    ),
                )
            }
        }
    }
    val duplicateBuckets = remember(memberBucketById) {
        ModelGroupBuckets.duplicateBuckets(memberBucketById.values)
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // from.index / to.index are LazyColumn global indices (include header items).
        // Use the item key (entryId string) to find the correct position in memberIds.
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        val fromIdx = memberIds.indexOf(fromKey)
        val toIdx = memberIds.indexOf(toKey)
        if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
        memberIds = memberIds.toMutableList().apply {
            add(toIdx, removeAt(fromIdx))
        }
        providerRepository.updateGroup(group.copy(memberEntryIds = memberIds.toMutableList()))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MinisCenterTopBar(
                title = { Text(group.name, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(m3Icon(Icons.AutoMirrored.Filled.ArrowBack), contentDescription = stringResource(R.string.model_group_detail_back))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Name section ──────────────────────────────────────────
            item {
                SettingsSection(
                    header = stringResource(R.string.model_group_detail_name),
                    footer = stringResource(R.string.model_group_detail_the_label_shown_in_the_model_picker_save),
                ) {
                    SettingsCardBlock {
                        SectionTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            fieldModifier = Modifier.onFocusChanged { focusState ->
                                if (!focusState.isFocused && name.isNotBlank() && name != group.name) {
                                    providerRepository.updateGroup(group.copy(name = name))
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Name saved")
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // ── Routing Strategy ──────────────────────────────────────
            item {
                SettingsSection(
                    header = stringResource(R.string.model_group_detail_routing_strategy),
                    footer = stringResource(RoutingStrategyUi.footerRes(strategy)),
                ) {
                    // [T-routing-strategy-x] Shared choice-row template; labels in RoutingStrategyUi.
                    RoutingStrategy.entries.forEach { candidate ->
                        SettingsChoiceRow(
                            title = stringResource(RoutingStrategyUi.titleRes(candidate)),
                            selected = strategy == candidate,
                            onSelect = {
                                strategy = candidate
                                providerRepository.updateGroup(group.copy(strategy = candidate))
                            },
                            showDivider = candidate != RoutingStrategy.smart,
                        )
                    }
                }
            }

            // ── First-token timeout (group override, all strategies) ──
            item {
                SettingsSection(
                    header = stringResource(R.string.model_group_detail_first_token_timeout),
                    footer = stringResource(R.string.model_group_detail_first_token_timeout_footer),
                ) {
                    SettingsCardBlock {
                        SectionTextField(
                            value = firstTokenText,
                            onValueChange = { input -> firstTokenText = input.filter { it.isDigit() } },
                            placeholder = stringResource(R.string.model_group_detail_first_token_timeout_placeholder),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            fieldModifier = Modifier.onFocusChanged { fs ->
                                if (!fs.isFocused) {
                                    val seconds = firstTokenText.toIntOrNull()?.takeIf { it > 0 }
                                    if (seconds != group.firstTokenTimeoutMs) {
                                        providerRepository.updateGroup(
                                            group.copy(firstTokenTimeoutMs = seconds),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // ── Member weights (weighted routing) ────────────────────
            if (strategy == RoutingStrategy.weighted) {
                item {
                    SettingsSection(
                        header = stringResource(R.string.model_group_detail_member_weights),
                        footer = stringResource(R.string.model_group_detail_member_weights_footer),
                    ) {
                        SettingsCardBlock {
                            memberIds.forEachIndexed { idx, memberId ->
                                val entry = config.modelEntries.find { it.id == memberId }
                                if (entry != null) {
                                    Column {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = entry.model.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            PlusMinusStepper(
                                                value = group.memberWeights[memberId] ?: 1,
                                                min = 1,
                                                max = 99,
                                                onValueChange = { newWeight ->
                                                    providerRepository.updateGroup(
                                                        group.copy(
                                                            memberWeights = group.memberWeights + (memberId to newWeight),
                                                        ),
                                                    )
                                                },
                                                decreaseContentDescription = stringResource(R.string.model_group_detail_weight_decrease),
                                                increaseContentDescription = stringResource(R.string.model_group_detail_weight_increase),
                                            )
                                        }
                                        if (idx != memberIds.lastIndex) SettingsRowDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Quota window (quotaWindow routing) ───────────────────
            if (strategy == RoutingStrategy.quotaWindow) {
                item {
                    // Editing either value re-arms the whole window via the
                    // pure editedQuotaWindow math (remaining refills to the new
                    // total, resetAt moves to now + period), then the live
                    // router state is synced so the very next pick sees it.
                    val applyQuotaWindow: () -> Unit = {
                        val edit = editedQuotaWindow(
                            current = group,
                            totalText = quotaTotalText,
                            periodHoursText = quotaPeriodHoursText,
                            nowMs = System.currentTimeMillis(),
                        )
                        if (edit != null) {
                            providerRepository.updateGroup(
                                group.copy(
                                    quotaWindowTotal = edit.total,
                                    quotaWindowPeriodMs = edit.periodMs,
                                    quotaWindowResetAt = edit.resetAt,
                                    quotaWindowRemaining = edit.remaining,
                                ),
                            )
                            edit.remaining?.let { GroupRoutingState.syncQuotaWindow(group.id, it) }
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.model_group_detail_quota_window_reset_snackbar),
                                )
                            }
                        }
                    }
                    QuotaWindowEditorSection(
                        group = group,
                        quotaTotalText = quotaTotalText,
                        onQuotaTotalTextChange = { quotaTotalText = it },
                        quotaPeriodHoursText = quotaPeriodHoursText,
                        onQuotaPeriodHoursTextChange = { quotaPeriodHoursText = it },
                        applyQuotaWindow = applyQuotaWindow,
                        focusManager = focusManager,
                    )
                }
            }

            // ── Fallback Trigger (only when strategy = fallback) ──────
            if (strategy == RoutingStrategy.fallback) {
                item {
                    FallbackTriggerSection(
                        group = group,
                        fallbackStrategy = fallbackStrategy,
                        onFallbackStrategyChange = { next ->
                            fallbackStrategy = next
                            providerRepository.updateGroup(group.copy(fallbackStrategy = next))
                        },
                    )
                }
            }

            // ── Members section header ───────────────────────────────
            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.model_group_detail_models_header_count, memberIds.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
                )
                Text(
                    text = stringResource(R.string.model_group_detail_members_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }

            if (memberIds.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            "No models in this group.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            } else {
                itemsIndexed(memberIds, key = { _, id -> id }) { _, entryId ->
                    // [T-android-provider-voice] System voice sentinels aren't
                    // persisted entries — resolve them to virtual entries so the
                    // default Voice Input/Output members render normally instead
                    // of as stale rows (mirrors iOS system virtual entries).
                    val entry = config.modelEntries.find { it.id == entryId }
                        ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId)

                    if (entry == null) {
                        // Stale member: UUID no longer resolves to any ModelEntry
                        ReorderableItem(reorderState, key = entryId) { _ ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        "Model no longer available",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        entryId,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = m3Icon(Icons.Default.Warning),
                                        contentDescription = stringResource(R.string.model_group_detail_unavailable),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = {
                                        val newIds = memberIds.toMutableList().apply { remove(entryId) }
                                        memberIds = newIds
                                        providerRepository.updateGroup(group.copy(memberEntryIds = newIds.toMutableList()))
                                    }) {
                                        Icon(
                                            imageVector = m3Icon(Icons.Default.Delete),
                                            contentDescription = stringResource(R.string.common_remove),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                            )
                        }
                    } else {
                        // Normal member row
                        val instance = config.instances.find { it.id == entry.providerInstanceId }
                        val instanceLabel = instance?.label
                        val providerDisabled = instance?.isEnabled == false
                        var showMenu by remember { mutableStateOf(false) }

                        ReorderableItem(reorderState, key = entryId) { _ ->
                            ListItem(
                                modifier = Modifier.alpha(if (providerDisabled) 0.4f else 1f),
                                headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(entry.model.displayName)
                                        if (instance != null && !providerDisabled) {
                                            Spacer(Modifier.width(6.dp))
                                            com.openminis.app.ui.quota.ModelQuotaMemberPill(instanceId = entry.providerInstanceId, modelId = entry.model.id) { quotaSheetInstanceId = entry.providerInstanceId }
                                        }
                                    }
                                },
                                supportingContent = {
                                    if (providerDisabled) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(instanceLabel ?: "")
                                            Text(
                                                " · Provider disabled",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    } else {
                                        val bucket = memberBucketById[entryId]
                                        val caption = bucket?.let { ModelGroupBuckets.caption(it) }.orEmpty()
                                        val dup = bucket != null && bucket in duplicateBuckets
                                        Column {
                                            Text(instanceLabel ?: "")
                                            if (caption.isNotEmpty()) {
                                                Text(
                                                    text = stringResource(
                                                        if (dup) R.string.model_group_detail_bucket_duplicate
                                                        else R.string.model_group_detail_bucket_line,
                                                        caption,
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (dup) MaterialTheme.colorScheme.error
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                },
                                leadingContent = {
                                    IconButton(
                                        modifier = Modifier.draggableHandle(),
                                        onClick = {},
                                    ) {
                                        Icon(m3Icon(Icons.Default.DragHandle), contentDescription = stringResource(R.string.model_group_detail_drag_to_reorder))
                                    }
                                },
                                trailingContent = {
                                    Box {
                                        IconButton(onClick = { showMenu = true }) {
                                            Icon(m3Icon(Icons.Default.MoreVert), contentDescription = stringResource(R.string.model_group_detail_more_options))
                                        }
                                        com.openminis.app.ui.components.MinisMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.model_group_detail_remove_from_group), color = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    showMenu = false
                                                    entryToRemove = entryId
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (duplicateBuckets.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.model_group_detail_duplicate_banner),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                    )
                }
            }

            // ── Add models: directly under the member list it acts on ────────
            // Previously this sat at the very bottom, below Session Defaults and
            // immediately above the destructive "Delete group" button. That put
            // an additive action two sections away from the list it appends to,
            // and pair-adjacent to a destructive one — easy to mis-tap and hard
            // to find. Placing it right after the list makes the relationship
            // obvious and leaves "Delete group" alone at the bottom.
            item {
                Spacer(Modifier.height(12.dp))
                MinisOutlinedButton(
                    onClick = onAddModels,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.model_group_detail_add_models))
                }
            }

            // ── Session Defaults (T312, mirrors iOS ModelGroupDetailView) ────
            item {
                SettingsSection(
                    header = stringResource(R.string.model_group_detail_session_defaults),
                    footer = stringResource(R.string.model_group_detail_session_defaults_footer),
                ) {
                    val reasoningEnabled = defaultThinkingLevel != null
                    SettingsSwitchRow(
                        title = stringResource(R.string.model_group_detail_enable_reasoning),
                        checked = reasoningEnabled,
                        onCheckedChange = { on ->
                            // iOS parity: ON defaults to MEDIUM, OFF clears to null.
                            val newLevel = if (on) ThinkingLevel.MEDIUM else null
                            defaultThinkingLevel = newLevel
                            providerRepository.updateGroup(
                                group.copy(defaultThinkingLevel = newLevel)
                            )
                        },
                        icon = m3Icon(Icons.Default.Psychology),
                        iconColor = IosPurple,
                        showDivider = reasoningEnabled,
                    )
                    if (reasoningEnabled) {
                        // [T-android-thinking-level-arch] The group's thinking
                        // CEILING is the HIGHEST level any reasoning-capable
                        // member supports — max(), not min() (mirrors iOS
                        // e51fef5d/53388728). min() was wrong twice:
                        //   1. A non-reasoning member (e.g. an image model with
                        //      effectiveMaxThinkingLevel == OFF) dragged the
                        //      ceiling to OFF and emptied the Intensity picker,
                        //      leaving "Enable Reasoning" on with no selector.
                        //   2. Even among reasoning members it capped the group at
                        //      the WEAKEST model — a group of [Opus MAX, a HIGH
                        //      model] would only offer up to High, hiding the tiers
                        //      Opus supports.
                        // The per-request clamp (provider layer) still caps to the
                        // ACTUAL model at send time, so offering the true ceiling
                        // here is safe. Exclude OFF; fall back to XHIGH when no
                        // member reasons (only reached with reasoning enabled).
                        val groupMaxThinkingLevel = remember(group.memberEntryIds, config.modelEntries) {
                            group.memberEntryIds
                                .mapNotNull { entryId ->
                                    config.modelEntries.find { it.id == entryId }
                                        ?.effectiveMaxThinkingLevel
                                }
                                .filter { it != ThinkingLevel.OFF }
                                .maxByOrNull { it.rank }
                                ?: ThinkingLevel.XHIGH
                        }
                        val labelFor: @Composable (ThinkingLevel) -> String = { level ->
                            when (level) {
                                ThinkingLevel.LOW -> stringResource(R.string.model_group_detail_thinking_low)
                                ThinkingLevel.MEDIUM -> stringResource(R.string.model_group_detail_thinking_medium)
                                ThinkingLevel.HIGH -> stringResource(R.string.model_group_detail_thinking_high)
                                ThinkingLevel.XHIGH -> stringResource(R.string.model_group_detail_thinking_xhigh)
                                ThinkingLevel.MAX -> stringResource(R.string.model_group_detail_thinking_max)
                                ThinkingLevel.ULTRA -> stringResource(R.string.model_group_detail_thinking_ultra)
                                ThinkingLevel.OFF -> stringResource(R.string.model_group_detail_thinking_low)
                            }
                        }
                        // Steps exclude OFF (the toggle above carries OFF
                        // semantics) and everything above the group ceiling.
                        val cases = ThinkingLevel.entries
                            .filter { it != ThinkingLevel.OFF && it.rank <= groupMaxThinkingLevel.rank }
                            .map { it to labelFor(it) }
                        SettingsCardBlock {
                            Text(
                                text = stringResource(R.string.model_group_detail_intensity),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                cases.forEachIndexed { idx, (level, label) ->
                                    SegmentedButton(
                                        selected = defaultThinkingLevel == level,
                                        onClick = {
                                            defaultThinkingLevel = level
                                            providerRepository.updateGroup(
                                                group.copy(defaultThinkingLevel = level)
                                            )
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = idx,
                                            count = cases.size,
                                        ),
                                    ) { Text(label) }
                                }
                            }
                        }
                    }
                    ContextLimitSection(
                        group = group,
                        contextLimitTokens = contextLimitTokens,
                        onContextLimitTokensChange = { contextLimitTokens = it },
                        onPersist = { tokens, last ->
                            providerRepository.updateGroup(
                                group.copy(
                                    contextLimitTokens = tokens,
                                    lastContextLimitTokens = last,
                                )
                            )
                        },
                    )
                }
            }

            // ── Destructive action: delete group ──────────────────────
            // "Add models" used to share this block; it now sits directly under
            // the member list, so the only thing left at the bottom is the
            // destructive action, with nothing adjacent to mis-tap.
            item {
                Spacer(Modifier.height(20.dp))
                MinisButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.model_group_detail_delete_group))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // Confirm remove model from group
    if (entryToRemove != null) {
        val removingId = entryToRemove!!
        val removingEntry = config.modelEntries.find { it.id == removingId }
        AlertDialog(
            onDismissRequest = { entryToRemove = null },
            title = { Text(stringResource(R.string.model_group_detail_remove_model)) },
            text = {
                val name = removingEntry?.model?.displayName
                    ?: stringResource(R.string.model_group_detail_remove_this_model_fallback)
                Text(stringResource(R.string.model_group_detail_remove_named_model_confirm, name))
            },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        val newIds = memberIds.toMutableList().apply { remove(removingId) }
                        memberIds = newIds
                        providerRepository.updateGroup(group.copy(memberEntryIds = newIds.toMutableList()))
                        entryToRemove = null
                    },
                ) {
                    Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { entryToRemove = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // Confirm delete group
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.model_group_detail_delete_group)) },
            text = { Text(stringResource(R.string.model_group_detail_delete_named_group_confirm, group.name)) },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        providerRepository.removeGroup(groupId)
                        showDeleteDialog = false
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    quotaSheetInstanceId?.let { com.openminis.app.ui.quota.QuotaDetailSheet(instanceId = it, onDismiss = { quotaSheetInstanceId = null }, halfHeight = false) }
}
