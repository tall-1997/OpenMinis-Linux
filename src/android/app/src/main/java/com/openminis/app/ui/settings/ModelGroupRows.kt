package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.ui.RoutingStrategyUi
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.theme.m3Icon

/**
 * 模型组列表的单行渲染与卡片视觉辅助（cardRow / SectionDividerInsetCard /
 * 模态图标），从 ModelGroupsScreen 抽出以守住架构行数上限。
 */
// ── T313 LazyList card helpers ────────────────────────────────────────────
//
// SectionCard / SectionDivider from SectionDesign are designed for
// Column-scoped sections, but the agent-loop section needs each row to
// stay a top-level LazyColumn item so ReorderableLazyListState (T186) can
// see it. These helpers paint the SectionDesign card visual on a per-row
// basis: cardRow() applies horizontal insets + surface fill + per-row
// corner clipping; SectionDividerInsetCard() draws the inner divider
// between consecutive rows so the painted run reads as one panel.

@Composable
internal fun Modifier.cardRow(isFirst: Boolean, isLast: Boolean): Modifier {
    val shape = when {
        isFirst && isLast -> SectionDesign.CardShape
        isFirst -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        isLast -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        else -> RectangleShape
    }
    // Match SectionCard's inner Column vertical padding so first/last rows
    // get the same breathing room from the card edge as Sections 1 & 2.
    return this
        .padding(horizontal = SectionDesign.ScreenHorizontalPadding)
        .clip(shape)
        .background(SectionDesign.cardColor())
        .padding(
            top = if (isFirst) SectionDesign.CardInnerVerticalPadding else 0.dp,
            bottom = if (isLast) SectionDesign.CardInnerVerticalPadding else 0.dp,
        )
}

/** Divider between two cardRow rows. Painted on the same surface fill so
 *  it reads as an inset divider inside the card panel rather than a hard
 *  line floating on the page background. */
@Composable
internal fun SectionDividerInsetCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SectionDesign.ScreenHorizontalPadding)
            .background(SectionDesign.cardColor()),
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(start = SectionDesign.DividerStartInset),
            thickness = SectionDesign.DividerThickness,
            color = SectionDesign.dividerColor(),
        )
    }
}

/**
 * [T-android-modelgroup-modality-icons] One modality marker shown after a Model
 * Group title. `kind` is the modality string ("image"/"audio"/"video"/"pdf"/
 * "text") and `isOutput` distinguishes a generation output from an accepted
 * input. Ports iOS ModelGroupsView.GroupRow.topModalities /
 * modalityIcon (commits 7caa580a + e0a7cd5f).
 */
internal data class GroupModalityMarker(val kind: String, val isOutput: Boolean)

/**
 * Distinctiveness ranking, most distinctive first — mirrors iOS
 * `modalityPriority`. `text` input is intentionally absent (universal noise);
 * `text` output ranks last (implied for every model, only surfaces when a group
 * has nothing more distinctive). Transcription (audio-in) outranks other inputs
 * so Whisper-style groups get flagged even though their output is plain text.
 */
internal val GROUP_MODALITY_PRIORITY: List<GroupModalityMarker> = listOf(
    GroupModalityMarker("video", isOutput = true),
    GroupModalityMarker("image", isOutput = true),
    GroupModalityMarker("audio", isOutput = true),
    GroupModalityMarker("audio", isOutput = false),
    GroupModalityMarker("video", isOutput = false),
    GroupModalityMarker("image", isOutput = false),
    GroupModalityMarker("pdf", isOutput = false),
    GroupModalityMarker("text", isOutput = true),
)

/**
 * The group's top-2 most distinctive modalities across every member model's
 * inputs AND outputs, in priority order. Aggregates raw model modality lists
 * (Android convention — no capability inference); a null/empty outputModalities
 * counts as text output (iOS treats text-out as universal). Mirrors iOS
 * `GroupRow.topModalities`.
 */
internal fun groupTopModalities(
    group: ModelGroup,
    config: com.openminis.app.data.model.ProviderConfig,
): List<GroupModalityMarker> {
    val inputs = mutableSetOf<String>()
    val outputs = mutableSetOf<String>()
    for (entryId in group.memberEntryIds) {
        val model = config.modelEntries.find { it.id == entryId }?.model ?: continue
        model.inputModalities.orEmpty().forEach { inputs.add(it.lowercase()) }
        val out = model.outputModalities.orEmpty()
        if (out.isEmpty()) outputs.add("text") else out.forEach { outputs.add(it.lowercase()) }
    }
    return GROUP_MODALITY_PRIORITY.filter { marker ->
        if (marker.isOutput) marker.kind in outputs else marker.kind in inputs
    }.take(2)
}

/**
 * Icon + tint + a11y label for one [GroupModalityMarker], reusing the glyph
 * convention from ProviderDetailScreen.ModalityIconsRow: output/generation
 * modalities use the primary tint + "generate"-style glyph; input modalities
 * use the muted onSurfaceVariant tint. Renders nothing for unknown combos.
 */
@Composable
internal fun GroupModalityIcon(marker: GroupModalityMarker) {
    val outputTint = MaterialTheme.colorScheme.primary
    val inputTint = MaterialTheme.colorScheme.onSurfaceVariant
    val size = Modifier.size(14.dp)
    val (vector, labelRes, tint) = when {
        marker.isOutput && marker.kind == "video" -> Triple(Icons.Default.MovieCreation, R.string.modeldetail_video_output, outputTint)
        marker.isOutput && marker.kind == "image" -> Triple(Icons.Default.AddPhotoAlternate, R.string.modeldetail_image_output, outputTint)
        marker.isOutput && marker.kind == "audio" -> Triple(Icons.Default.VolumeUp, R.string.modelgroup_speech_output, outputTint)
        marker.isOutput && marker.kind == "text" -> Triple(Icons.AutoMirrored.Filled.Article, R.string.modelgroup_text_generation, outputTint)
        marker.kind == "audio" -> Triple(Icons.Default.Mic, R.string.modelgroup_speech_transcription, inputTint)
        marker.kind == "video" -> Triple(Icons.Default.Videocam, R.string.modeldetail_video_input, inputTint)
        marker.kind == "image" -> Triple(Icons.Default.Image, R.string.modeldetail_image_input, inputTint)
        marker.kind == "pdf" -> Triple(Icons.AutoMirrored.Filled.InsertDriveFile, R.string.modeldetail_pdf_input, inputTint)
        else -> return
    }
    Icon(imageVector = vector, contentDescription = stringResource(labelRes), modifier = size, tint = tint)
}

/** A single group row inside the Groups section card. Built as a plain
 *  Composable (not a ListItem) so it inherits the card's surface color
 *  cleanly without ListItem's container override. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupRow(
    group: ModelGroup,
    config: com.openminis.app.data.model.ProviderConfig,
    onClick: () -> Unit,
    /**
     * [T-android-modelgroup-reorder] When non-null, a leading drag handle is
     * rendered carrying this modifier (ReorderableItem.draggableHandle).
     * Explicit handle rather than long-press-anywhere: the row already owns
     * tap (open detail) and horizontal swipe (delete), and this matches
     * AgentLoopRow's affordance in the section below.
     */
    dragHandleModifier: Modifier? = null,
) {
    // [T-modelgroups-row-scan] Three O(members × entries) scans used to run in
    // composition on EVERY recomposition of the row (member names, enabled
    // count, modality markers). One id→entry index is remembered per entries
    // list; each derived value remembers against the smallest key it depends
    // on, so unrelated recompositions reuse the results.
    val membersById = remember(config.modelEntries) {
        config.modelEntries.associateBy { it.id }
    }
    val enabledInstanceIds = remember(config.instances) {
        config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    }
    val memberNames = remember(group.memberEntryIds, membersById) {
        group.memberEntryIds.mapNotNull { membersById[it]?.model?.displayName }
    }
    val preview = if (memberNames.size <= 3) {
        memberNames.joinToString(", ")
    } else {
        memberNames.take(3).joinToString(", ") + " +${memberNames.size - 3}"
    }
    val isPrimary = config.defaultPrimaryGroupId == group.id
    val isSub = config.defaultSubGroupId == group.id
    val strategyLabel = stringResource(RoutingStrategyUi.titleRes(group.strategy))
    // [T-disabled-provider-via-group-android] Count members whose provider
    // instance is currently enabled — that's what the runtime resolver
    // will actually consider. When every member sits behind a disabled
    // provider, surface a warning so the user understands why the group
    // appears empty in chat.
    val totalMembers = group.memberEntryIds.size
    val enabledMembers = remember(group.memberEntryIds, membersById, enabledInstanceIds) {
        group.memberEntryIds.count { membersById[it]?.providerInstanceId in enabledInstanceIds }
    }
    val allDisabled = totalMembers > 0 && enabledMembers == 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = SectionDesign.RowHorizontalPadding,
                vertical = SectionDesign.RowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandleModifier != null) {
            // T198 (same as AgentLoopRow): the handle must be an IconButton —
            // a bare Icon has no pointer consumer, so draggableHandle() would
            // never receive ACTION_DOWN/MOVE.
            IconButton(
                onClick = {},
                modifier = dragHandleModifier.size(36.dp),
            ) {
                Icon(
                    imageVector = m3Icon(Icons.Default.DragHandle),
                    contentDescription = stringResource(R.string.model_group_detail_drag_to_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // [T-android-modelgroup-modality-icons] Mark the group's top-2
                // most distinctive modalities (inputs + outputs) right after the
                // title, in priority order, for at-a-glance "what is this group
                // for". Ports iOS ModelGroupsView (7caa580a + e0a7cd5f).
                // [T-modelgroups-row-scan] remember-guarded: recomputed only
                // when the group's members or the entry list change.
                val topModalities = remember(group.memberEntryIds, membersById) {
                    groupTopModalities(group, config)
                }
                topModalities.forEach { marker ->
                    GroupModalityIcon(marker)
                }
                if (isPrimary) BadgeLabel(stringResource(R.string.model_group_badge_primary), MaterialTheme.colorScheme.primary)
                if (isSub) BadgeLabel(stringResource(R.string.model_group_badge_sub), MaterialTheme.colorScheme.tertiary)
                if (allDisabled) {
                    BadgeLabel(
                        stringResource(R.string.model_group_no_usable_models_badge),
                        MaterialTheme.colorScheme.error,
                    )
                }
            }
            // [T-disabled-provider-via-group-android] Surface "M of N
            // disabled" when any member's provider is off so the user
            // doesn't have to drill into the detail page to learn that
            // the group isn't fully usable. Total count stays prominent
            // because it's still the source of truth for what's in the
            // group; the parenthetical reports disabled count.
            val disabledCount = totalMembers - enabledMembers
            // [T-hardcoded-strings] "models (N disabled)" was English-only.
            val subtitleText = if (disabledCount > 0) {
                "$strategyLabel · " + stringResource(R.string.model_group_models_disabled, totalMembers, disabledCount)
            } else {
                "$strategyLabel · " + stringResource(R.string.model_group_models_plain, totalMembers)
            }
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = if (allDisabled) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (preview.isNotEmpty()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
        }
        Icon(
            m3Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
