#!/usr/bin/env python3
"""Fail when a PR adds a user-visible string without translating it into
every locale the app ships.

Modeled on cuplivo's `check_no_new_untranslated.py` (their PR gate rejects
any new arb key lacking translations). Adapted to Android `strings.xml` and
its sibling default files (`strings_settings.xml` etc.):

  * Baseline keys are those present in the DEFAULT res dir at the PR base
    commit — the repo already carries historical untranslated entries and
    grandfathering them keeps the gate adoptable without a one-time
    translation sweep.
  * A key counts as "added by this PR" when a `<string name="K"` /
    `<plurals name="K"` / `<string-array name="K"` line appears in the
    unified diff of ANY default values/*.xml between base and head.
  * Every key added that way must exist in every `res/values-*/` dir — in
    ANY of that dir's xml files (settings strings live in
    strings_settings.xml for de/es mirrors and in strings.xml elsewhere).
    `values-night` and other qualifier-only dirs with no string resources
    are skipped automatically.
  * VALUE-LEVEL check: a new <string> whose locale value is identical to
    the English default is a fake translation when the value is real
    prose. Brand names, API/protocol terms, code placeholders and
    format-only strings legitimately stay identical; those are enumerated
    in scripts/ci/l10n-identical-baseline.json (reviewed allow-list, same
    grandfathering spirit as the key-level baseline).
  * All content is read from the git refs (git show / git ls-tree), never
    from the working tree — a local run against a historical head ref
    must report that ref's content, not whatever happens to be checked
    out. CI checks out github.sha, so the two coincide there.

Usage: check_new_untranslated.py <base-ref> <head-ref> [res-dir]
Exit 0 = clean, 1 = new untranslated keys (details on stdout).
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NAME_RE = re.compile(r'<(?:string|plurals|string-array)\s+name="([^"]+)"')
# Not user-visible; mirrors the `translatable="false"` escape hatch.
TRANSLATABLE_FALSE_RE = re.compile(r'translatable="false"')

# --- value-level fake-translation detection -----------------------------
# A locale value identical to the English default is a fake translation
# when it reads like real prose. Format specifiers, placeholders, ALL-CAPS
# acronyms and URLs are stripped before counting words, so only genuine
# multi-word prose triggers the check.
FORMAT_TOKEN_RE = re.compile(
    r"%\d+\$[sd]|%[sd]|\{\{[^}]+\}\}|&[a-z]+;|https?://\S+"
    r"|\b[A-Z0-9]{2,}[A-Za-z0-9]*\b"
)

def is_prose(value: str) -> bool:
    """True when the value reads like translatable English prose."""
    v = value.strip()
    if not re.search(r"[a-z]", v):
        return False  # ALL-CAPS acronyms, symbols, numbers only
    words = FORMAT_TOKEN_RE.sub(" ", v).split()
    return len([w for w in words if re.search(r"[a-z]", w)]) >= 2

def norm_escaped(value: str) -> str:
    """Normalize Android escape sequences (\\' vs ') and whitespace so a
    copied-English value that only differs in escaping still compares
    equal — the exact fake-translation shape this gate exists to catch."""
    return re.sub(r"\s+", " ", value.replace("\\", "")).strip()

def load_identical_baseline() -> set[str]:
    """Reviewed allow-list of keys whose locale value may legitimately equal
    the English default (brands, API terms, code placeholders). Lives next
    to this script so new entries go through PR review."""
    baseline_file = Path(__file__).resolve().parent / "l10n-identical-baseline.json"
    if not baseline_file.exists():
        return set()
    try:
        return set(json.loads(baseline_file.read_text(encoding="utf-8")))
    except (json.JSONDecodeError, OSError) as e:
        raise SystemExit(f"unreadable baseline {baseline_file}: {e}") from e

def git_show(ref: str, path: str) -> str:
    # Windows: Path joins with '\' but git paths inside a rev must be '/'.
    return subprocess.run(
        ["git", "show", f"{ref}:{path.replace(chr(92), '/')}"],
        check=True, capture_output=True, text=True, encoding="utf-8",
    ).stdout

def git_diff_added_names(base: str, head: str, path: str) -> set[str]:
    diff = subprocess.run(
        ["git", "diff", "--unified=0", base, head, "--", path],
        check=True, capture_output=True, text=True, encoding="utf-8",
    ).stdout
    names: set[str] = set()
    for line in diff.splitlines():
        if not line.startswith("+") or line.startswith("+++"):
            continue
        m = NAME_RE.search(line)
        if m and not TRANSLATABLE_FALSE_RE.search(line):
            names.add(m.group(1))
    return names

def keys_in(xml_text: str) -> set[str]:
    root = ET.fromstring(xml_text)
    keys: set[str] = set()
    for tag in ("string", "plurals", "string-array"):
        for node in root.iter(tag):
            name = node.get("name")
            if name:
                keys.add(name)
    return keys

def string_values_in(xml_text: str) -> dict[str, str]:
    """name -> text for <string> entries (plurals/arrays carry no single
    value to compare and stay key-level only)."""
    root = ET.fromstring(xml_text)
    values: dict[str, str] = {}
    for node in root.iter("string"):
        name = node.get("name")
        if name:
            values[name] = "".join(node.itertext())
    return values

def git_ls_files(ref: str, path: str) -> list[str]:
    """Every file under `path` at `ref` (repo-relative, forward slashes)."""
    out = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", ref, "--", path],
        check=True, capture_output=True, text=True, encoding="utf-8",
    ).stdout
    return [line for line in out.splitlines() if line.strip()]

def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    base, head = sys.argv[1], sys.argv[2]
    res_dir = Path(sys.argv[3]) if len(sys.argv) > 3 else Path(
        "src/android/app/src/main/res"
    )
    # Everything below reads tree content AT THE HEAD REF (git show /
    # git ls-tree) — never the working tree. A local run against a
    # historical head must report that ref's content, not whatever
    # happens to be checked out (CI checks out github.sha, so the two
    # coincide there).
    res_posix = res_dir.as_posix()
    res_files = [
        f for f in git_ls_files(head, res_posix)
        if f.startswith(f"{res_posix}/values") and f.endswith(".xml")
    ]
    if not res_files:
        print(f"No xml resources under {res_posix} at {head}.")
        return 1

    # Well-formedness of every xml resource file at HEAD (a broken locale
    # file otherwise only fails at release-assemble time).
    bad_xml: list[str] = []
    for f in sorted(res_files):
        try:
            ET.fromstring(git_show(head, f))
        except ET.ParseError as e:
            bad_xml.append(f"{f}: {e}")
    if bad_xml:
        print("Malformed xml resource at HEAD:")
        print("\n".join(bad_xml))
        return 1

    # Group the head-ref files by values dir: "values" (default) plus
    # each values-<qualifier> locale dir.
    dir_files: dict[str, list[str]] = {}
    for f in res_files:
        rel = f[len(res_posix) + 1:]
        dir_files.setdefault(rel.split("/", 1)[0], []).append(f)
    default_files = dir_files.get("values", [])
    if not default_files:
        print(f"No default values/*.xml at {head}.")
        return 1

    # Keys "added by this PR" = union of added names across all default
    # values/*.xml files (strings.xml + strings_settings.xml + ...).
    added: set[str] = set()
    for f in default_files:
        added |= git_diff_added_names(base, head, f)
    if not added:
        print("No new translatable keys added by this change.")
        return 0

    # Guard: a renamed/removed default key must not make an existing locale
    # file look "complete" — keys reported below are relative to HEAD's
    # default set, so intersect with what HEAD actually defines.
    head_keys: set[str] = set()
    for f in default_files:
        head_keys |= keys_in(git_show(head, f))
    added &= head_keys
    if not added:
        print("New keys were removed again before HEAD; nothing to check.")
        return 0

    # English default values at HEAD for the added keys (value-level check).
    default_values: dict[str, str] = {}
    for f in default_files:
        default_values.update(string_values_in(git_show(head, f)))
    identical_baseline = load_identical_baseline()

    failures: list[str] = []
    for dir_name in sorted(dir_files):
        if dir_name == "values":
            continue
        try:
            locale_keys: set[str] = set()
            locale_values: dict[str, str] = {}
            for f in dir_files[dir_name]:
                text = git_show(head, f)
                locale_keys |= keys_in(text)
                locale_values.update(string_values_in(text))
        except ET.ParseError as e:
            failures.append(f"  {dir_name}: malformed xml ({e})")
            continue
        if not locale_keys:
            # Qualifier-only dir (values-night): no string resources of its
            # own, inherits the default set — not a translation target.
            continue
        missing = sorted(added - locale_keys)
        if missing:
            failures.append(
                f"  {dir_name}: missing {len(missing)} -> "
                + ", ".join(missing[:10])
                + (" ..." if len(missing) > 10 else "")
            )
            continue
        # Value-level: a present key whose locale value equals the English
        # default is a fake translation when the English is real prose.
        # Comparison is escape-normalized: a copy differing only in \\' vs '
        # (the historical wave5 shape) must still be caught.
        fake = sorted(
            k for k in added
            if k in locale_values
            and k not in identical_baseline
            and k in default_values
            and norm_escaped(locale_values[k]) == norm_escaped(default_values[k])
            and is_prose(default_values[k])
        )
        if fake:
            failures.append(
                f"  {dir_name}: untranslated copy of English -> "
                + ", ".join(fake[:10])
                + (" ..." if len(fake) > 10 else "")
            )

    if failures:
        print(
            f"This change adds {len(added)} new string key(s) to values/ "
            "without translating them everywhere:"
        )
        print("\n".join(failures))
        print(
            "\nAdd the keys to every res/values-*/ dir (strings.xml or the "
            "matching sibling file) with a real translation, or mark the "
            "string translatable=\"false\" if it is not user-visible. Keys "
            "whose value legitimately stays identical across locales "
            "(brands, API terms, code placeholders) go into "
            "scripts/ci/l10n-identical-baseline.json."
        )
        return 1

    print(f"All {len(added)} new key(s) translated in every locale.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
