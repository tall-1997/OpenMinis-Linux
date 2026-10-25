package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.jobs.JobRegistry
import com.openminis.app.sandbox.jobs.JobRegistry.JobState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent-facing background-job tool over [JobRegistry] (deepseek-harness-style
 * jobs). One tool, four actions, cronjob-style:
 *
 *  - `start`  (command, workdir?) — launch a long command detached; returns a
 *    `job_id` immediately instead of blocking the turn the way
 *    `shell_execute` would.
 *  - `list`   — this session's jobs with state and exit codes.
 *  - `output` (job_id, tail_lines=200) — tail of the job's stdout+stderr.
 *  - `kill`   (job_id) — terminate a running job; idempotent for jobs that
 *    already finished.
 *
 * [T-android-jobs-registry] Session scoping is the security boundary: the
 * registry only ever exposes jobs started under the caller's sessionId, so
 * the tool passes the dispatching session through untouched and never lets
 * the model address another chat's job.
 *
 * The dispatch line passes `sessionId` the same way FileReadTool receives it
 * (`execute(argsJson, activeSessionId, context)`); `context` is kept for
 * signature symmetry with the other tools — the registry's host-installed
 * spawner owns all filesystem paths.
 */
object JobTools {
    const val NAME = "background_job"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Run a long shell command as a BACKGROUND job in this session's sandbox without " +
            "blocking the conversation, then poll its output or terminate it. Actions: 'start' " +
            "(command, optional workdir) launches detached and returns a job_id immediately; 'list' " +
            "shows this session's jobs with state (RUNNING/COMPLETED/FAILED/KILLED) and exit_code; " +
            "'output' (job_id, tail_lines, default 200) returns the tail of the job's stdout+stderr " +
            "accumulated so far; 'kill' (job_id) terminates a running job (idempotent for jobs that " +
            "already finished). At most 8 jobs may be RUNNING per session. Prefer this over " +
            "shell_execute for builds, package installs, dev servers, or long test suites you want " +
            "to keep working while you do something else. Jobs are private to this session and die " +
            "with the app process.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user (e.g. 'Build kernel in background'). Use the same language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "start, list, output, or kill.",
                enumValues = listOf("start", "list", "output", "kill"),
            ),
            "command" to AgentToolParam("string", "For start: the shell command to run in the background."),
            "workdir" to AgentToolParam(
                "string",
                "For start: optional guest working directory (defaults to the session workspace).",
            ),
            "job_id" to AgentToolParam(
                "string",
                "For output/kill: job id from start or list (a unique id prefix works).",
            ),
            "tail_lines" to AgentToolParam(
                "string",
                "For output: how many trailing lines to return (default 200, max 2000).",
            ),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "command", "workdir", "job_id", "tail_lines"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context? = null): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            return ToolExecutionResult(
                "Error: invalid JSON arguments",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
            )
        }
        val action = args.optString("action").trim().lowercase()
        return when (action) {
            "start" -> start(args, sessionId)
            "list" -> listJobs(sessionId)
            "output" -> jobOutput(args, sessionId)
            "kill" -> kill(args, sessionId)
            else -> ToolExecutionResult(
                "Error: action must be start, list, output, or kill",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
            )
        }
    }

    // --------------------------------------------------------------- start

    private fun start(args: JSONObject, sessionId: String): ToolExecutionResult {
        val command = args.optString("command").trim()
        if (command.isEmpty()) {
            return ToolExecutionResult(
                "Error: command is required for start",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
                recoveryHint = "Pass the command to run detached, e.g. {\"action\":\"start\",\"command\":\"./gradlew build\"}.",
            )
        }
        val workdir = args.optString("workdir").trim().ifEmpty { null }
        return when (val outcome = JobRegistry.start(sessionId, command, workdir)) {
            is JobRegistry.StartOutcome.Started -> {
                val job = outcome.job
                ToolExecutionResult(
                    JSONObject().apply {
                        put("ok", true)
                        put("job_id", job.id)
                        put("state", job.state.name)
                        put("command", job.command.take(200))
                        if (job.workingDir != null) put("workdir", job.workingDir)
                        if (job.outputSpillPath != null) put("output", job.outputSpillPath)
                        put(
                            "note",
                            "Poll progress with action=output and job_id; terminate with action=kill; " +
                                "action=list shows every job in this session.",
                        )
                    }.toString(),
                    true,
                )
            }
            is JobRegistry.StartOutcome.LimitReached -> ToolExecutionResult(
                "Error: ${outcome.running} background job(s) are already RUNNING " +
                    "(max ${JobRegistry.MAX_RUNNING_PER_SESSION} per session).",
                false,
                errorCode = ToolErrorCode.PERMISSION_DENIED,
                recoveryHint = "Clean up first: use action=list, then action=kill the jobs you no " +
                    "longer need (or poll finished ones with action=output) before starting more.",
            )
            JobRegistry.StartOutcome.SpawnerMissing -> ToolExecutionResult(
                "Error: background jobs are not available in this environment — no process " +
                    "spawner is installed, so commands cannot be started detached.",
                false,
                errorCode = ToolErrorCode.UNSUPPORTED,
                recoveryHint = "Run the command through shell_execute instead.",
            )
            is JobRegistry.StartOutcome.SpawnFailed -> ToolExecutionResult(
                "Error: failed to start background job: ${outcome.cause}",
                false,
                errorCode = ToolErrorCode.EXECUTION_FAILED,
                recoveryHint = "Retry once; if it persists, run via shell_execute and report the cause above.",
            )
        }
    }

    // ---------------------------------------------------------------- list

    private fun listJobs(sessionId: String): ToolExecutionResult {
        val jobs = JobRegistry.list(sessionId)
        if (jobs.isEmpty()) {
            return ToolExecutionResult(
                "No background jobs in this session. Start one with action=start.",
                true,
            )
        }
        val arr = JSONArray()
        for (job in jobs) {
            arr.put(
                JSONObject().apply {
                    put("job_id", job.id)
                    put("state", job.state.name)
                    put("exit_code", job.exitCode ?: JSONObject.NULL)
                    put("command", job.command.take(200))
                    if (job.workingDir != null) put("workdir", job.workingDir)
                    put("started_at_ms", job.startedAt)
                    if (job.outputSpillPath != null) put("output", job.outputSpillPath)
                },
            )
        }
        return ToolExecutionResult(
            JSONObject().put("count", jobs.size).put("jobs", arr).toString(),
            true,
        )
    }

    // -------------------------------------------------------------- output

    private fun jobOutput(args: JSONObject, sessionId: String): ToolExecutionResult {
        val ref = args.optString("job_id").trim()
        if (ref.isEmpty()) {
            return ToolExecutionResult(
                "Error: job_id is required for output",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
            )
        }
        // optInt parses numeric strings too, so the schema's string type is fine.
        val tailLines = if (args.has("tail_lines")) args.optInt("tail_lines", JobRegistry.DEFAULT_TAIL_LINES)
            else JobRegistry.DEFAULT_TAIL_LINES
        return when (val outcome = JobRegistry.output(sessionId, ref, tailLines)) {
            is JobRegistry.OutputOutcome.Found -> {
                val body = buildString {
                    append("Job ${outcome.job.id} [${outcome.job.state.name}")
                    outcome.job.exitCode?.let { append(", exit $it") }
                    if (outcome.truncated) append(", tail truncated")
                    append("]")
                    append("\n")
                    append(outcome.tail.ifBlank { "(no output yet)" })
                }
                ToolExecutionResult(body, true)
            }
            is JobRegistry.OutputOutcome.NoOutputYet -> ToolExecutionResult(
                "Job ${outcome.job.id} is ${outcome.job.state.name} and has written no output yet.",
                true,
            )
            is JobRegistry.OutputOutcome.Ambiguous -> ToolExecutionResult(
                "Error: job_id prefix matches ${outcome.matches.size} jobs: " +
                    outcome.matches.joinToString(", "),
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
            )
            JobRegistry.OutputOutcome.NotFound -> notFound(ref)
        }
    }

    // ---------------------------------------------------------------- kill

    private fun kill(args: JSONObject, sessionId: String): ToolExecutionResult {
        val ref = args.optString("job_id").trim()
        if (ref.isEmpty()) {
            return ToolExecutionResult(
                "Error: job_id is required for kill",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
            )
        }
        return when (val outcome = JobRegistry.kill(sessionId, ref)) {
            is JobRegistry.KillOutcome.Killed -> ToolExecutionResult(
                JSONObject().apply {
                    put("ok", true)
                    put("job_id", outcome.job.id)
                    put("state", JobState.KILLED.name)
                    put("note", "termination signal delivered")
                }.toString(),
                true,
            )
            is JobRegistry.KillOutcome.AlreadyFinished -> {
                val job = outcome.job
                // Idempotent success: the goal (job no longer running) already holds.
                ToolExecutionResult(
                    JSONObject().apply {
                        put("ok", true)
                        put("job_id", job.id)
                        put("state", job.state.name)
                        put("exit_code", job.exitCode ?: JSONObject.NULL)
                        put("note", "job had already finished; no kill was needed")
                    }.toString(),
                    true,
                )
            }
            is JobRegistry.KillOutcome.KillFailed -> ToolExecutionResult(
                "Error: failed to kill job ${outcome.job.id}: ${outcome.reason}",
                false,
                errorCode = ToolErrorCode.EXECUTION_FAILED,
            )
            is JobRegistry.KillOutcome.Ambiguous -> ToolExecutionResult(
                "Error: job_id prefix matches ${outcome.matches.size} jobs: " +
                    outcome.matches.joinToString(", "),
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
            )
            JobRegistry.KillOutcome.NotFound -> notFound(ref)
        }
    }

    private fun notFound(ref: String): ToolExecutionResult = ToolExecutionResult(
        "Error: no background job matching '$ref' in this session. " +
            "Use action=list to see the jobs you own.",
        false,
        errorCode = ToolErrorCode.NOT_FOUND,
    )
}
