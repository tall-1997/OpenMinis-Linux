#!/usr/bin/env python3
"""Fail when a PR adds a user-visible string without translating it into
every locale the app ships.

Modeled on cuplivo's `check_no_new_untranslated.py` (their PR gate rejects
any new arb key lacking translations). Adapted to Android `strings.xml`:

  * Baseline keys are those present in `res/values/strings.xml` at the PR
    base commit — the repo already carries historical untranslated entries
    and grandfathering them keeps the gate adoptable without a one-time
    translation sweep.
  * A key counts as "added by this PR" when a `<string name="K"` /
    `<plurals name="K"` / `<string-array name="K"` line appears in the
    unified diff of the DEFAULT strings.xml between base and head.
  * Every key added that way must exist in every `res/values-*/strings.xml`.
    `values-night` and other qualifier-only dirs without a strings.xml are
    skipped automatically.

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


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    base, head = sys.argv[1], sys.argv[2]
    res_dir = Path(sys.argv[3]) if len(sys.argv) > 3 else Path(
        "src/android/app/src/main/res"
    )
    default_rel = str(res_dir / "values" / "strings.xml")

    # Well-formedness of every strings.xml at HEAD (a broken locale file
    # otherwise only fails at release-assemble time).
    bad_xml: list[str] = []
    for strings in sorted(res_dir.glob("values*/strings.xml")):
        try:
            ET.parse(strings)
        except ET.ParseError as e:
            bad_xml.append(f"{strings}: {e}")
    if bad_xml:
        print("Malformed strings.xml at HEAD:")
        print("\n".join(bad_xml))
        return 1

    added = git_diff_added_names(base, head, default_rel)
    if not added:
        print("No new translatable keys added by this change.")
        return 0

    # Guard: a renamed/removed default key must not make an existing locale
    # file look "complete" — keys reported below are relative to HEAD's
    # default set, so intersect with what HEAD actually defines.
    head_keys = keys_in(git_show(head, default_rel))
    added &= head_keys
    if not added:
        print("New keys were removed again before HEAD; nothing to check.")
        return 0

    failures: list[str] = []
    for locale_dir in sorted(res_dir.glob("values-*")):
        strings = locale_dir / "strings.xml"
        if not strings.is_file():
            continue
        locale_keys = keys_in(strings.read_text(encoding="utf-8"))
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
            "\nAdd the keys to every res/values-*/strings.xml, or mark the "
            "string translatable=\"false\" if it is not user-visible."
        )
        return 1

    print(f"All {len(added)} new key(s) translated in every locale.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
