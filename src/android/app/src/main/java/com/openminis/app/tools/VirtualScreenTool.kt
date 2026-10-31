package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.SessionWorkspace
import com.openminis.app.vscreen.HandoffController
import com.openminis.app.vscreen.HandoffStateMachine
import com.openminis.app.vscreen.VirtualScreenManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-vscreen] Agent-facing surface for [VirtualScreenManager]:
 * put a target app on an agent-owned VIRTUAL display and drive it there.
 * One tool, ten actions, cronjob-style (multi-action precedent
 * [JobTools] / [CronJobTool]):
 *
 *  - `create`     (width?, height?, dpi?) — new virtual display, default
 *    1080x1920@320; at most 2, the least-recently-used is released. Returns a
 *    `display_id`. The display has no surface, so it renders nothing — the
 *    app still starts and receives input on it; verify state via screenshot.
 *    Apps launched there are muted (PLAY_AUDIO via appops, original mode
 *    restored when they leave) and the display tries to keep the keyboard
 *    with the user (IME policy, Android 12+ via the Shizuku user service).
 *  - `launch`     (display_id, package, activity?) — start an app on that
 *    display via Shizuku `am start -W --display`; the launcher activity is
 *    resolved via `cmd package resolve-activity --brief` when omitted. The
 *    result reports whether the mute took effect. A 3s spillover watcher
 *    pulls back apps that jump to the user's screen (best effort, probed).
 *  - `screenshot` (display_id) — capture the virtual display and return a
 *    `/var/minis/offloads/...` path for read_image. Layered read path
 *    (ImageReader app-uid first, then Shizuku screencap fallbacks). The
 *    result annotates the current hand_to_user state, if any.
 *  - `tap` / `swipe` / `text` / `key` (display_id, ...) — inject input into
 *    that display via Shizuku `input`, display-targeting probed at runtime.
 *  - `hand_to_user` (display_id, reason?, kind?) — hand the CURRENT STEP to
 *    the user (login / captcha / payment / protected page / choice): the
 *    display's top task moves to the main screen, its sound comes back and a
 *    "needs you" notification goes up with three buttons (Done = moved back +
 *    re-read; I did it myself = verify only; Not now = skip). Non-blocking:
 *    later results on that display report the handoff state.
 *  - `list` — live virtual displays (with handoff / IME-policy annotations).
 *  - `destroy` (display_id) — release one; the apps it muted get their sound
 *    back and any waiting handoff is cleared.
 *
 * HONEST STATUS: every failure carries a structured reason — Shizuku not
 * granted ([ToolErrorCode.PERMISSION_DENIED]), the display does not exist
 * ([ToolErrorCode.NOT_FOUND]), this ROM has no display-targeted input /
 * screencap / task move ([ToolErrorCode.UNSUPPORTED]), or a plain execution
 * failure with the underlying command output. Requires Shizuku for
 * launch/screenshot/tap/swipe/text/key/hand_to_user; create/list/destroy
 * need no Shizuku at all.
 *
 * The dispatch line passes `(argsJson, sessionId, context)` like
 * [JobTools]; `sessionId` scopes the screenshot's offloads directory and
 * `context` installs the DisplayManager factory on first use.
 */
object VirtualScreenTool {
    const val NAME = "virtual_screen"

    /** Valid wire values for the hand_to_user `kind` parameter (DSH §2). */
    val HANDOFF_KINDS = HandoffStateMachine.KINDS

    private val ACTIONS = listOf(
        "create", "launch", "screenshot", "tap", "swipe", "text", "key", "hand_to_user", "list", "destroy",
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Put a target Android app on an agent-owned VIRTUAL display and drive it " +
            "there independently of the user's physical screen: the app runs on a headless " +
            "virtual display, so the user keeps their phone and no Accessibility permission is " +
            "needed (driving goes through the Shizuku shell). Requires Shizuku for launch, " +
            "screenshot fallbacks, input and hand_to_user; create/list/destroy need none. " +
            "Actions: 'create' (width/height/dpi, default 1080x1920@320) makes a display and " +
            "returns a display_id (at most 2 live, least-recently-used is released; the display " +
            "renders nothing — the app still starts and receives input, verify state via " +
            "screenshot; apps launched there are muted via appops until they leave the display); " +
            "'launch' (display_id, package, optional activity) starts an app on that display and " +
            "reports whether the mute took effect; 'screenshot' (display_id) captures it and " +
            "returns a file path for read_image; 'tap'/'swipe' (coordinates in the virtual " +
            "display's pixel space), 'text' (types into the focused field) and 'key' (back/home/" +
            "enter or a numeric keycode) inject input into that display; 'hand_to_user' " +
            "(display_id, reason, kind) hands the current step to the user — login, captcha, " +
            "payment, a protected page or a choice — by moving the app to their screen with a " +
            "'needs you' notification; the user's pick (done / did it myself / not now) arrives " +
            "asynchronously and later results on that display report it; 'list' shows live " +
            "displays; 'destroy' (display_id) releases one and restores the muted apps' sound. " +
            "Typical loop: create -> launch -> screenshot -> tap/text -> screenshot to verify; " +
            "when only the user can proceed, hand_to_user instead of guessing. Whether this ROM " +
            "supports display-targeted input, screencap and task moves is probed at runtime; on " +
            "an unsupported ROM the tool says so and suggests the accessibility stack " +
            "(ui_read / ui_action) or asks the user to handle it manually.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user, e.g. 'Launch settings on virtual screen'.",
            ),
            "action" to AgentToolParam(
                "string",
                "One of: create, launch, screenshot, tap, swipe, text, key, hand_to_user, list, destroy.",
                enumValues = ACTIONS,
            ),
            "width" to AgentToolParam("integer", "For create: display width in pixels (default 1080)."),
            "height" to AgentToolParam("integer", "For create: display height in pixels (default 1920)."),
            "dpi" to AgentToolParam("integer", "For create: display density (default 320)."),
            "display_id" to AgentToolParam(
                "integer",
                "For launch/screenshot/tap/swipe/text/key/hand_to_user/destroy: the display_id " +
                    "from create or list.",
            ),
            "package" to AgentToolParam("string", "For launch: Android package to start on the display."),
            "activity" to AgentToolParam(
                "string",
                "For launch: optional component (e.g. com.example/.MainActivity); resolved from the " +
                    "package's launcher activity when omitted.",
            ),
            "reason" to AgentToolParam(
                "string",
                "For hand_to_user: what the user should do, in their language, e.g. " +
                    "'Please log in to your Taobao account'. Shown in the notification.",
            ),
            "kind" to AgentToolParam(
                "string",
                "For hand_to_user: what kind of step only the user can do.",
                enumValues = HANDOFF_KINDS,
            ),
            "x" to AgentToolParam("integer", "For tap: X coordinate in the display's pixel space."),
            "y" to AgentToolParam("integer", "For tap: Y coordinate in the display's pixel space."),
            "x1" to AgentToolParam("integer", "For swipe: start X."),
            "y1" to AgentToolParam("integer", "For swipe: start Y."),
            "x2" to AgentToolParam("integer", "For swipe: end X."),
            "y2" to AgentToolParam("integer", "For swipe: end Y."),
            "duration_ms" to AgentToolParam("integer", "For swipe: optional duration in milliseconds."),
            "text" to AgentToolParam("string", "For text: the text to type into the focused field."),
            "key" to AgentToolParam(
                "string",
                "For key: back, home, enter, or a numeric Android keycode.",
                enumValues = listOf("back", "home", "enter"),
            ),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf(
            "tool_title", "action", "width", "height", "dpi", "display_id",
            "package", "activity", "reason", "kind", "x", "y", "x1", "y1", "x2", "y2",
            "duration_ms", "text", "key",
        ),
    )

    fun execute(argsJson: String, sessionId: String, context: Context? = null): ToolExecutionResult {
        val obj = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult(
                "Error: invalid JSON arguments: ${it.message}",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
            )
        }
        val action = obj.optString("action").trim().lowercase()
        val toolTitle = obj.optString("tool_title", NAME)
        // [T-android-vscreen2] Leftover sweep on every dispatch: restores mute
        // ledger entries of dead displays and expires dead handoffs — cheap
        // when there is nothing to do.
        VirtualScreenManager.reconcileState()
        return when (action) {
            "create" -> create(obj, context, toolTitle)
            "launch" -> launch(obj, toolTitle)
            "screenshot" -> screenshot(obj, sessionId, context, toolTitle)
            "tap" -> tap(obj, toolTitle)
            "swipe" -> swipe(obj, toolTitle)
            "text" -> text(obj, toolTitle)
            "key" -> key(obj, toolTitle)
            "hand_to_user" -> handToUser(obj, context, toolTitle)
            "list" -> list(toolTitle)
            "destroy" -> destroy(obj, toolTitle)
            else -> ToolExecutionResult(
                "Error: unknown action '$action' (one of: ${ACTIONS.joinToString("/")})",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
                recoveryHint = "Choose one of: ${ACTIONS.joinToString(", ")}.",
                toolTitle = toolTitle,
            )
        }
    }

    // --------------------------------------------------------------- create

    private fun create(obj: JSONObject, context: Context?, toolTitle: String): ToolExecutionResult {
        val width = intArg(obj, "width") ?: 0
        val height = intArg(obj, "height") ?: 0
        val dpi = intArg(obj, "dpi") ?: 0
        return when (val outcome = VirtualScreenManager.create(width, height, dpi, null, context)) {
            is VirtualScreenManager.CreateOutcome.Created -> {
                val s = outcome.screen
                ToolExecutionResult(
                    JSONObject().apply {
                        put("ok", true)
                        put("display_id", s.displayId)
                        put("width", s.width)
                        put("height", s.height)
                        put("dpi", s.dpi)
                        put("label", s.label)
                        if (outcome.evicted != null) {
                            put("evicted_display_id", outcome.evicted.displayId)
                        }
                        put(
                            "note",
                            "The display has no surface and renders nothing; the target app " +
                                "still starts and receives input on it. Use action=launch to " +
                                "start an app, action=screenshot to verify, action=destroy to release.",
                        )
                    }.toString(),
                    true,
                    toolTitle = toolTitle,
                )
            }
            is VirtualScreenManager.CreateOutcome.FactoryMissing -> ToolExecutionResult(
                "Error: ${outcome.message}",
                false,
                errorCode = ToolErrorCode.UNSUPPORTED,
                toolTitle = toolTitle,
            )
            is VirtualScreenManager.CreateOutcome.Failed -> ToolExecutionResult(
                "Error: ${outcome.message}",
                false,
                errorCode = ToolErrorCode.EXECUTION_FAILED,
                toolTitle = toolTitle,
            )
        }
    }

    // --------------------------------------------------------------- launch

    private fun launch(obj: JSONObject, toolTitle: String): ToolExecutionResult {
        val displayId = intArg(obj, "display_id")
        if (displayId == null) return missingDisplayId(toolTitle)
        val pkg = obj.optString("package").trim()
        if (pkg.isEmpty()) {
            return ToolExecutionResult(
                "Error: package is required for launch",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
                recoveryHint = "Pass the Android package to start on the display.",
                toolTitle = toolTitle,
            )
        }
        val activity = obj.optString("activity").trim().ifEmpty { null }
        return toResult(VirtualScreenManager.launch(displayId, pkg, activity), toolTitle)
    }

    // ----------------------------------------------------------- screenshot

    private fun screenshot(
        obj: JSONObject,
        sessionId: String,
        context: Context?,
        toolTitle: String,
    ): ToolExecutionResult {
        val displayId = intArg(obj, "display_id")
        if (displayId == null) return missingDisplayId(toolTitle)
        val dir = context?.let { SessionWorkspace.hostDir(it.filesDir, sessionId, "offloads") }
        return toResult(VirtualScreenManager.screenshot(displayId, dir), toolTitle)
    }

    // ----------------------------------------------------------- drive class

    private fun tap(obj: JSONObject, toolTitle: String): ToolExecutionResult {
        val x = coordinate(obj, "x") ?: return badCoordinates("tap", toolTitle)
        val y = coordinate(obj, "y") ?: return badCoordinates("tap", toolTitle)
        return toResult(VirtualScreenManager.tap(displayIdOrZero(obj), x, y), toolTitle)
    }

    private fun swipe(obj: JSONObject, toolTitle: String): ToolExecutionResult {
        val x1 = coordinate(obj, "x1") ?: return badCoordinates("swipe", toolTitle)
        val y1 = coordinate(obj, "y1") ?: return badCoordinates("swipe", toolTitle)
        val x2 = coordinate(obj, "x2") ?: return badCoordinates("swipe", toolTitle)
        val y2 = coordinate(obj, "y2") ?: return badCoordinates("swipe", toolTitle)
        val duration = intArg(obj, "duration_ms")
        return toResult(
            VirtualScreenManager.swipe(displayIdOrZero(obj), x1, y1, x2, y2, duration),
            toolTitle,
        )
    }

    private fun text(obj: JSONObject, toolTitle: String): ToolExecutionResult {
        val text = obj.optString("text")
        if (text.isBlank()) {
            return ToolExecutionResult(
                "Error: text is required for the text action",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
                toolTitle = toolTitle,
            )
        }
        return toResult(VirtualScreenManager.text(displayIdOrZero(obj), text), toolTitle)
    }

    private fun key(obj: JSONObject, toolTitle: String): ToolExecutionResult {
        val key = obj.optString("key").trim()
        if (key.isEmpty()) {
            return ToolExecutionResult(
                "Error: key is required for the key action",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
                recoveryHint = "Pass back, home, enter, or a numeric Android keycode.",
                toolTitle = toolTitle,
            )
        }
        return toResult(VirtualScreenManager.key(displayIdOrZero(obj), key), toolTitle)
    }

    // ----------------------------------------------------------- hand_to_user

    /**
     * Hand the current step to the user (DSH AGENT-SCREEN-V2.md §2): move
     * the display's top task to the main screen, restore its sound, post the
     * "needs you" notification with the three buttons. Non-blocking — the
     * user's choice arrives via the notification receiver and shows up in
     * the `handoff` annotation of later results on this display.
     */
    private fun handToUser(obj: JSONObject, context: Context?, toolTitle: String): ToolExecutionResult {
        val displayId = intArg(obj, "display_id")
        if (displayId == null) return missingDisplayId(toolTitle)
        val kindRaw = obj.optString("kind", "").trim()
        val kind = HandoffStateMachine.parseKind(kindRaw)
        if (kind == null) {
            return ToolExecutionResult(
                "Error: kind '$kindRaw' is not a valid hand_to_user kind (one of: " +
                    "${HANDOFF_KINDS.joinToString("/")})",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
                recoveryHint = "Pick the closest kind: ${HANDOFF_KINDS.joinToString(", ")}.",
                toolTitle = toolTitle,
            )
        }
        val reason = obj.optString("reason").trim()
        if (VirtualScreenManager.screen(displayId) == null) {
            return ToolExecutionResult(
                "Error: no virtual screen with display_id=$displayId — use action=list for live displays.",
                false,
                errorCode = ToolErrorCode.NOT_FOUND,
                recoveryHint = "Use action=list to see live virtual displays, or action=create to make one.",
                toolTitle = toolTitle,
            )
        }
        val controller = VirtualScreenManager.handoffController(context)
            ?: return ToolExecutionResult(
                "Error: no app context is available — the hand_to_user flow needs one to post " +
                    "the 'needs you' notification and persist the handoff state",
                false,
                errorCode = ToolErrorCode.EXECUTION_FAILED,
                toolTitle = toolTitle,
            )
        return when (val outcome = controller.start(displayId, kind, reason)) {
            is HandoffController.StartOutcome.Started -> {
                val record = outcome.record
                ToolExecutionResult(
                    JSONObject().apply {
                        put("ok", true)
                        put("display_id", displayId)
                        put("handoff_state", record.state.name)
                        put("task_id", record.taskId)
                        put("package", record.packageName)
                        put("sound_restored", outcome.soundRestored)
                        put("notification_posted", outcome.notificationPosted)
                        put(
                            "note",
                            (HandoffStateMachine.guidance(record.state, record.packageName, record.kind)
                                ?: "handed to the user") +
                                (record.note.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                                if (reason.isBlank()) {
                                    " Pass a clearer 'reason' next time so the notification says what to do."
                                } else {
                                    ""
                                },
                        )
                    }.toString(),
                    true,
                    toolTitle = toolTitle,
                )
            }
            is HandoffController.StartOutcome.AlreadyWaiting -> {
                val record = outcome.record
                ToolExecutionResult(
                    JSONObject().apply {
                        put("ok", true)
                        put("display_id", displayId)
                        put("handoff_state", record.state.name)
                        put("task_id", record.taskId)
                        put("package", record.packageName)
                        put(
                            "note",
                            "a handoff is already WAITING for this display (started for kind=" +
                                "${record.kind}); the user's choice will arrive in later results",
                        )
                    }.toString(),
                    true,
                    toolTitle = toolTitle,
                )
            }
            is HandoffController.StartOutcome.NoScreen -> ToolExecutionResult(
                "Error: ${outcome.message}",
                false,
                errorCode = ToolErrorCode.NOT_FOUND,
                recoveryHint = "Use action=list to see live virtual displays.",
                toolTitle = toolTitle,
            )
            is HandoffController.StartOutcome.MoveUnavailable -> ToolExecutionResult(
                "Error: ${outcome.message}",
                false,
                errorCode = ToolErrorCode.UNSUPPORTED,
                recoveryHint = "Task moves are probed at runtime; when they are unavailable the " +
                    "user must handle the app on their screen manually — tell them what to do " +
                    "and stop driving that app.",
                toolTitle = toolTitle,
            )
            is HandoffController.StartOutcome.MoveFailed -> ToolExecutionResult(
                "Error: ${outcome.message}",
                false,
                errorCode = ToolErrorCode.EXECUTION_FAILED,
                toolTitle = toolTitle,
            )
        }
    }

    // ------------------------------------------------------------------ list

    private fun list(toolTitle: String): ToolExecutionResult {
        val arr = JSONArray()
        for (s in VirtualScreenManager.list()) {
            arr.put(
                JSONObject().apply {
                    put("display_id", s.displayId)
                    put("width", s.width)
                    put("height", s.height)
                    put("dpi", s.dpi)
                    put("label", s.label)
                    put("created_at_ms", s.createdAtMs)
                    put("last_used_at_ms", s.lastUsedAtMs)
                    s.imePolicyNote?.let { put("ime_policy", it) }
                    VirtualScreenManager.handoffNote(s.displayId)?.let { put("handoff", it) }
                    VirtualScreenManager.consumeSpillbackNote(s.displayId)?.let { put("spillback", it) }
                    put(
                        "idle_release_note",
                        "this display releases itself after ${VirtualScreenManager.IDLE_RELEASE_MS / 60_000} " +
                            "idle minutes",
                    )
                },
            )
        }
        val count = arr.length()
        return ToolExecutionResult(
            JSONObject().apply {
                put("ok", true)
                put("count", count)
                put("displays", arr)
                if (count == 0) {
                    put("note", "No virtual displays yet. Use action=create to make one.")
                }
            }.toString(),
            true,
            toolTitle = toolTitle,
        )
    }

    // --------------------------------------------------------------- destroy

    private fun destroy(obj: JSONObject, toolTitle: String): ToolExecutionResult {
        val displayId = intArg(obj, "display_id")
        if (displayId == null) return missingDisplayId(toolTitle)
        val report = VirtualScreenManager.destroyDetailed(displayId)
        if (report == null) {
            return ToolExecutionResult(
                "Error: no virtual screen with display_id=$displayId. " +
                    "Use action=list for live displays.",
                false,
                errorCode = ToolErrorCode.NOT_FOUND,
                toolTitle = toolTitle,
            )
        }
        return ToolExecutionResult(
            JSONObject().apply {
                put("ok", true)
                put("display_id", displayId)
                put("destroyed", true)
                put("sound_restored", JSONArray(report.soundRestored))
                if (report.handoffCleared) {
                    put(
                        "handoff_note",
                        "a waiting hand_to_user handoff was cleared with the screen; the app " +
                            "stays wherever the user has it",
                    )
                }
                put(
                    "note",
                    "apps this screen muted got their original PLAY_AUDIO mode back via appops " +
                        "(${report.soundRestored.size} restored)",
                )
            }.toString(),
            true,
            toolTitle = toolTitle,
        )
    }

    // --------------------------------------------------------------- helpers

    /**
     * [T-ui-action-arg-quoting] precedent: coordinates are model-supplied, so
     * accept a plain integer only — anything else is refused before it can
     * reach the manager.
     */
    private fun coordinate(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val raw = obj.get(key)) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    /** optInt parses numeric strings too, so the schema's integer type is fine. */
    private fun intArg(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val raw = obj.get(key)) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    private fun displayIdOrZero(obj: JSONObject): Int = intArg(obj, "display_id") ?: 0

    private fun missingDisplayId(toolTitle: String): ToolExecutionResult = ToolExecutionResult(
        "Error: display_id is required — use action=create first, then action=list to see live displays.",
        false,
        errorCode = ToolErrorCode.INVALID_ARGUMENTS,
        toolTitle = toolTitle,
    )

    private fun badCoordinates(action: String, toolTitle: String): ToolExecutionResult =
        ToolExecutionResult(
            "Error: bad arguments for action '$action' — coordinates must be plain integers " +
                "within the display's dimensions (take them from the latest screenshot).",
            false,
            errorCode = ToolErrorCode.INVALID_ARGUMENTS,
            recoveryHint = "Re-read the screen with action=screenshot and pass numeric " +
                "coordinates from that capture.",
            toolTitle = toolTitle,
        )

    /**
     * Outcome → result mapping. Every failure carries a structured reason:
     * Shizuku state, missing display, ROM API support, or the underlying
     * command output.
     */
    private fun toResult(outcome: VirtualScreenManager.CommandOutcome, toolTitle: String): ToolExecutionResult =
        when (outcome) {
            is VirtualScreenManager.CommandOutcome.Ok -> {
                val json = JSONObject().apply {
                    put("ok", true)
                    put("display_id", outcome.displayId)
                    put("detail", outcome.detail)
                    if (outcome.guestPath != null) put("path", outcome.guestPath)
                    // [T-android-vscreen2] The current hand_to_user state and any
                    // spillover-recovery outcome ride on every Ok result.
                    VirtualScreenManager.handoffNote(outcome.displayId)?.let { put("handoff", it) }
                    VirtualScreenManager.consumeSpillbackNote(outcome.displayId)?.let { put("spillback", it) }
                }.toString()
                ToolExecutionResult(json, true, toolTitle = toolTitle)
            }
            is VirtualScreenManager.CommandOutcome.ShizukuUnavailable -> ToolExecutionResult(
                "Error: ${outcome.message}",
                false,
                errorCode = ToolErrorCode.PERMISSION_DENIED,
                recoveryHint = "Grant Shizuku (Settings -> Permissions -> Shizuku), then retry. " +
                    "create/list/destroy work without Shizuku; screenshot falls back to the " +
                    "in-app ImageReader capture first.",
                toolTitle = toolTitle,
            )
            is VirtualScreenManager.CommandOutcome.NotFound -> ToolExecutionResult(
                "Error: ${outcome.message}",
                false,
                errorCode = ToolErrorCode.NOT_FOUND,
                recoveryHint = "Use action=list to see live virtual displays, or action=create to make one.",
                toolTitle = toolTitle,
            )
            is VirtualScreenManager.CommandOutcome.NotSupported -> ToolExecutionResult(
                "Error: ${outcome.message}",
                false,
                errorCode = ToolErrorCode.UNSUPPORTED,
                toolTitle = toolTitle,
            )
            is VirtualScreenManager.CommandOutcome.Failed -> ToolExecutionResult(
                "Error: ${outcome.message}",
                false,
                errorCode = ToolErrorCode.EXECUTION_FAILED,
                toolTitle = toolTitle,
            )
        }
}
