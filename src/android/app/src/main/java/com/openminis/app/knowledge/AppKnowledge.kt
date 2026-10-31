package com.openminis.app.knowledge

import android.content.Context
import android.content.res.AssetManager

/**
 * Built-in app knowledge packs (`assets/knowledge/apps/<id>/APP.md`): pure facts about an
 * app's UI (page structure, common entries, traps, login/security pages), written in Chinese,
 * indexed lazily on first use. Two indexes are built: package name -> pack and
 * name/alias -> pack. The same instance is shared by `app_launch` injection, `ui_read`
 * injection and the `app_guide` tool through [AppKnowledgeStore].
 *
 * The pack text is intended for model consumption only; it is not user-facing UI.
 */
class AppKnowledge internal constructor(private val source: () -> List<Pair<String, String>>) {

    data class Entry(
        val id: String,
        val name: String,
        val display: String,
        val description: String,
        val packages: List<String>,
        val aliases: List<String>,
        val body: String,
    )

    /** All parseable packs, sorted by id. Unreadable/invalid files are skipped, never thrown. */
    val entries: List<Entry> by lazy {
        source().mapNotNull { (id, text) -> runCatching { parse(id, text) }.getOrNull() }.sortedBy { it.id }
    }

    private val byPackage: Map<String, Entry> by lazy {
        val map = HashMap<String, Entry>()
        entries.forEach { e -> e.packages.forEach { p -> map.putIfAbsent(p.trim().lowercase(), e) } }
        map
    }

    /** id / name / display / alias (case-insensitive, first writer wins for stable lookup). */
    private val byName: Map<String, Entry> by lazy {
        val map = HashMap<String, Entry>()
        entries.forEach { e ->
            (listOf(e.id, e.name, e.display) + e.aliases).forEach { n ->
                val key = n.trim().lowercase()
                if (key.isNotEmpty()) map.putIfAbsent(key, e)
            }
        }
        map
    }

    /** Pack installed under an exact package name (case-insensitive), or null. */
    fun forPackage(pkg: String): Entry? = byPackage[pkg.trim().lowercase()]

    /**
     * By exact package, id, name, display or alias (case-insensitive); falls back to the first
     * name containing the query when it is at least two characters long ("weixin" hits the
     * WeChat pack).
     */
    fun find(query: String): Entry? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val exact = byPackage[q.lowercase()] ?: byName[q.lowercase()]
        if (exact != null) return exact
        if (q.length < 2) return null
        return entries.firstOrNull { e ->
            (listOf(e.id, e.display) + e.aliases).any { it.lowercase().contains(q.lowercase()) }
        }
    }

    /**
     * Injection payload for [pkgOrName] (package, id, display name or alias):
     * `---` separator, a `[App knowledge: <display>]` header, then the body — capped at
     * [MAX_INJECT_CHARS]. Null when no pack matches.
     */
    fun guideFor(pkgOrName: String): String? = find(pkgOrName)?.let { injectionText(it) }

    companion object {
        const val ASSETS_DIR = "knowledge/apps"
        const val MAX_INJECT_CHARS = 2500

        /** Read every `knowledge/apps/<id>/APP.md`; unreadable entries are silently dropped. */
        fun fromAssets(assets: AssetManager): List<Pair<String, String>> =
            assets.list(ASSETS_DIR).orEmpty().mapNotNull { id ->
                runCatching {
                    id to assets.open("$ASSETS_DIR/$id/APP.md").bufferedReader().use { it.readText() }
                }.getOrNull()
            }

        /**
         * APP.md format: a `---` frontmatter of `key: value` lines (values are plain scalars or
         * a JSON-ish string array `["a", "b"]`), closed by a second `---`, then the Markdown body.
         * Hand-parsed on purpose: no YAML/serialization dependency. Unknown keys are ignored;
         * lines without a colon, blank lines and `#` lines are skipped (not fatal). A missing or
         * empty `packages` list, an unterminated frontmatter or an empty body throws.
         *
         * [P2-convergence] The frontmatter scan itself is the shared
         * [Frontmatter.parse] (same reader SOUL.md uses); only the pack's own
         * strictness — the thrown errors and the `packages` requirement —
         * stays here. CRLF is normalized first, exactly as before.
         */
        fun parse(id: String, text: String): Entry {
            val normalized = text.replace("\r\n", "\n")
            val fm = Frontmatter.parse(normalized) ?: throw IllegalArgumentException(
                "$id: " +
                    // Distinguish the two failure shapes the pack used to report separately.
                    if (normalized.lineSequence().firstOrNull()?.trim() == "---") "unterminated frontmatter"
                    else "missing frontmatter",
            )

            val name = fm.value("name")?.ifEmpty { null }
            val display = fm.value("display")?.ifEmpty { null }
            val description = fm.value("description").orEmpty()
            val packages = fm.value("packages")?.let { parseList(it) } ?: emptyList()
            val aliases = fm.value("aliases")?.let { parseList(it) } ?: emptyList()
            require(packages.isNotEmpty()) { "$id: no packages" }
            val body = fm.body.trim()
            require(body.isNotEmpty()) { "$id: empty body" }
            return Entry(id, name ?: id, display ?: name ?: id, description, packages, aliases, body)
        }

        /** `["a", "b"]` or bare `a` -> list; anything else -> empty. */
        private fun parseList(raw: String): List<String> {
            val s = raw.trim()
            if (s.isEmpty()) return emptyList()
            if (!s.startsWith("[") || !s.endsWith("]")) return listOf(s)
            return s.substring(1, s.length - 1)
                .split(',')
                .mapNotNull { item ->
                    item.trim().removeSurrounding("\"").trim().takeIf { it.isNotEmpty() }
                }
        }

        /** Header + body capped at [MAX_INJECT_CHARS] so app_launch / ui_read stay bounded. */
        fun injectionText(entry: Entry): String {
            val header = "---\n[App knowledge: ${entry.display}]\n"
            val budget = MAX_INJECT_CHARS - header.length
            val body = if (entry.body.length <= budget) entry.body else entry.body.take(budget - 1) + "…"
            return header + body
        }
    }
}

/**
 * Process-wide holder for the single [AppKnowledge] plus the per-session "already injected"
 * record. Session ids may arrive as `parentId/childId`; the part before `/` scopes a whole
 * conversation. Callers that have no session (device tools without a threaded session id)
 * share one process bucket, so a pack is still injected at most once per process.
 */
object AppKnowledgeStore {

    /** Foreground package provider, overridable in tests; real impl uses the a11y service. */
    internal var foregroundProvider: () -> String? = {
        runCatching {
            com.openminis.app.accessibility.MinisAccessibilityService.getInstance()
                ?.foregroundPackage()?.first
        }.getOrNull()
    }

    @Volatile
    private var knowledge: AppKnowledge? = null
    private val lock = Any()
    private val given = HashMap<String, MutableSet<String>>()

    /** Sessions without an id land here; see class doc. */
    internal const val PROCESS_BUCKET = "__process__"

    /** Pack text source; overridable in tests (Robolectric's asset shim is unreliable). */
    internal var sourceProvider: (Context) -> List<Pair<String, String>> =
        { context -> AppKnowledge.fromAssets(context.applicationContext.assets) }

    fun get(context: Context): AppKnowledge =
        knowledge ?: synchronized(lock) {
            knowledge ?: AppKnowledge { sourceProvider(context.applicationContext) }
                .also { knowledge = it }
        }

    /** True when [sessionId] has already been given the pack with [key]. */
    fun wasGiven(sessionId: String?, key: String): Boolean = synchronized(lock) {
        given[sessionId ?: PROCESS_BUCKET]?.contains(key) == true
    }

    /** Mark [key] as given for [sessionId]. */
    fun markGiven(sessionId: String?, key: String) {
        synchronized(lock) { given.getOrPut(sessionId ?: PROCESS_BUCKET) { mutableSetOf() }.add(key) }
    }

    /**
     * Injection appended to a successful `app_launch` result when the target app has a pack.
     * Returns null when there is no pack or this session already got it.
     */
    fun afterLaunch(context: Context, pkg: String, sessionId: String?): String? {
        val entry = get(context).forPackage(pkg) ?: return null
        return claim(sessionId, entry)
    }

    /**
     * Injection for `ui_read`: looks up the current foreground package (a11y service) and
     * returns the pack body when it changed to a covered app this session has not seen yet.
     * Honest degradation: no a11y service, own package, or no pack -> null (no injection).
     */
    fun onScreenRead(context: Context, sessionId: String?): String? {
        val fg = runCatching { foregroundProvider() }.getOrNull() ?: return null
        if (fg == context.packageName) return null
        val entry = get(context).forPackage(fg) ?: return null
        return claim(sessionId, entry)
    }

    /** Test support: drop the lazily built index and every session record. */
    fun resetForTests() {
        synchronized(lock) {
            knowledge = null
            given.clear()
        }
    }

    private fun claim(sessionId: String?, entry: AppKnowledge.Entry): String? = synchronized(lock) {
        val bucket = given.getOrPut(sessionId ?: PROCESS_BUCKET) { mutableSetOf() }
        if (!bucket.add(entry.id)) null else AppKnowledge.injectionText(entry)
    }
}
