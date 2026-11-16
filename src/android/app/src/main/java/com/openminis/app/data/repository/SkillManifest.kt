package com.openminis.app.data.repository

import android.content.Context

/**
 * [T-skill-manifest] pi coding-agent `src/core/skills.ts:358-388` progressive
 * skill disclosure, ported. The system prompt carries only an
 * `<available_skills>` XML manifest — name + description + location per skill —
 * and the model loads the matched skill's full SKILL.md from `<location>` with
 * the read tool on demand. Resident prompt cost is a few lines per skill no
 * matter how large any single skill body is, and skills flagged
 * `disable-model-invocation` (pi skills.ts:341/359) are hidden from the
 * manifest entirely: they stay reachable only through explicit invocation
 * (`invoke_skill` / user action), never model-discovered.
 *
 * This object owns everything both prompt formats share, so the legacy
 * one-line fragment and the new XML manifest cannot drift apart:
 *  - [selectForPrompt]: the disclosure selection (bundled > recently updated >
 *    most-used, capped at [MAX_SKILLS_IN_PROMPT]) extracted verbatim from
 *    SkillRepository.skillPromptFragment;
 *  - [disclosureNote]: the "N more skills not shown above" tail, byte-identical
 *    to the legacy rendering;
 *  - [buildSkillManifest]: the pi-style XML renderer;
 *  - [resolvePromptFragment]: the pure decision core of the
 *    `skillsProgressiveLoading` settings switch (the OFF branch restores the
 *    legacy fragment, giving the user a real escape hatch).
 *
 * Pure prompt assembly only — no disk, no DB, unit-testable on the plain JVM.
 * The Android-prefs accessor for the switch lives in [SkillPromptPrefs] at the
 * bottom of this file, kept out of the pure object's classpath.
 */
object SkillManifest {

    /** [T-prompt-slim] Disclosure cap, moved verbatim from SkillRepository. */
    const val MAX_SKILLS_IN_PROMPT = 120

    /** [T-prompt-slim] Descriptions are capped at 200 chars in both formats. */
    const val MAX_SKILL_DESC_LENGTH = 200

    /** [skill-startup-refresh] Recency window for the priority-2 slots. */
    const val RECENT_WINDOW_MS = 7L * 24 * 3600 * 1000

    /** [skill-startup-refresh] Extra slots reserved for recently updated skills. */
    const val RECENT_SLOTS = 10

    /**
     * [T-prompt-slim] Name-disclosure budget: with the 120 cap a heavy library
     * can omit 200+ skills — naming up to 200 of them keeps every omitted
     * skill FINDABLE from the prompt (the rest are still one
     * `ls /var/minis/skills/` away).
     */
    private const val MAX_UNDISCLOSED_NAMES = 200

    /** The tool the manifest instructs the model to load skill files with. */
    const val DEFAULT_READ_TOOL = "file_read"

    /** Canonical guest-side SKILL.md path for a skill id (matches skillMdPath). */
    fun locationFor(skillId: String): String = "/var/minis/skills/$skillId/SKILL.md"

    /** [T-skill-manifest] What the prompt discloses: kept vs omitted skills. */
    data class PromptSelection(
        val selected: List<SkillRepository.Skill>,
        val omitted: List<SkillRepository.Skill>,
    )

    /**
     * [T-skill-manifest-hidden-cap] (P2-6) The model-visible subset:
     * `disable-model-invocation` skills are dropped BEFORE the disclosure
     * selection (pi skills.ts:358-372 filters, then formats). Selecting from
     * the unfiltered list let a hidden skill burn one of the 120 cap slots,
     * and a hidden skill squeezed out by the cap resurfaced in
     * [disclosureNote]'s "N more skills ...: <names>" tail — the model learned
     * the name and read path of a skill that must stay invoke_skill-only
     * (the class-doc promise). Filtered skills are equally absent from the
     * omitted list, so they never reach the tail either.
     */
    fun disclosableSkills(enabled: List<SkillRepository.Skill>): List<SkillRepository.Skill> =
        enabled.filter { !it.disableModelInvocation }

    /**
     * The shared disclosure selection, moved verbatim from
     * SkillRepository.skillPromptFragment: up to [maxSkills] entries, priority
     * bundled > recently-updated ([RECENT_SLOTS] slots) > most-used. [nowMs]
     * is injectable so the recency window is testable without a clock.
     */
    fun selectForPrompt(
        enabled: List<SkillRepository.Skill>,
        maxSkills: Int = MAX_SKILLS_IN_PROMPT,
        nowMs: Long = System.currentTimeMillis(),
    ): PromptSelection {
        if (enabled.size <= maxSkills) {
            return PromptSelection(enabled.sortedByDescending { it.updatedAt }, emptyList())
        }
        val picked = linkedMapOf<String, SkillRepository.Skill>() // preserves insertion order + id dedupe
        // Priority 1: bundled
        enabled.filter { it.importSource == SkillRepository.ImportSource.BUNDLED }
            .forEach { picked.putIfAbsent(it.id, it) }
        // Priority 2: recently updated (within 7 days), up to RECENT_SLOTS more
        val cutoff = nowMs - RECENT_WINDOW_MS
        val recentLimit = minOf(RECENT_SLOTS, maxSkills - picked.size).coerceAtLeast(0)
        enabled.asSequence()
            .filter { it.updatedAt > cutoff && it.id !in picked }
            .sortedByDescending { it.updatedAt }
            .take(recentLimit)
            .forEach { picked.putIfAbsent(it.id, it) }
        // Priority 3: fill remaining slots by useCount (desc)
        if (picked.size < maxSkills) {
            val remaining = maxSkills - picked.size
            enabled.asSequence()
                .filter { it.id !in picked }
                .sortedByDescending { it.useCount }
                .take(remaining)
                .forEach { picked.putIfAbsent(it.id, it) }
        }
        val selected = picked.values.toList()
        val selectedIds = selected.mapTo(HashSet(selected.size)) { it.id }
        return PromptSelection(selected, enabled.filter { it.id !in selectedIds })
    }

    /**
     * The "N more skills not shown above" tail, byte-identical to the legacy
     * fragment's rendering. Leading "\n\n" included; null when nothing was
     * omitted (callers append only when non-null, so no stray separator).
     */
    fun disclosureNote(
        selected: List<SkillRepository.Skill>,
        omitted: List<SkillRepository.Skill>,
    ): String? {
        if (omitted.isEmpty()) return null
        val maxUndisclosed = (MAX_UNDISCLOSED_NAMES - selected.size).coerceAtLeast(0)
        val names = omitted.take(maxUndisclosed).joinToString(", ") { it.name }
        return "\n\n" + omitted.size + " more skills not shown above: " + names +
            ". List /var/minis/skills/ or grep to search all."
    }

    /**
     * Description normalization shared with the legacy fragment: collapse
     * newlines (frontmatter block scalars span lines), cap at
     * [MAX_SKILL_DESC_LENGTH]. The cut is the legacy fragment's plain
     * substring + ellipsis, so both formats render the same text for the same
     * skill.
     */
    fun clampDescription(raw: String): String {
        var desc = raw.replace('\n', ' ').replace('\r', ' ').trim()
        if (desc.length > MAX_SKILL_DESC_LENGTH) {
            desc = desc.substring(0, MAX_SKILL_DESC_LENGTH) + "…"
        }
        return desc
    }

    /**
     * pi skills.ts:358 `formatSkillsForPrompt`: header lines plus one XML
     * `<skill>` block (name / description / location) per visible skill.
     * Skills with `disable-model-invocation` are excluded here as well, so the
     * renderer is safe to call with an unfiltered list (pi filters at exactly
     * this spot — skills.ts:359). Returns null when no skill is disclosable.
     *
     * [readTool] names the tool that loads the skill file — [DEFAULT_READ_TOOL]
     * for the standard chat surface, "indirect" for a reader reachable only
     * through another tool (pi's third mode).
     */
    fun buildSkillManifest(
        skills: List<SkillRepository.Skill>,
        readTool: String = DEFAULT_READ_TOOL,
        locationOf: (SkillRepository.Skill) -> String = { locationFor(it.id) },
    ): String? {
        val visibleSkills = skills.filter { !it.disableModelInvocation }
        if (visibleSkills.isEmpty()) return null
        val lines = mutableListOf(
            "The following skills provide specialized instructions for specific tasks.",
            "Use the $readTool tool to load a skill's file when the task matches its description.",
            "Resolve relative paths against the skill's directory (parent of SKILL.md). Use absolute paths in tool calls.",
            "",
            "<available_skills>",
        )
        for (skill in visibleSkills) {
            lines.add("  <skill>")
            lines.add("    <name>${escapeXml(skill.name)}</name>")
            lines.add("    <description>${escapeXml(clampDescription(skill.description))}</description>")
            lines.add("    <location>${escapeXml(locationOf(skill))}</location>")
            lines.add("  </skill>")
        }
        lines.add("</available_skills>")
        return lines.joinToString("\n")
    }

    /**
     * pi skills.ts:390 escaping: the five XML entities. The legacy
     * SkillRepository fragment only escapes `& < >`; the manifest quotes
     * attribute-delimiter characters too so any consumer that re-parses the
     * block as XML sees exactly the authored strings.
     */
    fun escapeXml(str: String): String = str
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /**
     * Pure decision core of the `skillsProgressiveLoading` switch: ON builds
     * the pi-style manifest, OFF falls back to the legacy one-line fragment
     * (byte-identical to pre-manifest builds). Lambda-shaped so the fallback
     * contract is testable without a SkillRepository instance.
     */
    fun resolvePromptFragment(
        progressiveLoading: Boolean,
        manifest: () -> String?,
        legacy: () -> String?,
    ): String? = if (progressiveLoading) manifest() else legacy()

    /**
     * Session-scoped composition for the chat system-prompt build: reads the
     * `skillsProgressiveLoading` switch ([SkillPromptPrefs], default ON) and
     * returns either the pi-style manifest fragment ([SkillRepository.skillManifestFragment])
     * or the legacy one-line fragment ([SkillRepository.skillPromptFragment]).
     * The chat layer stays unaware of the switch — this is the ONLY wiring
     * point, kept to one call line because ChatViewModelPromptExt sits exactly
     * at the architecture-policy 800-line cap.
     */
    fun sessionFragment(
        repo: SkillRepository,
        sessionId: String,
        context: Context,
    ): String? = resolvePromptFragment(
        progressiveLoading = SkillPromptPrefs.isProgressiveLoading(context),
        manifest = { repo.skillManifestFragment(sessionId) },
        legacy = { repo.skillPromptFragment(sessionId) },
    )
}

/**
 * [T-skill-manifest] Persisted state of the `skillsProgressiveLoading` switch,
 * read on every system-prompt build. Default ON: the manifest format is the
 * pi-mirrored default. OFF restores the legacy one-line fragment byte-for-byte,
 * so the switch is a genuine user escape hatch rather than a dead kill switch.
 * Pattern matches [com.openminis.app.data.MemoryGlobalPrefs]: a tiny read/write
 * pair over one boolean in its own prefs file.
 */
object SkillPromptPrefs {
    private const val PREFS = "skill_prompt_prefs"
    private const val KEY_PROGRESSIVE = "skills.progressive_loading"

    fun isProgressiveLoading(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROGRESSIVE, true)

    fun setProgressiveLoading(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROGRESSIVE, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
