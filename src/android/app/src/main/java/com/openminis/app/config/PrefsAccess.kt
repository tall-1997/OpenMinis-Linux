package com.openminis.app.config

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 一个 prefs 文件的类型化句柄 —— 建在 [PrefsBackend] 之上。
 *
 * ```
 * private val ui = Prefs.file("ui_prefs")
 * var splitMode by ui.boolProp("tablet_list_pane_collapsed", false)
 * val lastModel = ui.string("chat.last_model", "auto")
 * ```
 *
 * 为什么不像早先草案那样再定义一套 `PrefsSource`：`PrefsBackend` 已经是
 * 「键 → 值」的完整抽象（`PrefsFields.kt` 的 7 个配置字段类都建在它上面），
 * 再加一个同形状的接口只会让"哪个才是底层"变成新的歧义 —— 那正是这次
 * 重构要消除的东西。
 *
 * 唯一强制的东西：**键必须可追溯**。
 * - [PrefsRegistry] 已为该文件登记键清单 → 只接受登记过的键；
 * - 未登记（历史遗留文件）→ 至少要求 `前缀.名字` 形态。
 */
class PrefsHandle internal constructor(
    val spec: PrefsFileSpec,
    private val backend: PrefsBackend,
) {
    val name: String get() = spec.name

    /**
     * 把句柄内部的 backend 交给配置字段用。
     *
     * `PrefsBoolField` 等字段的主构造函数收 `PrefsBackend`，但那是**不带守卫**的
     * 原始后端 —— 直接传进去等于绕开了本句柄的键校验。这里返回的是一个在每次
     * 读写时先过 [check] 的包装。
     *
     * `keys()` / `snapshot()` 不校验：它们本来就是给诊断和迁移用的全量视图，
     * 注册表里没登记的键也得看得见，否则永远查不出漏登记。
     *
     * **`write { }` 内部的 put/remove 不过守卫** —— `Writer` 是纯接口，
     * 没有拦截点。走这条路的调用点必须自己确认键已登记。目前只有
     * `ConfigBuiltins` 的字段绑定用它，那些键都在注册表里。
     */
    fun guardedBackend(): PrefsBackend = object : PrefsBackend {
        override val name: String get() = spec.name
        override fun contains(key: String): Boolean = backend.contains(check(key))
        override fun keys(): Set<String> = backend.keys()
        override fun snapshot(): Map<String, Any?> = backend.snapshot()
        override fun readBoolean(key: String, defaultValue: Boolean): Boolean =
            backend.readBoolean(check(key), defaultValue)
        override fun readInt(key: String, defaultValue: Int): Int =
            backend.readInt(check(key), defaultValue)
        override fun readLong(key: String, defaultValue: Long): Long =
            backend.readLong(check(key), defaultValue)
        override fun readFloat(key: String, defaultValue: Float): Float =
            backend.readFloat(check(key), defaultValue)
        override fun readString(key: String, defaultValue: String?): String? =
            backend.readString(check(key), defaultValue)
        override fun readStringSet(key: String, defaultValue: Set<String>?): Set<String>? =
            backend.readStringSet(check(key), defaultValue)
        override fun write(block: PrefsBackend.Writer.() -> Unit) =
            backend.write(block)
    }

    fun string(key: String, default: String = ""): String = backend.readString(check(key), default) ?: default

    fun stringOrNull(key: String): String? = backend.readString(check(key), null)

    fun int(key: String, default: Int = 0): Int = backend.readInt(check(key), default)

    fun long(key: String, default: Long = 0L): Long = backend.readLong(check(key), default)

    fun float(key: String, default: Float = 0f): Float = backend.readFloat(check(key), default)

    fun bool(key: String, default: Boolean = false): Boolean = backend.readBoolean(check(key), default)

    fun stringSet(key: String, default: Set<String> = emptySet()): Set<String> =
        backend.readStringSet(check(key), default) ?: default

    fun set(key: String, value: String?): Boolean = backend.write { putString(check(key), value) }
    fun set(key: String, value: Int): Boolean = backend.write { putInt(check(key), value) }
    fun set(key: String, value: Long): Boolean = backend.write { putLong(check(key), value) }
    fun set(key: String, value: Float): Boolean = backend.write { putFloat(check(key), value) }
    fun set(key: String, value: Boolean): Boolean = backend.write { putBoolean(check(key), value) }
    fun set(key: String, value: Set<String>?): Boolean = backend.write { putStringSet(check(key), value) }

    fun remove(key: String) = backend.write { remove(check(key)) }

    /**
     * 批量写：一次事务提交多个键（对应旧的 `prefs.edit()...apply()`）。
     * 每个键仍然过 [check]，所以批量写不能绕开键名校验。
     */
    fun write(block: PrefsBackend.Writer.() -> Unit) {
        backend.write { block(GuardedWriter(this)) }
    }

    /** 把 [PrefsBackend.Writer] 的每次调用都接到 [check] 后面。 */
    private inner class GuardedWriter(private val delegate: PrefsBackend.Writer) :
        PrefsBackend.Writer {
        override fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(check(key), value)
        override fun putInt(key: String, value: Int) = delegate.putInt(check(key), value)
        override fun putLong(key: String, value: Long) = delegate.putLong(check(key), value)
        override fun putFloat(key: String, value: Float) = delegate.putFloat(check(key), value)
        override fun putString(key: String, value: String?) = delegate.putString(check(key), value)
        override fun putStringSet(key: String, value: Set<String>?) =
            delegate.putStringSet(check(key), value)
        override fun remove(key: String) = delegate.remove(check(key))
    }

    fun contains(key: String): Boolean = backend.contains(check(key))

    /**
     * 订阅本文件的键变更，返回取消订阅函数。
     *
     * 键不校验：监听器拿到的 key 是写盘时实际写入的键，其中可能包含
     * 未登记的历史键（诊断/迁移路径会写这类键）。校验只会让"删掉一个
     * 已退休键"的通知在 `check()` 里抛异常、直接崩掉 UI 线程。
     */
    fun observe(onChange: (key: String) -> Unit): () -> Unit =
        backend.observe(onChange)

    /** 本文件已落盘的全部键（不校验，用于诊断/迁移）。 */
    fun rawKeys(): Set<String> = backend.keys()

    /**
     * 全部键值快照（不校验）。用于「按内容算指纹」这类场景 ——
     * OnlinePluginStore.toolDefinitionsStamp 就用 `.all.hashCode()` 判断
     * 插件标志位有没有变。键空间必须是开放的或已登记的。
     */
    fun snapshot(): Map<String, Any?> = backend.snapshot()

    /**
     * 清空本文件全部键，返回删掉的个数。
     *
     * 用于清理已退休功能的遗留数据 —— `ProviderConfigStore.purgeRetiredBuiltInLaneFiles`
     * 就是这么清 `"a2a_gateway"` 的。
     *
     * 只在开放键空间上允许：对已登记的文件调 clear() 等于静默删掉用户配置，
     * 那必须由更明确的调用点负责，不该是一个通用方法顺手做到的事。
     */
    fun clear(): Int {
        require(spec.openKeySpace) {
            "clear() only allowed on open-key-space files; ${spec.name} has a fixed registry"
        }
        val victims = backend.keys()
        if (victims.isEmpty()) return 0
        backend.write { victims.forEach { remove(it) } }
        return victims.size
    }

    /**
     * 删除命中 [prefix] 的全部键，返回删掉的个数。
     *
     * 这是 `prefs.all.keys.filter { it.startsWith(prefix) }` 的类型化版本 ——
     * ConnectorNetworkPolicy 清理插件配置时就是这么做的，一次事务里删掉
     * `allow_private_<pluginId>` 和它的所有 scope 变体。
     *
     * 前缀本身必须是被接受的（登记过的键前缀或动态前缀），否则这个方法会
     * 变成一个绕过守卫的批量删除后门。
     */
    fun removeAllWithPrefix(prefix: String): Int {
        require(prefix.isNotEmpty()) { "prefix must not be empty" }
        require(spec.accepts(prefix) || spec.keys.values.any { it.startsWith(prefix) } ||
            spec.dynamicPrefixes.any { it.startsWith(prefix) || prefix.startsWith(it) }) {
            "prefix '$prefix' is not covered by PrefsRegistry for ${spec.name}"
        }
        val victims = backend.keys().filter { it.startsWith(prefix) }
        if (victims.isEmpty()) return 0
        backend.write { victims.forEach { remove(it) } }
        return victims.size
    }

    fun boolProp(key: String, default: Boolean = false): ReadWriteProperty<Any?, Boolean> =
        object : ReadWriteProperty<Any?, Boolean> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = bool(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { set(key, value) }
        }

    fun stringProp(key: String, default: String = ""): ReadWriteProperty<Any?, String> =
        object : ReadWriteProperty<Any?, String> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = string(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { set(key, value) }
        }

    fun intProp(key: String, default: Int = 0): ReadWriteProperty<Any?, Int> =
        object : ReadWriteProperty<Any?, Int> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = int(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { set(key, value) }
        }

    fun floatProp(key: String, default: Float = 0f): ReadWriteProperty<Any?, Float> =
        object : ReadWriteProperty<Any?, Float> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = float(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) { set(key, value) }
        }

    fun longProp(key: String, default: Long = 0L): ReadWriteProperty<Any?, Long> =
        object : ReadWriteProperty<Any?, Long> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = long(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) { set(key, value) }
        }

    /**
     * 键名守卫，规则见类注释。
     * 登记清单用 [PrefsFileSpec.keys] 的**值**（真实字符串键），不是常量名。
     *
     * 三条放行路径，按优先级：
     *   1. [PrefsFileSpec.accepts] —— 静态登记 / 动态前缀 / 开放键空间
     *   2. keys 与 dynamicPrefixes 都为空 —— 扫描器没抓到清单，退回最弱的
     *      「前缀.名字」约定（这本身是扫描器缺陷，但守卫不能因此崩溃）
     *   3. 其余 —— 明确的错
     */
    private fun check(key: String): String {
        require(key.isNotBlank()) { "prefs key must not be blank" }
        // 1. 静态登记、动态前缀、开放键空间 —— 一定放行
        if (spec.accepts(key)) return key
        // 2. 该文件没有任何登记清单（历史遗留，扫描器没抓到键常量）——
        //    退回最弱约定：必须带 '前缀.名字' 形态，挡住裸键即可。
        if (spec.keys.isEmpty() && spec.dynamicPrefixes.isEmpty()) {
            require(key.contains('.')) {
                "key '$key' must look like 'prefix.name'; ${spec.name} has no registered key list, so only the prefix convention applies"
            }
            return key
        }
        // 3. 有登记清单但不包含该键 —— 明确的错
        throw IllegalArgumentException(
            buildString {
                append("key '$key' is not registered in PrefsRegistry for ${spec.name}")
                if (spec.dynamicPrefixes.isNotEmpty()) {
                    append("; allowed dynamic prefixes in this file: ")
                    append(spec.dynamicPrefixes.joinToString(", ") { "`$it`" })
                }
                append("; add `const val KEY_X = \"")
                append(key)
                append("\"` in the source file, then re-run gen_prefs_registry.py")
            }
        )
    }
}

/**
 * 全局入口。真实构造走 `Prefs.install(source)`（AppContainer 里装一次），
 * 测试构造走 `Prefs.install(InMemorySource())`。
 */
object Prefs {

    private var source: PrefsSource? = null
    private val cache = LinkedHashMap<String, PrefsHandle>()

    fun install(s: PrefsSource) {
        synchronized(this) {
            source = s
            cache.clear()
        }
    }

    fun uninstall() = synchronized(this) {
        source = null
        cache.clear()
    }

    fun file(name: String): PrefsHandle = synchronized(this) {
        cache.getOrPut(name) {
            val spec = PrefsRegistry.require(name)
            val s = source ?: error("Prefs is not installed: call Prefs.install(...) from MinisApp.onCreate")
            PrefsHandle(spec, s.backend(spec.name))
        }
    }

    /** 诊断/迁移用：绕开缓存与工厂，直接拿一个后端。 */
    fun handleFor(spec: PrefsFileSpec, backend: PrefsBackend) = PrefsHandle(spec, backend)
}
