package com.openminis.app.agent.lifecycle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GH#131] In-process listener delivery is the part of the hook system every
 * future extension point (notifications, stats, skill hooks) will sit on, so it
 * gets a contract test independent of the sandbox: registration, payload,
 * unregistration, and the "a listener can never break the caller" promise.
 *
 * 中文：这是 #131 的最小回归面——监听器必须收到完整事件（事件 / 会话 id /
 * detail），注销后不再收到；某个监听器抛异常不能影响其它监听器或调用方
 * （fire 文档承诺“永不抛异常、永不阻塞”）。
 */
class LifecycleHooksTest {

    private val registered = mutableListOf<LifecycleHooks.LifecycleListener>()

    private fun register(listener: LifecycleHooks.LifecycleListener) {
        LifecycleHooks.registerListener(listener)
        registered += listener
    }

    @After
    fun tearDown() {
        registered.forEach { LifecycleHooks.unregisterListener(it) }
    }

    @Test
    fun `every registered listener receives the event payload`() {
        val first = HookRecorder()
        val second = HookRecorder()
        register(first)
        register(second)

        LifecycleHooks.fire(
            LifecycleHooks.LifecycleEvent.SESSION_START,
            "session-1",
            "My chat",
        )

        val expected = listOf(HookEvent(LifecycleHooks.LifecycleEvent.SESSION_START, "session-1", "My chat"))
        assertEquals(expected, first.events)
        assertEquals(expected, second.events)
    }

    @Test
    fun `unregistered listeners stop receiving events`() {
        val recorder = HookRecorder()
        LifecycleHooks.registerListener(recorder)
        LifecycleHooks.fire(LifecycleHooks.LifecycleEvent.SESSION_END, "gone", "deleted")
        LifecycleHooks.unregisterListener(recorder)

        LifecycleHooks.fire(LifecycleHooks.LifecycleEvent.SESSION_END, "later", "deleted")

        assertEquals(
            listOf(HookEvent(LifecycleHooks.LifecycleEvent.SESSION_END, "gone", "deleted")),
            recorder.events,
        )
    }

    @Test
    fun `a throwing listener cannot break delivery to the others or the caller`() {
        val bad = object : LifecycleHooks.LifecycleListener {
            override fun onEvent(
                event: LifecycleHooks.LifecycleEvent,
                sessionId: String,
                detail: String,
            ) {
                throw IllegalStateException("listener bug")
            }
        }
        val good = HookRecorder()
        register(bad)
        register(good)

        // Must not throw: agent turns call fire() inline.
        LifecycleHooks.fire(LifecycleHooks.LifecycleEvent.RESPONSE, "session-2", "tail")

        assertEquals(
            listOf(HookEvent(LifecycleHooks.LifecycleEvent.RESPONSE, "session-2", "tail")),
            good.events,
        )
    }

    @Test
    fun `event wire names parse case-insensitively`() {
        assertEquals(
            LifecycleHooks.LifecycleEvent.SESSION_START,
            LifecycleHooks.LifecycleEvent.fromWire("SESSION_START"),
        )
        assertEquals(
            LifecycleHooks.LifecycleEvent.RESPONSE,
            LifecycleHooks.LifecycleEvent.fromWire("response"),
        )
        assertNull(LifecycleHooks.LifecycleEvent.fromWire("nope"))
    }
}

/** [GH#131] One delivered event, compared by value. */
private data class HookEvent(
    val event: LifecycleHooks.LifecycleEvent,
    val sessionId: String,
    val detail: String,
)

/** [GH#131] Records every delivery so assertions can compare the full payload. */
private class HookRecorder : LifecycleHooks.LifecycleListener {
    val events = mutableListOf<HookEvent>()

    override fun onEvent(
        event: LifecycleHooks.LifecycleEvent,
        sessionId: String,
        detail: String,
    ) {
        events += HookEvent(event, sessionId, detail)
    }
}
