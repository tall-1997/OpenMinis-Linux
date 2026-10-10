package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.i18n.MlKitTranslationEngine
import com.openminis.app.i18n.TranslationPrefs
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun TranslationSettingsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onOpenPad: () -> Unit,
) {
    val context = LocalContext.current
    val config by providerRepository.config.collectAsState()
    var entryId by remember { mutableStateOf(TranslationPrefs.entryId(context)) }
    // [T-mlkit-stream-translate] 思考流/输出流的实时离线翻译设置。
    var streamEnabled by remember { mutableStateOf(TranslationPrefs.isStreamEnabled(context)) }
    BackHandler(onBack = onBack)
    val entries = config.modelEntries.filter { entry ->
        if (entry.isHidden) return@filter false
        val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return@filter false
        inst.isEnabled
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_translate),
        onBack = null,
    ) {
        SettingsSection(header = stringResource(R.string.translate_settings_section)) {
            // [T-translation-page-slim] 「显示翻译按钮」开关挪到外观设置页；
            // 「翻译成」下拉删除——与下方实时翻译的目标语言重复，气泡翻译
            // 改读同一个目标语言（TranslationPrefs.lang 回退 streamTarget）。
            // 本区块只剩气泡翻译的模型列表。
        }
        // [T-mlkit-stream-translate] 思考流/输出流的实时离线翻译（ML Kit）。
        // [T-apk-flavors] slim 变体无 bundled 推理运行时：区块换成需要 full 版
        // 的提示，而不是渲染一个永远下载失败的语言包卡。
        if (MlKitTranslationEngine.bundledEngine) {
            SettingsSection(header = stringResource(R.string.translate_stream_section)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            streamEnabled = !streamEnabled
                            TranslationPrefs.setStreamEnabled(context, streamEnabled)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.translate_stream_enabled), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.translate_stream_enabled_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = streamEnabled,
                        onCheckedChange = {
                            streamEnabled = it
                            TranslationPrefs.setStreamEnabled(context, it)
                        },
                    )
                }
                HorizontalDivider()
                // [T-lang-picker][T-lang-switch-txn] 语言对下拉 + 切换事务状态条 +
                // 回滚失败错误条 + 语言包管理卡。prefs 只在下载成功后由控制器写入。
                TranslationSwitchPanel()
            }
        } else {
            SettingsSection(header = stringResource(R.string.translate_stream_section)) {
                Text(
                    stringResource(R.string.translate_stream_slim_notice),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsSection(header = stringResource(R.string.translate_pick_model)) {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.translate_no_model),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    entries.forEach { entry ->
                        val provider = config.instances.find { it.id == entry.providerInstanceId }
                        val selected = entry.id == entryId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    entryId = entry.id
                                    TranslationPrefs.setEntryId(context, entry.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.model.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    provider?.label ?: entry.providerInstanceId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.cd_selected))
                            }
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.translate_settings_hint),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsSection {
            SettingsRow(
                title = stringResource(R.string.translate_pad_open),
                subtitle = stringResource(R.string.translate_pad_open_sub),
                onClick = onOpenPad,
                showDivider = false,
            )
        }
    }
}

/**
 * [T-mlkit-model-mgmt] 语言包状态/下载/删除卡（引擎状态机驱动）。
 * 下载跑在引擎级 scope：退出页面不中断，重进显示在飞的 Downloading 状态。
 */
@Composable
internal fun TranslationModelManager(
    src: String,
    tgt: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by remember(src, tgt) { MlKitTranslationEngine.packState(src, tgt) }.collectAsState()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(src, tgt) { busy = false }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val statusText = when (val s = state) {
            MlKitTranslationEngine.PackState.Checking -> stringResource(R.string.translate_model_status_checking)
            MlKitTranslationEngine.PackState.NeedsDownload -> stringResource(R.string.translate_model_status_not_downloaded)
            MlKitTranslationEngine.PackState.Ready -> stringResource(R.string.translate_model_status_ready)
            is MlKitTranslationEngine.PackState.Downloading ->
                stringResource(R.string.translate_model_downloading) +
                    (s.detailText.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            is MlKitTranslationEngine.PackState.Failed -> stringResource(R.string.translate_model_failed, s.message)
        }
        Text(
            stringResource(R.string.translate_model_section) + " · " + statusText,
            style = MaterialTheme.typography.labelLarge,
        )
        // [T-mlkit-download-stall] ML Kit 不暴露字节进度：不确定式进度条，
        // 不再渲染会卡死的假百分比。
        if (state is MlKitTranslationEngine.PackState.Downloading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        when (state) {
            is MlKitTranslationEngine.PackState.Downloading -> Unit
            is MlKitTranslationEngine.PackState.Failed, MlKitTranslationEngine.PackState.NeedsDownload, MlKitTranslationEngine.PackState.Checking -> {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { MlKitTranslationEngine.downloadPack(context, src, tgt) }
                            busy = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.translate_model_download), style = MaterialTheme.typography.labelMedium)
                }
            }
            MlKitTranslationEngine.PackState.Ready -> {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { MlKitTranslationEngine.deletePack(src, tgt) }
                            busy = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.translate_model_delete), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
