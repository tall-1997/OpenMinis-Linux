package com.openminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#358] A timed-out command must not leave the session's shell channel in
 * the per-session registry: the next command has to build a fresh one instead
 * of reusing a shell whose pending callback / stdin writer may still be wedged.
 *
 * [GH#358] 判定不能裸看 exit 124：用户命令自己退出 124（`timeout 5 sleep 10`）
 * 不是工具超时，必须保留健康通道。真正的超时只认内部标志或超时输出的首行。
 */
class ShellChannelRecoveryTest {

    @Test
    fun timeoutVerdictDiscardsTheChannel() {
        // The shell's own watchdog flag — the authoritative signal.
        assertTrue(ShellChannelRecovery.shouldDiscardChannel(124, "", timedOut = true))
        // Fallback for a caller that only has the result text: the timeout
        // branch returns exactly this prefix.
        assertTrue(
            ShellChannelRecovery.shouldDiscardChannel(
                124,
                "[Command timed out after 180s; guest process group killed]",
            ),
        )
    }

    @Test
    fun userCommandExit124KeepsTheChannel() {
        // [GH#358] The reported false positive: `timeout 5 sleep 10` legitimately
        // returns 124. It is not a wedged shell and must not be discarded.
        assertFalse(ShellChannelRecovery.shouldDiscardChannel(124, "user timeout output"))
        assertFalse(ShellChannelRecovery.shouldDiscardChannel(124, ""))
        // A command whose OUTPUT merely mentions the marker mid-text is not the
        // timeout branch either (the branch writes the prefix at the start).
        assertFalse(
            ShellChannelRecovery.shouldDiscardChannel(
                0,
                "the script printed: [Command timed out after 5s]",
            ),
        )
    }

    @Test
    fun ordinaryFailuresKeepTheChannel() {
        // 126 = refused by policy, 127 = command not found, 1 = normal failure:
        // all of these are valid shells that must be reused (env/cwd survive).
        assertFalse(ShellChannelRecovery.shouldDiscardChannel(126, "refused"))
        assertFalse(ShellChannelRecovery.shouldDiscardChannel(127, "not found"))
        assertFalse(ShellChannelRecovery.shouldDiscardChannel(1, "boom"))
        assertFalse(ShellChannelRecovery.shouldDiscardChannel(0, "ok"))
    }

    @Test
    fun resetFieldListNamesEveryStuckStateField() {
        val fields = ShellChannelRecovery.RESET_FIELDS
        for (name in listOf(
            "pendingCallback", "stdinWriter", "process", "isStarting",
            "sourcedOffloadSignature", "lastExitCode",
        )) {
            assertTrue("reset list must name $name", fields.contains(name))
        }
    }
}
