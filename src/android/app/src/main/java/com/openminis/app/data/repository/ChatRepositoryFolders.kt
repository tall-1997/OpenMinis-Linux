package com.openminis.app.data.repository

import com.openminis.app.data.db.FolderEntity
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.SessionWorkspace
import com.openminis.app.sandbox.WorkspaceMover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Session groups ("folders"), split out of [ChatRepository] so that file stays
 * under its line ceiling. Code says Folder, UI says Group — see FolderEntity
 * for why. [T-android-session-grouping] Ported from iOS ChatStore's Folders
 * section.
 */

fun ChatRepository.observeFolders(): Flow<List<FolderEntity>> = dao.observeFolders()

suspend fun ChatRepository.listFolders(): List<FolderEntity> = dao.listFolders()

suspend fun ChatRepository.getFolder(id: String): FolderEntity? = dao.getFolder(id)

/**
 * Create a group. The description is trimmed and capped at
 * [FolderEntity.DESC_MAX_CHARS]; blank collapses to null so "no
 * description" is one value rather than two.
 */
suspend fun ChatRepository.createFolder(
    name: String,
    description: String? = null,
    origin: String = FolderEntity.ORIGIN_MANUAL,
): FolderEntity {
    val now = System.currentTimeMillis()
    val folder = FolderEntity(
        id = UUID.randomUUID().toString(),
        name = name.trim(),
        origin = origin,
        description = description?.trim()?.take(FolderEntity.DESC_MAX_CHARS)?.ifBlank { null },
        createdAt = now,
        updatedAt = now,
    )
    dao.insertFolder(folder)
    filesDir?.let { SessionWorkspace.ensureProjectDirs(it, folder.id) }
    return folder
}

suspend fun ChatRepository.ensureDefaultWorkspace(name: String): FolderEntity {
    dao.getFolder(FolderEntity.DEFAULT_ID)?.let { return it }
    val now = System.currentTimeMillis()
    val folder = FolderEntity(
        id = FolderEntity.DEFAULT_ID,
        name = name.trim().ifEmpty { "Workspace" },
        origin = FolderEntity.ORIGIN_MANUAL,
        createdAt = now,
        updatedAt = now,
    )
    dao.insertFolder(folder)
    filesDir?.let { SessionWorkspace.ensureProjectDirs(it, folder.id) }
    return folder
}

suspend fun ChatRepository.warmupWorkspaceOwners() {
    // [T-android-startup-splash-hang] Two-column projection over FILED
    // sessions only, replacing `SELECT * FROM sessions ORDER BY updated_at
    // DESC` + full entity hydration. See SessionFolderId for why unfiled
    // rows are skipped without changing behaviour.
    try {
        for (row in dao.listSessionFolderIds()) {
            SessionWorkspace.rememberFolder(row.id, row.folderId)
        }
    } finally {
        // Always release the gate: a failed warmup must degrade to "this
        // session looks unfiled", never wedge every shell behind an await
        // that can never complete.
        SessionWorkspace.completeWarmup()
    }
}

/**
 * Every launch: if a session is filed in a project but still holds private
 * copies of the shared subdirs (the 1.36.14 partial-filing leftover), move
 * those copies into the project. Idempotent; unfiled sessions are skipped.
 */
suspend fun ChatRepository.reconcileWorkspaceFiles() {
    val dir = filesDir ?: return
    val sessions = dao.listSessions()
    withContext(Dispatchers.IO) {
        for (s in sessions) {
            val target = s.folderId ?: continue
            runCatching {
                WorkspaceMover.moveSessionIntoProject(dir, s.id, target)
            }.onFailure {
                AppLogger.warning(FOLDER_TAG, "reconcile failed for ${s.id}: ${it.message}")
            }
        }
    }
}

/**
 * Rename / re-describe. The UUID key is untouched, so members never move —
 * that is the whole reason the group is keyed by UUID and not by name.
 *
 * @param description null LEAVES the stored value alone; an empty string
 *   clears it. Callers that always pass the field through (e.g. a rename
 *   dialog) must therefore seed the field from the current value, or they
 *   will wipe descriptions they never meant to touch.
 */
suspend fun ChatRepository.renameFolder(id: String, name: String, description: String? = null) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return
    dao.renameFolder(
        id = id,
        name = trimmed,
        description = description?.trim()?.take(FolderEntity.DESC_MAX_CHARS),
        updatedAt = System.currentTimeMillis(),
    )
}

/** @return the new pinned state. Bumps `updated_at` so the edit is stamped. */
suspend fun ChatRepository.toggleFolderPin(id: String): Boolean {
    val current = dao.getFolder(id) ?: return false
    val nowPinned = current.pinnedAt == null
    val now = System.currentTimeMillis()
    dao.setFolderPinned(id, if (nowPinned) now else null, now)
    return nowPinned
}

/**
 * Dissolve a group: drop the group row and return its members to ungrouped.
 * **No session is deleted** — this is the ONLY delete operation on a group,
 * and it can never cost the user a conversation.
 *
 * Members are read BEFORE the clear because the ids are the return value
 * (callers use them to refresh, and a future sync layer would need them to
 * push each freed session).
 *
 * @return ids of the sessions that became ungrouped.
 */
suspend fun ChatRepository.dissolveFolder(id: String): List<String> {
    val memberIds = dao.sessionIdsInFolder(id)
    dao.clearFolderForSessions(id)
    dao.deleteFolder(id)
    for (sid in memberIds) SessionWorkspace.rememberFolder(sid, null)
    val dir = filesDir
    if (dir != null && memberIds.isNotEmpty()) {
        copyBackAndMaybeDropProjects(dir, memberIds.associateWith { id })
    }
    return memberIds
}

suspend fun ChatRepository.sessionIdsInFolder(folderId: String): List<String> =
    dao.sessionIdsInFolder(folderId)

/**
 * Move sessions into a group, or out of one when [folderId] is null.
 *
 * Writes only `folder_id`, never `updated_at` — filing is organizational
 * and must not re-sort the session list.
 */
suspend fun ChatRepository.setFolderForSessions(folderId: String?, sessionIds: List<String>) {
    if (sessionIds.isEmpty()) return
    val leavingProject = if (folderId == null) {
        sessionIds.associateWith { SessionWorkspace.folderIdFor(it) }
            .mapNotNull { (sid, fid) -> fid?.let { sid to it } }
            .toMap()
    } else {
        emptyMap()
    }
    for (sid in sessionIds) {
        dao.setSessionFolder(sid, folderId)
        SessionWorkspace.rememberFolder(sid, folderId)
    }
    val dir = filesDir ?: return
    if (folderId != null) {
        SessionWorkspace.ensureProjectDirs(dir, folderId)
        withContext(Dispatchers.IO) {
            for (sid in sessionIds) {
                runCatching {
                    WorkspaceMover.moveSessionIntoProject(dir, sid, folderId)
                }.onFailure {
                    AppLogger.warning(FOLDER_TAG, "file-move failed for $sid: ${it.message}")
                }
            }
        }
    } else {
        copyBackAndMaybeDropProjects(dir, leavingProject)
    }
}

/**
 * File a session only if it is still ungrouped. The condition is part of the
 * UPDATE, so a hand-filed session can never be overridden by an automatic
 * write racing it.
 *
 * @return true if this call actually filed the session.
 */
suspend fun ChatRepository.setFolderIfUnfiled(folderId: String, sessionId: String): Boolean {
    val n = dao.setSessionFolderIfUnfiled(sessionId, folderId)
    if (n > 0) {
        SessionWorkspace.rememberFolder(sessionId, folderId)
        val d = filesDir
        if (d != null) {
            SessionWorkspace.ensureProjectDirs(d, folderId)
            withContext(Dispatchers.IO) {
                runCatching {
                    WorkspaceMover.moveSessionIntoProject(d, sessionId, folderId)
                }.onFailure {
                    AppLogger.warning(FOLDER_TAG, "file-move failed for $sessionId: ${it.message}")
                }
            }
        }
    }
    return n > 0
}

/**
 * Copy a project's shared files into each leaving session's private dir.
 * Drop the project tree only when no remaining member exists AND every
 * leaver copied back without refusal, exception, or conflict. A conflict
 * means the private subdir already had files, so deleting the project
 * would throw away the shared copy.
 */
private suspend fun copyBackAndMaybeDropProjects(
    filesDir: File,
    leavingProject: Map<String, String>,
) {
    if (leavingProject.isEmpty()) return
    withContext(Dispatchers.IO) {
        val independent = mutableSetOf<String>()
        for ((sid, oldProject) in leavingProject) {
            val result = runCatching {
                WorkspaceMover.copyProjectToSession(filesDir, sid, oldProject)
            }.onFailure {
                AppLogger.warning(FOLDER_TAG, "copy-back failed for $sid: ${it.message}")
            }.getOrNull()
            if (result != null && result.independent) independent.add(sid)
        }
        for (proj in leavingProject.values.toSet()) {
            val remaining = dao.sessionIdsInFolder(proj)
            if (remaining.isNotEmpty()) continue
            val leavers = leavingProject.filterValues { it == proj }.keys
            val blocked = leavers - independent
            if (blocked.isNotEmpty()) {
                AppLogger.info(
                    FOLDER_TAG,
                    "keeping project $proj; copy-back incomplete for $blocked",
                )
                continue
            }
            if (SessionWorkspace.deleteProject(filesDir, proj)) {
                AppLogger.info(FOLDER_TAG, "dropped empty project $proj after copy-back")
            }
        }
    }
}

/**
 * Name → group, case- and whitespace-insensitive. Duplicate-tolerant by
 * construction (names are not unique); returns the most recently updated
 * match, which is what [listFolders]' ordering already puts first.
 */
suspend fun ChatRepository.findFolderByName(name: String): FolderEntity? {
    val needle = name.trim().lowercase()
    if (needle.isEmpty()) return null
    return dao.listFolders().firstOrNull { it.name.trim().lowercase() == needle }
}

private const val FOLDER_TAG = "ChatRepository"
