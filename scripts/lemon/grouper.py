#!/usr/bin/env python3
"""工具分组解析器 —— 从 groups.json 读规则，不再硬编码在 Python 里。

用途：
  1. 作为库：classify(name) -> (tag, label)，供面板与门面共用同一份规则
  2. 命令行：--emit-tsv 重写工具索引.tsv 的「域」列；--report 列出仍未分组的工具

为什么外置：
  原来 slim_proxy_v154.py 里有一张 DOMAINS 常量表（前缀元组）。新增插件若不带
  cat 字段，classify 落回 "D9 冷库"，面板再把它算成「其他」——这个桶会随插件
  增长单调变大。规则落到 JSON 后，补一条前缀就行，不需要改代码、不需要重启门面。
"""
from __future__ import annotations

import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_RULES = os.path.join(HERE, "groups.json")


def load_rules(path: str | None = None) -> dict:
    with open(path or DEFAULT_RULES, encoding="utf-8") as fh:
        rules = json.load(fh)
    # 预排序：长前缀优先，避免 "sys_" 抢走 "sys_fs_" 这类更具体的匹配
    for dom in rules.get("domains", []):
        dom["prefixes"] = sorted(dom.get("prefixes", []), key=len, reverse=True)
    rules["_exact"] = {n: d["tag"] for d in rules.get("domains", []) for n in d.get("exact", [])}
    rules["_labels"] = {d["tag"]: d["label"] for d in rules.get("domains", [])}
    rules["_fallback"] = rules.get("fallback") or {"tag": "D9", "label": "未分组"}
    rules["_labels"][rules["_fallback"]["tag"]] = rules["_fallback"]["label"]
    return rules


def classify(name: str, rules: dict | None = None) -> tuple[str, str]:
    """工具名 → (域标号, 域中文名)。精确名优先，其次最长前缀。"""
    r = rules or load_rules()
    low = (name or "").lower()

    over = (r.get("overrides") or {}).get(name)
    if over:
        return over, r["_labels"].get(over, over)
    if name in r["_exact"]:
        tag = r["_exact"][name]
        return tag, r["_labels"].get(tag, tag)

    for dom in r.get("domains", []):
        for p in dom["prefixes"]:
            if low.startswith(p):
                return dom["tag"], dom["label"]
    return r["_fallback"]["tag"], r["_fallback"]["label"]


def domain_field(name: str, rules: dict | None = None) -> str:
    """工具索引.tsv 第三列的形状：`D1-系统`。"""
    tag, label = classify(name, rules)
    return f"{tag}-{label}"


def _emit_tsv(tsv_path: str, rules: dict) -> int:
    """重写索引文件的域列，保留编号/工具名/一句话。"""
    rows = []
    with open(tsv_path, encoding="utf-8") as fh:
        head = fh.readline()
        for line in fh:
            p = line.rstrip("\n").split("\t")
            if len(p) < 2 or not p[1].strip():
                continue
            while len(p) < 4:
                p.append("")
            p[2] = domain_field(p[1].replace("[MT] ", "").strip(), rules)
            rows.append(p)
    with open(tsv_path, "w", encoding="utf-8") as fh:
        fh.write(head if head.strip() else "编号\t工具名\t域\t一句话\n")
        if not head.strip():
            fh.write("编号\t工具名\t域\t一句话\n")
        for p in rows:
            fh.write("\t".join(p[:4]) + "\n")
    print(f"已重写 {tsv_path}：{len(rows)} 行")
    return 0


def _report(plugin_dir: str, rules: dict) -> int:
    """列出落进 fallback 的工具，便于补前缀。"""
    miss = []
    for fn in sorted(os.listdir(plugin_dir)):
        if not fn.endswith(".json"):
            continue
        try:
            d = json.load(open(os.path.join(plugin_dir, fn), encoding="utf-8"))
        except Exception:
            continue
        name = d.get("name")
        if not name:
            continue
        tag, label = classify(name, rules)
        if tag == rules["_fallback"]["tag"]:
            miss.append(name)
    print(f"插件 {len(os.listdir(plugin_dir))} 个文件，落进「{rules['_fallback']['label']}」{len(miss)} 个")
    for n in miss[:40]:
        print("  ", n)
    if len(miss) > 40:
        print(f"  … 另 {len(miss) - 40} 个")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rules", default=DEFAULT_RULES)
    ap.add_argument("--emit-tsv", metavar="工具索引.tsv",
                    help="按规则重写索引的域列")
    ap.add_argument("--report", metavar="PLUGIN_DIR",
                    help="列出仍未分组的插件")
    ap.add_argument("--classify", metavar="TOOL_NAME", help="打印单个工具的分组")
    a = ap.parse_args()
    rules = load_rules(a.rules)
    if a.classify:
        print(domain_field(a.classify, rules))
        return 0
    if a.emit_tsv:
        return _emit_tsv(a.emit_tsv, rules)
    if a.report:
        return _report(a.report, rules)
    ap.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
