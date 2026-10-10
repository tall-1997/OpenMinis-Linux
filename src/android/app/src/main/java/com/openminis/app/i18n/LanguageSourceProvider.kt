package com.openminis.app.i18n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [T-lang-source-provider] 语言清单数据源：翻译设置页/语言下拉框的单一
 * 语言状态来源。**动态查询而非静态快照**——ML Kit 支持集随 SDK 版本变，
 * 兜底模型集随自家 release 变，硬编码数组两侧任一更新即返工。
 *
 * 接口形状（拍板定稿）：
 * - per-language 两个 flag：[LanguageStatus.mlKitReady]（ML Kit 支持该语言）
 *   与 [LanguageStatus.downloaded]（离线包已下载）；
 * - **全局一个** [fallbackReachable]（模型兜底可达，单一服务端点语义——
 *   逐语言探测纯属耗电）；
 * - stale-while-revalidate：[languages] 首帧立即发射 stale 快照（静态支持
 *   集 + 已知下载态），[refresh] 后收窄为 live 态。stale 只提供首帧初始值，
 * **不作为保留理由**——可见性判定永远用 live 组合：
 * `visible = live(mlKitReady ∨ downloaded ∨ fallbackReachable)`。
 *
 * 实现侧 [MlKitLanguageSource] 持有 ML Kit 侧探测；兜底探测经构造注入
 * （设置页侧注入「有可用模型 entry」检查，无 Android 依赖可 JVM 测试）。
 */
interface LanguageSourceProvider {

    data class LanguageStatus(
        val code: String,
        /** ML Kit 支持该语言（TranslateLanguage.fromLanguageTag 可解析）。 */
        val mlKitReady: Boolean,
        /** 离线语言包已下载（live 查询 RemoteModelManager）。 */
        val downloaded: Boolean,
    ) {
        /** live 可见性：三 flag 任一为真（fallbackReachable 全局注入判定）。 */
        fun visible(fallbackReachable: Boolean): Boolean =
            mlKitReady || downloaded || fallbackReachable
    }

    /** 语言状态流（stale 首帧 → refresh 后 live）。顺序 = 展示顺序。 */
    val languages: StateFlow<List<LanguageStatus>>

    /** 全局兜底可达开关（一个 flag，非 per-language）。 */
    val fallbackReachable: StateFlow<Boolean>

    /** live 查询：刷新 downloaded 与 fallbackReachable。失败保持 stale 态。 */
    suspend fun refresh()
}

/**
 * ML Kit 侧实现：首帧 = [TranslationLanguages.CODES] + 静态支持判定 +
 * downloaded=false（stale）；[refresh] 查 RemoteModelManager 收窄。
 * downloaded 变化才收窄列表；fallbackReachable 只翻标记不删项。
 */
class MlKitLanguageSource(
    private val probeFallback: suspend () -> Boolean = { true },
    private val queryDownloaded: suspend () -> Set<String> = { emptySet() },
    private val isSupported: (String) -> Boolean = { true },
) : LanguageSourceProvider {

    private val stale = TranslationLanguages.CODES.map {
        LanguageSourceProvider.LanguageStatus(code = it, mlKitReady = isSupported(it), downloaded = false)
    }

    private val _languages = MutableStateFlow(stale)
    override val languages: StateFlow<List<LanguageSourceProvider.LanguageStatus>> = _languages

    private val _fallbackReachable = MutableStateFlow(true)
    override val fallbackReachable: StateFlow<Boolean> = _fallbackReachable

    override suspend fun refresh() {
        runCatching { _fallbackReachable.value = probeFallback() }
        val downloaded = runCatching { queryDownloaded() }.getOrDefault(null) ?: return
        _languages.value = stale.map {
            it.copy(downloaded = downloaded.contains(it.code))
        }
    }
}

/**
 * [T-lang-picker] 下拉框可选语言码 = live 可见集，按目录顺序。
 *
 * 当前选中项**即使不在可见集里也追加到末尾**：否则字段显示空值，用户看不到
 * 自己选的是什么、也无从判断为何被隐藏（例如兜底模型下线后原语言对失去
 * 可见性）。追加而非插首，保持目录展示顺序稳定。
 *
 * [exclude] 用于源/目标互斥（同一语言对翻译无意义）。空白码不入列。
 */
fun List<LanguageSourceProvider.LanguageStatus>.visibleCodes(
    fallbackReachable: Boolean,
    selected: String,
    exclude: String? = null,
): List<String> {
    val base = filter { it.visible(fallbackReachable) }
        .map { it.code.trim() }
        .filter { it.isNotEmpty() && it != exclude }
        .distinct()
    val keep = selected.trim()
    return if (keep.isNotEmpty() && keep != exclude && keep !in base) base + keep else base
}
