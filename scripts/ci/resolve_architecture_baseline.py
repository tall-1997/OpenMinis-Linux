#!/usr/bin/env python3
"""自动解 `architecture-policy.json` 的合并冲突 —— 基线取更紧的值。

## 为什么

两天里这个文件冲突了 **6 次**（`git log --merges` 统计），每次手工动作完全相同：
逐行比两个数字，取小的那个。棘轮语义（上限只降不升）决定这个动作**永远正确**，
所以可以脚本化，而不是每次都靠人看图。

同类问题的规模（同期冲突次数）：
    architecture-policy.json  6
    values/strings.xml        5   （规则是并集，另有工具处理）
    token_data.json           5   （已移出版本库，见 70a7f18f）
    MinisTokens.kt            4   （生成物，必须进库）

## 规则

| 冲突块内容 | 处理 |
|---|---|
| `"path": N` 键值行 | 同名键取 **min**（更紧的上限） |
| 只有一侧出现的键 | 直接保留（新增条目不该被丢掉） |
| 纯文本行（`comment` 数组） | 并集去重，保留 HEAD 顺序 |
| 结构化行（`{` `}` `[` `]`） | 原样保留，去重 |

无法归类时**保留冲突标记并以非零码退出** —— 宁可让人看一眼，也不要猜。

## 用法

    python3 scripts/ci/resolve_architecture_baseline.py          # 就地修复
    python3 scripts/ci/resolve_architecture_baseline.py --check  # 只检查，不改文件

退出码：0 = 无冲突或已解决；1 = 仍有无法自动判定的块；2 = 文件不存在。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "scripts/ci/architecture-policy.json"

BLOCK = re.compile(
    r"^<<<<<<< [^\n]*\n(.*?)^=======\n(.*?)^>>>>>>> [^\n]*\n",
    re.M | re.S,
)
KV = re.compile(r'^\s*"([^"]+)"\s*:\s*(-?\d+)\s*,?\s*$')
TXT = re.compile(r'^\s*"(?:[^"\\]|\\.)*"\s*,?\s*$')
STRUCT = re.compile(r'^\s*[\[\]{}],?\s*$')


def _classify(lines: list[str]) -> str:
    """一整块属于哪类：kv / text / struct / mixed。"""
    kinds = set()
    for ln in lines:
        if not ln.strip():
            continue
        if KV.match(ln):
            kinds.add("kv")
        elif TXT.match(ln):
            kinds.add("text")
        elif STRUCT.match(ln):
            kinds.add("struct")
        else:
            kinds.add("other")
    if not kinds:
        return "empty"
    return kinds.pop() if len(kinds) == 1 else "mixed"


def _merge_block(head: str, theirs: str) -> tuple[str, bool]:
    """返回 (合并后的文本, 是否可自动判定)。"""
    hl = head.rstrip("\n").split("\n") if head.strip() else []
    tl = theirs.rstrip("\n").split("\n") if theirs.strip() else []
    kh, kt = _classify(hl), _classify(tl)

    if kh == "kv" and kt == "kv":
        hmap = {m.group(1): int(m.group(2)) for ln in hl if (m := KV.match(ln))}
        tmap = {m.group(1): int(m.group(2)) for ln in tl if (m := KV.match(ln))}
        merged = {}
        for k in list(hmap) + [k for k in tmap if k not in hmap]:
            if k in hmap and k in tmap:
                merged[k] = min(hmap[k], tmap[k])   # 棘轮：只降不升
            else:
                merged[k] = hmap.get(k, tmap.get(k))
        # 保持 HEAD 的键顺序，新键按他们侧顺序追加
        order = [k for k in hmap] + [k for k in tmap if k not in hmap]
        out = [f'    "{k}": {merged[k]},' for k in order]
        # 最后一行去掉尾逗号（由调用方保证 JSON 合法，这里保守保留）
        return "\n".join(out) + "\n", True

    if kh in ("text", "struct", "empty") and kt in ("text", "struct", "empty"):
        seen, out = set(), []
        for ln in hl + tl:
            s = ln.rstrip()
            if not s.strip() or s in seen:
                continue
            seen.add(s)
            out.append(s)
        return "\n".join(out) + "\n", True

    return f"<<<<<<< unresolved\n{head}=======\n{theirs}>>>>>>> unresolved\n", False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只检查，不写盘")
    ap.add_argument("--path", default=str(TARGET))
    a = ap.parse_args()

    p = Path(a.path)
    if not p.is_file():
        print(f"resolve-arch: SKIP (not found: {p})")
        return 2

    text = p.read_text(encoding="utf-8")
    if "<<<<<<<" not in text:
        print("resolve-arch: 无冲突")
        return 0

    unresolved = []
    n_blocks = 0

    def repl(m: re.Match) -> str:
        nonlocal n_blocks
        n_blocks += 1
        merged, ok = _merge_block(m.group(1), m.group(2))
        if not ok:
            unresolved.append(n_blocks)
        return merged

    out = BLOCK.sub(repl, text)

    if unresolved:
        print(f"resolve-arch: {n_blocks} 个冲突块，其中 {len(unresolved)} 个无法自动判定"
              f"（块号 {unresolved}）—— 保留标记，请人工处理")
        if not a.check:
            p.write_text(out, encoding="utf-8")
        return 1

    # 合并结果必须是合法 JSON，否则不落盘
    try:
        json.loads(out)
    except json.JSONDecodeError as e:
        print(f"resolve-arch: 合并后不是合法 JSON（{e}）—— 未写盘，请人工检查")
        return 1

    if not a.check:
        p.write_text(out, encoding="utf-8")
    print(f"resolve-arch: 已合并 {n_blocks} 个冲突块（基线取更紧值、其余取并集）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
