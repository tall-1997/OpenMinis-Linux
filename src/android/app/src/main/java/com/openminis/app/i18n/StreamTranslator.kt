package com.openminis.app.i18n

/**
 * [T-mlkit-stream-translate] 思考流/输出流的**实时**翻译管道：句子缓冲 +
 * 逐句翻译。ML Kit 按短句翻译最快（毫秒级），逐 delta 调用会浪费且碎片化——
 * 这里把增长中的流按句子边界（中英文标点 + 换行）切片，完成的句子逐个翻译，
 * [feed] 返回「已翻译完成部分」的全文；尾部残句由 [flush] 在流结束时补上。
 *
 * 不改变原始流：调用方把 [feed] 的返回值作为**翻译覆盖层**渲染（原文照旧），
 * 引擎不可用（无 GMS/下载失败）时静默为空，零行为变化。
 */
class StreamTranslator(
    private val engine: MlKitTranslationEngine,
    private val src: String,
    private val tgt: String,
) {
    private val buffer = StringBuilder()
    private val translated = StringBuilder()
    private var seq = 0

    val translatedSoFar: String get() = translated.toString()

    /** 流式喂入一个 delta；返回到目前为止的翻译全文（无新完成句时不变）。 */
    suspend fun feed(delta: String): String {
        if (delta.isEmpty()) return translated.toString()
        buffer.append(delta)
        // 按句子边界切片：中英文句号/叹号/问号/换行。引号/括号跟随句尾。
        while (true) {
            val end = sentenceEnd(buffer) ?: break
            val sentence = buffer.substring(0, end).trim()
            buffer.delete(0, end)
            if (sentence.isEmpty()) continue
            seq++
            val translatedSentence = runCatching {
                engine.translate(sentence, src, tgt)
            }.getOrNull()
            if (!translatedSentence.isNullOrBlank()) {
                if (translated.isNotEmpty()) translated.append('\n')
                translated.append(translatedSentence)
            }
        }
        return translated.toString()
    }

    /** 流结束：翻译尾部残句（可能没有句尾标点）。 */
    suspend fun flush(): String {
        val tail = buffer.toString().trim()
        buffer.setLength(0)
        if (tail.isEmpty()) return translated.toString()
        val t = runCatching { engine.translate(tail, src, tgt) }.getOrNull()
        if (!t.isNullOrBlank()) {
            if (translated.isNotEmpty()) translated.append('\n')
            translated.append(t)
        }
        return translated.toString()
    }

    companion object {
        /** 句子边界检测与模型兜底路径共享（见 [ModelStreamTranslator.sentenceBoundary]）。 */
        private fun sentenceEnd(text: StringBuilder): Int? = ModelStreamTranslator.sentenceBoundary(text)
    }
}
