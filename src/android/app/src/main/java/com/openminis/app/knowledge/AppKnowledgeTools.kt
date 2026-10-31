package com.openminis.app.knowledge

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * Agent-facing surface for the app knowledge packs + local experience library (batch F).
 * Registration and dispatch wiring live in AgentTools / the tool dispatcher; this object only
 * guarantees:
 *
 *  - [definitions] — one [AgentToolDefinition] per tool, static English descriptions ending
 *    with a risk label (app_guide: Read-only; app_note: Writes this app's local notes store).
 *  - [handles] — whether a tool name belongs to this set (`app_guide`, `app_note`).
 *  - [execute] — runs one tool and returns model-readable English text; failures follow the
 *    `Error: <code> — <message>` shape used by the device tool groups.
 *
 * `app_guide(app)` looks a pack up by package name, app id, display name or alias and returns
 * the local notes first (at most [AppNotes.MAX_NOTICE]), then the pack body; unknown inputs
 * get an explicit "no knowledge pack" answer (not an error shape — a miss is a valid result).
 * `app_note(package, text, kind)` records an experience note through the privacy filter and
 * dedupe. Both mark the pack as given for the session so screen-read injection will not
 * repeat it.
 */
object AppKnowledgeTools {

    const val NAME_PREFIX = "app_"
    const val GUIDE = "app_guide"
    const val NOTE = "app_note"

    private const val TOOL_TITLE_PARAM = "tool_title"
    private const val TOOL_TITLE_DESC = "A concise 5-10 word summary shown to the user."

    private fun titleParam() = AgentToolParam("string", TOOL_TITLE_DESC)

    fun definitions(): List<AgentToolDefinition> = listOf(guideDefinition(), noteDefinition())

    fun handles(name: String): Boolean = name == GUIDE || name == NOTE

    fun execute(
        name: String,
        argsJson: String,
        activeSessionId: String?,
        context: Context,
    ): String = when (name) {
        GUIDE -> executeGuide(argsJson, activeSessionId, context)
        NOTE -> executeNote(argsJson, context)
        else -> "Error: unknown_tool — knowledge tools handle only $GUIDE and $NOTE"
    }

    // ── app_guide ──

    private fun guideDefinition() = AgentToolDefinition(
        name = GUIDE,
        description = "Look up the built-in knowledge pack for an app (page structure, common " +
            "entries, common traps, login/security pages) plus notes recorded on this phone. " +
            "Accepts a package name, app id, display name or alias (e.g. com.tencent.mm, " +
            "weixin, WeChat). Notes come first, then the pack body; unknown apps return an " +
            "explicit no-pack answer instead of guessing. Read-only.",
        parameters = mapOf(
            TOOL_TITLE_PARAM to titleParam(),
            "app" to AgentToolParam(
                "string",
                "Package name, app id, display name or alias, e.g. com.tencent.mm, weixin, WeChat.",
            ),
        ),
        required = listOf(TOOL_TITLE_PARAM, "app"),
        propertyOrdering = listOf(TOOL_TITLE_PARAM, "app"),
    )

    private fun executeGuide(argsJson: String, sessionId: String?, context: Context): String {
        val obj = runCatching { JSONObject(argsJson) }.getOrElse {
            return "Error: invalid_args — arguments are not valid JSON"
        }
        val app = obj.str("app")
            ?: return "Error: invalid_args — missing 'app' (package name, app id, display name or alias)"
        val knowledge = AppKnowledgeStore.get(context)
        val entry = knowledge.find(app)
        val pkgs = entry?.packages ?: listOf(app)
        val notes = AppNotesStore.forContext(context)
            .notice(pkgs, entry?.display ?: appLabel(context, app))
        if (entry == null) {
            if (notes != null) return notes
            val covered = knowledge.entries.joinToString(", ") { it.id }
            return "No knowledge pack for '$app'. Covered apps: ${covered.ifEmpty { "none" }}. " +
                "Use app_list to see what is installed, and app_note to record what you learn " +
                "about an app for future runs."
        }
        AppKnowledgeStore.markGiven(sessionId, entry.id)
        return buildString {
            append("[App knowledge: ")
            append(entry.display)
            append(" (")
            append(entry.packages.joinToString(", "))
            append(")]\n")
            if (notes != null) {
                append(notes)
                append("\n\n")
            }
            append(entry.body)
        }
    }

    private fun appLabel(context: Context, pkg: String): String =
        runCatching { AppNotes.labelOf(context, pkg) }.getOrDefault(pkg)

    // ── app_note ──

    private fun noteDefinition() = AgentToolDefinition(
        name = NOTE,
        description = "Record a short UI fact about an app on this phone for future runs: a " +
            "non-obvious entry you found (kind entry), a trap you hit (kind trap), a plain fact " +
            "(kind fact) or a tap path that worked (kind route). One sentence, no personal data " +
            "(emails, phone numbers, verification codes and long digit runs are rejected); " +
            "near-duplicates re-confirm the existing note instead of storing twice. " +
            "Writes this app's local notes store.",
        parameters = mapOf(
            TOOL_TITLE_PARAM to titleParam(),
            "package" to AgentToolParam("string", "Package name of the app, e.g. com.example.app."),
            "text" to AgentToolParam("string", "One-sentence UI fact in the app's own language, e.g. a label and where it sits on screen."),
            "kind" to AgentToolParam(
                "string",
                "Note kind.",
                enumValues = listOf("entry", "trap", "fact", "route"),
            ),
        ),
        required = listOf(TOOL_TITLE_PARAM, "package", "text"),
        propertyOrdering = listOf(TOOL_TITLE_PARAM, "package", "text", "kind"),
    )

    private fun executeNote(argsJson: String, context: Context): String {
        val obj = runCatching { JSONObject(argsJson) }.getOrElse {
            return "Error: invalid_args — arguments are not valid JSON"
        }
        val pkg = obj.str("package") ?: return "Error: invalid_args — missing 'package'"
        val text = obj.str("text") ?: return "Error: invalid_args — missing 'text'"
        val kind = obj.str("kind") ?: "fact"
        val notes = AppNotesStore.forContext(context)
        return when (val outcome = notes.add(pkg, text, kind)) {
            is AppNotes.Outcome.Saved ->
                "Saved note ${outcome.note.id} for $pkg (${outcome.note.kind.name}); " +
                    "app_guide will show it on future runs."
            is AppNotes.Outcome.Duplicate ->
                "Already recorded as ${outcome.note.id} (uses: ${outcome.note.uses}); " +
                    "re-confirmed for the currently installed app version instead of storing twice."
            is AppNotes.Outcome.Rejected -> "Error: rejected — ${outcome.reason}"
        }
    }

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
}
