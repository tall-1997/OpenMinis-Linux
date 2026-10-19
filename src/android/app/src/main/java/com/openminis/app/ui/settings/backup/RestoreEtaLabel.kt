package com.openminis.app.ui.settings.backup

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R

/**
 * [T-android-restore-ui] The remaining-time estimate as display text, or null
 * when there is nothing worth saying.
 *
 * Split from [RestoreEta] on purpose: that class stays pure and device-free so
 * its rate arithmetic can be tested against a fixed clock, while this half
 * needs `stringResource` and therefore a composition. Mixing locale lookup into
 * the estimator would make the interesting cases — a stalled restore, a
 * category switch, the first sample — untestable without Android.
 *
 * Every branch of [RestoreEta.EtaBucket] maps to exactly one string here; a new
 * bucket that lands nowhere shows as a missing estimate rather than a crash, so
 * `RestoreEtaWiringTest` pins this mapping.
 */
@Composable
internal fun restoreEtaLabel(seconds: Long?): String? =
    when (val bucket = RestoreEta.bucket(seconds)) {
        null -> null
        RestoreEta.EtaBucket.AlmostDone -> stringResource(R.string.restore_eta_almost_done)
        is RestoreEta.EtaBucket.Seconds ->
            stringResource(R.string.restore_eta_seconds, bucket.seconds)
        is RestoreEta.EtaBucket.MinutesSeconds ->
            stringResource(R.string.restore_eta_minutes, bucket.minutes, bucket.seconds)
    }
