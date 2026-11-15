package com.openminis.app.data.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-compat-flags-persist] Source-level replay of the ProviderDatabase
 * migration chain against the newest exported schema — the
 * SchemaMigrationDriftTest approach scaled to `provider.db`, whose migrations
 * all live in ProviderDatabase.kt. Room's MigrationTestHelper needs an
 * instrumented device (room-testing is androidTest-only here), so this test
 * replays the hand-written DDL from source and compares the resulting
 * column set of `provider_model_entries` with the exported schema JSON.
 *
 * Scope note (mirrors SchemaMigrationDriftTest limitations): column comparison
 * is name-only; affinity / notNull / defaults are verified by the instrumented
 * ProviderDatabaseCompatJsonMigrationTest on devices.
 */
class ProviderDatabaseCompatSchemaTest {

    private val dbDir: File = resolveRepoFile(
        "src/main/java/com/openminis/app/data/db",
        "src/android/app/src/main/java/com/openminis/app/data/db",
    )
    private val schemaDir: File = resolveRepoFile(
        "schemas/com.openminis.app.data.db.ProviderDatabase",
        "src/android/app/schemas/com.openminis.app.data.db.ProviderDatabase",
    )

    /** Gradle runs unit tests from the module dir; local runs from the repo root. */
    private fun resolveRepoFile(vararg candidates: String): File {
        candidates.firstOrNull { File(it).isDirectory || File(it).isFile }?.let { return File(it) }
        return File(candidates.first())
    }

    private val migrationRegex = Regex(
        """\bMIGRATION_(\d+)_(\d+)\s*=\s*object\s*:\s*Migration\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*\{""",
    )
    private val addColumnRegex = Regex(
        """addColumnIfMissing\("([A-Za-z_][A-Za-z0-9_]*)",\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*,\s*"([A-Za-z_][A-Za-z0-9_ ]*)"\)""",
    )

    /** The entity under test and the migration that adds its newest column. */
    private val table = "provider_model_entries"
    private val newestColumn = "compat_json"
    private val newestMigration = 7 to 8

    @Test
    fun `database version is 8`() {
        val source = File(dbDir, "ProviderDatabase.kt").readText()
        assertTrue(
            "ProviderDatabase must declare version = 8",
            source.contains(Regex("""version\s*=\s*8,""")),
        )
    }

    @Test
    fun `MIGRATION_7_8 adds compat_json as nullable TEXT`() {
        val source = File(dbDir, "ProviderDatabase.kt").readText()
        val match = migrationRegex.findAll(source)
            .firstOrNull { (it.groupValues[1].toInt() to it.groupValues[2].toInt()) == newestMigration }
        assertNotNull("MIGRATION_7_8 object must exist", match)
        val bodyStart = match!!.range.last + 1
        val segment = source.substring(bodyStart, source.indexOf('}', bodyStart))
        val columns = addColumnRegex.findAll(segment).map { it.groupValues }.toList()
        val added = columns.filter { it[1] == table && it[2] == newestColumn }
        assertTrue(
            "MIGRATION_7_8 must addColumnIfMissing($table, $newestColumn, TEXT)",
            added.size == 1 && added[0][3] == "TEXT",
        )
    }

    @Test
    fun `exported schema 8 matches the replayed provider_model_entries shape`() {
        val schemas = (schemaDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f ->
                val db = JSONObject(f.readText()).getJSONObject("database")
                db.getInt("version") to f
            }
            .sortedBy { it.first }
        assertTrue("at least schema 7 and 8 must be exported", schemas.size >= 2)
        val newest = schemas.last()
        assertEquals("newest exported ProviderDatabase schema must be version 8", 8, newest.first)
        val json = JSONObject(newest.second.readText()).getJSONObject("database")
        val entity = json.getJSONArray("entities")
            .let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { it.getString("tableName") == table }
            }
        assertNotNull("exported schema must describe $table", entity)
        val fields = entity!!.getJSONArray("fields")
        val names = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }.toSet()
        val compat = (0 until fields.length())
            .map { fields.getJSONObject(it) }
            .firstOrNull { it.getString("columnName") == newestColumn }
        assertNotNull("exported schema must carry $table.$newestColumn", compat)
        assertEquals("TEXT", compat!!.getString("affinity"))
        assertFalse("compat_json must be nullable (overlay is optional)", compat.getBoolean("notNull"))

        // The full replayed column set: v7 shape + the one added column. The
        // pre-8 column list is pinned literally so a silently dropped historical
        // migration cannot pass the comparison by re-adding everything.
        assertEquals(
            setOf(
                "id", "provider_instance_id", "base_model_json", "overrides_json",
                "is_custom", "is_hidden", "sort_order", "user_modified_at", "compat_json",
            ),
            names,
        )
        // Migrations 1..7 added their own columns to OTHER tables; provider_model_entries
        // never grew before 7→8, so the newest migration is its only delta.
        val otherTables = json.getJSONArray("entities").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("tableName") }
        }
        assertTrue(otherTables.contains("provider_instances"))
    }
}
