#!/usr/bin/env python3
"""CI 守卫：生成的 PrefsRegistry.kt 是否与源码一致。

为什么需要单独一条 CI：
  `config/PrefsRegistry.kt` 是生成物，但它决定 `PrefsHandle` 的键名守卫。
  如果有人改了 prefs 相关源码却没重跑生成器，注册表会与代码脱节 ——
  后果是守卫要么拒绝活代码（崩溃），要么放过不该放过的键（校验形同虚设）。
  两者都不会在编译期暴露，只会在运行期以奇怪的方式失败。

用法：
    python3 scripts/ci/check_prefs_registry.py
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src/android/app/src/main/java/com/openminis/app"
GEN = Path(__file__).resolve().parents[1] / "prefs" / "gen_prefs_registry.py"


def unexplained_orphans() -> list:
    """[T-prefs-orphan-key] Orphan keys that can ACTUALLY throw at runtime.

    `orphan_keys` is the generator's list of `const val` declarations it could
    not attribute to a file. Failing on that whole list would be noise: most
    entries are not prefs keys at all (`API_KEY`, `HEADER_KEY`, `KEYS_URL`,
    `KEY_ENDPOINT` are header/file/field names). A noisy gate gets switched off.

    The keys that can really throw are the ones whose VALUE is passed to a
    prefs read/write on a declared `PrefsHandle`. So intersect:

        orphan constants  ∩  constants used as a prefs key  −  already explained

    "Explained" is judged on the VALUE, not the constant name: a key registered
    in prefs_extra_keys.json (or in any generated spec) is fine no matter what
    the constant is called. That is what made the old name-based comparison
    report `lastUsedModelEntryId` even though it is registered.
    """
    import json
    import re

    inventory = ROOT / "docs/prefs/prefs-inventory.json"
    if not inventory.is_file():
        return []
    try:
        data = json.loads(inventory.read_text(encoding="utf-8"))
    except Exception:
        return []
    orphans = set(data.get("orphan_keys") or [])
    if not orphans:
        return []

    # Values already registered anywhere (generated registry or the extra list).
    registered_values = set()
    prefs_files = data.get("prefs_files") or []
    if isinstance(prefs_files, dict):
        prefs_files = list(prefs_files.values())
    for spec in prefs_files:
        registered_values.update((spec.get("keys") or {}).values())
    extra = Path(__file__).resolve().parents[1] / "prefs" / "prefs_extra_keys.json"
    if extra.is_file():
        try:
            extra_data = json.loads(extra.read_text(encoding="utf-8"))
        except Exception:
            extra_data = {}
        for spec in (extra_data.get("files") or {}).values():
            registered_values.update((spec.get("keys") or {}).values())

    methods = (r"(?:string|stringOrNull|bool|int|long|float|stringSet|set"
               r"|remove|contains|observe)")
    re_decl = re.compile(
        r"\b([A-Za-z_][A-Za-z0-9_]*)\s*:\s*"
        r"(?:com\.openminis\.app\.config\.)?\s*PrefsHandle\b")
    re_const = re.compile(r"const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*\"([^\"]*)\"")
    # The key argument is either a bare identifier or a qualified one
    # (`Pkg.KEY`); capture the FINAL segment only, and anchor with a
    # non-identifier boundary so a plain identifier cannot backtrack into a
    # truncated prefix.
    re_call = re.compile(
        r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\??\.\s*" + methods +
        r"\s*\(\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*([A-Za-z_][A-Za-z0-9_]*)(?![A-Za-z0-9_])")

    bad = []
    for path in SRC.rglob("*.kt"):
        try:
            src = path.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        handles = set(re_decl.findall(src))
        if not handles:
            continue
        consts = dict(re_const.findall(src))
        for recv, arg in re_call.findall(src):
            if recv not in handles:
                continue
            value = consts.get(arg)
            if not value:
                continue
            if value in registered_values:
                continue
            if arg in orphans:
                bad.append(arg)
    return sorted(set(bad))


def main() -> int:
    if not GEN.exists():
        print(f"prefs registry check: FAIL (generator not found at {GEN})")
        return 1
    if not SRC.exists():
        print(f"prefs registry check: FAIL (source root not found: {SRC})")
        return 1

    proc = subprocess.run(
        [sys.executable, str(GEN), "--src", str(SRC),
         "--out", str(SRC / "config"),
         "--inventory-out", str(ROOT / "docs/prefs"),
         "--check"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    output = (proc.stdout + proc.stderr).strip()

    if proc.returncode == 0:
        orphans = unexplained_orphans()
        if orphans:
            print(f"prefs registry check: FAILED ({len(orphans)} unexplained orphan key(s))")
            for name in orphans[:20]:
                print(f"  {name}: 生成器看见了，但无法归属到任何 prefs 文件，"
                      f"也没在 prefs_extra_keys.json 里解释")
            print()
            print("两种修法（都要在同一个 PR 里）：")
            print("  1. 把键改成 `const val` 并放进真正使用它的文件（文件里要有")
            print("     Prefs.file(...) 或文件名常量），生成器就能归属；")
            print("  2. 该文件的 handle 是构造器注入的话，登记进")
            print("     scripts/prefs/prefs_extra_keys.json 并在 reason 里写明为什么。")
            return 1
        print("prefs registry check: OK (register matches source)")
        return 0

    print("prefs registry check: FAILED")
    print(output)
    print()
    print("Regenerate and commit:")
    print(f"  python3 {GEN.relative_to(ROOT)} --src {SRC.relative_to(ROOT)} "
          f"--out {(SRC / 'config').relative_to(ROOT)} "
          f"--inventory-out docs/prefs")
    return 1


if __name__ == "__main__":
    sys.exit(main())
