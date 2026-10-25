package com.openminis.app.evolution

import java.io.File

data class LearnedItem(
    val raw: String,
    val body: String,
    val scene: SceneTag,
    val core: Boolean,
)

/**
 * Machine-managed region inside `minis-global/memory/LEARNED.md`.
 * Only the marker block is rewritten; surrounding prose is preserved.
 *
 * Optional prefixes on a bullet: `[core]` and `[backend]|[workflow]|[writing]`.
 */
class LearnedPrefsStore(private val file: File) {

    @Synchronized
    fun readItems(): List<String> = readParsed().map { it.raw }

    @Synchronized
    fun readParsed(): List<LearnedItem> {
        val body = readRaw()
        val start = body.indexOf(START)
        val end = body.indexOf(END)
        if (start < 0 || end < 0 || end <= start) return emptyList()
        val region = body.substring(start + START.length, end)
        return region.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") && it.length > 2 }
            .map { parseItem(it) }
            .toList()
    }

    @Synchronized
    fun snapshotRegion(): String {
        val body = readRaw()
        val start = body.indexOf(START)
        val end = body.indexOf(END)
        if (start < 0 || end < 0 || end <= start) return ""
        return body.substring(start + START.length, end)
    }

    @Synchronized
    fun restoreRegion(snapshot: String) {
        writeRegion(snapshot)
    }

    @Synchronized
    fun addBullet(text: String, scene: SceneTag = SceneTag.GENERAL, core: Boolean = false): Boolean {
        val formatted = formatBullet(text, scene, core) ?: return false
        val items = readParsed().toMutableList()
        if (items.any { it.body.equals(parseItem(formatted).body, ignoreCase = true) }) return false
        items.add(parseItem(formatted))
        writeItems(items.map { it.raw })
        return true
    }

    @Synchronized
    fun removeBullet(text: String): Boolean {
        val target = parseItem(normalize(text) ?: return false).body
        val items = readParsed().toMutableList()
        val removed = items.removeAll { it.body.equals(target, ignoreCase = true) }
        if (removed) writeItems(items.map { it.raw })
        return removed
    }

    /** Settings preview: every scene, still capped. */
    @Synchronized
    fun previewFragment(): String? = promptFragment(SceneTag.GENERAL, allScenes = true)

    /**
     * Prompt fragment for [scene]. Always includes untagged / general rules.
     * Over the byte/item cap, CORE bullets win, then most recently added.
     */
    @Synchronized
    fun promptFragment(scene: SceneTag = SceneTag.GENERAL, allScenes: Boolean = false): String? {
        val matching = readParsed().filter {
            allScenes || it.scene == SceneTag.GENERAL || it.scene == scene
        }
        if (matching.isEmpty()) return null
        val ranked = matching.sortedWith(
            compareByDescending<LearnedItem> { it.core }.thenBy { matching.indexOf(it) },
        )
        val header = headerFor(scene)
        var kept = ranked
        while (kept.isNotEmpty()) {
            val body = header + kept.joinToString("\n") {
                if (allScenes) it.raw else "- ${it.body}"
            }
            if (body.toByteArray(Charsets.UTF_8).size <= MAX_FRAGMENT_BYTES &&
                kept.size <= MAX_ITEMS
            ) {
                return body
            }
            val dropIdx = kept.indexOfFirst { !it.core }.takeIf { it >= 0 } ?: 0
            kept = kept.filterIndexed { i, _ -> i != dropIdx }
        }
        return null
    }

    private fun writeItems(items: List<String>) {
        val region = if (items.isEmpty()) {
            "\n"
        } else {
            buildString {
                append('\n')
                items.forEach { append(it).append('\n') }
            }
        }
        writeRegion(region)
    }

    private fun writeRegion(region: String) {
        val existing = if (file.exists()) {
            runCatching { file.readText() }.getOrDefault("")
        } else {
            ""
        }
        val next = if (existing.contains(START) && existing.contains(END)) {
            val start = existing.indexOf(START)
            val end = existing.indexOf(END)
            existing.substring(0, start + START.length) + region + existing.substring(end)
        } else {
            TEMPLATE.replace("$START\n$END", START + region + END)
        }
        file.parentFile?.mkdirs()
        file.writeText(next)
    }

    private fun readRaw(): String {
        if (!file.exists()) return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    companion object {
        const val FILE_NAME = "LEARNED.md"
        const val START = "<!-- LEARNED-PREFS-START -->"
        const val END = "<!-- LEARNED-PREFS-END -->"
        const val MAX_ITEMS = 12
        const val MAX_FRAGMENT_BYTES = 2048

        private val TAG_RE = Regex(
            """^-\s*(?:\[core]\s*)?(?:\[(backend|workflow|writing|general)]\s*)?(.*)$""",
            RegexOption.IGNORE_CASE,
        )

        private val TEMPLATE = """
            |# Learned preferences
            |
            |User-approved rules from the evolution pipeline. Only the marked region is machine-managed.
            |
            |$START
            |$END
            |
        """.trimMargin()

        fun normalize(text: String): String? {
            val compact = text.trim().replace(Regex("\\s+"), " ")
            if (compact.isBlank()) return null
            val body = compact.removePrefix("- ").trim()
            if (body.isBlank()) return null
            return "- ${body.take(160)}"
        }

        fun formatBullet(text: String, scene: SceneTag, core: Boolean): String? {
            val parsed = parseItem(normalize(text) ?: return null)
            val tags = buildString {
                if (core) append("[core] ")
                if (scene != SceneTag.GENERAL) append("[${scene.raw}] ")
            }
            return "- $tags${parsed.body}".trimEnd()
        }

        fun parseItem(raw: String): LearnedItem {
            val line = raw.trim()
            val core = line.contains("[core]", ignoreCase = true)
            val match = TAG_RE.matchEntire(line)
            val scene = SceneTag.from(match?.groupValues?.getOrNull(1))
            val body = match?.groupValues?.getOrNull(2)?.trim()?.ifBlank { null }
                ?: line.removePrefix("- ").replace(Regex("""\[core]\s*""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\[(backend|workflow|writing|general)]\s*""", RegexOption.IGNORE_CASE), "")
                    .trim()
            return LearnedItem(raw = line, body = body, scene = scene, core = core)
        }

        private fun headerFor(scene: SceneTag): String {
            val extra = if (scene == SceneTag.GENERAL) {
                ""
            } else {
                " Scene=${scene.raw}: include general rules plus this scene."
            }
            return "Learned preferences (LEARNED.md — user-approved standing rules.$extra Follow them unless the current message contradicts them. Do not edit this file.):\n"
        }
    }
}
