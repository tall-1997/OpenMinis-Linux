package com.openminis.app.provider

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * [GH#258] The free-lane probe client must follow the app trust store's
 * generation instead of being frozen by `by lazy` at first use.
 *
 * 中文：CA 导入/删除会 bump `AppTrustStore.generation`；免费 lane 的探测请求
 * 也走同一个 CA 信任链，所以 generation 变化后的下一次探测必须拿到新 client。
 * 本测试用可注入的 generation 源 + client 工厂把这条契约钉在 JVM 上，无需
 * Android Context，也不会真的发请求。
 */
class FreeLaneDirectoryClientTest {

    @Before
    fun setUp() {
        FreeLaneDirectory.resetClientForTest()
    }

    @After
    fun tearDown() {
        FreeLaneDirectory.trustGenerationForTest = null
        FreeLaneDirectory.clientFactoryForTest = null
        FreeLaneDirectory.resetClientForTest()
    }

    @Test
    fun `a CA generation change rebuilds the probe client on the next access`() {
        var generation = 0
        var builds = 0
        FreeLaneDirectory.trustGenerationForTest = { generation }
        FreeLaneDirectory.clientFactoryForTest = {
            builds++
            OkHttpClient()
        }

        val first = FreeLaneDirectory.clientForTest()
        assertEquals("first access builds exactly one client", 1, builds)
        assertSame(
            "same generation reuses the client (one pool per generation)",
            first,
            FreeLaneDirectory.clientForTest(),
        )
        assertEquals(1, builds)

        // Simulate Settings → Trusted certificates importing/removing a CA.
        generation = 1
        val second = FreeLaneDirectory.clientForTest()
        assertNotSame(
            "the next probe after a CA change must not reuse the old sslSocketFactory",
            first,
            second,
        )
        assertEquals(2, builds)
        assertSame(second, FreeLaneDirectory.clientForTest())
        assertEquals("stable again until the next generation bump", 2, builds)
    }
}
