package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.chat.ModelBrandIcon
import com.openminis.app.ui.chat.ModelBrandResolver
import com.openminis.app.ui.chat.brandAccentFor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import com.openminis.app.logging.AppLogger
import com.openminis.app.usage.LiveInstance
import com.openminis.app.usage.LiveModel
import com.openminis.app.usage.Tokens
import com.openminis.app.usage.UsageBucket
import com.openminis.app.usage.UsageDisplay
import com.openminis.app.usage.UsageGranularity
import com.openminis.app.usage.UsageGroup
import com.openminis.app.usage.UsageGroupBy
import com.openminis.app.usage.UsageQuery
import com.openminis.app.usage.UsageRange
import com.openminis.app.usage.UsageReport
import com.openminis.app.usage.UsageReportBuilder
import com.openminis.app.usage.UsageReportLoader
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * [T-token-attribution-snapshot] How trustworthy a row's model attribution is.
 *
 * These must stay visually distinct. Lumping them together is what made the
 * original bug invisible: an estimated row looked exactly like a measured one,
 * so nobody could tell that a session's history had been re-attributed to
 * whichever model it currently pointed at.
 */
internal enum class Attribution {
    /** A. Per-message snapshot — the model that actually served the request. */
    MEASURED,

    /**
     * B. Written before the snapshot columns existed. Attributed to the
     * session's CURRENT model, which may be wrong if the model was ever
     * switched. Shown, but labelled as an estimate.
     */
    ESTIMATED,

    /** C. Orphaned row: no snapshot AND no session to guess from. */
    UNKNOWN_SESSION,

    /**
     * D. Snapshot present, but the model is no longer in the live config
     * (provider deleted / model removed). Renders normally from the snapshot —
     * this is the case that used to collapse into "Unknown".
     */
    MEASURED_REMOVED,
}

/**
 * [T-token-attribution-snapshot] Map a stored `ProviderType` rawValue back to
 * its display name.
 *
 * Snapshots store the rawValue (`openAI`) rather than the display name
 * (`OpenAI`) precisely so grouping is stable: display names are localized and
 * historically inconsistent — iOS ended up with `Google`, `Gemini` and
 * `Google Gemini` as three separate sections for one provider. An unrecognised
 * rawValue (a provider type removed in a later build) degrades to the raw
 * string rather than vanishing.
 */
internal fun providerDisplayName(rawValue: String): String =
    runCatching { ProviderType.valueOf(rawValue).displayName }.getOrDefault(rawValue)

/**
 * [T-token-attribution-snapshot] Decide how trustworthy a usage row's model
 * attribution is.
 *
 * Extracted from the Composable so it can be unit-tested on the JVM: this is
 * the rule that decides whether a number is presented as fact or as a guess,
 * and getting it wrong in either direction is invisible in a screenshot.
 *
 * @param modelId resolved id (snapshot, else the session's current model),
 *   or null for an orphaned row with no session to fall back to.
 * @param hasSnapshot whether the row carries a per-message snapshot.
 * @param resolvesInConfig whether that id still exists in the live config.
 */
internal fun classifyAttribution(
    modelId: String?,
    hasSnapshot: Boolean,
    resolvesInConfig: Boolean,
): Attribution = when {
    modelId == null -> Attribution.UNKNOWN_SESSION
    !hasSnapshot -> Attribution.ESTIMATED
    !resolvesInConfig -> Attribution.MEASURED_REMOVED
    else -> Attribution.MEASURED
}

private const val TAG = "UsageStats"

/** [T-usage-step2] Colours of the four stacked segments, input -> output. */
private data class SegmentColors(val input: Color, val cacheWrite: Color, val cacheRead: Color, val output: Color)

@Composable
private fun segmentColors(): SegmentColors {
    val cs = MaterialTheme.colorScheme
    // Cache read is the same hue as input at low emphasis: it is the cheap
    // part of the total, and the legend/values carry it for anyone who cannot
    // tell the shades apart.
    return SegmentColors(
        input = cs.primary,
        cacheWrite = cs.tertiary,
        cacheRead = cs.primary.copy(alpha = 0.35f),
        output = Color(0xFFF29A2E),
    )
}

/**
 * [T-usage-step2] The Usage page: range tabs, a KPI card, a stacked trend
 * chart, and a provider / model list. Every figure comes from
 * [UsageReportBuilder] (USAGE_METRICS_SPEC.md); this file only lays it out.
 * The report is built off the main thread by [UsageReportLoader].
 */
@Composable
fun UsageStatsScreen(
    chatDao: ChatDao,
    providerConfig: ProviderConfig? = null,
    onBack: () -> Unit,
) {
    var range by rememberSaveable { mutableStateOf(UsageRange.LAST_7_DAYS) }
    var groupBy by rememberSaveable { mutableStateOf(UsageGroupBy.PROVIDER) }
    var mergeModels by rememberSaveable { mutableStateOf(true) }
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }
    // [T-usage-row-cache] The reload the cached rows were read for: a new
    // reloadKey (opening the page, Retry, a new day) reads fresh rows, while a
    // tab, a toggle or a rotation regroups the rows already read.
    var readForReload by rememberSaveable { mutableIntStateOf(-1) }
    var report by remember { mutableStateOf<UsageReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var loadedDay by remember { mutableStateOf<LocalDate?>(null) }
    val live = remember(providerConfig) { liveConfig(providerConfig) }

    // Day rollover / time-zone change while the page sits in the background:
    // re-query on resume when the local date moved (spec 7.4).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && loadedDay != null && loadedDay != LocalDate.now()) reloadKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(range, groupBy, mergeModels, reloadKey, live) {
        loading = true
        failed = false
        val zone = ZoneId.systemDefault()
        val query = UsageQuery(
            range = range,
            zone = zone,
            nowMs = System.currentTimeMillis(),
            groupBy = groupBy,
            exactModelId = !mergeModels,
            firstWeekday = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
            liveInstances = live.first,
            liveModels = live.second,
        )
        try {
            val fresh = readForReload != reloadKey
            report = UsageReportLoader.load(chatDao, query, fresh = fresh)
            readForReload = reloadKey
            loadedDay = LocalDate.now(zone)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error(TAG, "usage report failed range=$range groupBy=$groupBy: ${e.javaClass.simpleName}: ${e.message}")
            failed = true
        }
        loading = false
    }

    SettingsScaffold(title = stringResource(R.string.usage_title), onBack = onBack) {
        RangeTabs(range) { range = it }
        // Switching ranges keeps the previous content on screen under a thin
        // progress bar instead of flashing an empty page.
        Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 16.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val r = report
        when {
            failed && r == null -> ErrorState { reloadKey++ }
            r == null -> Unit
            // Judged on the range the report was built for, not the tab just tapped.
            r.totals.tokens.total == 0L && (r.rangeStartMs == null || r.query.range == UsageRange.ALL) ->
                EmptyState(stringResource(R.string.usage_empty_all), null)
            r.totals.tokens.total == 0L -> {
                RangeCaption(r)
                EmptyState(stringResource(R.string.usage_empty_range)) { range = UsageRange.ALL }
            }
            else -> {
                RangeCaption(r)
                OverviewCard(r)
                TrendCard(r)
                DimensionBar(groupBy, mergeModels, onGroupBy = { groupBy = it }, onMerge = { mergeModels = it })
                GroupList(r)
                Footer()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Live instances and the model -> provider-type map the builder falls back on (spec 8.1). */
private fun liveConfig(config: ProviderConfig?): Pair<List<LiveInstance>, List<LiveModel>> {
    if (config == null) return emptyList<LiveInstance>() to emptyList()
    val instances = config.instances.map { LiveInstance(it.id, it.providerType.name, it.label) }
    val typeById = instances.associate { it.id to it.providerType }
    val models = config.modelEntries.mapNotNull { e -> typeById[e.providerInstanceId]?.let { LiveModel(e.model.id, it) } }
    return instances to models
}

// ─── Header ───────────────────────────────────────────────────────────────────

@Composable
private fun RangeTabs(selected: UsageRange, onSelect: (UsageRange) -> Unit) {
    val ranges = listOf(
        UsageRange.TODAY to R.string.usage_range_today,
        UsageRange.LAST_7_DAYS to R.string.usage_range_7d,
        UsageRange.LAST_30_DAYS to R.string.usage_range_30d,
        UsageRange.ALL to R.string.usage_range_all,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
        ranges.forEachIndexed { i, (r, label) ->
            SegmentedButton(
                selected = r == selected,
                onClick = { onSelect(r) },
                shape = SegmentedButtonDefaults.itemShape(i, ranges.size),
                icon = {},
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
}

@Composable
private fun RangeCaption(r: UsageReport) {
    val start = r.rangeStartMs ?: return
    val end = r.rangeEndMs ?: return
    val zone = r.query.zone
    val fmt = dayFormatter()
    val first = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    val last = Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate()
    val dates = if (first == last) first.format(fmt) else "${first.format(fmt)} – ${last.format(fmt)}"
    Text(
        stringResource(R.string.usage_range_caption_fmt, dates),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
    )
}

@Composable
private fun dayFormatter(): DateTimeFormatter {
    val locale = Locale.getDefault()
    return remember(locale) {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "Md"), locale)
    }
}

// ─── Overview ─────────────────────────────────────────────────────────────────

@Composable
private fun OverviewCard(r: UsageReport) {
    val t = r.totals
    val colors = segmentColors()
    SettingsSection {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.usage_total_tokens), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(UsageDisplay.tokens(t.tokens.total), fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.usage_non_cached_fmt, UsageDisplay.tokens(t.tokens.nonCached)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            val avg = r.dailyAvg?.let { stringResource(R.string.usage_daily_avg_fmt, UsageDisplay.tokens(it.toLong())) }
            val active = stringResource(R.string.usage_active_days_fmt, r.activeDays, r.calendarDays)
            Text(listOfNotNull(avg, active).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            SplitBar(t.tokens, colors)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Legend(colors.input, stringResource(R.string.usage_seg_input), t.tokens.input)
                    Legend(colors.cacheRead, stringResource(R.string.usage_seg_cache_read), t.tokens.cacheRead)
                }
                Column(Modifier.weight(1f)) {
                    Legend(colors.cacheWrite, stringResource(R.string.usage_seg_cache_write), t.tokens.cacheWrite)
                    Legend(colors.output, stringResource(R.string.usage_seg_output), t.tokens.output)
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.height(12.dp))
            // The hit rate is only as good as the share of input it is based on;
            // under 5% it says nothing and shows the em dash (design 2.3).
            val basis = t.cacheBasis
            val rate = t.hitRate?.takeIf { (basis ?: 0.0) >= 0.05 }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.usage_cache_hit), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Text(UsageDisplay.percent(rate), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (rate != null && basis != null && basis < 0.995) {
                    Text(
                        stringResource(R.string.usage_cache_basis_fmt, UsageDisplay.percent(basis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitBar(t: Tokens, c: SegmentColors) {
    val total = t.total.toFloat()
    Canvas(
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (total <= 0f) return@Canvas
        var x = 0f
        for ((value, color) in listOf(t.input to c.input, t.cacheWrite to c.cacheWrite, t.cacheRead to c.cacheRead, t.output to c.output)) {
            val w = size.width * value / total
            if (w > 0f) drawRect(color, Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, value: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text("$label ${UsageDisplay.tokens(value)}", style = MaterialTheme.typography.bodySmall)
    }
}

// ─── Trend ────────────────────────────────────────────────────────────────────

@Composable
private fun TrendCard(r: UsageReport) {
    val series = r.series
    if (series.isEmpty()) return
    val colors = segmentColors()
    var selected by remember(r) { mutableStateOf<Int?>(null) }
    val peak = series.maxOf { it.tokens.total }
    val zone = r.query.zone
    val granularity = r.granularity ?: UsageGranularity.DAY
    val dayFmt = dayFormatter()
    val todayLabel = stringResource(R.string.usage_today_short)
    val today = Instant.ofEpochMilli(r.query.nowMs).atZone(zone).toLocalDate()
    fun label(b: UsageBucket): String {
        val start = Instant.ofEpochMilli(b.startMs).atZone(zone)
        return when (granularity) {
            UsageGranularity.HOUR -> start.hour.toString()
            UsageGranularity.DAY -> if (start.toLocalDate() == today) todayLabel else start.dayOfMonth.toString()
            UsageGranularity.WEEK -> start.toLocalDate().format(dayFmt)
            UsageGranularity.MONTH -> start.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
        }
    }
    fun fullLabel(b: UsageBucket): String {
        val start = Instant.ofEpochMilli(b.startMs).atZone(zone)
        return when (granularity) {
            UsageGranularity.HOUR -> "%02d:00".format(start.hour)
            UsageGranularity.MONTH -> label(b)
            else -> start.toLocalDate().format(dayFmt)
        }
    }
    val a11y = stringResource(
        R.string.usage_a11y_chart_fmt,
        UsageDisplay.tokens(r.totals.tokens.total),
        UsageDisplay.tokens(peak),
        series.maxBy { it.tokens.total }.let(::fullLabel),
    )
    SettingsSection {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.usage_trend), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                val sel = selected?.let { series.getOrNull(it) }
                Text(
                    if (sel != null) "${fullLabel(sel)} · ${UsageDisplay.tokens(sel.tokens.total)}"
                    else stringResource(R.string.usage_trend_peak_fmt, UsageDisplay.tokens(peak)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            val placeholder = MaterialTheme.colorScheme.outlineVariant
            val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            Canvas(
                Modifier.fillMaxWidth().height(140.dp)
                    .semantics { contentDescription = a11y }
                    .pointerInput(series) {
                        detectTapGestures { pos ->
                            val i = (pos.x / (size.width.toFloat() / series.size)).toInt().coerceIn(0, series.lastIndex)
                            selected = if (selected == i) null else i
                        }
                    },
            ) {
                val slot = size.width / series.size
                val barW = (slot * 0.7f).coerceAtLeast(1f)
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                series.forEachIndexed { i, b ->
                    val left = i * slot + (slot - barW) / 2
                    if (i == selected) drawRect(highlight, Offset(i * slot, 0f), Size(slot, size.height))
                    val total = b.tokens.total
                    if (total == 0L) {
                        // Empty days keep their place (design 5.5): a faint dashed slot
                        // rather than a lone bar stretched across the card.
                        if (granularity != UsageGranularity.HOUR && barW >= 4f) {
                            drawRoundRect(placeholder, Offset(left, 0f), Size(barW, size.height), CornerRadius(3f), style = Stroke(1f, pathEffect = dash))
                        }
                        return@forEachIndexed
                    }
                    var top = size.height
                    val scale = if (peak > 0) size.height / peak.toFloat() else 0f
                    for ((value, color) in listOf(b.tokens.input to colors.input, b.tokens.cacheWrite to colors.cacheWrite, b.tokens.cacheRead to colors.cacheRead, b.tokens.output to colors.output)) {
                        val h = value * scale
                        if (h <= 0f) continue
                        drawRect(color, Offset(left, top - h), Size(barW, h))
                        top -= h
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // At most ~7 axis labels, always including the last bucket.
            val step = ((series.size + 6) / 7).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth()) {
                series.forEachIndexed { i, b ->
                    val show = (series.lastIndex - i) % step == 0
                    Text(
                        if (show) label(b) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

// ─── Groups ───────────────────────────────────────────────────────────────────

@Composable
private fun DimensionBar(groupBy: UsageGroupBy, merge: Boolean, onGroupBy: (UsageGroupBy) -> Unit, onMerge: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dims = listOf(UsageGroupBy.PROVIDER to R.string.usage_dim_provider, UsageGroupBy.MODEL to R.string.usage_dim_model)
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            dims.forEachIndexed { i, (d, label) ->
                SegmentedButton(
                    selected = d == groupBy,
                    onClick = { onGroupBy(d) },
                    shape = SegmentedButtonDefaults.itemShape(i, dims.size),
                    icon = {},
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
        if (groupBy == UsageGroupBy.MODEL) {
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.usage_merge_models), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Switch(checked = merge, onCheckedChange = onMerge)
        }
    }
}

@Composable
private fun GroupList(r: UsageReport) {
    val total = r.totals.tokens.total
    val folded = remember(r) { UsageDisplay.fold(r.groups) }
    val byModel = r.query.groupBy == UsageGroupBy.MODEL
    var otherOpen by remember(r) { mutableStateOf(false) }
    SettingsSection(modifier = Modifier.padding(top = 0.dp)) {
        val rows = folded.top
        rows.forEach { g -> GroupRow(g, total, byModel, merged = !r.query.exactModelId, divider = true) }
        if (folded.rest.isNotEmpty()) {
            OtherRow(folded.rest.size, folded.restTotal, total, otherOpen) { otherOpen = !otherOpen }
            if (otherOpen) folded.rest.forEach { g -> GroupRow(g, total, byModel, merged = !r.query.exactModelId, divider = true) }
        }
        folded.unknown?.let { GroupRow(it, total, byModel, merged = !r.query.exactModelId, divider = false) }
    }
}

@Composable
private fun groupTitle(g: UsageGroup, byModel: Boolean): String = when {
    byModel && g.key == UsageReportBuilder.UNKNOWN_MODEL -> stringResource(R.string.usage_unknown_model)
    byModel -> g.label ?: g.key
    g.key == UsageReportBuilder.UNKNOWN_PROVIDER -> stringResource(R.string.usage_unknown_provider)
    else -> providerDisplayName(g.key)
}

@Composable
private fun GroupRow(g: UsageGroup, reportTotal: Long, byModel: Boolean, merged: Boolean, divider: Boolean) {
    var expanded by remember(g.key) { mutableStateOf(false) }
    val share = UsageDisplay.share(g.tokens.total, reportTotal)
    val subtitle = if (byModel) {
        pluralStringResource(R.plurals.usage_n_providers, g.providerCount, g.providerCount)
    } else {
        pluralStringResource(R.plurals.usage_n_models, g.modelCount, g.modelCount)
    }
    Column {
        Column(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // [T-usage-topbar-brand-icon] Model rows carry the vendor logo;
                // unknown/unmapped vendors fall back to the accent initial.
                if (byModel) {
                    val title = groupTitle(g, byModel)
                    val brandSlug = remember(g.key) {
                        ModelBrandResolver.resolveExact(g.key, title)
                    }
                    BrandAvatar(
                        slug = brandSlug,
                        fallbackName = title,
                        accent = brandAccentFor(g.key, title),
                    )
                    Spacer(Modifier.width(11.dp))
                }
                Text(
                    groupTitle(g, byModel),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(UsageDisplay.tokens(g.tokens.total), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(4.dp))
                Text(UsageDisplay.percent(share), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(
                    if (expanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(6.dp))
            ShareBar(share ?: 0.0)
            // The unknown-model bucket keeps its explanation (design 3.4).
            if (byModel && g.key == UsageReportBuilder.UNKNOWN_MODEL) {
                Text(stringResource(R.string.usage_attr_unknown_session), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 32.dp, end = 16.dp, bottom = 12.dp)) {
                for (c in g.children) {
                    DetailRow(childTitle(c, byModel), UsageDisplay.tokens(c.tokens.total))
                }
                if (byModel && merged && g.rawIds.isNotEmpty()) {
                    Text(
                        stringResource(R.string.usage_raw_ids_fmt, g.rawIds.joinToString(" · ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                DetailRow(stringResource(R.string.usage_detail_fresh_input), UsageDisplay.tokens(g.tokens.input))
                DetailRow(stringResource(R.string.usage_detail_cache_read), UsageDisplay.tokens(g.tokens.cacheRead))
                DetailRow(stringResource(R.string.usage_detail_cache_creation), UsageDisplay.tokens(g.tokens.cacheWrite))
                DetailRow(stringResource(R.string.usage_detail_output), UsageDisplay.tokens(g.tokens.output))
                DetailRow(stringResource(R.string.usage_detail_cache_hit_rate), UsageDisplay.percent(g.hitRate))
            }
        }
        if (divider) RowDivider()
    }
}

@Composable
private fun BrandAvatar(slug: String?, fallbackName: String, accent: Color) {
    val resId = remember(slug) { slug?.let { ModelBrandResolver.iconResId(it) } }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (resId != null) {
            ModelBrandIcon(slug = slug, sizeDp = 17.dp)
        } else {
            Text(
                text = fallbackName.take(1),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
    }
}

@Composable
private fun childTitle(c: UsageGroup, byModel: Boolean): String = when {
    byModel && c.key == UsageReportBuilder.UNKNOWN_PROVIDER -> stringResource(R.string.usage_unknown_provider)
    byModel -> providerDisplayName(c.key)
    c.key == UsageReportBuilder.REMOVED_INSTANCE -> stringResource(R.string.usage_removed_instances)
    c.key == UsageReportBuilder.UNRECORDED_INSTANCE -> stringResource(R.string.usage_instance_unknown)
    else -> c.label ?: c.key
}

@Composable
private fun OtherRow(count: Int, value: Long, reportTotal: Long, open: Boolean, onToggle: () -> Unit) {
    val share = UsageDisplay.share(value, reportTotal)
    Column {
        Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.usage_other_fmt, count), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(UsageDisplay.tokens(value), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(4.dp))
                Text(UsageDisplay.percent(share), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(
                    if (open) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(6.dp))
            ShareBar(share ?: 0.0)
        }
        RowDivider()
    }
}

@Composable
private fun ShareBar(share: Double) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))) {
        drawRect(track)
        // A sliver stays visible so a <1% row does not look empty.
        val w = if (share > 0) (size.width * share.toFloat()).coerceAtLeast(size.height) else 0f
        drawRect(fill, size = Size(w, size.height))
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp).height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

// ─── States and footer ────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.EmptyState(message: String, onShowAll: (() -> Unit)?) {
    SettingsSection {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onShowAll != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onShowAll) { Text(stringResource(R.string.usage_show_all)) }
            }
        }
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    SettingsSection {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.usage_error), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.usage_retry)) }
        }
    }
}

@Composable
private fun Footer() {
    Column(Modifier.padding(horizontal = 32.dp, vertical = 12.dp)) {
        for (note in listOf(R.string.usage_note_retained, R.string.usage_note_background)) {
            Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
