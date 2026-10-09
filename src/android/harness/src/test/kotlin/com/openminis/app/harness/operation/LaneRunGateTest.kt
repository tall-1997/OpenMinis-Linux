package com.openminis.app.harness.operation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-p1-6-finish-begin-race] LaneRunGate 的锁序验收："finish 已登记、尚未拿锁"
 * 的窗口内，withLaneMutex 必须让位等待而不是直接进临界区（生产侧该窗口由
 * withFinishMutex 在登记与拿锁之间打开；测试直接向表内注入同款信号）。
 */
class LaneRunGateTest {

    @Test
    fun `withLaneMutex yields to a registered pending finish before locking`() = runBlocking {
        val gate = LaneRunGate()
        val pending = CompletableDeferred<Unit>()
        gate.pendingLaneFinishes["s" to "main"] = pending

        val entered = async { gate.withLaneMutex("s", "main") { true } }
        yield()
        assertTrue("登记中的收尾未完成前，入口不得进临界区", entered.isActive)

        pending.complete(Unit)
        assertTrue(entered.await())
    }
}
