package com.openminis.app.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.repository.ModelRefreshFailure
import com.openminis.app.data.repository.ModelRefreshResult
import com.openminis.app.data.repository.ProviderRefreshMarks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-models-refresh-failure-reason] A string resource and its format args,
 * resolved to text at the composable boundary.
 *
 * Split out from the composable on purpose: this half is plain Kotlin, so the
 * mapping from "what happened" to "which sentence" is testable without a
 * Compose harness or an Android Context.
 */
internal data class RefreshNoteLabel(val resId: Int, val args: List<Any> = emptyList())

/**
 * The models-section header: the count, plus — only while the last attempt
 * failed — how old the list on screen actually is.
 */
internal data class ModelsHeaderLabels(
    val base: RefreshNoteLabel,
    val staleSuffix: RefreshNoteLabel?,
)

internal object ProviderRefreshLabels {

    /**
     * The one-line status under the provider's Refresh row.
     *
     * `ModelRefreshResult.SUCCESS_API` is a legal argument but always answers
     * null: a success clears the persisted mark, so the screen can never
     * observe it. It is handled explicitly instead of left to a `when` branch
     * that would silently map a success onto "not updated".
     */
    fun subtitle(
        result: ModelRefreshResult?,
        failure: ModelRefreshFailure?,
    ): RefreshNoteLabel? = when (result) {
        null, ModelRefreshResult.SUCCESS_API -> null
        ModelRefreshResult.NO_KEY -> RefreshNoteLabel(R.string.provider_sync_mark_nokey)
        ModelRefreshResult.PRESERVED -> RefreshNoteLabel(R.string.provider_sync_mark_unchanged)
        ModelRefreshResult.FAILURE -> failureLabel(failure)
    }

    /**
     * A failure with no recorded reason still has to say something — that is
     * the pre-existing wording, kept as the floor rather than replaced.
     */
    private fun failureLabel(failure: ModelRefreshFailure?): RefreshNoteLabel =
        when (failure?.kind) {
            ModelRefreshFailure.Kind.HTTP_ERROR ->
                // A status code without a code is meaningless; fall back.
                failure.httpStatus?.let {
                    RefreshNoteLabel(R.string.provider_refresh_failed_http, listOf(it))
                } ?: RefreshNoteLabel(R.string.provider_sync_mark_failed)
            ModelRefreshFailure.Kind.TRANSPORT ->
                RefreshNoteLabel(R.string.provider_refresh_failed_transport)
            ModelRefreshFailure.Kind.PAYLOAD ->
                RefreshNoteLabel(R.string.provider_refresh_failed_payload)
            null -> RefreshNoteLabel(R.string.provider_sync_mark_failed)
        }

    /**
     * [T-models-stale-catalogue] The list under a failed refresh is the PREVIOUS
     * one, kept on purpose. Saying so is the whole point: without it a stale
     * catalogue reads as a live one, which is how a provider pointed at the
     * wrong port looked healthy for days.
     *
     * Only widened when the last attempt failed *and* something is on screen to
     * mislead — an empty picker needs no warning.
     */
    fun modelsHeader(
        count: Int,
        result: ModelRefreshResult?,
        lastSuccessAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): ModelsHeaderLabels {
        val base = RefreshNoteLabel(R.string.provider_detail_models_count_header, listOf(count))
        if (result != ModelRefreshResult.FAILURE || count <= 0) {
            return ModelsHeaderLabels(base, staleSuffix = null)
        }
        val suffix = if (lastSuccessAtMs <= 0L) {
            RefreshNoteLabel(R.string.provider_models_never_synced)
        } else {
            RefreshNoteLabel(R.string.provider_models_stale_suffix, listOf(formatStamp(lastSuccessAtMs, nowMs)))
        }
        return ModelsHeaderLabels(base, suffix)
    }

    /**
     * Coarse wall-clock stamp: minute precision, and the same year is dropped.
     * A model catalogue is never minutes old in a way that matters, and a full
     * timestamp pushes the header past the row width.
     */
    fun formatStamp(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val pattern = if (sameYear(atMs, nowMs)) "MM-dd HH:mm" else "yyyy-MM-dd"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(atMs))
    }

    @Suppress("DEPRECATION")
    private fun sameYear(a: Long, b: Long): Boolean {
        val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR)
    }
}

/** Resolved status line, or null when there is nothing to say. */
@Composable
internal fun providerRefreshSubtitle(
    context: Context,
    instanceId: String,
    note: ModelRefreshResult?,
): String? {
    val label = ProviderRefreshLabels.subtitle(note, ProviderRefreshMarks.failureOf(context, instanceId))
        ?: return null
    return stringResource(label.resId, *label.args.toTypedArray())
}

/** Resolved models-section header, with the staleness suffix when it applies. */
@Composable
internal fun providerModelsHeader(
    context: Context,
    instanceId: String,
    count: Int,
    note: ModelRefreshResult?,
): String {
    val labels = ProviderRefreshLabels.modelsHeader(
        count = count,
        result = note,
        lastSuccessAtMs = ProviderRefreshMarks.lastSuccessfulFetchAt(context, instanceId),
    )
    val base = stringResource(labels.base.resId, *labels.base.args.toTypedArray())
    val suffix = labels.staleSuffix ?: return base
    return "$base · ${stringResource(suffix.resId, *suffix.args.toTypedArray())}"
}
