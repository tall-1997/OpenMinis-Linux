package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.i18n.LanguageSourceProvider
import com.openminis.app.i18n.MlKitLanguageSource
import com.openminis.app.i18n.MlKitTranslationEngine
import com.openminis.app.i18n.TranslationLanguages
import com.openminis.app.i18n.TranslationRunner
import com.openminis.app.i18n.visibleCodes
import com.openminis.app.ui.components.SectionDropdown

/**
 * [T-lang-picker] 语言清单数据源的 Composable 持有者：进页面即 refresh 一次
 * （stale 首帧先渲染，live 态到达后收窄）。
 *
 * 三个注入点的实义：
 * - `probeFallback` = 有可用模型 entry（全局一个 flag，逐语言探测纯属耗电）；
 * - `queryDownloaded` = live 查 RemoteModelManager 已下载语言码；
 * - `isSupported` = ML Kit 静态支持判定（TranslateLanguage 可解析）。
 */
@Composable
internal fun rememberLanguageSource(): MlKitLanguageSource {
    val context = LocalContext.current
    val provider = remember {
        MlKitLanguageSource(
            probeFallback = { TranslationRunner.resolveEntry(context) != null },
            queryDownloaded = { MlKitTranslationEngine.downloadedLanguages() },
            isSupported = { MlKitTranslationEngine.mlLang(it) != null },
        )
    }
    LaunchedEffect(provider) { provider.refresh() }
    return provider
}

/**
 * [T-lang-picker] 翻译语言下拉框：替代手输 BCP-47 代码（手输错码 ML Kit 直接抛
 * IllegalArgumentException，用户无从排查）。
 *
 * 可选项 = [visibleCodes]（live 可见集 ∪ 当前选中），显示名走
 * [TranslationLanguages.displayName] 的系统本地化（zh 设备显示「英语」）。
 * 可见性判定与顺序都在 i18n 纯函数里，本文件只是 Composable 外壳。
 */
@Composable
fun LanguagePicker(
    selected: String,
    statuses: List<LanguageSourceProvider.LanguageStatus>,
    fallbackReachable: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    exclude: String? = null,
) {
    val codes = remember(statuses, fallbackReachable, selected, exclude) {
        statuses.visibleCodes(fallbackReachable, selected, exclude)
    }
    SectionDropdown(
        selected = selected,
        items = codes,
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
        itemLabel = { TranslationLanguages.displayName(it) },
    )
}

/**
 * [T-lang-picker] 单语言目标选择器（翻译板 / 模型翻译的目标语言）。
 *
 * **不走切换事务**：模型翻译不需要离线语言包，没有「下载中取消」要回滚的
 * 东西，选完直接写 prefs。需要事务的是流式翻译语言对（见
 * [TranslationSwitchPanel]）。
 */
@Composable
fun TranslationTargetField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val provider = rememberLanguageSource()
    val statuses by provider.languages.collectAsState()
    val fallbackReachable by provider.fallbackReachable.collectAsState()
    LanguagePicker(
        selected = value,
        statuses = statuses,
        fallbackReachable = fallbackReachable,
        onSelect = onValueChange,
        modifier = modifier,
    )
}
