package com.openminis.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * [T-audit-room-persist] Migrations for `audit_entries` — the durable mirror of
 * SecurityGateImpl's in-memory audit hash chain (fed by the gate's `auditSink`
 * hook; the column contract lives on [AuditEntryEntity]).
 *
 * Split out of AppMigrations.kt when that file hit the 800-line policy cap —
 * the same move AppMigrations.kt itself once made out of AppDatabase.kt. The
 * drift gates (`SchemaMigrationDriftTest.appDbSources` and
 * `APP_DB_SOURCES` in scripts/ci/check_schema_drift.py) list this file
 * explicitly; registration happens in [AppMigrations.ALL].
 */
object AuditMigrations {

    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // audit_entries: pure additive — a new table plus two plain
            // indices, no existing entity touched, no data rewritten. Room
            // validates the migrated schema EXACTLY against AuditEntryEntity,
            // so names, affinities and NOT NULLs below must match it verbatim
            // (including the AUTOINCREMENT primary key form).
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `audit_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `session_id` TEXT NOT NULL,
                    `tool` TEXT NOT NULL,
                    `decision` TEXT NOT NULL,
                    `result_preview` TEXT,
                    `hash` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_audit_entries_session` " +
                    "ON `audit_entries` (`session_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_audit_entries_created_at` " +
                    "ON `audit_entries` (`created_at`)"
            )
        }
    }

    val MIGRATION_31_30 = object : Migration(31, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Downgrade 31 → 30. No-op: audit_entries stays on disk so the
            // audit trail survives a version round-trip; the older build never
            // selects from the table.
            // Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
        }
    }
}
