package com.openminis.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-models-refresh-failure-reason] Durability half of the failure reason.
 *
 * [ModelRefreshDiagnostics] is the hand-off between a fetch and the repository
 * and dies with the process; the provider screen re-reads its state after
 * process death, so the reason has to be on disk or it degrades back to the
 * anonymous "Refresh failed" line while the cause is perfectly known.
 *
 * Also pins the layout rule that makes this safe: the reasons share the prefs
 * file with the per-instance results, so `load()` — which feeds the list
 * badge — must never mistake a `failure_*` key for an instance id. A provider
 * named `failure_openai` would otherwise appear out of nowhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProviderRefreshMarksTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("provider_refresh_marks", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(PROVIDER_CONFIG_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ModelRefreshDiagnostics.resetForTests()
    }

    @Test
    fun `encode and decode round-trip both shapes`() {
        val withStatus = ModelRefreshFailure(ModelRefreshFailure.Kind.HTTP_ERROR, 404)
        val withoutStatus = ModelRefreshFailure(ModelRefreshFailure.Kind.TRANSPORT)

        assertEquals(withStatus, decodeFailure(encodeFailure(withStatus)))
        assertEquals(withoutStatus, decodeFailure(encodeFailure(withoutStatus)))
        assertEquals("HTTP_ERROR:404", encodeFailure(withStatus))
        assertEquals("TRANSPORT", encodeFailure(withoutStatus))
    }

    @Test
    fun `an unknown kind decodes to null instead of throwing`() {
        // A mark written by a future build must not crash the screen.
        assertNull(decodeFailure("SOMETHING_NEW:418"))
        assertNull(decodeFailure(""))
    }

    @Test
    fun `a failure survives the process boundary`() {
        ProviderRefreshMarks.record(
            context, "inst-a", ModelRefreshResult.FAILURE,
            ModelRefreshFailure(ModelRefreshFailure.Kind.HTTP_ERROR, 404),
        )

        assertEquals(ModelRefreshResult.FAILURE, ProviderRefreshMarks.get(context, "inst-a"))
        assertEquals(404, ProviderRefreshMarks.failureOf(context, "inst-a")?.httpStatus)
    }

    @Test
    fun `a success clears the reason as well as the result`() {
        ProviderRefreshMarks.record(
            context, "inst-b", ModelRefreshResult.FAILURE,
            ModelRefreshFailure(ModelRefreshFailure.Kind.PAYLOAD),
        )
        ProviderRefreshMarks.record(context, "inst-b", ModelRefreshResult.SUCCESS_API)

        assertNull(ProviderRefreshMarks.get(context, "inst-b"))
        assertNull("the cause must not outlive the failure", ProviderRefreshMarks.failureOf(context, "inst-b"))
    }

    @Test
    fun `captureFailure drains the in-memory reason into the mark`() {
        ModelRefreshDiagnostics.recordHttp("inst-c", 404)

        ProviderRefreshMarks.captureFailure(context, "inst-c")

        assertEquals(ModelRefreshResult.FAILURE, ProviderRefreshMarks.get(context, "inst-c"))
        assertEquals(404, ProviderRefreshMarks.failureOf(context, "inst-c")?.httpStatus)
        assertNull("reading it must consume it", ModelRefreshDiagnostics.take("inst-c"))
    }

    @Test
    fun `captureFailure writes nothing when the fetcher recorded no reason`() {
        ProviderRefreshMarks.captureFailure(context, "inst-d")

        assertNull(ProviderRefreshMarks.get(context, "inst-d"))
        assertNull(ProviderRefreshMarks.failureOf(context, "inst-d"))
    }

    @Test
    fun `load ignores the failure side-channel keys`() {
        ProviderRefreshMarks.record(
            context, "inst-e", ModelRefreshResult.FAILURE,
            ModelRefreshFailure(ModelRefreshFailure.Kind.HTTP_ERROR, 500),
        )

        val loaded = ProviderRefreshMarks.load(context)

        assertEquals(setOf("inst-e"), loaded.keys)
        assertTrue(loaded.values.all { it == ModelRefreshResult.FAILURE })
    }

    @Test
    fun `the success timestamp is read from the provider config prefs`() {
        assertEquals("never synced", 0L, ProviderRefreshMarks.lastSuccessfulFetchAt(context, "inst-f"))

        context.getSharedPreferences(PROVIDER_CONFIG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(ProviderRefreshMarks.lastFetchKey("inst-f"), 1_700_000_000_000L)
            .commit()

        assertEquals(1_700_000_000_000L, ProviderRefreshMarks.lastSuccessfulFetchAt(context, "inst-f"))
    }
}
