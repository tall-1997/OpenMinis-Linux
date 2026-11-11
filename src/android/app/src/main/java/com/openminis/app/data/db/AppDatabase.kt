package com.openminis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        MessageEntity::class,
        CompactMarkerEntity::class,
        WebAppShortcutEntity::class,
        FolderEntity::class,
        CodeSymbolEntity::class,
        CodeEdgeEntity::class,
        SessionGoalEntity::class,
        MessageVersionEntity::class,
        com.openminis.app.automation.AutomationRuleEntity::class,
        com.openminis.app.automation.AutomationRunEntity::class,
        ProjectExperienceEntity::class,
        com.openminis.app.data.instruction.InstructionStateEntity::class,
        com.openminis.app.data.instruction.InstructionUpdateEntity::class,
    ],
    version = 29, // keep DatabaseVersionGuard.CODE_DB_VERSION in lockstep
    // [T-android-downgrade-compat] Kept ON so MigrationTestHelper and CI can
    // validate migrations against the committed schema json. The chain now
    // runs 12-29 complete. 13.json and 17.json were never exported by a
    // compiling build (Room only writes the version it compiles) and were
    // reconstructed in place from their neighbours: 13 = 12 + the
    // code_symbols/code_edges entities copied verbatim from 16.json, 17 =
    // 16 + the sessions.permissionMode field copied verbatim from 18.json —
    // exactly what MIGRATION_12_13 / MIGRATION_16_17 produce. Their
    // identityHash values are carried over from the neighbouring export as
    // placeholders: MigrationTestHelper validates TABLE SHAPE, not the hash
    // (only Room's runtime open check reads it), so replay validation is
    // sound. If a future test ever asserts on those hashes, regenerate both
    // by compiling at version 13/17.
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun chatSearchDao(): ChatSearchDao
    abstract fun webAppShortcutDao(): WebAppShortcutDao
    abstract fun codeIndexDao(): CodeIndexDao
    abstract fun goalDao(): GoalDao
    abstract fun messageVersionDao(): MessageVersionDao
    abstract fun projectExperienceDao(): ProjectExperienceDao
    abstract fun instructionStateDao(): com.openminis.app.data.instruction.InstructionStateDao
    abstract fun automationRuleDao(): com.openminis.app.automation.AutomationRuleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * [T-backup-undo-restore] Closes the singleton so a restore can replace
         * the `minis.db` files on disk.
         *
         * The undo path swaps database files wholesale; leaving a live Room
         * connection open would keep writing into the replaced inode, and the
         * next `get()` would hand out a handle to a deleted file. Closing and
         * dropping the INSTANCE is what makes the on-disk state authoritative
         * again — the process restart that follows rebuilds every repository.
         */
        fun closeForRestore() {
            synchronized(this) {
                runCatching { INSTANCE?.close() }
                INSTANCE = null
            }
        }



        /**
         * compact_markers: add Phase-A id-first boundary columns. The legacy
         * sort_order columns stay for backfill; when both are present the
         * id-first fields win on lookup (see ChatDao.latestCompactMarker).
         */

        /**
         * T239: per-session thinking-mode override. Nullable so existing
         * sessions transparently keep "unset" semantics; only sessions where
         * the user explicitly chooses a level start storing a non-null value.
         */

        /**
         * T-pwa-1: pwa_shortcuts table backs the home-screen PWA pinning
         * flow. Pure additive migration — no existing entity is modified
         * and no data is rewritten.
         *
         * Superseded by MIGRATION_8_9 below (Pwa → WebApp rename); kept
         * here so users who already migrated from <=6 land on a
         * consistent state before the rename runs.
         */

        /**
         * compact_markers: add `version` column for marker schema versioning.
         * Mirrors iOS Phase v2 — version=1 = legacy multi-field model,
         * version=2 = simplified id-only anchor model. Existing rows default
         * to 1 so legacy resolution code keeps running for them.
         */

        /**
         * Pwa → WebApp rename: copy every row from `pwa_shortcuts` into a
         * new `webapp_shortcuts` table with identical schema, then drop
         * the old table. Row contents (UUIDs, html paths, icon refs) are
         * preserved verbatim — only the table name changes — so existing
         * in-app shortcut lists keep showing the same entries.
         *
         * Note: pinned launcher icons created before this rename still
         * carry the old `ACTION_OPEN_PWA` intent action and will be dead
         * after the upgrade (manifest no longer registers it). The user
         * has to re-pin from inside the app. Per
         * `feedback_no_destructive_git` we do NOT silently delete data —
         * the DB row stays, only the launcher-side icon dies.
         */

        /**
         * [T-error-persist-android] messages.error_info — persist the terminal
         * error sticker on an assistant turn so the inline error survives a
         * session reload (mirrors iOS messages.error_info). Pure additive,
         * nullable column; existing rows read back NULL (= no error). No data
         * rewrite.
         */

        /**
         * [T-android-session-grouping] Session groups. Adds the `folders` table
         * and `sessions.folder_id`.
         *
         * Purely additive: existing sessions read back `folder_id = NULL`
         * (= ungrouped), which is exactly the pre-migration behaviour, so no
         * data is rewritten and a downgrade loses only the grouping.
         *
         * `folder_id` carries NO foreign key on purpose — an id pointing at a
         * group that is not present locally must render as ungrouped rather
         * than fail a constraint (see ChatSessionEntity.folderId). The index is
         * plain and non-unique: many sessions share one group.
         */


        /**
         * [T-token-attribution-snapshot] Per-message model attribution.
         *
         * Four nullable columns, no DEFAULT. `ADD COLUMN` is an O(1) metadata
         * change in SQLite — existing rows are untouched and simply read NULL,
         * which is the signal the Usage page uses to mark a row "estimated"
         * rather than "measured". A `NOT NULL DEFAULT ''` would make old rows
         * indistinguishable from new ones that genuinely have no model.
         */

        /**
         * [T-code-graph] Adds the code_symbols and code_edges tables for the
         * lightweight code index used by CodeGraphTool. Pure additive — no
         * existing entity is modified and no data is rewritten.
         */

        /**
         * Downgrade 13 → 12. Intentionally a no-op: the two code index tables
         * remain on disk so the index survives a version round-trip and the
         * next 1.31+ upgrade doesn't need to rebuild from scratch.
         * Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
         */

        /**
         * [T-kanban] Adds the kanban_tasks table for the task board.
         * Pure additive — no existing entity is modified and no data is rewritten.
         */

        /**
         * Downgrade 14 → 13. Intentionally a no-op: the kanban_tasks table
         * remains on disk so tasks survive a version round-trip. The older
         * build simply won't see the column, which is the safe fallback.
         * Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
         */

        /**
         * [T-kanban-removal] Drops the kanban_tasks table (feature removed:
         * no entry point, no readers). Pure destructive on a table nothing
         * references anymore; chat/code-index data is untouched.
         */

        /**
         * Downgrade 15 -> 14. No-op: the table is already gone and the older
         * build only touched it from the removed Kanban screen.
         * Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
         */

        /**
         * [T-android-sessions-updated-at-index] 15 -> 16: the session-list
         * query (`SELECT * FROM sessions ORDER BY updated_at DESC`) ran as a
         * full scan + sort; the index lets SQLite walk it in order.
         */

        /**
         * Downgrade 16 -> 15: drop the index; the old schema has no column constraints.
         * Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
         */


        /**
         * Downgrade 18 → 17. No-op (keep summary_chunks).
         * Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
         */

        /**
         * Adds body metadata only. Does not rewrite parts_json. length(CAST AS BLOB)
         * is the UTF-8 byte count and stays inside SQLite. Preview for oversized
         * rows is a short JSON text part built with json_quote, so the hot path
         * can show the session without pulling the cell into Java.
         */

        /**
         * Downgrade 19 → 18. No-op (keep the body-metadata columns).
         * Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
         */


        /**
         * Downgrade 20 → 19. No-op (keep session_goals).
         * Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
         */

        /** Re-number legacy duplicate cursors deterministically before enforcing uniqueness. */

        /**
         * Downgrade 21 → 20. Re-creates the session/sort index as non-unique —
         * the exact reverse of 20_21.
         * Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
         */

        /** [T-msg-version-archive] message_versions — see MessageVersionEntity. */

        /**
         * Downgrade 22 → 21. Drops message_versions (the table did not exist
         * for the older build). Destructive but by design — see MIGRATION_21_22.
         * Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
         */

        /**
         * [T-android-automation-rules] automation_rules + automation_runs for
         * the conditional-trigger automation engine. Pure additive — no
         * existing entity is modified and no data is rewritten.
         *
         * The spec column is a validated JSON blob ([com.openminis.app.automation.RuleSpec]),
         * so future trigger kinds do not need another migration. The runs
         * index serves both the per-rule newest-first history and the
         * rolling-24h rate-limit COUNT (rule_id, fired_at).
         */

        /**
         * Downgrade 23 → 22. Intentionally a no-op: both automation tables
         * stay on disk so a version round-trip keeps rules and run history
         * (the same policy as the code-index and session_goals downgrades).
         * The older build simply ignores the extra tables.
         *
         * Downgrade backstop note: this migration is also what lets the
         * secondary `AppDatabase.getInstance` call sites (BackupViewModel,
         * ChatViewModelExecuteToolExt, ProviderRepository scrub,
         * VoiceCorrection) survive a downgrade — they open Room WITHOUT the
         * [DatabaseVersionGuard] pre-check, so these registered downgrades are
         * their only safety net.
         */

        /**
         * [T-android-messages-role-time-index] 23 → 24: composite index on `messages(role, created_at)`
         * for the cross-session mining query (ChatDao.loadUserMessagesSince). The name must equal
         * Room's generated `index_messages_role_created_at` or post-migration validation fails.
         */

        /**
         * Downgrade 24 → 23. NOT a no-op — drops the v24 index: Room validates entity indices
         * EXACTLY, so an extra index fails the older build's open (same policy as MIGRATION_16_15).
         * Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
         */


        /**
         * Downgrade 17 → 16. No-op (keep the permission_mode column).
         * Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
         */

        /**
         * [T-android-downgrade-compat] Downgrade 12 → 11. Deliberately a NO-OP.
         *
         * ## Why this exists
         *
         * Room resolves a downgrade by calling `onUpgrade(from, to)` and
         * looking for a migration path; when none exists it consults
         * `isMigrationRequired`, which either throws or — with a destructive
         * fallback enabled — calls `dropAllTables`. We enable no fallback, so
         * before this migration existed, installing an older build on top of a
         * newer database threw `IllegalStateException` at first DB access and
         * the app could not start at all. Data survived on disk, but the user
         * experience was "the app is broken".
         *
         * Registering any 12 → 11 path is enough for Room to proceed; it does
         * not inspect what the migration does. So the cheapest correct answer
         * is to do nothing and let the four extra columns stay.
         *
         * ## Why NOT `DROP COLUMN`
         *
         * Dropping would genuinely delete the attribution captured by the
         * newer build, so a user who downgrades to look at something and then
         * upgrades back would silently lose it — violating the "no data loss"
         * constraint this whole mechanism exists to uphold. Keeping the
         * columns costs a few dozen bytes per row and makes the round trip
         * lossless. (`ALTER TABLE ... DROP COLUMN` also needs SQLite 3.35+ /
         * API 34+, and a full table rebuild below that.)
         *
         * ## Why leaving the columns is safe for the older build
         *
         * - Room's generated DAOs bind by column NAME, never by position.
         * - Room's schema validation checks that every column the entity
         *   REQUIRES exists; extra columns in the table are ignored.
         * - `SELECT *` is likewise resolved by name.
         * - The one place this codebase reads a cursor positionally
         *   (`VoiceCorrectionDb`, `ConfigAuditLog`) uses explicit column lists
         *   in separate databases, so the projection is fixed regardless.
         * - Old `INSERT` statements omit the new columns, which is fine
         *   precisely because they are nullable with no DEFAULT.
         *
         * ## Scope
         *
         * This pattern generalises to ADD COLUMN / ADD TABLE / ADD INDEX. It
         * does NOT cover renames, drops, type changes, or changes to the
         * MEANING of existing data (Room cannot detect the last one at all).
         * Those need a real reverse migration — or the version pre-check in
         * [com.openminis.app.data.db.DatabaseVersionGuard], which is the
         * backstop for exactly this case.
         * Downgrade backstop note: also covers the secondary `AppDatabase.getInstance` call sites that open Room without the [DatabaseVersionGuard] pre-check.
         */


        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                val appContext = context.applicationContext
                // [T-db-gate-async] quick_check BEFORE Room opens the file, on its own
                // background thread — see DatabaseStartupGate for the ordering constraints
                // (gate-before-open is structural via the gated open helper; the
                // DatabaseVersionGuard decision precedes this call). A corrupt database is
                // quarantined (renamed aside, never opened) and the app starts on a fresh
                // empty database; MainActivity surfaces the failure report.
                DatabaseStartupGate.start(appContext)
                Room.databaseBuilder(
                    appContext,
                    AppDatabase::class.java,
                    "minis.db"
                )
                    .openHelperFactory(DatabaseStartupGate.openHelperFactory())
                    // MIGRATION_12_11 is the downgrade counterpart of
                    // MIGRATION_11_12 — registering it is what lets an older
                    // build open a newer database instead of failing to start.
                    .addMigrations(*AppMigrations.ALL)
                    .build()
                    .also { INSTANCE = it }
                }
            }
        }
    }
}
