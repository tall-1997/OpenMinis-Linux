package com.openminis.app.automation

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.ToolArgs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-automation-rules] Agent-facing surface for conditional-trigger
 * automation rules — the rule_create / rule_list / rule_update / rule_delete
 * / rule_runs family from DSH's RuleTools, shaped after
 * [com.openminis.app.tools.UiActionTool]: one object exposing definitions(),
 * a name-prefix [handles] and a single [execute] dispatcher. The dispatch
 * wiring lives in the tool-execution extension (shared file — the automation
 * report carries the exact diff for the manifest/tool owner).
 *
 * All descriptions and results are static English (model-facing), matching
 * the other tool families. Arguments follow the DSH RuleSpec JSON dialect:
 *
 * ```
 * trigger: {"type":"time","at":"07:30","days":[1..7 Mon=1]} | {"type":"time","everyMinutes":n>=15}
 *        | {"type":"time","onceAtEpochMs":ms} | {"type":"wifi_connect","ssid":opt}
 *        | {"type":"bluetooth_connect","deviceName":opt} | {"type":"power_connected"}
 *        | {"type":"battery_low","percent":n} | {"type":"screen_on"|"screen_off"|"screen_unlock"}
 *        | {"type":"notification","packageName":opt,"keyword":opt} | {"type":"app_opened","packageName":str}
 * condition: {"type":"time_range","from":"HH:mm","to":"HH:mm"} | {"type":"days_of_week","days":[..]}
 *          | {"type":"battery_at_least","percent":n} | {"type":"charging","charging":bool}
 * action: {"type":"agent","prompt":str}   (unattended run; only allowedTools usable)
 *       | {"type":"steps","steps":[{"tool":"…","argsJson":"{}"}]}  (stored only in v1)
 * ```
 */
object AutomationRuleTools {

    const val NAME_PREFIX = "automation_rule"

    private const val CREATE = "${NAME_PREFIX}_create"
    private const val LIST = "${NAME_PREFIX}_list"
    private const val UPDATE = "${NAME_PREFIX}_update"
    private const val DELETE = "${NAME_PREFIX}_delete"
    private const val RUNS = "${NAME_PREFIX}_runs"

    /** Tool names this build actually exposes — whitelist normalization input. */
    fun knownToolNames(): Set<String> = runCatching {
        AgentTools.makeAgentTools().map { it.name }.toSet()
    }.getOrElse {
        // Defensive floor: never let a registry failure widen or empty the gate.
        setOf(
            "shell_execute", "file_read", "file_write", "file_edit", "multi_edit",
            "list_dir", "grep", "glob", "browser_use", "web_search", "web_fetch",
            "ui_read", "ui_action", "read_image", "cronjob", "background_job",
        )
    }

    private val TRIGGER_DOC = "Trigger object, one of: {\"type\":\"time\",\"at\":\"HH:mm\",\"days\":[1..7 Mon=1, empty=every day]} | " +
        "{\"type\":\"time\",\"everyMinutes\":n>=15} | {\"type\":\"time\",\"onceAtEpochMs\":epoch_ms} | " +
        "{\"type\":\"wifi_connect\",\"ssid\":\"opt\"} | {\"type\":\"bluetooth_connect\",\"deviceName\":\"opt\"} | " +
        "{\"type\":\"power_connected\"} | {\"type\":\"battery_low\",\"percent\":n} (fires when the level drops to n while discharging) | " +
        "{\"type\":\"screen_on\"} | {\"type\":\"screen_off\"} | {\"type\":\"screen_unlock\"} | " +
        "{\"type\":\"notification\",\"packageName\":\"opt\",\"keyword\":\"opt\"} (needs Notification access) | " +
        "{\"type\":\"app_opened\",\"packageName\":\"pkg\"}"

    private val ACTION_DOC = "Action object: {\"type\":\"agent\",\"prompt\":\"what the agent should do\"} runs an unattended " +
        "chat that can only use allowed_tools and cannot ask the user; " +
        "{\"type\":\"steps\",\"steps\":[{\"tool\":\"t\",\"argsJson\":\"{}\"}]} is stored for a future version and NOT executed yet."

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = CREATE,
            description = "Create a conditional automation rule that runs when something happens on the phone " +
                "(time, Wi-Fi, Bluetooth, power, battery, screen, notification). Confirm the details with the " +
                "user first. The rule is validated before it is saved; unknown names in allowed_tools are dropped. " +
                "Geofence triggers are not supported.",
            parameters = mapOf(
                "tool_title" to AgentToolParam(
                    "string",
                    "A concise 5-10 word summary shown to the user (e.g. 'Create morning briefing rule').",
                ),
                "name" to AgentToolParam("string", "Short human-readable rule name."),
                "trigger" to AgentToolParam("object", TRIGGER_DOC, required = null),
                "conditions" to AgentToolParam(
                    "array",
                    "Optional array of condition objects that must ALL hold at fire time: " +
                        "{\"type\":\"time_range\",\"from\":\"HH:mm\",\"to\":\"HH:mm\"} (no overnight windows) | " +
                        "{\"type\":\"days_of_week\",\"days\":[1..7 Mon=1]} | " +
                        "{\"type\":\"battery_at_least\",\"percent\":n} | {\"type\":\"charging\",\"charging\":bool}.",
                    items = AgentToolParam("object", "Condition object."),
                ),
                "action" to AgentToolParam("object", ACTION_DOC),
                "allowed_tools" to AgentToolParam(
                    "array",
                    "Tool names the unattended agent run may use (everything else is refused once " +
                        "enforcement is wired). Names not exposed by this app are dropped.",
                    items = AgentToolParam("string", "Tool name."),
                ),
                "permission_mode" to AgentToolParam(
                    "string",
                    "Optional tool-gate for the created session: ASK (default) or YOYO (allow all tools).",
                    enumValues = listOf("ASK", "YOYO"),
                ),
            ),
            required = listOf("tool_title", "name", "trigger", "action"),
            propertyOrdering = listOf(
                "tool_title", "name", "trigger", "conditions", "action",
                "allowed_tools", "permission_mode",
            ),
        ),
        AgentToolDefinition(
            name = LIST,
            description = "List automation rules with their spec, enabled state and last fired time.",
            parameters = mapOf(
                "tool_title" to AgentToolParam(
                    "string",
                    "A concise 5-10 word summary shown to the user (e.g. 'List automation rules').",
                ),
            ),
            required = listOf("tool_title"),
        ),
        AgentToolDefinition(
            name = UPDATE,
            description = "Enable/disable an automation rule, rename it, or replace parts of its spec " +
                "(same trigger/conditions/action/allowed_tools format as $CREATE). Omitted fields keep " +
                "their stored values.",
            parameters = mapOf(
                "tool_title" to AgentToolParam(
                    "string",
                    "A concise 5-10 word summary shown to the user (e.g. 'Disable automation rule').",
                ),
                "id" to AgentToolParam("string", "Rule id from $LIST (a unique id prefix works)."),
                "enabled" to AgentToolParam("string", "\"true\" to enable, \"false\" to disable."),
                "name" to AgentToolParam("string", "New rule name."),
                "trigger" to AgentToolParam("object", "Replacement trigger ($TRIGGER_DOC)."),
                "conditions" to AgentToolParam("array", "Replacement conditions array."),
                "action" to AgentToolParam("object", "Replacement action ($ACTION_DOC)."),
                "allowed_tools" to AgentToolParam("array", "Replacement tool whitelist."),
                "permission_mode" to AgentToolParam("string", "ASK or YOYO."),
            ),
            required = listOf("tool_title", "id"),
            propertyOrdering = listOf(
                "tool_title", "id", "enabled", "name", "trigger", "conditions",
                "action", "allowed_tools", "permission_mode",
            ),
        ),
        AgentToolDefinition(
            name = DELETE,
            description = "Delete an automation rule and its run history.",
            parameters = mapOf(
                "tool_title" to AgentToolParam(
                    "string",
                    "A concise 5-10 word summary shown to the user (e.g. 'Delete automation rule').",
                ),
                "id" to AgentToolParam("string", "Rule id from $LIST (a unique id prefix works)."),
            ),
            required = listOf("tool_title", "id"),
        ),
        AgentToolDefinition(
            name = RUNS,
            description = "List the most recent execution records of one automation rule (newest first), " +
                "including outcome, session link and what triggered it.",
            parameters = mapOf(
                "tool_title" to AgentToolParam(
                    "string",
                    "A concise 5-10 word summary shown to the user (e.g. 'Show rule run records').",
                ),
                "id" to AgentToolParam("string", "Rule id from $LIST (a unique id prefix works)."),
                "limit" to AgentToolParam("string", "How many records to return (default 20, max 100)."),
            ),
            required = listOf("tool_title", "id"),
        ),
    )

    fun handles(name: String): Boolean = name.startsWith(NAME_PREFIX)

    /**
     * Dispatch one automation_rule_* call. Returns a JSON string with
     * {"ok":true,...} or {"ok":false,"error":"..."} — plain-String contract,
     * same as the cronjob family.
     */
    fun execute(
        name: String,
        argsJson: String,
        activeSessionId: String,
        context: Context,
    ): String {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return error("invalid JSON arguments: ${it.message}")
        }
        val manager = AutomationRuleManager(context.applicationContext)
        return try {
            runBlocking {
                when (name) {
                    CREATE -> create(manager, args)
                    LIST -> list(manager)
                    UPDATE -> update(manager, args)
                    DELETE -> delete(manager, args)
                    RUNS -> runs(manager, args)
                    else -> error("unknown automation tool '$name'")
                }
            }
        } catch (t: Throwable) {
            error("automation tool failed: ${t.message}")
        }
    }

    // ── actions ──────────────────────────────────────────────────────────

    private suspend fun create(manager: AutomationRuleManager, args: JSONObject): String {
        val spec = parseSpec(args, allowPartial = false)
            ?: return error("missing or malformed fields: name, trigger, action are required")
        val (entity, problems) = manager.createValidated(spec, knownToolNames())
        if (entity == null) {
            return error("rule rejected (nothing was saved): " + problems.joinToString("; "))
        }
        return JSONObject()
            .put("ok", true)
            .put("id", entity.id)
            .put("name", entity.name)
            .put("enabled", entity.enabled)
            .put("allowedTools", JSONArray(entity.spec()?.allowedTools ?: emptyList<String>()))
            .toString()
    }

    private suspend fun list(manager: AutomationRuleManager): String {
        val rules = manager.all()
        val arr = JSONArray()
        val now = System.currentTimeMillis()
        for (r in rules) {
            val spec = r.spec()
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("enabled", r.enabled)
                    put("trigger", spec?.trigger?.summarize() ?: "(unparseable spec)")
                    put("lastFiredAt", r.lastFiredAt ?: JSONObject.NULL)
                    (spec?.trigger as? Trigger.Time)?.let { t ->
                        put(
                            "nextTriggerMs",
                            t.nextTriggerMs(now, r.lastFiredAt ?: r.createdAt) ?: JSONObject.NULL,
                        )
                    }
                    put("spec", spec?.toJson() ?: JSONObject.NULL)
                },
            )
        }
        return JSONObject().put("ok", true).put("count", rules.size).put("rules", arr).toString()
    }

    private suspend fun update(manager: AutomationRuleManager, args: JSONObject): String {
        val id = ToolArgs.str(args, "id") ?: return error("id is required")
        val resolved = resolveRuleId(manager, id) ?: return error("no rule with id or prefix '$id'")
        val partial = parseSpec(args, allowPartial = true)
        if (partial == null && hasAnySpecField(args)) {
            return error("malformed trigger/action/conditions payload")
        }
        // [P2-convergence] NOT ToolArgs.bool on purpose: the historic contract
        // here is optBoolean with a string=="true" fallback, so "1"/"yes" and
        // a quoted "true" behave differently from the device tools' dialect.
        // Preserving that byte-for-byte beats unifying the tolerance.
        val enabled: Boolean? = when {
            !args.has("enabled") -> null
            else -> args.optBoolean("enabled", args.optString("enabled") == "true")
        }
        val (entity, problems) = manager.updateValidated(
            ruleId = resolved,
            name = ToolArgs.str(args, "name"),
            trigger = partial?.trigger,
            conditions = partial?.conditions,
            action = partial?.action,
            allowedTools = partial?.allowedTools,
            permissionMode = ToolArgs.str(args, "permission_mode"),
            enabled = enabled,
            knownTools = knownToolNames(),
        )
        if (entity == null) return error("update rejected: " + problems.joinToString("; "))
        return JSONObject()
            .put("ok", true)
            .put("id", entity.id)
            .put("name", entity.name)
            .put("enabled", entity.enabled)
            .toString()
    }

    private suspend fun delete(manager: AutomationRuleManager, args: JSONObject): String {
        val id = ToolArgs.str(args, "id") ?: return error("id is required")
        val resolved = resolveRuleId(manager, id) ?: return error("no rule with id or prefix '$id'")
        val removed = manager.delete(resolved)
        return JSONObject().put("ok", removed).put("id", resolved).toString()
    }

    private suspend fun runs(manager: AutomationRuleManager, args: JSONObject): String {
        val id = ToolArgs.str(args, "id") ?: return error("id is required")
        val resolved = resolveRuleId(manager, id) ?: return error("no rule with id or prefix '$id'")
        // Kept off ToolArgs.int: the historic limit parsing accepts integer
        // STRINGS only ("20.5" → default 20), while ToolArgs.int truncates.
        val limit = (args.optString("limit").toIntOrNull() ?: 20).coerceIn(1, 100)
        val runs = manager.recentRuns(resolved, limit)
        val arr = JSONArray()
        for (r in runs) {
            arr.put(
                JSONObject().apply {
                    put("firedAt", r.firedAt)
                    put("outcome", r.outcome)
                    if (r.sessionId != null) put("sessionId", r.sessionId)
                    if (r.preview != null) put("preview", r.preview)
                },
            )
        }
        return JSONObject().put("ok", true).put("runs", arr).toString()
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Exact id, or a unique prefix of at least 4 characters (cronjob convention). */
    private suspend fun resolveRuleId(manager: AutomationRuleManager, idOrPrefix: String): String? {
        manager.get(idOrPrefix)?.let { return it.id }
        if (idOrPrefix.length < 4) return null
        return manager.all().filter { it.id.startsWith(idOrPrefix) }.singleOrNull()?.id
    }

    private fun hasAnySpecField(args: JSONObject): Boolean =
        args.has("trigger") || args.has("action") || args.has("conditions") ||
            args.has("allowed_tools") || args.has("permission_mode")

    /**
     * Build a [RuleSpec] from flat tool args. With [allowPartial], missing
     * fields stay null on the returned spec (the manager merges with the
     * stored one); otherwise all of name/trigger/action are required.
     *
     * [P2-convergence] String extraction goes through [ToolArgs] like every
     * other tool family.
     */
    private fun parseSpec(args: JSONObject, allowPartial: Boolean): RuleSpec? {
        val name = ToolArgs.str(args, "name").orEmpty()
        val triggerJson = args.optJSONObject("trigger")
        val actionJson = args.optJSONObject("action")
        if (!allowPartial && (name.isEmpty() || triggerJson == null || actionJson == null)) return null

        val trigger: Trigger? = triggerJson?.let {
            runCatching { Trigger.fromJson(it) }.getOrNull()
        } ?: if (allowPartial) null else return null
        // Unknown discriminators (incl. the rejected geofence) pass through:
        // RuleValidation turns them into explicit, model-readable problems.

        val action: RuleAction? = actionJson?.let {
            RuleAction.fromJson(it)
        } ?: if (allowPartial) null else return null

        val conditions = args.optJSONArray("conditions")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(Condition.fromJson(it)) }
                }
            }
        }
        val allowedTools = args.optJSONArray("allowed_tools")?.let { arr ->
            buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
        }

        return RuleSpec(
            name = name,
            trigger = trigger ?: Trigger.Time(),
            conditions = conditions ?: emptyList(),
            action = action ?: RuleAction.Agent(""),
            allowedTools = allowedTools ?: emptyList(),
            permissionMode = ToolArgs.str(args, "permission_mode"),
        )
    }

    private fun error(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
