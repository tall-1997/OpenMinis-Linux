package com.openminis.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * [T-memory-embedding-recall] Migrations for `memory_embeddings` (#27) —
 * split out of AppMigrations.kt on the AuditMigrations.kt pattern (that file
 * sits at the 800-line policy cap). Registered in [AppMigrations.ALL]; the
 * drift gates (SchemaMigrationDriftTest.appDbSources and APP_DB_SOURCES in
 * scripts/ci/check_schema_drift.py) list this file explicitly, like
 * AuditMigrations.kt before it.
 */
object MemoryEmbeddingMigrations {

    val MIGRATION_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // memory_embeddings: pure additive — one new table, no existing
            // entity touched, no data rewritten. Room validates the migrated
            // schema EXACTLY against MemoryEmbeddingEntity, so names and
            // affinities below must match it verbatim.
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `memory_embeddings` (
                    `memory_id` TEXT NOT NULL PRIMARY KEY,
                    `scope` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `line` TEXT NOT NULL,
                    `vector` BLOB NOT NULL,
                    `model` TEXT NOT NULL,
                    `dim` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )""".trimIndent()
            )
        }
    }

    val MIGRATION_33_32 = object : Migration(33, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Downgrade 33 → 32. No-op: memory_embeddings stays on disk so a
            // version round-trip keeps the (re-buildable) vectors; the older
            // build never selects from the table.
            // Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
        }
    }
}
