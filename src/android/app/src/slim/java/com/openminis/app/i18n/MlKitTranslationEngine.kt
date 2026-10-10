package com.openminis.app.i18n

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-apk-flavors] slim 变体的翻译引擎存根：与 full 变体的
 * [com.google.mlkit.nl.translate] 引擎同公开 API，但整引擎恒不可用。
 *
 * slim APK 不带 ML Kit 依赖（libtranslate_jni.so arm64 15.6 MB，bundled 交付
 * 无法按需化，用户报告「安装包又变大了」）——src/main 零改动：流式翻译在
 * 调用点被 [available]=false 关闭（零渲染），设置页的 ML Kit 区块经
 * [bundledEngine]=false 换成需要 full 版的提示；气泡翻译仍走 LLM 路径。
 *
 * 存根永不写 available=true：没有推理运行时，任何"可用"都是谎言。
 */
object MlKitTranslationEngine {

    /** 与 full 变体同构（设置页/测试共用）；slim 下 flow 恒停在 NeedsDownload。 */
    sealed class PackState {
        data object Checking : PackState()
        data object NeedsDownload : PackState()
        data class Downloading(
            val progress: Float? = null,
            val step: Int = 1,
            val totalSteps: Int = 2,
            val downloadedBytes: Long = 0L,
            val totalBytes: Long = 0L,
        ) : PackState() {
            /** "12.3 MB / 29.8 MB (41%)" or "41% (第 1/2 阶段)" */
            val detailText: String
                get() = if (downloadedBytes > 0L && totalBytes > 0L) {
                    val dl = String.format(java.util.Locale.US, "%.1f", downloadedBytes / 1048576.0)
                    val tot = String.format(java.util.Locale.US, "%.1f", totalBytes / 1048576.0)
                    val pct = ((downloadedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                    "$dl MB / $tot MB ($pct%)"
                } else if (progress != null) {
                    "${(progress * 100).toInt().coerceIn(0, 100)}% (第 $step/$totalSteps 阶段)"
                } else {
                    "第 $step/$totalSteps 阶段 (连接中...)"
                }
        }
        data object Ready : PackState()
        data class Failed(val message: String) : PackState()
    }

    @Volatile
    var available: Boolean = false
        private set

    /** [T-apk-flavors] slim 变体：无 bundled 推理运行时。 */
    const val bundledEngine: Boolean = false

    private val packStates = ConcurrentHashMap<String, MutableStateFlow<PackState>>()

    /** 翻译缓存在 slim 下照常存在（测试断言淘汰行为；永不命中推理路径）。 */
    internal val translationCache = Collections.synchronizedMap(
        object : LinkedHashMap<Int, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean {
                return size > 100
            }
        }
    )

    fun packState(src: String, tgt: String): StateFlow<PackState> =
        packStates.getOrPut(key(src, tgt)) { MutableStateFlow(PackState.NeedsDownload) }

    /** slim 无 ML Kit 语言目录：任何 tag 都不受支持（语言清单收窄为空集）。 */
    fun mlLang(tag: String): String? = null

    private fun key(src: String, tgt: String) = "$src>$tgt"

    suspend fun isPairReady(src: String, tgt: String): Boolean = false

    suspend fun downloadedLanguages(): Set<String> = emptySet()

    /** slim 下探测恒失败（available 已是 false，永不翻转）。 */
    suspend fun probe(context: Context): Boolean = false

    /** slim 下无推理运行时：显式失败而不是假装下载。 */
    suspend fun downloadPack(context: Context, src: String, tgt: String, requireWifi: Boolean = false) {
        flowFor(key(src, tgt)).value = PackState.Failed("translation engine requires the full build")
        throw IllegalStateException("translation engine requires the full build")
    }

    fun cancelActiveDownload(src: String, tgt: String) {
        flowFor(key(src, tgt)).value = PackState.NeedsDownload
    }

    suspend fun deletePack(src: String, tgt: String) {
        translationCache.clear()
        flowFor(key(src, tgt)).value = PackState.NeedsDownload
    }

    /** slim 下无推理：返回空串，调用方 takeIf { isNotBlank() } 自然零渲染。 */
    suspend fun translate(text: String, src: String, tgt: String): String = ""

    private fun flowFor(k: String): MutableStateFlow<PackState> =
        packStates.getOrPut(k) { MutableStateFlow(PackState.NeedsDownload) }
}
