package com.openminis.app.data.db

import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * [T-conditional-automation] Keeps the hand-written Room migration DDL honest
 * against the exported schema — the exact class of drift that made
 * MIGRATION_22_23 crash the app at startup.
 *
 * Room validates the migrated database against the @Entity declarations on
 * every upgrade. An index created by a hand-written migration but missing
 * from the entity's `indices = [...]` fails RoomOpenHelper.onUpgrade with
 * "Migration didn't properly handle: <table>" — after the migration ran, so
 * the whole app goes down instead of one screen. This test replays every
 * forward migration in AppDatabase.kt from version 1 to the newest exported
 * schema and compares the resulting per-table column and index state against
 * `schemas/.../<newest>.json`, which Room's schema export keeps in lockstep
 * with the entities. Agreeing with the JSON means agreeing with the entities
 * too.
 *
 * Limitations (deliberate, mirrored in scripts/ci/check_schema_drift.py):
 * - Tables and indexes that exist since schema v1 (no CREATE anywhere in the
 *   migration chain, verified against the oldest exported JSON) are not
 *   compared: migrations never define their initial shape. Deltas applied to
 *   them (ADD COLUMN, CREATE/DROP INDEX) ARE checked.
 * - Column comparison is name-only: affinity / notNull / defaults are not
 *   replayed; MigrationTestHelper covers that on devices.
 * - DDL is extracted from string literals inside migration bodies; DDL that
 *   is assembled with non-literal interpolation would be missed.
 */
class SchemaMigrationDriftTest {

    private val appDbFile = resolveRepoFile(
        "src/main/java/com/openminis/app/data/db/AppDatabase.kt",
        "src/android/app/src/main/java/com/openminis/app/data/db/AppDatabase.kt",
    )
    private val schemaDir = resolveRepoFile(
        "schemas/com.openminis.app.data.db.AppDatabase",
        "src/android/app/schemas/com.openminis.app.data.db.AppDatabase",
    )

    /** Gradle runs unit tests from the module dir; local runs from the repo root. */
    private fun resolveRepoFile(vararg candidates: String): File {
        candidates.firstOrNull { File(it).isDirectory || File(it).isFile }?.let { return File(it) }
        return File(candidates.first())
    }

    private data class IndexState(val unique: Boolean, val columnNames: List<String>, val by: String)

    private data class TableState(
        val cols: MutableSet<String> = mutableSetOf(),
        val added: MutableSet<String> = mutableSetOf(),
        val indexes: MutableMap<String, IndexState> = mutableMapOf(),
        val createdBy: String? = null,
    )

    private class SchemaSnapshot(
        val columns: Set<String>,
        val indexes: Map<String, Pair<Boolean, List<String>>>,
    )

    // --- extraction ---------------------------------------------------------

    private val migrationRegex = Regex(
        """\bMIGRATION_(\d+)_(\d+)\s*=\s*object\s*:\s*Migration\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*\{""",
    )

    private fun extractBody(source: String, braceIndex: Int): String {
        var depth = 1
        var i = braceIndex
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return source.substring(braceIndex, i - 1)
    }

    private fun concatLiterals(segment: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < segment.length) {
            if (segment.startsWith("\"\"\"", i)) {
                val j = segment.indexOf("\"\"\"", i + 3)
                if (j == -1) break
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

    private fun execSqlStrings(body: String): List<String> {
        val sqls = mutableListOf<String>()
        val call = Regex("""\bexecSQL\s*\(""")
        for (m in call.findAll(body)) {
            var depth = 1
            var j = m.range.last + 1
            while (j < body.length && depth > 0) {
                when (body[j]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                j++
            }
            sqls.add(concatLiterals(body.substring(m.range.last + 1, j - 1))
                .replace(Regex("""\s+"""), " ")
                .trim())
        }
        return sqls
    }

    private val addColumnRegex = Regex(
        """addColumnIfMissing\("([A-Za-z_][A-Za-z0-9_]*)",\s*"([A-Za-z_][A-Za-z0-9_]*)" """.trimEnd()
    )

    private fun migrationSqls(source: String): Map<Pair<Int, Int>, List<String>> {
        val out = mutableMapOf<Pair<Int, Int>, List<String>>()
        for (m in migrationRegex.findAll(source)) {
            val body = extractBody(source, m.range.last + 1)
            val ddl = execSqlStrings(body).toMutableList()
            for (am in addColumnRegex.findAll(body)) {
                ddl.add("addColumnIfMissing(\"${am.groupValues[1]}\",\"${am.groupValues[2]}\")")
            }
            out[m.groupValues[1].toInt() to m.groupValues[2].toInt()] = ddl
        }
        return out
    }

    // --- replay -------------------------------------------------------------

    private val createTableRegex = Regex(
        """CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?\s*\((.+)\)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val createIndexRegex = Regex(
        """CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?\s+ON\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\s*\((.+)\)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val dropIndexRegex = Regex(
        """DROP\s+INDEX\s+(?:IF\s+EXISTS\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?""",
        RegexOption.IGNORE_CASE,
    )
    private val dropTableRegex = Regex(
        """DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?""",
        RegexOption.IGNORE_CASE,
    )
    private val columnDefRegex = Regex(
        """`?([A-Za-z_][A-Za-z0-9_]*)`?\s+(?:TEXT|INTEGER|REAL|BLOB|NUMERIC)""",
        RegexOption.IGNORE_CASE,
    )
    private val indexColumnRegex = Regex("""`?([A-Za-z_][A-Za-z0-9_]*)`?""")

    private fun replay(sqls: Map<Pair<Int, Int>, List<String>>): Map<String, TableState> {
        val tables = mutableMapOf<String, TableState>()
        val forward = sqls.keys.filter { it.first < it.second }.sortedWith(compareBy({ it.first }, { it.second }))
        for ((a, b) in forward) {
            val tag = "MIGRATION_${a}_$b"
            for (ddl in sqls[a to b].orEmpty()) {
                createTableRegex.find(ddl)?.let { t ->
                    val name = t.groupValues[1]
                    if (name !in tables) {
                        val cols = columnDefRegex.findAll(t.groupValues[2])
                            .map { it.groupValues[1] }.toMutableSet()
                        tables[name] = TableState(cols = cols, createdBy = tag)
                    }
                    return@let
                }
                Regex("""addColumnIfMissing\("([^"]+)","([^"]+)"\)""").find(ddl)?.let { am ->
                    val tbl = am.groupValues[1]
                    val col = am.groupValues[2]
                    tables[tbl]?.let {
                        it.cols.add(col)
                        it.added.add(col)
                    }
                    return@let
                }
                createIndexRegex.find(ddl)?.let { ci ->
                    val unique = ci.groupValues[1].isNotBlank()
                    val iname = ci.groupValues[2]
                    val tbl = ci.groupValues[3]
                    val cols = indexColumnRegex.findAll(ci.groupValues[4])
                        .map { it.groupValues[1] }.toList()
                    val state = tables.getOrPut(tbl) { TableState(createdBy = null) }
                    state.indexes[iname] = IndexState(unique, cols, tag)
                    return@let
                }
                dropIndexRegex.find(ddl)?.let { di ->
                    for (state in tables.values) state.indexes.remove(di.groupValues[1])
                    return@let
                }
                dropTableRegex.find(ddl)?.let { dt ->
                    tables.remove(dt.groupValues[1])
                    return@let
                }
            }
        }
        return tables
    }

    // --- expected schema ----------------------------------------------------

    private fun loadSchema(file: File): Map<String, SchemaSnapshot> {
        require(file.isFile) { "missing schema file ${file.absolutePath}" }
        val db = JSONObject(file.readText()).getJSONObject("database")
        val out = mutableMapOf<String, SchemaSnapshot>()
        val entities = db.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val columns = mutableSetOf<String>()
            val fields = e.getJSONArray("fields")
            for (j in 0 until fields.length()) {
                columns.add(fields.getJSONObject(j).getString("columnName"))
            }
            val indexes = mutableMapOf<String, Pair<Boolean, List<String>>>()
            val idx = e.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until idx.length()) {
                val index = idx.getJSONObject(j)
                val cols = mutableListOf<String>()
                val colArr = index.getJSONArray("columnNames")
                for (k in 0 until colArr.length()) cols.add(colArr.getString(k))
                indexes[index.getString("name")] = index.getBoolean("unique") to cols
            }
            out[e.getString("tableName")] = SchemaSnapshot(columns, indexes)
        }
        return out
    }

    private fun exportedSchemas(): List<Pair<Int, String>> =
        (schemaDir.listFiles { f -> f.extension == "json" } ?: emptyArray<File>())
            .mapNotNull {
                val version = JSONObject(it.readText()).getJSONObject("database").getInt("version")
                version to it.name
            }
            .sortedBy { it.first }

    @Test
    fun `migration chain replays cleanly against the newest exported schema`() {
        require(appDbFile.isFile) { "missing ${appDbFile.absolutePath}" }
        val source = appDbFile.readText()
        val sqls = migrationSqls(source)
        val replayed = replay(sqls)

        val schemas = exportedSchemas()
        require(schemas.isNotEmpty()) {
            "No exported Room schema found at ${schemaDir.absolutePath} — exportSchema must stay on"
        }
        val (_, newestName) = schemas.last()
        val (_, oldestName) = schemas.first()
        val expected = loadSchema(File(schemaDir, newestName))
        val oldest = loadSchema(File(schemaDir, oldestName))

        val problems = mutableListOf<String>()
        val allTables = (replayed.keys + expected.keys).sorted()
        for (table in allTables) {
            val r = replayed[table]
            val e = expected[table]
            if (r == null) {
                problems.add("$table: exported in $newestName but no forward migration creates it")
                continue
            }
            if (e == null) {
                problems.add("$table: created by ${r.createdBy} but absent from $newestName")
                continue
            }
            val preV1 = r.createdBy == null && table in oldest
            if (!preV1) {
                for (col in (r.cols - e.columns).sorted()) {
                    problems.add("$table.$col: exists after replay but not exported")
                }
                for (col in (e.columns - r.cols).sorted()) {
                    problems.add("$table.$col: exported but migrations never add it")
                }
            } else {
                for (col in (r.added - e.columns).sorted()) {
                    problems.add("$table.$col: added by a migration but not exported")
                }
            }
            for (iname in (r.indexes.keys - e.indexes.keys).sorted()) {
                problems.add(
                    "$table: index `$iname` created by ${r.indexes[iname]!!.by} " +
                        "but not declared in $newestName — Room will crash on upgrade"
                )
            }
            for (iname in (e.indexes.keys - r.indexes.keys).sorted()) {
                if (preV1 && iname in (oldest[table]?.indexes ?: emptyMap())) {
                    continue // exists since v1, never re-created by migrations
                }
                problems.add("$table: index `$iname` exported but no migration creates it")
            }
            for (iname in (r.indexes.keys intersect e.indexes.keys).sorted()) {
                val ri = r.indexes[iname]!!
                val ei = e.indexes[iname]!!
                if (ri.unique != ei.first) {
                    problems.add("$table.$iname: unique mismatch replay=${ri.unique} exported=${ei.first}")
                }
                if (ri.columnNames != ei.second) {
                    problems.add(
                        "$table.$iname: columns mismatch replay=${ri.columnNames} exported=${ei.second}"
                    )
                }
            }
        }

        if (problems.isNotEmpty()) {
            fail(
                "schema drift vs $newestName (${problems.size}):\n" +
                    problems.joinToString("\n") { "  - $it" }
            )
        }
    }
}
