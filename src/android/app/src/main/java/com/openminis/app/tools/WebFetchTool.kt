package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.network.guardedDohDns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
                return ToolExecutionResult("Error: $blocked", false, toolTitle = toolTitle)
            }
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) MinisUltra/web_fetch")
                .build()
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                FetchUrlGuard.blockedReason(finalUrl)?.let {
                    return ToolExecutionResult("Error after redirect: $it", false, toolTitle = toolTitle)
                }
                if (!resp.isSuccessful) {
                    return ToolExecutionResult("HTTP ${resp.code} for $url", false, toolTitle = toolTitle)
                }
                val raw = resp.body?.string().orEmpty()
                val text = htmlToText(raw).take(MAX_OUTPUT)
                ToolExecutionResult("URL: $finalUrl\n\n$text", true, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error fetching URL: ${e.message}", false, toolTitle = toolTitle)
        }
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
