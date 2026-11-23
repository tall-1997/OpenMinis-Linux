# scripts/lemon

小柠檬工具目录的生成链。**运行时只读 `assets/lemon/catalog.json`**，这里是它怎么来的。

| 文件 | 职责 | 谁用 |
|---|---|---|
| `groups.json` | 11 个域的前缀规则（exact + 最长前缀优先） | `grouper.py`、`gen_catalog.py` |
| `risk.json` | 358 个工具的风险等级（low 219 / medium 96 / high 43） | `gen_catalog.py` |
| `grouper.py` | 读 `groups.json` 判域；`--report` / `--emit-tsv` / `--classify` | `gen_catalog.py` |
| `gen_risk.py` | 从每个插件的 `cmd` 推导风险 → `risk.json`（含 13 条人工 `OVERRIDES`） | 手动跑 |
| `gen_catalog.py` | 合并插件壳 + 分组 + 风险 → `assets/lemon/catalog.json` | 手动跑 |

## 重生成

```sh
python3 scripts/lemon/gen_risk.py --help      # 改风险规则后重出 risk.json
python3 scripts/lemon/gen_catalog.py --plugins <lemon-kernel-opensource/06-plugins>
```

`--plugins` 是唯一必填参数，其余走仓库内默认值。

## 为什么这里没有 `guard.py` / `policy.json`

这两份文件曾在独立面板时期存在，做的是**同一个三档权限的 Python 参考实现**
（`declareRisk` 矩阵 + `delegated` 委派表 + 名字关键词升级）。搬进 App 后它们
零消费者，而且会与真实现分叉 —— 已删除，理由逐条：

1. **三档判定**已由 `com.openminis.app.security.SecurityGate` 承担，且比那份
   Python 更完整（只读放行、协调工具放行、`ASK_ALL` 严格档、致命命令拦截）。
   两份并存只会让"该信哪份"变成日常问题。
2. **`delegated` 委派表**（改设置→设置页、删会话→会话菜单）在 App 内**不适用**：
   那一层是给「独立面板同时服务人和 agent」设计的 —— 人点 UI 时有意义。
   App 内只有 agent 通过 MCP 调用，把 `settings put` 指回设置页对面没有操作
   主体。所以 App 内不实现委派，不是漏了。
3. **名字关键词升 high** 已经固化进 `risk.json`（`gen_risk.py` 生成阶段做），
   运行时不需要再判一次 —— 再判会把人工作过的 `OVERRIDES` 覆盖掉
   （踩过：`rfkill_list` 判 low 后被 `kill` 关键词抬回 high）。

判定链只有一条：`lemon_call` → `McpToolShared.gateAndRun` → `SecurityGate`。
