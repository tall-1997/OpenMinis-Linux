package com.openminis.app.i18n

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-mlkit-offline-translate] ML Kit 离线翻译引擎：思考流/输出流的实时翻译。
 *
 * - 推理完全在本机（不出设备、零隐私外泄），毫秒级每句；
 * - 语言包**不内置**：首次使用某语言对时经 [ensurePack] 按需下载（已下载则
 *   跳过）；下载状态经 [packState] 供设置页展示；
 * - **可用性探测**：ML Kit 依赖 Play services 基建，无 GMS 的设备上首次
 *   translate 会抛——[probe] 用一次小调用探测，失败永久降级（[available]
 *   翻转 false，调用方静默不渲染翻译行；气泡翻译仍走 LLM 路径）。
 * - 每语言对一个 Translator，缓存复用。
 *
 * ML Kit 的语言码就是字符串（[TranslateLanguage.fromLanguageTag] 返回
 * String?，TranslatorOptions/RemoteModel 都吃 String）——不要引入中间类型。
 */
object MlKitTranslationEngine {

    sealed class PackState {
        data object Ready : PackState()
        data object Downloading : PackState()
        data class Failed(val message: String) : PackState()
        data object NeedsDownload : PackState()
    }

    @Volatile
    var available: Boolean = true
        private set

    private val translators = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val packStates = ConcurrentHashMap<String, PackState>()

    fun packState(src: String, tgt: String): PackState = packStates[key(src, tgt)] ?: PackState.NeedsDownload

    /** BCP-47 tag → ML Kit 语言码（字符串）；不支持的 tag 返回 null。 */
    fun mlLang(tag: String): String? = try {
        TranslateLanguage.fromLanguageTag(tag)
    } catch (_: Throwable) {
        null
    }

    private fun key(src: String, tgt: String) = "$src>$tgt"

    private fun translator(src: String, tgt: String): com.google.mlkit.nl.translate.Translator {
        val k = key(src, tgt)
        return translators.getOrPut(k) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlLang(src) ?: return@getOrPut throw IllegalArgumentException("unsupported source language: $src"))
                .setTargetLanguage(mlLang(tgt) ?: throw IllegalArgumentException("unsupported target language: $tgt"))
                .build()
            Translation.getClient(options)
        }
    }

    /**
     * 一次性可用性探测：确保 en→zh 语言包并翻译一个词。无 GMS / 模块缺失时
     * 抛异常 → 永久降级（available=false，之后调用方全部静默）。
     */
    suspend fun probe(context: Context): Boolean {
        if (!available) return false
        return try {
            ensurePack("en", "zh")
            translate("hi", "en", "zh").isNotBlank()
        } catch (t: Throwable) {
            available = false
            false
        }
    }

    /** 确保语言包已下载（已存在则立即返回）。 */
    suspend fun ensurePack(src: String, tgt: String) {
        val srcCode = mlLang(src) ?: throw IllegalArgumentException("unsupported source language: $src")
        val tgtCode = mlLang(tgt) ?: throw IllegalArgumentException("unsupported target language: $tgt")
        val k = key(src, tgt)
        if (packStates[k] is PackState.Ready) return
        packStates[k] = PackState.Downloading
        try {
            val manager = RemoteModelManager.getInstance()
            val downloaded = runCatching {
                manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            }.getOrDefault(emptySet())
            val haveSrc = downloaded.any { it.language == srcCode }
            val haveTgt = downloaded.any { it.language == tgtCode }
            if (!haveSrc) manager.download(TranslateRemoteModel.Builder(srcCode).build(), DownloadConditions.Builder().build()).await()
            if (!haveTgt) manager.download(TranslateRemoteModel.Builder(tgtCode).build(), DownloadConditions.Builder().build()).await()
            packStates[k] = PackState.Ready
        } catch (t: Throwable) {
            packStates[k] = PackState.Failed(t.message ?: "download failed")
            throw t
        }
    }

    /** 单句翻译（毫秒级）。异常向上抛，由调用方决定降级。 */
    suspend fun translate(text: String, src: String, tgt: String): String {
        if (text.isBlank()) return ""
        return translator(src, tgt).translate(text).await()
    }
}
