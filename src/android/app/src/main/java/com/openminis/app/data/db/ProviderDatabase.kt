package com.openminis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Standalone Room database for provider config. Lives in `provider.db`,
 * separate from `minis.db` (sessions/messages/etc), so that downgrading
 * to a version that doesn't know about these tables does NOT crash on
 * `minis.db`. Old builds simply ignore provider.db and continue to read
 * provider config from the legacy SharedPreferences JSON mirror — which
 * we keep writing on every save so it's never stale.
 *
 * On re-upgrade, ProviderRepository compares a stored hash of the JSON
 * mirror against the live mirror to detect "old build wrote JSON behind
 * our back during the downgrade window" and re-imports if needed; that
 * way provider.db can never become authoritative-but-stale relative to
 * what the user did while downgraded.
 *
 * Schema starts at version 1; future column adds use Migration like
 * AppDatabase does. We deliberately do NOT enable
 * fallbackToDestructiveMigration: provider.db is the only copy of
 * structured provider state, and the JSON mirror is the safety net, not
 * a substitute for proper migrations.
 *
 * [Wave3-fix-10] exportSchema is now true, matching AppDatabase: the schema
 * JSON is what makes MigrationTestHelper possible at all. The KSP arg
 * `room.schemaLocation = $projectDir/schemas` is already configured
 * (app/build.gradle.kts:341), so the NEXT build emits
 * `app/schemas/com.openminis.app.data.db.ProviderDatabase/5.json` (the
 * current version; Room only exports the version being compiled). That JSON
 * MUST be committed together with this change — a schema export that never
 * lands in git protects nothing.
 */
@Database(
    entities = [
        ProviderInstanceEntity::class,
        ProviderModelEntryEntity::class,
        ProviderModelGroupEntity::class,
        ProviderAgentLoopIdEntity::class,
        ProviderConfigMetaEntity::class,
        ProviderThinkingRuleEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class ProviderDatabase : RoomDatabase() {
    abstract fun providerConfigDao(): ProviderConfigDao

    companion object {
        @Volatile
        private var INSTANCE: ProviderDatabase? = null

        /**
         * [T-android-azure-openai] Add the Azure OpenAI mode column. Pure
         * additive ALTER with NOT NULL DEFAULT 0 so every existing provider row
         * backfills to "off" — no data rewrite, no provider drop. This is the
         * first migration on provider.db (introduced at v1 with all columns
         * inline); older builds that don't know the column keep reading the JSON
         * mirror, and re-upgrade re-imports if they wrote behind our back.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("provider_instances", "azure_mode", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * [GH#68 T-android-image-endpoint-persist] Add the image-endpoint
         * picker columns that the Room migration of the provider store
         * (4dd24ecf) missed — the JSON model carried them but every Room
         * round-trip dropped the value, snapping the picker back to Auto.
         * Pure additive nullable TEXT ALTERs; existing rows read as null →
         * auto / no cached probe, no data rewrite, no provider drop.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("provider_instances", "image_endpoint_mode", "TEXT")
                db.addColumnIfMissing("provider_instances", "image_endpoint_resolved", "TEXT")
            }
        }

        /**
         * [T-android-thinking-rules-phase2] Add the custom-thinking-rules table.
         * Pure additive CREATE TABLE — no existing row is touched, and a provider with
         * no custom rules has an empty table, so resolution stays byte-identical to
         * pre-migration (the resolver prepends an empty list above the built-ins).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS provider_thinking_rules (
                        id TEXT NOT NULL PRIMARY KEY,
                        provider_instance_id TEXT NOT NULL,
                        label TEXT NOT NULL,
                        scope_kind TEXT NOT NULL,
                        scope_pattern TEXT,
                        wire_format_json TEXT,
                        reasoning_echo_json TEXT,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_provider_thinking_rules_provider_instance_id " +
                        "ON provider_thinking_rules(provider_instance_id)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("provider_instances", "pinned", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * [T-routing-strategy-x-fix] Persist the five strategies' scheduling
         * knobs (member weights, first-token timeout, quota window) into the
         * provider_model_groups table. Pre-fix builds only carried them in the
         * JSON mirror, so every Room round-trip dropped the values; the
         * DB-snapshot-first load path re-defaulted them on each cold start.
         * Pure additive nullable ALTERs: existing rows read as defaults
         * (empty weights, null timeout, null quota) — no data rewrite.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("provider_model_groups", "member_weights_json", "TEXT NOT NULL DEFAULT '{}'")
                db.addColumnIfMissing("provider_model_groups", "first_token_timeout_ms", "INTEGER")
                db.addColumnIfMissing("provider_model_groups", "quota_window_remaining", "INTEGER")
                db.addColumnIfMissing("provider_model_groups", "quota_window_total", "INTEGER")
                db.addColumnIfMissing("provider_model_groups", "quota_window_period_ms", "INTEGER")
                db.addColumnIfMissing("provider_model_groups", "quota_window_reset_at", "INTEGER")
            }
        }

        /**
         * [OpenMinis#311] Force extended thinking for Anthropic-compatible
         * relays whose catalog entry says `supportsReasoning = false`. Pure
         * additive ALTER with NOT NULL DEFAULT 0: existing rows keep the old
         * "capability decides" behaviour, no data rewrite, no provider drop.
         * The exported schema (7.json) lands with the next Gradle build.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("provider_instances", "force_thinking", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): ProviderDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProviderDatabase::class.java,
                    "provider.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
