package com.openminis.app.sandbox.offload

import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.NativeOffloadRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#539] `android-su` deny-path contract.
 *
 * 中文：这条分支是 Agent 唯一能看到的“为什么没执行”的解释，过去既没有
 * 单测也没有注入点，只能靠真机 root/Shizuku 试出来。这里用构造函数注入
 * 假 gate / 假注册表 / 假 su 探测，把错误体
 * `{"ok":false,"error":{"code":"PERMISSION_DENIED",…}}` 与 exit 126 的语义
 * 钉在 JVM 上——它们是 shell 调用方（以及模型）实际读取的契约。
 *
 * 为什么 exit 126：与 OffloadGate.enforce 的 PERMISSION_DENIED、
 * GuestWorkloadPolicy 的 hostRefusal 保持一致，Agent 可以据此区分
 * “策略拒绝”与“命令执行失败”（1/2/127）。
 */
class SuOffloadHandlerDenyTest {

    private fun request(vararg args: String) = NativeOffloadRequest(
        pid = 4242,
        argv = listOf("android-su") + args,
        env = emptyMap(),
        cwd = "/",
        sessionId = "deny-test-session",
    )

    @Test
    fun `agent gate denial returns the ok-false envelope with exit 126`() {
        var gateCalls = 0
        val handler = SuOffloadHandler(
            isRegistered = { true },
            permissionGate = { tool, display, _ ->
                gateCalls++
                assertEquals("su_cli", tool)
                assertEquals("android-su", display)
                false
            },
            findSu = { null },
            currentShizukuState = { ShizukuManager.State.NOT_INSTALLED },
        )

        val result = handler.handle(request("-c", "id"))

        assertEquals("policy denials must not look like a command failure", 126, result.exitCode)
        assertEquals(1, gateCalls)
        val body = JSONObject(result.output.trim())
        assertFalse("the envelope must say the call did not run", body.getBoolean("ok"))
        val error = body.getJSONObject("error")
        assertEquals("PERMISSION_DENIED", error.getString("code"))
        val message = error.getString("message")
        // [GH#539] The message must name the actual refusing layer. With no su
        // binary and no Shizuku installed, "not installed" is the actionable
        // half; the agent gate is the other half, so both are referenced.
        assertTrue("must name the missing backend, got: $message", message.contains("not installed"))
        assertTrue("must name the agent gate, got: $message", message.contains("Agent permission gate"))
    }

    @Test
    fun `an unregistered su_cli fails closed without consulting the gate`() {
        var gateCalls = 0
        val handler = SuOffloadHandler(
            isRegistered = { false },
            permissionGate = { _, _, _ ->
                gateCalls++
                true
            },
            findSu = { null },
            currentShizukuState = { ShizukuManager.State.NOT_RUNNING },
        )

        val result = handler.handle(request("exec", "id"))

        assertEquals(126, result.exitCode)
        assertEquals("registry mismatch must fail before the gate", 0, gateCalls)
        val body = JSONObject(result.output.trim())
        assertFalse(body.getBoolean("ok"))
        val error = body.getJSONObject("error")
        assertEquals("PERMISSION_DENIED", error.getString("code"))
        // The injected registry is the handler-level seam; OffloadDenial keeps
        // its own (real) registry lookup, so this assertion pins the structural
        // contract (fail closed, same envelope) rather than that copy string.
        assertTrue(error.getString("message").isNotBlank())
    }

    @Test
    fun `help and version never touch the permission gate`() {
        var gateCalls = 0
        var registeredCalls = 0
        val handler = SuOffloadHandler(
            isRegistered = {
                registeredCalls++
                false
            },
            permissionGate = { _, _, _ ->
                gateCalls++
                false
            },
            findSu = { null },
            currentShizukuState = { ShizukuManager.State.NOT_INSTALLED },
        )

        assertEquals(0, handler.handle(request("--help")).exitCode)
        assertEquals(0, handler.handle(request("--version")).exitCode)
        assertEquals(0, gateCalls)
        assertEquals(0, registeredCalls)
    }
}
