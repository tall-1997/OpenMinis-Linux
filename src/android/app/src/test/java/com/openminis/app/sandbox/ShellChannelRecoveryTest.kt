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
 * 不是工具超时，必须保留健康通道。
 *
 * 第三轮复审：[#358] 首行标记 `[Command timed out after …]` 是命令输出，用户
 * 命令 echo 它就会伪造超时。生产路径（ExecutionCoordinator）手里始终有
 * consumeTimedOut() 的权威标志，所以标记匹配现在只是显式开启的测试 seam，
 * 默认关闭。
 */
class ShellChannelRecoveryTest {

    @Test
    fun realTimeoutFlagDiscardsTheChannel() {
        // [GH#358] The shell's own watchdog flag — the authoritative signal.
        assertTrue(ShellChannelRecovery.shouldDiscardChannel(124, "", timedOut = true))
        // The flag wins regardless of the output text or the reported exit code.
        assertTrue(ShellChannelRecovery.shouldDiscardChannel(0, "ok", timedOut = true))
    }

    @Test
    fun echoingTheTimeoutMarkerKeepsTheChannel() {
        // [GH#358] The residual hole from round two: the timeout branch's marker
        // is ordinary command-visible text. A user command echoing it must NOT
        // cost a healthy shell (env/cwd survive), so production never reads it.
        assertFalse(
            ShellChannelRecovery.shouldDiscardChannel(
                124,
                "[Command timed out after 180s; guest process group killed]",
            ),
        )
        assertFalse(
            ShellChannelRecovery.shouldDiscardChannel(0, "[Command timed out after 5s]"),
        )
    }

    @Test
    fun markerFallbackOnlyExistsBehindTheExplicitTestSeam() {
        val marker = "[Command timed out after 180s; guest process group killed]"
        // Default is off: this is what every production call gets.
        assertFalse(ShellChannelRecovery.shouldDiscardChannel(124, marker))
        // The seam still parses the documented first line, so the marker's shape
        // stays pinned; it just cannot decide a real channel's fate.
        assertTrue(
            ShellChannelRecovery.shouldDiscardChannel(
                124,
                marker,
                allowMarkerFallback = true,
            ),
        )
        assertFalse(
            ShellChannelRecovery.shouldDiscardChannel(
                0,
                "the script printed: $marker",
                allowMarkerFallback = true,
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
