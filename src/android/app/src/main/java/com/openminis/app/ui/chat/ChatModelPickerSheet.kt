package com.openminis.app.ui.chat

// [T-android-split-chat] ModelPickerSheet (+ its private helpers fuzzyMatch /
// ModelPickerSheet is internal so ChatScreen can call it.
//
// [R4+R10] The sheet chrome (drag handle, title bar, search capsule, 0.9f
// container, LazyColumn scaffold) and the shared row atoms (section card,
// section header, provider card header, collapse chevron, selection mark,
// Active badge) live in ui/components/PickerSheetShell.kt and
// MinisSheetDragHandle.kt. What stays here is this picker's own difference
// layer: group rows with strategy badges / resolved-entry subtitles / default
// badge / edit affordance, brand-icon entry rows with modality chips, the
// collapsed provider summary with its "Show N models" row, the empty state,
// and the keyed Quick Test host.

import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.theme.m3Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.PickerActiveBadge
import com.openminis.app.ui.components.PickerCollapseChevron
import com.openminis.app.ui.components.PickerProviderCardHeader
import com.openminis.app.ui.components.PickerSectionCard
import com.openminis.app.ui.components.PickerSectionHeader
import com.openminis.app.ui.components.PickerSelectionMark
import com.openminis.app.ui.components.PickerSheetShell
/**
 * Fuzzy match: substring first, then all query chars appear in order.
 * Matches iOS SessionModelPicker.fuzzyMatch.
 */
private fun fuzzyMatch(text: String, query: String): Boolean {
    if (query.isEmpty()) return true
    val q = query.lowercase()
    val t = text.lowercase()
    if (t.contains(q)) return true
    var idx = 0
    for (ch in q) {
        val found = t.indexOf(ch, idx)
        if (found < 0) return false
        idx = found + 1
    }
    return true
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ModelPickerSheet(
    groups: List<ModelGroup>,
    selectedGroupId: String?,
    activeEntryId: String?,
    defaultPrimaryGroupId: String?,
    config: ProviderConfig,
    providerRepository: ProviderRepository,
    onSelectGroup: (String) -> Unit,
    onSelectGroupEntry: (String, String) -> Unit,
    onSelectEntry: (String) -> Unit,
    onDismiss: () -> Unit,
    /** [T-android-modelpicker-group-edit] "Edit" affordance on the Model Groups
     *  section header — dismisses the sheet and navigates to the Model Groups
     *  management screen. Null hides the button (callers without a route). */
    onEditGroups: (() -> Unit)? = null,
) {
    val openTime = remember { System.nanoTime() }

    LaunchedEffect(Unit) {
        AppLogger.info("ModelPicker", "[ModelPicker] open triggered")
        withFrameNanos { frameTime ->
            val ms = (frameTime - openTime) / 1_000_000.0
            AppLogger.info("ModelPicker", "[ModelPicker] first frame rendered: ${"%.1f".format(ms)}ms (total since trigger)")
        }
    }

    var searchText by remember { mutableStateOf("") }
    var expandedGroupIds by remember { mutableStateOf(setOf<String>()) }
    // Note: the non-text-output "may not work as an Agent" confirmation lives
    // in ChatScreen's callback wrappers (ee828dba), NOT here — the sheet stays
    // a dumb list and the caller owns selection policy.
    val allInstanceIds = remember(config) {
        config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    }
    var collapsedInstanceIds by remember(allInstanceIds) {
        mutableStateOf(allInstanceIds)
    }

    /**
     * [T-android-model-picker-polish] Model whose Quick Test sheet is open.
     *
     * Reuses the same [com.openminis.app.ui.components.QuickTestSheet] the
     * provider settings screens already use, as iOS reuses ModelQuickTestSheet
     * from its picker — a model that behaves one way when tested from Settings
     * and another from the picker would be worse than no button at all.
     */
    var quickTestEntry by remember { mutableStateOf<ModelEntry?>(null) }

    // Filtered groups
    val filteredGroups = remember(groups, searchText) {
        if (searchText.isEmpty()) groups
        else {
            val t0 = System.nanoTime()
            val result = groups.filter { fuzzyMatch(it.name, searchText) }
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            AppLogger.info("ModelPicker", "[ModelPicker] filter groups: ${result.size}/${groups.size}, ${"%.1f".format(ms)}ms")
            result
        }
    }

    // Filtered entries by instance
    val allInstancesWithEntries = remember(config, searchText) {
        val t0 = System.nanoTime()
        var totalCount = 0
        val result = config.instances
            .filter { it.isEnabled }
            .map { instance ->
                val pt = System.nanoTime()
                val entries = config.modelEntries.filter {
                    it.providerInstanceId == instance.id && !it.isHidden
                }
                val filtered = if (searchText.isEmpty()) entries
                else entries.filter {
                    fuzzyMatch(it.model.displayName, searchText) || fuzzyMatch(it.model.id, searchText)
                }
                val pms = (System.nanoTime() - pt) / 1_000_000.0
                if (filtered.isNotEmpty()) {
                    totalCount += filtered.size
                    AppLogger.info("ModelPicker", "[ModelPicker] provider \"${instance.label.ifEmpty { instance.providerType.displayName }}\" models loaded: ${filtered.size} items, ${"%.1f".format(pms)}ms")
                }
                instance to filtered
            }
            .filter { it.second.isNotEmpty() }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        AppLogger.info("ModelPicker", "[ModelPicker] all providers loaded: total $totalCount items, ${"%.1f".format(ms)}ms")
        result
    }

    PickerSheetShell(
        title = stringResource(R.string.model_picker_title),
        searchText = searchText,
        onSearchTextChange = { searchText = it },
        onDismiss = onDismiss,
    ) {
        // ── Model Groups (grouped section card with embedded header) ──
        if (filteredGroups.isNotEmpty()) {
            item {
                PickerSectionCard {
                    // [T-android-modelpicker-group-edit] Section header row:
                    // label on the left, an "Edit" button on the right that
                    // jumps to the Model Groups management screen (mirrors the
                    // iOS picker's group-section edit affordance). Button
                    // hidden when no route is wired.
                    PickerSectionHeader(
                        stringResource(R.string.model_picker_groups_section),
                        trailing = if (onEditGroups != null) {
                            {
                                // Text button (not a pencil icon) matching the
                                // sheet's other text actions like "Done".
                                MinisTextButton(
                                    onClick = onEditGroups,
                                    modifier = Modifier.padding(end = 8.dp),
                                ) {
                                    Text(stringResource(R.string.model_picker_groups_edit))
                                }
                            }
                        } else null,
                    )
                    filteredGroups.forEachIndexed { index, group ->
                        val isSelected = group.id == selectedGroupId
                        val isDefault = group.id == defaultPrimaryGroupId
                        val isExpanded = expandedGroupIds.contains(group.id)
                        val strategyLabel = when (group.strategy) {
                            RoutingStrategy.fallback -> "FB"
                            RoutingStrategy.loadBalance -> "LB"
                        }
                        // Resolve entry: try memberEntryIds first, fallback to activeEntryId ONLY if this group is selected
                        // SystemVoiceEntries fallback: voice groups are
                        // seeded with "__builtin_system_speech__/…"
                        // members that are synthesized on demand and
                        // never stored in config.modelEntries, so
                        // matching modelEntries alone counts them as 0.
                        val resolvedEntry = group.memberEntryIds.firstNotNullOfOrNull { entryId ->
                            config.modelEntries.find { it.id == entryId }
                                ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId)
                        } ?: if (isSelected && activeEntryId != null) config.modelEntries.find { it.id == activeEntryId } else null
                        // Count of resolved members for display
                        val resolvedCount = group.memberEntryIds.count { entryId ->
                            config.modelEntries.any { it.id == entryId } ||
                                com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId) != null
                        }

                        // Header sits above the first row, so the
                        // first-row corners are no longer rounded
                        // (only the bottom row of the last group is).
                        val groupShape = when {
                            index == filteredGroups.size - 1 -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                            else -> RoundedCornerShape(0.dp)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(groupShape)
                                .clickable { onSelectGroup(group.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Group rows use the 22dp GREEN variant of the
                            // selection mark (a selected GROUP, not the blue
                            // per-entry mark).
                            PickerSelectionMark(
                                selected = isSelected,
                                size = 22.dp,
                                selectedTint = Color(0xFF34C759),
                            )
                            Spacer(Modifier.width(10.dp))
                            // [T-android-model-picker-polish] Group
                            // glyph, matching iOS's blue "layers" mark
                            // (SessionModelPicker). Without it a group
                            // row and a provider row differ only by
                            // their subtitle, which is easy to miss
                            // when scrolling — and the two mean very
                            // different things (a group can fail over
                            // or load-balance; a model cannot).
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = null,
                                tint = Color(0xFF007AFF),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        group.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    // iOS: ⊕ FB / ⊕ LB badge with icon
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                                RoundedCornerShape(8.dp),
                                            )
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        Icon(
                                            if (group.strategy == RoutingStrategy.fallback)
                                                Icons.Default.ArrowCircleDown
                                            else Icons.Default.AccountTree,
                                            contentDescription = null,
                                            modifier = Modifier.size(9.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Text(
                                            strategyLabel,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                                // iOS: "→ ModelName" resolved entry
                                if (resolvedEntry != null) {
                                    Text(
                                        "→ ${resolvedEntry.model.displayName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                } else if (resolvedCount > 0) {
                                    Text(
                                        pluralStringResource(R.plurals.model_picker_models_count, resolvedCount, resolvedCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                } else {
                                    Text(
                                        stringResource(R.string.model_picker_models_count_unlinked, group.memberEntryIds.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                }
                            }

                            // [T-android-model-picker-polish] "Default"
                            // badge, matched to iOS
                            // (UnifiedModelPicker.swift:669-674):
                            // caption2 medium on a 10%-blue capsule with
                            // 5/1 padding. Android was running 6/2 —
                            // double the vertical inset — which made the
                            // capsule noticeably taller than the group
                            // name beside it and pulled the eye away from
                            // the group's own label. Font drops 10sp -> 9sp
                            // to match caption2's optical weight at this
                            // density, and lineHeight is pinned so Compose
                            // does not re-add the font's ascent/descent
                            // slack on top of the 1.dp padding.
                            if (isDefault) {
                                Text(
                                    stringResource(R.string.model_picker_default_badge),
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF007AFF),
                                    modifier = Modifier
                                        .background(
                                            Color(0xFF007AFF).copy(alpha = 0.1f),
                                            RoundedCornerShape(50),
                                        )
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                            }

                            // Expand/collapse chevron — the shared 24dp
                            // circled [PickerCollapseChevron] (its comment
                            // carries the iOS-matching rationale).
                            PickerCollapseChevron(
                                expanded = isExpanded,
                                onToggle = {
                                    expandedGroupIds = if (isExpanded) {
                                        expandedGroupIds - group.id
                                    } else {
                                        expandedGroupIds + group.id
                                    }
                                },
                            )
                        }

                        // Expanded group member entries
                        if (isExpanded) {
                            // Resolve actual entries from memberEntryIds
                            val resolvedMembers = group.memberEntryIds.mapNotNull { entryId ->
                                config.modelEntries.find { it.id == entryId }
                                    ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId)
                            }
                            // Fallback: show active entry ONLY if this group is selected
                            val displayMembers = resolvedMembers.ifEmpty {
                                if (isSelected && activeEntryId != null)
                                    listOfNotNull(config.modelEntries.find { it.id == activeEntryId })
                                else emptyList()
                            }
                            if (displayMembers.isEmpty()) {
                                Text(
                                    stringResource(R.string.model_picker_no_linked_models),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp),
                                )
                            }
                            displayMembers.forEachIndexed { memberIndex, entry ->
                                // [T-android-model-picker-polish]
                                // Hairline between members. The group
                                // CARDS had one and the provider rows
                                // had one, but the members inside an
                                // expanded group did not — so a group
                                // with several models read as one
                                // undifferentiated block, which is
                                // exactly where separation matters
                                // most (these rows are indented and
                                // visually similar). Drawn BEFORE each
                                // row except the first, so it never
                                // collides with the divider that
                                // follows the group card itself.
                                if (memberIndex > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    )
                                }
                                val isActive = activeEntryId == entry.id
                                val instance = config.instances.find { it.id == entry.providerInstanceId }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectGroupEntry(group.id, entry.id) }
                                        .padding(start = 48.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (isActive) m3Icon(Icons.Default.CheckCircle) else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isActive) Color(0xFF007AFF)
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                        modifier = Modifier.size(17.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    // [T-brand-icon-in-picker] Vendor LOGO, not a
                                    // provider-color dot: the dot only encoded
                                    // the relay's ProviderType, so every
                                    // OpenAI-typed relay looked identical.
                                    // Resolve(modelId, displayName) — the type is
                                    // NOT passed: a relay serves any vendor.
                                    ModelBrandIcon(
                                        slug = ModelBrandResolver.resolve(
                                            entry.model.id,
                                            entry.model.displayName,
                                        ),
                                        sizeDp = 16.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.model.displayName, style = MaterialTheme.typography.bodyMedium)
                                        Row {
                                            if (instance != null) {
                                                Text(
                                                    instance.label.ifEmpty { instance.providerType.displayName },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                )
                                                Text(
                                                    " · ",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                )
                                            }
                                            Text(
                                                entry.model.id,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            )
                                        }
                                    }
                                    if (isActive) {
                                        PickerActiveBadge()
                                    }
                                    QuickTestButton(onClick = { quickTestEntry = entry })
                                }
                            }
                        }

                        // Inset hairline between groups, matches MinisMenuDivider rhythm.
                        if (index < filteredGroups.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 48.dp, end = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                        }
                    }
                }
            }

            if (searchText.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.model_picker_groups_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        // T236: hint sits between Model Groups card and
                        // first Provider card — top 8 hugs the group
                        // card, bottom 12 separates from the next card.
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                    )
                }
            }
        }

        // ── Individual Models by Provider (one section card per provider) ──
        // Each provider becomes a single grouped card containing: an
        // embedded header row with the collapse chevron, then either
        // the collapsed summary row or the expanded entry list. Cards
        // are visually separated from each other by a 12dp gap, and
        // sit on a higher tonal surface so the boundary between
        // providers is unmistakable even on the dark sheet background.
        if (allInstancesWithEntries.isNotEmpty()) {
            allInstancesWithEntries.forEach { (instance, entries) ->
                val isCollapsed = collapsedInstanceIds.contains(instance.id)
                item(key = "section_${instance.id}") {
                    PickerSectionCard {
                        // Header row, embedded in the card: provider label +
                        // 24dp circled chevron + hairline, all shared via
                        // [PickerProviderCardHeader].
                        PickerProviderCardHeader(
                            label = instance.label.ifEmpty { instance.providerType.displayName },
                            expanded = !isCollapsed,
                            onToggle = {
                                collapsedInstanceIds = if (isCollapsed) {
                                    collapsedInstanceIds - instance.id
                                } else {
                                    collapsedInstanceIds + instance.id
                                }
                            },
                        )

                        if (isCollapsed) {
                            // Collapsed summary — show selected or first entry + model count.
                            val selectedEntry = entries.firstOrNull { it.id == activeEntryId && selectedGroupId == null }
                            val displayEntry = selectedEntry ?: entries.firstOrNull()
                            if (displayEntry != null) {
                                Row(
                                    // T236: collapsed summary row —
                                    // vertical 14→8 + heightIn(min=48dp)
                                    // so tap target stays comfortable.
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                                        .clickable { onSelectEntry(displayEntry.id) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PickerSelectionMark(selected = selectedEntry != null)
                                    Spacer(Modifier.width(10.dp))
                                    ModelBrandIcon(
                                        slug = ModelBrandResolver.resolve(
                                            displayEntry.model.id,
                                            displayEntry.model.displayName,
                                        ),
                                        sizeDp = 16.dp,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    // [T-android-provider-voice] Modality chips
                                    // (iOS entryRow badges) — without them a
                                    // voice seed serving as the collapsed
                                    // representative is indistinguishable from
                                    // a chat model. FlowRow so overflow wraps
                                    // whole chips instead of shattering them.
                                    FlowRow(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        itemVerticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            displayEntry.model.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        com.openminis.app.ui.components.modalityBadges(displayEntry.model).forEach { badge ->
                                            com.openminis.app.ui.components.ModalityBadge(badge)
                                        }
                                    }
                                    // [T-android-model-picker-polish]
                                    // Quick Test, not a model count. The
                                    // count is already stated by the
                                    // "Show N models" row directly below,
                                    // so repeating it here spent the row's
                                    // trailing slot on a duplicate —
                                    // where every OTHER model row in this
                                    // sheet carries a bolt. iOS puts the
                                    // bolt here for the same reason.
                                    QuickTestButton(onClick = { quickTestEntry = displayEntry })
                                }
                                // Only when there is something to show:
                                // a single-model provider's collapsed
                                // preview IS its entire list, so
                                // "Show 1 model" would expand to the
                                // exact row already on screen.
                                if (entries.size > 1) {
                                    // Hairline before the expand row: it is a
                                    // control, not another model, and butting
                                    // it against the summary row above made
                                    // the two read as one two-line entry.
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    )
                                    // [T-android-model-picker-polish] Explicit
                                    // "Show N models" affordance, as iOS has.
                                    // The chevron in the header already
                                    // expands, but it is a small target in the
                                    // corner and reads as decoration — the
                                    // collapsed row gave no hint that the
                                    // other N-1 models were one tap away.
                                    // Tapping the summary row itself SELECTS
                                    // that model, so expanding needed its own
                                    // control rather than sharing that one.
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 40.dp)
                                            .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                                            .clickable { collapsedInstanceIds = collapsedInstanceIds - instance.id }
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // Chevron LEADS the label and the row
                                        // starts at the card's own inset: the
                                        // arrow is what signals "this expands",
                                        // so it has to be the first thing read,
                                        // and the earlier 30.dp indent left it
                                        // floating under the model names above
                                        // rather than aligned with the card.
                                        Icon(
                                            m3Icon(Icons.Default.KeyboardArrowDown),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = Color(0xFF007AFF),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            pluralStringResource(
                                                R.plurals.model_picker_show_models,
                                                entries.size,
                                                entries.size,
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color(0xFF007AFF),
                                        )
                                    }
                                }
                            }
                        } else {
                            entries.forEachIndexed { index, entry ->
                                val isSelected = activeEntryId == entry.id && selectedGroupId == null
                                // Last row clips its own bottom so the
                                // ripple respects the card corners.
                                val rowShape = if (index == entries.size - 1) {
                                    RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                                } else {
                                    RoundedCornerShape(0.dp)
                                }
                                Row(
                                    // T236: expanded entry row —
                                    // vertical 14→8 + heightIn(min=48dp)
                                    // for accessible tap target.
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clip(rowShape)
                                        .clickable { onSelectEntry(entry.id) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PickerSelectionMark(selected = isSelected)
                                    Spacer(Modifier.width(10.dp))
                                    ModelBrandIcon(
                                        slug = ModelBrandResolver.resolve(
                                            entry.model.id,
                                            entry.model.displayName,
                                        ),
                                        sizeDp = 16.dp,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.model.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        // [T-android-provider-voice] Modality
                                        // chips (iOS entryRow badges). FlowRow so
                                        // an overflow wraps whole chips to the
                                        // next line instead of squeezing each
                                        // Text into a vertical letter column.
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            itemVerticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                entry.model.id,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            )
                                            com.openminis.app.ui.components.modalityBadges(entry.model).forEach { badge ->
                                                com.openminis.app.ui.components.ModalityBadge(badge)
                                            }
                                        }
                                    }
                                    if (selectedGroupId != null && activeEntryId == entry.id) {
                                        PickerActiveBadge()
                                    }
                                    QuickTestButton(onClick = { quickTestEntry = entry })
                                }
                                // Inset hairline between entries.
                                if (index < entries.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Empty / No Results ──
        if (filteredGroups.isEmpty() && allInstancesWithEntries.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        if (searchText.isNotEmpty()) m3Icon(Icons.Default.Search) else Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(if (searchText.isNotEmpty()) R.string.model_picker_no_results else R.string.model_picker_no_models),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (searchText.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.model_picker_configure_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.model_picker_try_different_search),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }

    // [T-android-model-picker-polish] Quick Test host. Keyed on the entry id so
    // switching models rebuilds the sheet's state rather than reusing a run
    // from the previously tested model — the same identity trap iOS documents
    // at UnifiedModelPicker.swift:524.
    quickTestEntry?.let { entry ->
        key(entry.id) {
            com.openminis.app.ui.components.QuickTestSheet(
                entry = entry,
                providerRepository = providerRepository,
                onDismiss = { quickTestEntry = null },
            )
        }
    }
}

/**
 * [T-android-model-picker-polish] Per-row Quick Test affordance, mirroring
 * iOS's `bolt.badge.checkmark` button (UnifiedModelPicker.swift:542).
 *
 * Sized to a 32dp target and given its own click surface so it never competes
 * with the row's select gesture: tapping the row picks the model, tapping the
 * bolt tests it without changing the selection.
 */
@Composable
private fun QuickTestButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Bolt,
            contentDescription = stringResource(R.string.model_picker_quick_test),
            tint = Color(0xFF007AFF),
            modifier = Modifier.size(17.dp),
        )
    }
}
