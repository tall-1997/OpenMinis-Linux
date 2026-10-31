package com.openminis.app.knowledge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Injection dedupe on the shared store: once per session per pack for `app_launch`,
 * foreground-package-driven injection for `ui_read`, honest degradation without an a11y
 * service, and the real asset packs being loadable and bounded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppKnowledgeStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppKnowledgeStore.resetForTests()
        AppKnowledgeStore.sourceProvider = { TestPacks.source() }
        AppKnowledgeStore.foregroundProvider = { null }
    }

    @Test
    fun realAssetPacksLoad() {
        val entries = AppKnowledgeStore.get(context).entries
        assertTrue("expected at least 16 packs, got ${entries.size}", entries.size >= 16)
        entries.forEach { e ->
            assertTrue("$e: packages must be non-empty", e.packages.isNotEmpty() && e.packages.all { it.isNotBlank() })
            assertTrue("$e: aliases must be non-empty", e.aliases.isNotEmpty())
            val bodyLines = e.body.lines()
            val nonBlank = bodyLines.count { it.isNotBlank() }
            assertTrue("$e: body should have 15-30 content lines, got $nonBlank", nonBlank in 15..32)
            val sections = bodyLines.count { it.startsWith("## ") }
            assertTrue("$e: at least two sections, got $sections", sections >= 2)
        }
    }

    @Test
    fun afterLaunchInjectsOncePerSession() {
        val first = AppKnowledgeStore.afterLaunch(context, "com.tencent.mm", "s1")
        assertNotNull(first)
        assertTrue(first!!.startsWith("---\n[App knowledge: "))
        assertTrue(first.length <= AppKnowledge.MAX_INJECT_CHARS)
        assertNull("same session must not be injected twice", AppKnowledgeStore.afterLaunch(context, "com.tencent.mm", "s1"))
        assertNotNull("a different session gets its own injection", AppKnowledgeStore.afterLaunch(context, "com.tencent.mm", "s2"))
    }

    @Test
    fun afterLaunchUnknownPackageReturnsNull() {
        assertNull(AppKnowledgeStore.afterLaunch(context, "com.not.covered", "s1"))
    }

    @Test
    fun onScreenReadUsesForegroundPackage() {
        AppKnowledgeStore.foregroundProvider = { "com.eg.android.AlipayGphone" }
        val injected = AppKnowledgeStore.onScreenRead(context, "s1")
        assertNotNull(injected)
        assertNull("dedupe also applies to screen reads", AppKnowledgeStore.onScreenRead(context, "s1"))
        AppKnowledgeStore.foregroundProvider = { "com.ss.android.ugc.aweme" }
        assertNotNull("a different foreground app injects its own pack", AppKnowledgeStore.onScreenRead(context, "s1"))
    }

    @Test
    fun onScreenReadSkipsOwnPackageAndEmptyForeground() {
        AppKnowledgeStore.foregroundProvider = { context.packageName }
        assertNull(AppKnowledgeStore.onScreenRead(context, "s1"))
        AppKnowledgeStore.foregroundProvider = { null }
        assertNull("no a11y service -> honest no-injection", AppKnowledgeStore.onScreenRead(context, "s1"))
    }

    @Test
    fun guideLookupMarksPackGiven() {
        AppKnowledgeStore.markGiven("s9", "wechat")
        assertTrue(AppKnowledgeStore.wasGiven("s9", "wechat"))
        AppKnowledgeStore.markGiven(null, "wechat")
        assertTrue(AppKnowledgeStore.wasGiven(null, "wechat"))
        AppKnowledgeStore.resetForTests()
        assertTrue(!AppKnowledgeStore.wasGiven("s9", "wechat"))
    }
}
