#!/usr/bin/env python3
"""Architecture policy ratchet — keeps oversized source files on a one-way
diet (taixu-style architecture-policy, adapted from the UI review report).

Rules (policy lives in scripts/ci/architecture-policy.json):
  1. NEW files may not exceed `newFileMaxLines` (800).
  2. Files already over the cap when the policy landed are registered in
     `baseline` with their line count at registration time. A baseline entry
     is a CEILING: the file may shrink freely but may never grow past it.
  3. The baseline itself only tightens:
       - file grew past its ceiling            -> FAIL, shrink it or extract
       - file shrank below its ceiling         -> FAIL, lower the entry
         (run `--update-baseline` — it can only lower or remove entries,
         never raise them, so it is always safe to run)
       - baseline entry with no file on disk   -> FAIL, delete the entry
  4. A baseline file that drops under `newFileMaxLines` graduates: remove its
     entry entirely (it is now an ordinary file governed by rule 1).
  5. `forbiddenImports` pairs a `fromPath` regex (matched against the file's
     POSIX path) with a `ban` substring phrased as `import <fqn-prefix>`.
     Comments and string literals are masked out first, so the word `import`
     inside a prompt or comment never trips the gate.
     FQN UPGRADE (review v3 item 9): the ban is stripped to its fully-
     qualified-name core (e.g. `com.openminis.app.ui.`) and matched ANYWHERE
     in the surviving code text — import statements AND bare fully-qualified
     references like `com.openminis.app.ui.ChatMessage(...)` written with no
     import at all (the old import-line-only scan could be dodged that way).
     `forbiddenImportsBaseline` freezes the existing debt per file as a
     count ceiling — the same one-way ratchet as the line-count baseline:
       - a file not on the baseline may not contain a forbidden import or
         bare FQN reference;
       - an entry is a CEILING: references may be removed but never added;
       - `--update-baseline` only lowers entries or drops clean ones; it can
         never register NEW debt.
  6. `--self-test` runs the checker's own assertions (masking, FQN scan,
     path filter, legacy bans) and exits non-zero on any failure. It is a
     CI-runnable guard on the checker itself; it never reads the policy.

Exit code 0 = policy satisfied. Non-zero = violations listed on stdout.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
POLICY_PATH = pathlib.Path(__file__).resolve().parent / "architecture-policy.json"


def count_lines(path: pathlib.Path) -> int:
    """Editor-style line count (a final line without a newline still counts)."""
    with path.open("r", encoding="utf-8", errors="replace") as fh:
        return len(fh.read().splitlines())


def watched_files(roots: list[str]) -> list[pathlib.Path]:
    files: list[pathlib.Path] = []
    for root in roots:
        base = REPO_ROOT / root
        if not base.is_dir():
            print(f"error: watched root missing on disk: {root}", file=sys.stderr)
            sys.exit(2)
        files.extend(p for p in base.rglob("*.kt") if p.is_file())
    return sorted(files)


def check_forbidden_patterns(
    files: list[pathlib.Path],
    patterns: list[dict],
) -> list[str]:
    """Literal-substring ratchet: forbiddenPatterns from the policy must not
    appear in any watched *.kt file (e.g. lifecycle-unaware collectAsState)."""
    violations: list[str] = []
    if not patterns:
        return violations
    for path in files:
        rel = path.relative_to(REPO_ROOT).as_posix()
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            for lineno, line in enumerate(fh, 1):
                for rule in patterns:
                    if rule["pattern"] in line:
                        violations.append(
                            f"{rel}:{lineno}: forbidden pattern "
                            f"{rule['pattern']!r} — {rule['reason']}"
                        )
    return violations


def _string_literal(text: str, start: int, quote: str) -> tuple[str, int]:
    """Return (content, index after closing delimiter) for one Kotlin string."""
    i = start + len(quote)
    out: list[str] = []
    while i < len(text):
        if text.startswith(quote, i):
            return "".join(out), i + len(quote)
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            out.append(text[i:i + 2])
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out), i


def _blank_span(text: str, start: int, end: int) -> str:
    """Same-length spaces, keeping newlines so line numbers stay aligned."""
    return "".join(ch if ch == "\n" else " " for ch in text[start:end])


def mask_comments_and_strings(text: str) -> str:
    """Blank every comment and string literal (newlines preserved), so only
    real code text survives. Handles //, /* */ and both string flavours plus
    'c' char literals, mirroring the CJK gate's lexer."""
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "\n":
            out.append("\n")
            i += 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append(_blank_span(text, i, end))
            i = end
        elif text.startswith('"""', i):
            _, end = _string_literal(text, i, '"""')
            out.append(_blank_span(text, i, end))
            i = end
        elif c == '"':
            _, end = _string_literal(text, i, '"')
            out.append(_blank_span(text, i, end))
            i = end
        elif c == "'":
            _, end = _string_literal(text, i, "'")
            out.append(_blank_span(text, i, end))
            i = end
        else:
            out.append(c)
            i += 1
    return "".join(out)


def fqn_core_of_ban(ban: str) -> str | None:
    """The fully-qualified-name core of a `ban` substring.

    Rules phrase their ban as `import <fqn-prefix>`. The FQN scan matches that
    prefix anywhere in code — an import statement is the most common hit
    shape, but a bare `com.openminis.app.ui.X` reference with no import is the
    exact dodge the upgrade exists to catch. A ban NOT phrased as an import
    keeps legacy import-statement-only matching (returns None).
    """
    return ban[len("import "):] if ban.startswith("import ") else None


def scan_forbidden_imports(
    files: list[pathlib.Path],
    rules: list[dict],
    root: pathlib.Path = REPO_ROOT,
) -> dict[str, list[tuple[int, str, dict]]]:
    """Forbidden layering references: `import` statements AND bare FQNs.

    A rule fires when the file's POSIX path matches `fromPath` (regex search)
    and its FQN core (see [fqn_core_of_ban]) occurs in the line of surviving
    code text — comments and string literals are blanked away first, so a
    package path inside a prompt, KDoc `[com.openminis.app.ui.X]` link or SQL
    literal never trips the gate. On a line matching several rules only the
    first one counts, so layered rules (specific package pair first, generic
    layering rule last) never double-report. One hit per line maximum, exactly
    like the old import-only scan, so pre-upgrade baselines carry over
    one-for-one for files whose debt is import-shaped.
    """
    hits: dict[str, list[tuple[int, str, dict]]] = {}
    if not rules:
        return hits
    compiled = [
        (re.compile(rule["fromPath"]), rule, fqn_core_of_ban(rule["ban"]))
        for rule in rules
    ]
    for path in files:
        rel = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8", errors="replace")
        masked = mask_comments_and_strings(text)
        for lineno, (code_line, raw_line) in enumerate(
            zip(masked.splitlines(), text.splitlines()), 1
        ):
            statement = code_line.strip()
            for rx, rule, fqn in compiled:
                if not rx.search(rel):
                    continue
                if fqn is not None:
                    matched = fqn in code_line
                else:
                    # Legacy shape: ban is not an import-prefixed FQN; match
                    # real import statements only, as before the upgrade.
                    matched = (
                        statement.startswith("import ") and rule["ban"] in statement
                    )
                if matched:
                    hits.setdefault(rel, []).append((lineno, raw_line.strip(), rule))
                    break
    return hits


def self_test() -> int:
    """Assertions on the checker itself (run via --self-test).

    Synthetic sources under a throwaway temp dir exercise the three things
    the FQN upgrade changed or could silently break: masking (comments and
    strings must never count), the full-text FQN match (import AND bare
    reference), the fromPath filter, and the legacy ban shape. Never
    touches the real policy or repository sources.
    """
    import tempfile

    failures: list[str] = []

    def check(name: str, cond: bool) -> None:
        if not cond:
            failures.append(name)

    masked = mask_comments_and_strings(
        "val a = 1 // com.openminis.app.ui.X in comment\n"
        "/* com.openminis.app.ui.Y */\n"
        'val s = "com.openminis.app.ui.Z"\n'
        "val k = com.openminis.app.ui.W()\n"
    )
    check("line comment masked", "com.openminis.app.ui.X" not in masked)
    check("block comment masked", "com.openminis.app.ui.Y" not in masked)
    check("string literal masked", "com.openminis.app.ui.Z" not in masked)
    check("code FQN kept", "com.openminis.app.ui.W" in masked)
    check("newlines preserved", masked.count("\n") == 4)

    rules = [
        {"fromPath": "ui_banned/", "ban": "import com.openminis.app.ui.", "reason": "test"},
        {"fromPath": "db_banned/", "ban": "import com.openminis.app.provider.", "reason": "test"},
    ]
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        f1 = root / "ui_banned" / "A.kt"
        f1.parent.mkdir(parents=True)
        f1.write_text(
            "import com.openminis.app.ui.chat.ChatMessage\n"
            "val x = com.openminis.app.ui.chat.ChatMessage()\n"
            "val ok = com.openminis.app.data.db.AppDatabase()\n"
            "import com.openminis.app.data.db.AppDatabase\n",
            encoding="utf-8",
        )
        f2 = root / "clean" / "B.kt"
        f2.parent.mkdir(parents=True)
        f2.write_text(
            'val y = "com.openminis.app.ui.escaped"\n'
            "// com.openminis.app.ui.Commented\n"
            "val fine = com.openminis.app.data.db.Other()\n",
            encoding="utf-8",
        )
        hits = scan_forbidden_imports([f1, f2], rules, root=root)
        check(
            "import line + bare FQN each count once",
            len(hits.get("ui_banned/A.kt", [])) == 2,
        )
        check("masked debt is not a hit", "clean/B.kt" not in hits)
        check(
            "hit line numbers are 1-based raw",
            [h[0] for h in hits.get("ui_banned/A.kt", [])] == [1, 2],
        )

        f3 = root / "not_banned" / "C.kt"
        f3.parent.mkdir(parents=True)
        f3.write_text("val z = com.openminis.app.ui.Anything()\n", encoding="utf-8")
        check(
            "fromPath filter excludes unwatched paths",
            scan_forbidden_imports([f3], rules, root=root) == {},
        )

    legacy = [{"fromPath": "x/", "ban": "com.openminis.app.ui.", "reason": "legacy"}]
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        f = root / "x" / "L.kt"
        f.parent.mkdir(parents=True)
        f.write_text(
            "import com.openminis.app.ui.A\nval q = com.openminis.app.ui.A()\n",
            encoding="utf-8",
        )
        hits = scan_forbidden_imports([f], legacy, root=root)
        check(
            "legacy non-import ban matches import statements only",
            len(hits.get("x/L.kt", [])) == 1 and hits["x/L.kt"][0][0] == 1,
        )

    if failures:
        print("self-test FAILED:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("self-test: OK (masking, FQN full-text scan, fromPath filter, legacy bans)")
    return 0

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--update-baseline",
        action="store_true",
        help="Tighten the baseline to current line counts. Only ever lowers "
        "entries or removes them (graduated/stale files); never raises one.",
    )
    ap.add_argument(
        "--self-test",
        action="store_true",
        help="Run the checker's own assertions (masking, FQN scan, path "
        "filter, legacy bans) against synthetic sources and exit. Never "
        "reads the policy.",
    )
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
    cap: int = policy["newFileMaxLines"]
    roots: list[str] = policy["watchedRoots"]
    baseline: dict[str, int] = policy["baseline"]
    import_rules: list[dict] = policy.get("forbiddenImports", [])
    import_baseline: dict[str, int] = policy.get("forbiddenImportsBaseline", {})

    files = watched_files(roots)
    # Policy paths are repository-style POSIX paths.  pathlib returns
    # backslashes on Windows, which made every baseline entry look stale and
    # every existing oversized file look like an unregistered new file when
    # the check was run locally.  Normalize once at the boundary so the same
    # policy has identical results on Windows and Linux CI.
    by_rel = {p.relative_to(REPO_ROOT).as_posix(): count_lines(p) for p in files}

    import_hits = scan_forbidden_imports(files, import_rules)
    import_counts = {rel: len(hits) for rel, hits in import_hits.items()}

    # Tighten FIRST so the check below validates the post-update state
    # (otherwise a just-lowered entry would still be reported as stale).
    if args.update_baseline:
        changed = False
        for rel in sorted(baseline):
            lines = by_rel.get(rel)
            if lines is None:
                del baseline[rel]  # stale entry
                changed = True
            elif lines <= cap:
                del baseline[rel]  # graduated — ordinary file now
                changed = True
            elif lines < baseline[rel]:
                baseline[rel] = lines  # tighten to the new count
                changed = True
        for rel in sorted(import_baseline):
            cur = import_counts.get(rel, 0)
            if cur == 0:
                del import_baseline[rel]  # clean — drop the entry
                changed = True
            elif cur < import_baseline[rel]:
                import_baseline[rel] = cur  # tighten to the new count
                changed = True
            # cur > baseline is never fixable by re-snapshotting: the check
            # below fails and the imports must be removed in code instead.
        if changed:
            policy["baseline"] = dict(sorted(baseline.items()))
            policy["forbiddenImportsBaseline"] = dict(sorted(import_baseline.items()))
            POLICY_PATH.write_text(
                json.dumps(policy, indent=2, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
            print("baseline tightened (see the diff for the lowered entries)")
        else:
            print("baseline already tight")

    violations: list[str] = []

    # Rule 3a: baseline file grew past its ceiling, or shrank without the
    # entry being lowered (a stale ceiling lets the file grow back for free).
    for rel, ceiling in sorted(baseline.items()):
        lines = by_rel.get(rel)
        if lines is None:
            violations.append(
                f"stale baseline entry (file no longer exists): {rel} — delete the entry"
            )
        elif lines > ceiling:
            violations.append(
                f"{rel}: {lines} lines > ceiling {ceiling} — extract a piece or "
                f"split the file (the ceiling never rises)"
            )
        elif lines < ceiling:
            violations.append(
                f"{rel}: shrank to {lines} lines but ceiling is {ceiling} — "
                f"run --update-baseline and commit the lowered entry"
            )

    # Rules 1+3b: unregistered files over the new-file cap.
    for rel, lines in sorted(by_rel.items()):
        if rel in baseline:
            continue
        if lines > cap:
            violations.append(
                f"{rel}: {lines} lines > new-file cap {cap} — split it before merging"
            )

    # Literal-substring ratchet (see forbiddenPatterns in the policy JSON):
    # e.g. lifecycle-unaware collectAsState may not (re)appear.
    violations.extend(check_forbidden_patterns(files, policy.get("forbiddenPatterns", [])))

    # Layered-import ratchet (see forbiddenImports / forbiddenImportsBaseline
    # in the policy JSON): a count ceiling per file, never rises.
    n_import_details = 0
    for rel, hits in sorted(import_hits.items()):
        count = len(hits)
        allowed = import_baseline.get(rel, 0)
        if count <= allowed:
            continue
        if rel in import_baseline:
            violations.append(
                f"{rel}: {count} forbidden import/FQN reference(s) > baseline "
                f"{allowed} — remove them (the ceiling never rises)"
            )
        else:
            violations.append(
                f"{rel}: {count} forbidden import/FQN reference(s), not on "
                f"baseline — remove them (new debt is never registered by "
                f"--update-baseline)"
            )
        for lineno, statement, rule in hits:
            n_import_details += 1
            violations.append(
                f"    {rel}:{lineno}: {statement} — {rule['reason']}"
            )
    for rel, allowed in sorted(import_baseline.items()):
        if import_counts.get(rel, 0) < allowed:
            violations.append(
                f"{rel}: {import_counts.get(rel, 0)} forbidden import/FQN "
                f"reference(s) but baseline is {allowed} — run "
                f"--update-baseline and commit the lowered entry"
            )

    if violations:
        print(f"architecture policy: {len(violations) - n_import_details} violation(s)")
        for v in violations:
            print(f"  - {v}")
        print(
            "\nThe baseline only tightens. To re-snapshot after a legitimate "
            "extraction run:\n"
            "  python3 scripts/ci/check_architecture_policy.py --update-baseline\n"
            "and commit the lowered entries together with your change."
        )
        return 1

    registered = sum(1 for rel in by_rel if rel in baseline)
    print(
        f"architecture policy: OK "
        f"({len(by_rel)} files, cap {cap}, {registered} on baseline, "
        f"{len(import_baseline)} on forbidden-imports baseline)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
