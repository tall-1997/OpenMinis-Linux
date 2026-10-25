#!/usr/bin/env python3
"""Detect (and optionally remove) orphan imports in Kotlin files.

An import is "orphan" when its simple name (last dotted segment, or the
`as` alias when present) never occurs as a whole word in the code text of
the file (import lines and the `package` line excluded).

Correctness rules (v2):
  * Single-pass lexer classifies every character as CODE / STR / DROP.
    Line comments, nested block comments (Kotlin `/* /* */ */`), regular
    strings with escapes, raw strings (triple-quoted), char literals and
    backtick identifiers are handled; comments are stripped outside
    strings. String template expressions (`"$x"`, `"${expr}"`, including
    nested strings/templates) count as code.
  * `getValue` / `setValue` / `provideDelegate` are used via `by`
    delegation and never appear by name: never orphans.
  * Operator-convention functions (plus, get, invoke, iterator,
    component1..componentN, ...) are referenced by symbol, not by name:
    never orphans.
  * Whole-word (regex \\b) matching, so `Text` does not match `TextField`.
  * A name that occurs ONLY inside string literals is reported as
    "uncertain" (possible reflection) and is never removed.
  * Files that fail to lex (unterminated comment/string/template/char,
    stray backtick) are reported as parse errors and never modified.

Modes:
  default   print a per-file report of orphan imports
  --json    machine-readable report on stdout
  --apply   delete orphan import lines (whole line only). Every modified
            file is backed up to BACKUP_DIR first, and brace/paren/bracket
            counts are asserted unchanged before and after the write.

Usage:
  python scripts/dev/find_orphan_imports.py [--json] [--apply]
         [--report FILE] [--backup-dir DIR] [--root DIR] [paths...]
"""
import argparse
import json
import os
import re
import shutil
import sys
import tempfile
import time

CODE, STR, DROP = 0, 1, 2

BY_DELEGATE_NAMES = {"getValue", "setValue", "provideDelegate"}

OPERATOR_NAMES = {
    "plus", "minus", "times", "div", "rem",
    "plusAssign", "minusAssign", "timesAssign", "divAssign", "remAssign",
    "unaryPlus", "unaryMinus", "not", "inc", "dec",
    "get", "set", "invoke", "contains", "rangeTo", "rangeUntil",
    "compareTo", "iterator",
}
COMPONENT_RE = re.compile(r"component\d+\Z")

IDENT_START = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_")
IDENT_CHARS = IDENT_START | set("0123456789")

IMPORT_STMT_RE = re.compile(
    r"import\s+(?P<path>[A-Za-z_][\w]*(?:\.[\w]+|\.\*)+)"
    r"(?:\s+as\s+(?P<alias>[A-Za-z_]\w*))?\Z"
)
PACKAGE_STMT_RE = re.compile(r"package\s+[\w.]+\Z")


class ParseError(Exception):
    pass


def lex_kotlin(src):
    """Single pass over `src`.

    Returns a bytearray `cls` with CODE / STR / DROP per character:
      CODE  real code (including string-template expressions)
      STR   plain string-literal content (never code)
      DROP  comments and lexical delimiters
    Raises ParseError on structural anomalies.
    """
    n = len(src)
    cls = bytearray(n)  # CODE by default

    def drop(a, b):
        b = min(b, n)
        for j in range(a, b):
            cls[j] = DROP

    i = 0
    stack = ["code"]  # 'code' | ('tmpl', depth) | 'line' | 'block' | 'str' | 'raw' | 'char'
    while i < n:
        top = stack[-1]
        c = src[i]

        if top == "line":
            j = src.find("\n", i)
            if j == -1:
                drop(i, n)
                i = n
            else:
                drop(i, j)
                i = j  # newline itself stays
            stack.pop()

        elif top == "block":
            if c == "*" and i + 1 < n and src[i + 1] == "/":
                drop(i, i + 2)
                i += 2
                stack.pop()
            elif c == "/" and i + 1 < n and src[i + 1] == "*":
                drop(i, i + 2)
                i += 2
                stack.append("block")  # Kotlin block comments nest
            else:
                cls[i] = DROP
                i += 1

        elif top == "str":
            if c == '"':
                cls[i] = DROP
                i += 1
                stack.pop()
            elif c == "\\":
                drop(i, i + 2)
                i += 2
            elif c == "$" and i + 1 < n and (src[i + 1] in IDENT_START or src[i + 1] == "{"):
                cls[i] = DROP  # '$'
                if src[i + 1] == "{":
                    i += 1
                    stack.append(["tmpl", 0])
                else:
                    i += 1
                    while i < n and src[i] in IDENT_CHARS:
                        cls[i] = CODE
                        i += 1
            elif c == "\n":
                raise ParseError("unterminated string literal")
            else:
                cls[i] = STR
                i += 1

        elif top == "raw":
            if c == '"':
                j = i
                while j < n and src[j] == '"':
                    j += 1
                k = j - i
                if k >= 3:
                    # Kotlin closes a raw string with the LAST three quotes of
                    # the run: content absorbs the first k-3 (verified against
                    # kotlinc: '"""abc""""' == 'abc"', '""""""""' == '""').
                    for t in range(i, j - 3):
                        cls[t] = STR
                    drop(j - 3, j)
                    i = j
                    stack.pop()
                else:
                    for t in range(i, j):
                        cls[t] = STR
                    i = j
            elif c == "$" and i + 1 < n and (src[i + 1] in IDENT_START or src[i + 1] == "{"):
                cls[i] = DROP
                if src[i + 1] == "{":
                    i += 1
                    stack.append(["tmpl", 0])
                else:
                    i += 1
                    while i < n and src[i] in IDENT_CHARS:
                        cls[i] = CODE
                        i += 1
            else:
                cls[i] = STR  # backslash is plain content in raw strings
                i += 1

        elif top == "char":
            if c == "\\":
                drop(i, i + 2)
                i += 2
            elif c == "'":
                cls[i] = DROP
                i += 1
                stack.pop()
            elif c == "\n":
                raise ParseError("unterminated char literal")
            else:
                cls[i] = DROP
                i += 1

        else:  # 'code' or ['tmpl', depth]
            in_tmpl = isinstance(top, list)
            if in_tmpl and c == "}":
                top[1] -= 1
                cls[i] = CODE
                i += 1
                if top[1] == 0:
                    stack.pop()
            elif in_tmpl and c == "{":
                top[1] += 1
                cls[i] = CODE
                i += 1
            elif c == "/" and i + 1 < n and src[i + 1] == "/":
                drop(i, i + 2)
                i += 2
                stack.append("line")
            elif c == "/" and i + 1 < n and src[i + 1] == "*":
                drop(i, i + 2)
                i += 2
                stack.append("block")
            elif c == '"':
                if src.startswith('"""', i):
                    drop(i, i + 3)
                    i += 3
                    stack.append("raw")
                else:
                    cls[i] = DROP
                    i += 1
                    stack.append("str")
            elif c == "'":
                cls[i] = DROP
                i += 1
                stack.append("char")
            elif c == "`":
                j = src.find("`", i + 1)
                if j == -1:
                    raise ParseError("unterminated backtick identifier")
                cls[i] = DROP  # opening backtick; content stays CODE
                cls[j] = DROP  # closing backtick
                i = j + 1
            else:
                i += 1  # stays CODE

    if len(stack) != 1:
        raise ParseError("unterminated %s at EOF" % stack[-1])
    return cls


def _stmt_text(ln, cls, line_start):
    """Chars of a line up to the first non-CODE char (statement prefix).

    `line_start` is the offset of the line's first character; leading
    whitespace is CODE, so the prefix is stripped afterwards.
    """
    out = []
    j = 0
    while j < len(ln) and cls[line_start + j] == CODE:
        out.append(ln[j])
        j += 1
    return "".join(out).strip()


def scan_text(src):
    """Scan one file's text.

    Returns dict:
      error: None or message
      imports: list of dicts (lineno, line, path, alias, simple, kind)
               kind in {'wildcard','orphan','used','uncertain'}
    """
    try:
        cls = lex_kotlin(src)
    except ParseError as e:
        return {"error": str(e), "imports": []}

    lines = src.split("\n")  # keeps trailing '\r' inside elements (CRLF-safe)
    result = {"error": None, "imports": []}

    # offsets of each line within src
    offsets = []
    pos = 0
    for ln in lines:
        offsets.append(pos)
        pos += len(ln) + 1

    import_lines = set()
    imports = []
    for idx, ln in enumerate(lines):
        stripped = ln.strip()
        if not stripped.startswith("import"):
            continue
        stmt = _stmt_text(ln, cls, offsets[idx])
        m = IMPORT_STMT_RE.match(stmt)
        if not m:
            continue  # looks like import but not a clean statement -> body text
        path = m.group("path")
        alias = m.group("alias")
        simple = alias if alias else path.rsplit(".", 1)[-1]
        item = {
            "lineno": idx + 1,
            "line": ln,
            "path": path,
            "alias": alias,
            "simple": simple,
            "kind": "wildcard" if path.endswith(".*") else "pending",
        }
        imports.append(item)
        import_lines.add(idx)

    # package lines are also not "usage" context
    package_lines = set()
    for idx, ln in enumerate(lines):
        if PACKAGE_STMT_RE.match(_stmt_text(ln, cls, offsets[idx])):
            package_lines.add(idx)

    # build code text and string text (import & package lines excluded from code)
    code_parts = []
    str_parts = []
    for idx, ln in enumerate(lines):
        base = offsets[idx]
        code_parts.append("".join(
            ch for k, ch in enumerate(ln) if cls[base + k] == CODE
        ) if idx not in import_lines and idx not in package_lines else "")
        str_parts.append("".join(
            ch for k, ch in enumerate(ln) if cls[base + k] == STR
        ))
    code_text = "\n".join(code_parts)
    str_text = "\n".join(str_parts)

    for item in imports:
        if item["kind"] != "pending":
            continue
        name = item["simple"]
        if name in BY_DELEGATE_NAMES or name in OPERATOR_NAMES or COMPONENT_RE.match(name):
            item["kind"] = "used"
            continue
        if re.search(r"\b%s\b" % re.escape(name), code_text):
            item["kind"] = "used"
        elif re.search(r"\b%s\b" % re.escape(name), str_text):
            item["kind"] = "uncertain"
        else:
            item["kind"] = "orphan"

    result["imports"] = imports
    return result


def scan_file(path, encoding="utf-8", newline_preserve=True):
    with open(path, encoding=encoding, newline="" if newline_preserve else None) as f:
        src = f.read()
    res = scan_text(src)
    res["path"] = path
    return res


def count_delims(s):
    return (
        s.count("(") - s.count(")"),
        s.count("{") - s.count("}"),
        s.count("[") - s.count("]"),
    )


def apply_to_text(src, linenos):
    """Delete the 1-based given lines. Returns new text."""
    drop = set(linenos)
    lines = src.split("\n")
    out = [ln for i, ln in enumerate(lines, 1) if i not in drop]
    return "\n".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("paths", nargs="*")
    ap.add_argument("--root", help="recursively scan all *.kt under this dir")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--apply", action="store_true", help="delete orphan import lines")
    ap.add_argument("--report", help="write a JSON report to this file")
    ap.add_argument("--backup-dir", help="backup dir for --apply (default: system temp)")
    args = ap.parse_args(argv)

    files = list(args.paths)
    if args.root:
        for dirpath, _dirs, names in os.walk(args.root):
            for nm in sorted(names):
                if nm.endswith(".kt"):
                    files.append(os.path.join(dirpath, nm))

    if not files:
        ap.error("no input files (pass paths or --root)")

    results = []
    for p in files:
        try:
            res = scan_file(p)
        except Exception as e:  # never let one file abort a batch
            res = {"path": p, "error": "read error: %s" % e, "imports": []}
        results.append(res)

    summary = {"files": 0, "orphans": 0, "uncertain": 0, "errors": 0,
               "changed_files": 0, "removed_lines": 0}

    if args.apply:
        backup_root = args.backup_dir or os.path.join(
            tempfile.gettempdir(), "orphan_import_backup_" + time.strftime("%Y%m%d_%H%M%S"))
        os.makedirs(backup_root, exist_ok=True)

    for res in results:
        orphans = [it for it in res["imports"] if it["kind"] == "orphan"]
        uncertain = [it for it in res["imports"] if it["kind"] == "uncertain"]
        summary["files"] += 1
        summary["orphans"] += len(orphans)
        summary["uncertain"] += len(uncertain)
        if res["error"]:
            summary["errors"] += 1

        if args.apply and orphans and not res["error"]:
            src = open(res["path"], encoding="utf-8", newline="").read()
            linenos = [it["lineno"] for it in orphans]
            new = apply_to_text(src, linenos)
            before = count_delims(src)
            after = count_delims(new)
            if before != after:
                res["apply_error"] = "delimiter balance would change %s -> %s" % (before, after)
                summary["errors"] += 1
                continue
            backup_path = os.path.join(backup_root, res["path"].replace(":", "_").replace("\\", "/").lstrip("/"))
            os.makedirs(os.path.dirname(backup_path), exist_ok=True)
            shutil.copy2(res["path"], backup_path)
            with open(res["path"], "w", encoding="utf-8", newline="") as f:
                f.write(new)
            res["removed"] = linenos
            summary["changed_files"] += 1
            summary["removed_lines"] += len(linenos)

    if args.json:
        out = {
            "summary": summary,
            "files": [
                {
                    "path": r["path"],
                    "error": r["error"],
                    "imports": [
                        {"lineno": it["lineno"], "line": it["line"], "kind": it["kind"]}
                        for it in r["imports"]
                    ],
                }
                for r in results
            ],
        }
        text = json.dumps(out, ensure_ascii=False, indent=1)
    else:
        chunks = []
        for r in results:
            orphans = [it for it in r["imports"] if it["kind"] == "orphan"]
            uncertain = [it for it in r["imports"] if it["kind"] == "uncertain"]
            if not orphans and not uncertain and not r["error"]:
                continue
            lines = ["== %s" % r["path"]]
            if r["error"]:
                lines.append("  ! parse error: %s" % r["error"])
            for it in orphans:
                lines.append("  - L%d: %s" % (it["lineno"], it["line"].strip()))
            for it in uncertain:
                lines.append("  ? L%d: %s  [only inside string literal]" % (it["lineno"], it["line"].strip()))
            chunks.append("\n".join(lines))
        text = "\n".join(chunks)
        if not text:
            text = "(no orphan imports found)"
        text += "\n\nSummary: %d files, %d orphan imports, %d uncertain, %d errors" % (
            summary["files"], summary["orphans"], summary["uncertain"], summary["errors"])
        if args.apply:
            text += "\nApplied: %d files changed, %d lines removed (backup: %s)" % (
                summary["changed_files"], summary["removed_lines"],
                backup_root if args.apply else "-")

    print(text)
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            f.write(json.dumps({"summary": summary, "results": results}, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
