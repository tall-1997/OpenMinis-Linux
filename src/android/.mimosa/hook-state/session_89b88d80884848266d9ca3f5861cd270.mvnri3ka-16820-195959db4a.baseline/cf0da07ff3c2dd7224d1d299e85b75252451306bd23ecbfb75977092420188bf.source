package com.openminis.app.provider

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
 * [T-models-fetch-no-query-buster] The catalog request must reach the endpoint
 * the user typed, with cache defeat carried in headers rather than in the URL.
 *
 * A gateway that routes strictly on path answers 404 with an empty body to
 * *any* query string on `/v1/models` — measured: `?minis_nocache=x` and
 * `?foo=bar` both 404, while the same request with `Cache-Control: no-cache,
 * no-store` returns 200. `bustUrl` only appended its parameter on force
 * refresh, so adding a provider worked and pressing Refresh never could; since
 * Refresh also runs `clearFirst`, the picker was emptied and then could not be
 * repopulated. These assertions are the guard against that shape of URL
 * mutation coming back.
 */
class ModelListUrlShapeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Base shaped like ProviderConfig.effectiveBaseURL — already carries /v1. */
    private fun base(): String = server.url("/v1").toString().trimEnd('/')

    private fun enqueueCatalog() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"object":"list","data":[{"id":"m1","object":"model"}]}"""),
        )
    }

    @Test
    fun `force refresh puts no query string on the catalog URL`() = runBlocking {
        enqueueCatalog()
        OpenAIModelsApi.fetchModels("k", base(), forceRefresh = true, cacheScope = "instance-1")
        assertEquals(
            "a query string here 404s on strict-path gateways",
            "/v1/models",
            server.takeRequest().path,
        )
    }

    @Test
    fun `a plain refresh puts no query string on the catalog URL either`() = runBlocking {
        enqueueCatalog()
        OpenAIModelsApi.fetchModels("k", base(), cacheScope = "instance-1")
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test
    fun `force refresh defeats caches with headers, not with the URL`() = runBlocking {
        enqueueCatalog()
        OpenAIModelsApi.fetchModels("k", base(), forceRefresh = true, cacheScope = "instance-1")
        val recorded = server.takeRequest()
        assertEquals("no-cache, no-store", recorded.getHeader("Cache-Control"))
        assertEquals("no-cache", recorded.getHeader("Pragma"))
    }

    @Test
    fun `a plain refresh sends no cache overrides`() = runBlocking {
        enqueueCatalog()
        OpenAIModelsApi.fetchModels("k", base())
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Pragma"))
        assertTrue(
            "a plain refresh must not claim no-store",
            recorded.getHeader("Cache-Control") != "no-cache, no-store",
        )
    }

    @Test
    fun `the catalog still parses on a force refresh`() = runBlocking {
        enqueueCatalog()
        val models = OpenAIModelsApi.fetchModels("k", base(), forceRefresh = true)
        assertTrue("got ${models.map { it.id }}", models.any { it.id == "m1" })
    }
}
