package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.network.guardedDohDns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Fetch a public URL to text. SSRF via [FetchUrlGuard].
 *
 * Adapted from XINCODE-Public WebFetchTool (GPL-3.0-or-later).
 */
object WebFetchTool {
    const val NAME = "web_fetch"
    private const val MAX_OUTPUT = 8_000

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // [T-doh-resolver-fallback] Composed, NOT replaced: the SSRF guard must keep
            // veto power over every address OkHttp would dial (incl. redirects),
            // while still getting the DoH fallback when system DNS is dead.
            .dns(guardedDohDns { !FetchUrlGuard.isUnsafeAddress(it) })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Fetch a public http(s) URL and return extracted text. Use web_search first to find URLs. Private/loopback hosts are blocked.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "url" to AgentToolParam("string", "http or https URL."),
        ),
        required = listOf("tool_title", "url"),
        propertyOrdering = listOf("tool_title", "url"),
    )

    fun execute(argsJson: String): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val url = JSONObject(argsJson).optString("url", "").trim()
            val blocked = FetchUrlGuard.blockedReason(url)
            if (blocked != null) {
                // [T-retry-error-codes] P1-7: 失败路径补机器可读错误码——此前全部
                // errorCode=null，ToolRetry.isTransient（只认 NETWORK_ERROR/TIMEOUT）
                // 从未触发，统一重试层是死代码。SSRF 拒绝不是瞬态，标 PERMISSION_DENIED
                // 弹回模型换 URL。
                return ToolExecutionResult(
                    "Error: $blocked", false,
                    errorCode = ToolErrorCode.PERMISSION_DENIED,
                    toolTitle = toolTitle,
                )
            }
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) MinisUltra/web_fetch")
                .build()
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                FetchUrlGuard.blockedReason(finalUrl)?.let {
                    return ToolExecutionResult(
                        "Error after redirect: $it", false,
                        errorCode = ToolErrorCode.PERMISSION_DENIED,
                        toolTitle = toolTitle,
                    )
                }
                if (!resp.isSuccessful) {
                    // [T-retry-error-codes] 5xx/408/429 是瞬态（过载/限流/请求超时），
                    // 交给 ToolRetry 退避重试；其余 4xx 是确定性答复，重试也是同一个错。
                    val transient = resp.code == 408 || resp.code == 429 || resp.code in 500..599
                    return ToolExecutionResult(
                        "HTTP ${resp.code} for $url", false,
                        errorCode = if (transient) ToolErrorCode.NETWORK_ERROR else ToolErrorCode.EXECUTION_FAILED,
                        toolTitle = toolTitle,
                    )
                }
                val raw = resp.body?.string().orEmpty()
                val text = htmlToText(raw).take(MAX_OUTPUT)
                ToolExecutionResult("URL: $finalUrl\n\n$text", true, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                "Error fetching URL: ${e.message}", false,
                errorCode = errorCodeFor(e),
                toolTitle = toolTitle,
            )
        }
    }

    /**
     * [T-retry-error-codes] P1-7: 异常 → 错误码分类。超时/IO 是瞬态（退避重试
     * 有意义）；畸形 URL / 烂 argsJson 是参数错；其余按执行失败弹回模型自查。
     */
    internal fun errorCodeFor(cause: Exception): ToolErrorCode = when (cause) {
        is SocketTimeoutException, is TimeoutException -> ToolErrorCode.TIMEOUT
        is IOException -> ToolErrorCode.NETWORK_ERROR
        is JSONException, is IllegalArgumentException -> ToolErrorCode.INVALID_ARGUMENTS
        else -> ToolErrorCode.EXECUTION_FAILED
    }

    internal fun htmlToText(raw: String): String {
        var s = raw
        s = Regex("(?is)<script[^>]*>.*?</script>").replace(s, " ")
        s = Regex("(?is)<style[^>]*>.*?</style>").replace(s, " ")
        s = Regex("(?is)<[^>]+>").replace(s, " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"")
        return s.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}
