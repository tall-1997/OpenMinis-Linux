package com.openminis.app.provider

import android.content.Context
import org.json.JSONObject

/**
 * [GH#221] 每个 provider 实例的自定义请求头（KV）。
 *
 * ## 为什么单独存，而不是加到 ProviderInstance
 *
 * provider 配置的 Room 表（ProviderDatabase）迁移由 b3 独占，本批禁止改
 * schema。因此请求头不走 ProviderInstance / ProviderInstanceEntity，而是存在
 * 独立的 SharedPreferences（`provider_extra_headers`，JSON：
 * `{"<instanceId>": {"Header": "value"}}`），由 [ProviderFactory] 在构建
 * provider 时读取并注入。
 *
 * 这样做的代价是它不会随 provider 的 JSON 备份导出 —— 与“最小可用、清晰
 * 扩展点”的目标一致：等 b3 的迁移窗口打开后，把这里换成 ProviderInstance
 * 字段即可（读取入口 [headersFor] 保持不变）。
 *
 * ## 安全 / 正确性约束
 *
 *  - 逐行解析 `Name: value`，空行与 `#` 注释忽略；
 *  - header 名必须是 RFC 7230 token，值不允许 CR/LF（防注入）；
 *  - 拒绝会破坏传输语义的 Host / Content-Length / Connection 等；
 *  - 校验失败时整次保存失败（返回明确错误），不做“部分成功”。
 *
 * ## 缓存
 *
 * [prime] 在 MinisApp.onCreate 里调用一次，只保存 app context；首次真正读写时
 * 才打开 EncryptedSharedPreferences（keystore 初始化不应压在主线程启动路径上）。
 * [ProviderFactory.create] 在后台线程首次读取后，缓存常驻内存。
 * 保存后 bump [revision]，ProviderFactory 的 memo key 带上 header 哈希，
 * 已缓存的 provider 会在下一次 create 时重建。
 *
 * ## 存储安全
 *
 * 请求头里经常放 relay 的鉴权头（Authorization / X-Api-Key），所以落盘走
 * [com.openminis.app.util.EncryptedPrefsFactory]（Keystore-backed
 * EncryptedSharedPreferences），与 API key / env var 同一等级；不用普通 prefs。
 */
object ProviderHeaderStore {

    private const val PREFS = "provider_extra_headers"
    private const val KEY = "headers_json"
    private const val MAX_HEADERS = 32
    private const val MAX_NAME_LEN = 128
    private const val MAX_VALUE_LEN = 4096

    /** 不允许用户覆盖的传输层头：覆盖它们会直接破坏请求。 */
    private val BLOCKED = setOf(
        "host", "content-length", "connection", "transfer-encoding",
        "expect", "upgrade", "te", "trailer", "proxy-connection",
    )

    /** RFC 7230 token 字符集（header name）。 */
    private val NAME_RE = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")

    @Volatile
    private var app: Context? = null

    @Volatile
    private var cache: Map<String, Map<String, String>> = emptyMap()

    @Volatile
    private var loaded: Boolean = false
    private val loadLock = Any()

    /** 单调递增，保存后 +1；保留给需要“配置代”语义的调用点。 */
    @Volatile
    private var revisionValue: Long = 0L

    fun revision(): Long = revisionValue

    /**
     * 只捕获 context，不做 keystore IO —— MinisApp.onCreate 在主线程调用，
     * EncryptedSharedPreferences 的第一次打开必须留到实际读写时。
     */
    fun prime(context: Context) {
        app = context.applicationContext
    }

    /** 首次读写时加载；失败按空配置处理（不阻断请求）。 */
    private fun ensureLoaded() {
        if (loaded) return
        val ctx = app ?: return
        synchronized(loadLock) {
            if (loaded) return
            // A keystore hiccup must degrade to "no custom headers" instead of
            // taking down provider construction; the factory would otherwise
            // throw on the first chat after a restore.
            cache = runCatching { load(ctx) }.getOrDefault(emptyMap())
            loaded = true
        }
    }

    private fun prefs(ctx: Context) =
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(ctx.applicationContext, PREFS)

    /** 该实例当前生效的请求头；未配置返回空 map（调用点无需判空）。 */
    fun headersFor(instanceId: String): Map<String, String> {
        if (instanceId.isBlank()) return emptyMap()
        ensureLoaded()
        return cache[instanceId].orEmpty()
    }

    /** 已配置请求头的实例数（设置 UI 用来显示“已配置”）。 */
    fun configuredInstanceIds(): Set<String> {
        ensureLoaded()
        return cache.keys.toSet()
    }

    /** 保存结果的错误信息；null = 成功。 */
    fun save(instanceId: String, rawText: String): String? {
        if (instanceId.isBlank()) return "Unknown provider instance"
        val ctx = app ?: return "Provider header store is not initialized"
        ensureLoaded()
        val parsed = parse(rawText) ?: return parseError ?: "Invalid headers"
        val next = cache.toMutableMap()
        if (parsed.isEmpty()) next.remove(instanceId) else next[instanceId] = parsed
        val ok = runCatching {
            prefs(ctx)
                .edit()
                .putString(KEY, encode(next))
                .commit()
        }.getOrDefault(false)
        if (!ok) return "Failed to persist headers"
        cache = next
        revisionValue++
        return null
    }

    /** 删除该实例的全部自定义请求头。 */
    fun clear(instanceId: String): Boolean =
        if (instanceId.isBlank()) false else save(instanceId, "") == null

    /**
     * 把已保存的请求头还原成逐行文本，供设置 UI 编辑。保持插入顺序
     * （JSONObject 在 Android 上不保证顺序，但 map 的迭代顺序来自解析时的
     * LinkedHashMap，持久化往返后仍稳定到“按写入顺序”这一档就够用）。
     */
    fun rawTextFor(instanceId: String): String =
        headersFor(instanceId).entries.joinToString("\n") { "${it.key}: ${it.value}" }

    /** 上次 [save] / [parse] 的具体错误，UI 直接展示。 */
    @Volatile
    var parseError: String? = null
        private set

    /**
     * 解析逐行文本；合法返回 LinkedHashMap，非法返回 null（原因写进
     * [parseError]）。空文本 = 清空，返回空 map。
     */
    internal fun parse(rawText: String): LinkedHashMap<String, String>? {
        parseError = null
        val out = LinkedHashMap<String, String>()
        for ((lineNo, rawLine) in rawText.lineSequence().withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) {
                parseError = "Line ${lineNo + 1}: expected 'Name: value'"
                return null
            }
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (name.isEmpty() || !NAME_RE.matches(name)) {
                parseError = "Line ${lineNo + 1}: invalid header name '$name'"
                return null
            }
            if (name.length > MAX_NAME_LEN) {
                parseError = "Line ${lineNo + 1}: header name too long"
                return null
            }
            if (value.length > MAX_VALUE_LEN) {
                parseError = "Line ${lineNo + 1}: header value too long"
                return null
            }
            if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                parseError = "Line ${lineNo + 1}: header value contains a control character"
                return null
            }
            if (name.lowercase() in BLOCKED) {
                parseError = "Line ${lineNo + 1}: '$name' is managed by the HTTP client and cannot be overridden"
                return null
            }
            if (out.keys.any { it.equals(name, ignoreCase = true) }) {
                parseError = "Line ${lineNo + 1}: duplicate header '$name'"
                return null
            }
            out[name] = value
            if (out.size > MAX_HEADERS) {
                parseError = "Too many headers (max $MAX_HEADERS)"
                return null
            }
        }
        return out
    }

    private fun load(context: Context): Map<String, Map<String, String>> {
        val raw = prefs(context).getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            buildMap {
                for (id in root.keys()) {
                    val obj = root.optJSONObject(id) ?: continue
                    val headers = LinkedHashMap<String, String>()
                    for (name in obj.keys()) {
                        val value = obj.optString(name, "")
                        // Re-validate on read: the prefs file is app-private but a
                        // hand-edited/restored copy must not smuggle a blocked or
                        // malformed header past the save-time checks.
                        if (name.isNotBlank() && value.isNotBlank() &&
                            NAME_RE.matches(name) && name.lowercase() !in BLOCKED
                        ) {
                            headers[name] = value
                        }
                    }
                    if (headers.isNotEmpty()) put(id, headers)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun encode(map: Map<String, Map<String, String>>): String {
        val root = JSONObject()
        for ((id, headers) in map) {
            val obj = JSONObject()
            for ((name, value) in headers) obj.put(name, value)
            root.put(id, obj)
        }
        return root.toString()
    }
}
