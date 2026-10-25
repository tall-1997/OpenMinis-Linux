package com.openminis.app.sandbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Speaks when a guest command produces no user-visible output.
 *
 * apt and curl hide their meters when stdout is not a tty, and the chat card
 * only refreshes on newlines. A download then looks frozen until the process
 * exits. This heartbeat is preview-only: it must not be written into the
 * command's captured output.
 */
internal class SilentOutputHeartbeat(
    val intervalMs: Long = 6_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val startedAt = AtomicLong(0L)
    private val lastOutputAt = AtomicLong(0L)
    private val lastBeatAt = AtomicLong(0L)

    fun start(nowMs: Long = now()) {
        startedAt.set(nowMs)
        lastOutputAt.set(nowMs)
        lastBeatAt.set(nowMs)
    }

    fun onOutput(nowMs: Long = now()) {
        lastOutputAt.set(nowMs)
    }

    fun tick(nowMs: Long = now()): String? {
        val beat = lastBeatAt.get()
        if (nowMs - beat < intervalMs) return null
        if (!lastBeatAt.compareAndSet(beat, nowMs)) return null
        if (nowMs - lastOutputAt.get() < intervalMs) return null
        val waited = (nowMs - startedAt.get()) / 1000L
        return "…仍在运行，已等待 ${waited}s，这一步没有新输出（下载或解包时属正常）"
    }
}

internal fun CoroutineScope.launchSilentHeartbeat(
    heartbeat: SilentOutputHeartbeat,
    stillCurrent: () -> Boolean,
    emit: (String) -> Unit,
): Job {
    heartbeat.start()
    return launch {
        while (isActive && stillCurrent()) {
            delay(heartbeat.intervalMs)
            if (!isActive || !stillCurrent()) break
            heartbeat.tick()?.let(emit)
        }
    }
}
