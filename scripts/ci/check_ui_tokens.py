#!/usr/bin/env python3
"""CI 守卫：设计令牌生成物是否与 ui/ 源码一致，且引用都能解析。

`ui/tokens/MinisTokens.kt` 是 `scripts/ui/gen_tokens.py` 从 ui/ 下所有
`Color(0x...)` 字面量聚合出来的生成物。它一旦与源码脱节，会出现两种静默失败：

  1. 代码里用了新色值，令牌表里没有 —— 编译期报 unresolved reference，
     但报错点在调用处，看不出是生成物过期
  2. 令牌表里有源码已不再使用的值 —— 没人发现，表慢慢腐化

所以这里做两件事：重新生成到临时目录比对，以及校验全部 `RawColors.*` 引用可解析。

用法：
    python3 scripts/ci/check_ui_tokens.py
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "src/android/app/src/main/java/com/openminis/app/ui"
TOKENS = UI / "tokens/MinisTokens.kt"
GEN = Path(__file__).resolve().parents[1] / "ui" / "gen_tokens.py"
REF = Path(__file__).resolve().parents[1] / "ui" / "check_token_refs.py"


def main() -> int:
    for p, label in ((GEN, "gen_tokens.py"), (REF, "check_token_refs.py"), (UI, "ui/")):
        if not p.exists():
            print(f"ui tokens check: SKIP ({label} not found)")
            return 0

    tmp = Path(tempfile.mkdtemp(prefix="tokcheck-"))
    try:
        proc = subprocess.run(
            [sys.executable, str(GEN), "--ui", str(UI), "--out", str(tmp)],
            capture_output=True, text=True,
        )
        if proc.returncode != 0:
            print("ui tokens check: FAILED (generator error)")
            print((proc.stdout + proc.stderr).strip())
            return 1

        fresh = tmp / "MinisTokens.kt"
        if not fresh.exists():
            print("ui tokens check: FAILED (generator produced no MinisTokens.kt)")
            return 1

        if fresh.read_text(encoding="utf-8") != TOKENS.read_text(encoding="utf-8"):
            print("ui tokens check: FAILED (MinisTokens.kt is stale)")
            print()
            print("Regenerate and commit:")
            print(f"  python3 scripts/ui/gen_tokens.py --ui {UI.relative_to(ROOT)} "
                  f"--out {(UI / 'tokens').relative_to(ROOT)}")
            diff = subprocess.run(
                ["diff", "-u", str(TOKENS), str(fresh)],
                capture_output=True, text=True,
            )
            print(diff.stdout[:3000])
            return 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    ref = subprocess.run(
        [sys.executable, str(REF), str(UI), str(TOKENS)],
        capture_output=True, text=True,
    )
    out = (ref.stdout + ref.stderr).strip()
    if ref.returncode != 0:
        print("ui tokens check: FAILED (unresolved RawColors references)")
        print(out)
        return 1

    print("ui tokens check: OK (tokens regenerable, all references resolve)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
