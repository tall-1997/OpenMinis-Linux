package com.openminis.app.data.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level contract for the 34 -> 33 DOWNGRADE migration (review P1-1).
 *
 * A downgrade migration cannot be replayed on the JVM (it needs a device
 * SQLite), so this test pins the next best thing: the exact DDL inside
 * [ThinkingDormancyMigrations.MIGRATION_34_33], checked line-by-line against
 * `app/schemas/com.openminis.app.data.db.AppDatabase/33.json` — the schema the
 * OLD (33) build's Room validates with TableInfo equality right after the
 * migration runs. The old no-op body kept the extra column, so the round-trip
 * crashed with IllegalStateException instead of losing only the park; these
 * assertions make any regression back to a no-op (or any drift from 33.json)
 * fail in a two-second local test instead of on a device.
 *
 * The device-side replay (column gone, other data kept, indices rebuilt)
 * lives in ThinkingDormancyMigrationTest (androidTest).
 */
class ThinkingDormancyDowngradeContractTest {

    // ---------------------------------------------------------- locations --

    /** Gradle runs unit tests from the module dir; local runs from the repo root. */
    private fun resolveRepoFile(vararg candidates: String): File {
        candidates.forEach { if (File(it).isFile) return File(it) }
        return File(candidates.first())
    }

    private val migrationFile = resolveRepoFile(
        "src/main/java/com/openminis/app/data/db/ThinkingDormancyMigrations.kt",
        "src/android/app/src/main/java/com/openminis/app/data/db/ThinkingDormancyMigrations.kt",
    )
    private val schema33 = resolveRepoFile(
        "schemas/com.openminis.app.data.db.AppDatabase/33.json",
        "src/android/app/schemas/com.openminis.app.data.db.AppDatabase/33.json",
    )

    // -------------------------------------------------------- extraction --

    /**
     * Whitespace-free canonical form: the migration's Kotlin raw strings
     * indent every line, the exported JSON's createSql is single-line — the
     * DDL is equal when all whitespace is gone.
     */
    private fun canonical(s: String): String = s.filter { !it.isWhitespace() }

    private fun migrationSource(): String = migrationFile.readText()

    private fun migrationBody(source: String, tag: String): String {
        val m = Regex("""\b$tag\s*=\s*object\s*:\s*Migration""").find(source)
            ?: error("$tag not found in ${migrationFile.name}")
        var depth = 1
        var i = m.range.last + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return source.substring(m.range.last + 1, i - 1)
    }

    /** Every execSQL argument, with adjacent string literals concatenated. */
    private fun execSqlStatements(body: String): List<String> {
        val sqls = mutableListOf<String>()
        for (m in Regex("""\bexecSQL\s*\(""").findAll(body)) {
            var depth = 1
            var j = m.range.last + 1
            while (j < body.length && depth > 0) {
                when (body[j]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                j++
            }
            sqls.add(concatLiterals(body.substring(m.range.last + 1, j - 1)))
        }
        return sqls
    }

    private fun concatLiterals(segment: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < segment.length) {
            if (segment.startsWith("\"\"\"", i)) {
                // Raw-string block: take its content verbatim.
                val j = segment.indexOf("\"\"\"", i + 3)
                if (j == -1) {
                    out.append(segment.substring(i + 3))
                    break
                }
                out.append(segment.substring(i + 3, j))
                i = j + 3
            } else if (segment[i] == '"') {
                var j = i + 1
                while (j < segment.length) {
                    if (segment[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (segment[j] == '"') break
                    j++
                }
                out.append(segment.substring(i + 1, j))
                i = j + 1
            } else {
                i++
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------ 33.json truth --

    private data class SessionsSchema(
        val createSql: String,
        val columns: List<String>,
        val indexSqls: List<String>,
    )

    private fun sessionsSchema33(): SessionsSchema {
        val db = JSONObject(schema33.readText()).getJSONObject("database")
        assertEquals("fixture must be the exported schema 33", 33, db.getInt("version"))
        val entities = db.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            if (e.getString("tableName") != "sessions") continue
            val columns = (0 until e.getJSONArray("fields").length())
                .map { e.getJSONArray("fields").getJSONObject(it).getString("columnName") }
            val idx = e.optJSONArray("indices") ?: org.json.JSONArray()
            val indexSqls = (0 until idx.length())
                .map { idx.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", "sessions") }
            return SessionsSchema(
                createSql = e.getString("createSql").replace("\${TABLE_NAME}", "sessions"),
                columns = columns,
                indexSqls = indexSqls,
            )
        }
        error("33.json has no sessions entity")
    }

    // -------------------------------------------------------------- tests --

    @Test
    fun `both directions stay registered in ALL`() {
        val source = migrationSource()
        assertTrue(migrationBody(source, "MIGRATION_33_34").isNotEmpty())
        val all = Regex("""val ALL: Array<Migration> = arrayOf\(([^)]*)\)""").find(source)
            ?: error("ALL migration array not found — downgrades must stay reachable")
        assertEquals(
            "the downgrade pair must both ride ALL",
            "MIGRATION_33_34,MIGRATION_34_33",
            all.groupValues[1].replace(" ", ""),
        )
    }

    @Test
    fun `downgrade is a full table rebuild not a no-op`() {
        val statements = execSqlStatements(migrationBody(migrationSource(), "MIGRATION_34_33"))
        val canonical = statements.map { canonical(it) }

        fun startsWith(prefix: String) = canonical.any { it.startsWith(canonical(prefix)) }

        // The Room drop-column recipe, in order: scratch table -> carry over
        // -> drop -> rename -> indices.
        assertTrue("expected DROP TABLE IF EXISTS `sessions_mig`", startsWith("DROP TABLE IF EXISTS `sessions_mig`"))
        assertTrue("expected CREATE TABLE IF NOT EXISTS `sessions_mig`", startsWith("CREATE TABLE IF NOT EXISTS `sessions_mig`"))
        assertTrue("expected INSERT INTO `sessions_mig` ... SELECT", startsWith("INSERT INTO `sessions_mig`"))
        assertTrue(
            "expected the live sessions table to be dropped",
            canonical.contains(canonical("DROP TABLE `sessions`")),
        )
        assertTrue(
            "expected the scratch table renamed back to sessions",
            canonical.contains(canonical("ALTER TABLE `sessions_mig` RENAME TO `sessions`")),
        )
        assertTrue("expected index_sessions_folder_id to be recreated", startsWith("CREATE INDEX IF NOT EXISTS `index_sessions_folder_id`"))
        assertTrue("expected index_sessions_updated_at to be recreated", startsWith("CREATE INDEX IF NOT EXISTS `index_sessions_updated_at`"))
    }

    @Test
    fun `rebuilt createSql equals the exported 33 schema`() {
        val schema = sessionsSchema33()
        val statements = execSqlStatements(migrationBody(migrationSource(), "MIGRATION_34_33"))
        val create = statements.first { canonical(it).startsWith(canonical("CREATE TABLE IF NOT EXISTS `sessions_mig`")) }
        // 33.json's createSql retargeted at the scratch table IS the required
        // shape: same columns, same affinities, same NOT NULLs, same PK.
        assertEquals(
            canonical(schema.createSql.replace("`sessions`", "`sessions_mig`")),
            canonical(create),
        )
    }

    @Test
    fun `carried-over column list equals the 33 schema exactly`() {
        val schema = sessionsSchema33()
        assertTrue(
            "fixture sanity: the 33 schema must not know the dormancy column",
            "dormant_thinking_override" !in schema.columns,
        )
        val statements = execSqlStatements(migrationBody(migrationSource(), "MIGRATION_34_33"))
        val insert = statements.first { canonical(it).startsWith(canonical("INSERT INTO `sessions_mig`")) }
        val selectList = schema.columns.joinToString(", ") { "`$it`" }
        val expected = "INSERT INTO `sessions_mig` ($selectList) SELECT $selectList FROM `sessions`"
        assertEquals(canonical(expected), canonical(insert))
    }

    @Test
    fun `no downgrade statement carries the dormant column`() {
        val statements = execSqlStatements(migrationBody(migrationSource(), "MIGRATION_34_33"))
        // The INSERT may not select it and the scratch table may not declare
        // it — any mention in DDL means the 33 build's TableInfo check fails.
        statements
            .filter { canonical(it).startsWith(canonical("CREATE TABLE")) || canonical(it).startsWith(canonical("INSERT INTO")) }
            .forEach {
                assertFalse(
                    "downgrade DDL must not reference dormant_thinking_override: ${it.take(80)}",
                    it.contains("dormant_thinking_override"),
                )
            }
    }

    @Test
    fun `recreated indices equal the exported 33 indices`() {
        val schema = sessionsSchema33()
        assertTrue("fixture sanity: sessions must carry two indices in 33.json", schema.indexSqls.size == 2)
        val canonical = execSqlStatements(migrationBody(migrationSource(), "MIGRATION_34_33")).map { canonical(it) }
        for (indexSql in schema.indexSqls) {
            assertTrue(
                "missing index DDL: $indexSql",
                canonical.contains(canonical(indexSql)),
            )
        }
    }
}
