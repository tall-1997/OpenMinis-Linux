package com.openminis.app.sandbox.offload

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult

/**
 * `minis-toast <text>` — show a short Android toast from the sandbox.
 * Harmless UI; not gated. Must post on the main looper.
 */
class ToastOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val text = args.positional.joinToString(" ").ifBlank {
            args.get("text").orEmpty()
        }
        if (text.isBlank()) {
            return NativeOffloadResult(2, "minis-toast: missing <text>\n$HELP")
        }
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
        }
        return NativeOffloadResult(0, OffloadOutput.formatBody("toast: $text", args) + "\n")
    }

    companion object {
        private const val HELP = """minis-toast — show a short Android toast

Usage:
  minis-toast <text>
  minis-toast --help
"""
    }
}
