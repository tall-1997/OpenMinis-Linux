package com.openminis.app.sandbox

import com.openminis.app.sandbox.kernel.TokenBucket

/**
 * 实时行回调的限速与 CR 覆盖处理（从 [PersistentShell.feedLines] 拆出）。
 *
 * [T-line-budget-sync] 字节预算 512 KiB/s；250 行/s（约 200 KiB/s 典型文本）
 * 让实时进度留出突发余量。捕获输出不受影响（snapshot 保留全部）。
 */
internal class ShellLineFeeder {

    private val lineBudget = TokenBucket(ratePerSec = 250.0, burst = 500)
    private val lineCallbackLock = Any()

    fun feed(text: String, callback: (String) -> Unit) {
        val lines = text.split('\n')
        if (!lineBudget.tryTake(System.currentTimeMillis())) {
            // Over budget. Dropping the whole chunk froze the live stream view
            // at an older frame for the length of the burst; keep only the
            // newest emitted-worthy line(s) so a progress meter still shows its
            // latest state. Captured output is untouched.
            val first = maxOf(0, lines.size - 2)
            for (i in first until lines.size) {
                val line = lines[i].split('\r').lastOrNull { it.isNotEmpty() }.orEmpty()
                val complete = i < lines.size - 1 || text.endsWith('\n')
                if (line.isNotEmpty() && (complete || i == lines.size - 1)) {
                    emitLine(callback, line)
                }
            }
            return
        }
        for (i in lines.indices) {
            // CR overwrites a progress meter. Keep the latest non-empty
            // segment so "50%\r51%\r" becomes "51%", not "5051".
            val line = lines[i].split('\r').lastOrNull { it.isNotEmpty() }.orEmpty()
            if (line.isNotEmpty() && (i < lines.size - 1 || text.endsWith('\n'))) {
                emitLine(callback, line)
            } else if (line.isNotEmpty() && i == lines.size - 1) {
                // Partial line — still feed it for real-time updates.
                emitLine(callback, line)
            }
        }
    }

    /** lineCallback may come from the reader thread and the heartbeat job. */
    internal fun emitLine(callback: (String) -> Unit, line: String) {
        synchronized(lineCallbackLock) { callback(line) }
    }
}
