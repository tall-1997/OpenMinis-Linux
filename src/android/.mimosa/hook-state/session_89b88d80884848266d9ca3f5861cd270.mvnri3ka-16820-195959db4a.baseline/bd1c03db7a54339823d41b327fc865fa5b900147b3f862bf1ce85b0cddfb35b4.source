package com.openminis.app.ui.chat

import com.openminis.app.agent.shell.BashismDetector
import com.openminis.app.agent.shell.BashismReminder
import com.openminis.app.agent.shell.OnDemandBash
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.SubAgentLane
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Mirror of iOS AIChatViewModel post-tool hook (Agent/Chat/AIChatViewModel.swift:5387 / :5408):
 * when the agent writes or edits a SKILL.md inside a `/skills/` directory
 * we ask SkillRepository to re-scan disk so the new skill is visible
 * immediately, without waiting for app restart.
 */
internal fun ChatViewModel.maybeReloadSkillsForPath(argsJson: String) {
    runCatching {
        val path = JSONObject(argsJson).optString("path", "")
        if (path.contains("/skills/") && path.endsWith("SKILL.md")) {
            skillRepository?.reloadFromDisk()
        }
    }
}

/** Sentinel returned by the bash wrapper when bash is missing at run time,
 *  distinct from a script that legitimately exits 127 (T-bash-on-demand M5). */
private const val BASH_MISSING_SENTINEL = 119

/** Wrap a script to run under bash via a guest-side self-written temp file
 *  (base64, single line, self-cleaning), guarding on `command -v bash` so a
 *  vanished bash is detected precisely for inline self-heal.
 *
 *  The whole wrapper runs inside a SUBSHELL `( … )`. This is load-bearing on
 *  Android: PersistentShell drives commands as `{cmd}; echo …_EXIT_$?…` and
 *  reads the exit code from that marker line. A bare `|| exit 119` would exit
 *  the persistent shell process itself BEFORE the marker echo runs, so no
 *  marker is emitted and PersistentShell.parseExitCode falls back to -1 —
 *  the M5 self-heal sentinel check (== 119 / 30464) then never matches and a
 *  vanished bash is never re-installed. Wrapping in a subshell makes
 *  `exit 119` leave only the subshell, so `$?` = 119 reaches the marker. */
private fun ChatViewModel.wrapForBash(script: String): String {
    // [T-heredoc-trailing-newline] A heredoc that ends the decoded file with
    // no trailing newline fails with "unexpected end of file". Guarantee one.
    val normalized = if (script.endsWith("\n")) script else script + "\n"
    val b64 = android.util.Base64.encodeToString(
        normalized.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    return "( command -v bash >/dev/null 2>&1 || exit $BASH_MISSING_SENTINEL; " +
        "printf %s '$b64' | base64 -d > /tmp/.minis-exec-\$\$.sh && " +
        "bash /tmp/.minis-exec-\$\$.sh; rc=\$?; rm -f /tmp/.minis-exec-\$\$.sh; exit \$rc )"
}

internal suspend fun ChatViewModel.executeShellCommand(
    argsJson: String,
    toolId: String,
    toolBlocks: MutableList<AssistantBlock>,
    assistantId: String,
    currentText: String,
): ToolExecutionResult {
    return try {
        val args = JSONObject(argsJson)
        var command = args.optString("command", "")
        val resourceClass = when (args.optString("resource_class", "auto")) {
            "auto", "" -> if (com.openminis.app.sandbox.SandboxResourceGate.isHeavy(command))
                com.openminis.app.sandbox.SandboxResourceGate.ResourceClass.HEAVY
                else com.openminis.app.sandbox.SandboxResourceGate.ResourceClass.AUTO
            "heavy" -> com.openminis.app.sandbox.SandboxResourceGate.ResourceClass.HEAVY
            else -> return ToolExecutionResult("Error: resource_class must be auto or heavy", false)
        }
        val timeoutSec = com.openminis.app.data.ToolLimitPrefs.resolveShellTimeoutSec(
            if (args.has("timeout")) args.optInt("timeout") else null,
        )
        val delaySec = args.optInt("delay", 0).coerceAtLeast(0)
        val toolTitle = args.optString("tool_title", "shell_execute")

        if (command.isBlank()) {
            return ToolExecutionResult("Error: 'command' is required", false, toolTitle = toolTitle)
        }
        val mounted = com.openminis.app.security.SecurityGateHolder.gate.sdcardMounted?.invoke() ?: true
        command = com.openminis.app.security.GuestMountPolicy.rewriteCompound(command, mounted)
        command = com.openminis.app.tools.WritePathGuard.wrapShellCommand(command)

        // [T-android-overlay-finalize item 1] Removed the
        // shell-specific status hack ("shell: $toolTitle"). Since the
        // dispatch loop (~5003) now surfaces `tool_title` in the overlay
        // label uniformly via SessionActivityTracker.updateToolStatus(
        // status, toolName, isRunning, toolTitle), the per-tool override
        // produced redundant "shell / shell: <title>" rows. Lifecycle
        // status ("Running: shell_execute") set by the dispatch loop is
        // sufficient.

        // Delay execution: block the agent flow without occupying the shell,
        // allowing other concurrent tasks to use it during the wait period.
        if (delaySec > 0) {
            for (remaining in delaySec downTo 1) {
                val idx = toolBlocks.indexOfFirst { it.id == toolId }
                if (idx >= 0) {
                    val mm = remaining / 60
                    val ss = remaining % 60
                    val countdown = if (mm > 0) String.format("%d:%02d", mm, ss) else "${ss}s"
                    toolBlocks[idx] = toolBlocks[idx].copy(content = "⏳ Waiting $countdown before executing...")
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, currentText, true, toolBlocks)
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
            val idx = toolBlocks.indexOfFirst { it.id == toolId }
            if (idx >= 0) {
                toolBlocks[idx] = toolBlocks[idx].copy(content = "")
            }
        }

        // [diag] sessionId vs realSessionId mismatch was the root cause
        // of the Chinese-emoji filename "disappears" bug. `activeSessionId`
        // resolves to the persisted id once `ensureSession()` has run, so
        // every shell runs in a directory that survives VM recreation.
        val dispatchSessionId = kotlin.coroutines.coroutineContext[SubAgentLane]?.id ?: activeSessionId
        android.util.Log.w("ShellExecDiag",
            "executeShell dispatch=$dispatchSessionId rawSessionId=$sessionId realSessionId=$realSessionId isDraft=$isDraft cmd=${command.take(120).replace('\n', ' ')}")

        // [T-bash-on-demand] Detect busybox-ash-incompatible bash syntax and,
        // if found, transparently install + switch to bash. Install time is
        // NOT charged against the command timeout (OnDemandBash has its own
        // budget). `command` is rewritten to the bash-wrapped form on the S/E
        // path; `bashReminder` is attached if we fall back to sh. Only this
        // agent path runs here; the in-app terminal is untouched.
        BashismDetector.ensureLoaded(context)
        val bashism = BashismDetector.detect(command)
        var bashReminder: String? = null
        val originalCommand = command
        var bashScript: String? = null   // set when we bash-wrapped; enables M5 self-heal retry
        if (bashism.needsBash) {
            val executor = OnDemandBash.Executor { c, t ->
                ExecutionCoordinator.execute(sessionId = dispatchSessionId, command = c, timeout = t).exitCode
            }
            when (val outcome = OnDemandBash.ensureBash(context, executor)) {
                is OnDemandBash.Outcome.Available -> {
                    if (bashism.mustSwitchInterpreter) {
                        // §3.2 M3: self-write the script in the guest (base64,
                        // single line, self-cleaning) and run it under bash.
                        // The `command -v bash || exit 119` guard detects a
                        // bash that vanished after our cache check (M5) so we
                        // can self-heal below instead of failing.
                        command = wrapForBash(command)
                        bashScript = originalCommand // remember for self-heal retry
                    }
                    // T1-only (script invokes bash itself) → run as-is under sh.
                }
                is OnDemandBash.Outcome.Unavailable ->
                    bashReminder = BashismReminder.build(bashism.hits, outcome.reason)
            }
        }

        val preview = ShellOutputPreview(
            kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext + Dispatchers.Main),
        ) { text ->
            val idx = toolBlocks.indexOfFirst { it.id == toolId }
            if (idx >= 0) {
                toolBlocks[idx] = toolBlocks[idx].copy(content = text)
                updateAssistantMessage(assistantId, currentText, true, toolBlocks)
            }
        }
        var result = try {
            ExecutionCoordinator.execute(
            sessionId = dispatchSessionId,
            command = command,
            timeout = timeoutSec * 1000L,
            resourceClass = resourceClass,
            lineCallback = lc@{ rawLine ->
                // Strip any OSC MinisOpenURL markers emitted by
                // /usr/local/bin/minis-open and forward the captured
                // URLs to the broker so the chat screen can present the
                // in-app preview. Lines that were *entirely* a marker
                // (nothing visible afterwards) are dropped so the tool
                // output doesn't grow blank rows.
                val (cleanedLine, capturedUrls) = MinisUrlMarker.extract(rawLine)
                for (raw in capturedUrls) MinisOpenUrlBroker.offer(raw)
                if (cleanedLine.isEmpty() && rawLine.isNotEmpty()) return@lc

                preview.append(cleanedLine)
            },
            ).also {
                withContext(Dispatchers.Main) { preview.finish() }
            }
        } finally {
            preview.cancel()
        }

        // [T-bash-on-demand] M5 self-heal: our bash wrapper returns sentinel
        // 119 when bash vanished (user apk del'd) after we cached it
        // available. Re-probe + reinstall once and rerun THIS command under
        // bash inline, so it still succeeds instead of failing.
        // Accept both the raw sentinel (119) and the wait(2)-encoded status
        // (119 << 8 = 30464) the coordinator may surface.
        if ((result.exitCode == BASH_MISSING_SENTINEL ||
                result.exitCode == (BASH_MISSING_SENTINEL shl 8)) && bashScript != null) {
            OnDemandBash.markDisappeared()
            val executor = OnDemandBash.Executor { c, t ->
                ExecutionCoordinator.execute(sessionId = dispatchSessionId, command = c, timeout = t).exitCode
            }
            val healed = OnDemandBash.ensureBash(context, executor)
            command = if (healed is OnDemandBash.Outcome.Available) wrapForBash(bashScript!!) else bashScript!!
            result = ExecutionCoordinator.execute(
                sessionId = dispatchSessionId, command = command, timeout = timeoutSec * 1000L,
                resourceClass = resourceClass)
        }

        // Also scrub markers from the aggregated one-shot output and
        // broker any URLs that only appeared there (defensive — handles
        // executors that don't fire lineCallback for every line).
        val (cleanedOutput, oneShotUrls) = MinisUrlMarker.extract(result.output)
        for (raw in oneShotUrls) MinisOpenUrlBroker.offer(raw)
        val output = if (cleanedOutput.isBlank()) "(no output)" else cleanedOutput
        val exitInfo = if (result.exitCode != 0) " (exit code ${result.exitCode})" else ""
        // Exit code 124 is the BusyBox/GNU timeout-utility convention for
        // a command that exceeded its budget. PersistentShell returns this
        // when its `withTimeoutOrNull(timeout)` wrapper fires.
        val timedOut = result.exitCode == 124

        // Redact env-var values that leaked into the captured output
        // before the model sees them. No-op when Privacy Mode is OFF.
        // Done after exitInfo is appended so the suffix can't accidentally
        // contain a secret that escaped masking. The user-visible streamed
        // content (toolBlocks above) is intentionally left unmasked.
        val finalOutput = "$output$exitInfo"
        val (redactedOut, redactHits) = com.openminis.app.data.EnvVarRedactor.redactIfEnabled(finalOutput)
        if (redactHits > 0) {
            android.util.Log.i("EnvVarRedact", "shell_execute: masked $redactHits env-var value(s) in tool result")
        }

        // [T-bash-on-demand] M5 self-heal: bash disappeared (user apk del'd)
        // → re-probe next time.
        if (result.exitCode == 127 && bashism.mustSwitchInterpreter) {
            OnDemandBash.markDisappeared()
        }
        // §4.2: append the bashism reminder when we fell back to sh and the
        // command failed OR any silent-class rule was hit (S-class exit-0
        // exception, default-on).
        val withReminder = bashReminder?.let { rem ->
            if (result.exitCode != 0 || bashism.hasSilent) "$redactedOut\n\n$rem" else redactedOut
        } ?: redactedOut

        ToolExecutionResult(
            output = withReminder,
            success = result.exitCode == 0,
            toolTitle = toolTitle,
            timedOut = timedOut,
        )
    } catch (e: Exception) {
        ToolExecutionResult("Error: ${e.message}", false)
    }
}

internal suspend fun ChatViewModel.executeBrowserUseTool(argsJson: String): ToolExecutionResult {
    val input = BrowserActionInput.parse(argsJson)
        ?: return ToolExecutionResult("Error: Invalid browser_use input", false)

    return try {
        val result = browserTabPool.execute(input)
        val toolTitle = try {
            JSONObject(argsJson).optString("tool_title", "browser_use")
        } catch (_: Exception) { "browser_use" }

        var output = result.text
        var persistentImagePath: String? = result.imageFilePath
        var inferenceBytes: ByteArray? = null

        // Persist browser screenshots to /var/minis/browser/<session>/ so the
        // agent can reference them via minis:// in subsequent tool calls
        // (mirrors iOS AIChatViewModel case "browser_use").
        val base64 = result.base64Image
        var linuxImagePath: String? = null
        if (base64 != null) {
            val raw = try {
                android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            } catch (_: Exception) { null }
            if (raw != null) {
                // Anthropic supports up to 8000×8000 / 5MB; we standardize at 2000
                // long edge across attachments / browser / read_image.
                inferenceBytes = resizeJpegToMaxEdge(raw, 2000) ?: raw
                val filename = "screenshot_${System.currentTimeMillis() / 1000}.jpg"
                val persistPath = persistBrowserArtifact(filename, raw)
                if (persistPath != null) {
                    persistentImagePath = persistPath
                    linuxImagePath = "/var/minis/browser/$filename"
                    linuxPathToMinisURL(linuxImagePath)?.let {
                        output = "$output\nminis_url: $it"
                    }
                }
            }
        }

        // Persist fetched files (fetch action) and append minis_url
        val fetchData = result.fetchedFileData
        val fetchName = result.fetchedFileName
        if (fetchData != null && fetchName != null) {
            persistBrowserArtifact(fetchName, fetchData)
            linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {
                output = "$output\nminis_url: $it"
            }
        }

        ToolExecutionResult(
            output = output,
            success = result.success,
            imageData = inferenceBytes,
            imageMimeType = if (inferenceBytes != null) "image/jpeg" else null,
            toolTitle = toolTitle,
            pageURL = result.pageURL,
            imageFilePath = persistentImagePath,
            imageLinuxPath = linuxImagePath,
        )
    } catch (e: Exception) {
        ToolExecutionResult("Error: ${e.message}", false)
    }
}

/**
 * Write bytes to <filesDir>/minis-sessions/<sessionId>/browser/<filename>.
 * That directory is bind-mounted to `/var/minis/browser/` so the agent can
 * read it back via file_read / file_write / minis:// URLs.
 * Returns the host absolute path on success, null otherwise.
 */
private fun ChatViewModel.persistBrowserArtifact(filename: String, data: ByteArray): String? {
    val sid = activeSessionId.takeIf { it.isNotEmpty() } ?: return null
    return try {
        val dir = com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sid, "browser").apply { mkdirs() }
        val file = java.io.File(dir, filename)
        file.writeBytes(data)
        file.absolutePath
    } catch (e: Exception) {
        android.util.Log.w("ChatViewModel", "persistBrowserArtifact failed: ${e.message}")
        null
    }
}

/**
 * Convert a Linux path under /var/minis/ to a percent-encoded minis:// URL.
 * Mirrors iOS AIChatViewModel.linuxPathToMinisURL.
 */
private fun ChatViewModel.linuxPathToMinisURL(path: String): String? {
    val prefix = "/var/minis/"
    if (!path.startsWith(prefix)) return null
    val rest = path.removePrefix(prefix)
    val slash = rest.indexOf('/')
    if (slash < 0) return null
    val namespace = rest.substring(0, slash)
    val filename = rest.substring(slash + 1)
    val encoded = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
    return "minis://$namespace/$encoded"
}

/**
 * Resize a JPEG so its longest edge is at most `maxEdge` px. Returns null
 * if already within bounds. Mirrors iOS AIChatViewModel.resizedImageData.
 */
private fun ChatViewModel.resizeJpegToMaxEdge(data: ByteArray, maxEdge: Int): ByteArray? {
    val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
    val longest = maxOf(bmp.width, bmp.height)
    if (longest <= maxEdge) { bmp.recycle(); return null }
    val scale = maxEdge.toFloat() / longest
    val w = (bmp.width * scale).toInt()
    val h = (bmp.height * scale).toInt()
    val resized = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
    bmp.recycle()
    val out = java.io.ByteArrayOutputStream()
    resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
    resized.recycle()
    return out.toByteArray()
}

internal fun ChatViewModel.executeMemoryWriteTool(argsJson: String): ToolExecutionResult {
    val repo = sessionMemoryRepo()
    if (!_memoryEnabled.value) {
        val msg = "Memory writes are disabled for this session (user toggled /memory off). Reads remain available."
        return ToolExecutionResult(msg, false, toolTitle = "Memory (disabled)")
    }
    val result = MemoryTools.executeMemoryWrite(argsJson, repo)
    // Record for SessionMemorySheet
    val content = try {
        JSONObject(argsJson).optString("content", "")
    } catch (_: Exception) { "" }
    _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
        title = result.toolTitle,
        isWrite = true,
        preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
        output = result.output,
        writtenContent = content,
    )
    return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
}

internal fun ChatViewModel.executeMemoryGetTool(argsJson: String): ToolExecutionResult {
    val repo = sessionMemoryRepo()
    val result = MemoryTools.executeMemoryGet(argsJson, repo)
    val keywords = try {
        JSONObject(argsJson).optString("keywords", "")
    } catch (_: Exception) { "" }
    _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
        title = result.toolTitle,
        isWrite = false,
        preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
        output = result.output,
        keywords = keywords,
    )
    return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
}
