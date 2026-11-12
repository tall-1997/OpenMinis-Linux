#!/usr/bin/env python3
"""Fail when hand-written Room migration DDL drifts from the exported schema.

Room validates the migrated database against the @Entity declarations on every
upgrade. An index created by a hand-written MIGRATION_a_b but missing from the
entity's `indices = [...]` crashes the app at startup with
"IllegalStateException: Migration didn't properly handle: <table>" — the crash
happens inside RoomOpenHelper.onUpgrade, after the migration ran, so it takes
down the whole app instead of one screen.

This checker makes that failure a two-second local check: it replays every
forward migration in AppDatabase.kt from version 1 to the newest exported
schema and compares the resulting per-table column and index state against
<schemas>/<AppDatabase>/<newest>.json. The exported JSON and the @Entity
annotations are kept in lockstep by Room's schema export, so agreeing with the
JSON means agreeing with the entities too.

Exit 1 on any drift, printing the table, the direction of the drift, and the
migration that last touched the object so a fix shows up as an obvious edit
rather than a hunt.

Limitations (deliberate):
- Tables and indexes that exist since schema v1 (no CREATE anywhere in the
  migration chain, verified against the oldest exported JSON) are not compared:
  the migrations never define their initial shape, so there is nothing to
  replay. Deltas applied to them (ADD COLUMN, CREATE/DROP INDEX) ARE checked.
- Column comparison is name-only: affinity / notNull / defaults are not
  replayed. A type-level drift still needs the MigrationTestHelper tests.
- DDL is extracted from string literals inside the migration bodies. SQL that
  is assembled with non-literal interpolation would be missed — keep migration
  DDL as literal strings (the existing style already does).
"""
import json
import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

DB_DIR = os.path.join(
    REPO,
    "src/android/app/src/main/java/com/openminis/app/data/db",
)

# The package holds two independent Room databases, and their migration lists
# reuse the same MIGRATION_a_b names for different schemas (ProviderDatabase's
# 3→4 creates provider_thinking_rules; AppDatabase's 3→4 creates
# compact_markers). Blanket-scanning the directory therefore lets one database
# overwrite the other's replay. Name AppDatabase's migration sources explicitly;
# a missing entry fails loudly instead of silently replaying the wrong schema.
# AuditMigrations.kt joined the split when AppMigrations.kt hit the 800-line
# policy cap; MemoryEmbeddingMigrations.kt followed the same pattern (#27)
# (mirrors appDbSources in SchemaMigrationDriftTest.kt).
APP_DB_SOURCES = ("AppDatabase.kt", "AppMigrations.kt", "AuditMigrations.kt", "MemoryEmbeddingMigrations.kt")


def load_migration_sources():
    """AppDatabase's migration text, from every file that contributes DDL.

    Migrations were split out of AppDatabase.kt into AppMigrations.kt when the
    class hit the file-size cap; the gate follows the code rather than pinning
    a single filename.
    """
    missing = [n for n in APP_DB_SOURCES if not os.path.exists(os.path.join(DB_DIR, n))]
    if missing:
        raise SystemExit(
            "schema drift: migration sources missing: %s — update APP_DB_SOURCES"
            % ", ".join(missing)
        )
    return "\n".join(open(os.path.join(DB_DIR, n), encoding="utf-8").read() for n in APP_DB_SOURCES)
SCHEMAS_DIR = os.path.join(REPO, "src/android/app/schemas/com.openminis.app.data.db.AppDatabase")

# MIGRATION_A_B = object : Migration(A, B) { ... }
MIGRATION = re.compile(
    r"\bMIGRATION_(\d+)_(\d+)\s*=\s*object\s*:\s*Migration\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*\{"
)

CREATE_TABLE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?\s*\((.+)\)",
    re.S | re.I,
)
CREATE_INDEX = re.compile(
    r"CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?\s+ON\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\s*\((.+)\)",
    re.S | re.I,
)
DROP_INDEX = re.compile(
    r"DROP\s+INDEX\s+(?:IF\s+EXISTS\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?",
    re.I,
)
DROP_TABLE = re.compile(
    r"DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?",
    re.I,
)
ADD_COLUMN = re.compile(
    r'addColumnIfMissing\("([A-Za-z_][A-Za-z0-9_]*)",\s*"([A-Za-z_][A-Za-z0-9_]*)"',
)
COLUMN_DEF = re.compile(r"`?([A-Za-z_][A-Za-z0-9_]*)`?\s+(?:TEXT|INTEGER|REAL|BLOB|NUMERIC)", re.I)


def extract_body(text, start):
    """Return the body of the brace at `start` (index just after '{')."""
    depth = 1
    i = start
    while i < len(text) and depth:
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        i += 1
    return text[start:i - 1]


def concat_literals(seg):
    """Concatenate every string literal inside `seg`, in source order."""
    out = []
    i = 0
    n = len(seg)
    while i < n:
        if seg.startswith('"""', i):
            j = seg.find('"""', i + 3)
            if j == -1:
                break
            out.append(seg[i + 3:j])
            i = j + 3
        elif seg[i] == '"':
            j = i + 1
            while j < n:
                if seg[j] == "\\":
                    j += 2
                    continue
                if seg[j] == '"':
                    break
                j += 1
            out.append(seg[i + 1:j])
            i = j + 1
        else:
            i += 1
    return "".join(out)


def execsql_sqls(body):
    """Full SQL string per `execSQL(...)` call, concatenation resolved."""
    sqls = []
    for m in re.finditer(r"\bexecSQL\s*\(", body):
        i, j = m.end(), m.end()
        depth = 1
        while j < len(body) and depth:
            c = body[j]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            j += 1
        sqls.append(re.sub(r"\s+", " ", concat_literals(body[i:j - 1])).strip())
    return sqls


def migration_sqls(source):
    """{(from, to): [ddl, ...]} for every migration, forward and backward."""
    out = {}
    for m in MIGRATION.finditer(source):
        body = extract_body(source, m.end())
        ddl = execsql_sqls(body)
        for am in ADD_COLUMN.finditer(body):
            ddl.append('addColumnIfMissing("%s","%s")' % (am.group(1), am.group(2)))
        out[(int(m.group(1)), int(m.group(2)))] = ddl
    return out


def replay(sqls, upto=None):
    """Apply forward migrations 1→upto in order; return {table: state}."""
    tables = {}
    for (a, b) in sorted(k for k in sqls if k[0] < k[1]):
        if upto is not None and b > upto:
            continue
        tag = "MIGRATION_%d_%d" % (a, b)
        for ddl in sqls[(a, b)]:
            t = CREATE_TABLE.search(ddl)
            if t:
                name, cols = t.group(1), t.group(2)
                if name in tables:
                    continue  # IF NOT EXISTS
                tables[name] = {
                    "cols": set(COLUMN_DEF.findall(cols)),
                    "added": set(),
                    "indexes": {},
                    "created_by": tag,
                }
                continue
            am = ADD_COLUMN.search(ddl)
            if am:
                tbl, col = am.group(1), am.group(2)
                if tbl in tables:
                    tables[tbl]["cols"].add(col)
                    tables[tbl]["added"].add(col)
                continue
            ci = CREATE_INDEX.search(ddl)
            if ci:
                unique, iname, tbl = ci.group(1), ci.group(2), ci.group(3)
                cols = re.findall(r"`?([A-Za-z_][A-Za-z0-9_]*)`?", ci.group(4))
                tables.setdefault(
                    tbl,
                    {"cols": set(), "added": set(), "indexes": {}, "created_by": None},
                )
                tables[tbl]["indexes"][iname] = {
                    "unique": bool(unique),
                    "columnNames": cols,
                    "by": tag,
                }
                continue
            di = DROP_INDEX.search(ddl)
            if di:
                for tbl in tables.values():
                    tbl["indexes"].pop(di.group(1), None)
                continue
            dt = DROP_TABLE.search(ddl)
            if dt:
                tables.pop(dt.group(1), None)
    return tables


def load_schema(path):
    db = json.load(open(path))
    out = {}
    for e in db["database"]["entities"]:
        out[e["tableName"]] = {
            "columns": {f["columnName"] for f in e["fields"]},
            "indexes": {
                i["name"]: {"unique": i["unique"], "columnNames": i["columnNames"]}
                for i in e.get("indices", [])
            },
        }
    return out, path


def exported_schemas():
    """[(version, filename, (schema, path))] for every export, sorted.

    Every export is compared, not just the newest: the hand-reconstructed
    intermediate exports (13.json / 17.json, see AppDatabase.kt) have no Room
    build behind them, so the replay is the only thing keeping them honest.
    """
    out = []
    for f in os.listdir(SCHEMAS_DIR):
        if not f.endswith(".json"):
            continue
        path = os.path.join(SCHEMAS_DIR, f)
        out.append((json.load(open(path))["database"]["version"], f, load_schema(path)))
    return sorted(out)


def compare(replayed, expected, oldest, label):
    """Drift problems for one exported version; [] when the replay at that
    version agrees with the export. `oldest` is the pre-chain baseline: tables
    it already lists predate the migration chain, and at intermediate versions
    a pre-chain table no migration has touched yet has no replay state at all.
    """
    problems = []
    for table in sorted(set(replayed) | set(expected)):
        r, e = replayed.get(table), expected.get(table)
        if r is None:
            if table in oldest:
                continue  # pre-chain table, not touched by any migration yet
            problems.append(
                f"{table}: exported in {label} but no forward migration creates it"
            )
            continue
        if e is None:
            problems.append(
                f"{table}: created by {r['created_by']} but absent from {label}"
            )
            continue
        pre_v1 = r["created_by"] is None and table in oldest
        if not pre_v1:
            for col in sorted(r["cols"] - e["columns"]):
                problems.append(f"{table}.{col}: exists after replay but not exported")
            for col in sorted(e["columns"] - r["cols"]):
                problems.append(f"{table}.{col}: exported but migrations never add it")
        else:
            for col in sorted(r["added"] - e["columns"]):
                problems.append(
                    f"{table}.{col}: added by a migration but not exported"
                )
        for iname in sorted(r["indexes"].keys() - e["indexes"].keys()):
            problems.append(
                f"{table}: index `{iname}` created by {r['indexes'][iname]['by']} "
                f"but not declared in {label} — Room will crash on upgrade"
            )
        for iname in sorted(e["indexes"].keys() - r["indexes"].keys()):
            if pre_v1 and iname in oldest.get(table, {}).get("indexes", {}):
                continue  # exists since v1, never re-created by migrations
            problems.append(f"{table}: index `{iname}` exported but no migration creates it")
        for iname in sorted(r["indexes"].keys() & e["indexes"].keys()):
            ri, ei = r["indexes"][iname], e["indexes"][iname]
            if ri["unique"] != ei["unique"]:
                problems.append(
                    f"{table}.{iname}: unique mismatch replay={ri['unique']} exported={ei['unique']}"
                )
            if ri["columnNames"] != ei["columnNames"]:
                problems.append(
                    f"{table}.{iname}: columns mismatch replay={ri['columnNames']} "
                    f"exported={ei['columnNames']}"
                )
    return problems


def main():
    source = load_migration_sources()
    sqls = migration_sqls(source)
    exports = exported_schemas()
    if not exports:
        raise SystemExit("schema drift: no exported schemas found in %s" % SCHEMAS_DIR)
    oldest = exports[0][2][0]

    problems = []
    for version, fname, (expected, _) in exports:
        problems.extend(compare(replay(sqls, upto=version), expected, oldest, fname))

    if problems:
        print(f"schema drift ({len(problems)}):")
        for p in problems:
            print("  - " + p)
        sys.exit(1)
    print(
        "schema drift check passed: %d exported versions replay cleanly "
        "(%s → %s)" % (len(exports), exports[0][1], exports[-1][1])
    )


if __name__ == "__main__":
    main()
