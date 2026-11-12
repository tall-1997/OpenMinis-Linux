package com.openminis.app.agent.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [GH#131] The sandbox-script half of the hook system, without a PRoot process.
 *
 * 中文：listener 测试只能证明进程内投递；真正执行用户脚本的那条分支还需要
 * 证明三件事——事件/会话/detail 被正确转义成环境变量、`TIMEOUT_MS` 原样传给
 * 执行器、命令本身在脚本末尾执行。这里用 [LifecycleHooks.scriptRunnerForTest]
 * 注入 fake runner，不启动沙箱也能把投递契约钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LifecycleHooksScriptTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LifecycleHooks.prime(context)
        LifecycleHooks.setEnabled(context, false)
        LifecycleHooks.setCommand(context, LifecycleHooks.LifecycleEvent.RESPONSE, "")
    }

    @After
    fun tearDown() {
        LifecycleHooks.scriptRunnerForTest = null
        if (::context.isInitialized) {
            LifecycleHooks.setCommand(context, LifecycleHooks.LifecycleEvent.RESPONSE, "")
            LifecycleHooks.setEnabled(context, false)
        }
    }

    @Test
    fun `an enabled hook dispatches the quoted script once for the event`() = runBlocking {
        LifecycleHooks.setEnabled(context, true)
        LifecycleHooks.setCommand(
            context,
            LifecycleHooks.LifecycleEvent.RESPONSE,
            "echo hook",
        )
        val dispatched = CompletableDeferred<Pair<String, Long>>()
        LifecycleHooks.scriptRunnerForTest = { script, timeoutMs ->
            dispatched.complete(script to timeoutMs)
            0 to "ok"
        }

        LifecycleHooks.fire(
            LifecycleHooks.LifecycleEvent.RESPONSE,
            "session-42",
            "it's done",
        )
        val (script, timeoutMs) = withTimeout(5_000) { dispatched.await() }

        assertEquals("the hook budget must reach the runner unchanged", 20_000L, timeoutMs)
        assertEquals(
            "export MINIS_HOOK_EVENT='response'\n" +
                "export MINIS_HOOK_SESSION_ID='session-42'\n" +
                "export MINIS_HOOK_DETAIL='it'\\''s done'\n" +
                "echo hook",
            script,
        )
    }
}
