package com.openminis.app.i18n

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [T-model-stream-translate] 模型兜底的实时翻译器：句级缓冲 + 异步批量翻译。
 * 与 [StreamTranslator]（ML Kit 毫秒级同步）不同，LLM 翻译是秒级网络请求，
 * 不能在流消费循环里同步等——这里 feed 只切句入队，翻译在注入的 scope 里
 * 异步跑，完成后经 [onUpdate] 回调通知调用方刷新覆盖层。
 *
 * 批内句子并发翻译（async），批间串行（in-flight 合并：feed 在翻译进行中
 * 只入队不追加请求）。[translatedSoFar] 供 materialize 时挂块。
 */
class ModelStreamTranslator(
    private val scope: CoroutineScope,
    private val translateBatch: suspend (List<String>) -> List<String?>,
    private val onUpdate: (suspend (String) -> Unit)? = null,
) {
    private val lock = Any()
    private val queue = ArrayList<String>()
    private val translated = StringBuilder()
    private var job: Job? = null

    val translatedSoFar: String
        get() = synchronized(lock) { translated.toString() }

    /** 流式喂入 delta：切句入队，触发异步翻译（非阻塞）。 */
    fun feed(delta: String) {
        if (delta.isEmpty()) return
        val ready = ArrayList<String>(2)
        synchronized(lock) {
            buffer.append(delta)
            while (true) {
                val end = sentenceBoundary(buffer) ?: break
                val sentence = buffer.substring(0, end).trim()
                buffer.delete(0, end)
                if (sentence.isNotEmpty()) {
                    queue.add(sentence)
                    ready.add(sentence)
                }
            }
        }
        if (ready.isNotEmpty()) kick()
    }

    /** 流结束：buffer 残句 + 调用方尾句入队，等待在飞翻译完成。 */
    suspend fun flush(tail: String = "") {
        synchronized(lock) {
            if (buffer.isNotEmpty()) {
                val rest = buffer.toString().trim()
                buffer.clear()
                if (rest.isNotEmpty()) queue.add(rest)
            }
            if (tail.isNotBlank()) queue.add(tail.trim())
        }
        kick()
        job?.join()
        // join 后队列仍可能有货（kick 时 job 已在收尾）——直接同步消费。
        val rest = synchronized(lock) { queue.toList().also { queue.clear() } }
        if (rest.isNotEmpty()) translateAndAppend(rest)
    }

    /** 取消在飞翻译（重试回滚时丢弃本轮翻译状态）。 */
    fun cancel() {
        synchronized(lock) {
            job?.cancel()
            job = null
            queue.clear()
            buffer.setLength(0)
        }
    }

    private fun kick() {
        synchronized(lock) {
            if (queue.isEmpty() || job?.isActive == true) return
            job = scope.launch { drain() }
        }
    }

    private suspend fun drain() {
        while (true) {
            val batch = synchronized(lock) {
                if (queue.isEmpty()) return
                queue.toList().also { queue.clear() }
            }
            translateAndAppend(batch)
        }
    }

    private suspend fun translateAndAppend(batch: List<String>) {
        val results = runCatching { translateBatch(batch) }.getOrElse { batch.map { null } }
        if (results.isNotEmpty()) {
            synchronized(lock) {
                results.forEach { r ->
                    if (!r.isNullOrBlank()) {
                        if (translated.isNotEmpty()) translated.append('\n')
                        translated.append(r)
                    }
                }
            }
            onUpdate?.invoke(translatedSoFar)
        }
    }

    private val buffer = StringBuilder()

    companion object {
        private val SENTENCE_END = charArrayOf('。', '！', '？', '!', '?', '\n')

        /**
         * 第一个句子边界（句尾标点 + 可选的引号/右括号跟随）之后的下标。
         * 英文句号带空格前瞻（"One. Two" 切，"3.14" 不切）。
         */
        internal fun sentenceBoundary(text: StringBuilder): Int? {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val isEnd = c in SENTENCE_END ||
                    (c == '.' && i + 1 < text.length && text[i + 1] == ' ')
                if (isEnd) {
                    var end = i + 1
                    while (end < text.length && text[end] in "\"》）】”』」)】]") end++
                    return end
                }
                i++
            }
            return null
        }
    }
}
