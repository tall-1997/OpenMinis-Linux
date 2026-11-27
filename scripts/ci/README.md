# scripts/ci

两类脚本：**守卫**（每次门禁都跑，失败即红）和**合并辅助**（解冲突时手动跑）。

## 守卫

入口是 `verify_all.py`，CI 的 `All static gates (single entry)` 一步调用它。
新增守卫只需在 `GATES` 里加一行 `Gate("name", _py("check_xxx.py"))`。

`check_feature_anchors.py` 特殊一点：它比对的不是代码正确性，而是
`feature-anchors.json` 里人工认定的「关键入口最少出现次数」。
用来补「代码能编译但整块功能丢失」这一类合并事故 —— 详见该文件的 docstring。

## 合并辅助

这两个不进门禁（没冲突时它们什么都不做，跑一次只是浪费 20 秒），
解冲突时手动调用。

### `resolve_architecture_baseline.py`

`architecture-policy.json` 是行数上限基线，两天里冲突 **6 次**（最多）。
每次手工动作完全相同：逐行比两个数字取小的。棘轮语义（只降不升）决定
这个动作永远正确，所以脚本化了。

```sh
python3 scripts/ci/resolve_architecture_baseline.py          # 就地解
python3 scripts/ci/resolve_architecture_baseline.py --check  # 只看判定
```

- `"path": N` 键值行 → 取 **min**
- 只有一侧的键 → 保留
- 纯文本行（comment 数组）→ 并集去重
- 结构行 `{ } [ ]` → 去重保留
- **无法归类 → 保留冲突标记 + rc=1**（宁可让人看一眼，也不猜）
- 合并结果必须通过 `json.loads` 才落盘

### `resolve_xml_strings.py`

`values*/strings.xml` 冲突 **5 次**（第二多）。17 个语言包 × 2922 个键，
两边同时加键是常态。

```sh
python3 scripts/ci/resolve_xml_strings.py                    # 全部 values*/strings.xml
python3 scripts/ci/resolve_xml_strings.py --check            # 只报告
python3 scripts/ci/resolve_xml_strings.py --path X.xml       # 单个文件
```

- 只有一侧有该键 → 取有的一侧（并集）
- 两侧同键且值相同 → 取一个
- **两侧同键值不同 → 整个文件不写盘 + rc=1**（语义分歧机器不该猜；
  写一半比不写更危险，人会以为合好了，另一侧文案是静默丢的）
- 只处理 `<string>`；`plurals` / `string-array` 出现即视为不可自动合并

并集后翻译完整性由 `check_new_untranslated` 和 `check_string_refs`
在门禁里接着校验 —— 这个脚本只负责把 XML 合到能解析。

## 不能自动化的

`MinisTokens.kt` 冲突过 4 次，处理动作是「取一侧 + 重跑 `gen_tokens.py`」，
有确定性但依赖生成器——`check_ui_tokens` 会在门禁里拦住忘记重生成的情况，
所以不另写脚本，避免第三份「怎么生成」的实现。

`ChatViewModel.kt` 冲突 2 次：上游高频改的大文件，冲突都是真实语义分歧，无解。
