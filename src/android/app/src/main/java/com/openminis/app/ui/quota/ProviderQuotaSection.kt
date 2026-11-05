// [port-upstream-1.16] Ported verbatim from OpenMinis/OpenMinis upstream 1a71df0f (ui/quota/ProviderQuotaSection.kt).
package com.openminis.app.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.i18n.uppercaseForDisplay
import com.openminis.app.quota.QuotaConsoleLink
import com.openminis.app.quota.QuotaInstanceDisplay
import com.openminis.app.quota.QuotaPool
import com.openminis.app.quota.QuotaPoolBinding
import com.openminis.app.quota.QuotaState
import com.openminis.app.quota.QuotaStore
import com.openminis.app.quota.QuotaSubject
import com.openminis.app.quota.QuotaSubjectID
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.UrlPreviewSheet
import com.openminis.app.ui.theme.LocalChatPalette
import kotlinx.coroutines.launch

// [T-provider-quota] "Quota" section of the provider detail page: the upstream
// balance / quota of this instance's account, from the vendor itself - never
// Minis's own token counts. Port of iOS ProviderQuotaSection.swift.
//
// - Shared pools appear once, labelled as shared by every model on the account.
// - Amounts are shown in their own unit; a prepaid balance has no bar and no percent.
// - Windows are listed side by side with their own reset time, never summed.

@Composable
fun ProviderQuotaSection(instance: ProviderInstance) {
    val context = LocalContext.current
    val st by QuotaStore.state.collectAsStateWithLifecycle()
    val descriptor = QuotaStore.descriptor(instance)
    val display = st.display(descriptor)
    if (display is QuotaInstanceDisplay.Hidden) return

    // [T-quota-attention-refresh] Refreshes on open when due, and keeps the
    // watched interval while the page is open.
    QuotaWatch(instance.id, "detailAppeared")

    val scope = rememberCoroutineScope()
    var blockedMessage by remember(instance.id) { mutableStateOf<String?>(null) }
    var consoleUrl by remember { mutableStateOf<String?>(null) }
    val linkUrl = QuotaConsoleLink.url(descriptor)

    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        // Header: "QUOTA" + refresh (same type as SettingsSection's header).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 20.dp).height(28.dp),
        ) {
            Text(
                stringResource(R.string.quota_title).uppercaseForDisplay(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.weight(1f))
            if (canRefresh(display)) {
                if (st.isRefreshing(instance.id)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                } else {
                    IconButton(
                        onClick = {
                            scope.launch {
                                QuotaStore.noteViewed(instance.id)
                                val r = QuotaStore.refresh(instance.id, forced = true, reason = "manual")
                                blockedMessage = QuotaText.blocked(context, r)
                            }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.quota_refresh),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            SectionContent(st, display)
            if (linkUrl != null) {
                QuotaDivider()
                QuotaConsoleLinkRow(linkUrl, onOpen = { consoleUrl = linkUrl })
            }
        }
        SectionFooter(st, display, instance, blockedMessage)
    }

    consoleUrl?.let { url -> UrlPreviewSheet(url = url, onDismiss = { consoleUrl = null }) }
}

private fun canRefresh(display: QuotaInstanceDisplay): Boolean = when (display) {
    QuotaInstanceDisplay.Hidden -> false
    is QuotaInstanceDisplay.Unsupported -> display.reason == QuotaSubject.UnsupportedReason.capabilityProbeFailed
    else -> true
}

@Composable
internal fun QuotaDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

@Composable
private fun ColumnScope.SectionContent(st: QuotaState, display: QuotaInstanceDisplay) {
    val context = LocalContext.current
    val rowPadding = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
    when (display) {
        QuotaInstanceDisplay.Hidden -> Unit
        is QuotaInstanceDisplay.Unsupported -> Text(
            QuotaText.unsupported(context, display.reason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = rowPadding,
        )
        QuotaInstanceDisplay.Loading -> CheckingRow(rowPadding)
        is QuotaInstanceDisplay.Empty -> Text(
            stringResource(R.string.quota_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = rowPadding,
        )
        is QuotaInstanceDisplay.Failed -> Row(verticalAlignment = Alignment.CenterVertically, modifier = rowPadding) {
            Icon(Icons.Outlined.WarningAmber, null, tint = quotaOrange(), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(QuotaText.error(context, display.error), style = MaterialTheme.typography.bodySmall, color = quotaOrange())
        }
        is QuotaInstanceDisplay.Pools -> {
            val pools = st.poolsOf(display.subjectId)
            val shared = st.bindingsOf(display.subjectId).any { it.target == QuotaPoolBinding.Target.Subject }
            if (shared) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp),
                ) {
                    Icon(
                        Icons.Filled.Group, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        stringResource(R.string.quota_shared_by_all),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            pools.forEachIndexed { i, pool ->
                if (i > 0) QuotaDivider()
                QuotaPoolRow(pool, st.poolStatus(pool), modifier = rowPadding)
            }
        }
    }
}

@Composable
internal fun CheckingRow(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.quota_checking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionFooter(st: QuotaState, display: QuotaInstanceDisplay, instance: ProviderInstance, blocked: String?) {
    val context = LocalContext.current
    val lines = mutableListOf<Pair<String, Boolean>>() // text, warning
    if (display is QuotaInstanceDisplay.Pools) {
        val subject = st.subjects[display.subjectId]
        val pools = st.poolsOf(display.subjectId)
        val newest = pools.maxOfOrNull { it.observedAt }
        val source = pools.firstOrNull()?.provenance?.source
        if (newest != null && source != null) {
            lines += context.getString(
                R.string.quota_source_updated,
                QuotaResourceStrings(context).source(source),
                QuotaText.relative(context, newest),
            ) to false
        }
        subject?.lastRefresh?.error?.let {
            lines += context.getString(R.string.quota_last_refresh_failed, QuotaText.error(context, it)) to true
        }
        val sharing = sharingLabels(st, instance.id)
        if (sharing.isNotEmpty()) lines += QuotaText.sharing(context, sharing) to false
    }
    if (blocked != null) lines += blocked to false
    relayDetectionLine(st, instance)?.let { lines += it to false }
    if (lines.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 4.dp),
    ) {
        for ((text, warning) in lines) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (warning) quotaOrange() else MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
internal fun rememberProviderRepository(): com.openminis.app.data.repository.ProviderRepository? =
    (LocalContext.current.applicationContext as? com.openminis.app.MinisApp)?.providerRepositoryOrNull

/** Labels of the other instances on the same account. */
@Composable
internal fun sharingLabels(st: QuotaState, instanceId: String): List<String> {
    val repo = rememberProviderRepository() ?: return emptyList()
    val config by repo.config.collectAsStateWithLifecycle()
    return st.sharingInstanceIds(instanceId).mapNotNull { id -> config.instances.find { it.id == id }?.label }
}

/**
 * [T-quota-relay-probe] "Relay type: Sub2API · /v1/usage (detected 2 hr. ago)"
 * or what was checked when nothing answered.
 */
@Composable
internal fun relayDetectionLine(st: QuotaState, instance: ProviderInstance): String? {
    val context = LocalContext.current
    val d = st.relayDetection(QuotaStore.descriptor(instance)) ?: return null
    val ago = QuotaText.relative(context, d.at)
    return if (d.found) {
        context.getString(R.string.quota_relay_type_detected, d.text, ago)
    } else {
        context.getString(R.string.quota_relay_none_found, d.text, ago)
    }
}

// MARK: - Detail sheet

/**
 * [T-quota-badge-ui] The full quota picture of one provider account, opened
 * from the pill on the model picker's provider header (and the Model Group
 * member pills). Read-only quota, nothing else. Port of iOS QuotaDetailSheet.
 *
 * - One card per canonical pool ([QuotaPoolRow], detailed).
 * - Per pool: source and confidence, update time, and what it applies to.
 * - No key, not even masked.
 * - Pull to refresh / toolbar refresh use the store's forced refresh:
 *   single flight, Retry-After and the 30 s debounce still apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaDetailSheet(
    instanceId: String,
    onDismiss: () -> Unit,
    /**
     * Open at half height, draggable to full (iOS `.medium, .large` detents
     * over the model picker). The Model Group page opens it full height, as iOS.
     */
    halfHeight: Boolean = true,
) {
    val context = LocalContext.current
    val st by QuotaStore.state.collectAsStateWithLifecycle()
    val repo = rememberProviderRepository()
    val config = repo?.config?.collectAsStateWithLifecycle()?.value
    val instance = config?.instances?.find { it.id == instanceId }
    val scope = rememberCoroutineScope()
    var blockedMessage by remember(instanceId) { mutableStateOf<String?>(null) }
    var consoleUrl by remember { mutableStateOf<String?>(null) }

    QuotaWatch(instanceId, "detailSheet")

    fun refresh() {
        scope.launch {
            val r = QuotaStore.refresh(instanceId, forced = true, reason = "detailSheet")
            blockedMessage = QuotaText.blocked(context, r)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !halfHeight),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), contentAlignment = Alignment.TopCenter) {
                Box(
                    Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(LocalChatPalette.current.secondaryText.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding()) {
            // Title bar: Done | label | refresh.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.quota_done)) }
                }
                Text(
                    instance?.label ?: stringResource(R.string.quota_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (st.isRefreshing(instanceId)) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(18.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { refresh() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.quota_refresh),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            PullToRefreshBox(
                isRefreshing = st.isRefreshing(instanceId),
                onRefresh = { refresh() },
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    if (instance != null) {
                        val descriptor = QuotaStore.descriptor(instance)
                        val display = st.display(descriptor)
                        val linkUrl = QuotaConsoleLink.url(descriptor)
                        if (display !is QuotaInstanceDisplay.Pools && linkUrl != null) {
                            SheetSection(null) { QuotaConsoleLinkRow(linkUrl, onOpen = { consoleUrl = linkUrl }) }
                        }
                        SheetBody(st, display, instance, blockedMessage, linkUrl) { consoleUrl = it }
                    }
                }
            }
        }
    }

    consoleUrl?.let { url -> UrlPreviewSheet(url = url, onDismiss = { consoleUrl = null }) }
}

@Composable
private fun SheetBody(
    st: QuotaState,
    display: QuotaInstanceDisplay,
    instance: ProviderInstance,
    blocked: String?,
    linkUrl: String?,
    openLink: (String) -> Unit,
) {
    val context = LocalContext.current
    val pad = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
    when (display) {
        is QuotaInstanceDisplay.Pools -> {
            AccountSection(st, display.subjectId, instance, blocked, linkUrl, openLink)
            for (pool in st.poolsOf(display.subjectId)) PoolSection(st, pool)
            Text(
                stringResource(R.string.quota_sheet_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp),
            )
        }
        QuotaInstanceDisplay.Loading -> SheetSection(null) { CheckingRow(pad) }
        is QuotaInstanceDisplay.Unsupported -> SheetSection(null) {
            Text(QuotaText.unsupported(context, display.reason), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = pad)
        }
        is QuotaInstanceDisplay.Failed -> SheetSection(null) {
            Text(QuotaText.error(context, display.error), color = quotaOrange(), modifier = pad)
        }
        is QuotaInstanceDisplay.Empty -> SheetSection(null) {
            Text(stringResource(R.string.quota_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = pad)
        }
        QuotaInstanceDisplay.Hidden -> SheetSection(null) {
            Text(stringResource(R.string.quota_error_missing_credential), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = pad)
        }
    }
}

@Composable
private fun SheetSection(header: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        if (header != null) {
            Text(
                header.uppercaseForDisplay(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 8.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            content = content,
        )
    }
}

/** "Title ........ value" row (SwiftUI LabeledContent). */
@Composable
private fun LabeledRow(title: String, value: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
    }
}

@Composable
private fun AccountSection(
    st: QuotaState,
    sid: QuotaSubjectID,
    instance: ProviderInstance,
    blocked: String?,
    linkUrl: String?,
    openLink: (String) -> Unit,
) {
    val context = LocalContext.current
    val subject = st.subjects[sid]
    SheetSection(stringResource(R.string.quota_account_header)) {
        var needsDivider = false
        @Composable
        fun divider() {
            if (needsDivider) QuotaDivider()
            needsDivider = true
        }
        relaySheetRows(st, instance)?.let { rows ->
            divider()
            rows()
        }
        subject?.lastRefresh?.succeededAt?.let { at ->
            divider()
            LabeledRow(stringResource(R.string.quota_last_checked)) {
                Text(
                    QuotaText.stamp(context, at),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
        val notes = mutableListOf<Pair<String, Boolean>>()
        val sharing = sharingLabels(st, instance.id)
        if (sharing.isNotEmpty()) notes += QuotaText.sharing(context, sharing) to false
        subject?.lastRefresh?.error?.let {
            notes += context.getString(R.string.quota_last_refresh_failed, QuotaText.error(context, it)) to true
        }
        if (blocked != null) notes += blocked to false
        for ((text, warning) in notes) {
            divider()
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (warning) quotaOrange() else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (linkUrl != null) {
            divider()
            QuotaConsoleLinkRow(linkUrl, onOpen = { openLink(linkUrl) })
        }
    }
}

/** [T-quota-relay-probe] "Relay type  Sub2API · /v1/usage / detected 2 hr. ago", only when found. */
@Composable
internal fun relaySheetRows(st: QuotaState, instance: ProviderInstance): (@Composable () -> Unit)? {
    val d = st.relayDetection(QuotaStore.descriptor(instance))?.takeIf { it.found } ?: return null
    return {
        val context = LocalContext.current
        LabeledRow(stringResource(R.string.quota_relay_type)) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(d.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                Text(
                    context.getString(R.string.quota_detected, QuotaText.relative(context, d.at)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PoolSection(st: QuotaState, pool: QuotaPool) {
    val context = LocalContext.current
    val status = st.poolStatus(pool)
    val scopes = st.bindings.filter { it.poolKey == pool.id }
    SheetSection(QuotaText.kindName(context, pool.kind)) {
        QuotaPoolRow(pool, status, detailed = true, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        QuotaDivider()
        LabeledRow(stringResource(R.string.quota_source)) {
            Text(
                QuotaText.sourceText(context, pool.provenance),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        QuotaDivider()
        LabeledRow(stringResource(R.string.quota_updated)) {
            Text(
                QuotaText.stamp(context, pool.observedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        for (binding in scopes) {
            QuotaDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                val (icon, tint) = scopeIcon(binding.target)
                QuotaCircleIcon(icon, tint)
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(QuotaText.scope(context, binding.target), style = MaterialTheme.typography.bodyMedium)
                    if (binding.evidence == QuotaPoolBinding.Evidence.assumedSubjectWide) {
                        Text(
                            stringResource(R.string.quota_scope_assumed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
