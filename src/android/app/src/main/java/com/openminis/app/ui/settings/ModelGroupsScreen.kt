package com.openminis.app.ui.settings
import com.openminis.app.ui.theme.m3Icon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.repository.AutoModelGroups
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.R
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import com.openminis.app.ui.components.SwipeRowAction
import com.openminis.app.ui.components.SwipeRowActions
import com.openminis.app.ui.components.SectionCard
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionDivider
import com.openminis.app.ui.components.SectionFooter
import com.openminis.app.ui.components.SectionHeader
import com.openminis.app.ui.RoutingStrategyUi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGroupsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onGroupClick: (String) -> Unit,
    /** [T-model-connection] true 时不画 Scaffold 顶栏，交给「模型与连接」聚合页。 */
    embedded: Boolean = false,
    /** T185: navigate to the entries picker for the agent-loop set.
     *  Replaces T182's in-screen ModalBottomSheet so the picker shape
     *  matches AddModelsToGroupScreen (shared composable). */
    onAddAgentLoopModels: () -> Unit = {},
    /** T185: navigate to the groups picker for the agent-loop set. */
    onAddAgentLoopGroups: () -> Unit = {},
) {
    val config by providerRepository.config.collectAsStateWithLifecycle()
    // [T-auto-model-groups] 隐式组（auto: 前缀）由供应商派生，只在聊天选择器出现，
    // 不进管理列表——否则用户会把它当成可编辑的组。
    val groups = AutoModelGroups.userGroups(config)
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    // [T-android-swipe-row-actions] Pending swipe-delete target, hoisted out of
    // the row so the confirmation survives recomposition/reorder.
    var groupToDelete by remember { mutableStateOf<ModelGroup?>(null) }

    // T186: parent LazyListState shared with rememberReorderableLazyListState
    // so each agent-loop pinned row (rendered as its own LazyColumn item)
    // can carry a Modifier.draggableHandle and reorder live. The reorder
    // callback resolves the dragged row by its key (entry id / group id),
    // permutes our local copy, then commits via ProviderRepository.
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        // Two reorder zones share the same lazyListState — entries
        // (key prefix "agent_entry:") and groups ("agent_group:").
        // Refuse cross-zone drags so a user can't accidentally drop an
        // entry into the groups zone (the data shape would reject it
        // anyway, but a no-op is friendlier than a snap-back).
        when {
            fromKey.startsWith("agent_entry:") && toKey.startsWith("agent_entry:") -> {
                val fromId = fromKey.removePrefix("agent_entry:")
                val toId = toKey.removePrefix("agent_entry:")
                val cur = providerRepository.config.value.agentLoopModelEntryIds.toList()
                val fromIdx = cur.indexOf(fromId)
                val toIdx = cur.indexOf(toId)
                if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
                val newOrder = cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                providerRepository.reorderAgentLoopEntries(newOrder)
            }
            fromKey.startsWith("agent_group:") && toKey.startsWith("agent_group:") -> {
                val fromId = fromKey.removePrefix("agent_group:")
                val toId = toKey.removePrefix("agent_group:")
                val cur = providerRepository.config.value.agentLoopGroupIds.toList()
                val fromIdx = cur.indexOf(fromId)
                val toIdx = cur.indexOf(toId)
                if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
                val newOrder = cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                providerRepository.reorderAgentLoopGroups(newOrder)
            }
            // [T-android-modelgroup-reorder] Third zone: the user's Model
            // Groups themselves ("日常/编程/翻译"…). NOTE the prefix check
            // order is safe: "agent_group:" does not start with "group:", so
            // the zones cannot cross-match.
            fromKey.startsWith("group:") && toKey.startsWith("group:") -> {
                val fromId = fromKey.removePrefix("group:")
                val toId = toKey.removePrefix("group:")
                val cur = AutoModelGroups.userGroups(providerRepository.config.value).map { it.id }
                val fromIdx = cur.indexOf(fromId)
                val toIdx = cur.indexOf(toId)
                if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
                val newOrder = cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                providerRepository.reorderModelGroups(newOrder)
            }
        }
    }

    val newGroupAction: @Composable () -> Unit = {
        IconButton(onClick = { showNewGroupDialog = true }) {
            Icon(
                m3Icon(Icons.Default.Add),
                contentDescription = stringResource(R.string.model_groups_new_group),
            )
        }
    }

    val listContent: @Composable (PaddingValues) -> Unit = { padding ->
            // T313: page background uses SectionDesign.screenBackgroundColor()
            // (= surfaceContainerLow), so the section cards painted in `surface`
            // visibly stand out as iOS-style inset-grouped panels.
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(SectionDesign.screenBackgroundColor()),
            ) {
                item("top_gap") { Spacer(modifier = Modifier.height(SectionDesign.FirstSectionTopGap)) }

                if (groups.isEmpty()) {
                    item("empty_state") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                m3Icon(Icons.Default.Layers),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.model_groups_no_model_groups),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.model_groups_groups_let_you_combine_models_for_fallba),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    // [T-android-modelgroup-reorder] Groups are individual LazyColumn items keyed "group:<id>" (not one item wrapping a forEach), so ReorderableLazyListState can reorder them; drag lives on the leading handle.
                    item("groups_section_header") {
                        SectionHeader(text = stringResource(R.string.agent_loop_models_groups))
                    }
                    itemsIndexed(groups, key = { _, g -> "group:${g.id}" }) { index, group ->
                        ReorderableItem(
                            state = reorderState,
                            key = "group:${group.id}",
                        ) { _ ->
                            Column {
                                if (index != 0) SectionDividerInsetCard()
                                Box(
                                    modifier = Modifier.cardRow(
                                        isFirst = index == 0,
                                        isLast = index == groups.lastIndex,
                                    ),
                                ) {
                                    // [T-android-swipe-row-actions] Replaces a SwipeToDismissBox that destroyed groups with no confirmation; Delete now routes through the same dialog/repo call as the detail screen.
                                    SwipeRowActions(
                                        actions = listOf(
                                            SwipeRowAction(
                                                label = stringResource(R.string.common_edit),
                                                icon = Icons.Filled.Edit,
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                onClick = { onGroupClick(group.id) },
                                            ),
                                            SwipeRowAction(
                                                label = stringResource(R.string.common_delete),
                                                icon = Icons.Filled.Delete,
                                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                                onClick = { groupToDelete = group },
                                            ),
                                        ),
                                    ) {
                                        GroupRow(
                                            group = group,
                                            config = config,
                                            onClick = { onGroupClick(group.id) },
                                            dragHandleModifier = with(this@ReorderableItem) {
                                                Modifier.draggableHandle()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                }

                // T182 / T185 / T186: Agent Loop Models section. Mirrors iOS
                // ModelGroupsView.swift L77-79's inline AgentLoopModelsSection
                // — header + curated rows + add-row + footer all directly in
                // this LazyColumn so the rows can participate in the parent's
                // ReorderableLazyListState (T186 drag-to-reorder).
                agentLoopModelsSectionItems(
                    providerRepository = providerRepository,
                    config = config,
                    reorderState = reorderState,
                    onAddModelsTap = onAddAgentLoopModels,
                    onAddGroupsTap = onAddAgentLoopGroups,
                )
            }
        }

        // T185: the in-screen ModalBottomSheet was replaced by full-screen
        // navigated picker screens (AddAgentLoopModelsScreen /
        // AddAgentLoopGroupsScreen). The "Add Models" / "Add Groups"
        // buttons inside AgentLoopModelsSection now invoke
        // onAddAgentLoopModels / onAddAgentLoopGroups passed from
        // AppNavigation, mirroring how AddModelsToGroupScreen is reached.

        if (showNewGroupDialog) {
            AlertDialog(
                onDismissRequest = {
                    showNewGroupDialog = false
                    newGroupName = ""
                },
                title = { Text(stringResource(R.string.model_groups_new_group)) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.model_groups_new_group_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = newGroupName,
                            onValueChange = { newGroupName = it },
                            label = { Text(stringResource(R.string.model_groups_group_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    MinisTextButton(
                        onClick = {
                            if (newGroupName.isNotBlank()) {
                                val newGroup = ModelGroup(name = newGroupName.trim())
                                providerRepository.addGroup(newGroup)
                                // Auto-set as primary default if it's the first group
                                if (AutoModelGroups.userGroups(config).size == 1 && config.defaultPrimaryGroupId == null) {
                                    providerRepository.defaultPrimaryGroupId = newGroup.id
                                }
                                newGroupName = ""
                                showNewGroupDialog = false
                            }
                        },
                        enabled = newGroupName.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.model_groups_create))
                    }
                },
                dismissButton = {
                    MinisTextButton(onClick = {
                        showNewGroupDialog = false
                        newGroupName = ""
                    }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }

        // [T-android-swipe-row-actions] Swipe-delete confirmation. Same title/body
        // strings and same removeGroup() call as ModelGroupDetailScreen's Delete
        // Group button — the swipe is a shortcut to that flow, not a second one.
        groupToDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { groupToDelete = null },
                title = { Text(stringResource(R.string.model_group_detail_delete_group)) },
                text = {
                    Text(
                        stringResource(
                            R.string.model_group_detail_delete_named_group_confirm,
                            target.name,
                        ),
                    )
                },
                confirmButton = {
                    MinisTextButton(
                        onClick = {
                            providerRepository.removeGroup(target.id)
                            groupToDelete = null
                        },
                    ) {
                        Text(
                            stringResource(R.string.common_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    MinisTextButton(onClick = { groupToDelete = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
    
    }

    if (embedded) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SectionDesign.screenBackgroundColor()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) { newGroupAction() }
            listContent(PaddingValues(0.dp))
        }
    } else {
        Scaffold(
            topBar = {
                MinisCenterTopBar(
                    title = { Text(stringResource(R.string.model_groups_model_groups)) },
                    actions = { newGroupAction() },
                )
            },
        ) { padding ->
            listContent(padding)
        }
    }
}

/**
 * T182 — inline section embedded at the bottom of ModelGroupsScreen.
 * Mirrors iOS `AgentLoopModelsSection` from
 * `src/ios/Views/Providers/AgentLoopModelsView.swift` L1-72. Renders
 * the user's curated agent-loop usable set: pinned groups (rendered
 * first, since picking a group implicitly pins all its members) then
 * pinned individual entries. Each row carries a × icon to remove the
 * pin; the section footer hosts two add-sheet trigger buttons.
 *
 * The picker layouts behind those triggers live in
 * `AgentLoopAddSheets.kt` and use `LazyColumn` so a user with a 369-
 * entry OpenRouter instance doesn't freeze Compose the way the pre-
 * T182 `AgentLoopModelsScreen` did when it materialised every entry
 * in a single `Column`.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun LazyListScope.agentLoopModelsSectionItems(
    providerRepository: ProviderRepository,
    config: com.openminis.app.data.model.ProviderConfig,
    reorderState: ReorderableLazyListState,
    onAddModelsTap: () -> Unit,
    onAddGroupsTap: () -> Unit,
) {
    // [T-android-agentloop-dup-key-crash] distinctBy id (belt-and-suspenders
    // alongside the data-layer sink dedup). A config that's ALREADY corrupted
    // with a duplicate id (persisted before the sink fix) would otherwise yield
    // two rows sharing the same LazyColumn/Reorderable key and crash on scroll
    // (IllegalArgumentException: Key "..." was already used). Deduping here
    // makes the screen render so the user can even reach a state where the
    // persisted list gets rewritten clean. totalRows / absIndex below derive
    // from these deduped lists, so the row math stays consistent.
    val pinnedGroups = config.agentLoopGroupIds
        .mapNotNull { gid -> config.modelGroups.find { it.id == gid } }
        .distinctBy { it.id }
    val pinnedEntries = config.agentLoopModelEntryIds
        .mapNotNull { eid -> config.modelEntries.find { it.id == eid } }
        .distinctBy { it.id }
    val isEmpty = pinnedGroups.isEmpty() && pinnedEntries.isEmpty()

    // T313 — agent-loop section. Reorder rows MUST stay as top-level
    // LazyColumn items (ReorderableLazyListState only sees direct child
    // items), so the card visual is built per-row via SectionDesign
    // tokens: every row is wrapped with cardRow(isFirst, isLast) which
    // applies horizontal insets, surface fill, and rounded corners only
    // on the first/last row. SectionDivider is emitted as its own item
    // between rows so the inner card reads as one continuous panel.
    item("agent_loop_section_spacer") {
        Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap))
    }
    item("agent_loop_section_header") {
        SectionHeader(text = stringResource(R.string.agent_loop_section_title))
    }

    if (isEmpty) {
        item("agent_loop_section_empty") {
            Box(modifier = Modifier.cardRow(isFirst = true, isLast = true)) {
                Text(
                    text = stringResource(R.string.agent_loop_section_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    } else {
        val totalRows = pinnedGroups.size + pinnedEntries.size
        itemsIndexed(pinnedGroups, key = { _, g -> "agent_group:${g.id}" }) { index, group ->
            ReorderableItem(
                state = reorderState,
                key = "agent_group:${group.id}",
            ) { _ ->
                val resolvedCount = group.memberEntryIds.count { mid ->
                    config.modelEntries.any { it.id == mid }
                }
                val subtitle = if (resolvedCount == 1) {
                    stringResource(R.string.agent_loop_models_model_count_singular)
                } else {
                    stringResource(R.string.agent_loop_models_model_count_plural, resolvedCount)
                }
                Column {
                    if (index != 0) SectionDividerInsetCard()
                    Box(
                        modifier = Modifier.cardRow(
                            isFirst = index == 0,
                            isLast = index == totalRows - 1,
                        ),
                    ) {
                        AgentLoopRow(
                            title = group.name,
                            subtitle = subtitle,
                            badge = stringResource(R.string.agent_loop_section_group_badge),
                            onRemove = { providerRepository.removeAgentLoopGroup(group.id) },
                            dragHandleModifier = Modifier.then(
                                with(this@ReorderableItem) {
                                    Modifier.draggableHandle()
                                }
                            ),
                        )
                    }
                }
            }
        }

        itemsIndexed(pinnedEntries, key = { _, e -> "agent_entry:${e.id}" }) { index, entry ->
            ReorderableItem(
                state = reorderState,
                key = "agent_entry:${entry.id}",
            ) { _ ->
                val instanceLabel = config.instances
                    .find { it.id == entry.providerInstanceId }
                    ?.label
                val absIndex = pinnedGroups.size + index
                Column {
                    if (absIndex != 0) SectionDividerInsetCard()
                    Box(
                        modifier = Modifier.cardRow(
                            isFirst = absIndex == 0,
                            isLast = absIndex == totalRows - 1,
                        ),
                    ) {
                        AgentLoopRow(
                            title = entry.model.displayName,
                            subtitle = instanceLabel,
                            badge = null,
                            onRemove = { providerRepository.removeAgentLoopEntry(entry.id) },
                            dragHandleModifier = Modifier.then(
                                with(this@ReorderableItem) {
                                    Modifier.draggableHandle()
                                }
                            ),
                        )
                    }
                }
            }
        }
    }

    item("agent_loop_section_add_buttons") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MinisOutlinedButton(
                onClick = onAddModelsTap,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    m3Icon(Icons.Default.Add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.agent_loop_section_add_models))
            }
            MinisOutlinedButton(
                onClick = onAddGroupsTap,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    m3Icon(Icons.Default.Add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.agent_loop_section_add_groups))
            }
        }
    }

    item("agent_loop_section_footer") {
        SectionFooter(text = stringResource(R.string.agent_loop_section_footer))
    }

    item("bottom_gap") { Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap)) }
}

/** Single row inside the agent-loop section. Drag handle on the left
 *  (T186), title + optional subtitle + optional "Group" badge in the
 *  middle, × to unpin on the right. The drag-handle modifier is built
 *  by the parent (since ReorderableItemScope.draggableHandle() is
 *  scope-bound) and threaded in here to keep the row composable
 *  scope-agnostic. */
@Composable
private fun AgentLoopRow(
    title: String,
    subtitle: String?,
    badge: String?,
    onRemove: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // T198: drag handle must be wrapped in IconButton — Icon is raw
        // vector graphics with no pointer consumer, so reorderable v2.4.0's
        // draggableHandle() never sees ACTION_DOWN/MOVE on a bare Icon.
        // IconButton is Compose's clickable container and consumes pointer
        // events, letting the dragHandleModifier route gestures correctly.
        IconButton(
            onClick = {},
            modifier = dragHandleModifier.size(36.dp),
        ) {
            Icon(
                imageVector = m3Icon(Icons.Default.DragHandle),
                contentDescription = stringResource(R.string.model_group_detail_drag_to_reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                if (badge != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    BadgeLabel(badge, MaterialTheme.colorScheme.primary)
                }
            }
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = m3Icon(Icons.Default.Close),
                contentDescription = stringResource(R.string.agent_loop_section_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
