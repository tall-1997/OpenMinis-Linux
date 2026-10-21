package com.openminis.app.data.repository

import com.openminis.app.provider.openai.OpenAIModelsApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-models-refresh-failure-reason] The fetcher must hand the *reason* it
 * produced nothing to [ModelRefreshDiagnostics], not just an empty list.
 *
 * Before this, `refreshModels` returned a bare `ModelRefreshResult.FAILURE` for
 * every one of these cases and the provider screen could only render "Refresh
 * failed". The measured report: a provider saved as
 * `http://127.0.0.1:8080/v1` while the server listened on `:8000` — every
 * refresh answered 404, the screen said "Refresh failed", and five models from
 * an earlier sync stayed listed, so the wrong port looked like a live but
 * flaky provider for days.
 *
 * These assertions run the real [OpenAIModelsApi.fetchModels] against a real
 * socket, because the failure classification lives inside that function: the
 * status check moved ahead of the body read (a 404 with an empty body used to
 * return at the `?:` with the code dropped), and the payload verdicts sit in
 * three different branches. A pure-logic test of the diagnostics map would pass
 * while the fetcher still recorded nothing.
 */
class ModelRefreshDiagnosticsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        ModelRefreshDiagnostics.resetForTests()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ModelRefreshDiagnostics.resetForTests()
    }

    /** Base shaped like ProviderConfig.effectiveBaseURL — already carries /v1. */
    private fun base(): String = server.url("/v1").toString().trimEnd('/')

    @Test
    fun `404 records the status code instead of only an empty list`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        val models = OpenAIModelsApi.fetchModels("k", base(), forceRefresh = true, cacheScope = "scope-404")

        assertTrue("a 404 yields no models", models.isEmpty())
        val failure = ModelRefreshDiagnostics.take("scope-404")
        assertEquals(ModelRefreshFailure.Kind.HTTP_ERROR, failure?.kind)
        assertEquals(404, failure?.httpStatus)
    }

    /**
     * The `?:` on the body was the original bug: a wrong port answering 404
     * with an EMPTY body returned before the status was ever looked at, so the
     * code vanished. Pinning both shapes keeps the reordering honest.
     */
    @Test
    fun `404 with an empty body still records the status code`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        OpenAIModelsApi.fetchModels("k", base(), forceRefresh = true, cacheScope = "scope-404-empty")

        assertEquals(404, ModelRefreshDiagnostics.take("scope-404-empty")?.httpStatus)
    }

    @Test
    fun `200 with a non-JSON body records a payload failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>captive portal</html>"))

        val models = OpenAIModelsApi.fetchModels("k", base(), forceRefresh = true, cacheScope = "scope-html")

        assertTrue(models.isEmpty())
        assertEquals(ModelRefreshFailure.Kind.PAYLOAD, ModelRefreshDiagnostics.take("scope-html")?.kind)
    }

    @Test
    fun `200 without a data array records a payload failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"list"}"""))

        OpenAIModelsApi.fetchModels("k", base(), forceRefresh = true, cacheScope = "scope-nodata")

        assertEquals(ModelRefreshFailure.Kind.PAYLOAD, ModelRefreshDiagnostics.take("scope-nodata")?.kind)
    }

    @Test
    fun `a successful fetch clears a stale reason`() = runBlocking {
        ModelRefreshDiagnostics.recordHttp("scope-ok", 500)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"object":"list","data":[{"id":"m1","object":"model"}]}"""),
        )

        val models = OpenAIModelsApi.fetchModels("k", base(), forceRefresh = true, cacheScope = "scope-ok")

        assertTrue("the catalogue must come back", models.isNotEmpty())
        assertNull(
            "a success must not leave the previous failure readable",
            ModelRefreshDiagnostics.take("scope-ok"),
        )
    }

    @Test
    fun `reasons are scoped per instance and read once`() {
        ModelRefreshDiagnostics.recordHttp("a", 404)
        ModelRefreshDiagnostics.recordHttp("b", 500)

        assertEquals(404, ModelRefreshDiagnostics.take("a")?.httpStatus)
        assertEquals(500, ModelRefreshDiagnostics.take("b")?.httpStatus)
        assertNull("a second read must not resurrect it", ModelRefreshDiagnostics.take("a"))
    }

    @Test
    fun `a blank scope records nothing`() {
        ModelRefreshDiagnostics.recordHttp("", 404)
        assertNull(
            "anonymous callers must not share one slot",
            ModelRefreshDiagnostics.take(""),
        )
    }
}
