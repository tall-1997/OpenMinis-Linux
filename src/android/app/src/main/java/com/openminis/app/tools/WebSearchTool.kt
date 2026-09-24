package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Key-free web search via DuckDuckGo HTML. Overlay already labeled this
 * tool; the agent schema never exposed it. Prefer this over spinning up
 * [browser_use] just to look up a fact.
 */
object WebSearchTool {
    const val NAME = "web_search"
    private const val MAX_RESULTS = 8
    private const val TIMEOUT_MS = 15_000

    data class Result(
        val title: String,
        val url: String,
        val snippet: String,
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the public web and return titles, URLs, and snippets. " +
            "Use this for facts, docs, news, and package versions instead of opening a browser. " +
            "Follow up with browser_use only when you need to interact with a specific page. " +
            "Uses Settings → Web search. First-class backends: Tavily, Bocha, Exa, Brave, Jina, Zhipu, Bing, SearXNG. " +
            "Falls back to DuckDuckGo, then suggest browser_use for a specific URL.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user (e.g. 'Search Android 15 release notes'). Use the same language as the user.",
            ),
            "query" to AgentToolParam("string", "Search query. Be specific; include version numbers or site: filters when useful."),
            "max_results" to AgentToolParam("integer", "How many results to return (default 5, max 8)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "max_results"),
    )

    fun execute(argsJson: String, context: Context? = null): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val query = args.optString("query", "").trim()
            val toolTitle = args.optString("tool_title", NAME)
            val max = args.optInt("max_results", 5).coerceIn(1, MAX_RESULTS)
            if (query.isEmpty()) {
                return ToolExecutionResult("query is required", success = false, toolTitle = toolTitle)
            }
            val preferred = context?.let { WebSearchSettings.engine(it) } ?: WebSearchSettings.Engine.DDG
            val allowFallback = context?.let { WebSearchSettings.fallbackEnabled(it) } ?: true
            val engines = mutableListOf(preferred)
            // A user who picked DuckDuckGo must not silently spend keyed quotas
            // when it returns nothing. Keyed fallback only runs after a keyed
            // engine was the one they asked for.
            if (allowFallback && context != null && preferred != WebSearchSettings.Engine.DDG) {
                for (keyed in WebSearchSettings.configuredKeyed(context)) {
                    if (keyed != preferred) engines += keyed
                }
                if (WebSearchSettings.Engine.DDG !in engines) engines += WebSearchSettings.Engine.DDG
            }
            var lastError: String? = null
            var used = preferred
            var results: List<Result> = emptyList()
            for (engine in engines) {
                used = engine
                val attempt = search(engine, query, max, context)
                if (attempt.results.isNotEmpty()) {
                    results = attempt.results
                    lastError = null
                    break
                }
                lastError = attempt.error
            }
            if (results.isEmpty()) {
                val fallback = searchNoKeyFallback(query, max, context)
                if (fallback.isNotEmpty()) {
                    return ToolExecutionResult(
                        format(query, fallback, "no-key-fallback"),
                        success = true,
                        toolTitle = toolTitle,
                    )
                }
                return ToolExecutionResult(
                    "web_search failed for \"$query\" via ${preferred.id}: ${lastError ?: "no results"}. " +
                        "DuckDuckGo HTML returned no cards, and the no-key Wikipedia/Bing/Mojeek fallback was also empty. " +
                        "Configure Settings → Web search, or open a known URL with browser_use.",
                    success = false,
                    toolTitle = toolTitle,
                )
            }
            ToolExecutionResult(format(query, results, used.id), success = true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("web_search failed: ${e.message}", success = false)
        }
    }

    private data class Attempt(val results: List<Result>, val error: String?)

    private fun search(
        engine: WebSearchSettings.Engine,
        query: String,
        max: Int,
        context: Context?,
    ): Attempt {
        return try {
            when (engine) {
                WebSearchSettings.Engine.DDG -> searchDuckDuckGo(query, max, context)
                WebSearchSettings.Engine.SEARXNG -> {
                    val base = context?.let { WebSearchSettings.searxngUrl(it) }.orEmpty().trimEnd('/')
                    if (base.isEmpty()) return Attempt(emptyList(), "SearXNG URL is not configured")
                    val endpoint = if (base.endsWith("/search")) base else "$base/search"
                    val body = fetchUrl("$endpoint?q=${enc(query)}&format=json", context = context)
                        ?: return Attempt(emptyList(), "empty response from SearXNG")
                    val parsed = parseSearxJson(body, max)
                    Attempt(parsed, if (parsed.isEmpty()) "SearXNG returned no results" else null)
                }
                WebSearchSettings.Engine.BING -> keyedGet(
                    engine, context,
                    "https://api.bing.microsoft.com/v7.0/search?q=${enc(query)}&count=$max",
                    headerName = "Ocp-Apim-Subscription-Key",
                    max = max,
                    parse = ::parseBingJson,
                )
                WebSearchSettings.Engine.TAVILY -> keyedPost(
                    engine, context,
                    url = "https://api.tavily.com/search",
                    body = JSONObject()
                        .put("query", query)
                        .put("max_results", max)
                        .put("search_depth", "basic"),
                    keyField = "api_key",
                    max = max,
                )
                WebSearchSettings.Engine.BOCHA -> keyedPost(
                    engine, context,
                    url = "https://api.bochaai.com/v1/web-search",
                    body = JSONObject().put("query", query).put("count", max).put("summary", true),
                    bearer = true,
                    max = max,
                )
                WebSearchSettings.Engine.EXA -> keyedPost(
                    engine, context,
                    url = "https://api.exa.ai/search",
                    body = JSONObject()
                        .put("query", query)
                        .put("numResults", max)
                        .put("contents", JSONObject().put("text", JSONObject().put("maxCharacters", 400))),
                    headerName = "x-api-key",
                    max = max,
                )
                WebSearchSettings.Engine.BRAVE -> keyedGet(
                    engine, context,
                    "https://api.search.brave.com/res/v1/web/search?q=${enc(query)}&count=$max",
                    headerName = "X-Subscription-Token",
                    max = max,
                    parse = { json, n -> parseGenericSearchJson(json, n) },
                )
                WebSearchSettings.Engine.JINA -> keyedGet(
                    engine, context,
                    "https://s.jina.ai/${enc(query)}",
                    headerName = "Authorization",
                    bearer = true,
                    extra = mapOf("Accept" to "application/json"),
                    max = max,
                    parse = { json, n -> parseGenericSearchJson(json, n) },
                )
                WebSearchSettings.Engine.ZHIPU -> keyedPost(
                    engine, context,
                    url = "https://open.bigmodel.cn/api/paas/v4/web_search",
                    body = JSONObject().put("search_query", query).put("count", max),
                    bearer = true,
                    max = max,
                )
                WebSearchSettings.Engine.CUSTOM -> {
                    val template = context?.let { WebSearchSettings.customUrl(it) }.orEmpty()
                    if (template.isEmpty()) return Attempt(emptyList(), "Custom search URL is not configured")
                    val key = context?.let { WebSearchSettings.customKey(it) }.orEmpty()
                    val headerName = context?.let { WebSearchSettings.customKeyHeader(it) }.orEmpty()
                    val endpoint = expandCustomUrl(template, query, key)
                    val headers = linkedMapOf("Accept" to "application/json, text/html")
                    if (key.isNotEmpty() && !template.contains("{key}", ignoreCase = true)) {
                        val h = headerName.ifBlank { "Authorization" }
                        val v = if (h.equals("Authorization", ignoreCase = true) &&
                            !key.startsWith("Bearer ", ignoreCase = true)
                        ) {
                            "Bearer $key"
                        } else {
                            key
                        }
                        headers[h] = v
                    }
                    val body = fetchUrl(endpoint, extraHeaders = headers, context = context)
                        ?: return Attempt(emptyList(), "empty response from custom search")
                    val trimmed = body.trimStart()
                    val parsed = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                        parseGenericSearchJson(body, max)
                    } else {
                        parseHtml(body, max)
                    }
                    Attempt(parsed, if (parsed.isEmpty()) "Custom search returned no results" else null)
                }
            }
        } catch (e: Exception) {
            Attempt(emptyList(), e.message ?: engine.id)
        }
    }

    private fun keyedGet(
        engine: WebSearchSettings.Engine,
        context: Context?,
        url: String,
        headerName: String,
        max: Int,
        parse: (String, Int) -> List<Result>,
        bearer: Boolean = false,
        extra: Map<String, String> = emptyMap(),
    ): Attempt {
        val key = context?.let { WebSearchSettings.apiKey(it, engine) }.orEmpty()
        if (key.isEmpty()) return Attempt(emptyList(), "${engine.id} API key is not configured")
        val headers = linkedMapOf("Accept" to "application/json")
        headers.putAll(extra)
        headers[headerName] = if (bearer && !key.startsWith("Bearer ", ignoreCase = true)) "Bearer $key" else key
        val body = fetchUrl(url, extraHeaders = headers, context = context)
            ?: return Attempt(emptyList(), "empty response from ${engine.id}")
        val parsed = parse(body, max)
        return Attempt(parsed, if (parsed.isEmpty()) "${engine.id} returned no results" else null)
    }

    private fun keyedPost(
        engine: WebSearchSettings.Engine,
        context: Context?,
        url: String,
        body: JSONObject,
        max: Int,
        keyField: String? = null,
        headerName: String? = null,
        bearer: Boolean = false,
    ): Attempt {
        val key = context?.let { WebSearchSettings.apiKey(it, engine) }.orEmpty()
        if (key.isEmpty()) return Attempt(emptyList(), "${engine.id} API key is not configured")
        if (keyField != null) body.put(keyField, key)
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        )
        if (headerName != null) headers[headerName] = key
        if (bearer) headers["Authorization"] = if (key.startsWith("Bearer ", ignoreCase = true)) key else "Bearer $key"
        val raw = postJson(url, body.toString(), headers, context)
            ?: return Attempt(emptyList(), "empty response from ${engine.id}")
        val parsed = parseGenericSearchJson(raw, max)
        return Attempt(parsed, if (parsed.isEmpty()) "${engine.id} returned no results" else null)
    }

    internal fun parseSearxJson(json: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val root = JSONObject(json)
        val arr = root.optJSONArray("results") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            val title = o.optString("title").trim()
            if (url.isBlank() || title.isBlank()) continue
            out += Result(title, url, o.optString("content").trim())
            if (out.size >= max) break
        }
        return out
    }

    internal fun parseBingJson(json: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val pages = JSONObject(json).optJSONObject("webPages") ?: return out
        val arr = pages.optJSONArray("value") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            val title = o.optString("name").trim()
            if (url.isBlank() || title.isBlank()) continue
            out += Result(title, url, o.optString("snippet").trim())
            if (out.size >= max) break
        }
        return out
    }

    internal fun expandCustomUrl(template: String, query: String, key: String = ""): String {
        var url = template.trim()
        val q = enc(query)
        val k = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
        url = url.replace("{query}", q, ignoreCase = true)
            .replace("{q}", q, ignoreCase = true)
            .replace("{key}", k, ignoreCase = true)
        if (!template.contains("{query}", ignoreCase = true) &&
            !template.contains("{q}", ignoreCase = true)
        ) {
            val sep = if (url.contains('?')) "&" else "?"
            url = "$url${sep}q=$q"
        }
        return url
    }

    internal fun parseGenericSearchJson(json: String, max: Int): List<Result> {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            return parseResultArray(JSONArray(trimmed), max)
        }
        val fromSearx = parseSearxJson(json, max)
        if (fromSearx.isNotEmpty()) return fromSearx
        val fromBing = parseBingJson(json, max)
        if (fromBing.isNotEmpty()) return fromBing
        return findResultArray(JSONObject(json), max, depth = 0).orEmpty()
    }

    private val RESULT_KEYS = arrayOf(
        "results", "items", "data", "organic", "organic_results",
        "webPages", "web", "search_result", "value",
    )

    /** Bocha nests hits at data.webPages.value; one flat pass misses that. */
    private fun findResultArray(node: JSONObject, max: Int, depth: Int): List<Result>? {
        if (depth > 3) return null
        for (key in RESULT_KEYS) {
            node.optJSONArray(key)?.let { arr ->
                val parsed = parseResultArray(arr, max)
                if (parsed.isNotEmpty()) return parsed
            }
        }
        if (depth == 3) return null
        for (key in RESULT_KEYS) {
            val child = node.optJSONObject(key) ?: continue
            findResultArray(child, max, depth + 1)?.let { return it }
        }
        return null
    }

    private fun parseResultArray(arr: JSONArray, max: Int): List<Result> {
        val out = ArrayList<Result>(max)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").ifBlank { o.optString("link") }
                .ifBlank { o.optString("href") }
                .ifBlank { o.optString("displayUrl") }.trim()
            val title = o.optString("title").ifBlank { o.optString("name") }.trim()
            if (url.isBlank() || title.isBlank()) continue
            val snippet = o.optString("snippet").ifBlank { o.optString("content") }
                .ifBlank { o.optString("description") }
                .ifBlank { o.optString("summary") }
                .ifBlank { o.optString("text") }.trim()
            out += Result(title, url, snippet)
            if (out.size >= max) break
        }
        return out
    }

    private fun searchDuckDuckGo(query: String, max: Int, context: Context?): Attempt {
        val urls = listOf(
            "https://html.duckduckgo.com/html/?q=${enc(query)}",
            "https://lite.duckduckgo.com/lite/?q=${enc(query)}",
        )
        var last = "DuckDuckGo returned no cards"
        var sawBody = false
        for (url in urls) {
            val html = fetchUrl(url, context = context) ?: continue
            sawBody = true
            if (html.contains("anomaly.js") || html.contains("Unfortunately, bots use DuckDuckGo")) {
                last = "DuckDuckGo blocked this client. Set a backend in Settings → Web search."
                continue
            }
            val parsed = parseHtml(html, max)
            if (parsed.isNotEmpty()) return Attempt(parsed, null)
        }
        if (!sawBody) last = "empty response from DuckDuckGo"
        return Attempt(emptyList(), last)
    }

    internal fun parseHtml(html: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val seen = HashSet<String>()
        val resultBlock = Regex(
            """class="result(?:__body)?"[\s\S]{0,2500}?class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>[\s\S]{0,1200}?class="result__snippet"[^>]*>([\s\S]*?)</(?:a|td|div)>""",
            RegexOption.IGNORE_CASE,
        )
        for (m in resultBlock.findAll(html)) {
            val url = decodeDuckLink(htmlUnescape(m.groupValues[1]))
            val title = stripTags(m.groupValues[2])
            val snippet = stripTags(m.groupValues[3])
            if (url.isBlank() || title.isBlank()) continue
            if (!seen.add(url)) continue
            out += Result(title, url, snippet)
            if (out.size >= max) return out
        }
        if (out.isNotEmpty()) return out
        val lite = Regex(
            """<a[^>]+rel="nofollow"[^>]+href="(https?://[^"]+)"[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE,
        )
        for (m in lite.findAll(html)) {
            val url = htmlUnescape(m.groupValues[1])
            if (url.contains("duckduckgo.com", ignoreCase = true)) continue
            val title = stripTags(m.groupValues[2])
            if (url.isBlank() || title.isBlank()) continue
            if (!seen.add(url)) continue
            out += Result(title, url, "")
            if (out.size >= max) break
        }
        return out
    }

    internal fun decodeDuckLink(raw: String): String {
        val href = htmlUnescape(raw).trim()
        val normalized = when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/l/?") -> "https://duckduckgo.com$href"
            else -> href
        }
        val marker = "uddg="
        val idx = normalized.indexOf(marker)
        if (idx >= 0) {
            val start = idx + marker.length
            val end = normalized.indexOf('&', start).let { if (it < 0) normalized.length else it }
            val encoded = normalized.substring(start, end)
            if (encoded.isNotBlank()) {
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
            }
        }
        return normalized
    }

    private fun format(query: String, results: List<Result>, engine: String = "ddg"): String = buildString {
        appendLine("web_search ($engine) results for \"$query\" (${results.size}):")
        results.forEachIndexed { i, r ->
            appendLine()
            appendLine("${i + 1}. ${r.title}")
            appendLine("   ${r.url}")
            if (r.snippet.isNotBlank()) appendLine("   ${r.snippet}")
        }
    }

    private fun enc(query: String): String =
        URLEncoder.encode(query, StandardCharsets.UTF_8.name())

    /**
     * DuckDuckGo HTML often returns a page with no result cards. Wikipedia
     * OpenSearch and public HTML engines need no API key, so they run only
     * after every configured engine returned empty.
     */
    private fun searchNoKeyFallback(query: String, max: Int, context: Context?): List<Result> {
        val out = mutableListOf<Result>()
        out += searchWikipedia(query, max, context)
        if (out.size < max) {
            out += searchHtmlLinks(
                "https://www.bing.com/search?q=${enc(query)}&setlang=zh-Hans",
                max - out.size,
                context,
                Regex("""<h2>\s*<a[^>]+href="(https?://[^"]+)"[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE),
            )
        }
        if (out.size < max) {
            out += searchHtmlLinks(
                "https://www.mojeek.com/search?q=${enc(query)}",
                max - out.size,
                context,
                Regex("""<a[^>]+class="[^"]*title[^"]*"[^>]+href="(https?://[^"]+)"[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE),
            )
        }
        return out.distinctBy { it.url }.take(max)
    }

    private fun searchWikipedia(query: String, max: Int, context: Context?): List<Result> {
        val host = if (query.any { it.code > 127 }) "zh.wikipedia.org" else "en.wikipedia.org"
        val url = "https://$host/w/api.php?action=opensearch&search=${enc(query)}&limit=$max&namespace=0&format=json"
        val body = fetchUrl(url, mapOf("Accept" to "application/json"), context) ?: return emptyList()
        return try {
            val arr = JSONArray(body)
            val titles = arr.optJSONArray(1) ?: return emptyList()
            val snippets = arr.optJSONArray(2) ?: JSONArray()
            val urls = arr.optJSONArray(3) ?: JSONArray()
            buildList {
                for (i in 0 until titles.length().coerceAtMost(max)) {
                    val link = urls.optString(i)
                    if (!link.startsWith("http")) continue
                    add(Result(titles.optString(i), link, snippets.optString(i)))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun searchHtmlLinks(
        url: String,
        max: Int,
        context: Context?,
        pattern: Regex,
    ): List<Result> {
        if (max <= 0) return emptyList()
        val html = fetchUrl(url, context = context) ?: return emptyList()
        val out = mutableListOf<Result>()
        for (match in pattern.findAll(html)) {
            val link = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (!link.startsWith("http") || link.contains("bing.com/ck/") || link.contains("microsoft.com")) continue
            val title = match.groupValues.getOrNull(2).orEmpty()
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .trim()
            if (title.isEmpty()) continue
            out += Result(title, link, "")
            if (out.size >= max) break
        }
        return out
    }

    private fun httpUserAgent(context: Context?): String {
        val major = context?.let {
            runCatching { com.openminis.app.browser.WebViewEngine.snapshot(it).major }.getOrNull()
        }
        val chrome = major?.let { "Chrome/$it.0.0.0" }
            ?: "Chrome/${com.openminis.app.browser.WebViewEngine.TARGET_MAJOR}.0.0.0"
        return "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; OpenMinis-Linux) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) $chrome Mobile Safari/537.36"
    }

    private fun fetchUrl(
        urlString: String,
        extraHeaders: Map<String, String> = emptyMap(),
        context: Context? = null,
    ): String? {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", httpUserAgent(context))
            setRequestProperty("Accept", extraHeaders["Accept"] ?: "text/html,application/xhtml+xml,application/json")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.use { inp ->
                BufferedReader(InputStreamReader(inp, StandardCharsets.UTF_8)).readText()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(
        urlString: String,
        json: String,
        extraHeaders: Map<String, String>,
        context: Context?,
    ): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", httpUserAgent(context))
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.use { inp ->
                BufferedReader(InputStreamReader(inp, StandardCharsets.UTF_8)).readText()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun stripTags(raw: String): String =
        htmlUnescape(raw.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun htmlUnescape(raw: String): String =
        raw.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
}
