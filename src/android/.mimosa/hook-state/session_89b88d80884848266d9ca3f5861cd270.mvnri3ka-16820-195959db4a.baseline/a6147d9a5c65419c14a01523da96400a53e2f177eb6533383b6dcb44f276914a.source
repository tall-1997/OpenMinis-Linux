package com.openminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SkillReplayEngine 的六场景覆盖，全部用 fake [A11yActor]（不碰
 * MinisAccessibilityService / android.graphics.Path——Production impl
 * ServiceA11yActor 才有 android 依赖，引擎本身纯 JVM）。
 */
class SkillReplayEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 可编程 fake：dump 队列 + 各动作成败 + 调用记录。 */
    private class FakeActor(
        var dumps: ArrayDeque<String> = ArrayDeque(),
        var tapOk: Boolean = true,
        var tapByTextOk: Boolean = true,
        var typeOk: Boolean = true,
    ) : A11yActor {
        val taps = mutableListOf<Pair<Int, Int>>()
        val tapsByText = mutableListOf<String>()
        val typed = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        var nextTapFailsAt = -1 // 第 N 次 tap 失败（0-based 调用序号；-1 = 永不）
        private var tapCount = 0
        var dumpCount = 0
            private set

        override fun dumpScreen(): String {
            dumpCount++
            return dumps.removeFirstOrNull() ?: ""
        }
        override fun tap(x: Int, y: Int): Boolean {
            val idx = tapCount
            taps.add(x to y)
            tapCount++
            return if (idx == nextTapFailsAt) false else tapOk
        }
        override fun tapByText(text: String): Boolean {
            tapsByText.add(text)
            return tapByTextOk
        }
        override fun typeText(text: String): Boolean { typed.add(text); return typeOk }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
        override fun back(): Boolean = true
        override fun openApp(packageName: String): Boolean = true
        override fun sleep(ms: Long) { waits.add(ms) }
    }

    private fun store(): A11ySkillStore = A11ySkillStore(tmp.newFolder())

    private fun step(
        kind: String = "TAP",
        text: String? = "OK",
        xy: String? = "10,20",
        atMs: Long = 0L,
    ) = A11ySkillStore.Step(
        kind = kind, packageName = "com.example", text = text,
        viewId = null, contentDescription = null, xy = xy, atMs = atMs,
    )

    private fun saveSkill(
        store: A11ySkillStore,
        name: String = "flow",
        steps: List<A11ySkillStore.Step>,
    ): A11ySkillStore.Skill = store.save(name, "instruction for $name", steps, null)!!

    private fun dumpWithLabel(label: String, bounds: String = "100,200-200,300"): String =
        "[id] text=$label desc= cls= bounds=$bounds clickable=true editable=false\n"

    // ─── 场景 1：全过清零 ─────────────────────────────────────────────────

    @Test
    fun `all steps pass clears failStreak and refreshes lru`() {
        val store = store()
        val skill = saveSkill(
            store,
            steps = listOf(step(atMs = 0), step(kind = "INPUT", atMs = 1_000, xy = null, text = "hello")),
        )
        store.recordFailure(skill.id)
        store.recordFailure(skill.id)
        val actor = FakeActor()
        // 每步消费一个 dump：step0 的 dump 带 text=OK → label 重解析 tap 中心；
        // 「OK」不在弹窗词表，弹窗预检不得吃掉正常步骤
        actor.dumps.addAll(listOf(dumpWithLabel("OK"), dumpWithLabel("无关")))
        val r = SkillReplayEngine(actor, store).replay(store.byId(skill.id)!!)
        assertTrue(r.reason, r.success)
        assertEquals(2, r.stepsExecuted)
        assertEquals(0, r.popupsDismissed)
        assertEquals(0, store.byId(skill.id)!!.failStreak)
        // 步间等待 = clamp(1000, 200..3000) = 1000
        assertEquals(listOf(1000L), actor.waits)
        // label 重解析：tap 的是 dump 中心，且坐标已自愈写回
        assertEquals(listOf(150 to 250), actor.taps)
        assertEquals("150,250", store.byId(skill.id)!!.steps[0].resolvedXy)
    }

    @Test
    fun `empty skill fails fast without touching the store`() {
        val store = store()
        val skill = saveSkill(store, steps = emptyList())
        val r = SkillReplayEngine(FakeActor(), store).replay(skill)
        assertFalse(r.success)
        assertNull(r.stepIndex)
        assertEquals("skill has no steps", r.reason)
        assertEquals(0, store.byId(skill.id)!!.failStreak) // 未执行任何步，不计失败
    }

    // ─── 场景 2：中途失败 failStreak+1 ────────────────────────────────────

    @Test
    fun `mid failure aborts with step index and bumps failStreak`() {
        val store = store()
        val skill = saveSkill(
            store,
            steps = listOf(
                step(atMs = 0, xy = "1,1", text = null),
                step(atMs = 300, xy = "2,2", text = null),
                step(atMs = 600, xy = "3,3", text = null),
            ),
        )
        val actor = FakeActor()
        actor.nextTapFailsAt = 1 // 第二次 tap（index 1，第 2 步）失败
        val r = SkillReplayEngine(actor, store).replay(store.byId(skill.id)!!)
        assertFalse(r.success)
        assertEquals(1, r.stepIndex)
        assertTrue(r.reason!!.contains("step 1"))
        assertEquals(1, r.stepsExecuted)
        assertEquals(1, store.byId(skill.id)!!.failStreak)
        assertFalse(store.byId(skill.id)!!.stale)
        // dumpExcerpt 作为证据返回（可能为 null——dump 为空时）
        assertNull(r.dumpExcerpt) // FakeActor dump 队列空 → dump ""
    }

    @Test
    fun `input step failure aborts with kind in reason`() {
        val store = store()
        val skill = saveSkill(store, steps = listOf(step(kind = "INPUT", text = "hello")))
        val actor = FakeActor(typeOk = false)
        val r = SkillReplayEngine(actor, store).replay(store.byId(skill.id)!!)
        assertFalse(r.success)
        assertEquals(0, r.stepIndex)
        assertTrue(r.reason!!.contains("INPUT"))
        assertEquals(listOf("hello"), actor.typed)
        assertEquals(1, store.byId(skill.id)!!.failStreak)
    }

    // ─── 场景 3：弹窗预检点击 ─────────────────────────────────────────────

    @Test
    fun `popup precheck dismisses known dialog labels before the step`() {
        val store = store()
        val skill = saveSkill(store, steps = listOf(step(atMs = 0, xy = "5,5")))
        val actor = FakeActor()
        // 第一次 dump 出现弹窗按钮「以后再说」→ tapByText 关掉；随后干净 dump
        actor.dumps.add(dumpWithLabel("以后再说"))
        actor.dumps.add(dumpWithLabel("无关内容"))
        val r = SkillReplayEngine(actor, store).replay(store.byId(skill.id)!!)
        assertTrue(r.success)
        assertEquals(1, r.popupsDismissed)
        assertEquals(listOf("以后再说"), actor.tapsByText)
        // TAP 用了录制的 xy 回退（dump 里没有 text=OK）
        assertEquals(listOf(5 to 5), actor.taps)
    }

    @Test
    fun `loading word waits then gives up with evidence dump`() {
        val store = store()
        val skill = saveSkill(store, steps = listOf(step(text = null, xy = "5,5")))
        val actor = FakeActor()
        // 永远处于「加载中」→ 重试 LOADING_RETRIES 次后放弃
        // （首查 1 次 + 每次重试各 1 次 dump，队列给足）
        repeat(SkillReplayEngine.LOADING_RETRIES + 5) { actor.dumps.add(dumpWithLabel("加载中")) }
        val r = SkillReplayEngine(actor, store).replay(store.byId(skill.id)!!)
        assertFalse(r.success)
        assertEquals(0, r.stepIndex)
        assertTrue(r.reason!!.contains("loading still present"))
        assertEquals(SkillReplayEngine.LOADING_RETRIES, actor.waits.size)
        assertTrue(actor.waits.all { it == SkillReplayEngine.LOADING_RETRY_MS })
        // 证据 dump 带回
        assertNotNull(r.dumpExcerpt)
        assertTrue(r.dumpExcerpt!!.contains("加载中"))
        // 加载卡死不算步骤失败，但按失败计 streak
        assertEquals(1, store.byId(skill.id)!!.failStreak)
    }

    // ─── 场景 4：label 重解析写回坐标（坐标自愈） ─────────────────────────

    @Test
    fun `label re-resolution taps fresh bounds and writes coordinates back`() {
        val store = store()
        val skill = saveSkill(store, steps = listOf(step(text = "登录", xy = "1,1")))
        val actor = FakeActor()
        actor.dumps.add(dumpWithLabel("登录", bounds = "100,200-200,300"))
        val r = SkillReplayEngine(actor, store).replay(store.byId(skill.id)!!)
        assertTrue(r.reason, r.success)
        // 点的是 dump 里的中心 (150,250)，不是录制的 (1,1)
        assertEquals(listOf(150 to 250), actor.taps)
        assertEquals("150,250", store.byId(skill.id)!!.steps[0].resolvedXy)
        assertEquals("1,1", store.byId(skill.id)!!.steps[0].xy)
    }

    // ─── 场景 5：退 resolvedXy / xy / tapByText ───────────────────────────

    @Test
    fun `tap falls back to resolvedXy before raw xy`() {
        val store = store()
        // 直接构造带 resolvedXy 的 skill（save 不设 resolvedXy）
        val skill = saveSkill(store, steps = listOf(step(text = null, xy = "7,7")))
        val withHealed = skill.copy(
            steps = listOf(skill.steps[0].copy(resolvedXy = "9,9")),
        )
        // 写回 store（replace via updateResolvedXy 之外的路径——直接比对内存副本即可）
        val actor = FakeActor()
        val r = SkillReplayEngine(actor, null).replay(withHealed)
        assertTrue(r.success)
        assertEquals(listOf(9 to 9), actor.taps)
    }

    @Test
    fun `tap without labels or coordinates fails the step`() {
        val store = store()
        val skill = saveSkill(store, steps = listOf(step(text = null, xy = null)))
        val actor = FakeActor()
        val r = SkillReplayEngine(actor, store).replay(store.byId(skill.id)!!)
        assertFalse(r.success)
        assertEquals(0, r.stepIndex)
        assertTrue(actor.taps.isEmpty() && actor.tapsByText.isEmpty())
    }

    @Test
    fun `tap with label falls back to tapByText when xy unresolvable`() {
        val store = store()
        val skill = saveSkill(store, steps = listOf(step(text = "确定", xy = null)))
        val actor = FakeActor()
        actor.dumps.add("some screen without the label\n")
        val r = SkillReplayEngine(actor, store).replay(store.byId(skill.id)!!)
        assertTrue(r.reason, r.success)
        assertEquals(listOf("确定"), actor.tapsByText)
        assertTrue(actor.taps.isEmpty())
    }

    // ─── 场景 6：超步数上限 ───────────────────────────────────────────────

    @Test
    fun `more than 40 steps is rejected upfront`() {
        val store = store()
        val tooMany = (0 until SkillReplayEngine.MAX_STEPS + 1).map { step(atMs = it.toLong()) }
        val skill = saveSkill(store, steps = tooMany)
        val actor = FakeActor()
        val r = SkillReplayEngine(actor, store).replay(store.byId(skill.id)!!)
        assertFalse(r.success)
        assertNull(r.stepIndex)
        assertTrue(r.reason!!.contains("too many steps"))
        assertTrue(actor.taps.isEmpty())
        assertEquals(0, store.byId(skill.id)!!.failStreak) // 前置拒绝不计失败
    }

    @Test
    fun `exactly 40 steps is allowed and waits clamp to 3000ms`() {
        val store = store()
        // 步间隔 10s → clamp 到 3000
        val steps = (0 until SkillReplayEngine.MAX_STEPS).map { step(atMs = it * 10_000L, text = null, xy = "1,1") }
        val skill = saveSkill(store, steps = steps)
        val actor = FakeActor()
        val r = SkillReplayEngine(actor, store).replay(store.byId(skill.id)!!)
        assertTrue(r.reason, r.success)
        assertEquals(SkillReplayEngine.MAX_STEPS, r.stepsExecuted)
        // 39 个间隔，全部钳到 MAX_STEP_GAP_MS
        assertEquals(SkillReplayEngine.MAX_STEPS - 1, actor.waits.size)
        assertTrue(actor.waits.all { it == SkillReplayEngine.MAX_STEP_GAP_MS })
    }

    @Test
    fun `tiny atms deltas clamp the wait up to 200ms`() {
        val store = store()
        val skill = saveSkill(store, steps = listOf(step(atMs = 0, xy = "1,1"), step(atMs = 5, xy = "2,2")))
        val actor = FakeActor()
        SkillReplayEngine(actor, store).replay(store.byId(skill.id)!!)
        assertEquals(listOf(SkillReplayEngine.MIN_STEP_GAP_MS), actor.waits) // 5 → 200
    }

    @Test
    fun `engine with null store never crashes on store writes`() {
        val skill = A11ySkillStore.Skill(
            id = "no-store", name = "n", instruction = "i",
            packageName = null, steps = listOf(step(text = null, xy = "3,3")),
            createdAt = 0L, lastUsedAt = 0L,
        )
        val r = SkillReplayEngine(FakeActor(), null).replay(skill)
        assertTrue(r.success) // store=null 时不写回，纯执行
    }
}
