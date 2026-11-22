package com.openminis.app.crash

import android.content.Context
import android.util.Log
import java.io.File

/**
 * [T-degraded-cause-diagnostics] Records WHICH startup step degraded the app.
 *
 * Why: the degraded-mode banner shows a cause, but until now that cause only
 * lived in memory + one logcat line. A crash zip keeps ~200 logcat lines, so by
 * the time the user reports "app crashed / runs degraded" the reason is gone and
 * support has to guess. A degraded run usually produces no crash report at all,
 * so ACRA custom data would never travel either.
 *
 * The file lands in filesDir/logs, which is exactly what the "send logs" picker
 * ships — so the next report self-documents.
 */
internal object StartupDegradedLog {

    const val FILE_NAME = "startup-degraded.log"

    fun record(context: Context, stage: String, t: Throwable) {
        val cause = buildString {
            append(stage).append(": ").append(t.javaClass.simpleName)
            t.message?.take(200)?.let { append(": ").append(it) }
        }
        Log.e("MinisApp", "DEGRADED_STARTUP cause=$cause")
        runCatching {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            File(dir, FILE_NAME).writeText(
                buildString {
                    appendLine("stage=" + stage)
                    appendLine("cause=" + cause)
                    appendLine(t.stackTraceToString().take(4000))
                },
            )
        }
    }
}
