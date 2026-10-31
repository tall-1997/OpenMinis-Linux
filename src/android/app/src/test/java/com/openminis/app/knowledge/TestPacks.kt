package com.openminis.app.knowledge

import java.io.File

/**
 * Loads the real APP.md packs straight from the assets source directory. Robolectric's
 * AssetManager does not surface app assets reliably in this repo (existing tests read the
 * asset tree from disk too), so knowledge tests point [AppKnowledgeStore.sourceProvider] at
 * this instead.
 */
internal object TestPacks {

    private fun findRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(
                File(dir, "src/android/app/src/main/assets/knowledge/apps"),
                File(dir, "src/main/assets/knowledge/apps"),
                File(dir, "app/src/main/assets/knowledge/apps"),
            )) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("knowledge/apps assets dir not found from ${File("").absolutePath}")
    }

    /** Every `<id>/APP.md` on disk as (id, text), matching the runtime asset loader. */
    fun source(): List<Pair<String, String>> {
        val root = findRoot()
        return root.listFiles { f -> f.isDirectory }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { dir ->
                val f = File(dir, "APP.md")
                if (f.isFile) dir.name to f.readText() else null
            }
    }
}
