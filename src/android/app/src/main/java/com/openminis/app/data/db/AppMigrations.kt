package com.openminis.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * [T-db-migrations-extracted] Every schema migration, out of [AppDatabase].
 *
 * AppDatabase had grown past the 800-line file cap purely because forty
 * migrations were inlined into its companion object. Keeping the database
 * class to registration plus the singleton keeps the thing that changes every
 * release (migrations) apart from the thing that never does (the Room build).
 *
 * The order of [ALL] mirrors the previous `.addMigrations(...)` call verbatim —
 * downgrade pairs sit next to their upgrade counterpart, which is why the list
 * is not sorted by version.
 */
internal object AppMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("sessions", "last_message", "TEXT")
            db.addColumnIfMissing("sessions", "model_binding", "TEXT")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("messages", "reasoning_content", "TEXT")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("compact_markers", "first_kept_message_id", "TEXT")
            db.addColumnIfMissing("compact_markers", "last_compacted_message_id", "TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_first_kept_message_id ON compact_markers(first_kept_message_id)")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("sessions", "thinking_override", "TEXT")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pwa_shortcuts (
                    id TEXT NOT NULL PRIMARY KEY,
                    html_path TEXT NOT NULL,
                    path_scope TEXT NOT NULL,
                    scope_context TEXT,
                    title TEXT NOT NULL,
                    icon_ref TEXT NOT NULL,
                    icon_cache_path TEXT,
                    created_at INTEGER NOT NULL,
                    source_session_id TEXT
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("compact_markers", "version", "INTEGER NOT NULL DEFAULT 1")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS webapp_shortcuts (
                    id TEXT NOT NULL PRIMARY KEY,
                    html_path TEXT NOT NULL,
                    path_scope TEXT NOT NULL,
                    scope_context TEXT,
                    title TEXT NOT NULL,
                    icon_ref TEXT NOT NULL,
                    icon_cache_path TEXT,
                    created_at INTEGER NOT NULL,
                    source_session_id TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO webapp_shortcuts (
                    id, html_path, path_scope, scope_context, title,
                    icon_ref, icon_cache_path, created_at, source_session_id
                )
                SELECT
                    id, html_path, path_scope, scope_context, title,
                    icon_ref, icon_cache_path, created_at, source_session_id
                FROM pwa_shortcuts
                """.trimIndent()
            )
            db.execSQL("DROP TABLE IF EXISTS pwa_shortcuts")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("messages", "error_info", "TEXT")
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS folders (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    icon TEXT,
                    color TEXT,
                    origin TEXT NOT NULL DEFAULT 'manual',
                    sort_index INTEGER NOT NULL DEFAULT 0,
                    pinned_at INTEGER,
                    description TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.addColumnIfMissing("sessions", "folder_id", "TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_folder_id ON sessions(folder_id)")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // sessions: add iOS-parity columns
            db.addColumnIfMissing("sessions", "source", "TEXT")
            db.addColumnIfMissing("sessions", "memory_enabled", "INTEGER NOT NULL DEFAULT 1")
            db.addColumnIfMissing("sessions", "pinned_at", "INTEGER")
            db.addColumnIfMissing("sessions", "edit_count", "INTEGER NOT NULL DEFAULT 0")

            // messages: add iOS-parity columns
            db.addColumnIfMissing("messages", "stream_interrupt_count", "INTEGER NOT NULL DEFAULT 0")
            db.addColumnIfMissing("messages", "updated_at", "INTEGER")

            // compact_markers: new table mirroring iOS
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS compact_markers (
                    id TEXT NOT NULL PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    first_kept_sort_order INTEGER NOT NULL,
                    compacted_count INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    ui_boundary_sort_order INTEGER,
                    boundary_message_id TEXT,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_session_id ON compact_markers(session_id)")
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("messages", "model_id", "TEXT")
            db.addColumnIfMissing("messages", "model_display_name", "TEXT")
            db.addColumnIfMissing("messages", "provider_type", "TEXT")
            db.addColumnIfMissing("messages", "provider_instance_id", "TEXT")
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS code_symbols (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    root TEXT NOT NULL,
                    filePath TEXT NOT NULL,
                    name TEXT NOT NULL,
                    qualifiedName TEXT NOT NULL DEFAULT '',
                    kind TEXT NOT NULL DEFAULT '',
                    startLine INTEGER NOT NULL DEFAULT 0,
                    endLine INTEGER NOT NULL DEFAULT 0,
                    signature TEXT NOT NULL DEFAULT '',
                    indexedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_code_symbols_root ON code_symbols(root)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_code_symbols_name ON code_symbols(name)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_code_symbols_filePath ON code_symbols(filePath)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS code_edges (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    root TEXT NOT NULL,
                    filePath TEXT NOT NULL,
                    kind TEXT NOT NULL DEFAULT '',
                    fromName TEXT NOT NULL DEFAULT '',
                    toName TEXT NOT NULL DEFAULT '',
                    line INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_code_edges_root ON code_edges(root)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_code_edges_fromName ON code_edges(fromName)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_code_edges_toName ON code_edges(toName)")
        }
    }

    val MIGRATION_13_12 = object : Migration(13, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No-op. See T-code-graph downgrade policy.
        }
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS kanban_tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT,
                    status TEXT NOT NULL DEFAULT 'todo',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_14_13 = object : Migration(14, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No-op.
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS kanban_tasks")
        }
    }

    val MIGRATION_15_14 = object : Migration(15, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No-op.
        }
    }

    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sessions_updated_at` ON `sessions` (`updated_at`)",
            )
        }
    }

    val MIGRATION_16_15 = object : Migration(16, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS `index_sessions_updated_at`")
        }
    }

    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // [T-compact-chunk-pool] Additive only: per-compaction summary
            // chunks for retrieval. Legacy rows stay NULL and keep
            // reading through `summary`.
            db.addColumnIfMissing("compact_markers", "summary_chunks", "TEXT")
        }
    }

    val MIGRATION_18_17 = object : Migration(18, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Keep the column. Older builds ignore extra fields.
        }
    }

    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("messages", "body_bytes", "INTEGER NOT NULL DEFAULT 0")
            db.addColumnIfMissing("messages", "body_ref", "TEXT")
            db.addColumnIfMissing("messages", "body_sha", "TEXT")
            db.addColumnIfMissing("messages", "preview", "TEXT")
            db.addColumnIfMissing("compact_markers", "summary_ref", "TEXT")
            db.execSQL("UPDATE messages SET body_bytes = length(CAST(parts_json AS BLOB))")
            db.execSQL(
                "UPDATE messages SET preview = CASE " +
                    "WHEN body_bytes <= 2048 THEN parts_json " +
                    "ELSE '[{\"type\":\"text\",\"value\":' || json_quote(substr(parts_json, 1, 2000)) || '}]' " +
                    "END",
            )
        }
    }

    val MIGRATION_19_18 = object : Migration(19, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Keep the columns. Older builds ignore extra fields.
        }
    }

    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS session_goals (
                    session_id TEXT NOT NULL PRIMARY KEY,
                    objective TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'active',
                    token_budget INTEGER,
                    tokens_used INTEGER NOT NULL DEFAULT 0,
                    elapsed_ms INTEGER NOT NULL DEFAULT 0,
                    blocked_condition TEXT,
                    blocked_count INTEGER NOT NULL DEFAULT 0,
                    paused_at INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }

    val MIGRATION_20_19 = object : Migration(20, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Keep session_goals so a downgrade does not discard a user's goal.
        }
    }

    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                db.execSQL("DROP INDEX IF EXISTS `index_messages_session_id_sort_order`")
                // The old statement was a correlated `UPDATE … SET sort_order =
                // (SELECT COUNT(*) FROM messages prior WHERE prior.session_id =
                // messages.session_id AND …)` — one full per-session scan per
                // row, i.e. O(N²). A 100k-message history spent minutes under
                // the upgrade lock and ANR'd. SQLite window functions are not
                // available on the older Android versions this app supports, so
                // renumber with one prepared UPDATE per row inside this single
                // transaction: O(N) statements, no repeated scans.
                val renumber = db.compileStatement(
                    "UPDATE messages SET sort_order = ? WHERE id = ?",
                )
                val sessions = ArrayList<String>()
                db.query("SELECT DISTINCT session_id FROM messages").use { c ->
                    while (c.moveToNext()) sessions.add(c.getString(0))
                }
                for (session in sessions) {
                    val ids = ArrayList<String>()
                    db.query(
                        "SELECT id FROM messages WHERE session_id = ? " +
                            "ORDER BY created_at ASC, id ASC",
                        arrayOf(session),
                    ).use { c ->
                        while (c.moveToNext()) ids.add(c.getString(0))
                    }
                    ids.forEachIndexed { index, id ->
                        renumber.clearBindings()
                        renumber.bindLong(1, index.toLong())
                        renumber.bindString(2, id)
                        renumber.executeUpdateDelete()
                    }
                }
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_session_id_sort_order` " +
                        "ON `messages` (`session_id`, `sort_order`)"
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    val MIGRATION_21_20 = object : Migration(21, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS `index_messages_session_id_sort_order`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_session_id_sort_order` " +
                    "ON `messages` (`session_id`, `sort_order`)"
            )
        }
    }

    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `message_versions` (
                    `id` TEXT NOT NULL,
                    `message_id` TEXT NOT NULL,
                    `session_id` TEXT NOT NULL,
                    `version_index` INTEGER NOT NULL,
                    `role` TEXT NOT NULL,
                    `parts_json` TEXT,
                    `body_ref` TEXT,
                    `body_sha` TEXT,
                    `reasoning_content` TEXT,
                    `error_info` TEXT,
                    `model_id` TEXT,
                    `model_display_name` TEXT,
                    `source_created_at` INTEGER NOT NULL,
                    `archived_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_message_versions_message_id_version_index` " +
                    "ON `message_versions` (`message_id`, `version_index`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_message_versions_session_id` " +
                    "ON `message_versions` (`session_id`)"
            )
        }
    }

    val MIGRATION_22_21 = object : Migration(22, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `message_versions`")
        }
    }

    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `automation_rules` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `spec_json` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `last_fired_at` INTEGER,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `automation_runs` (
                    `run_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `rule_id` TEXT NOT NULL,
                    `fired_at` INTEGER NOT NULL,
                    `outcome` TEXT NOT NULL,
                    `session_id` TEXT,
                    `preview` TEXT
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_automation_runs_rule_id_fired_at` " +
                    "ON `automation_runs` (`rule_id`, `fired_at`)"
            )
        }
    }

    val MIGRATION_23_22 = object : Migration(23, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Keep the tables. Older builds ignore extra tables.
        }
    }

    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_role_created_at` " +
                    "ON `messages` (`role`, `created_at`)"
            )
        }
    }

    val MIGRATION_24_23 = object : Migration(24, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS `index_messages_role_created_at`")
        }
    }

    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `knowledge_notes` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `body_md` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `tags_json` TEXT NOT NULL,
                    `links_json` TEXT NOT NULL,
                    `search_text` TEXT NOT NULL,
                    `dedupe_hash` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `trashed_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `knowledge_note_versions` (
                    `note_id` TEXT NOT NULL,
                    `version` INTEGER NOT NULL,
                    `body_at` INTEGER NOT NULL,
                    PRIMARY KEY(`note_id`, `version`),
                    FOREIGN KEY(`note_id`) REFERENCES `knowledge_notes`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_notes_status_updated_at` " +
                    "ON `knowledge_notes` (`status`, `updated_at`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_notes_dedupe_hash` " +
                    "ON `knowledge_notes` (`dedupe_hash`)"
            )
        }
    }

    val MIGRATION_25_24 = object : Migration(25, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Keep the tables. Older builds ignore extra tables.
        }
    }

    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // [T-m9-supervisor-experience] Per-session tool experience rows
            // used by the Supervisor-Worker recall/evidence wiring.
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `project_experience` (
                    `id` TEXT NOT NULL,
                    `session_id` TEXT NOT NULL,
                    `tool_name` TEXT NOT NULL,
                    `used_count` INTEGER NOT NULL,
                    `success_count` INTEGER NOT NULL,
                    `failure_count` INTEGER NOT NULL,
                    `first_used_at` INTEGER NOT NULL,
                    `last_used_at` INTEGER NOT NULL,
                    `fingerprint` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_project_experience_session_id` " +
                    "ON `project_experience` (`session_id`)"
            )
        }
    }

    val MIGRATION_26_25 = object : Migration(26, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Keep the table. Older builds ignore extra tables.
        }
    }

    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // [T-p1-instruction-snapshot] Instruction snapshots (P1 of the
            // memory rebuild): one frozen state row per session plus the
            // append-only event stream. Mirrors opencode instruction-state.
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `instruction_states` (
                    `sessionId` TEXT NOT NULL,
                    `epochStartSeq` INTEGER NOT NULL,
                    `throughSeq` INTEGER NOT NULL,
                    `initialValuesJson` TEXT NOT NULL,
                    `currentValuesJson` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`sessionId`)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `instruction_updates` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `seq` INTEGER NOT NULL,
                    `isInitial` INTEGER NOT NULL,
                    `deltaJson` TEXT,
                    `renderedText` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_instruction_updates_sessionId` " +
                    "ON `instruction_updates` (`sessionId`)"
            )
        }
    }

    val MIGRATION_27_26 = object : Migration(27, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Keep the tables. Older builds ignore extra tables.
        }
    }

    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // [T-p6-remove-knowledge-workbench] The knowledge workbench is
            // retired; drop its tables. Child first (FK), DROP takes the
            // indexes with it. Note data is gone for good.
            db.execSQL("DROP TABLE IF EXISTS `knowledge_note_versions`")
            db.execSQL("DROP TABLE IF EXISTS `knowledge_notes`")
        }
    }

    val MIGRATION_28_27 = object : Migration(28, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Downgrade rebuilds empty tables so a v27 build's schema
            // validation still passes. Same DDL as MIGRATION_24_25.
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `knowledge_notes` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `body_md` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `tags_json` TEXT NOT NULL,
                    `links_json` TEXT NOT NULL,
                    `search_text` TEXT NOT NULL,
                    `dedupe_hash` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `trashed_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `knowledge_note_versions` (
                    `note_id` TEXT NOT NULL,
                    `version` INTEGER NOT NULL,
                    `body_at` INTEGER NOT NULL,
                    PRIMARY KEY(`note_id`, `version`),
                    FOREIGN KEY(`note_id`) REFERENCES `knowledge_notes`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_notes_status_updated_at` " +
                    "ON `knowledge_notes` (`status`, `updated_at`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_notes_dedupe_hash` " +
                    "ON `knowledge_notes` (`dedupe_hash`)"
            )
        }
    }

    val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // [T-android-session-branch-tree] Session-level branch metadata.
            // Additive columns, both nullable: pre-30 rows are root sessions.
            db.addColumnIfMissing("sessions", "branch_of", "TEXT")
            db.addColumnIfMissing("sessions", "branch_point", "TEXT")
        }
    }

    val MIGRATION_30_29 = object : Migration(30, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Keep the columns (same policy as 17->16): older builds ignore
            // extra fields, and dropping them would lose branch provenance.
        }
    }

    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // [T-goal-model-origin] Provenance decides whether a later task may
            // replace the session Goal. Rows written before this column existed
            // default to 'user': the conservative side — the model never
            // overwrites an objective a human may have set.
            db.addColumnIfMissing("session_goals", "created_by", "TEXT NOT NULL DEFAULT 'user'")
        }
    }

    val MIGRATION_29_28 = object : Migration(29, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Rebuild without the column: ALTER TABLE DROP COLUMN needs SQLite
            // 3.35+, which older supported Android releases do not ship.
            // A previous partial attempt could have left the scratch table
            // behind (its INSERT would then hit the primary key), so clear it.
            db.execSQL("DROP TABLE IF EXISTS `session_goals_old`")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `session_goals_old` (
                    `session_id` TEXT NOT NULL,
                    `objective` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `token_budget` INTEGER,
                    `tokens_used` INTEGER NOT NULL,
                    `elapsed_ms` INTEGER NOT NULL,
                    `blocked_condition` TEXT,
                    `blocked_count` INTEGER NOT NULL,
                    `paused_at` INTEGER,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`session_id`)
                )""".trimIndent()
            )
            db.execSQL(
                "INSERT INTO `session_goals_old` (`session_id`, `objective`, `status`, " +
                    "`token_budget`, `tokens_used`, `elapsed_ms`, `blocked_condition`, " +
                    "`blocked_count`, `paused_at`, `created_at`, `updated_at`) " +
                    "SELECT `session_id`, `objective`, `status`, `token_budget`, " +
                    "`tokens_used`, `elapsed_ms`, `blocked_condition`, `blocked_count`, " +
                    "`paused_at`, `created_at`, `updated_at` FROM `session_goals`"
            )
            db.execSQL("DROP TABLE IF EXISTS `session_goals`")
            db.execSQL("ALTER TABLE `session_goals_old` RENAME TO `session_goals`")
        }
    }

    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("sessions", "permission_mode", "TEXT")
            // Pre-2.0.4 rows have NULL. Force ASK so upgraded chats
            // do not inherit the old global ALLOW_ALL / READ_ONLY prefs.
            db.execSQL("UPDATE sessions SET permission_mode = 'ASK' WHERE permission_mode IS NULL OR TRIM(permission_mode) = ''")
        }
    }

    val MIGRATION_17_16 = object : Migration(17, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Keep the column. Older builds ignore extra fields.
        }
    }

    val MIGRATION_12_11 = object : Migration(12, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Intentionally empty. See the doc comment above — the four
            // columns added by MIGRATION_11_12 are left in place.
        }
    }

    /** Registration order, exactly as [AppDatabase] used to pass it. */
    val ALL: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_11,
    MIGRATION_12_13,
    MIGRATION_13_12,
    MIGRATION_13_14,
    MIGRATION_14_13,
    MIGRATION_14_15,
    MIGRATION_15_14,
    MIGRATION_15_16,
    MIGRATION_16_15,
    MIGRATION_16_17,
    MIGRATION_17_16,
    MIGRATION_17_18,
    MIGRATION_18_17,
    MIGRATION_18_19,
    MIGRATION_19_18,
    MIGRATION_19_20,
    MIGRATION_20_19,
    MIGRATION_20_21,
    MIGRATION_21_20,
    MIGRATION_21_22,
    MIGRATION_22_21,
    MIGRATION_22_23,
    MIGRATION_23_22,
    MIGRATION_23_24,
    MIGRATION_24_23,
    MIGRATION_24_25,
    MIGRATION_25_24,
    MIGRATION_25_26,
    MIGRATION_26_25,
    MIGRATION_26_27,
    MIGRATION_27_26,
    MIGRATION_27_28,
    MIGRATION_28_27,
    MIGRATION_28_29,
    MIGRATION_29_30,
    MIGRATION_29_28,
    )
}
