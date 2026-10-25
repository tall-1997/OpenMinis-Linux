package com.openminis.app.data.db

import androidx.room.ColumnInfo

/**
 * [T-android-startup-splash-hang] Projection for the launch-time workspace
 * warmup: the two columns it actually uses, and only for FILED sessions.
 *
 * The warmup used to run `SELECT * FROM sessions ORDER BY updated_at DESC` and
 * hydrate a 13-column entity (including the `last_message` preview text) for
 * every session the user has ever created, just to copy `id` and `folder_id`
 * into an in-memory map. Unfiled sessions contributed nothing at all — the map
 * starts empty each process, so `rememberFolder(id, null)` was a `remove()` on
 * a key that was never there.
 */
data class SessionFolderId(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "folderId") val folderId: String,
)
