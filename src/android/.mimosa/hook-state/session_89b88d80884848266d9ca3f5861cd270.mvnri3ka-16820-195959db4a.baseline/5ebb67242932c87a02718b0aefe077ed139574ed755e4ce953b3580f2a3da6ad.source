package com.openminis.app.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Reader-thread producer; one UI consumer, bounded text, at most one pending signal. */
internal class ShellOutputPreview(
    scope: CoroutineScope,
    private val intervalMs: Long = 150,
    private val maxChars: Int = 32 * 1024,
    private val maxLines: Int = 50,
    private val render: (String) -> Unit,
) {
    private val lines = ArrayDeque<String>()
    private var chars = 0
    private var dirty = false
    private var closed = false
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private val consumer: Job = scope.launch {
        for (ignored in changed) {
            delay(intervalMs)
            flush()
        }
    }

    init {
        require(maxChars > 0 && maxLines > 0 && intervalMs > 0)
    }

    @Synchronized
    fun append(line: String) {
        if (closed) return
        val bounded = line.takeLast(maxChars)
        lines.addLast(bounded)
        chars += bounded.length + 1
        while (lines.size > 1 && (lines.size > maxLines || chars - 1 > maxChars)) {
            chars -= lines.removeFirst().length + 1
        }
        dirty = true
        changed.trySend(Unit)
    }

    private fun flush() {
        val text = synchronized(this) {
            if (!dirty) return
            dirty = false
            lines.joinToString("\n")
        }
        render(text)
    }

    /** Called on the render dispatcher after the producer has finished. */
    suspend fun finish() {
        synchronized(this) { closed = true }
        consumer.cancelAndJoin()
        changed.close()
        flush()
    }

    fun cancel() {
        synchronized(this) {
            closed = true
            lines.clear()
            chars = 0
            dirty = false
        }
        changed.close()
        consumer.cancel()
    }
}
