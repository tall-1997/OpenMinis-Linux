# 正文碎片根因分析 v3——多变量排除后的最终定位

日期：2026-11-13 ｜ 数据：真机 logcat + live DB + 代码 diff（2.0.83 → main）
前版：v1（模型层假设，被排除）、v2（消息结构假设，部分正确）

---

## 0. 排除法收敛

| 假设 | 排除实验 | 判定 |
|------|---------|------|
| 模型 preamble 固有 | 换模型复现 | ❌ |
| persona/提示词 | 换人格提示词复现 | ❌ |
| 消息拆分结构 | v2 分析发现 per-turn text/tool 分离；但 #330 已经用 commit-once 修复，wire 格式正确 | 🟡 已修但不够 |
| **hydration 每轮触发** | #335 已加 hasUndelivered 门修复 | ✅ 已修 |
| **system prompt 体量** | 68k tokens 实测 | ❗ **最终根因** |

---

## 1. 实锤数据

| 指标 | 2.0.83 | main 实测 |
|------|--------|-----------|
| system prompt source 行数 | 537 | 697 |
| system prompt 注入段 | ~8 | ~15+ |
| 每轮请求 prompt tokens | （短会话，无问题） | **68,120** |
| cached tokens | — | **66,880**（cache hit 率高但 miss 1,240）|
| reasoning per turn | — | **1.2 万–5.5 万字符** |
| content per turn | — | 100–172 字符 |
| 带正文的 tool_calls / 总 tool_calls | — | 近 100%（从 logcat 采样） |

**每轮模型处理 68k tokens → 思考 12k tokens → 写 165 字正文 → 调工具。**
循环往复，每轮 50+ 秒（streamMs=54404）。

---

## 2. 为什么 system prompt 会膨胀

2.0.83 → main 增加了以下**每轮注入**的内容（system prompt 或历史 user 消息）：

| 注入块 | 来源 PR | 每轮？ | 触发模型回应？ |
|--------|---------|--------|---------------|
| spawn_agent 完整说明（含 group chat/supervisor 模式选择） | 2.0.83 已有，但更长 | ✅ | 低（工具说明，模型不"回应"） |
| collaboration mode 选择指引 | 2.0.83 已有 | ✅ | 低 |
| work report 模板/说明 | **#321 新增** | ✅ | 中（模型读报告模板就"汇报"） |
| background jobs 说明 | **#321 新增** | ✅ | 中 |
| in-flight lanes prompt | **#321 新增** | ✅ | 中 |
| plan board prompt（含 auto-advance/retry 指引） | **#327 扩展** | ✅ | **高**（模型读到 plan 步骤就"汇报进度"） |
| live recall（memory） | **#316 改造** | ✅ | **高**（模型读到记忆就"引用/确认"） |
| goal_context | **#311/#328** | ✅ | **高**（模型读到目标就"重述目标"） |
| WorkingMemory 交付行 | **#333** | 有新行时 | **高**（模型读到过程记录就"汇报"） |
| SessionStateSnapshot 交付行 | **#330** | 有变化时 | 中 |

**标"高"的注入块是 preamble 的主要诱因**：模型读到这些状态块后，
自然会输出一段"我已经理解了状态，现在要做 X"的正文——这是 LLM 的
正常行为（有新上下文就更新认知），但每次都打断工具调用流。

---

## 3. 为什么 ZCode/Harness 没有这个问题

ZCode 的系统提示也是大 prompt，但它有一个关键差异：
**ZCode 的工具调用之间的正文直接渲染在 TUI 流里，与工具输出混排——
用户看到的不是"正文碎片"，而是自然的 CLI 输出流**。没有"正文/工具卡
交替"的视觉断裂。

ZCode 还有第二个机制：**`ReasoningEffort` 按轮调整**（zcode.cjs 实锤
`reasoningEffort:m.enum(["none","minimal","low","medium","high","xhigh"])`）——
某些轮次用低 effort，模型就不会深度思考再写长 preamble。

---

## 4. 根因修复建议（结构级，非补丁）

### 4.1 P0：goal/plan/memory 状态块的"commit-once → 增量更新"

**现状**：goal_context/plan_board/memory 每轮都在 system prompt 或
USER 消息中重复出现（即使内容没变）→ 模型每次都"重新确认"。

**修复**：这些状态块**只在变化时注入**（增量 diff），不在 system prompt
中常驻。ZCode 用 `session goal state` 字段实现（zcode.cjs 实锤
`"Current session goal state"` 字符串）——状态在历史中承载，只在变化时
发新消息。#330 已经做了 commit-once，但 system prompt 中的 goal_context
和 plan_board 仍然每轮出现。

**具体**：`GoalContextInjection.goalMount` 和 `planBoardPrompt()` 的
输出应**只注入一次**（首次挂载），后续轮次靠 plan steps 的 auto-advance
（#11 已实现）驱动——plan 状态通过 tool result 的 step 行反馈给模型，
不需要 system prompt 重复。

### 4.2 P1：条件性注入（减少 system prompt 静态体积）

以下注入块**只在对应场景活跃时注入**：
- spawn_agent 说明：仅当有子代理在飞（`SubAgentActivityTracker.isActive`）
- collaboration mode 选择：仅当 group chat/supervisor 可用且用户未选
- work report 模板：仅当有 report 在飞
- background jobs 说明：仅当有 job 在跑
- minis-model-use/minis-config/minis-scheduled 说明：可从 system prompt 移到
  tool schema description（模型通过工具列表已能看到）

**效果**：system prompt 缩短 → reasoning 减少 → preamble 频率下降 →
每轮时间从 50s 降到 ~15s。

### 4.3 P2：reasoning effort 按轮调整

zcode.cjs 实锤支持 `reasoningEffort: enum("none","minimal","low","medium","high","xhigh")`。
后续轮次（工具循环中）可用 LOW/MEDIUM，首轮（用户意图理解）用用户选择的档位。
需要 per-call thinking override 通道（LLMProvider 接口改动）——探查后实施。

---

## 5. 排除项（已做，不是问题）

| 项 | 排除理由 |
|----|---------|
| 消息拆分结构 | wire 格式正确（assistant content+tool_calls → tool result，标准 agent 形态）|
| 持久化结构 | assistant 消息已含 text+tool_use（同一条消息）|
| provider 序列化 | 2.0.83 与 main 一致 |
| WorkingMemory 每轮注入 | #335 已修（hasUndelivered 门）|
| hydration 每轮触发 | #335 已修 |

---

## 6. 前置动作（真机验证）

1. 装 #333/#334 合并后的滚动包，跑长任务，观察 preamble 频率是否下降
   （#335 的 hydration 门 + #334 的动态预算应该有效果）
2. 如果仍然高频：在 Settings 加一个 "System prompt 长度" 诊断行（当前
   token 数 + 各段占比），帮助用户确认是 system prompt 膨胀还是别的
3. 如果确认是 system prompt 膨胀：按 §4.1 → §4.2 顺序修

---

*分析文档 v3。核心发现：wire 格式正确、模型行为正常——问题在 system prompt
中每轮重复出现的状态块（goal/plan/memory）导致模型每次都"重新确认"。*
