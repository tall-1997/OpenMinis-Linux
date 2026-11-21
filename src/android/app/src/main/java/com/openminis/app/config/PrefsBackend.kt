package com.openminis.app.config

import android.content.SharedPreferences

/**
 * config 层真正需要的窄接口 —— 六个读 + 一次批量写 + 键枚举。
 *
 * 为什么要接口而不是直接收 [SharedPreferences]：`SharedPreferences` 的唯一可用
 * 实现要求 Android 运行时，这会让每个配置字段都无法在 JVM 上测。
 * [PrefsBackend] 只有字段包装类实际用到的那些方法，所以一个纯 map 实现
 * （[InMemoryPrefsBackend]）就足以单测全部配置字段 —— 33 项断言正是这么跑的。
 *
 * 任何实现都必须保持的语义（现有代码依赖全部三条）：
 *  - [readInt]/[readLong]/[readFloat] 在键缺失**或类型不符**时返回传入的默认值。
 *    `SharedPreferences` 在第二种情况会抛 `ClassCastException`，这里归一成默认值，
 *    因为 agent 配置面不能因为一个损坏的存量值而崩溃。
 *  - [write] 返回即代表已落盘 —— 不用 `apply()` 那种延迟刷盘，因为 agent 报告
 *    成功的配置写入必须在进程立刻被杀后仍然存在。
 *  - [keys] 返回当前实际存在的键（含未在注册表登记的），供诊断与迁移用。
 */
interface PrefsBackend {
    val name: String
    fun contains(key: String): Boolean
    fun keys(): Set<String>

    /**
     * 全部键值快照。用于「按内容算指纹」—— OnlinePluginStore 用
     * `.all.hashCode()` 判断插件标志位是否变化，只比字符串值就够了。
     * 返回的 Map 是不可变副本，读它不会看到后续写入。
     */
    fun snapshot(): Map<String, Any?>
    fun readBoolean(key: String, defaultValue: Boolean): Boolean
    fun readInt(key: String, defaultValue: Int): Int
    fun readLong(key: String, defaultValue: Long): Long
    fun readFloat(key: String, defaultValue: Float): Float
    fun readString(key: String, defaultValue: String?): String?
    fun readStringSet(key: String, defaultValue: Set<String>?): Set<String>?

    /**
     * 批量写。[block] 里的所有 put/remove 在实现内部合成一次事务提交。
     *
     * 返回提交是否**成功落盘**。`SharedPreferences.commit()` 会在磁盘满、
     * XML 损坏、或存储不可写时返回 false —— 调用方（ProviderRepository 的
     * JSON 镜像）需要据此决定要不要报错重试，所以这个布尔不能吞掉。
     * 内存后端永远返回 true：没有盘可写，也就没有失败。
     */
    fun write(block: Writer.() -> Unit): Boolean

    /**
     * 订阅本文件的键变更，返回取消订阅的函数。
     *
     * 语义必须与 `SharedPreferences.OnSharedPreferenceChangeListener` 一致：
     *  - 只在**写盘生效**后回调（不是 `write {}` 调用时），`key` 是被改的键；
     *  - 批量写里的每个键各回调一次 —— 调用方按 key 分支判定的写法（ChatScreen
     *    的 appearance 监听器）依赖这一点，合成一次会让它漏掉中间状态；
     *  - 同进程内的写入**必须**触发，这是跨页面同步的唯一机制
     *    （设置页改字号 → 聊天页监听器重读）。
     *
     * 默认空实现：`InMemoryPrefsBackend` 这类纯测试后端没有监听需求，
     * 订阅等于无操作，不会让测试因为「没人发通知」而挂住。
     */
    fun observe(onChange: (key: String) -> Unit): () -> Unit = {}

    interface Writer {
        fun putBoolean(key: String, value: Boolean)
        fun putInt(key: String, value: Int)
        fun putLong(key: String, value: Long)
        fun putFloat(key: String, value: Float)
        fun putString(key: String, value: String?)
        fun putStringSet(key: String, value: Set<String>?)
        fun remove(key: String)
    }
}

/** [PrefsBackend] over a real `SharedPreferences`. The only Android-bound implementation. */
class SharedPrefsBackend(
    private val prefs: SharedPreferences,
    override val name: String = "<shared>",
) : PrefsBackend {

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun keys(): Set<String> = prefs.all.keys.toSet()

    override fun snapshot(): Map<String, Any?> = HashMap<String, Any?>(prefs.all)

    override fun readBoolean(key: String, defaultValue: Boolean): Boolean =
        if (!prefs.contains(key)) defaultValue
        else runCatching { prefs.getBoolean(key, defaultValue) }.getOrDefault(defaultValue)

    override fun readInt(key: String, defaultValue: Int): Int =
        if (!prefs.contains(key)) defaultValue
        else runCatching { prefs.getInt(key, defaultValue) }.getOrDefault(defaultValue)

    override fun readLong(key: String, defaultValue: Long): Long =
        if (!prefs.contains(key)) defaultValue
        else runCatching { prefs.getLong(key, defaultValue) }.getOrDefault(defaultValue)

    override fun readFloat(key: String, defaultValue: Float): Float =
        if (!prefs.contains(key)) defaultValue
        else runCatching { prefs.getFloat(key, defaultValue) }.getOrDefault(defaultValue)

    override fun readString(key: String, defaultValue: String?): String? =
        if (!prefs.contains(key)) defaultValue
        else runCatching { prefs.getString(key, defaultValue) }.getOrDefault(defaultValue)

    override fun readStringSet(key: String, defaultValue: Set<String>?): Set<String>? =
        if (!prefs.contains(key)) defaultValue
        else runCatching { prefs.getStringSet(key, defaultValue)?.toSet() }.getOrDefault(defaultValue)

    /**
     * 用 `commit()` 而非 `apply()`：见接口 KDoc。配置写入一局只有几次，
     * 主线程开销无关紧要；而丢一次写会产生极难复现的「设置不生效」报告。
     */
    override fun write(block: PrefsBackend.Writer.() -> Unit): Boolean {
        val editor = prefs.edit()
        val w = object : PrefsBackend.Writer {
            override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
            override fun putInt(key: String, value: Int) { editor.putInt(key, value) }
            override fun putLong(key: String, value: Long) { editor.putLong(key, value) }
            override fun putFloat(key: String, value: Float) { editor.putFloat(key, value) }
            override fun putString(key: String, value: String?) {
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
            override fun putStringSet(key: String, value: Set<String>?) {
                if (value == null) editor.remove(key) else editor.putStringSet(key, value)
            }
            override fun remove(key: String) { editor.remove(key) }
        }
        w.block()
        return editor.commit()
    }

    override fun observe(onChange: (key: String) -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            key?.let(onChange)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

/** Test/offline backend. Deterministic, no Android runtime, no disk. */
class InMemoryPrefsBackend(
    override val name: String,
    initial: Map<String, Any?> = emptyMap(),
) : PrefsBackend {

    private val data = LinkedHashMap<String, Any?>(initial)

    /** Test hook: simulate a corrupt value of the wrong primitive type. */
    fun corrupt(key: String, raw: Any?) { data[key] = raw }

    override fun snapshot(): Map<String, Any?> = HashMap<String, Any?>(data)

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun keys(): Set<String> = data.keys.toSet()

    override fun readBoolean(key: String, defaultValue: Boolean): Boolean =
        (data[key] as? Boolean) ?: defaultValue

    override fun readInt(key: String, defaultValue: Int): Int =
        (data[key] as? Int) ?: defaultValue

    override fun readLong(key: String, defaultValue: Long): Long =
        when (val v = data[key]) {
            is Long -> v
            is Int -> v.toLong()
            else -> defaultValue
        }

    override fun readFloat(key: String, defaultValue: Float): Float =
        when (val v = data[key]) {
            is Float -> v
            is Double -> v.toFloat()
            else -> defaultValue
        }

    override fun readString(key: String, defaultValue: String?): String? =
        (data[key] as? String) ?: defaultValue

    @Suppress("UNCHECKED_CAST")
    override fun readStringSet(key: String, defaultValue: Set<String>?): Set<String>? =
        (data[key] as? Set<String>)?.toSet() ?: defaultValue

    override fun write(block: PrefsBackend.Writer.() -> Unit): Boolean {
        val pending = LinkedHashMap<String, Any?>()
        val removed = LinkedHashSet<String>()
        val w = object : PrefsBackend.Writer {
            override fun putBoolean(key: String, value: Boolean) { pending[key] = value; removed.remove(key) }
            override fun putInt(key: String, value: Int) { pending[key] = value; removed.remove(key) }
            override fun putLong(key: String, value: Long) { pending[key] = value; removed.remove(key) }
            override fun putFloat(key: String, value: Float) { pending[key] = value; removed.remove(key) }
            override fun putString(key: String, value: String?) {
                if (value == null) { pending.remove(key); removed.add(key) }
                else { pending[key] = value; removed.remove(key) }
            }
            override fun putStringSet(key: String, value: Set<String>?) {
                if (value == null) { pending.remove(key); removed.add(key) }
                else { pending[key] = value.toSet(); removed.remove(key) }
            }
            override fun remove(key: String) { pending.remove(key); removed.add(key) }
        }
        w.block()
        removed.forEach { data.remove(it) }
        data.putAll(pending)
        return true
    }
}

/**
 * 同一个 `SharedPreferences` 实例必须复用同一个 backend 句柄。
 *
 * 理由：`SharedPreferences.Editor` 的每次 `commit()` 是整文件重写的语义，两个
 * backend 各自持有一个 Editor 交错提交并不会丢数据（Editor 基于快照合并），
 * 但每新建一个包装对象就多一次 `prefs.all` 拷贝；更重要的是句柄身份稳定后，
 * `Prefs` 的缓存才有意义。用 `IdentityHashMap` 是因为 `SharedPreferences`
 * 没有可靠的 `equals`。
 */
object PrefsBackends {
    private val cache = java.util.IdentityHashMap<SharedPreferences, SharedPrefsBackend>()

    @Synchronized
    fun wrap(prefs: SharedPreferences, name: String): PrefsBackend =
        cache.getOrPut(prefs) { SharedPrefsBackend(prefs, name) }

    @Synchronized
    fun clearForTest() = cache.clear()
}
