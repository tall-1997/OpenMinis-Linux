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
            // Downgrade 34 -> 33: the dormant_thinking_override column must be
            // REMOVED, not kept. The older build's Room validates every table
            // it knows against its compiled schema (onValidateSchema ->
            // TableInfo equality) right after the downgrade runs, so one
            // extra column in `sessions` makes validation fail and
            // RoomOpenHelper throws IllegalStateException at first open — the
            // version round-trip was a CRASH, not a lost park. This migration
            // makes the downgrade safe by landing the file exactly on the 33
            // shape; 33.json is the single source of truth for every DDL
            // literal below.
            // The park value itself is deliberately discarded: the user's
            // visible thinking level lives in thinking_override and survives.
            // Rebuild recipe (SQLite has no ALTER TABLE DROP COLUMN on the
            // API levels this build ships to, and `sessions` cannot be
            // rename-danced in place: it carries indices and is FK-referenced
            // by messages / compact_markers / message_versions). Foreign-key
            // enforcement is switched on only in Room's onOpen — never during
            // a migration callback — so dropping the parent here cannot
            // cascade and the child tables keep their rows.
            // Clear any scratch table a partial earlier attempt left behind.
            db.execSQL("DROP TABLE IF EXISTS `sessions_mig`")
            // 33.json `sessions` createSql, retargeted at the scratch table:
            // the full 33 column set, no dormant_thinking_override.
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `sessions_mig` (
                    `id` TEXT NOT NULL,
                    `title` TEXT,
                    `model_id` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `category` TEXT,
                    `last_message` TEXT,
                    `model_binding` TEXT,
                    `source` TEXT,
                    `memory_enabled` INTEGER NOT NULL,
                    `pinned_at` INTEGER,
                    `edit_count` INTEGER NOT NULL,
                    `thinking_override` TEXT,
                    `folder_id` TEXT,
                    `permission_mode` TEXT,
                    `branch_of` TEXT,
                    `branch_point` TEXT,
                    `archived_at` INTEGER,
                    PRIMARY KEY(`id`)
                )""",
            )
            // Carry every surviving column over, then swap the table in.
            db.execSQL(
                "INSERT INTO `sessions_mig` (`id`, `title`, `model_id`, `created_at`, " +
                    "`updated_at`, `category`, `last_message`, `model_binding`, `source`, " +
                    "`memory_enabled`, `pinned_at`, `edit_count`, `thinking_override`, " +
                    "`folder_id`, `permission_mode`, `branch_of`, `branch_point`, `archived_at`) " +
                    "SELECT `id`, `title`, `model_id`, `created_at`, `updated_at`, `category`, " +
                    "`last_message`, `model_binding`, `source`, `memory_enabled`, `pinned_at`, " +
                    "`edit_count`, `thinking_override`, `folder_id`, `permission_mode`, " +
                    "`branch_of`, `branch_point`, `archived_at` FROM `sessions`",
            )
            db.execSQL("DROP TABLE `sessions`")
            db.execSQL("ALTER TABLE `sessions_mig` RENAME TO `sessions`")
            // 33.json indices for sessions — a rebuilt table carries none.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sessions_folder_id` ON `sessions` (`folder_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sessions_updated_at` ON `sessions` (`updated_at`)",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_33_34, MIGRATION_34_33)
}
