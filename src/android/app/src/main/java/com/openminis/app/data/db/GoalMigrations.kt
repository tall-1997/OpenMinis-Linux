package com.openminis.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * [T-goal-drift-reconciler] session_goals.acceptance — the structured
 * definition of done the drift reconciler quotes and the goal_context mount
 * renders every request. Nullable TEXT: pre-migration rows and quick
 * objectives legitimately have none. Declared beside its sibling migration
 * families (Audit/MemoryEmbedding/CompactAsset) to keep AppMigrations.kt
 * under its line ceiling — registration order lives in AppMigrations.ALL.
 */
object GoalMigrations {

    val MIGRATION_35_36: Migration = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("session_goals", "acceptance", "TEXT")
        }
    }
}
