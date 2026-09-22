# Codex `/goal` 追求目标 — 可行性与对齐规格

> Status: **analysis only（2026-09-21）**。未实现、未改 Room、未挂 Agent 循环。
> 对照：[OpenAI Codex /goal 源码级剖析](https://60ke.github.io/posts/openai-codex-goal-implementation/)（2026-05-24）。
> 原则：对齐 **契约与运行时行为**，不复制 OpenAI 内部 prompt 原文，不把 CLI 进程模型原样搬进 Android。

---

## 1. 结论

**能做，而且应该做成独立运行时；不要拿现有 `agent_plan` 硬改。**

Codex 的 `/goal` 不是「任务描述字段」，而是跨 Turn 的运行时：每线程一条目标、状态机、Token/墙钟会计、Turn 结束后自动再开一轮、用隐藏用户消息注入 steering。本仓库缺的正是「Turn 结束后仍 Active → 自动续跑」这一环；其余挂钩（隐藏 reminder、Room、slash、`LLMUsage`、前台服务）已经存在。

架构上可行，且与现有 tool 循环咬合。要对齐的是「持久意图 + 会计 + 隐藏 steering + 受控自动续跑」。Android 生命周期做不到 Codex CLI 的进程级 1:1（见 §6）。

---

## 2. Codex 契约（要对齐的不是 UI 文案）

来源文章把系统拆成：数据结构、工具层、会计、自动继续、Steering Prompt、事件、SQLite 持久化。

### 2.1 数据：`ThreadGoal`

| 字段 | 类型 | 说明 |
|---|---|---|
| `objective` | String | 目标描述，最大 4000 字符 |
| `status` | enum | 状态机当前状态 |
| `token_budget` | optional i64 | Token 预算上限 |
| `tokens_used` | i64 | 已消耗 Token |
| `time_used_seconds` | i64 | 已消耗墙钟时间 |

本仓库映射：`thread_id` → 聊天 `sessionId`。一会话一条 Goal。

### 2.2 状态机：`ThreadGoalStatus`

```
None --create_goal--> Active <-- resume
                         |
          +--------------+--------------+------------+
          v              v              v            v
      Complete        Blocked     BudgetLimited    Paused
                                  UsageLimited
```

约束（必须原样对齐）：

- `update_goal` **只能**把状态设为 `complete` 或 `blocked`。
- `Paused` / `UsageLimited` 由用户或系统控制，模型不能改。
- `BudgetLimited` 由会计系统自动触发。
- `Blocked` 需同一阻塞条件连续出现 **3 次**（防抖）。

### 2.3 工具层

| 工具 | 行为 |
|---|---|
| `create_goal` | 仅当用户**明确要求**时创建；`objective` 必填，`token_budget` 可选；已有 Goal 则失败 |
| `update_goal` | 只允许 `complete` / `blocked` |
| `get_goal` | 只读，含预算使用情况 |

用户入口另有 slash `/goal`。

### 2.4 会计

- **Turn 维度**：相对上次记账的 Token 增量（saturating sub）。
- **墙钟维度**：`last_accounted_at` → 经过秒数。
- 时机：`TurnStarted` 初始化快照；`ToolCompleted` 累加并检查预算；`TurnFinished` 清理。
- 超预算 → `BudgetLimited`，并注入 `budget_limit` steering，引导收尾而不是直接掐死。
- 会计与续跑各一把互斥锁。

### 2.5 自动继续

Turn 结束后若 Goal 仍为 `Active`，系统自动开新 Turn。需同时满足（文章 `maybe_start_goal_continuation_turn`）：

- Goal 存在且 `Active`
- 未暂停、未超预算
- 上一续跑产生了有效自主活动（否则抑制，直到用户 / 工具 / 外部活动重置）

### 2.6 Steering：隐藏用户消息，不是 system prompt

包装在 `<goal_context>` 里，作为**隐藏用户消息**注入。三种模板：

| 模板 | 何时 |
|---|---|
| `continuation.md` | Turn 结束仍 Active：防缩小目标、防过早完成、防轻易放弃、注入预算 |
| `budget_limit.md` | 预算耗尽：总结进度、剩余工作、下一步建议 |
| `objective_updated.md` | 用户改写目标：用 `<untrusted_objective>` 强调这是未验证用户数据 |

设计意图：多数模型把 user turn 当成「要处理的任务」，把 system 当成「必须遵守的规则」。用户写的 objective 当规则会抬高注入权限。

### 2.7 完成审计（prompt 约束，无额外基础设施）

- 完成必须被**证明**，不能把成功标准缩小到当前已做完的部分。
- 「没发现剩余工作」不等于完成。

---

## 3. 本仓库对照

| 机制 | Codex | OpenMinis-Linux（1.36.6 时点） |
|---|---|---|
| 数据 | `ThreadGoal` | 无。Room **version 15**，可加 `session_goals`（下一版 16） |
| 状态机 | 见 §2.2 | 无。流式只有 cancel / `_canResume` |
| 模型工具 | create / update / get | 无。`agent_plan` 是会话内步骤板，模型可 `set/advance/done/fail` |
| 会计 | 按 Turn + 墙钟，超限转 BudgetLimited | 每条助手消息有 `LLMUsage`，会话可汇总；**没有按目标记账，也没有预算闸** |
| 自动继续 | Turn 结束仍 Active → 注入 continuation → 新 Turn | `runAgentLoop` 在**无 tool_call 时 break**。空回复只有一次 `<system-reminder>` 重试 |
| Steering | `<goal_context>` 隐藏用户消息 | 已有不进气泡的合成 `<system-reminder>` 用户行（空回合、取消、bashism）；compact 会 `stripSystemReminders` |
| 完成审计 | Prompt | 无 |
| Blocked 三连 | 同一条件 × 3 | 无。`ask_user_question` 是「问用户」，不是 blocked |
| 防跑飞 | `continuation_lock` + 无活动抑制 | `MAX_AGENT_TURNS = 200` 只罩**单次用户发送内的 tool 循环** |
| 持久化 | SQLite 原子 `account_thread_goal_usage` | 可 Room；杀进程、换会话再进必须仍能读到目标 |

挂钩（实现时用，不要另起炉灶）：

- 工具注册：`AgentTools.makeAgentTools`（`src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt`）
- 安全白名单：`SecurityGateImpl`
- Slash：`ChatViewModel.availableSlashCommands` / `executeSlashCommand`（现有 `/clear` `/compact` `/memory` `/thinking`）
- 隐藏合成用户行：`ChatViewModel` 里 `<system-reminder>` 路径；compact 剥离见 `ChatRepository.stripSystemReminders`
- Token：`LLMUsage`、`ChatDao.tokenUsages`、会话 `SessionTokenStats`
- 无 UI 再跑一轮：`HeadlessChatRunner` / `ScheduledAgentRunner`（闹钟语义，**不是** idle 续跑）
- 前台保活：`AgentForegroundService`
- 循环上限：`ChatViewModel.MAX_AGENT_TURNS = 200`

### 3.1 不要和这三块搞混

| 已有能力 | 它是什么 | 和 Goal 的关系 |
|---|---|---|
| `agent_plan`（`AgentPlanTool.kt`） | 当前会话的 HOW（步骤索引），内存、不会计、不自动续跑 | Goal 是 WHY + 运行时。应**并存**：Goal 活着时模型仍可用 `agent_plan` 拆步 |
| `cronjob` / `ScheduledAgentRunner` | 闹钟触发新 prompt | 不是「本轮说完了还要接着干」 |
| Memory / compact | 背景上下文；系统提示已写明不要当进行中的 goal | 和 Codex 的 standing objective **相反**。禁止把目标写进 `GLOBAL.md` |

---

## 4. 关键缺口：自动续跑

要对齐 Codex，必须改 `runAgentLoop` 的退出语义：

```
用户发送
  → 现有 tool 循环（最多 200 轮）
  → 模型不再调工具（一次「Codex Turn」结束）
  → 若 session 有 Active goal
       且未 Paused / 未超预算
       且上一续跑产生了有效活动（文本或工具）
       且 App 仍前台 / 用户未点停
     → 注入隐藏 <goal_context>（continuation）
     → 再进一轮 runAgentLoop
  → 否则停，等用户
```

与现有「空 tool-result 后塞 reminder 再 `continue`」同类，但：

1. 续跑是**新的用户 Turn**（新助手气泡），不是同一轮里再调一次模型。
2. 内容来自 DB 里的 goal + 模板。compact 掉历史 reminder 也不丢目标——**每轮从状态重注入**，不要依赖聊天记录里那条 XML。
3. 停按钮要拆成：停这一轮 vs **暂停 Goal**（不再自动续）。

`ChatViewModel.runAgentLoop` 已经很重。续跑必须抽到独立 `GoalRuntime`（事件驱动，对应文章的 `GoalRuntimeEvent`），只在 loop 尾部调 `maybeContinue`。续跑次数要有会话级软顶（例如 20 次自动 Turn 或预算先到），和现有 200 tool-turns 是**两层闸**。

---

## 5. 做得到一致的部分

可以直接按文章落地：

1. 一会话一条 Goal（`sessionId`）。
2. Slash `/goal <objective> [--budget N]` + 工具 `create_goal`（描述写明：仅当用户明确要求；已有 Active 则失败）。
3. `update_goal` 只允许 complete/blocked；Paused 由用户；BudgetLimited 由会计。
4. Steering 用隐藏用户消息，不塞进已经很长的 system prompt；objective 用 `<untrusted_objective>` 当数据。
5. Blocked 三连、完成审计、budget_limit 收尾 prompt。
6. 会计：每个模型 round 用 `LLMUsage` 做 saturating delta（input+output；cache 是否计入需定一条并写进注释）。墙钟用 `elapsedRealtime`。
7. UI：会话顶栏显示 objective / 状态 / used/budget；Paused 可 Resume。

---

## 6. 做不到 1:1、必须显式降级

| Codex | 本仓库必须怎么处理 |
|---|---|
| CLI 进程一直活着，空闲就 `MaybeContinueIfIdle` | Android 会 Doze / 杀后台。续跑只在**聊天页前台或已有 AgentForegroundService** 时进行；进后台 → 自动 `Paused`（或保持 Active 但冻结续跑），通知里可点继续。不要做成无限后台 Agent。 |
| `UsageLimited`（ChatGPT 套餐额度） | BYOK，没有平台配额。可省略该状态，或把提供商 429/额度错误映射成临时 Blocked，不要假装有 Codex 账号墙。 |
| 每次 tool 完成后精确 token delta | 多数提供商按**整次 LLM 响应**报 usage，工具本身不计 token。按 round 记账即可，文档写明。部分模型 usage 为空 → 用估算或只记墙钟。 |
| 源码级模板原文 | 文章里 continuation / budget_limit / objective_updated 是转述。对齐机制与约束，prompt 用本仓库短模板（中英随用户语言），不要假装复制 OpenAI 内部 md。 |

---

## 7. 建议落地（尚未开工）

### MVP（行为已像 Codex，可测）

1. `SessionGoal` 实体 + Room **16** 迁移（含 downgrade，现有链有这个传统；`DatabaseVersionGuard.CODE_DB_VERSION` 同步）。
2. `create_goal` / `update_goal` / `get_goal` + `/goal` slash。
3. 每轮从 DB 注入 `<goal_context>`。
4. `runAgentLoop` 正常结束后 `maybeContinueGoal()`，带无活动抑制 + 单次续跑互斥。
5. Token 预算 → `BudgetLimited` + 收尾一轮。
6. 单元测试：状态机、三连 blocked、会计超限、续跑抑制、一会话一条。

### 不要第一期做

- 后台无限续跑
- `UsageLimited`
- 改 `agent_plan` 冒充 Goal
- 把 goal 写进 memory / `GLOBAL.md`

### 设计哲学（实现时保持）

摘自对照文，作为验收标准：

1. **约束即能力**：模型只回答「完成了吗？」或「真的卡住了吗？」
2. **渐进式阻塞**：同一条件连续 3 次才 blocked。
3. **隐式引导 vs 显式控制**：steering 是隐藏用户消息，不是系统法令。
4. **会计即治理**：预算耗尽时优雅收尾，不是直接停。

---

## 8. 关键代码索引（本仓库）

| 路径 | 用途 |
|---|---|
| `src/android/app/src/main/java/com/openminis/app/tools/AgentPlanTool.kt` | 现有步骤板（不要改成 Goal） |
| `src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt` | 工具注册点 |
| `src/android/app/src/main/java/com/openminis/app/tools/AskUserQuestion.kt` | 问用户 ≠ blocked |
| `src/android/app/src/main/java/com/openminis/app/tools/CronJobTool.kt` | 闹钟 ≠ idle 续跑 |
| `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt` | `runAgentLoop`、slash、`MAX_AGENT_TURNS`、空回合 reminder |
| `src/android/app/src/main/java/com/openminis/app/data/model/LLMUsage.kt` | 会计输入 |
| `src/android/app/src/main/java/com/openminis/app/data/db/AppDatabase.kt` | Room 15；Goal 需 16 |
| `src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt` | `stripSystemReminders` |
| `src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt` | 无 UI 再跑一轮的参考，语义不同 |
| `src/android/app/src/main/java/com/openminis/app/security/SecurityGateImpl.kt` | 新工具白名单 |

Codex 侧文件名（文章索引，实现时对照契约即可）：

`core/src/goals.rs`、`core/src/context/goal_context.rs`、`core/src/tools/handlers/goal_spec.rs`、`core/templates/goals/{continuation,budget_limit,objective_updated}.md`、`protocol/src/protocol.rs`、`state/src/runtime/goals.rs`。

---

## 9. 参考

- [OpenAI Codex /goal 源码级剖析](https://60ke.github.io/posts/openai-codex-goal-implementation/)
- [openai/codex](https://github.com/openai/codex)
