package com.openminis.app.mcp.server.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-mcp-refactor] Tool descriptors for listTools — declarations separated
 * from implementations (after OmniBot's McpToolDefinitions). Each entry is
 * consumed by the dispatcher's registry; the JSON shape is byte-identical to
 * the pre-refactor listTools output (tests assert the exact tool surface).
 */
internal object McpToolSchemas {

    private fun prop(type: String, description: String? = null): JSONObject = JSONObject().apply {
        put("type", type)
        if (description != null) put("description", description)
    }

    private fun schema(vararg required: String, props: JSONObject = JSONObject()): JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray().apply { required.forEach { put(it) } })
        }

    fun descriptor(spec: McpToolSpec): JSONObject = JSONObject().apply {
        put("name", spec.name)
        put("description", spec.description)
        put("inputSchema", spec.schema)
    }

    val fileTransferPullSchema = schema("path", props = JSONObject().apply {
        put("offset", JSONObject().put("type", "integer").put("description", "Byte offset to start from (default 0)."))
        put("maxBytes", JSONObject().put("type", "integer").put("description", "Maximum bytes to return (default 262144, hard cap 1048576)."))
    })

    val scheduleTaskListSchema = schema(props = JSONObject())

    val deviceInfo = "Get device information: model, manufacturer, Android version, battery level."

    val shellExec = "Execute a command in an isolated Linux process (Ubuntu 24.04 arm64 via PRoot). " +
        "The command runs in GNU bash (/bin/bash). stdout and stderr are merged. " +
        "Use 'command' to specify the shell command, optional 'timeout_sec' to limit execution."

    val shellExecSchema = schema("command", props = JSONObject().apply {
        put("command", prop("string"))
        put("timeout_sec", prop("integer"))
    })

    val uiRead = "Read a bounded snapshot of the current foreground screen via the accessibility tree."

    val uiAction = "Perform one UI action via the accessibility service: tap, type, swipe, scroll, back, open_app, open_url, long_press."

    val uiActionSchema = schema("action", props = JSONObject().apply {
        put("action", prop("string"))
        put("x", prop("integer"))
        put("y", prop("integer"))
        put("x2", prop("integer"))
        put("y2", prop("integer"))
        put("text", prop("string"))
        put("direction", prop("string"))
        put("package_name", prop("string"))
        put("url", prop("string"))
    })

    val fileRead = "Read a file from the Linux filesystem. Restricted to /var/minis/ and /sdcard/ prefixes. " +
        "Binary files return a type guess and a bounded hex preview instead of raw contents."

    val fileReadSchema = schema("path", props = JSONObject().apply {
        put("path", prop("string"))
    })

    val fileWrite = "Write a UTF-8 text file to the Linux filesystem. " +
        "Restricted to /var/minis/ and /sdcard/ prefixes; max 256 KB per call."

    val fileWriteSchema = schema("path", "content", props = JSONObject().apply {
        put("path", prop("string"))
        put("content", prop("string"))
        put("append", prop("boolean", "Append to the file instead of overwriting (default false)."))
    })

    val fileTransferPull = "Read a binary or text file from the Linux filesystem and return it base64-encoded " +
        "with size and sha256. Same path restrictions as file_read; max 8 MB per call."

    val fileTransferPush = "Write base64-encoded content to a file on the Linux filesystem (binary-safe). " +
        "Same path restrictions as file_write; max 8 MB decoded per call."

    val fileTransferPushSchema = schema("path", "content_base64", props = JSONObject().apply {
        put("path", prop("string"))
        put("content_base64", prop("string"))
    })

    val scheduleTaskCreate = "Create a scheduled task that fires an agent prompt. " +
        "Schedule: any duration like '90s', '7m', '1h30m' (once) or 'every 15m' (repeating); units s/m/h/d combinable."

    val scheduleTaskCreateSchema = schema("schedule", "prompt", props = JSONObject().apply {
        put("name", prop("string"))
        put("schedule", prop("string"))
        put("prompt", prop("string"))
    })

    val scheduleTaskList = "List all scheduled tasks with id, name, schedule, next trigger, and enabled state."

    val scheduleTaskUpdate = "Enable or disable a scheduled task by id (accepts a unique id prefix)."

    val scheduleTaskUpdateSchema = schema("id", "enabled", props = JSONObject().apply {
        put("id", prop("string"))
        put("enabled", prop("boolean"))
    })

    val scheduleTaskDelete = "Delete a scheduled task by id (accepts a unique id prefix)."

    val scheduleTaskDeleteSchema = schema("id", props = JSONObject().apply {
        put("id", prop("string"))
    })

    val alarmReminderCreate = "Create a one-shot reminder that runs an agent prompt after a delay. " +
        "Equivalent to a schedule_task with a 'once' schedule."

    val alarmReminderCreateSchema = schema("delay_sec", "message", props = JSONObject().apply {
        put("delay_sec", prop("integer", "Seconds from now until the reminder fires (60-86400)."))
        put("message", prop("string", "Agent prompt to run when the reminder fires."))
        put("name", prop("string"))
    })

    val contextAppsQuery = "List launchable apps on this device (label + package name). " +
        "Optional 'filter' substring matches label or package, case-insensitive. " +
        "Optional 'limit' caps results (default 50, max 200)."

    val contextAppsQuerySchema = schema(props = JSONObject().apply {
        put("filter", prop("string"))
        put("limit", prop("integer"))
    })

    // ── Three-phase approval flow ──────────────────────────────────────────

    val phoneTaskRequest = "Step 1 of 3 of the phone approval flow: announce a task. " +
        "Returns a taskId in PENDING state; nothing runs until the user approves the task " +
        "on the phone. Poll phone_task_status before submitting runs."

    val phoneTaskRequestSchema = schema("name", props = JSONObject().apply {
        put("name", prop("string", "Human-readable task name shown in the approval sheet."))
        put("taskId", prop("string", "Optional client-chosen id ([A-Za-z0-9._-]{1,64}); generated when omitted."))
    })

    val phoneLocalRun = "Step 2 of 3: submit one run under an approved task. " +
        "requestId is the content hash of (taskId, tool, arguments) — pass it to pin the payload; " +
        "the server recomputes and rejects any mismatch. The run waits in WAITING state until the " +
        "user approves that exact content on the phone."

    val phoneLocalRunSchema = schema("taskId", "tool", props = JSONObject().apply {
        put("taskId", prop("string", "Active task id from phone_task_request."))
        put("tool", prop("string", "Tool to run (shell_exec, file_write, ...)."))
        put("arguments", JSONObject().put("type", "object").put("description", "Tool arguments object."))
        put("operationId", prop("string", "Optional client-chosen operation id; generated when omitted."))
        put("requestId", prop("string", "Content hash from a previous attempt; recomputed and verified when supplied."))
    })

    val phoneOperationExecute = "Step 3 of 3: execute an operation the user already approved. " +
        "Idempotent by operationId — a repeated call returns the stored result without re-running the tool. " +
        "Fails when the approval expired (10 min), the task was revoked, or a bound fingerprint/source hash is missing."

    val phoneOperationExecuteSchema = schema("operationId", props = JSONObject().apply {
        put("operationId", prop("string"))
        put("operationFingerprint", prop("string", "Fingerprint bound at approval time, when one was set."))
        put("sourceFileHash", prop("string", "SHA-256 of the source evidence file, when the approval bound one."))
        put("sourceFilePath", prop("string", "Host path of the source evidence file (hashed server-side)."))
    })

    val phoneTaskStatus = "Read-only status, at either level: pass 'taskId' for the task plus all of its " +
        "operations (each with its approval status and whether it already ran), or 'operationId' for one " +
        "operation. Poll this instead of re-submitting runs; it never executes anything and never mutates state."

    val phoneTaskStatusSchema = schema(props = JSONObject().apply {
        put("taskId", prop("string", "Task id from phone_task_request."))
        put("operationId", prop("string", "Operation id from phone_local_run."))
    })
}
