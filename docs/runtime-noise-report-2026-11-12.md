# 运行噪音的真实根因：每轮请求对历史重复注入过程块

- **日期**：2026-11-12（修订版，取代同文件首版）
- **纠偏说明**：首版把"噪音"理解成 UI 显示/导出格式，方向错了。本文按你澄清的口径重写：**噪音 = 上下文里被不停重复注入的过程信息**，它在模型每次请求时重复出现，干扰推理与推进。首版的 UI/导出部分降级为附录（次要，且已有部分修复）。
- **结论**：这不是"某个块泄漏一次"的 bug，而是**注入架构**问题——过程块被 graft 到历史消息上、且**每轮重新组装一次**。历次修复（strip 正则、hit-key 去重、阈值、召回闩锁）都作用在"注入的下游"，没有动"注入的位置与频率"，所以反复复发。

---

## 一、机制（代码证据，不是猜测）

### 1. 注入发生在每一轮请求里

`ChatViewModelAgentLoopExt.kt:558`（**在 `for (turn in 0 until maxTurns)` 循环体内**）：

```kotlin
val requestHistory = ToolIdNormalization.normalizeMessages(
    withGoalAndPlanContext(
        effectiveAgentHistory(),          // 每轮从 agentHistory 复制一份
        goalMount.prompt,
        appendNewUser = goalExecutionRun && turn == 0,
    ).let { history -> /* 必要时追加 stall 续写 note */ }
)
```

`withGoalAndPlanContext` → `PromptParts.render(...)` → `withGoalContext(history, combined)`。

`PromptParts.KEYS`（`PromptParts.kt`）把**五个过程块**纳入注入注册表（**每个块按需注入**：render 返回 null/空白时跳过，不是无条件五块齐发）：

```
runtime/work_report   ← AgentReportStore.stagePrompt()（当前阶段 + 最近报告）
runtime/in_flight     ← SubAgentBatchRegistry.renderInFlight()（在跑的子代理车道）
runtime/jobs          ← JobRegistry.list()（后台任务）
runtime/plan_board    ← AgentPlanStore.boardSnapshotPrompt()（计划板快照）
runtime/recall        ← liveRecallPrompt()（记忆召回，唯一有跨轮去重）
```

### 2. 注入目标是"最后一条 USER 消息"——turn≥1 时那是 tool_result 载体

`GoalContextInjection.kt:92`：

```kotlin
var userIndex = requestHistory.indexOfLast { it.role == LLMMessage.Role.USER }
...
requestHistory[userIndex] = user.copy(
    content = user.content + "\n\n" + prompt,          // 过程块拼接
    contentParts = user.contentParts + AgentContentPart.Text(prompt),
)
```

`runAgentLoopBody` 每轮工具执行后会 `appendBoundedHistory(LLMMessage(role = USER, contentParts = resultParts))`，于是：

- **turn 0**：最后一条 USER 是**用户真实消息**（或 goal 模式的追加消息），过程块拼在它后面；
- **turn ≥ 1**：最后一条 USER **就是 tool_result 载体**，过程块被拼进**工具结果消息**——模型看到的是"工具输出 + 过程旁白"的混合体；
- 每个 turn 都产生一个新的 tool_result 载体，过程块**随之每轮出现一次**；
- 内容是否变化取决于各 store 状态（计划板是 per-turn 快照、车道/任务随运行状态变、召回按轮判定），但**无论变不变，每个请求都会重新注入一遍**——这就是"不停的重复注入"。

### 3. "strip 旧块再注入"只修了表面

`withGoalContext` 确实会在注入前 strip 旧块，但：

- 只 strip **最后一条 USER** 上的 3 类标签：`<goal_context>`、`<plan_board>`、`<external_data`；
- `work_report` / `in_flight` / `jobs` **没有 strip 规则**；
- `external_data`（召回）的 strip 还带条件：只有本轮确实要注入召回（`prompt.contains("<external_data")`）才 strip 旧帧——否则旧块保留；
- strip 是"防御历史里已有的副本"，不是"防止重复注入"——每轮副本是新构建的，strip 拦不住"每轮重新注入相同内容"。

### 3b. #321 的修复反而在提示词里**确认**了每轮重复

`AgentReportTool.stagePrompt` 的 `STAGE_RULE` 原文：

> "This block repeats on every request until you call agent_report again …"

也就是说"每轮重复注入"不但是实现行为，还被写进了对模型的指令：#321 把"陈旧快照断言旧结论"改成了"陈旧时降级为一句提示"，但没有改变"每轮都注入"，并且这条规则**推动模型每轮调用 agent_report 刷新**（更多工具轮次）。这是"修过多次但没修到位"的直接例子。


### 4. 顺带确认：系统提示是 run 级一次构建，每轮只替换日期字节

`ChatViewModelPromptExt.buildSystemPromptWithSections` 由 send/retry/resume/rerun 在**每次 run 开始时**调用一次（`ChatViewModelSendExt.kt:433` 等）；`runAgentLoopBody` 里每轮只执行：

```kotlin
val turnSystemPrompt = refreshRuntimeContextDate(systemPrompt, LocalDate.now().toString())
```

`refreshRuntimeContextDate` 只替换 `- Current date: YYYY-MM-DD` 这一段，其余字节不动（已核实实现）。也就是说：

- **系统提示：一次构建、多轮复用**（前缀缓存友好，动态段不随 plan/report 更新）；
- **过程块：每轮历史注入**（缓存不友好，且干扰模型）。

这个不对称正是问题所在：真正需要"每轮最新"的是过程块（plan/report），它们却在历史里；而本该稳定的历史，反而每轮被改写。

### 5. 核实记录（本次审核逐条验证）

| 断言 | 证据 | 结论 |
|---|---|---|
| 注入调用在 turn 循环内 | `ChatViewModelAgentLoopExt.kt:273` `for (turn in 0 until maxTurns)` → L558-562 | ✅ |
| turn≥1 最后一条 USER 是 tool_result 载体 | `appendBoundedHistory(LLMMessage(USER, contentParts = resultParts))` | ✅（turn 0 为用户消息，报告已区分） |
| 五块"全部"每轮注入 | `render` 对 null/blank 跳过 | ❌ 修正为"按需注入；有内容时每轮注入" |
| "内容大多没变" | 无请求体级证据；`boardSnapshotPrompt` 是 per-turn 快照 | ❌ 删除该断言（改为"是否变化取决于 store"） |
| strip 只覆盖 3 类标签 | `GoalContextInjection.kt:109-117` | ✅ |
| 召回是唯一有跨轮去重的块 | `shouldAnnounceRecall`（其他四块无） | ✅；补充：同轮内每个请求仍注入（`turnKey == lastTurnKey → true`） |
| 系统提示 run 级一次构建 | `runAgentLoopBody(systemPrompt)` 参数；send/retry/resume 各构建一次 | ✅ |
| 每轮只刷日期 | `refreshRuntimeContextDate` 仅替换日期 bytes | ✅ |
| #321 修复只改内容未改频率 | `stagePrompt` 陈旧分支仍返回提示块；`STAGE_RULE` 明写 "repeats on every request" | ✅（补充为第 3b 节） |
| 导出降噪"实测" | python 按同一导出回放**模拟**，非真机导出 | ❌ 修正为"回放模拟"（附录 A） |


---

## 二、成熟项目怎么处理（codex / opencode / DeepSeek Harness）

| 项目 | 做法 | 对我们的启示 |
|---|---|---|
| **codex** | 上下文 = system prompt（稳定）+ 对话消息；进度/计划状态由**工具**与**状态行**承载，不注入历史。TUI 只显示 progress-first 单行，过程不进入对话流（[#41601](https://github.com/openai/codex/issues/41601)、[#36845](https://github.com/openai/codex/issues/36845)）。 | 过程信息不该"塞进用户消息"；它属于系统/状态层。 |
| **opencode** | 消息是 part 模型（text/tool/reasoning），**注入只发生在 system prompt 与 rules 文件**；工具输出默认折叠（[#7260](https://github.com/anomalyco/opencode/pull/7260)、[#32152](https://github.com/anomalyco/opencode/pull/32152)）。 | user 消息保持"用户说的话"，不被过程块污染。 |
| **DeepSeek Harness** | 系统提示 + 会话消息分离；上下文压缩按 span/轮次边界；工具调用与结果严格成对。（与我们已修的压缩边界方向一致） | 注入层与对话层分离是通用设计。 |

**共性**：成熟 agent 的"过程状态"要么在**系统侧**（system prompt 的稳定/动态段），要么在**工具状态**里（plan/todo 工具、后台任务 registry 由工具查询），**不会每轮拼接到用户消息上**。

---

## 三、为什么"之前修复过但没真实修复到位"

历次提交都作用在下游症状上：

| 提交 | 修的是什么 | 为什么没根治 |
|---|---|---|
| `4fed29bb` (#321) | work_report 陈旧快照不再每轮注入 | 只处理 work_report 一个块的行为，注入位置/频率没变 |
| `bb1acb00` (#311) | goal 旧目标劫持/清理缺口 | 处理 goal 生命周期，注入仍每轮 graft |
| `2b9560a2` / `36e2ffae` | 召回跨轮去重 | recall 是 5 个块里唯一有去重的；其余 4 个仍每轮注入 |
| `withGoalContext` 的 strip 正则 | 历史里已有的副本 | 防的是"副本残留"，不是"每轮重复注入" |
| 阈值/循环检测器（warning 10 / critical 20） | 拦住极端重复 | 阈值放宽时无效，且不减少上下文干扰 |

**根因级缺陷 = 注入位置（历史 user 消息）+ 注入频率（每轮）+ 缺少统一的"注入层"抽象**。

---

## 四、修复设计（结构性，P0）——**已实施**

> 实施状态（2026-11-12）：4.1 / 4.2 / 4.3 / 4.4 全部落地，编译与全量单测通过。
> 新增 `withGoalFirstMount`、`processInjectionTail` / `processInjectedSystemPrompt` /
> `reuseProcessTail`，以及三组回归测试（`ProcessInjectionArchitectureTest`、
> `GoalFirstMountTest`、`ProcessTailDedupeTest`）。
> 附录 A 的 P1「单步 run 折叠」**未实施**：它与既有契约「单步思考不得被丢弃」
> （[P-process-card-single-step]，用户曾投诉该块消失）直接冲突，正确做法是
> 「round 级卡」重构（全部过程块归一个卡，折叠时一行、展开时全列出），属于
> UI 渲染层的独立改动，需要单独一轮验证，不能混在本次注入层迁移里。


### 4.1 注入层从"历史"上移到"系统提示动态段"

- `runAgentLoopBody` 不再调用 `withGoalAndPlanContext(...)`；`requestHistory` 只做 `ToolIdNormalization.normalizeMessages(effectiveAgentHistory())`（+ 必要的 stall 续写 note）。
- 过程块改由 `buildSystemPromptWithSections` 的 **dynamic tail** 承载：`PromptParts.render(...)` 的输出拼在 `stablePrefixLen` 之后（`assembleDynamicTail` 已有该位置，worldBook/learnedPrefs/runtimeContext/personalityReminder 就在那里）。
- 每轮请求前重建 **dynamic tail**（`systemPrompt.head + buildDynamicTail()`），head（identity + 工具说明 + 技能 + MCP + 全局记忆）保持不变 → **缓存前缀仍然命中**（`systemPromptStablePrefixLen` 语义不变）。
- 效果：历史里不再有过程块；每轮过程块在 system prompt 的同一位置出现**一次**；模型不再把过程块当工具输出。

### 4.2 过程块只在内容变化时重建（内容级去重）

- `PromptParts.render` 的结果按 key 计算内容 hash，与上一轮比较：**全部未变 → 复用字符串**（零重建成本，也是天然的"无重复注入"）。
- 变化的块（如 plan 步骤完成、report 更新）才重建 → 模型只看到"变化的部分"，减少干扰。
- 这与 recall 已有的 hit-key 去重是同一层语义，统一收口到 `PromptParts`。

### 4.3 收敛 goal 的特殊注入

- `goalMount.prompt` 的 `appendNewUser`（goal 模式下 turn 0 追加新 user 消息）保留（那是"目标就是用户指令"的语义）；但 `<goal_context>` 的**例行续写提示**移入 system prompt 动态段，不再每轮挂在 tool_result 上。
- `withGoalContext` 的历史 graft 分支保留为**遗留兼容**（仅 goal 首次挂载），不再用于每轮注入。

### 4.4 验收

- 抓取一轮请求体（或代理侧日志）：同一个 run 的连续 turn，**历史最后一条 user 消息只含工具结果**；过程块只出现在 system prompt 尾部一次。
- 对比 system prompt 长度：注入前后 stablePrefixLen 不变，dynamic tail 只含当前块（无重复）。
- 回归测试：`withGoalContext` 的 5 块+goal 注入单测迁移为 `PromptParts` 的动态段测试；断言同一 run 两次构建（内容未变）产出**字节相同**的 tail。

---


## 五、影响面与风险

| 改动 | 风险 | 缓解 |
|---|---|---|
| 注入位置迁移 | 模型可能依赖"过程块在用户消息里"的位置（历史中就是那样训练的）；迁移后需验证 plan 跟随行为 | 先在 debug 构建中 A/B（同一会话两种模式对比 plan 完成率） |
| 每轮重建 dynamic tail | 可能破坏 prompt 缓存（tail 变化） | head 严格不变、tail 只在内容变化时变；保持 `systemPromptStablePrefixLen` |
| 移除 history graft | goal 首挂载/appendNewUser 语义 | 保留 goal 首挂载路径，仅去掉例行块 |

---

## 六、P2 并行工具调用（已实施）

轮次爆炸（样本 1289 轮 × 每轮恰好 1 个工具）的另一半原因是模型不敢批量发调用：调度器
（`[T-parallel-tool-calls]` / `ToolCallLanes`）早已支持"只读并发、写屏障串行"，但系统提示
从未告诉模型这件事。

本次落地：

1. **能力声明（不是约束）**：系统提示新增 `Parallel tool calls (capability, not advice)` 段，
   明确"host 能并发执行同一响应里的多个独立调用"，并点名 parallel-safe 工具
   （file_read/list_dir/grep/glob/web_fetch/web_search/read_image/ocr_image/memory_get/
   search_sessions/read_session/code_graph/get_screen_time）与 serial barriers
   （file_write/file_edit/multi_edit/shell_execute/spawn_agent/ask_user_question/cronjob）。
2. **与真实 lane 表的一致性守护**：`ParallelToolsPromptTest` 用 `ToolCallLanes.laneFor()`
   逐条核对提示里声明的工具——声明为并行却实际是 BARRIER（或反之）会直接测试失败，
   防止提示与调度器漂移。
3. **预期收益**：独立只读侦察类任务（读多文件、并行检索）轮次可降 30–50%，总耗时
   约降 20–40%；链式任务（clone→build→test）受依赖限制收益 < 15%。工具执行耗时本身不变，
   省下的是模型往返。


## 七、对抗复审记录（2026-11-12）

对未推送的四笔提交做了一次对抗审查，发现并修复三处真问题：

1. **goal 上下文双份**（c5f4da04 引入）：首挂载轮把 `<goal_context>` 追加成 user 消息，
   而 tail 从第二轮起又携带同一份 → turn≥1 的请求里 objective 出现两次。修复：删除
   `withGoalFirstMount`，goal 上下文**只走系统提示 tail**（每轮一份），历史不追加。
   架构测试改为守护"first-mount 不得回归"。
2. **sanitizeAgentHistory 仍在制造"工具失败"**（既有缺陷，与本轮修复矛盾）：它对每个
   无结果的 `tool_use` 注入 `"Tool execution was interrupted by an unexpected error."`
   占位结果——模型读到"调用失败"就重发同一调用，正是"重复且不干活"的另一条路径，且
   与 dropOrphanedToolParts 的"成对移除"策略相反。修复：改为**移除孤儿的 tool_use**
   （保留同条 assistant 的文本），并把配对扫描从"仅下一条消息"扩到"剩余整段历史"
   （compaction divider 是 system 消息，只查下一条会误判正确配对为孤儿）。
3. **导出未剥离 system-reminder**（4b005d2b 的 P0.4 缺口）：断流续写提醒
   （`<system-reminder>The connection dropped…`）会作为正文进入导出。修复：
   `stripSystemReminders` 在 `extractMarkdownBody` 的 text 分支剥离，提醒仍保留在 DB
   供 resume 使用。

已知风险（记录，不改代码）：`MIN_MAX_TOKENS = 4096` 在上下文剩余不足 4096 时可能大于
剩余窗口；多数 OpenAI 兼容网关不校验该组合（截断而非报错），且此时 in-loop context
check 本应触发压缩。保留作为 reasoning 预算下限，若真机出现 provider 400 再收紧。

## 附录 A：UI/导出噪音（次要，首版内容）

- 首版量化的导出噪音（1289 工具块 / 1023 空段 / 658 stub）**不是**你说的噪音，但属于次生问题；其中导出静默转录（P0）已实施，并按同一份真实导出**回放模拟**验证：块数 3192→1415（−56%）、字符 −29%、空段 0。（模拟脚本按导出块序列套用同一规则；真机导出需设备侧再确认一次。）
- UI 侧已有 `ChatRound` 折叠（运行中展开、完成折叠、保留最新 2 步、≥2 步 run 合并）；缺口是"单步 run 不折叠"，列在 P1。

## 附录 B：首版已确认有效的修复（与本主题相关）

- 压缩尾部按完整轮次边界切分（`safeTailStart`）+ 孤儿 `tool_use` 成对移除；
- 召回块在压缩逐出后只重播一次（`lastRecallCarrierReplayed`）；
- 导出器解析外置正文（不再显示 `body stored`）。

---

### 参考

- [openai/codex #41601 progress-first TUI with collapsible tool output](https://github.com/openai/codex/issues/41601)
- [openai/codex #36845 final-answer-only / quiet transcript mode](https://github.com/openai/codex/issues/36845)
- [anomalyco/opencode #7260 compact tool output with click-to-expand](https://github.com/anomalyco/opencode/pull/7260)
- [anomalyco/opencode #32152 collapse fragmented reasoning parts and strip thinking echo](https://github.com/anomalyco/opencode/pull/32152)
- [DeepWiki: codex Status Line and Footer Rendering](https://deepwiki.com/openai/codex/4.1.4-status-line-and-footer-rendering)
