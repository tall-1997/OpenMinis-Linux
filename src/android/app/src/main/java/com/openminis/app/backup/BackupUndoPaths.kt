package com.openminis.app.backup

import com.openminis.app.agent.SoulStore

/**
 * [T-backup-undo-paths] Which on-disk roots a restore of each [BackupCategory]
 * can overwrite.
 *
 * The undo snapshot has to cover exactly what `BackupImporter` writes, and
 * nothing else: copying `filesDir` wholesale would drag the rootfs, the cache
 * and every log into a 2 GB snapshot; missing one root would leave a restore
 * that cannot be undone for that category. So the mapping lives here as data
 * instead of being re-derived at each call site, and
 * `BackupUndoPathsTest` pins every entry against the importer's real write
 * target.
 *
 * `ROOTFS` / `CACHE` / `VOICE_CORRECTIONS` are absent on purpose:
 * `BackupCategory.backupable` excludes them, so the restore screen cannot tick
 * them and there is nothing to undo. An unknown future category that forgets
 * this table contributes nothing rather than an empty-root snapshot.
 */
internal object BackupUndoPaths {

    const val DB_DIR = "databases"
    const val BODIES_DIR = "bodies"
    const val SESSIONS_DIR = "minis-sessions"
    const val PREFS_DIR = "shared_prefs"
    const val GLOBAL_DIR = "minis-global"

    /**
     * Roots for one category, in a stable order. `databases` leads for CHATS
     * because it is the root whose loss is unrecoverable — a snapshot that ran
     * out of budget before copying it would be worse than useless.
     */
    private fun rootsFor(category: BackupCategory): List<String> = when (category) {
        // importChats: Room rows + per-message bodies + per-session artefacts.
        BackupCategory.CHATS -> listOf(DB_DIR, BODIES_DIR, SESSIONS_DIR)
        // importSharedFiles / importSkills / importMemory / importMcpServers.
        BackupCategory.SHARED_FILES -> listOf("$GLOBAL_DIR/shared")
        BackupCategory.SKILLS -> listOf("$GLOBAL_DIR/skills")
        // [GH#299] Same definition the MEMORY exporter/importer use: the
        // migrated Soul icon lives in this tree, so undo must snapshot it too.
        BackupCategory.MEMORY -> listOf(SoulStore.MEMORY_SUBDIR)
        BackupCategory.MCP_SERVERS -> listOf("$GLOBAL_DIR/mcp-servers")
        // importProviders / importEnvironmentVariables both land in prefs.
        BackupCategory.PROVIDERS -> listOf(PREFS_DIR)
        BackupCategory.ENVIRONMENT_VARIABLES -> listOf(PREFS_DIR)
        BackupCategory.ROOTFS, BackupCategory.CACHE, BackupCategory.VOICE_CORRECTIONS -> emptyList()
    }

    /**
     * Union of the roots [categories] can touch. Deduplicated but order
     * preserving: PROVIDERS + ENVIRONMENT_VARIABLES collapse to one
     * `shared_prefs` entry instead of snapshotting the same directory twice.
     */
    fun pathsFor(categories: Set<BackupCategory>): List<String> {
        val out = LinkedHashSet<String>()
        for (category in BackupCategory.entries) {
            if (category !in categories) continue
            out.addAll(rootsFor(category))
        }
        return out.toList()
    }
}
