package com.openminis.app.i18n

import com.openminis.app.i18n.LanguageSwitchController.LangPair
import com.openminis.app.i18n.LanguageSwitchController.SwitchResult
import com.openminis.app.i18n.LanguageSwitchController.SwitchState
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-lang-switch-txn] 切换事务纯逻辑测试：双槽语义（rollbackTo 只认已生效槽）、
 * 连切基准、下载失败/取消回滚、回滚失败落点态、串行下载队列。
 * 依赖全注入，无 Android。
 */
class LanguageSwitchControllerTest {

    private class Harness {
        var effective = LangPair("en", "zh")
        val ready = mutableSetOf(LangPair("en", "zh"))
        val downloads = mutableListOf<LangPair>()
        val aborts = mutableListOf<LangPair>()
        val cleared = mutableListOf<LangPair>()
        var failNextClear = false
        var downloadDelayMs = 30L
        var downloadFails = false

        val controller = LanguageSwitchController(
            readEffective = { effective },
            writeEffective = { effective = it },
            isPairReady = { it in ready },
            downloadPack = { pair ->
                downloads.add(pair)
                if (downloadDelayMs > 0) delay(downloadDelayMs)
                if (downloadFails) throw RuntimeException("net down")
                ready.add(pair)
            },
            abortDownload = { aborts.add(it) },
            clearPartial = { if (failNextClear) throw RuntimeException("delete failed") else cleared.add(it) },
        )

        suspend fun switch(src: String, tgt: String) =
            controller.requestSwitch(LangPair(src, tgt))
    }

    @Test
    fun `same pair is a no-op commit`() = runBlocking {
        val h = Harness()
        assertEquals(SwitchResult.COMMITTED, h.switch("en", "zh"))
        assertEquals(LangPair("en", "zh"), h.effective)
        assertTrue(h.downloads.isEmpty())
    }

    @Test
    fun `ready pair commits immediately without download`() = runBlocking {
        val h = Harness()
        h.ready.add(LangPair("en", "ja"))
        assertEquals(SwitchResult.COMMITTED, h.switch("en", "ja"))
        assertEquals(LangPair("en", "ja"), h.effective)
        assertTrue(h.downloads.isEmpty())
    }

    @Test
    fun `download success commits pair into effective slot`() = runBlocking {
        val h = Harness()
        assertEquals(SwitchResult.COMMITTED, h.switch("en", "ja"))
        assertEquals(LangPair("en", "ja"), h.effective)
        assertEquals(listOf(LangPair("en", "ja")), h.downloads)
    }

    @Test
    fun `download failure keeps effective pair untouched and clears partial`() = runBlocking {
        val h = Harness()
        h.downloadFails = true
        assertEquals(SwitchResult.FAILED, h.switch("en", "ja"))
        assertEquals(LangPair("en", "zh"), h.effective)
        assertEquals(listOf(LangPair("en", "ja")), h.cleared)
        assertEquals(SwitchState.Idle, h.controller.state.value)
    }

    @Test
    fun `rollback during download aborts and clears but keeps effective`() = runBlocking {
        val h = Harness()
        h.downloadDelayMs = 200L
        val job = launch { h.switch("en", "ja") }
        // 等进入 DOWNLOADING 相位。
        while (h.controller.state.value !is SwitchState.Pending) delay(5)
        assertEquals(SwitchResult.ROLLED_BACK, h.controller.rollback())
        assertEquals(listOf(LangPair("en", "ja")), h.aborts)
        assertEquals(listOf(LangPair("en", "ja")), h.cleared)
        assertEquals(LangPair("en", "zh"), h.effective)
        assertEquals(SwitchState.Idle, h.controller.state.value)
        job.cancel()
    }

    @Test
    fun `consecutive switches roll back to the effective pair not the previous pending`() = runBlocking {
        val h = Harness()
        h.downloadDelayMs = 50L
        // 连切：en→ja 下载中，直接切 en→ko。
        val job = async { h.switch("en", "ja") }
        while (h.controller.state.value !is SwitchState.Pending) delay(5)
        assertEquals(SwitchResult.COMMITTED, h.switch("en", "ko"))
        // 旧事务被回滚：abort ja + 清半成品 ja；基准仍是 zh（已生效槽），不是 ja。
        assertEquals(listOf(LangPair("en", "ja")), h.aborts)
        assertTrue(LangPair("en", "ja") in h.cleared)
        assertEquals(LangPair("en", "ko"), h.effective)
        // 被顶掉的旧事务：不写 prefs、不改状态，返回 FAILED。
        assertEquals(SwitchResult.FAILED, job.await())
    }

    @Test
    fun `rollback failure surfaces RollbackFailed state for UI`() = runBlocking {
        val h = Harness()
        h.downloadDelayMs = 200L
        h.failNextClear = true
        val job = launch { h.switch("en", "ja") }
        while (h.controller.state.value !is SwitchState.Pending) delay(5)
        assertEquals(SwitchResult.FAILED, h.controller.rollback())
        val failed = h.controller.state.value
        assertTrue(failed is SwitchState.RollbackFailed)
        failed as SwitchState.RollbackFailed
        assertEquals(LangPair("en", "zh"), failed.from)
        assertEquals(LangPair("en", "ja"), failed.to)
        assertEquals("delete failed", failed.message)
        h.controller.acknowledgeFailure()
        assertEquals(SwitchState.Idle, h.controller.state.value)
        job.cancel()
    }

    @Test
    fun `downloads run serially through the global queue`() = runBlocking {
        val h = Harness()
        h.downloadDelayMs = 40L
        val order = mutableListOf<Int>()
        val j1 = launch {
            h.controller.requestSwitch(LangPair("en", "ja"))
            order.add(1)
        }
        val j2 = launch {
            h.controller.requestSwitch(LangPair("en", "ko"))
            order.add(2)
        }
        j1.join(); j2.join()
        // 串行：两次下载都发生且不交错（downloads 各一次，完成顺序不定但都提交）。
        assertEquals(2, h.downloads.size)
        assertTrue(LangPair("en", "ja") in h.downloads)
        assertTrue(LangPair("en", "ko") in h.downloads)
        assertEquals(2, order.size)
    }
}
