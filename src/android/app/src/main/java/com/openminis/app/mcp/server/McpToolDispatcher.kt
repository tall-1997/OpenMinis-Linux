package com.openminis.app.mcp.server

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.security.Decision
import com.openminis.app.security.PermissionMode
import com.openminis.app.security.SecurityGate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tool implementation layer for the built-in MCP server.
 * Dispatches tools/call to the app's real capabilities.
 *
 * [gate] is injectable for tests; defaults to the process-wide SecurityGate.
 *
 * [shellMode] supplies the PermissionMode used by the shell_exec gate decision:
 * the settings UI exposes this as the shell policy (auto → ALLOW_ALL, strict
 * → ASK). Defaults to ALLOW_ALL so a bare dispatcher stays usable; the manager
 * always wires it to the persisted policy. NeedConfirm/Denied outcomes still
 * map to CallResult(errorCode=GATE_DENIED) — the MCP channel has no interactive
 * approval path.
 */
class McpToolDispatcher(
    // Nullable for JVM unit tests (plain-junit, no Robolectric): every
    // dereference below is guarded and degrades to an error result.
    private val context: Context?,
    private val gate: SecurityGate,
    private val shellMode: () -> PermissionMode = { PermissionMode.ALLOW_ALL },
) : McpServerCore.ToolDispatcher {

    companion object {
        const val SESSION_ID = "mcp-server"
        const val MAX_FILE_READ_CHARS = 80_000
        val FILE_READ_PREFIXES = listOf("/var/minis/", "/sdcard/")

        /** Strict-policy hint appended to shell_exec gate denial messages. */
        const val SHELL_STRICT_HINT =
            "当前 shell 策略为 strict，命令需要用户确认（MCP 通道无法弹确认）。" +
                "可在 设置 → MCP → 内置 Server 把 shell 策略切到 auto。"

        fun text(content: String): JSONObject = JSONObject().apply {
            put("type", "text")
            put("text", content)
        }

        /**
         * Pure path validation, exposed for unit tests. Returns null when the
         * normalized path is allowed, otherwise a deny reason string.
         */
        fun validateFilePath(rawPath: String): String? {
            val normalized = normalizePath(rawPath)
            if (normalized.isEmpty()) return "path is empty"
            val allowed = FILE_READ_PREFIXES.any { prefix ->
                val root = prefix.trimEnd('/')
                normalized == prefix || normalized == root || normalized.startsWith(prefix)
            }
            if (!allowed) {
                return "path '$normalized' is outside allowed prefixes (${FILE_READ_PREFIXES.joinToString()})"
            }
            // Reject traversal attempts that survived normalization.
            val segments = normalized.split('/')
            if (segments.any { it == ".." }) return "path contains '..'"
            return null
        }

        /** Normalize slashes and collapse '.'/'..' segments (no filesystem access). */
        fun normalizePath(path: String): String {
            var p = path.trim().replace('\\', '/')
            while (p.contains("//")) p = p.replace("//", "/")
            val segments = mutableListOf<String>()
            for (seg in p.split('/')) {
                when {
                    seg.isEmpty() || seg == "." -> Unit
                    seg == ".." -> {
                        if (segments.isNotEmpty() && segments.last() != "..") segments.removeLast()
                        else if (segments.isEmpty()) return "" // escaping above root
                    }
                    else -> segments.add(seg)
                }
            }
            val body = segments.joinToString("/")
            return when {
                !p.startsWith("/") -> body
                body.isEmpty() -> "/"
                p.endsWith("/") -> "/$body/"
                else -> "/$body"
            }
        }
    }

    override fun listTools(): JSONArray {
        return JSONArray().apply {
            put(
                JSONObject().apply {
                    put("name", "device_info")
                    put("description", "Get device information: model, manufacturer, Android version, battery level.")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "shell_exec")
                    put(
                        "description",
                        "Execute a command in an isolated Linux process (Ubuntu 24.04 arm64 via PRoot). " +
                            "The command runs in GNU bash (/bin/bash). stdout and stderr are merged. " +
                            "Use 'command' to specify the shell command, optional 'timeout_sec' to limit execution.",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("command", JSONObject().apply { put("type", "string") })
                            put("timeout_sec", JSONObject().apply { put("type", "integer") })
                        })
                        put("required", JSONArray().put("command"))
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "ui_read")
                    put("description", "Read a bounded snapshot of the current foreground screen via the accessibility tree.")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "ui_action")
                    put(
                        "description",
                        "Perform one UI action via the accessibility service: tap, type, swipe, scroll, back, open_app, open_url, long_press.",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("action", JSONObject().apply { put("type", "string") })
                            put("x", JSONObject().apply { put("type", "integer") })
                            put("y", JSONObject().apply { put("type", "integer") })
                            put("x2", JSONObject().apply { put("type", "integer") })
                            put("y2", JSONObject().apply { put("type", "integer") })
                            put("text", JSONObject().apply { put("type", "string") })
                            put("direction", JSONObject().apply { put("type", "string") })
                            put("package_name", JSONObject().apply { put("type", "string") })
                            put("url", JSONObject().apply { put("type", "string") })
                        })
                        put("required", JSONArray().put("action"))
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "file_read")
                    put(
                        "description",
                        "Read a file from the Linux filesystem. Restricted to /var/minis/ and /sdcard/ prefixes.",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("path", JSONObject().apply { put("type", "string") })
                        })
                        put("required", JSONArray().put("path"))
                    })
                },
            )
        }
    }

    override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult {
        return when (name) {
            "device_info" -> deviceInfo()
            "shell_exec" -> shellExec(arguments)
            "ui_read" -> uiRead()
            "ui_action" -> uiAction(arguments)
            "file_read" -> fileRead(arguments)
            else -> McpServerCore.CallResult(
                JSONArray().put(text("Unknown tool: $name")),
                isError = true,
            )
        }
    }

    // ─── device_info ───────────────────────────────────────────────────────

    private fun deviceInfo(): McpServerCore.CallResult {
        val battery = batteryPercent()
        val result = JSONObject().apply {
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("android_version", Build.VERSION.RELEASE)
            put("battery_percent", battery)
        }
        return McpServerCore.CallResult(
            JSONArray().put(text(result.toString(2))),
        )
    }

    private fun batteryPercent(): Int? {
        return runCatching {
            val bm = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()
    }

    // ─── shell_exec ────────────────────────────────────────────────────────

    private fun shellExec(arguments: JSONObject): McpServerCore.CallResult {
        val command = arguments.optString("command", "").ifEmpty {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'command' is required")),
                isError = true,
            )
        }
        val timeoutSec = arguments.optInt("timeout_sec", 60).coerceIn(1, 900)

        // Gate: classify + decide with the policy-driven mode (auto → ALLOW_ALL,
        // strict → ASK). No interactive approval on this channel — need-confirm /
        // denied both become a JSON-RPC error so the client gets an explicit verdict.
        val argsJson = JSONObject().put("command", command).toString()
        val gateCommand = gate.classify("shell_execute", argsJson)
        val mode = shellMode()
        val decision = gate.decide(gateCommand, mode)
        gate.audit(gateCommand, decision, null)
        when (decision) {
            is Decision.Denied -> {
                return McpServerCore.CallResult(
                    JSONArray().put(text("SecurityGate denied: ${decision.reason}")),
                    isError = true,
                    errorCode = McpServerCore.ErrorCode.GATE_DENIED,
                    errorData = decision.reason,
                )
            }
            is Decision.NeedConfirm -> {
                val preview = decision.preview.take(240).ifBlank { decision.reason }
                // Under strict the ask-for-approval verdict is expected policy
                // behavior, not an anomaly — append a hint pointing at the
                // settings toggle so MCP clients (and their humans) can recover.
                val policyHint = if (mode == PermissionMode.ASK) " $SHELL_STRICT_HINT" else ""
                return McpServerCore.CallResult(
                    JSONArray().put(
                        text(
                            "This command requires user approval which is not available on the " +
                                "MCP server channel. Reason: ${decision.reason}. Preview: $preview.$policyHint",
                        ),
                    ),
                    isError = true,
                    errorCode = McpServerCore.ErrorCode.GATE_DENIED,
                    errorData = decision.reason,
                )
            }
            is Decision.Allow -> {
                // proceed
            }
        }

        // Execute via ExecutionCoordinator in the server's own workspace.
        val result = runCatching {
            runBlocking {
                ExecutionCoordinator.execute(
                    sessionId = SESSION_ID,
                    command = command,
                    timeout = timeoutSec * 1000L,
                )
            }
        }.getOrElse {
            return McpServerCore.CallResult(
                JSONArray().put(text("Execution failed: ${it.message}")),
                isError = true,
            )
        }

        val output = JSONObject().apply {
            put("exit_code", result.exitCode)
            put("output", result.output)
            put("duration_ms", result.durationMs)
        }
        return McpServerCore.CallResult(
            JSONArray().put(text(output.toString(2))),
            isError = result.exitCode != 0,
        )
    }

    // ─── ui_read ───────────────────────────────────────────────────────────

    private fun uiRead(): McpServerCore.CallResult {
        val script = """
            if [ -x /usr/local/bin/android-a11y-cli ]; then
              android-a11y-cli read-screen 2>&1 | head -c 6000 || true
            else
              echo "android-a11y-cli not available in sandbox"
            fi
        """.trimIndent()
        return runHostShell(script)
    }

    // ─── ui_action ─────────────────────────────────────────────────────────

    private fun uiAction(arguments: JSONObject): McpServerCore.CallResult {
        val action = arguments.optString("action", "").lowercase()
        if (action.isEmpty()) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'action' is required (tap/type/swipe/scroll/back/open_app/open_url/long_press)")),
                isError = true,
            )
        }

        fun num(key: String): String = arguments.opt(key).let { v ->
            if (v == JSONObject.NULL || v == null) "" else v.toString()
        }

        val cmd: String = when (action) {
            "tap" -> "android-a11y-cli tap ${num("x")} ${num("y")}"
            "long_press" -> "android-a11y-cli long-press ${num("x")} ${num("y")}"
            "type" -> "android-a11y-cli type '" + arguments.optString("text").replace("'", "'\\''") + "'"
            "swipe" -> "android-a11y-cli swipe ${num("x")} ${num("y")} ${num("x2")} ${num("y2")}"
            "scroll" -> "android-a11y-cli scroll ${arguments.optString("direction", "down")}"
            "back" -> "android-a11y-cli back"
            "open_app" -> "android-shizuku-cli launch '" + arguments.optString("package_name").replace("'", "'\\''") + "'"
            "open_url" -> "android-open '" + arguments.optString("url").replace("'", "'\\''") + "'"
            else -> return McpServerCore.CallResult(
                JSONArray().put(text("Error: unknown action '$action'")),
                isError = true,
            )
        }

        val script = """
            if [ -x /usr/local/bin/android-a11y-cli ]; then
              $cmd 2>&1 || true
            else
              echo "android-a11y-cli not available in sandbox"
            fi
        """.trimIndent()
        return runHostShell(script)
    }

    private fun runHostShell(script: String): McpServerCore.CallResult {
        return runCatching {
            val process = ProcessBuilder("/bin/bash", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            McpServerCore.CallResult(
                JSONArray().put(text(out.trim().ifBlank { "(no output)" })),
            )
        }.getOrElse {
            McpServerCore.CallResult(
                JSONArray().put(text("Error: ${it.message}")),
                isError = true,
            )
        }
    }

    // ─── file_read ────────────────────────────────────────────────────────


    private fun fileRead(arguments: JSONObject): McpServerCore.CallResult {
        val rawPath = arguments.optString("path", "").ifEmpty {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'path' is required")),
                isError = true,
            )
        }

        validateFilePath(rawPath)?.let { reason ->
            return McpServerCore.CallResult(
                JSONArray().put(text("Path denied: $reason")),
                isError = true,
                errorCode = McpServerCore.ErrorCode.PATH_DENIED,
                errorData = reason,
            )
        }

        val normalized = normalizePath(rawPath)

        // Resolve the Linux path to a host file through the session workspace,
        // exactly like FileReadTool does — so /var/minis/workspace reads resolve
        // inside the mcp-server session tree, not some other chat's.
        val ctx = context
            ?: return McpServerCore.CallResult(
                JSONArray().put(text("Error: no host context available")),
                isError = true,
            )
        val file: File = PRootKernel.resolveSessionHostPath(SESSION_ID, normalized, ctx)
            ?: return McpServerCore.CallResult(
                JSONArray().put(text("Error: cannot resolve path '$normalized' (rootfs-only guest path?)")),
                isError = true,
            )

        if (!file.exists()) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: file not found: $normalized")),
                isError = true,
            )
        }
        if (file.isDirectory) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: path is a directory: $normalized")),
                isError = true,
            )
        }

        return runCatching {
            val size = file.length()
            // Binary detection on the first 8192 bytes
            val isBinary = file.inputStream().use { input ->
                val buf = ByteArray(minOf(8192, size.toInt()))
                val read = input.read(buf)
                if (read > 0) buf.take(read).any { it == 0.toByte() } else false
            }
            if (isBinary) {
                return McpServerCore.CallResult(
                    JSONArray().put(text("[$normalized | $size bytes | binary file — cannot display]")),
                )
            }
            val raw = file.readText()
            val content = if (raw.length > MAX_FILE_READ_CHARS) {
                raw.take(MAX_FILE_READ_CHARS) + "\n... (truncated at $MAX_FILE_READ_CHARS chars)"
            } else {
                raw
            }
            val header = "[$normalized | $size bytes]"
            McpServerCore.CallResult(JSONArray().put(text("$header\n$content")))
        }.getOrElse {
            McpServerCore.CallResult(
                JSONArray().put(text("Error reading file: ${it.message}")),
                isError = true,
            )
        }
    }
}
