package com.openminis.app.provider

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
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
 * [prime] 在 MinisApp.onCreate 里调用一次，只保存 app context；随后同一启动
 * 流程在 IO 协程里调用 [preload] 完成首次 EncryptedSharedPreferences
 * （keystore 初始化不应压在主线程启动路径上）。[headersFor] 被
 * [ProviderFactory.create] 从 Main 调用时，若预热尚未完成则返回空 map 而不再
 * 同步打开 Keystore；加载完成后 revision +1，ProviderFactory 的 memo key 带上
 * header 哈希，已缓存的 provider 会在下一次 create 时重建。
 *
 * ## 存储安全
 *
 * 请求头里经常放 relay 的鉴权头（Authorization / X-Api-Key），所以落盘走
 * [com.openminis.app.util.EncryptedPrefsFactory]（Keystore-backed
 * EncryptedSharedPreferences），与 API key / env var 同一等级；不用普通 prefs。
 *
 * ## [GH#347] 为什么这里还存了一个开关
 *
 * `x-opencode-session` 默认只能发给 OpenCode 官方端点；自定义 relay 必须由用户
 * 在 Provider detail 显式 opt-in（[openCodeSessionEnabled] /
 * [setOpenCodeSessionEnabled]）。该布尔值存在同一 prefs 文件的独立 key 里，
 * 而不是 ProviderInstance 字段：ProviderDatabase 的迁移窗口不在本批，且这是
 * “请求头通道”的一部分（见 [OpenCodeSessionHeader.customEndpointHeadersFor]）。
 */
object ProviderHeaderStore {

    private const val PREFS = "provider_extra_headers"
    private const val KEY = "headers_json"

    /**
     * [GH#347] 同一 prefs 文件里的第二个 key：per-instance 的 opt-in 标记
     * （`{"<instanceId>": true}`）。与 headers 分开，避免用户头编辑/清空时
     * 误删开关；仍然不碰 ProviderDatabase schema。
     */
    private const val OPENCODE_SESSION_KEY = "opencode_session_json"
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

    /** [GH#347] instanceId → 是否允许发送 OpenCode 会话头。默认空 = 全部关闭。 */
    @Volatile
    private var openCodeSession: Map<String, Boolean> = emptyMap()

    @Volatile
    private var loaded: Boolean = false
    private val loadLock = Any()

    /**
     * 单调递增，首次加载完成与每次保存后 +1。ProviderFactory 的 memo key 带
     * 的是 header 内容哈希，所以这里不需要它来失效缓存；[revisionFlow] 是给
     * 需要“配置代”语义的观察者用的（加载完成前在 UI 侧拿到的是空 map）。
     *
     * [GH#221] `headersFor` 不再同步触发首次加载后，预热协程完成加载会 bump
     * 一次，观察者据此刷新；未完成期间的读取是空 map（见 [headersFor]）。
     */
    private val _revision = MutableStateFlow(0L)
    val revisionFlow: StateFlow<Long> = _revision.asStateFlow()

    fun revision(): Long = _revision.value

    private fun bumpRevision() {
        _revision.update { it + 1 }
    }

    /**
     * 只捕获 context，不做 keystore IO —— MinisApp.onCreate 在主线程调用，
     * EncryptedSharedPreferences 的第一次打开必须留到实际读写时。
     */
    fun prime(context: Context) {
        app = context.applicationContext
    }

    /**
     * [GH#221] 预热：在 IO 协程里完成首次 Keystore 打开与 JSON 解码。
     *
     * `MinisApp.onCreate` 用 `appCoroutineScopes.io.launch { preload() }` 调用。
     * 这样 `ProviderFactory.create()`（可能从 `viewModelScope` = Main 进入）
     * 第一次读 [headersFor] 时不再同步打开 Keystore、不再阻塞主线程。
     * 加载完成前读到的空 map 是可接受的：ProviderFactory 的 memo key 包含
     * header 内容指纹，预热完成后下一次 create 会自然重建带头的 provider。
     * [revisionFlow] 同时 +1，供需要响应式刷新的调用点观察。
     */
    fun preload() {
        if (loaded) return
        ensureLoaded()
        if (loaded) bumpRevision()
    }

    /**
     * 测试清理入口：把单例恢复到“未 prime / 未加载”的初始态，避免
     * `headersFor` 的非阻塞契约被同进程内先跑过的测试掩盖。
     */
    internal fun resetForTest() {
        synchronized(loadLock) {
            app = null
            cache = emptyMap()
            openCodeSession = emptyMap()
            loaded = false
            _revision.value = 0L
        }
    }

    /** 测试观察点：是否已完成首次加载（生产代码不调用）。 */
    internal fun isLoadedForTest(): Boolean = loaded

    /** 首次加载（IO 预热 [preload]，或保存/开关写入路径）；失败按空配置处理（不阻断请求）。 */
    private fun ensureLoaded() {
        if (loaded) return
        val ctx = app ?: return
        synchronized(loadLock) {
            if (loaded) return
            // A keystore hiccup must degrade to "no custom headers" instead of
            // taking down provider construction; the factory would otherwise
            // throw on the first chat after a restore.
            cache = runCatching { load(ctx) }.getOrDefault(emptyMap())
            openCodeSession = runCatching { loadOpenCodeSession(ctx) }.getOrDefault(emptyMap())
            loaded = true
        }
    }

    private fun prefs(ctx: Context) =
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(ctx.applicationContext, PREFS)

    /**
     * 该实例当前生效的请求头；未配置返回空 map（调用点无需判空）。
     *
     * [GH#221] 未预热完成（[loaded] == false）时直接返回空 map，绝不在这里
     * 触发首次同步加载：本方法被 [ProviderFactory.create] 从 Main 线程调用，
     * 首次加载会打开 Keystore-backed EncryptedSharedPreferences。预热由
     * `MinisApp.onCreate` 的 IO 协程负责（[preload]）；加载完成后 revision
     * +1，下一次 create 的 memo key 变化并带上真实 header。
     */
    fun headersFor(instanceId: String): Map<String, String> {
        if (instanceId.isBlank()) return emptyMap()
        if (!loaded) return emptyMap()
        return cache[instanceId].orEmpty()
    }

    /** 已配置请求头的实例数（设置 UI 用来显示“已配置”）；预热完成前为空集。 */
    fun configuredInstanceIds(): Set<String> {
        // [GH#221] 与 headersFor 同策略：不触发 Keystore 首次同步加载。
        if (!loaded) return emptySet()
        return cache.keys.toSet()
    }

    /**
     * 保存结果的错误信息；null = 成功。
     *
     * [GH#221] suspend + IO：save 首次调用会打开 EncryptedSharedPreferences
     * （Keystore 初始化）并 `commit()` 落盘，原先在设置页的 onClick 里同步
     * 执行，直接卡主线程。所有调用点都必须从协程调用。
     */
    suspend fun save(instanceId: String, rawText: String): String? = withContext(Dispatchers.IO) {
        if (instanceId.isBlank()) return@withContext "Unknown provider instance"
        val ctx = app ?: return@withContext "Provider header store is not initialized"
        ensureLoaded()
        val parsed = parse(rawText) ?: return@withContext parseError ?: "Invalid headers"
        val next = cache.toMutableMap()
        if (parsed.isEmpty()) next.remove(instanceId) else next[instanceId] = parsed
        val ok = runCatching {
            prefs(ctx)
                .edit()
                .putString(KEY, encode(next))
                .commit()
        }.getOrDefault(false)
        if (!ok) return@withContext "Failed to persist headers"
        cache = next
        bumpRevision()
        null
    }

    /** 删除该实例的全部自定义请求头。 */
    suspend fun clear(instanceId: String): Boolean =
        if (instanceId.isBlank()) false else save(instanceId, "") == null

    /**
     * 把已保存的请求头还原成逐行文本，供设置 UI 编辑。保持插入顺序
     * （JSONObject 在 Android 上不保证顺序，但 map 的迭代顺序来自解析时的
     * LinkedHashMap，持久化往返后仍稳定到“按写入顺序”这一档就够用）。
     *
     * [GH#221] 必须只从 IO 调用：编辑框的初值不能依赖“预热已经完成”的时序，
     * 否则空文本会让用户一保存就把已存 header 清掉，所以这里显式
     * [ensureLoaded]（当前两个调用点都在 `withContext(Dispatchers.IO)` 内）。
     */
    fun rawTextFor(instanceId: String): String {
        ensureLoaded()
        return headersFor(instanceId).entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    /**
     * [GH#347] 是否允许向该实例的自定义端点发送 `x-opencode-session`。
     *
     * 默认 **false**，这是隐私默认值：会话 ID 虽然不含账号信息，但它是稳定的
     * 每对话标识符，第三方 relay 不应该在用户没有显式打开开关时收到它。
     * OpenCode 官方端点（`*.opencode.ai`）不受此开关影响，仍由
     * [OpenCodeSessionHeader.headersFor] 自动发送 —— 那条路径是服务端的硬性
     * 路由要求。
     */
    fun openCodeSessionEnabled(instanceId: String): Boolean {
        if (instanceId.isBlank()) return false
        // [GH#221] 与 headersFor 同策略：未预热完成时返回隐私默认值 false，
        // 不在请求路径 / Main 上触发 Keystore 首次同步加载。真正打开时设置页
        // 的 save/setOpenCodeSessionEnabled 已经在 IO 上 ensureLoaded。
        if (!loaded) return false
        return openCodeSession[instanceId] == true
    }

    /**
     * [GH#347] 设置 per-instance 会话头 opt-in。返回是否持久化成功
     * （失败时内存态不变，UI 可据此提示）。
     *
     * suspend + IO，与 [save] 同理：`commit()` 会打开 Keystore-backed prefs，
     * 不能在 Compose 点击回调的主线程上同步执行。
     */
    suspend fun setOpenCodeSessionEnabled(instanceId: String, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (instanceId.isBlank()) return@withContext false
            val ctx = app ?: return@withContext false
            ensureLoaded()
            val next = openCodeSession.toMutableMap()
            if (enabled) next[instanceId] = true else next.remove(instanceId)
            val ok = runCatching {
                prefs(ctx).edit()
                    .putString(OPENCODE_SESSION_KEY, encodeFlags(next))
                    .commit()
            }.getOrDefault(false)
            if (!ok) return@withContext false
            openCodeSession = next
            bumpRevision()
            true
        }

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

    /** [GH#347] 读取 opt-in 标记；损坏/空文件都退化成“全部关闭”。 */
    private fun loadOpenCodeSession(context: Context): Map<String, Boolean> {
        val raw = prefs(context).getString(OPENCODE_SESSION_KEY, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            buildMap {
                for (id in root.keys()) {
                    if (root.optBoolean(id, false)) put(id, true)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun encodeFlags(map: Map<String, Boolean>): String {
        val root = JSONObject()
        for ((id, enabled) in map) if (enabled) root.put(id, true)
        return root.toString()
    }
}
