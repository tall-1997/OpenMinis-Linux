package com.openminis.app.accessibility

import com.openminis.app.tools.ToolErrorCode
import com.openminis.app.tools.UiSkillTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * UiSkillTool 的 action 分支与错误码映射（纯 JVM：A11ySkillStore 走 File 构造；
 * replay 的服务依赖在 JVM 下 MinisAccessibilityService.getInstance()==null，
 * 正好覆盖「服务未启用」分支；save 依赖录制缓冲，无法在 JVM 灌数据，
 * 只覆盖空录制分支）。
 */
class UiSkillToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): A11ySkillStore = A11ySkillStore(tmp.newFolder())

    private fun storeWithSkill(name: String = "刷视频", stale: Boolean = false): A11ySkillStore {
        val s = store()
        val saved = s.save(name, "instruction for $name", listOf(step()), null)!!
        if (stale) repeat(A11ySkillStore.STALE_AFTER_FAILURES) { s.recordFailure(saved.id) }
        return s
    }

    private fun step() = A11ySkillStore.Step(
        kind = "TAP", packageName = "com.example", text = "OK",
        viewId = null, contentDescription = null, xy = "10,20", atMs = 0L,
    )

    // ─── 参数层 ───────────────────────────────────────────────────────────

    @Test
    fun `invalid args json maps to INVALID_ARGUMENTS`() {
        val r = UiSkillTool.execute("{not json", store())
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
        assertTrue(r.output.contains("invalid args"))
    }

    @Test
    fun `null store maps to UNSUPPORTED with a wiring hint`() {
        val r = UiSkillTool.execute("""{"action":"list"}""", null)
        assertFalse(r.success)
        assertEquals(ToolErrorCode.UNSUPPORTED, r.errorCode)
        assertTrue(r.output.contains("store unavailable"))
    }

    @Test
    fun `unknown action maps to INVALID_ARGUMENTS with recovery hint`() {
        val r = UiSkillTool.execute("""{"action":"frobnicate"}""", store())
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
        assertEquals("Choose one of: list, match, replay, save, delete.", r.recoveryHint)
    }

    // ─── list ─────────────────────────────────────────────────────────────

    @Test
    fun `list on empty store says how to record the first skill`() {
        val r = UiSkillTool.execute("""{"action":"list"}""", store())
        assertTrue(r.success)
        assertTrue(r.output.contains("no a11y skills yet"))
        assertTrue(r.output.contains("录制场景"))
    }

    @Test
    fun `list shows ready and stale markers with fail streak`() {
        val s = storeWithSkill(name = "ready-skill")
        val staleStore = storeWithSkill(name = "stale-skill", stale = true)
        // 合并到同一个 store 再列
        val merged = store()
        merged.save("ready-skill", "i1", listOf(step()), null)
        val id = merged.save("stale-skill", "i2", listOf(step()), null)!!.id
        repeat(A11ySkillStore.STALE_AFTER_FAILURES) { merged.recordFailure(id) }
        val r = UiSkillTool.execute("""{"action":"list"}""", merged)
        assertTrue(r.success)
        assertTrue(r.output.contains("ready-skill\tready"))
        assertTrue(r.output.contains("stale-skill\tSTALE"))
        assertTrue(r.output.contains("failStreak=3"))
    }

    // ─── match ────────────────────────────────────────────────────────────

    @Test
    fun `match without instruction maps to INVALID_ARGUMENTS`() {
        val r = UiSkillTool.execute("""{"action":"match"}""", storeWithSkill())
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun `match hit reports name score and step count`() {
        val r = UiSkillTool.execute(
            """{"action":"match","instruction":"instruction for 刷视频"}""", storeWithSkill(),
        )
        assertTrue(r.success)
        assertTrue(r.output.contains("match: 刷视频"))
        assertTrue(r.output.contains("score=1.00"))
    }

    @Test
    fun `match miss is a success result with threshold hint`() {
        val r = UiSkillTool.execute(
            """{"action":"match","instruction":"把屏幕亮度调到最高"}""", storeWithSkill(),
        )
        assertTrue(r.success)
        assertTrue(r.output.contains("no skill matches"))
        assertTrue(r.output.contains("0.22"))
    }

    // ─── replay ───────────────────────────────────────────────────────────

    @Test
    fun `replay unknown name maps to NOT_FOUND`() {
        val r = UiSkillTool.execute("""{"action":"replay","name":"ghost"}""", storeWithSkill())
        assertFalse(r.success)
        assertEquals(ToolErrorCode.NOT_FOUND, r.errorCode)
    }

    @Test
    fun `replay without name or instruction maps to INVALID_ARGUMENTS`() {
        val r = UiSkillTool.execute("""{"action":"replay"}""", storeWithSkill())
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun `replay stale skill maps to STALE_OBSERVATION and refuses to run`() {
        val r = UiSkillTool.execute("""{"action":"replay","name":"stale-skill"}""", storeWithSkill(name = "stale-skill", stale = true))
        assertFalse(r.success)
        assertEquals(ToolErrorCode.STALE_OBSERVATION, r.errorCode)
        assertTrue(r.output.contains("stale"))
        assertTrue(r.output.contains("does not replay"))
    }

    @Test
    fun `replay without accessibility service maps to PERMISSION_DENIED`() {
        // JVM 下 MinisAccessibilityService.getInstance() == null（isReturnDefaultValues）
        val r = UiSkillTool.execute("""{"action":"replay","name":"刷视频"}""", storeWithSkill())
        assertFalse(r.success)
        assertEquals(ToolErrorCode.PERMISSION_DENIED, r.errorCode)
        assertTrue(r.output.contains("accessibility service not running"))
    }

    @Test
    fun `replay miss by instruction maps to NOT_FOUND with record hint`() {
        val r = UiSkillTool.execute(
            """{"action":"replay","instruction":"完全无关的指令序列"}""", storeWithSkill(),
        )
        assertFalse(r.success)
        assertEquals(ToolErrorCode.NOT_FOUND, r.errorCode)
        assertTrue(r.output.contains("record it first"))
    }

    // ─── save ─────────────────────────────────────────────────────────────

    @Test
    fun `save without name maps to INVALID_ARGUMENTS`() {
        val r = UiSkillTool.execute("""{"action":"save","instruction":"x"}""", store())
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun `save with empty recording buffer maps to INVALID_ARGUMENTS with hint`() {
        // JVM 下 A11yScriptRecorder.steps 为空（无法在纯 JVM 灌录制数据）
        val r = UiSkillTool.execute("""{"action":"save","name":"n"}""", store())
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
        assertTrue(r.output.contains("no recording in progress"))
        assertTrue(r.recoveryHint!!.contains("A11yScriptRecorder.start()"))
    }

    // ─── delete ───────────────────────────────────────────────────────────

    @Test
    fun `delete removes the skill by name`() {
        val s = storeWithSkill()
        val r = UiSkillTool.execute("""{"action":"delete","name":"刷视频"}""", s)
        assertTrue(r.success)
        assertTrue(r.output.contains("deleted"))
        assertNull(s.byName("刷视频"))
    }

    @Test
    fun `delete unknown name maps to NOT_FOUND and missing name to INVALID_ARGUMENTS`() {
        val missing = UiSkillTool.execute("""{"action":"delete","name":"ghost"}""", storeWithSkill())
        assertFalse(missing.success)
        assertEquals(ToolErrorCode.NOT_FOUND, missing.errorCode)
        val noName = UiSkillTool.execute("""{"action":"delete"}""", storeWithSkill())
        assertFalse(noName.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, noName.errorCode)
    }

    // ─── definition 形状 ──────────────────────────────────────────────────

    @Test
    fun `definition exposes five actions with the tool name ui_skill`() {
        val d = UiSkillTool.definition()
        assertEquals(UiSkillTool.NAME, d.name)
        assertEquals("ui_skill", d.name)
        val actions = d.parameters["action"]!!.enumValues!!
        assertEquals(listOf("list", "match", "replay", "save", "delete"), actions)
        assertTrue(d.required.contains("action"))
    }
}
