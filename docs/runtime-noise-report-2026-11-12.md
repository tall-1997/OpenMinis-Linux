# 运行过程噪音：根因与成熟项目对照报告

- **日期**：2026-11-12
- **样本**：用户会话 `2e93dfc0`（glm-5.3-flash / NewAPI 代理）+ `minis-2026-11-12.log`（8.4 MB）
- **结论**：噪音是**结构性**的——"每轮一个工具的单步 run 不触发折叠" + "导出无合并/无截断" + "对外置正文的占位符泄漏"。不是 UI 配色或文案问题，靠调阈值/提示词无法解决。

---

## 一、量化证据（会话导出 `messages.md`，149 KB / 3192 块）

| 指标 | 数值 | 含义 |
|---|---|---|
| 工具块 | **1289**（shell_execute **1200**） | 导出 41.0% 的字符是工具流水账 |
| assistant 文本块 | 301 | 每轮旁白（"进度有点拖沓…"）单独成段 |
| **空块** | **1023** | 纯 tool_result 载体，导出渲染为空 `## 用户` 段 |
| `body stored (N bytes)` 块 | **658（占 23.1% 字符）** | 外置正文占位符被当正文导出 |
| `system-reminder` 块 | 3 | 断流续写提醒（DB 保留，导出可见） |
| **每轮工具数** | **恒为 1（1289 轮 × 1）** | 串行单工具循环 ⇒ 轮次爆炸 |
| 行数/字节 | 3191 条消息、20 word avg | 长会话被过程噪音撑爆 |

日志侧：每轮 `runAgentLoop turn=N dispatching 1 tool call(s)`（214 条），同一命令 `sha` 在 6 秒内被重放 2–3 次；`finish_reason=length reasoningLen=94525 contentLen=0`；`auto-continuation 1/3`。

---

## 二、成熟项目怎么做（重点）

### 1. progress-first + 折叠（codex / opencode）

- codex 的 TUI 诉求是 "progress-first TUI with **collapsible** tool output"，工具输出默认**折叠**、点击展开（[codex#41601](https://github.com/openai/codex/issues/41601)）；同类的 "hide or collapse code snippets in progress output"（[codex#32817](https://github.com/openai/codex/issues/32817)）。
- opencode 有专门的 "compact tool output with **click-to-expand**"（[PR#7260](https://github.com/anomalyco/opencode/pull/7260)）、"collapse execute child details"（[PR#41624](https://github.com/anomalyco/opencode/pull/41624)）、"choose how much tool output shows before Click to expand"（[PR#54039](https://github.com/anomalyco/opencode/pull/54039)）。
- **要点**：工具输出**永远默认折叠**，且一个"步骤组"只占一行；用户需要时才展开。

### 2. reasoning 合并成一个折叠块（opencode）

- "collapse **fragmented reasoning parts** and strip thinking echo"（[PR#32152](https://github.com/anomalyco/opencode/pull/32152)）：provider 把思考拆成碎片（我们的 `reasoningLen=94525` 就是碎片累加），成熟做法是**合并成一个 Thinking 折叠块**并把模型回显（thinking echo）剥离，而不是逐碎片渲染。

### 3. 状态行/页脚承载过程信息（codex）

- codex 把会话级过程状态放在 **status line + footer**（[DeepWiki: Status Line and Footer Rendering](https://deepwiki.com/openai/codex/4.1.4-status-line-and-footer-rendering)），对话流里只留结果。
- 我们的对应物是 `SessionActivityTracker`（"Running: shell_execute"）+ overlay——但**没有把"断流/续写/空回合/压缩"这些系统事件收敛到状态行**，它们以 inline error / system-reminder / 额外卡片形式进入对话流（日志里 3 次 resume reminder，UI 上 3 次断流提示）。

### 4. quiet / final-answer-only 模式（codex）

- codex 有明确的 "final-answer-only / **quiet transcript** mode"（[codex#36845](https://github.com/openai/codex/issues/36845)）：导出/转录只保留最终回答与关键错误，过程不进入转录。
- 我们的导出是"全量转录"：1289 个工具行 + 1023 个空块 + 658 个占位符——**这是用户直接看到的"噪音文件"**。

### 5. 并行工具调用（codex / opencode 的 agent loop）

- 成熟编码 agent 的模型一次响应会带**多个独立 tool_calls**，并行执行后回灌（我们 wave5 的 `[T-parallel-tool-calls]` 调度器已具备能力，日志也出现过 `accumulators=2`）。
- 我们样本里 **1289 轮 × 恰好 1 个工具**：模型每轮只发一个，于是"每步 = 一次完整请求"——轮次放大 1289 倍，既是速度问题，也是噪音来源。

---

## 三、我们已有的机制（不是从零开始）

`ChatFlatItems.kt` 的 `ChatRound` 折叠已经相当成熟：

- 一个 round 一个 fold bar（"one round, one bar, round-wide totals"）；
- **RUNNING 的 round 永远展开**，完成后自动折叠，保留最新 2 步（`LATEST 2 steps` 的"刚才在干什么"tail）；
- **≥2 步的 process RUN 折叠为 summary bar**（"a bar summarising two rows is noise"）；
- 流式更新走 `UiUpdateBatcher`（200ms 批次）；
- 断流续写提醒以 `null` UI id 持久化（`commitStreamDropReminder`），不渲染为用户气泡。

**所以 UI 层设计方向是对的**——缺口在于：

| 缺口 | 证据 | 影响 |
|---|---|---|
| **1 步的 run 不折叠** | `SEGMENT_MIN_STEPS` = 2；样本每轮恰好 1 步 | 1289 个单步卡片全部可见 = "运行过程全是工具卡片" |
| **导出无合并/无截断/无跳过** | `ChatExporter` 逐消息写 `## role`；空 tool_result 写空段 | 导出 = 工具流水账 |
| **外置正文占位符泄漏到导出** | 已修（`fullBodyForCopy`），但**修复后**导出体积会暴涨 | 需配套截断 |
| **reasoning 碎片直接渲染/导出** | `reasoningLen=94525` 由多段 reasoning_content 累加 | 超长思考占满视口 |
| **系统事件进对话流** | inline error banner、`system-reminder` 行 | 断流/续写/空回合在界面上"喊话" |

---

## 四、修复方案（结构性，按优先级）

### P0 —— 导出降噪（用户直接可见的"噪音文件"）

1. **跳过空载体**：`role=user` 且无文本、无媒体、无工具结果文本的纯 tool_result 行不输出 `## 用户` 段（1023 行噪音 → 0）。
2. **合并连续工具-only 段**：相邻且只有工具调用的 assistant 消息合并为一个块：`> **工具** shell_execute ×N`（1200 行 → 少量合并行）。
3. **单块截断**：外置正文解析后单块最多保留 N 字符（默认 2000）+ "…（已截断，完整内容见会话）"，避免修复 stub 后体积爆炸。
4. **系统提醒不导出**：`<system-reminder>` 断流续写提醒不写进 markdown（DB 保留，供 resume 用）。

### P1 —— 运行中 UI 降噪

5. **单步 run 合并**：把相邻的 1 步 process run 并入 round 折叠条（`SEGMENT_MIN_STEPS` 对"连续单步"不生效的缺口）；保持"运行中展开、完成后折叠、保留最新 2 步"的既定语义。
6. **reasoning 合并为单块**：同一轮的多段 reasoning 合并渲染成一个 Thinking 折叠块（默认折叠），并按 `opencode#32152` 剥离 thinking echo。
7. **系统事件收敛到状态行**：断流/续写/空回合/压缩的提示进入顶部状态行或 overlay（codex status line 模式），不再以 inline error 反复出现在对话流；错误只在**最终失败**时出现一次。

### P2 —— 减少轮次（根因性的"噪音源"）

8. **并行工具调用**：在工具 schema / 系统能力说明中显式声明"可以一次返回多个独立 tool_calls"（`[T-parallel-tool-calls]` 已支持调度），让模型把"读三个文件"这类操作合并成一轮；这是协议能力，不是措辞约束。
9. **重复调用合并显示**：同参数同结果的连续调用在 UI 合并为 `×N`（根因修复后应显著减少）。

---

## 五、本次已完成的修复（与噪音直接相关）

- `ChatExporter.forEachBatch` 现在用 `fullBodyForCopy` 解析外置正文（stub 不再被当正文导出）→ 对应上表"外置正文占位符泄漏"。
- **导出降噪已实施（P0）**，并用同一份真实会话回放验证：
  - 纯 tool_result 载体（1023 行）不再输出 `## 用户` 空段；
  - 连续工具-only 轮合并为 `> **工具** name ×N`（1289 个工具块 → 535 个合并段）；
  - 外置正文单块截断 2000 字符（防止修复 stub 后体积爆炸）；
  - **实测：3192 块 → 1415 块（−56%），字符 68,632 → 48,664（−29%），空段 0**。
- 压缩尾部按完整轮次边界切分（`safeTailStart`）+ 孤儿 `tool_use` 成对移除 → 直接减少"重复工具卡片"的根源。
- 召回块在压缩逐出后只重播一次 → 消除"每个请求重复宣布记忆"的重复噪音。

## 六、验收方式

- 用同一会话重新导出：**空 `## 用户` 段 = 0**、`body stored (` 出现 = 0、连续工具行合并后 ≤ 原 1/5。
- 运行中：任一轮完成后折叠条只留一行 + 最新 2 步；断流/续写不产生对话流条目（仅状态行）。
- 单测：导出格式化（合并/跳过/截断）与 `ChatRound` 单步合并各加一组回归测试。

---

### 参考

- [openai/codex #41601 progress-first TUI with collapsible tool output](https://github.com/openai/codex/issues/41601)
- [openai/codex #36845 final-answer-only / quiet transcript mode](https://github.com/openai/codex/issues/36845)
- [openai/codex #32817 hide/collapse code snippets in progress output](https://github.com/openai/codex/issues/32817)
- [anomalyco/opencode #7260 compact tool output with click-to-expand](https://github.com/anomalyco/opencode/pull/7260)
- [anomalyco/opencode #54039 choose how much tool output shows before Click to expand](https://github.com/anomalyco/opencode/pull/54039)
- [anomalyco/opencode #32152 collapse fragmented reasoning parts and strip thinking echo](https://github.com/anomalyco/opencode/pull/32152)
- [DeepWiki: codex Status Line and Footer Rendering](https://deepwiki.com/openai/codex/4.1.4-status-line-and-footer-rendering)
