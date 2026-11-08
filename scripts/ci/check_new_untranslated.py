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

Usage: check_new_untranslated.py <base-ref> <head-ref> [res-dir]
Exit 0 = clean, 1 = new untranslated keys (details on stdout).
"""
from __future__ import annotations

import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NAME_RE = re.compile(r'<(?:string|plurals|string-array)\s+name="([^"]+)"')
# Not user-visible; mirrors the `translatable="false"` escape hatch.
TRANSLATABLE_FALSE_RE = re.compile(r'translatable="false"')

def git_show(ref: str, path: str) -> str:
    return subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        check=True, capture_output=True, text=True,
    ).stdout

def git_diff_added_names(base: str, head: str, path: str) -> set[str]:
    diff = subprocess.run(
        ["git", "diff", "--unified=0", base, head, "--", path],
        check=True, capture_output=True, text=True,
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

def dir_keys(locale_dir: Path) -> set[str]:
    """Union of resource keys across every xml file in one values-*/ dir."""
    keys: set[str] = set()
    for f in sorted(locale_dir.glob("*.xml")):
        try:
            keys |= keys_in(f.read_text(encoding="utf-8"))
        except ET.ParseError as e:
            raise ET.ParseError(f"{f}: {e}") from e
    return keys

def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    base, head = sys.argv[1], sys.argv[2]
    res_dir = Path(sys.argv[3]) if len(sys.argv) > 3 else Path(
        "src/android/app/src/main/res"
    )
    default_dir = res_dir / "values"

    # Well-formedness of every xml resource file at HEAD (a broken locale
    # file otherwise only fails at release-assemble time).
    bad_xml: list[str] = []
    for f in sorted(res_dir.glob("values*/*.xml")):
        try:
            ET.parse(f)
        except ET.ParseError as e:
            bad_xml.append(f"{f}: {e}")
    if bad_xml:
        print("Malformed xml resource at HEAD:")
        print("\n".join(bad_xml))
        return 1

    # Keys "added by this PR" = union of added names across all default
    # values/*.xml files (strings.xml + strings_settings.xml + ...).
    added: set[str] = set()
    for default_file in sorted(default_dir.glob("*.xml")):
        added |= git_diff_added_names(base, head, str(default_file))
    if not added:
        print("No new translatable keys added by this change.")
        return 0

    # Guard: a renamed/removed default key must not make an existing locale
    # file look "complete" — keys reported below are relative to HEAD's
    # default set, so intersect with what HEAD actually defines.
    head_keys: set[str] = set()
    for default_file in sorted(default_dir.glob("*.xml")):
        head_keys |= keys_in(git_show(head, str(default_file)))
    added &= head_keys
    if not added:
        print("New keys were removed again before HEAD; nothing to check.")
        return 0

    failures: list[str] = []
    for locale_dir in sorted(res_dir.glob("values-*")):
        try:
            locale_keys = dir_keys(locale_dir)
        except ET.ParseError as e:
            failures.append(f"  {locale_dir.name}: malformed xml ({e})")
            continue
        if not locale_keys:
            # Qualifier-only dir (values-night): no string resources of its
            # own, inherits the default set — not a translation target.
            continue
        missing = sorted(added - locale_keys)
        if missing:
            failures.append(
                f"  {locale_dir.name}: missing {len(missing)} -> "
                + ", ".join(missing[:10])
                + (" ..." if len(missing) > 10 else "")
            )

    if failures:
        print(
            f"This change adds {len(added)} new string key(s) to values/ "
            "without translating them everywhere:"
        )
        print("\n".join(failures))
        print(
            "\nAdd the keys to every res/values-*/ dir (strings.xml or the "
            "matching sibling file), or mark the string "
            "translatable=\"false\" if it is not user-visible."
        )
        return 1

    print(f"All {len(added)} new key(s) translated in every locale.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
