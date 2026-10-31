package com.openminis.app.knowledge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `app_guide` / `app_note` through the execute() contract: handles(), the no-pack answer,
 * notes-before-body ordering, dedupe and privacy rejection, and the Error: shapes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppKnowledgeToolsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppKnowledgeStore.resetForTests()
        AppKnowledgeStore.sourceProvider = { TestPacks.source() }
        AppNotesStore.resetForTests()
        RouteTrackers.global.resetForTests()
    }

    private fun guide(app: String, session: String? = "sess-1"): String =
        AppKnowledgeTools.execute(AppKnowledgeTools.GUIDE, JSONObject().put("app", app).put("tool_title", "t").toString(), session, context)

    private fun note(pkg: String, text: String, kind: String = "entry"): String =
        AppKnowledgeTools.execute(
            AppKnowledgeTools.NOTE,
            JSONObject().put("package", pkg).put("text", text).put("kind", kind).put("tool_title", "t").toString(),
            "sess-1",
            context,
        )

    @Test
    fun handlesOnlyItsOwnTools() {
        assertTrue(AppKnowledgeTools.handles("app_guide"))
        assertTrue(AppKnowledgeTools.handles("app_note"))
        assertFalse(AppKnowledgeTools.handles("app_launch"))
        assertFalse(AppKnowledgeTools.handles("app_list"))
        assertEquals("app_", AppKnowledgeTools.NAME_PREFIX)
        assertTrue(AppKnowledgeTools.definitions().map { it.name }.containsAll(listOf("app_guide", "app_note")))
    }

    @Test
    fun unknownToolUsesErrorShape() {
        val out = AppKnowledgeTools.execute("app_other", "{}", "s", context)
        assertTrue(out.startsWith("Error: unknown_tool —"))
    }

    @Test
    fun guideUnknownAppReturnsExplicitNoPackAnswer() {
        val out = guide("com.definitely.not.installed")
        assertTrue(out.contains("No knowledge pack"))
        assertFalse(out.startsWith("Error:"))
        assertTrue(out.contains("app_note"))
    }

    @Test
    fun guideResolvesByAliasAndPackage() {
        val byAlias = guide("weixin")
        assertTrue(byAlias.contains("[App knowledge: "))
        val byPackage = guide("com.tencent.mm")
        assertEquals(byAlias, byPackage)
    }

    @Test
    fun guidePutsLocalNotesBeforePackBody() {
        val saved = AppNotesStore.forContext(context).add("com.tencent.mm", "设置里搜索直达深层的项", "entry")
        assertTrue(saved is AppNotes.Outcome.Saved)
        val out = guide("com.tencent.mm")
        val notesIdx = out.indexOf("[n1]")
        val bodyIdx = out.indexOf("## ")
        assertTrue("note line present", notesIdx > 0)
        assertTrue("notes come first", notesIdx < bodyIdx)
    }

    @Test
    fun noteSavedThenDeduplicated() {
        val first = note("com.example.noteapp", "长按列表项可以多选", "trap")
        assertTrue(first.startsWith("Saved note n1"))
        val second = note("com.example.noteapp", "长按列表项  可以多选", "trap")
        assertTrue(second.startsWith("Already recorded as n1"))
        assertEquals(1, AppNotesStore.forContext(context).all.value.size)
    }

    @Test
    fun notePrivacyRejectsPersonalData() {
        val out = note("com.example.noteapp", "联系我 someone@example.com", "fact")
        assertTrue(out.startsWith("Error: rejected —"))
        assertTrue(AppNotesStore.forContext(context).all.value.isEmpty())
    }

    @Test
    fun invalidArgsUseErrorShape() {
        val missingApp = AppKnowledgeTools.execute(AppKnowledgeTools.GUIDE, "{}", "s", context)
        assertTrue(missingApp.startsWith("Error: invalid_args —"))
        val badJson = AppKnowledgeTools.execute(AppKnowledgeTools.NOTE, "not-json", "s", context)
        assertTrue(badJson.startsWith("Error: invalid_args —"))
        val missingText = AppKnowledgeTools.execute(
            AppKnowledgeTools.NOTE,
            JSONObject().put("package", "com.a").toString(),
            "s",
            context,
        )
        assertTrue(missingText.startsWith("Error: invalid_args —"))
    }

    @Test
    fun guideMarkGivenForSession() {
        guide("wechat", session = "sess-7")
        assertTrue(AppKnowledgeStore.wasGiven("sess-7", "wechat"))
        assertFalse(AppKnowledgeStore.wasGiven("sess-8", "wechat"))
    }

    // ── [T-android-route-tracker-read] the tap ledger gains readers ──

    @Test
    fun routeNoteRecordsTheTrackedTapPathInsteadOfTheModelText() {
        // Dedicated session so sibling tests' recorded routes cannot leak in
        // (JUnit method order is not guaranteed).
        RouteTrackers.global.record("sess-route-a", "com.tencent.mm", "我")
        RouteTrackers.global.record("sess-route-a", "com.tencent.mm", "设置")
        val out = AppKnowledgeTools.execute(
            AppKnowledgeTools.NOTE,
            JSONObject().put("package", "com.tencent.mm")
                .put("text", "the model's own retelling")
                .put("kind", "route")
                .put("tool_title", "t")
                .toString(),
            "sess-route-a",
            context,
        )
        assertTrue(out.startsWith("Saved note"))
        val stored = AppNotesStore.forContext(context).all.value.single { it.text == "Route: 我 → 设置" }
        assertEquals(AppNotes.Kind.route, stored.kind)
    }

    @Test
    fun routeNoteFallsBackToModelTextWhenTrackerIsEmpty() {
        // Dedicated session AND package so sibling tests' recorded routes and
        // notes cannot leak in (JUnit method order is not guaranteed).
        val out = AppKnowledgeTools.execute(
            AppKnowledgeTools.NOTE,
            JSONObject().put("package", "com.example.routefallback")
                .put("text", "底部 tab 第三个进入设置")
                .put("kind", "route")
                .put("tool_title", "t")
                .toString(),
            "sess-no-route",
            context,
        )
        assertTrue(out.startsWith("Saved note"))
        assertEquals(
            "底部 tab 第三个进入设置",
            AppNotesStore.forContext(context).all.value.single { it.pkg == "com.example.routefallback" }.text,
        )
    }

    @Test
    fun guidePrependsRecentSuccessfulRouteBeforeBody() {
        RouteTrackers.global.record("sess-1", "com.tencent.mm", "搜索")
        RouteTrackers.global.record("sess-1", "com.tencent.mm", "设置")
        val out = guide("com.tencent.mm")
        val routeIdx = out.indexOf("Recent successful route: 搜索 → 设置")
        val bodyIdx = out.indexOf("## ")
        assertTrue("route line present", routeIdx > 0)
        assertTrue("route comes before the pack body", routeIdx < bodyIdx)
    }

    @Test
    fun guideOmitsRouteBlockWhenNothingWasRecorded() {
        assertFalse(guide("com.tencent.mm").contains("Recent successful route"))
    }
}
