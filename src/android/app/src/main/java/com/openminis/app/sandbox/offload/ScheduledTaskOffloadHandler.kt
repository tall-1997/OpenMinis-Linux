package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.scheduled.CronScheduler
import com.openminis.app.scheduled.PrefilledToolCall
import com.openminis.app.scheduled.ScheduledModelResolver
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledSessionLookup
import com.openminis.app.scheduled.ScheduledTargetDelivery
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTriggerKind
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * [T-android-scheduled-tasks-full] minis-scheduled — CLI surface for the
 * Scheduled Tasks feature, exposing the same option set as the editor UI and
 * the iOS Shortcuts intents so the agent can create / inspect / fire timed
 * AI actions from a prompt.
 *
 *   minis-scheduled list
 *   minis-scheduled create --label L --time HH:MM --prompt "..."
 *                          [--repeat once|daily|weekdays|custom --days mon,tue,...]
 *                          [--target new|follow-up|rerun]
 *                          [--session <id>] [--message <id>]
 *                          [--model <modelId>]
 *                          [--start YYYY-MM-DD] [--end YYYY-MM-DD]
 *                          [--command 'shell command'] [--command-timeout 2m]
 *                          [--tool <name> --tool-args '<json>']
 *                          [--disabled]
 *   minis-scheduled delete --id <taskId>
 *   minis-scheduled enable  --id <taskId>
 *   minis-scheduled disable --id <taskId>
 *   minis-scheduled run     --id <taskId>     (fire immediately, off-schedule)
 *
 * Target modes mirror iOS App Intents:
 *   new        ≈ SendPrompt(no session)   — run prompt in a fresh chat
 *   follow-up  ≈ FollowUpSession           — append prompt to --session
 *   rerun      ≈ RetryRun                  — re-run --session from --message
 *
 * [T-android-scheduled-default-follow-up] Without --target, a create that runs
 * inside a chat defaults to follow-up on THAT chat; outside a chat it stays
 * new. Create always prints a `delivery` sentence saying where fires land.
 */
class ScheduledTaskOffloadHandler(private val context: Context) : NativeOffloadHandler {

    private val manager get() = ScheduledTaskManager(context)

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(
            request.argv.drop(1),
            booleanFlags = setOf("disabled", "h", "help", "allow-unknown-model"),
        )
        // [T-offload-defaults-batch-android] Help only on explicit
        // --help/-h; no subcommand defaults to `list`.
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        return try {
            when (val sub = args.positional.firstOrNull() ?: "list") {
                "list" -> handleList()
                "create", "add" -> handleCreate(args, request.sessionId)
                "delete", "remove", "rm" -> handleDelete(args)
                "enable" -> handleSetEnabled(args, true)
                "disable" -> handleSetEnabled(args, false)
                "run" -> handleRun(args)
                else -> NativeOffloadResult(2, "minis-scheduled: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: IllegalArgumentException) {
            NativeOffloadResult(2, "minis-scheduled: ${e.message}")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "handle failed: ${e.message}")
            NativeOffloadResult(1, "minis-scheduled: ${e.message}")
        }
    }

    private fun handleList(): NativeOffloadResult {
        val arr = JSONArray()
        for (t in manager.list()) arr.put(taskJson(t))
        val out = JSONObject().put("tasks", arr).put("count", arr.length())
        return NativeOffloadResult(0, out.toString(2))
    }

    private fun handleCreate(args: OffloadArgs, callerSessionId: String?): NativeOffloadResult {
        val label = args.get("label", "l") ?: ""
        // [T-android-scheduled-triggers] `--every` is the interval trigger,
        // `--of` the on-completion one, `--time --repeat` the calendar one.
        // They are the same field set the in-app editor and the cron tool write,
        // so the CLI can no longer mint the dead row `--repeat interval` used
        // to (no intervalMinutes was ever set here, so it never fired).
        val ofRaw = args.get("of")
        if (ofRaw != null && args.get("every") != null) {
            throw IllegalArgumentException("--of and --every are different triggers; pass one")
        }
        val everySec = if (ofRaw != null) null else args.get("every")?.let { parseEvery(it) }
        if ((everySec != null || ofRaw != null) && (args.get("start") != null || args.get("end") != null)) {
            throw IllegalArgumentException("--start/--end describe a calendar window; they do not apply to --every/--of")
        }
        val repeat = if (everySec != null || ofRaw != null) ScheduledRepeatMode.ONCE
        else parseRepeat(args.get("repeat", "r"))
        val timeArg = args.get("time", "t")
        val (hour, minute) = when {
            timeArg != null -> parseTime(timeArg)
            everySec != null || ofRaw != null -> 0 to 0
            else -> throw IllegalArgumentException("--time HH:MM required (or --every 30m / --of <taskId>)")
        }
        val customDays = if (repeat == ScheduledRepeatMode.CUSTOM) {
            parseDays(args.get("days") ?: throw IllegalArgumentException("--days required for custom repeat"))
        } else emptySet()
        // [T-android-scheduled-default-follow-up] Where fires land is settled
        // here, at create time — see resolveTarget.
        val (target, defaulted) = resolveTarget(args, callerSessionId)
        val prompt = args.get("prompt", "p") ?: ""
        if (target !is ScheduledTargetMode.RerunMessage && prompt.isBlank()) {
            throw IllegalArgumentException("--prompt required (except for rerun target)")
        }
        // [T-scheduled-tool-prefill] --command / --tool make the task run a
        // fixed call before the model is asked. Refused up front (exit 2) for
        // the same reason --model is resolved up front: a bad call must not
        // save cleanly and then fail on every unattended fire.
        val prefill = parsePrefill(args, target, label)
        val now = System.currentTimeMillis()
        val task = ScheduledTask(
            label = label,
            timeOfDayHour = hour,
            timeOfDayMinute = minute,
            repeatMode = repeat,
            customDays = customDays,
            prompt = prompt,
            targetMode = target,
            modelId = resolveModelArg(args),
            enabled = !args.hasFlag("disabled"),
            startDateMs = args.get("start")?.let { parseDate(it) },
            endDateMs = args.get("end")?.let { parseDate(it) },
            triggerKind = when {
                ofRaw != null -> ScheduledTriggerKind.ON_COMPLETION
                everySec != null -> ScheduledTriggerKind.INTERVAL
                else -> ScheduledTriggerKind.CALENDAR
            },
            intervalSec = everySec,
            anchorMs = if (everySec != null) now else null,
            triggeredCount = if (everySec != null) 0 else null,
            // Resolved now, like --model: a typo must fail at creation instead
            // of producing a task that waits for an id that does not exist.
            onCompletionOf = ofRaw?.let { raw ->
                manager.resolveId(raw) ?: throw IllegalArgumentException("no task with id=$raw")
            },
            prefillToolCall = prefill,
        )
        val saved = manager.create(task)
        val destination = destinationOf(target, label, callerSessionId)
        val out = JSONObject()
            .put("created", taskJson(saved))
            .put("delivery", ScheduledTargetDelivery.sentence(destination, defaulted))
            .put("deliveryTarget", JSONObject(ScheduledTargetDelivery.summary(destination, defaulted)))
        if (saved.id != task.id) out.put("already_existed", true)
        return NativeOffloadResult(0, out.toString(2))
    }

    private fun requireId(args: OffloadArgs): String {
        val raw = args.get("id") ?: throw IllegalArgumentException("--id required")
        return manager.resolveId(raw)
            ?: throw IllegalArgumentException("minis-scheduled: no task with id=$raw")
    }

    private fun handleDelete(args: OffloadArgs): NativeOffloadResult {
        val id = requireId(args)
        if (!manager.delete(id) || manager.get(id) != null) {
            return NativeOffloadResult(1, "minis-scheduled: delete did not persist for id=$id")
        }
        return NativeOffloadResult(0, JSONObject().put("deleted", id).toString())
    }

    private fun handleSetEnabled(args: OffloadArgs, enabled: Boolean): NativeOffloadResult {
        val id = requireId(args)
        if (manager.get(id) == null) return NativeOffloadResult(1, "minis-scheduled: no task with id=$id")
        manager.setEnabled(id, enabled)
        return NativeOffloadResult(0, JSONObject().put("id", id).put("enabled", enabled).toString())
    }

    private fun handleRun(args: OffloadArgs): NativeOffloadResult {
        val raw = args.get("id") ?: throw IllegalArgumentException("--id required")
        val id = manager.resolveId(raw) ?: return NativeOffloadResult(1, "minis-scheduled: no task with id=$raw")
        val task = manager.get(id) ?: return NativeOffloadResult(1, "minis-scheduled: no task with id=$id")
        // Fire immediately, off-schedule. Blocks until the agent loop finishes
        // (ScheduledAgentRunner waits internally). Mirrors the editor "Run now".
        val sessionId = runBlocking {
            com.openminis.app.scheduled.ScheduledAgentRunner.run(context, task)
        }
        val out = JSONObject()
            .put("id", id)
            .put("ran", sessionId != null)
            .apply { if (sessionId != null) put("sessionId", sessionId) }
        return NativeOffloadResult(if (sessionId != null) 0 else 1, out.toString(2))
    }

    // ── parsing helpers ──

    /**
     * [T-android-scheduled-model-resolve] Resolve `--model` against the
     * configured models NOW.
     *
     * Deferring this to fire time meant a typo saved cleanly and then failed
     * every unattended run — the exact opposite of what a scheduler is for.
     * Accepts a wire model id, an entry uuid or an unambiguous display name and
     * stores the canonical wire id.
     *
     * Returns null when `--model` was not given (the task keeps the runtime
     * default), and throws [IllegalArgumentException] — surfaced as exit code 2
     * by [handle] — for an unknown value, unless `--allow-unknown-model` was
     * passed for a model that will exist after a later provider refresh.
     */
    private fun resolveModelArg(args: OffloadArgs): String? {
        val raw = args.get("model", "m")
        val entries = (context.applicationContext as? com.openminis.app.MinisApp)
            ?.providerRepositoryOrNull?.config?.value?.modelEntries.orEmpty()
        return when (val resolved = ScheduledModelResolver.resolve(raw, entries)) {
            ScheduledModelResolver.Result.Unspecified -> null
            is ScheduledModelResolver.Result.Resolved -> resolved.modelId
            is ScheduledModelResolver.Result.Unknown ->
                if (args.hasFlag("allow-unknown-model")) {
                    raw
                } else {
                    val known = resolved.known.ifEmpty { listOf("(none configured)") }.joinToString(", ")
                    throw IllegalArgumentException(
                        "--model '${resolved.raw}' is not a configured model. Known ids: $known. " +
                            "Pass --allow-unknown-model to store it verbatim.",
                    )
                }
        }
    }

    private fun parseTime(s: String): Pair<Int, Int> {
        val parts = s.split(":")
        require(parts.size == 2) { "--time must be HH:MM" }
        val h = parts[0].toIntOrNull()?.takeIf { it in 0..23 }
            ?: throw IllegalArgumentException("bad hour in --time")
        val m = parts[1].toIntOrNull()?.takeIf { it in 0..59 }
            ?: throw IllegalArgumentException("bad minute in --time")
        return h to m
    }

    private fun parseRepeat(s: String?): ScheduledRepeatMode = when (s?.lowercase()) {
        null, "once" -> ScheduledRepeatMode.ONCE
        "daily" -> ScheduledRepeatMode.DAILY
        "weekdays" -> ScheduledRepeatMode.WEEKDAYS
        "custom" -> ScheduledRepeatMode.CUSTOM
        // One way to say "every N": --every. Point at it instead of silently
        // creating a row that never fires.
        "interval" -> throw IllegalArgumentException("use --every 30m for an interval (--repeat describes calendar days)")
        else -> throw IllegalArgumentException("--repeat must be once|daily|weekdays|custom")
    }

    /** Seconds between fires for `--every 30m` / `--every 2h` / `--every 1d`. */
    private fun parseEvery(s: String): Long {
        val parsed = CronScheduler.parseSchedule("every $s")
            ?: throw IllegalArgumentException("--every must look like 30m, 2h or 1d")
        return parsed.intervalMinutes * 60L
    }

    private fun parseDays(s: String): Set<Int> {
        val map = mapOf(
            "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY,
            "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY,
            "sat" to Calendar.SATURDAY,
        )
        return s.split(",").mapNotNull { map[it.trim().lowercase().take(3)] }.toSet()
    }

    /**
     * [T-android-scheduled-default-follow-up] The target mode and whether it
     * was defaulted. An explicit follow-up to a session that does not exist is
     * an error (instead of a failure on every fire); a DEFAULTED follow-up
     * whose caller session is gone falls back to `new`.
     */
    private fun resolveTarget(args: OffloadArgs, callerSessionId: String?): Pair<ScheduledTargetMode, Boolean> {
        val session = args.get("session", "s")
        val (target, defaulted) = ScheduledTargetDelivery.resolveTarget(args.get("target"), session, callerSessionId)
        val lookup = ScheduledSessionLookup(context)
        return when (target) {
            "new" -> ScheduledTargetMode.NewSession to defaulted
            "follow-up", "followup", "follow" -> {
                val sid = session ?: callerSessionId
                    ?: throw IllegalArgumentException("--session required for follow-up target")
                when {
                    lookup.exists(sid) -> ScheduledTargetMode.AppendToSession(sid) to defaulted
                    defaulted -> ScheduledTargetMode.NewSession to true
                    else -> throw IllegalArgumentException("no chat session with id=$sid for the follow-up target")
                }
            }
            "rerun", "re-run", "retry" -> {
                val sid = session ?: throw IllegalArgumentException("--session required for rerun target")
                val mid = args.get("message")
                    ?: throw IllegalArgumentException("--message required for rerun target")
                ScheduledTargetMode.RerunMessage(sid, mid) to defaulted
            }
            else -> throw IllegalArgumentException("--target must be follow-up|new|rerun")
        }
    }

    /** The saved target, enriched with the chat's name for the delivery sentence. */
    private fun destinationOf(
        target: ScheduledTargetMode,
        label: String,
        callerSessionId: String?,
    ): ScheduledTargetDelivery.Destination {
        val lookup = ScheduledSessionLookup(context)
        return when (target) {
            is ScheduledTargetMode.AppendToSession -> ScheduledTargetDelivery.Destination.FollowUp(
                target.sessionId, lookup.title(target.sessionId), target.sessionId == callerSessionId,
            )
            is ScheduledTargetMode.RerunMessage -> ScheduledTargetDelivery.Destination.Rerun(
                target.sessionId, target.messageId, lookup.title(target.sessionId),
            )
            ScheduledTargetMode.NewSession -> ScheduledTargetDelivery.Destination.NewChat(label.ifBlank { null })
        }
    }

    /** YYYY-MM-DD → local start-of-day epoch ms. */
    private fun parseDate(s: String): Long {
        val parts = s.split("-")
        require(parts.size == 3) { "--start/--end must be YYYY-MM-DD" }
        val y = parts[0].toInt(); val mo = parts[1].toInt(); val d = parts[2].toInt()
        return Calendar.getInstance().apply {
            clear(); set(y, mo - 1, d, 0, 0, 0)
        }.timeInMillis
    }

    private fun taskJson(t: ScheduledTask): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("label", t.label)
        put("time", "%02d:%02d".format(t.timeOfDayHour, t.timeOfDayMinute))
        put("trigger", t.triggerKind.name.lowercase())
        // `repeat` only ever described calendar rows.
        if (t.isCalendar) put("repeat", t.repeatMode.name.lowercase())
        t.intervalSec?.let { put("intervalSec", it) }
        t.maxFires?.let { put("maxFires", it) }
        t.onCompletionOf?.let { put("of", it) }
        if (!t.isCalendar) put("firesSoFar", t.firesSoFar)
        if (t.customDays.isNotEmpty()) put("days", JSONArray(t.customDays.toList()))
        put("prompt", t.prompt)
        put("target", t.targetMode.encode())
        if (t.modelId != null) put("model", t.modelId)
        put("enabled", t.enabled)
        t.nextTriggerMs()?.let { put("nextTriggerMs", it) }
        if (t.startDateMs != null) put("startDateMs", t.startDateMs)
        if (t.endDateMs != null) put("endDateMs", t.endDateMs)
        if (t.lastFiredAt != null) put("lastFiredAt", t.lastFiredAt)
        if (t.lastResultPreview != null) put("lastResultPreview", t.lastResultPreview)
        if (t.lastResultSessionId != null) put("lastResultSessionId", t.lastResultSessionId)
        // [T-scheduled-tool-prefill] What the fire runs before the model is
        // asked; absent for ordinary tasks.
        t.prefillToolCall?.let { put("prefill", JSONObject().put("tool", it.toolName).put("args", it.args())) }
    }

    companion object {
        private const val TAG = "ScheduledTaskOffload"

        /**
         * [T-scheduled-tool-prefill] `--command` (alias `--shell`) → a
         * shell_execute call, or `--tool NAME [--tool-args JSON]` for the
         * general form (NAME defaults to shell_execute, as on iOS). Null when
         * no prefill flag was passed.
         *
         * Static on purpose: the parsing rules — not the task manager — are
         * what the unit tests need, and a handler cannot be constructed
         * without an Android Context.
         */
        internal fun parsePrefill(
            args: OffloadArgs,
            target: ScheduledTargetMode,
            title: String? = null,
        ): PrefilledToolCall? {
            val command = args.get("command", "shell")
            val toolName = args.get("tool")
            val toolArgs = args.get("tool-args")
            val timeoutRaw = args.get("command-timeout", "shell-timeout")
            if (command != null && (toolName != null || toolArgs != null)) {
                throw IllegalArgumentException(
                    "--command is shorthand for `--tool shell_execute`; pass one of the two, not both",
                )
            }
            if (command == null && toolName == null && toolArgs == null) {
                // A timeout with nothing to time out is a typo, not a default.
                if (timeoutRaw != null) {
                    throw IllegalArgumentException("--command-timeout only applies with --command/--shell")
                }
                return null
            }
            if (target is ScheduledTargetMode.RerunMessage) {
                throw IllegalArgumentException(
                    "a rerun replays an existing turn and sends nothing new; " +
                        "a prefilled call needs --target new or follow-up",
                )
            }
            val flagTimeout = timeoutRaw?.let { requireTimeout(it) }
            val call = if (command != null) {
                PrefilledToolCall.shell(command, title = title, timeoutSec = flagTimeout)
            } else {
                val parsed = runCatching { JSONObject(toolArgs ?: "{}") }.getOrElse {
                    throw IllegalArgumentException("--tool-args must be a JSON object")
                }
                // The flag wins over a timeout inside --tool-args, as on iOS.
                flagTimeout?.let { parsed.put("timeout", it) }
                PrefilledToolCall.of(toolName ?: PrefilledToolCall.SHELL_TOOL, parsed, title = title)
            }
            call.validationError()?.let { throw IllegalArgumentException(it) }
            return call
        }

        /** `2.5m` / `2H` / `90s` / `45` → seconds, or null when not a duration. */
        internal fun parseDurationSec(raw: String): Int? {
            val t = raw.trim().lowercase()
            if (t.isEmpty()) return null
            val (num, unit) = when {
                t.endsWith("ms") -> t.dropLast(2) to 0.001
                t.endsWith("s") -> t.dropLast(1) to 1.0
                t.endsWith("m") -> t.dropLast(1) to 60.0
                t.endsWith("h") -> t.dropLast(1) to 3600.0
                else -> t to 1.0
            }
            val n = num.toDoubleOrNull() ?: return null
            return PrefilledToolCall.saturatingSeconds(n * unit).takeIf { it != Int.MIN_VALUE }
        }

        private fun requireTimeout(raw: String): Int {
            val secs = parseDurationSec(raw)
                ?: throw IllegalArgumentException(
                    "--command-timeout must be a duration like 90s, 2m or 1h (or plain seconds)",
                )
            if (secs !in PrefilledToolCall.TIMEOUT_RANGE_SEC) {
                throw IllegalArgumentException(
                    "--command-timeout must be between ${PrefilledToolCall.TIMEOUT_RANGE_SEC.first}s " +
                        "and ${PrefilledToolCall.TIMEOUT_RANGE_SEC.last / 3600}h",
                )
            }
            return secs
        }

        private val HELP = """
            minis-scheduled — manage timed AI tasks (mirrors the in-app Scheduled Tasks).

            (no subcommand)  Same as `list` (default subcommand)
            list
            create --time HH:MM --prompt "..." [--label L]
                   [--repeat once|daily|weekdays|custom --days mon,tue,...]
                   [--every 30m|2h|1d]   (repeat every N; no --time needed)
                   [--of <taskId>]       (once, when that task's schedule finishes)
                   [--target new|follow-up|rerun --session <id> --message <id>]
                   [--model <modelId>] [--start YYYY-MM-DD] [--end YYYY-MM-DD] [--disabled]
                   [--command 'shell command'] [--command-timeout 2m]
                   [--tool <name> --tool-args '{"command":"…"}']
            delete  --id <taskId>
            enable  --id <taskId>
            disable --id <taskId>
            run     --id <taskId>          fire immediately, off-schedule

            Target modes: new = run prompt in a fresh chat; follow-up = append prompt
            to an existing chat (--session); rerun = re-run a chat (--session) from a
            chosen user message (--message, prompt ignored). Without --target, a create
            INSIDE a chat defaults to follow-up on THAT chat; the output's `delivery`
            sentence always says where the fires will land.

            --model is resolved when the task is CREATED: a wire model id, an entry
            uuid or an unambiguous display name all work, and the stored value is the
            canonical id. An unknown value fails now instead of at every fire; pass
            --allow-unknown-model to store it verbatim anyway (e.g. a model that
            arrives with a later provider refresh).

            --command / --tool make the task run that call BEFORE the model is asked;
            its real output is handed to the model as an already-completed tool call,
            so "run this fixed command and report" costs one request instead of two.
            --shell and --shell-timeout are the iOS aliases. --tool defaults to
            shell_execute (the only tool supported today), so
            --tool-args '{"command":"date"}' alone works. A rerun target replays an
            old turn and cannot prefill.
        """.trimIndent()
    }
}
