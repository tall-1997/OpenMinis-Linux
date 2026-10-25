package com.openminis.app.data.repository

import java.io.File

/**
 * Cheap disk signature for `minis-global/skills/<id>/`.
 *
 * Only `SKILL.md` and `requirements.json` affect the registry. A matching
 * signature means [SkillRepository.reloadFromDisk] can skip the SQLite scan
 * and the per-file parse. Same-second rewrites that keep the same length are
 * the only hole; the next real mtime or length change still reloads.
 */
internal object SkillDiskIndex {
    data class Delta(
        val added: List<String>,
        val removed: List<String>,
        val changed: List<String>,
    ) {
        val isEmpty: Boolean
            get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
    }

    fun signatures(root: File): Map<String, String> {
        if (!root.isDirectory) return emptyMap()
        val dirs = root.listFiles() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (dir in dirs.sortedBy { it.name }) {
            if (!dir.isDirectory) continue
            val md = File(dir, "SKILL.md")
            if (!md.isFile) continue
            val req = File(dir, "requirements.json")
            out[dir.name] = signature(md, req)
        }
        return out
    }

    fun diff(before: Map<String, String>, after: Map<String, String>): Delta {
        val added = after.keys.filter { it !in before }.sorted()
        val removed = before.keys.filter { it !in after }.sorted()
        val changed = after.keys.filter { it in before && before[it] != after[it] }.sorted()
        return Delta(added, removed, changed)
    }

    private fun signature(md: File, req: File): String = buildString {
        append(md.lastModified())
        append(':')
        append(md.length())
        append(':')
        if (req.isFile) {
            append(req.lastModified())
            append(':')
            append(req.length())
        } else {
            append('-')
        }
    }
}
