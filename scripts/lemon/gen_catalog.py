#!/usr/bin/env python3
"""生成 App 内嵌的小柠檬工具目录 assets/lemon/catalog.json。

上游 lemon-kernel-opensource 的 358 个工具薄壳是「一条命令 + 参数契约」的 JSON，
本脚本把它们与分组表（groups.json）、风险表（risk.json）合并成一份 App 直接
可读的 catalog，随 APK 打包。

为什么要合并成一份：
  · App 里不该跑 Python 面板，也不该依赖 /data 下的外部目录；
  · 分组与风险必须在打包时定稿，运行时只读 —— 否则真机上又是两套真相；
  · 358 个工具作为 358 个 MCP spec 会撑爆 tools/list，所以只暴露 lemon_list /
    lemon_call 两个入口，目录内容走资产。

用法（在仓库根目录）：
    python3 scripts/lemon/gen_catalog.py \
        --plugins /path/to/lemon-kernel-opensource/06-plugins

    # groups.json / risk.json / 输出路径都有仓库内默认值
"""
from __future__ import annotations

import argparse
import json
import os
import sys


def _load(path: str) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def main() -> int:
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--plugins", required=True,
                    help="upstream 06-plugins dir (lemon-kernel-opensource)")
    ap.add_argument("--groups", default=os.path.join(here, "groups.json"))
    ap.add_argument("--risk", default=os.path.join(here, "risk.json"))
    ap.add_argument("--out", default=os.path.join(
        here, "..", "..",
        "src/android/app/src/main/assets/lemon/catalog.json"))
    a = ap.parse_args()

    sys.path.insert(0, os.path.dirname(os.path.abspath(a.groups)))
    from grouper import load_rules, domain_field  # noqa: E402

    rules = load_rules(a.groups)
    risk = (_load(a.risk) or {}).get("tools") or {}

    tools = []
    for fn in sorted(os.listdir(a.plugins)):
        if not fn.endswith(".json"):
            continue
        try:
            d = _load(os.path.join(a.plugins, fn))
        except Exception:
            continue
        name = d.get("name")
        if not name:
            continue
        rk = risk.get(name) or {}
        tools.append({
            "name": name,
            "desc": (d.get("desc") or "")[:160],
            "cmd": d.get("cmd") or "",
            "params": d.get("params") or "",
            "required": d.get("required") or "",
            "timeoutMs": int(d.get("timeoutMs") or 15000),
            "domain": domain_field(name, rules),
            "risk": rk.get("risk", "medium"),
            "ro": bool(rk.get("readOnlyHint", False)),
        })

    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as fh:
        json.dump({"version": 2, "tools": tools}, fh, ensure_ascii=False, separators=(",", ":"))

    import collections
    dom = collections.Counter(t["domain"] for t in tools)
    rk_cnt = collections.Counter(t["risk"] for t in tools)
    print("catalog: %d 工具  %d KB" % (len(tools), os.path.getsize(a.out) // 1024))
    print("  分组:", dict(dom.most_common()))
    print("  风险:", dict(rk_cnt))
    if dom.get("D9"):
        print("  WARN: %d 个工具落在未分组（补 groups.json 的 prefixes）" % dom["D9"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
