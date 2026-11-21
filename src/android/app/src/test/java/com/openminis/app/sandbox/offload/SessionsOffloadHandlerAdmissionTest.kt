package com.openminis.app.sandbox.offload

import com.openminis.app.data.body.Admission
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.GoalDao
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.sandbox.NativeOffloadRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * `minis-sessions-cli search` 的 admission 拒绝路径。
 *
 * 中文：[Admission] 预算被占满时 [ChatRepository.searchMessages] 会抛
 * [com.openminis.app.data.SearchAdmissionUnavailable]——"暂时拒绝"必须与
 * "没有匹配"区分开，且错误体要走本工具统一的
 * `{ok:false, tool, action, data:{error, message}}` 形状：调用方按
 * `data.error` 分类失败，裸 `{error, reason}` 对象无法归类（与权限拒绝的
 * PERMISSION_DENIED envelope、INVALID_ARGS envelope 都对不上）。
 *
 * 路径自身不触碰 DAO（admission 在任何查询之前拒绝），用 Proxy 假 DAO 即可
 * 在纯 JVM 上钉住整条契约：exit code 1（运行期失败，非 2/参数错）+
 * TEMP_UNAVAILABLE 错误码。
 */
class SessionsOffloadHandlerAdmissionTest {

    /** [HistorySearchToolTest] 同款 Proxy 假 DAO；本路径任何 DAO 调用都说明拒绝点错了。 */
    private class FakeRepo {
        val repo = ChatRepository(
            fake<ChatDao> { method, _ -> error("unexpected DAO call before admission refusal: $method") },
            fake<GoalDao> { _, _ -> error("No goal calls expected") },
        )
    }

    private fun request(vararg args: String) = NativeOffloadRequest(
        pid = 4242,
        argv = listOf("minis-sessions-cli") + args,
        env = emptyMap(),
        cwd = "/",
        // 与 caller 相同的 --ids：自己的会话不算 foreign，绕开权限 gate，
        // 让请求落到 search 的 admission 分支。
        sessionId = "session-1",
    )

    @Test
    fun `admission refusal returns the unified error envelope with exit 1`() {
        val handler = SessionsOffloadHandler(FakeRepo().repo)
        // 排空全局 admission 预算：下一个 tryAdmit 必然拒绝。
        val drained = Admission.remaining()
        assertTrue(Admission.tryAdmit(drained))
        try {
            val result = handler.handle(request("search", "--keywords", "hello", "--ids", "session-1"))

            assertEquals("runtime refusal is exit 1, not 2 (args) or 126 (permission)", 1, result.exitCode)
            // envelope 之后跟着 HELP_TEXT：只取 JSON 段解析。
            val body = JSONObject(result.output.substringBefore("\nminis-sessions-cli").trim())
            assertFalse("the refusal must not look like success", body.getBoolean("ok"))
            assertEquals("minis-sessions-cli", body.getString("tool"))
            assertEquals("search", body.getString("action"))
            val data = body.getJSONObject("data")
            assertEquals("TEMP_UNAVAILABLE", data.getString("error"))
            assertTrue("the reason must survive into the message", data.getString("message").isNotBlank())
        } finally {
            // 归还预算，避免污染同一 JVM 里的其他测试。
            Admission.release(drained)
        }
    }

    private companion object {
        inline fun <reified T : Any> fake(crossinline answer: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                answer(method.name, args.orEmpty())
            } as T
    }
}
