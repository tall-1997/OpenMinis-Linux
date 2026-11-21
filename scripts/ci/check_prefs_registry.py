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


def main() -> int:
    if not GEN.exists():
        print(f"prefs registry check: SKIP (generator not found at {GEN})")
        return 0
    if not SRC.exists():
        print(f"prefs registry check: SKIP (source root not found: {SRC})")
        return 0

    proc = subprocess.run(
        [sys.executable, str(GEN), "--src", str(SRC),
         "--out", str(SRC / "config"),
         "--inventory-out", str(ROOT / "docs/prefs"),
         "--check"],
        capture_output=True,
        text=True,
    )
    output = (proc.stdout + proc.stderr).strip()

    if proc.returncode == 0:
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
