package com.openminis.app.ui.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * [T-prompt-sections] Named-section view of the system prompt assembly. Ported
 * from pi coding-agent src/core/system-prompt.ts:128-228
 * (buildSystemPromptSections / diffSystemPromptSections): pi expresses the
 * prompt as ordered named sections, and when the model/tool state changes it
 * diffs sections by name so only CHANGED sections become a SystemMessage patch
 * — same-content sections never travel, which is what keeps the provider-side
 * prompt-cache prefix stable.
 *
 * This port is byte-conservative: [buildSections] mirrors the appends of
 * [ChatViewModel.buildSystemPromptWithSections] exactly (each section carries
 * its own leading separator, so [join] is a plain concatenation), and
 * [PromptSectionsGoldenTest] pins join() to the legacy buildString byte for
 * byte. The wire format is unchanged — providers still receive one full prompt
 * string; the sections exist so the assembly has ONE structural authority and
 * so [diffSections] can report exactly which region of the prompt changed
 * between builds (cache-economy observability; the evolution path toward pi's
 * section-patch wire format lives in the report of the section port batch).
 *
 * Section names mirror the real segmentation of
 * [ChatViewModel.buildSystemPromptWithSections] and [ContextBlockKey]:
 * SYSTEM_TOOLS covers the whole static body, the tail corresponds to
 * RUNTIME_TAIL. Pure string logic, no Android imports — must stay testable on
 * the pure JVM (same constraint as TaskAnchor / StandingContract).
 */
internal data class PromptSection(
    /** Stable section name (diff key; never user-visible on the wire). */
    val name: String,
    /**
     * Full text of the section INCLUDING any leading blank-line separator it
     * contributes to the prompt, so that [join] is a plain concatenation and
     * byte-identical to the legacy conditional-append assembly.
     */
    val content: String,
)

internal object PromptSections {

    // The static instruction body (identity + tools + rules + dir scheme +
    // tool-call style + android tools + env vars + scheduled tasks). 1:1 with
    // ContextBlockKey.SYSTEM_TOOLS.
    const val SYSTEM_TOOLS = "system_tools"

    // Skill manifest fragment. 1:1 with the skillFragment append (null-gated).
    const val SKILLS = "skills"

    // MCP disclosure fragment. 1:1 with the mcpFragment append (null-gated).
    const val MCP = "mcp"

    // Standing rules (project AGENTS.md / session GLOBAL.md / app-global
    // GLOBAL.md after dedupe) — the byte-stable memory half (GH#357 split).
    const val MEMORY_STANDING = "memory_standing"

    /**
     * [T-standing-rules-authority] Constraint header for the standing-rules
     * section. It states a HIERARCHY (rules outrank persona/formatting) rather
     * than ordering an action — an imperative here would make the model break
     * off mid-task to "go re-read" the rules, which is the fragmentation
     * mechanism #434 removed from the tool-result side.
     */
    private const val STANDING_RULES_HEADER =
        "STANDING RULES — binding constraints, not background. " +
            "Where a rule conflicts with tone, persona, or formatting guidance, the rule governs."

    // Per-turn dynamic tail (WorldBook / learned prefs / daily window / task
    // anchor / runtime context / standing-contract reminder), pre-assembled by
    // assembleDynamicTail. 1:1 with ContextBlockKey.RUNTIME_TAIL.
    const val RUNTIME_TAIL = "runtime_tail"

    /**
     * Map the legacy assembly onto ordered sections. Byte-exactness contract:
     * each optional fragment becomes a section whose content is
     * `"\n\n" + fragment` exactly when the legacy code would have appended it
     * (null-gated for skill / mcp / standing memory — note a non-null BLANK
     * fragment still appends its bare separator, and this representation keeps
     * that byte precisely instead of normalizing it away). [dynamicTail] is the
     * output of [assembleDynamicTail] and carries its own separators, so it is
     * taken verbatim as the final section.
     */
    fun buildSections(
        base: String,
        skillFragment: String?,
        mcpFragment: String?,
        globalMemoryFragment: String?,
        dynamicTail: String,
    ): List<PromptSection> = buildList {
        add(PromptSection(SYSTEM_TOOLS, base))
        if (skillFragment != null) add(PromptSection(SKILLS, "\n\n$skillFragment"))
        if (mcpFragment != null) add(PromptSection(MCP, "\n\n$mcpFragment"))
        if (globalMemoryFragment != null) {
            // [T-standing-rules-authority] Header is additive; the rules body and
            // the section order are byte-preserved.
            add(PromptSection(MEMORY_STANDING, "\n\n" + STANDING_RULES_HEADER + "\n\n" + globalMemoryFragment))
        }
        add(PromptSection(RUNTIME_TAIL, dynamicTail))
    }

    /** Render the prompt: plain concatenation, byte-identical to the legacy buildString. */
    fun join(sections: List<PromptSection>): String = sections.joinToString("") { it.content }

    /**
     * The stable/dynamic boundary length — everything before [RUNTIME_TAIL] is
     * byte-stable for a session within a day (identity + tools + skills + mcp +
     * standing rules), the tail varies per turn. This is the value
     * [ChatViewModel.systemPromptStablePrefixLen] publishes for the Anthropic
     * cache_control breakpoint; derived from the sections so the boundary can
     * never drift from the assembly that feeds it.
     */
    fun stablePrefixLength(sections: List<PromptSection>): Int {
        var len = 0
        for (section in sections) {
            if (section.name == RUNTIME_TAIL) break
            len += section.content.length
        }
        return len
    }

    /**
     * One section patch (pi diffSystemPromptSections record entry).
     * [newContent] == null means the section was REMOVED (pi `null` value);
     * otherwise the section is new or its content changed.
     */
    data class SectionPatch(val name: String, val newContent: String?)

    /**
     * Diff by section NAME: emit a patch for every current section whose
     * content differs from (or has no) previous counterpart, plus a removal
     * patch for every previous section absent from current. Same-content
     * sections — including REORDERED ones — produce no patch, matching pi
     * (the diff is a name-keyed record, position is not part of the key).
     */
    fun diffSections(
        previous: List<PromptSection>,
        current: List<PromptSection>,
    ): List<SectionPatch> {
        val prevByName = previous.associateBy { it.name }
        val patches = mutableListOf<SectionPatch>()
        for (section in current) {
            val old = prevByName[section.name]
            if (old == null || old.content != section.content) {
                patches.add(SectionPatch(section.name, section.content))
            }
        }
        val currentNames = current.mapTo(HashSet()) { it.name }
        for (section in previous) {
            if (section.name !in currentNames) patches.add(SectionPatch(section.name, null))
        }
        return patches
    }

    /**
     * Last published snapshot per session id, for [publishAndDiff], stored as
     * per-section CONTENT HASHES (name → hash). The diff only needs
     * equality per section — a hash change is exactly the "this section moved"
     * signal — so a session's snapshot is ~6 Ints instead of the whole prompt
     * text (tens of KB per session at real prompt sizes, the P2-5 leak).
     * Only STABLE sections are stored: [RUNTIME_TAIL] is excluded from the
     * signal by construction (see [publishAndDiff]), so its hash would be
     * dead weight. [String.hashCode] collisions between different content
     * are possible; the worst case is one missed observability line on a
     * real change, never a wrong wire payload. Bounded by session count and
     * cleared only on process death — no correct moment to drop one (any
     * session may resume).
     */
    private val lastPublished = ConcurrentHashMap<String, Map<String, Int>>()

    /**
     * Publish a fresh build and return a one-line change summary when any
     * STABLE section (everything before [RUNTIME_TAIL]) changed since the last
     * publish of [sessionId]; null when nothing stable changed (or the first
     * build for this session — there is nothing to diff against yet).
     *
     * [RUNTIME_TAIL] is deliberately excluded from the signal: it varies per
     * request (Current date / device language), so including it would log on
     * every request and carry no cache information. This is the observability
     * half of the pi section-diff port: the stable prefix is exactly the
     * prompt-cache-prefix region, so a non-null line means the next request
     * pays a prefix-cache miss on those bytes. Pure — the caller owns logging.
     *
     * Behaviourally identical to the pre-hash implementation (which compared
     * full content via [diffSections]); only the retained memory shrank.
     */
    fun publishAndDiff(sessionId: String, sections: List<PromptSection>): String? {
        if (sessionId.isBlank()) return null
        // Stable sections only (RUNTIME_TAIL excluded by construction).
        val hashes = sections.filter { it.name != RUNTIME_TAIL }
            .associate { it.name to it.content.hashCode() }
        val previous = lastPublished.put(sessionId, hashes) ?: return null
        val changed = hashes.mapNotNull { (name, hash) ->
            if (previous[name] != hash) name else null
        }
        val removed = previous.keys.filterNot { it in hashes }
        if (changed.isEmpty() && removed.isEmpty()) return null
        return buildString {
            append("[SectionDiff] stable sections changed: ")
            append(if (changed.isEmpty()) "(none)" else changed.joinToString(", "))
            if (removed.isNotEmpty()) {
                append("; removed: ")
                append(removed.joinToString(", "))
            }
        }
    }

    /** Test/fixture reset of the per-session snapshot cache. */
    fun clearPublished() {
        lastPublished.clear()
    }
}
