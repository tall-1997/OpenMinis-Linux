# 任务偏移根因分析与结构性修复

**问题陈述（用户描述）**
1. 长会话多任务时任务偏移
2. 记忆未召回读取
3. 模型运行时偏移任务，自己却不知道
4. 已有的经验/记忆再次执行时反复踩坑

**约束**：修复必须是结构性的，不能靠提示词或全局规则约束。

---

## 一、对照：opencode 是怎么做到的结构性保障

从 `anomalyco/opencode@dev` 实际源码（2026-11-04 抓取）：

| 机制 | 文件 | 结构性在哪 |
|---|---|---|
| **待办是持久会话状态** | `packages/core/src/tool/plugin/todo.ts` | `todowrite` 全量替换 `{content,status}` 列表，状态机 `pending/in_progress/completed/cancelled`；返回 `summarize()` 生成 "3/7 completed. In progress: …"，模型每轮都有可读进度 |
| **上下文纪元持久化** | `database/migration/20260605003541_add_session_context_snapshot.ts` | `session_context_epoch(session_id, baseline, snapshot, baseline_seq, replacement_seq, revision)`：把"这一轮模型实际看到的指令+工具快照"落库，可按 seq 重建/对比。漂移不是猜的，是可复现的 |
| **上下文装载是显式服务** | `session/context.ts` | `Selection{instructions, tools}` + `Loaded{initial, tools}`；"Selects the Session, agent, instructions, and tools used by subsequent work" |
| **回滚是文件级检查点** | `session/revert.ts` + `snapshot.ts` | `stage(messageID)` 先 `snapshot.capture()`，再 `plan()` 算出要还原的文件集，`snapshot.restore()`；消息截断与文件还原一起做 |
| **压缩保留原生尾** | `session/compaction.ts` + `recent-text.ts` | `keep` 参数 + `keepID` 锚点 + `recentUserMessages(keep)`，摘要旁边永远有 verbatim 尾 |

关键结论：**opencode 让"当前任务状态"成为独立于对话历史的数据，并且在每次请求时从数据重建**。对话历史可以被压缩、被摘要，但任务状态不会被摘要吃掉。

---

## 二、OpenMinis 的根因（逐条对应症状）

### 根因 1：计划板只进工具返回，从不进请求上下文 → 症状 1 + 3

`AgentPlanTool.kt` 里 `render(sessionId, context)` 生成的 `<plan_board>` 文本，**只出现在 `execute()` 的 13 处返回值里**（`:440`、`:446`、`:450`、`:463`、`:465` …），没有任何调用点把它放进每一轮的请求。

后果链条：
1. 模型调用 `agent_plan` 写下 5 步计划 → 计划落到 `agent-plan/plans.json`
2. 接下来几轮，历史里只剩"我调用过一次计划工具"这个动作，**计划内容不在上下文里**
3. 跑到第 5-10 轮，模型看不到计划，只能凭最近几条消息行动 → 偏移
4. 压缩之后，连"写过计划"这个动作都可能被折进摘要 → 彻底失去锚点
5. **模型没有任何可检查的状态**，所以它偏移时"自己不知道"——这不是模型的问题，是我们没给它可查的东西

对比 opencode：todo 是会话状态，且随每轮上下文重建；我们是"写进文件就再也看不见"。

### 根因 2：召回在 run 级只算一次 → 症状 2

`buildSystemPrompt()` 在 `ChatViewModelSendExt:407`、`ResumeExt:90`、`RetryExt:195`、`ResumeQueueExt:81`、`RerunTailExt:59` 各调用**一次**；返回的 `systemPrompt: String` 随后被 `runAgentLoop` 整个复用（`maxTurns` 默认 200）。

而召回在 `ChatViewModelPromptExt` 里，查询键是：

```kotlin
val query = _messages.value.lastOrNull { it.role == "user" && !it.isQueued }?.content.orEmpty()
```

一个长 run 里没有新的 user 消息 → query 不变 → **召回结果从第 1 轮到第 200 轮完全冻结**。中途话题换了、子任务换了，召回不会重算。

更糟：用户续任务时常说"继续"、"接着做"——查询词近乎为空 → `extractKeywords` 几乎无词 → 命中为零 → **等于没召回**，但模型和用户都看不出来。

### 根因 3：经验库没接主循环 → 症状 4

`ProjectExperienceRepository`（`recallBlock` / `recordProjectExperience`）全仓只有 **`ChatViewModelSupervisorExt:402`** 一处调用——那是 supervisor 席位。

主 agent loop：
- 执行工具时**不查**经验库
- 工具失败后**不写**经验库

所以"上次这个命令在这台机器上 OOM 了"这类信息，主模型永远拿不到；失败只存在于对话历史里，然后被压缩吃掉。下次遇到同样场景，从零开始踩同一个坑。

`ToolLoopDetector` 只能识别"连续重复同一个调用"，识别不了"换了工具但踩同一类坑"。

### 根因 4：记忆是"写多读少"，读取时机不对

写入侧有额度、去重、权限；读取侧只有"prompt 构建时按最后一条 user 消息召回"。任务连续时召回词稀薄，读不到；换任务时召回词变了，但 system prompt 已经冻结（见根因 2）。

---

## 三、结构性修复（按性价比排序）

### F1　计划板进入每一轮请求（治症状 1、3）

**做法**：在 `runAgentLoop` 每轮构造 `requestHistory` 时，从 `AgentPlanStore` 读取并 `render()`，作为独立数据注入请求；压缩时把计划板当作**不可压缩锚点**（类似 opencode `keepID`）。

**为什么是结构性的**：不是提示模型"记得看计划"，而是**每一轮请求里都客观存在计划数据**；模型不想看也会看到。计划为空时不注入（不产生噪声）。

**落点**：`ChatViewModelAgentLoopExt` 每轮 `withGoalContext(...)` 之前，插入计划板渲染；渲染进 system 段或作为 `<plan_board>` 的独立 user 段（需与 prompt cache 稳定性权衡）。

### F2　召回按轮重算 + 多源（治症状 2）

**做法**：
- 把召回从 `buildSystemPrompt`（每 run 一次）移到 loop 内**每轮重算**（或每 N 轮，N 可配）；
- 查询键改为「最近一条 assistant 文本 + 本轮待执行工具名 + 最近 user 文本」的拼接，而不是只看 user；
- 召回源加入 **本会话近期失败记录**（来自经验库）。

**为什么是结构性的**：召回结果随轮次变化，不再被 run 级快照冻结。

**成本控制**：已有 `MemoryRecallEngineCache` 与同 query memo，重算代价低；改为每 3 轮重算可进一步降本。

### F3　经验库接进主循环 + 硬闸门（治症状 4）

**做法**：
1. 工具执行前查 `project_experience`：同会话 + 同工具 + 同参数指纹，近期失败 ≥ 2 次 → **直接返回上次失败结果并标注 `[known-failure]`，不再执行**（硬约束，不依赖模型自觉）；
2. 工具执行后写回经验（主循环，而不是只有 supervisor）；
3. 失败信息进入下一轮召回源（与 F2 联动）。

**为什么是结构性的**：第一次踩坑后，第二次由**代码**拦住，而不是指望模型记住。

**风险**：已知失败也可能因环境变化而可重试。缓解：闸门只在"同参数指纹"命中时触发，且结果标注"如需强制重试请更换参数或使用 force 参数"。

### F4　上下文纪元（可选，较重）

仿 opencode `session_context_epoch`：每轮把实际注入的上下文（指令 + 计划板 + 召回结果）落库并带 seq，用于事后复现"模型当时看到了什么"。

**收益**：诊断与回滚的基础；对四个症状的**直接**边际收益低于 F1-F3。

---

## 四、执行顺序

| 序 | 任务 | 依赖 | 直接缓解 |
|---|---|---|---|
| 1 | F1 计划板每轮注入 + 压缩锚定 | — | 症状 1、3 |
| 2 | F3 经验硬闸门 + 主循环写回 | — | 症状 4 |
| 3 | F2 召回按轮重算 + 多源 | F3（复用失败源） | 症状 2 |
| 4 | F4 上下文纪元 | F1、F2 | 诊断能力 |

## 五、明确不做

- **不在系统提示里加"请定期检查计划"之类的提醒**：那正是用户明确排除的方案，且会随历史增长而失效。
- **不做基于语义相似度的漂移检测器**：误报代价高，四个参考项目都没有可靠做法。
- **不引入 embedding**：无后端，且与本轮四个症状无关。

## 六、验证口径

- F1：单测断言"计划板内容出现在第 N 轮请求里"；压缩后仍可见。
- F2：单测断言"同一 run 内不同轮次的召回查询不同"；"继续"类消息能召回上次任务的记忆。
- F3：单测断言"同参数第二次失败不执行、返回 known-failure"；"换参数后可执行"。

---

## 实施记录（F1 → F3 → F2）

### F1 计划板每轮注入

- 新增 `ChatViewModelPlanContextExt.kt`：`planBoardPrompt()` 从 `AgentPlanStore` 现取现渲染，**空板不注入**（否则每轮都带一段"暂无计划"噪声）。
- `withGoalAndPlanContext()` 把计划板与 goal 合并成一个块，仍挂在最后一条 user 消息上。
- `GoalContextInjection.withGoalContext` 的剥离逻辑扩展为同时剥离 `<plan_board>` 与 `<goal_context>`，否则每轮会把旧板叠一层。
- `ChatViewModelAgentLoopExt` 的调用点从 `com.openminis.app.goal.withGoalContext(` 换成 `withGoalAndPlanContext(`——**等长替换，文件仍 2081 行**。

**压缩锚定无需额外代码**：计划板每轮从 `plans.json` 重建，不来自历史，所以压缩天然吃不掉它。这是"结构性"的直接体现。

### F3 失败记忆闸门

- 新增 `ToolFailureMemory`：指纹 = 工具名 + 精确参数（SHA-256 前 16 位）；同指纹失败 ≥2 次、且在 30 分钟窗口内 → 拒绝执行；成功则清除；记录按时间保留最多 50 条。
- 闸门接在 `executeTool` 的 canonical 化之后（一处覆盖全部工具路径）；结果回写接在主循环 `ChatViewModelToolTurnExec` 的单次调用点。
- 拒绝文案带 `[known-failure]` 前缀、失败次数与上次错误，并明确"换参数会正常执行"。

### F2 召回按轮重算

- `buildSystemPrompt` 里的 run 级召回置空（保留变量与装配形状），改由 `liveRecallPrompt()` 每轮计算。
- 查询键 = 最近 user 文本 + 最近 assistant 文本尾部 400 字符 —— 让"继续"这类空查询也能带上当前上下文。
- 召回源仍是 memory 文件 + 知识笔记（F2 计划中的"会话内失败记录"未做，见下）。

### 偏差与遗留

1. **未把会话失败记录并入召回源。** 计划里 F2 依赖 F3 的失败数据，但失败记录是按"工具+参数"索引的，直接塞进关键词召回价值不大；等 F4（上下文纪元）有了统一视图再做更合理。
2. **召回频率没有节流。** 每轮都重算意味着每轮一次文件扫描 + 一次 sidecar 写。测试与本地评估认为可接受（毫秒级），但长期应改为每 N 轮或按查询变化触发。
3. **闸门只覆盖主循环的单次调用点**（`ChatViewModelToolTurnExec`）。子代理 / group chat / supervisor 的调用点未回写失败记录，因此它们的失败不会触发闸门。这是刻意收窄：先让主路径行为可预测。

### 验证

- 编译 `:app:compileReleaseUnitTestKotlin` 通过。
- `goal.*` / `tools.*` / `data.repository.*` / `data.instruction.*` **732 用例 / 0 失败 / 0 跳过**；新增 `GoalContextInjectionPlanTest` 4 个、`ToolFailureMemoryTest` 9 个；既有 `AgentPlanToolTest` 37 个与 `AgentPlanBoardLifecycleTest` 11 个无回归。
- 架构门禁 OK（`ChatViewModelAgentLoopExt.kt` 仍 2081 行，净零）；CJK 门禁 OK。
