package com.openminis.app.i18n

import android.content.Context
import android.app.DownloadManager
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-mlkit-offline-translate] ML Kit 离线翻译引擎：思考流/输出流的实时翻译。
 * 模型生命周期管理（状态机/字节级下载进度/删除/LRU 缓存）复用自
 * taixu TranslationManager（wkbin/taixu，core/common/translation）。
 *
 * - 推理完全在本机（不出设备、零隐私外泄），毫秒级每句；
 * - 语言包**不内置**：经 [downloadPack] 显式下载（~30MB/语言），下载进度
 *   经 [packState] StateFlow 供设置页展示——ML Kit 的 download Task 是黑盒，
 *   字节级进度靠轮询系统 DownloadManager（模型下载走系统通道）；
 * - **可用性探测**：无 GMS 设备首次 translate 会抛——[probe] 探测失败永久
 *   降级（[available]=false，调用方静默不渲染翻译行）；
 * - 翻译结果走 LRU 缓存（100 条）：流式重放/重复句零推理；
 * - 每语言对一个 Translator，缓存复用。
 */
object MlKitTranslationEngine {

    /** 对齐 taixu TranslationModelStatus；按语言对独立流转。 */
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
    var available: Boolean = true
        private set

    private val translators = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val packStates = ConcurrentHashMap<String, MutableStateFlow<PackState>>()
    private val downloadMutexes = ConcurrentHashMap<String, Mutex>()

    /** LRU 翻译缓存（复用 taixu：最多 100 条，访问序淘汰）。 */
    internal val translationCache = Collections.synchronizedMap(
        object : LinkedHashMap<Int, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean {
                return size > 100
            }
        }
    )

    fun packState(src: String, tgt: String): StateFlow<PackState> =
        flowFor(key(src, tgt)).also { refreshStatus(src, tgt, it) }

    /** BCP-47 tag → ML Kit 语言码；不支持的 tag 返回 null。 */
    fun mlLang(tag: String): String? = try {
        TranslateLanguage.fromLanguageTag(tag)
    } catch (_: Throwable) {
        null
    }

    private fun key(src: String, tgt: String) = "$src>$tgt"

    private fun flowFor(k: String): MutableStateFlow<PackState> =
        packStates.getOrPut(k) { MutableStateFlow(PackState.Checking) }

    /** 异步检测两个语言包的本地状态（复用 taixu refreshStatus）。 */
    private fun refreshStatus(src: String, tgt: String, flow: MutableStateFlow<PackState>) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            flow.value = PackState.Checking
            flow.value = try {
                if (isPairDownloaded(src, tgt)) PackState.Ready else PackState.NeedsDownload
            } catch (_: Throwable) {
                PackState.NeedsDownload
            }
        }
    }

    private suspend fun isPairDownloaded(src: String, tgt: String): Boolean {
        val srcCode = mlLang(src) ?: return false
        val tgtCode = mlLang(tgt) ?: return false
        val manager = RemoteModelManager.getInstance()
        val downloaded = runCatching {
            manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
        }.getOrDefault(emptySet())
        return downloaded.any { it.language == srcCode } && downloaded.any { it.language == tgtCode }
    }

    /**
     * 一次性可用性探测：确保 en→zh 语言包并翻译一个词。无 GMS / 模块缺失时
     * 抛异常 → 永久降级（available=false，之后调用方全部静默）。
     */
    suspend fun probe(context: Context): Boolean {
        if (!available) return false
        return try {
            downloadPack(context, "en", "zh")
            translate("hi", "en", "zh").isNotBlank()
        } catch (t: Throwable) {
            available = false
            false
        }
    }

    /**
     * 下载语言对模型（复用 taixu downloadModel：分阶段 + DownloadManager
     * 字节级进度轮询 + 无进度时平滑估算）。已就绪立即返回；并发调用同语言对
     * 只跑一个。失败置 [PackState.Failed] 并抛。
     */
    suspend fun downloadPack(context: Context, src: String, tgt: String, requireWifi: Boolean = false) {
        val srcCode = mlLang(src) ?: throw IllegalArgumentException("unsupported source language: $src")
        val tgtCode = mlLang(tgt) ?: throw IllegalArgumentException("unsupported target language: $tgt")
        val k = key(src, tgt)
        val flow = flowFor(k)
        val mutex = downloadMutexes.getOrPut(k) { Mutex() }
        if (!mutex.tryLock()) return
        try {
            if (flow.value is PackState.Ready && isPairDownloaded(src, tgt)) return
            val conditions = DownloadConditions.Builder().apply {
                if (requireWifi) requireWifi()
            }.build()
            val manager = RemoteModelManager.getInstance()
            val queue = mutableListOf<TranslateRemoteModel>()
            val downloaded = runCatching {
                manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            }.getOrDefault(emptySet())
            if (downloaded.none { it.language == srcCode }) queue.add(TranslateRemoteModel.Builder(srcCode).build())
            if (downloaded.none { it.language == tgtCode }) queue.add(TranslateRemoteModel.Builder(tgtCode).build())
            if (queue.isEmpty()) {
                flow.value = PackState.Ready
                return
            }
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val totalSteps = queue.size
                    for (i in queue.indices) {
                        val base = i.toFloat() / totalSteps
                        val weight = 1f / totalSteps
                        val monitor: Job = launch {
                            var simulated = 0.05f
                            while (isActive) {
                                delay(250)
                                val dm = queryDownloadProgress(context)
                                flow.value = if (dm != null && dm.second > 0L) {
                                    PackState.Downloading(
                                        progress = base + (dm.first.toFloat() / dm.second).coerceIn(0f, 0.99f) * weight,
                                        step = i + 1, totalSteps = totalSteps,
                                        downloadedBytes = dm.first, totalBytes = dm.second,
                                    )
                                } else {
                                    if (simulated < 0.90f) simulated += 0.03f
                                    PackState.Downloading(progress = base + simulated * weight, step = i + 1, totalSteps = totalSteps)
                                }
                            }
                        }
                        try {
                            manager.download(queue[i], conditions).await()
                        } finally {
                            monitor.cancel()
                        }
                        flow.value = PackState.Downloading(progress = (i + 1).toFloat() / totalSteps, step = i + 1, totalSteps = totalSteps)
                    }
                }
            }
            flow.value = PackState.Ready
        } catch (t: Throwable) {
            flow.value = PackState.Failed(t.message ?: "download failed")
            throw t
        } finally {
            mutex.unlock()
        }
    }

    /** 删除语言对模型释放空间（复用 taixu deleteModel）。 */
    suspend fun deletePack(src: String, tgt: String) {
        val srcCode = mlLang(src) ?: throw IllegalArgumentException("unsupported source language: $src")
        val tgtCode = mlLang(tgt) ?: throw IllegalArgumentException("unsupported target language: $tgt")
        val k = key(src, tgt)
        try {
            val manager = RemoteModelManager.getInstance()
            manager.deleteDownloadedModel(TranslateRemoteModel.Builder(srcCode).build()).await()
            manager.deleteDownloadedModel(TranslateRemoteModel.Builder(tgtCode).build()).await()
            translators.remove(k)
            translationCache.clear()
            flowFor(k).value = PackState.NeedsDownload
        } catch (t: Throwable) {
            flowFor(k).value = PackState.Failed(t.message ?: "delete failed")
            throw t
        }
    }

    /** 查询系统 DownloadManager 活跃任务的字节进度（照搬 taixu）。 */
    private fun queryDownloadProgress(context: Context): Pair<Long, Long>? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        return try {
            val query = DownloadManager.Query()
            dm.query(query)?.use { cursor ->
                val bytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                var latestBytes = 0L
                var latestTotal = 0L
                while (cursor.moveToNext()) {
                    val status = if (statusCol != -1) cursor.getInt(statusCol) else -1
                    if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED) {
                        val bytes = if (bytesCol != -1) cursor.getLong(bytesCol) else 0L
                        val total = if (totalCol != -1) cursor.getLong(totalCol) else 0L
                        if (total > 0L) {
                            latestBytes = bytes
                            latestTotal = total
                        }
                    }
                }
                if (latestTotal > 0L) Pair(latestBytes, latestTotal) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun translator(src: String, tgt: String): com.google.mlkit.nl.translate.Translator {
        val k = key(src, tgt)
        return translators.getOrPut(k) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlLang(src) ?: throw IllegalArgumentException("unsupported source language: $src"))
                .setTargetLanguage(mlLang(tgt) ?: throw IllegalArgumentException("unsupported target language: $tgt"))
                .build()
            Translation.getClient(options)
        }
    }

    /** 单句翻译（毫秒级，LRU 缓存命中零推理）。异常向上抛，由调用方决定降级。 */
    suspend fun translate(text: String, src: String, tgt: String): String {
        if (text.isBlank()) return ""
        translationCache[text.hashCode()]?.let { return it }
        val translated = translator(src, tgt).translate(text).await()
        translationCache[text.hashCode()] = translated
        return translated
    }
}
