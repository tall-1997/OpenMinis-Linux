package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.data.repository.ModelRefreshFailure
import com.openminis.app.data.repository.ModelRefreshResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-models-refresh-failure-reason] Which sentence the provider screen shows.
 *
 * Kept as pure logic (resource id + args, resolved at the composable edge)
 * precisely so this matrix is testable: five results × four failure shapes is
 * the kind of table that drifts silently when it only lives inside a `when`
 * buried in a 1000-line screen.
 *
 * The floor matters as much as the detail: a `FAILURE` with no recorded reason
 * must still say "Refresh failed" rather than nothing, because that is what
 * every build before this one did and a blank line would read as success.
 */
class ProviderRefreshLabelsTest {

    private fun http(status: Int?) =
        ModelRefreshFailure(ModelRefreshFailure.Kind.HTTP_ERROR, status)

    @Test
    fun `an http failure names the status code`() {
        val label = ProviderRefreshLabels.subtitle(ModelRefreshResult.FAILURE, http(404))

        assertEquals(R.string.provider_refresh_failed_http, label?.resId)
        assertEquals(listOf<Any>(404), label?.args)
    }

    @Test
    fun `transport and payload failures name their class`() {
        assertEquals(
            R.string.provider_refresh_failed_transport,
            ProviderRefreshLabels.subtitle(
                ModelRefreshResult.FAILURE,
                ModelRefreshFailure(ModelRefreshFailure.Kind.TRANSPORT),
            )?.resId,
        )
        assertEquals(
            R.string.provider_refresh_failed_payload,
            ProviderRefreshLabels.subtitle(
                ModelRefreshResult.FAILURE,
                ModelRefreshFailure(ModelRefreshFailure.Kind.PAYLOAD),
            )?.resId,
        )
    }

    @Test
    fun `a failure with no recorded reason keeps the old wording`() {
        assertEquals(
            R.string.provider_sync_mark_failed,
            ProviderRefreshLabels.subtitle(ModelRefreshResult.FAILURE, null)?.resId,
        )
        assertEquals(
            "an http kind without a code is not renderable",
            R.string.provider_sync_mark_failed,
            ProviderRefreshLabels.subtitle(ModelRefreshResult.FAILURE, http(null))?.resId,
        )
    }

    @Test
    fun `success and the two non-failure notes map as before`() {
        assertNull(
            "a success clears the mark, so it can never be observed",
            ProviderRefreshLabels.subtitle(ModelRefreshResult.SUCCESS_API, null),
        )
        assertEquals(
            R.string.provider_sync_mark_nokey,
            ProviderRefreshLabels.subtitle(ModelRefreshResult.NO_KEY, null)?.resId,
        )
        assertEquals(
            R.string.provider_sync_mark_unchanged,
            ProviderRefreshLabels.subtitle(ModelRefreshResult.PRESERVED, null)?.resId,
        )
        assertNull(ProviderRefreshLabels.subtitle(null, null))
    }

    @Test
    fun `a failed refresh dates the list it left behind`() {
        val header = ProviderRefreshLabels.modelsHeader(
            count = 5,
            result = ModelRefreshResult.FAILURE,
            lastSuccessAtMs = 1_700_000_000_000L,
            nowMs = 1_700_000_000_000L,
        )

        assertEquals(R.string.provider_detail_models_count_header, header.base.resId)
        assertEquals(listOf<Any>(5), header.base.args)
        assertEquals(R.string.provider_models_stale_suffix, header.staleSuffix?.resId)
        assertEquals(1, header.staleSuffix?.args?.size)
    }

    @Test
    fun `a failure with no successful sync says so`() {
        val header = ProviderRefreshLabels.modelsHeader(
            count = 5,
            result = ModelRefreshResult.FAILURE,
            lastSuccessAtMs = 0L,
        )

        assertEquals(R.string.provider_models_never_synced, header.staleSuffix?.resId)
        assertTrue("pitfall: this one has nothing to interpolate", header.staleSuffix?.args.isNullOrEmpty())
    }

    @Test
    fun `only a failure with something on screen widens the header`() {
        assertNull(
            "a successful refresh needs no age warning",
            ProviderRefreshLabels.modelsHeader(5, ModelRefreshResult.SUCCESS_API, 1_700_000_000_000L).staleSuffix,
        )
        assertNull(
            "an empty picker has no stale list to mislead with",
            ProviderRefreshLabels.modelsHeader(0, ModelRefreshResult.FAILURE, 1_700_000_000_000L).staleSuffix,
        )
    }

    @Test
    fun `the stamp drops the year only within the current year`() {
        val sameYear = ProviderRefreshLabels.formatStamp(1_700_000_000_000L, 1_700_100_000_000L)
        val otherYear = ProviderRefreshLabels.formatStamp(1_600_000_000_000L, 1_700_000_000_000L)

        // Locale-independent: both patterns are numeric fields only.
        assertTrue("same year keeps MM-dd HH:mm, got $sameYear", Regex("""\d{2}-\d{2} \d{2}:\d{2}""").matches(sameYear))
        assertTrue("older year keeps the full date, got $otherYear", Regex("""\d{4}-\d{2}-\d{2}""").matches(otherYear))
    }
}
