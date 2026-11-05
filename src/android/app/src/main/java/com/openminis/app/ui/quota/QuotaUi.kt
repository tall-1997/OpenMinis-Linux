// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f (ui/quota/QuotaUi.kt). Only change vs upstream: the one ChatColors.secondaryText reference is resolved through LocalChatPalette (this tree has no ChatColors object).
package com.openminis.app.ui.quota

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.quota.PoolStatus
import com.openminis.app.quota.QuotaAmounts
import com.openminis.app.quota.QuotaBarRules
import com.openminis.app.quota.QuotaConfidence
import com.openminis.app.quota.QuotaError
import com.openminis.app.quota.QuotaFormat
import com.openminis.app.quota.QuotaFreshness
import com.openminis.app.quota.QuotaPool
import com.openminis.app.quota.QuotaPoolBinding
import com.openminis.app.quota.QuotaProvenance
import com.openminis.app.quota.QuotaRefreshDecision
import com.openminis.app.quota.QuotaRefreshResult
import com.openminis.app.quota.QuotaSource
import com.openminis.app.quota.QuotaStatus
import com.openminis.app.quota.QuotaStatusRules
import com.openminis.app.quota.QuotaStore
import com.openminis.app.quota.QuotaStrings
import com.openminis.app.quota.QuotaSubject
import com.openminis.app.quota.QuotaThresholds
import com.openminis.app.quota.QuotaTone
import com.openminis.app.quota.QuotaToneRules
import com.openminis.app.quota.QuotaWindow
import com.openminis.app.quota.computedUtilization
import com.openminis.app.quota.utilization
import com.openminis.app.ui.theme.LocalChatPalette
import java.math.BigDecimal
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// [T-provider-quota] Shared quota visuals: the provider page section, the
// detail sheet and (stage 2) the picker pills all draw pools through these, so
// every surface reads the same numbers. Port of the iOS views in
// ProviderQuotaSection.swift / QuotaDetailSheet.swift.
//
// Colour carries state only: no green except a reliable percent that is fine,
// so "nothing shown" never reads as "plenty left".

// MARK: - Words from resources

class QuotaResourceStrings(private val context: Context) : QuotaStrings {
    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)
    override fun credits() = s(R.string.quota_credits)
    override fun requests(n: String) = s(R.string.quota_requests, n)
    override fun tokens(n: String) = s(R.string.quota_tokens, n)
    override fun used(figure: String) = s(R.string.quota_used, figure)
    override fun balance(figure: String) = s(R.string.quota_balance_short, figure)
    override fun account(figure: String) = s(R.string.quota_account_short, figure)
    override fun left(figure: String) = s(R.string.quota_left, figure)
    override fun quota() = s(R.string.quota_title)
    override fun role(role: QuotaAmounts.Role) = when (role) {
        QuotaAmounts.Role.Granted -> s(R.string.quota_role_granted)
        QuotaAmounts.Role.ToppedUp -> s(R.string.quota_role_topped_up)
        QuotaAmounts.Role.Voucher -> s(R.string.quota_role_voucher)
        QuotaAmounts.Role.Cash -> s(R.string.quota_role_cash)
        is QuotaAmounts.Role.Other -> role.name
    }
    override fun period(period: QuotaWindow.CalendarPeriod) = when (period) {
        QuotaWindow.CalendarPeriod.day -> s(R.string.quota_period_daily)
        QuotaWindow.CalendarPeriod.week -> s(R.string.quota_period_weekly)
        QuotaWindow.CalendarPeriod.month -> s(R.string.quota_period_monthly)
    }
    override fun source(source: QuotaSource) = when (source) {
        QuotaSource.officialQuery -> s(R.string.quota_source_official_query)
        QuotaSource.officialHeaders -> s(R.string.quota_source_official_headers)
        QuotaSource.officialStreamEvent -> s(R.string.quota_source_official_stream)
        QuotaSource.officialErrorSignal -> s(R.string.quota_source_official_error)
        QuotaSource.providerDashboardAPI -> s(R.string.quota_source_dashboard)
        QuotaSource.compatibleInterface -> s(R.string.quota_source_compatible)
        QuotaSource.relayAccountLedger -> s(R.string.quota_source_relay)
        QuotaSource.derived -> s(R.string.quota_source_derived)
    }
}

@Composable
fun rememberQuotaStrings(): QuotaStrings {
    val context = LocalContext.current
    return remember(context) { QuotaResourceStrings(context) }
}

@Composable
internal fun currentLocale(): Locale {
    val config = LocalContext.current.resources.configuration
    return config.locales[0] ?: Locale.getDefault()
}

// MARK: - Attention  [T-quota-attention-refresh]

/**
 * Marks an instance as WATCHED for as long as this composable is on screen
 * (keyed by the id, so a changed id re-targets). Coming on screen refreshes
 * it if due; while watched it refreshes on the normal interval instead of the
 * unattended hour. A lazy-list row scrolled out of view ends its watch.
 */
@Composable
fun QuotaWatch(instanceId: String?, reason: String) {
    DisposableEffect(instanceId) {
        if (instanceId != null) QuotaStore.beginWatching(instanceId, reason)
        onDispose { if (instanceId != null) QuotaStore.endWatching(instanceId) }
    }
}

// MARK: - Tone colours

/** Text / accent colour. Green only for `healthy`; an unmeasured balance stays neutral. */
@Composable
fun QuotaTone.color(): Color {
    val dark = LocalChatPalette.current.isDark
    return when (this) {
        QuotaTone.normal -> MaterialTheme.colorScheme.onSurfaceVariant
        QuotaTone.healthy -> if (dark) Color(0xFF30D158) else Color(0xFF34C759)
        QuotaTone.caution -> if (dark) Color(0xFFFF9F0A) else Color(0xFFFF9500)
        QuotaTone.critical -> if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
        QuotaTone.muted -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
}

/** Emphasised amount text: neutral stays primary, warnings keep their colour. */
@Composable
fun QuotaTone.amountColor(): Color = when (this) {
    QuotaTone.normal, QuotaTone.healthy -> MaterialTheme.colorScheme.onSurface
    else -> color()
}

@Composable
internal fun quotaOrange(): Color = QuotaTone.caution.color()

// MARK: - Text helpers

object QuotaText {
    /**
     * "2 hr. ago" / "in 3 hr." - iOS RelativeDateTimeFormatter (.short).
     * Not DateUtils: its future form is sentence-cased ("In 3 hr."), which
     * reads wrong inside "Resets %s" / "updated %s".
     */
    fun relative(context: Context, at: Long, now: Long = System.currentTimeMillis()): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val f = android.icu.text.RelativeDateTimeFormatter.getInstance(
            android.icu.util.ULocale.forLocale(locale), null,
            android.icu.text.RelativeDateTimeFormatter.Style.SHORT,
            android.icu.text.DisplayContext.CAPITALIZATION_NONE,
        )
        val diff = at - now
        val abs = kotlin.math.abs(diff)
        if (abs < 60_000L) {
            return f.format(
                android.icu.text.RelativeDateTimeFormatter.Direction.PLAIN,
                android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.NOW,
            )
        }
        val direction = if (diff < 0) android.icu.text.RelativeDateTimeFormatter.Direction.LAST
        else android.icu.text.RelativeDateTimeFormatter.Direction.NEXT
        val (value, unit) = when {
            abs < 3_600_000L -> abs / 60_000L to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES
            abs < 86_400_000L -> abs / 3_600_000L to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS
            abs < 7 * 86_400_000L -> abs / 86_400_000L to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS
            abs < 30 * 86_400_000L -> abs / (7 * 86_400_000L) to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.WEEKS
            else -> abs / (30 * 86_400_000L) to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MONTHS
        }
        return f.format(value.toDouble(), direction, unit)
    }

    fun stamp(context: Context, at: Long): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val abs = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(at))
        return "$abs · ${relative(context, at)}"
    }

    fun time(context: Context, at: Long): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(at))
    }

    fun unsupported(context: Context, reason: QuotaSubject.UnsupportedReason): String = context.getString(
        when (reason) {
            QuotaSubject.UnsupportedReason.vendorHasNoQuotaAPI -> R.string.quota_unsupported_no_api
            QuotaSubject.UnsupportedReason.unknownVendor -> R.string.quota_unsupported_unknown_endpoint
            QuotaSubject.UnsupportedReason.providerTypeUnsupported -> R.string.quota_unsupported_type
            QuotaSubject.UnsupportedReason.passiveNotImplemented -> R.string.quota_unsupported_passive
            QuotaSubject.UnsupportedReason.capabilityProbeFailed -> R.string.quota_unsupported_probe_failed
        },
    )

    fun error(context: Context, err: QuotaError): String = context.getString(
        when (err.kind) {
            QuotaError.Kind.unauthorized -> R.string.quota_error_unauthorized
            QuotaError.Kind.forbidden -> R.string.quota_error_forbidden
            QuotaError.Kind.notFound -> R.string.quota_error_not_found
            QuotaError.Kind.rateLimited -> R.string.quota_error_rate_limited
            QuotaError.Kind.server -> R.string.quota_error_server
            QuotaError.Kind.network -> R.string.quota_error_network
            QuotaError.Kind.badResponse, QuotaError.Kind.unsupportedResponse -> R.string.quota_error_bad_response
            QuotaError.Kind.missingCredential -> R.string.quota_error_missing_credential
        },
    )

    fun poolName(context: Context, pool: QuotaPool): String = when (pool.kind) {
        QuotaPool.Kind.prepaidBalance -> {
            val unit = pool.unit
            if (unit is com.openminis.app.quota.QuotaUnit.Currency) {
                context.getString(R.string.quota_pool_balance_currency, unit.code)
            } else {
                pool.vendorLabel ?: context.getString(R.string.quota_pool_balance)
            }
        }
        QuotaPool.Kind.spendCap -> pool.vendorLabel ?: context.getString(R.string.quota_pool_spend_limit)
        QuotaPool.Kind.allowance -> pool.vendorLabel ?: context.getString(R.string.quota_pool_allowance)
        QuotaPool.Kind.usageWindow -> pool.vendorLabel ?: context.getString(R.string.quota_pool_usage_limit)
        QuotaPool.Kind.rateLimit -> pool.vendorLabel ?: context.getString(R.string.quota_pool_rate_limit)
    }

    fun kindName(context: Context, kind: QuotaPool.Kind): String = context.getString(
        when (kind) {
            QuotaPool.Kind.prepaidBalance -> R.string.quota_pool_prepaid_balance
            QuotaPool.Kind.spendCap -> R.string.quota_pool_spend_limit
            QuotaPool.Kind.allowance -> R.string.quota_pool_allowance
            QuotaPool.Kind.usageWindow -> R.string.quota_pool_usage_limit
            QuotaPool.Kind.rateLimit -> R.string.quota_pool_rate_limit
        },
    )

    fun statusName(context: Context, status: QuotaStatus): String = context.getString(
        when (status) {
            QuotaStatus.available -> R.string.quota_status_available
            QuotaStatus.low -> R.string.quota_status_low
            QuotaStatus.rateLimited -> R.string.quota_status_rate_limited
            QuotaStatus.exhausted -> R.string.quota_status_used_up
            QuotaStatus.unknown -> R.string.quota_status_unknown
        },
    )

    fun confidence(context: Context, c: QuotaConfidence): String = context.getString(
        when (c) {
            QuotaConfidence.documented -> R.string.quota_confidence_documented
            QuotaConfidence.observedClient -> R.string.quota_confidence_observed
            QuotaConfidence.unverified -> R.string.quota_confidence_unverified
            QuotaConfidence.thirdParty -> R.string.quota_confidence_third_party
            QuotaConfidence.userDeclared -> R.string.quota_confidence_user
        },
    )

    fun sourceText(context: Context, p: QuotaProvenance): String =
        "${QuotaResourceStrings(context).source(p.source)} · ${confidence(context, p.confidence)}"

    fun scope(context: Context, t: QuotaPoolBinding.Target): String = when (t) {
        QuotaPoolBinding.Target.Subject -> context.getString(R.string.quota_shared_by_all)
        is QuotaPoolBinding.Target.Model -> context.getString(R.string.quota_scope_model, t.key)
        is QuotaPoolBinding.Target.ModelFamily -> context.getString(R.string.quota_scope_family, t.pattern)
        is QuotaPoolBinding.Target.Product -> context.getString(R.string.quota_scope_product, t.name)
    }

    /** What a blocked manual refresh tells the user; null clears the message. */
    fun blocked(context: Context, result: QuotaRefreshResult): String? {
        val decision = (result as? QuotaRefreshResult.Blocked)?.decision ?: return null
        return when (decision) {
            is QuotaRefreshDecision.ServerCooldown ->
                context.getString(R.string.quota_wait_until, time(context, decision.until))
            is QuotaRefreshDecision.ForcedDebounce -> context.getString(R.string.quota_just_refreshed)
            else -> null
        }
    }

    /** "“A”, “B”" - the same separator iOS uses. */
    fun sharing(context: Context, labels: List<String>): String =
        context.getString(R.string.quota_same_account, labels.joinToString("”, “"))
}

// MARK: - Bar, chip, window line

/** A progress bar drawn ONLY from a reliable fraction ([QuotaBarRules]). */
@Composable
fun QuotaUsageBar(fraction: Double, tone: QuotaTone, modifier: Modifier = Modifier) {
    val fill = tone.color()
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
        if (fraction > 0) {
            Box(
                Modifier
                    .widthIn(min = 4.dp)
                    .fillMaxWidth(fraction.toFloat())
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(fill),
            )
        }
    }
}

/** Coloured status word ("Low", "Used up", "Stale"). */
@Composable
fun QuotaStatusChip(status: PoolStatus) {
    val context = LocalContext.current
    val tone = QuotaToneRules.tone(status)
    val label = when {
        status.freshness == QuotaFreshness.stale && tone != QuotaTone.muted ->
            context.getString(R.string.quota_status_stale, QuotaText.statusName(context, status.status))
        status.freshness == QuotaFreshness.expired -> context.getString(R.string.quota_status_expired)
        else -> QuotaText.statusName(context, status.status)
    }
    val c = tone.color()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(c.copy(alpha = if (tone == QuotaTone.normal) 0.08f else 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (tone == QuotaTone.normal) MaterialTheme.colorScheme.onSurfaceVariant else c,
            maxLines = 1,
        )
    }
}

/** One window of a pool: label, used %, bar (only with a reliable percent), reset. */
@Composable
fun QuotaWindowLine(window: QuotaWindow, pool: QuotaPool) {
    val context = LocalContext.current
    val strings = rememberQuotaStrings()
    val locale = currentLocale()
    val util = window.utilization(pool.kind, pool.unlimited)
    val fraction = QuotaBarRules.windowFraction(window, pool)
    val tone = QuotaToneRules.tone(
        util?.let { QuotaStatusRules.status(it, QuotaThresholds()) } ?: QuotaStatus.unknown,
        QuotaFreshness.fresh,
        util != null,
    )
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                QuotaFormat.windowLabel(window, strings),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            val remaining = window.amounts?.remaining
            if (util != null) {
                Text(
                    QuotaFormat.percentUsedText(util.percent, strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tone == QuotaTone.healthy) MaterialTheme.colorScheme.onSurfaceVariant else tone.color(),
                )
            } else if (remaining != null) {
                Text(
                    context.getString(R.string.quota_left, QuotaFormat.amount(remaining, pool.unit, locale, strings)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (fraction != null) QuotaUsageBar(fraction, tone)
        window.resetsAt?.let {
            Text(
                context.getString(R.string.quota_resets, QuotaText.relative(context, it)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Pool row

/**
 * A pool in the provider page's Quota section, and (with [detailed]) a full
 * card in the quota sheet: the same composable, so both read the same numbers.
 */
@Composable
fun QuotaPoolRow(pool: QuotaPool, status: PoolStatus, detailed: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val strings = rememberQuotaStrings()
    val locale = currentLocale()
    val tone = QuotaToneRules.tone(status)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .alpha(if (status.freshness == QuotaFreshness.stale) 0.6f else 1f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                QuotaText.poolName(context, pool),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (detailed) {
                Spacer(Modifier.width(6.dp))
                QuotaStatusChip(status)
            }
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            val headline = headlineAmount(pool, locale, strings)
            if (status.freshness != QuotaFreshness.expired && headline != null) {
                Text(
                    headline,
                    style = if (detailed) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tone.amountColor(),
                    maxLines = 1,
                )
            }
        }
        if (status.freshness == QuotaFreshness.expired) {
            Text(
                context.getString(R.string.quota_data_expired),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PoolDetails(pool, tone, detailed)
        }
    }
}

@Composable
private fun PoolDetails(pool: QuotaPool, tone: QuotaTone, detailed: Boolean) {
    val context = LocalContext.current
    val strings = rememberQuotaStrings()
    val locale = currentLocale()
    val amounts = pool.amounts
    if (amounts != null) {
        val total = amounts.total
        if ((pool.kind == QuotaPool.Kind.spendCap || pool.kind == QuotaPool.Kind.allowance) && total != null) {
            CapLine(pool, amounts, total, tone)
        }
        if (detailed && pool.kind == QuotaPool.Kind.prepaidBalance) {
            Text(
                context.getString(R.string.quota_prepaid_no_percent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        QuotaFormat.breakdown(amounts, pool.unit, locale, strings)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (pool.verdict == QuotaPool.Verdict.rejected) {
        Text(
            context.getString(R.string.quota_account_rejected),
            style = MaterialTheme.typography.bodySmall,
            color = QuotaTone.critical.color(),
        )
    }
    pool.windows.forEach { QuotaWindowLine(it, pool) }
    if (pool.unlimited) {
        Text(
            context.getString(R.string.quota_no_limit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The pool's main figure: remaining balance / remaining allowance. */
private fun headlineAmount(pool: QuotaPool, locale: Locale, strings: QuotaStrings): String? {
    val a = pool.amounts ?: return null
    a.remaining?.let { return QuotaFormat.amount(it, pool.unit, locale, strings) }
    val t = a.total
    val u = a.used
    if (t != null && u != null) return QuotaFormat.amount(t.subtract(u), pool.unit, locale, strings)
    return null
}

@Composable
private fun CapLine(pool: QuotaPool, amounts: QuotaAmounts, total: BigDecimal, tone: QuotaTone) {
    val context = LocalContext.current
    val strings = rememberQuotaStrings()
    val locale = currentLocale()
    val used = amounts.used ?: amounts.remaining?.let { total.subtract(it) } ?: return
    val util = amounts.computedUtilization(pool.kind, pool.unlimited)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        QuotaBarRules.poolFraction(pool)?.let { QuotaUsageBar(it, tone) }
        Text(
            context.getString(
                R.string.quota_of_used,
                QuotaFormat.amount(used, pool.unit, locale, strings),
                QuotaFormat.amount(total, pool.unit, locale, strings),
            ) + (util?.let { " · " + QuotaFormat.percent(it.percent) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Settings-style circle icon, console link row

/** A filled glyph on a coloured circle - the Settings row badge style. */
@Composable
fun QuotaCircleIcon(icon: ImageVector, tint: Color, size: Int = 22) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp))
    }
}

fun scopeIcon(t: QuotaPoolBinding.Target): Pair<ImageVector, Color> = when (t) {
    QuotaPoolBinding.Target.Subject -> Icons.Filled.Group to Color(0xFF007AFF)
    is QuotaPoolBinding.Target.Model, is QuotaPoolBinding.Target.ModelFamily -> Icons.Filled.Memory to Color(0xFFAF52DE)
    is QuotaPoolBinding.Target.Product -> Icons.Filled.Inventory2 to Color(0xFF5856D6)
}

/**
 * [T-quota-console-links] "Usage on the provider's website" + its host, in
 * the Settings row-icon style; tapping opens the in-app browser.
 */
@Composable
fun QuotaConsoleLinkRow(url: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hint = context.getString(R.string.quota_console_link_hint)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics { contentDescription = context.getString(R.string.quota_console_link) + ", " + hint }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        QuotaCircleIcon(Icons.Filled.Language, Color(0xFF007AFF), size = 28)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.quota_console_link), style = MaterialTheme.typography.bodyLarge)
            Text(
                com.openminis.app.quota.QuotaConsoleLink.host(url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}
