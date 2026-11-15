package com.openminis.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * [T-thinking-dormant-restore] (P2 1-8) Migrations for
 * `sessions.dormant_thinking_override` — the ceiling-sync park slot. Split out
 * of AppMigrations.kt on the MemoryEmbeddingMigrations.kt pattern (AppMigrations
 * sits at the 800-line policy cap). Registered in [AppMigrations.ALL]; the
 * drift gates (SchemaMigrationDriftTest.appDbSources and APP_DB_SOURCES in
 * scripts/ci/check_schema_drift.py) list this file explicitly, like
 * MemoryEmbeddingMigrations.kt before it.
 */
object ThinkingDormancyMigrations {

    val MIGRATION_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // sessions.dormant_thinking_override: pure additive — one nullable
            // column, no existing entity field touched, no data rewritten.
            // Existing rows read NULL (= no park), the exact pre-migration
            // state.
            db.addColumnIfMissing("sessions", "dormant_thinking_override", "TEXT")
        }
    }

    val MIGRATION_34_33 = object : Migration(34, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Downgrade 34 → 33. No-op: keep the column — the older build never
            // selects it, so a version round-trip loses only the park (the
            // user's visible thinking level lives in thinking_override and is
            // untouched).
            // Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_33_34, MIGRATION_34_33)
}
