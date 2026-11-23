#!/usr/bin/env python3
"""Every gitlink in the index must have a .gitmodules mapping.

[T-submodule-mapping] PR #410 dropped the ``deps/ish`` entry from
``.gitmodules`` while the tree still carried the gitlink. Git then fails on
innocent commands for every checkout:

 $ git submodule status
 fatal: no submodule mapping found in .gitmodules for path 'deps/ish'
 $ git submodule update --init
 fatal: No url found for submodule path 'deps/ish' in .gitmodules

``git clone --recurse-submodules`` breaks the same way, so a new contributor
cannot follow BUILDING.md. Targeted ``git submodule update --init deps/proot``
keeps working, which is why CI stayed green and nobody noticed.

Fail-closed: a gitlink without a mapping is an error. A mapping without a
gitlink is only a note (a retired submodule may keep its registration).
"""

import configparser
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GITMODULES = ROOT / ".gitmodules"


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", check=True
    ).stdout


def gitlinks() -> list[str]:
    out = git("ls-files", "-s")
    paths = []
    for line in out.splitlines():
        meta, _, path = line.partition("\t")
        if meta.startswith("160000"):
            paths.append(path.strip())
    return sorted(paths)


def registered() -> dict[str, str]:
    if not GITMODULES.is_file():
        return {}
    parser = configparser.ConfigParser()
    parser.read(GITMODULES, encoding="utf-8")
    mapping: dict[str, str] = {}
    for section in parser.sections():
        if not section.startswith('submodule'):
            continue
        path = parser.get(section, "path", fallback="").strip()
        url = parser.get(section, "url", fallback="").strip()
        if path:
            mapping[path] = url
    return mapping


def main() -> int:
    links = gitlinks()
    mapping = registered()

    missing = [p for p in links if p not in mapping]
    empty_url = [p for p in links if p in mapping and not mapping[p].strip()]

    for p in missing:
        print(f" {p}: gitlink in the index but no [submodule] entry in .gitmodules")
    for p in empty_url:
        print(f" {p}: registered but the url is empty")

    orphans = sorted(set(mapping) - set(links))
    for p in orphans:
        print(f" note: {p}: registered in .gitmodules but not a gitlink in the index")

    if missing or empty_url:
        print()
        print("submodule mapping: FAILED")
        print("修法：给每个 gitlink补一条 [submodule]段（path + url），或")
        print(" git rm --cached <path>把不再使用的子模块从索引里去掉。")
        return 1

    print(
        f"submodule mapping: OK（{len(links)}个 gitlink，{len(mapping)}条注册，"
        f"{len(orphans)}条退役注册）"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
