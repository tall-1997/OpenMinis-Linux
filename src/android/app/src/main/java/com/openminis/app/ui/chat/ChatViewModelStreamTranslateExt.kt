package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.i18n.MlKitTranslationEngine
import com.openminis.app.i18n.ModelStreamTranslator
import com.openminis.app.i18n.StreamTranslator
import com.openminis.app.i18n.TranslationLanguages
import com.openminis.app.i18n.TranslationOutcome
import com.openminis.app.i18n.TranslationPrefs
import com.openminis.app.i18n.TranslationRunner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-model-stream-translate] 流式实时翻译支持件：离线（ML Kit）与模型兜底
 * 两路 translator 的判定、喂入、flush 与回滚重置。从 AgentLoopExt 抽出以
 * 控制其行数棘轮；调用方只持 [StreamTranslateHolder]，分支逻辑全在此。
 *
 * 引擎判定：AUTO = 离线优先、无 GMS 时模型接管；OFFLINE = 仅 ML Kit（原
 * 语义）；MODEL = 仅模型（无 GMS 设备的主力路径）。两条都不可用静默跳过。
 */
internal class StreamTranslateHolder {
    var offline: StreamTranslator? = null
    var model: ModelStreamTranslator? = null

    /** 当前已翻内容（离线优先，模型路径为异步已翻部分）。 */
    val translatedSoFar: String?
        get() = (offline?.translatedSoFar ?: model?.translatedSoFar)?.takeIf { it.isNotEmpty() }

    /** 重试/回退回滚：丢弃本轮翻译状态（旧 job 取消，防 onUpdate 写回滚后的块表）。 */
    fun reset() {
        offline = null
        model?.cancel()
        model = null
    }
}

/** 离线路径（ML Kit）是否接管本轮流翻译。 */
internal fun streamOfflineOn(context: Context): Boolean {
    if (!TranslationPrefs.isStreamEnabled(context)) return false
    if (!MlKitTranslationEngine.available) return false
    return TranslationPrefs.streamEngine(context) != TranslationPrefs.StreamEngine.MODEL
}

/** 模型兜底路径是否接管本轮流翻译。 */
internal fun streamModelOn(context: Context): Boolean {
    if (!TranslationPrefs.isStreamEnabled(context)) return false
    return when (TranslationPrefs.streamEngine(context)) {
        TranslationPrefs.StreamEngine.MODEL -> true
        TranslationPrefs.StreamEngine.AUTO -> !MlKitTranslationEngine.available
        else -> false
    }
}

/** 模型路径的批量翻译 lambda：批内句子并发（async），失败句静默置 null。 */
internal fun modelStreamBatch(context: Context): suspend (List<String>) -> List<String?> {
    val target = TranslationLanguages.displayName(TranslationPrefs.streamTarget(context))
    return { batch ->
        coroutineScope { batch.map { s -> async { translateSentence(context, s, target) } }.awaitAll() }
    }
}

private suspend fun translateSentence(context: Context, sentence: String, target: String): String? =
    when (val out = TranslationRunner.translate(context, sentence, target)) {
        is TranslationOutcome.Text -> out.value
        else -> null
    }

/** 离线路径喂入：返回最新已翻内容（同步毫秒级）。 */
internal suspend fun feedOfflineTranslate(holder: StreamTranslateHolder, context: Context, delta: String): String? =
    runCatching {
        (holder.offline ?: StreamTranslator(
            MlKitTranslationEngine,
            TranslationPrefs.streamSource(context),
            TranslationPrefs.streamTarget(context),
        ).also { holder.offline = it }).feed(delta)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

/**
 * 模型路径喂入：feed 只切句入队（非阻塞），翻译在注入 scope 异步跑。
 * 返回当前已翻部分（与离线路径同语义，onUpdate 回调负责后续刷新）。
 */
internal fun feedModelTranslate(
    holder: StreamTranslateHolder,
    scope: CoroutineScope,
    batch: suspend (List<String>) -> List<String?>,
    onUpdate: (suspend (String) -> Unit)? = null,
    delta: String,
): String? {
    val t = holder.model ?: ModelStreamTranslator(scope, batch, onUpdate).also { holder.model = it }
    t.feed(delta)
    return t.translatedSoFar.takeIf { it.isNotEmpty() }
}

/**
 * thinking 块的流翻译喂入：离线随句更新；模型路径 feed 入队异步翻，
 * onUpdate 回调刷覆盖层。返回当前已翻部分（未开启/不可用返回 null）。
 */
internal suspend fun feedThinkingTranslate(
    holder: StreamTranslateHolder,
    vm: ChatViewModel,
    context: Context,
    scope: CoroutineScope,
    batch: suspend (List<String>) -> List<String?>,
    blocks: MutableList<AssistantBlock>,
    turn: Int,
    assistantId: String,
    accumulatedText: String,
    turnTextSb: StringBuilder,
    offlineOn: Boolean,
    modelOn: Boolean,
    delta: String,
): String? = when {
    offlineOn -> feedOfflineTranslate(holder, context, delta)
    modelOn -> feedModelTranslate(
        holder, scope, batch,
        onUpdate = thinkingTranslateOnUpdate(vm, blocks, turn, assistantId, accumulatedText, turnTextSb),
        delta = delta,
    )
    else -> null
}

/** 文本块的流翻译喂入：离线句级缓冲（materialize 挂块）；模型入队（流结束 flush 挂回）。 */
internal suspend fun feedTextTranslate(
    holder: StreamTranslateHolder,
    context: Context,
    scope: CoroutineScope,
    batch: suspend (List<String>) -> List<String?>,
    offlineOn: Boolean,
    modelOn: Boolean,
    delta: String,
) {
    if (offlineOn) {
        feedOfflineTranslate(holder, context, delta)
    } else if (modelOn) {
        feedModelTranslate(holder, scope, batch, delta = delta)
    }
}

/** thinking 块覆盖层的异步刷新回调（模型路径 onUpdate）。 */
internal fun thinkingTranslateOnUpdate(
    vm: ChatViewModel,
    blocks: MutableList<AssistantBlock>,
    turn: Int,
    assistantId: String,
    accumulatedText: String,
    turnTextSb: StringBuilder,
): suspend (String) -> Unit = { translated ->
    if (translated.isNotEmpty()) {
        val idx = blocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
        if (idx >= 0) {
            blocks[idx] = blocks[idx].copy(translatedContent = translated)
            withContext(Dispatchers.Main) {
                vm.updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, blocks)
            }
        }
    }
}

/**
 * 模型路径流结束：flush 尾句翻译，把最终覆盖层挂回文本块（materialize 只
 * 挂已翻部分，flush 补齐）；thinking 覆盖层由 onUpdate 自刷，这里只 flush。
 * 翻译失败静默降级（覆盖层保持已翻部分）。
 */
internal suspend fun ChatViewModel.flushModelStreamTranslation(
    holder: StreamTranslateHolder,
    blocks: MutableList<AssistantBlock>,
    monolithic: Boolean,
    turnTextBlockIdx: Int,
    assistantId: String,
    accumulatedText: String,
    turnTextSb: StringBuilder,
) {
    if (holder.model == null) return
    runCatching { holder.model?.flush() }
    val translated = holder.model?.translatedSoFar?.takeIf { it.isNotEmpty() }
    if (translated != null) {
        val idx = if (monolithic) turnTextBlockIdx else blocks.lastIndex
        if (idx >= 0 && idx < blocks.size && blocks[idx].kind == "text") {
            blocks[idx] = blocks[idx].copy(translatedContent = translated)
        }
    }
    withContext(Dispatchers.Main) {
        updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, blocks)
    }
}

/**
 * [T-reply-translated-only] 工具边界补尾（异步）：materialize 只能挂"已翻部分"，
 * 模型批在飞的尾句 flush 后回填冻结块——否则中段文本块的译文缺尾，单段显示
 * 会丢内容。不阻塞工具派发；块 id 在协程外捕获（工具块随后入列，索引会漂）。
 * 失败静默：保持已翻部分，显示端由长度守卫回落原文。
 */
internal fun ChatViewModel.launchTextTailPatch(
    holder: StreamTranslateHolder,
    blocks: MutableList<AssistantBlock>,
    monolithic: Boolean,
    turnTextBlockIdx: Int,
    assistantId: String,
    accumulatedText: String,
    turnTextSb: StringBuilder,
) {
    if (holder.model == null) return
    val idx = if (monolithic) turnTextBlockIdx else blocks.lastIndex
    val blockId = blocks.getOrNull(idx)?.takeIf { it.kind == "text" }?.id ?: return
    viewModelScope.launch {
        runCatching { holder.model?.flush() }
        val translated = holder.model?.translatedSoFar?.takeIf { it.isNotEmpty() } ?: return@launch
        val i = blocks.indexOfFirst { it.id == blockId && it.kind == "text" }
        if (i >= 0) {
            blocks[i] = blocks[i].copy(translatedContent = translated)
            withContext(Dispatchers.Main) {
                updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, blocks)
            }
        }
    }
}
