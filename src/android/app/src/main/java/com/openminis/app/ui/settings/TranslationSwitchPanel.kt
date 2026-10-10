package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.i18n.LanguageSourceProvider
import com.openminis.app.i18n.LanguageSwitchController
// 嵌套类直接 import：typealias 能展开类型本身，但通过别名访问嵌套分类器
// （SwitchState.Idle）解析不了。
import com.openminis.app.i18n.LanguageSwitchController.LangPair
import com.openminis.app.i18n.LanguageSwitchController.SwitchState
import com.openminis.app.i18n.MlKitTranslationEngine
import com.openminis.app.i18n.TranslationLanguages
import com.openminis.app.i18n.TranslationPrefs
import kotlinx.coroutines.launch

/**
 * [T-lang-switch-txn][T-lang-picker] 流式翻译语言对区块：两个下拉框 + 切换事务
 * 状态条 + 回滚失败错误条 + 语言包管理卡。
 *
 * **双槽直接映射到 UI**：下拉框显示已生效槽（prefs 当前值），进行中槽由状态条
 * 呈现（「正在切换到 X…」+ 取消）。prefs 只在下载成功后由控制器写入，所以
 * 确认弹窗点取消 = 事务从未开启，天然无需回滚；下载中取消 = abort + 清半成品。
 *
 * **连切不禁用**：新选择直接开新事务，控制器先回滚在飞的旧事务，且回滚基准是
 * 已生效槽（不是被顶掉的 pending）。因此这里**不**加 busy 门禁拦第二次点击——
 * 拦了就等于把「连切回滚到生效值」这条语义废掉。
 */
@Composable
internal fun TranslationSwitchPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 已生效槽：rev 递增触发重读（提交后刷新下拉框显示；失败/回滚时 prefs 未变，
    // 重读拿到同值，无害）。
    var rev by remember { mutableIntStateOf(0) }
    val effective = remember(rev) {
        LangPair(TranslationPrefs.streamSource(context), TranslationPrefs.streamTarget(context))
    }

    val provider = rememberLanguageSource()
    val statuses by provider.languages.collectAsState()
    val fallbackReachable by provider.fallbackReachable.collectAsState()

    val controller = remember {
        LanguageSwitchController(
            readEffective = {
                LangPair(TranslationPrefs.streamSource(context), TranslationPrefs.streamTarget(context))
            },
            writeEffective = { p ->
                TranslationPrefs.setStreamSource(context, p.src)
                TranslationPrefs.setStreamTarget(context, p.tgt)
            },
            isPairReady = { p -> MlKitTranslationEngine.isPairReady(p.src, p.tgt) },
            downloadPack = { p -> MlKitTranslationEngine.downloadPack(context, p.src, p.tgt) },
            abortDownload = { p -> MlKitTranslationEngine.cancelActiveDownload(p.src, p.tgt) },
            clearPartial = { p -> MlKitTranslationEngine.deletePack(p.src, p.tgt) },
        )
    }
    val switchState by controller.state.collectAsState()
    var confirm by remember { mutableStateOf<LangPair?>(null) }

    fun commit(pair: LangPair) {
        scope.launch {
            try {
                controller.requestSwitch(pair)
            } finally {
                rev++
            }
        }
    }

    // 已下载 → 直接提交；否则先确认（下载是几十 MB 的计费/耗电动作）。
    // 这里的 downloaded 只是 UI 侧预判，控制器内部还会再查一次 isPairReady。
    fun onPick(pair: LangPair) {
        val ready = statuses.downloaded(pair.src) && statuses.downloaded(pair.tgt)
        if (ready) commit(pair) else confirm = pair
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.translate_stream_source),
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            style = MaterialTheme.typography.labelLarge,
        )
        LanguagePicker(
            selected = effective.src,
            statuses = statuses,
            fallbackReachable = fallbackReachable,
            exclude = effective.tgt,
            onSelect = { code -> onPick(LangPair(code, effective.tgt)) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            stringResource(R.string.translate_stream_target),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
            style = MaterialTheme.typography.labelLarge,
        )
        LanguagePicker(
            selected = effective.tgt,
            statuses = statuses,
            fallbackReachable = fallbackReachable,
            exclude = effective.src,
            onSelect = { code -> onPick(LangPair(effective.src, code)) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        when (val s = switchState) {
            SwitchState.Idle -> Unit
            is SwitchState.Pending -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.translate_switch_pending, TranslationLanguages.displayName(s.to.tgt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.phase == SwitchState.Pending.Phase.DOWNLOADING) {
                        SwitchDownloadProgress(s.to.src, s.to.tgt)
                    }
                }
                TextButton(onClick = { scope.launch { controller.rollback() } }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
            is SwitchState.RollbackFailed -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.translate_switch_rollback_failed, s.message),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { controller.acknowledgeFailure() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        TranslationModelManager(
            src = effective.src,
            tgt = effective.tgt,
        )
    }

    confirm?.let { pair ->
        AlertDialog(
            // 确认前取消 = 事务未开启：prefs 未动，无需回滚。
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.translate_switch_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.translate_switch_confirm_msg,
                        TranslationLanguages.displayName(pair.src),
                        TranslationLanguages.displayName(pair.tgt),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    commit(pair)
                }) { Text(stringResource(R.string.translate_model_download)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** 切换事务下载阶段的不确定式进度（复用引擎 packState，与手动下载同一状态机）。 */
@Composable
private fun SwitchDownloadProgress(src: String, tgt: String) {
    val state by remember(src, tgt) { MlKitTranslationEngine.packState(src, tgt) }.collectAsState()
    val dl = state as? MlKitTranslationEngine.PackState.Downloading ?: return
    // [T-mlkit-download-stall] ML Kit 不暴露字节进度：不确定式进度条，
    // 不再渲染会卡死的假百分比。
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    if (dl.detailText.isNotBlank()) {
        Text(
            dl.detailText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 某语言码的离线包是否已在本地（UI 侧预判，控制器内部另有权威判定）。 */
private fun List<LanguageSourceProvider.LanguageStatus>.downloaded(code: String): Boolean =
    any { it.code == code && it.downloaded }
