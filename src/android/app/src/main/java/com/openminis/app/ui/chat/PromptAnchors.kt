package com.openminis.app.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * [T-task-anchor] The task the session is CURRENTLY working on, re-injected
 * every turn in the dynamic tail. Long autonomous runs window their history:
 * the task description lives at the very head of the conversation and falls
 * out of `_messages` once the tail cap covers it, so the model literally
 * cannot see what it set out to do mid-run. The latest substantive live user
 * turn wins; the DB copy (the session's first message) is the fallback when
 * the live window has been trimmed.
 */
internal object TaskAnchor {
    const val MAX_CHARS = 1200

    /**
     * [T-task-anchor-edges] Fraction of the cap reserved for the END of the
     * first message. Users paste specs whose acceptance criteria / required
     * tests sit at the tail; a head-only clip silently dropped exactly the
     * binding part. Roughly a third mirrors `preserveSummaryEdges`.
     */
    const val TAIL_CHARS = 400

    const val OMISSION = " …[middle omitted]… "

    const val HEADER =
        "Current task anchor (the latest substantive request from the user in this " +
            "session; if the latest user message starts a different task, that message " +
            "replaces this anchor):"

    /**
     * [T-task-anchor-current] Follow-up tokens that name no task of their own.
     * A session that started with "migrate module A" and later receives
     * "\u7ee7\u7eed" must keep anchoring on the migration, not on a two-character
     * continuation.
     */
    private val THIN_FOLLOW_UPS = setOf(
        "\u7ee7\u7eed", "\u63a5\u7740", "\u7ee7\u7eed\u505a", "\u63a5\u7740\u505a",
        "\u7ee7\u7eed\u5427", "\u63a5\u7740\u5427",
        "\u597d", "\u597d\u7684", "\u55ef", "\u55ef\u55ef", "\u884c", "\u884c\u5427",
        "\u53ef\u4ee5", "\u6ca1\u95ee\u9898", "\u6536\u5230", "\u660e\u767d", "\u660e\u767d\u4e86",
        "\u4e86\u89e3", "\u61c2\u4e86", "\u8c22\u8c22", "\u591a\u8c22", "\u611f\u8c22",
        "\u8f9b\u82e6", "\u8f9b\u82e6\u4e86",
        "ok", "okay", "sure", "cool", "nice", "great", "hi", "hello", "hey",
        "no", "yes", "yep", "nope", "thanks", "thank you", "thx",
        "got it", "understood", "continue", "go on", "keep going", "carry on",
        "retry", "\u91cd\u8bd5", "\u518d\u8bd5\u4e00\u6b21",
    )

    /** True when [text] can stand as a task description on its own. */
    fun isSubstantive(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val normalized = trimmed.lowercase().trimEnd('.', '!', '?', '\u3002', '\uff01', '\uff1f')
        if (normalized in THIN_FOLLOW_UPS) return false
        // [T-task-anchor-ack] A two-word ASCII acknowledgement ("no", "hi")
        // must not replace the task. CJK is dense (2 chars can be a task),
        // so the floors differ.
        val cjk = trimmed.count { it.code in 0x4E00..0x9FFF }
        val alnum = trimmed.count { it.isLetterOrDigit() } - cjk
        return cjk >= 2 || alnum >= 4
    }

    /**
     * Extract the first `"type":"text"` element's `value` from a raw
     * `parts_json` array. The message body has no dedicated column — it
     * lives inside this JSON — so the DAO hands over the raw array and
     * this does the extraction. Non-text parts (toolResult/toolUse) are
     * skipped; a malformed array or no text element returns null.
     * kotlinx.serialization, not org.json: this must stay testable on the
     * pure JVM (the unit-test classpath only has the android.jar stub).
     */
    fun firstUserTextFromParts(partsJson: String?): String? {
        if (partsJson.isNullOrBlank()) return null
        val el = runCatching { Json.parseToJsonElement(partsJson) }.getOrNull() ?: return null
        val arr = el as? JsonArray ?: return null
        for (item in arr) {
            val o = item as? JsonObject ?: continue
            if (o["type"]?.jsonPrimitive?.contentOrNull != "text") continue
            val v = o["value"]?.jsonPrimitive?.contentOrNull
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    /**
     * [T-task-anchor-current] Precedence: the latest substantive user request
     * in the live window > the session's first message (DB) > the first
     * message still in the window.
     *
     * The old precedence was DB-first: a session whose first message was
     * "migrate module A" kept re-asserting that task forever, even after the
     * user had moved on. Long-run trimming then made the stale anchor the
     * only visible "task", so the model resumed it — the stale-task hijack
     * from the other side.
     */
    fun fragment(
        anchorFromDb: String?,
        firstUserInWindow: String?,
        latestUserInWindow: String? = null,
    ): String? {
        val anchor = latestUserInWindow?.takeIf { it.isNotBlank() && isSubstantive(it) }
            ?: anchorFromDb?.takeIf { it.isNotBlank() }
            ?: firstUserInWindow?.takeIf { it.isNotBlank() }
            ?: return null
        val clipped = if (anchor.length > MAX_CHARS) clipEdges(anchor) else anchor
        return HEADER + "\n" + clipped
    }

    /**
     * Head + tail clip: keeps the opening (what to do) and the closing (how it
     * will be judged) of an over-long first message. The omitted middle is
     * labelled so the model knows the anchor is not complete; it can still
     * read the full original from the transcript when it is resident.
     */
    internal fun clipEdges(anchor: String): String {
        val tailSize = TAIL_CHARS.coerceAtMost(MAX_CHARS / 2)
        val headSize = (MAX_CHARS - tailSize - OMISSION.length).coerceAtLeast(0)
        var head = anchor.take(headSize)
        if (head.isNotEmpty() && head.last().isHighSurrogate()) head = head.dropLast(1)
        var tail = anchor.takeLast(tailSize)
        if (tail.isNotEmpty() && tail.first().isLowSurrogate()) tail = tail.drop(1)
        return head + OMISSION + tail
    }
}

/**
 * [T-standing-contract-recency] Per-turn re-assertion of the standing
 * contract (persona + standing-rules authority) in the dynamic tail.
 * Measured on 2026-11-14 via the context-assembly snapshot of a 17k-record
 * session: the persona sits at char 0 of an ~87k-char system prompt and the
 * standing-rules block ~180 lines above the conversation start — after
 * folding, the effective distance from both to the action point is the
 * whole transcript. The head envelope already says STANDING; nothing at
 * recency position re-asserted it, which is exactly the drift window.
 * TaskAnchor proves tail re-assertion works for the task; this gives the
 * persona and the standing rules the same treatment.
 *
 * [T-persona-stack-once] update: the once-only rule governs conversation
 * TURNS (personaHistorySteering stays dead); the dynamic tail is the
 * per-request system-prompt suffix — user content untouched, cache safe.
 *
 * Pure string logic, no Android imports — must stay testable on the pure
 * JVM (same constraint as TaskAnchor).
 */
internal object StandingContract {
    /** Persona (~300) + rules (~400) + omission note (~200) fit with headroom. */
    const val MAX_CHARS = 1200

    /**
     * [T-standing-contract-rules-line] Lists ONLY the rule blocks actually
     * present in this build (project AGENTS.md / session GLOBAL.md /
     * app-global GLOBAL.md) — asserting binding-ness for blocks that were
     * never injected is noise the model reads as pointing at nothing. Null
     * when none exist: the caller then skips the rules line entirely.
     */
    fun rulesLine(
        hasSession: Boolean,
        hasGlobal: Boolean,
        hasProject: Boolean,
        /**
         * [T-standing-contract-budget-truth] Rule blocks that WERE found but
         * the memory budget dropped before rendering. They are not in this
         * prompt, so they must not be described as present; asserting their
         * binding-ness while their text is absent is the worst of both
         * worlds (the model believes it follows rules it cannot read).
         */
        omittedBlocks: List<String> = emptyList(),
    ): String? {
        val blocks = buildList {
            if (hasSession) add("Session-scoped standing rules")
            if (hasGlobal) add("Global standing rules (GLOBAL.md)")
            if (hasProject) add("Project rules (AGENTS.md)")
        }
        val present = if (blocks.isEmpty()) {
            null
        } else {
            val listed = blocks.joinToString(", the ", prefix = "the ", postfix = " blocks")
            "STANDING RULES IN FORCE: $listed.\n" +
                "These are binding constraints on every reply in this session, not " +
                "background. Where a rule conflicts with persona tone, formatting, or " +
                "brevity preference, the rule governs. The user's latest message still " +
                "wins any conflict."
        }
        val omitted = if (omittedBlocks.isEmpty()) {
            null
        } else {
            "Standing-rules note: " + omittedBlocks.joinToString(", ") +
                " are not present in this prompt, so nothing in this reply could rest " +
                "on their content."
        }
        return listOfNotNull(present, omitted).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    fun personalityLine(soulName: String?): String {
        val who = soulName?.trim()?.takeUnless { it.isEmpty() }
        val subject = if (who != null) "the configured persona '$who'" else "the configured persona"
        return "Persona reminder (SOUL.md): stay in $subject for every " +
            "reply this session — its voice, greeting ritual, address style, and stance — " +
            "even after long compactions, topic changes, or tool-heavy stretches. " +
            "This reminder never expires mid-session."
    }

    fun block(
        soulName: String?,
        hasSessionRules: Boolean = false,
        hasGlobalRules: Boolean = false,
        hasProjectRules: Boolean = false,
        omittedRules: List<String> = emptyList(),
        maxChars: Int = MAX_CHARS,
    ): String {
        // [T-standing-rules-authority] ONE allocation path, no early return.
        // The first cut had a "fits as-is" fast path that omitted the warning in
        // a different position than the clipped path — two shapes for one
        // contract. Persona and rules get separate quotas; the omission warning
        // is ALWAYS appended last and never clipped, so a long persona can no
        // longer silently swallow "the rules were not in this prompt".
        val personaCap = maxChars * 2 / 5
        val rulesCap = maxChars - personaCap
        val persona = clip(personalityLine(soulName), personaCap)
        val rules = if (hasSessionRules || hasGlobalRules || hasProjectRules) {
            rulesLine(hasSessionRules, hasGlobalRules, hasProjectRules)?.let { clip(it, rulesCap) }
        } else null
        val omitted = if (omittedRules.isEmpty()) null else rulesLine(
            hasSession = false,
            hasGlobal = false,
            hasProject = false,
            omittedBlocks = omittedRules,
        )
        return listOfNotNull(persona, rules, omitted).joinToString("\n\n")
    }

    private fun clip(text: String, cap: Int): String =
        if (text.length <= cap) text else text.take(cap).trimEnd() + "…"
}

/**
 * [T-standing-contract-budget-truth] What the standing-rules contract may
 * claim, derived from the TWO filters that actually gate a rule block:
 * content dedupe ([kept]) and the memory budget ([admitted]).
 *
 * Pure so the truth table is testable without a ViewModel. A block that was
 * deduped away is intentionally absent (its text is already rendered once);
 * a block that the budget dropped is absent for a worse reason and gets an
 * explicit omission line — never a "BINDING instructions" claim.
 */
internal data class StandingRuleContract(
    val hasSessionRules: Boolean,
    val hasGlobalRules: Boolean,
    val hasProjectRules: Boolean,
    val omittedBlocks: List<String>,
)

internal fun standingRuleContract(
    kept: Set<String>,
    admitted: Set<String>,
): StandingRuleContract {
    fun has(key: String) = key in kept && key in admitted
    fun omitted(key: String) = key in kept && key !in admitted
    return StandingRuleContract(
        hasSessionRules = has("session-global"),
        hasGlobalRules = has("app-global"),
        hasProjectRules = has("project-rules"),
        omittedBlocks = buildList {
            if (omitted("session-global")) add("session-scoped rules (/var/minis/memory/GLOBAL.md)")
            if (omitted("app-global")) add("global standing rules (Settings -> Memory GLOBAL.md)")
            if (omitted("project-rules")) add("project rules (AGENTS.md)")
        },
    )
}
