package com.openminis.app.config

import android.content.Context
import com.openminis.app.util.EncryptedPrefsFactory

/**
 * 把注册表里的 [PrefsFileSpec] 变成可读写的 [PrefsBackend]。
 *
 * 为什么不直接 `context.getSharedPreferences(...)`：
 *  - 文件名必须出自 [PrefsRegistry]，否则拼错一个字母就是静默新建一个空文件
 *    （本仓库实测：458 处同名常量散在 13 个 object 里，全靠字符串对齐）。
 *  - 加密与否由 spec 的 [PrefsScope] 决定，不能让每个调用点自己判断 ——
 *    判断错了就是把密钥写进明文 XML。
 *  - 同一文件必须复用同一 backend（见 [PrefsBackends.wrap]）。
 *
 * 测试用 [InMemorySource]：不碰 Android，全走内存 map。
 */
interface PrefsSource {
    /**
     * @param name 必须是 [PrefsRegistry] 登记过的文件名
     * @throws IllegalArgumentException 未登记的文件名
     */
    fun backend(name: String): PrefsBackend
}

/** 唯一需要 Android Context 的实现。 */
class AndroidPrefsSource(private val context: Context) : PrefsSource {

    override fun backend(name: String): PrefsBackend {
        val spec = PrefsRegistry.require(name)
        val delegate = if (spec.isEncrypted()) {
            // EncryptedPrefsFactory 内部已做 per-file 缓存与自愈（见其 KDoc），
            // 这里再调一次不会重复打开。
            EncryptedPrefsFactory.safeCreate(context.applicationContext, name)
        } else {
            context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
        return PrefsBackends.wrap(delegate, name)
    }

    /** 人工排查用：某文件的真实存储性质。 */
    fun scopeOf(name: String): PrefsScope = PrefsRegistry.require(name).scope
}

/**
 * 内存实现。仍走 [PrefsRegistry.require] 校验，这样测试里写出一个未登记的
 * 文件名会立刻失败而不是默默通过 —— 与真实实现同构的失败语义。
 */
class InMemorySource(
    private val seed: Map<String, Map<String, Any?>> = emptyMap(),
) : PrefsSource {

    private val backends = HashMap<String, PrefsBackend>()

    override fun backend(name: String): PrefsBackend {
        PrefsRegistry.require(name)
        return backends.getOrPut(name) { InMemoryPrefsBackend(name, seed[name].orEmpty()) }
    }

    /** 测试钩子：直接拿到某文件后端，用于断言落盘内容。 */
    fun inspect(name: String): InMemoryPrefsBackend? = backends[name] as? InMemoryPrefsBackend
}
