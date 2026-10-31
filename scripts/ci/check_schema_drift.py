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

APP_DB = os.path.join(
    REPO,
    "src/android/app/src/main/java/com/openminis/app/data/db/AppDatabase.kt",
)
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


def replay(sqls):
    """Apply forward migrations 1→N in order; return {table: state}."""
    tables = {}
    for (a, b) in sorted(k for k in sqls if k[0] < k[1]):
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


def newest_and_oldest():
    files = sorted(
        (json.load(open(os.path.join(SCHEMAS_DIR, f)))["database"]["version"], f)
        for f in os.listdir(SCHEMAS_DIR)
        if f.endswith(".json")
    )
    oldest, newest = files[0], files[-1]
    return (
        load_schema(os.path.join(SCHEMAS_DIR, oldest[1])),
        load_schema(os.path.join(SCHEMAS_DIR, newest[1])),
    )


def main():
    source = open(APP_DB, encoding="utf-8").read()
    sqls = migration_sqls(source)
    replayed = replay(sqls)
    (oldest, _), (expected, newest_path) = newest_and_oldest()
    newest_name = os.path.basename(newest_path)

    problems = []
    all_tables = sorted(set(replayed) | set(expected))
    for table in all_tables:
        r, e = replayed.get(table), expected.get(table)
        if r is None:
            problems.append(
                f"{table}: exported in {newest_name} but no forward migration creates it"
            )
            continue
        if e is None:
            problems.append(
                f"{table}: created by {r['created_by']} but absent from {newest_name}"
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
                f"but not declared in {newest_name} — Room will crash on upgrade"
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

    if problems:
        print(f"schema drift vs {newest_name} ({len(problems)}):")
        for p in problems:
            print("  - " + p)
        sys.exit(1)
    print(
        f"schema drift check passed: {len(all_tables)} tables replay cleanly against {newest_name}"
    )


if __name__ == "__main__":
    main()
