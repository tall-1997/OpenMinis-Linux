#!/usr/bin/env python3
"""自动解 `values*/strings.xml` 的合并冲突 —— 键级并集，同键不同值必须人工。

## 为什么

两天里 `values/strings.xml` 冲突 **5 次**（同期第二高，仅次于
`architecture-policy.json` 的 6 次 —— 那个已被
`resolve_architecture_baseline.py` 接管）。17 个语言包 + 2922 个键，
每加一条文案都要在 18 个文件里落一次，两边同时加键是常态。

## 规则

冲突块被解析成 `name → 文本` 的映射后：

| 情形 | 处理 |
|---|---|
| 只有一侧有该键 | 取有的一侧（各自新增的并集） |
| 两侧同键、**值相同** | 取一个 |
| 两侧同键、**值不同** | **保留冲突标记，rc=1** —— 这是语义分歧，机器不该猜 |

顺序按 HEAD 侧原有键序，新键追加在末尾，保证 diff 稳定。

## 边界（不做的事）

- 只处理 `<string>`；`plurals` / `string-array` / `item` 等一律保留冲突标记
- 不校验翻译完整性：`check_new_untranslated` 和 `check_string_refs` 会在
  `verify_all` 里接着检查，这里只负责把 XML 合到能解析

## 用法

    python3 scripts/ci/resolve_xml_strings.py                # 全部 values*/strings.xml
    python3 scripts/ci/resolve_xml_strings.py --check        # 只报告，不写盘
    python3 scripts/ci/resolve_xml_strings.py --path X.xml   # 指定文件

退出码：0 = 已解决或无需处理；1 = 有同键不同值（需人工）；2 = 无冲突。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "src/android/app/src/main/res"

BLOCK = re.compile(
    r"^<<<<<<< [^\n]*\n(.*?)^=======\n(.*?)^>>>>>>> [^\n]*\n",
    re.M | re.S,
)
STRING = re.compile(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', re.S)


def parse_block(text: str) -> tuple[dict[str, str], list[str]]:
    """返回 (name -> 原文, 非 string 行)。"""
    items: dict[str, str] = {}
    leftovers: list[str] = []
    pos = 0
    for m in STRING.finditer(text):
        items[m.group(1)] = m.group(2)
        pos = m.end()
    # 收集未被 <string> 覆盖的非空行（plurals / 注释等）
    covered = ["".join(m.group(0)) for m in STRING.finditer(text)]
    rest = STRING.sub("", text)
    for ln in rest.split("\n"):
        if ln.strip():
            leftovers.append(ln.rstrip())
    return items, leftovers


def render(items: dict[str, str], order: list[str]) -> str:
    out = []
    for name in order:
        out.append(f'    <string name="{name}">{items[name]}</string>')
    return "\n".join(out) + "\n"


def resolve_file(path: Path, check_only: bool) -> int:
    text = path.read_text(encoding="utf-8")
    if "<<<<<<<" not in text:
        return 2

    ambiguous: list[str] = []
    blocks = 0

    def repl(m: re.Match) -> str:
        nonlocal blocks
        blocks += 1
        head, theirs = m.group(1), m.group(2)
        hi, hl = parse_block(head)
        ti, tl = parse_block(theirs)

        # 非 string 内容（plurals / 注释）无法安全合并 → 整个块保留冲突
        if hl or tl:
            ambiguous.append(f"block#{blocks} 含非 <string> 内容")
            return m.group(0)

        merged = dict(hi)
        for k, v in ti.items():
            if k in merged:
                if merged[k].strip() != v.strip():
                    ambiguous.append(f"{k}（两侧值不同）")
            else:
                merged[k] = v

        order = list(hi) + [k for k in ti if k not in hi]
        return render(merged, order)

    out = BLOCK.sub(repl, text)

    if ambiguous:
        print(f"  {path.name}: {blocks} 块，其中 {len(ambiguous)} 处需人工")
        for a in ambiguous[:8]:
            print(f"    - {a}")
        if len(ambiguous) > 8:
            print(f"    … 另 {len(ambiguous) - 8} 处")
        # 有歧义就**完全不写盘**：写一半（同键取了 HEAD 的值）比不写更危险，
        # 人会以为已合好，而另一侧的文案是静默丢的。
        print(f"    → {path.name} 未改动，请人工判定上述键后再跑")
        return 1

    if not check_only:
        path.write_text(out, encoding="utf-8")
    print(f"  {path.name}: 已合并 {blocks} 块（键级并集）")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--path", help="指定单个 xml")
    a = ap.parse_args()

    if a.path:
        targets = [Path(a.path)]
    else:
        targets = sorted(RES.glob("values*/strings.xml"))

    if not targets:
        print("resolve-xml: SKIP (no strings.xml found)")
        return 2

    hit = 0
    worst = 0
    for p in targets:
        rc = resolve_file(p, a.check)
        if rc != 2:
            hit += 1
        worst = max(worst, 1 if rc == 1 else 0)

    if hit == 0:
        print(f"resolve-xml: {len(targets)} 个文件均无冲突")
        return 2

    print(f"resolve-xml: {hit}/{len(targets)} 个文件处理过"
          + ("" if worst == 0 else "，其中有需人工判定的同键不同值"))
    return worst


if __name__ == "__main__":
    sys.exit(main())
