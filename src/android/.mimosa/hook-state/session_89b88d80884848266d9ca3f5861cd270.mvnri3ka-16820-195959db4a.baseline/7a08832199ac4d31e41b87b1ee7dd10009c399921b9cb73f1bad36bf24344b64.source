package com.openminis.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room retries a migration only if the version bump rolled back. SQLite still
 * reports "duplicate column name" when a previous partial upgrade already
 * added the column and the user_version did not move. Skip the ALTER then.
 */
fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, definition: String) {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIdx = cursor.getColumnIndex("name")
        if (nameIdx < 0) return@use
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIdx) == column) return
        }
    }
    execSQL("ALTER TABLE `$table` ADD COLUMN $column $definition")
}
