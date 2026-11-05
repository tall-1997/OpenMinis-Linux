# 对照 dsh-superagent-prototype 的采纳建议报告

> 参考：`deepseek-ai/dsh-superagent-prototype`（TypeScript，236 文件，18.9k stars，2026-11-03 最后推送）；本地副本 `D:\refs\dsh-superagent-prototype`
> 对象：OpenMinis-Linux `main@09fe9dd4`
> 性质：建议报告。本文件不改代码，只给结论、映射与优先级。
>
> **结构**：第一部分（一～七节）单 agent 的约束与记忆；第二部分（九～十三节）多智能体与 AI 群聊。

---

## 一、原型是什么

一个"自主智能体运行时"的完整原型：把**外部事件流**（Feishu / GitHub）变成**授权的工作单元**，派给**执行器**，再把结果写回原会话。它由九篇编号文档串起来（哲学 → 域模型 → 授权 → 执行 → 提示词 → 面板 → 部署 → 架构 → 手册），代码与文档一一对应。

它回答的核心问题不是"怎么让模型更聪明"，而是：**怎么让一个会跑很久、会跑偏、会忘记自己为什么在做这件事的智能体，行为可被约束、状态可被检查、结论可被信任。**

这正是我们过去几轮在 OpenMinis 里反复补的东西。区别在于：我们每次是"遇到问题补一个机制"，它是"先把概念定死，机制自然长出来"。

## 二、原型模块清单

| 目录 | 主要文件（大小） | 职责 |
|---|---|---|
| `src/core` | `tickets.ts` 26.8K、`evals.ts` 21.6K、`model.ts` 19.6K、`engine.ts` 16.2K、`assignments.ts` 8.9K、`ports.ts` 7.2K、`replies.ts` 6.7K、`intake.ts` 3.8K、`memory.ts` 2.8K | 域模型与状态机：Ticket 生命周期、Eval 两层判定、Assignment 绑定、端口抽象（存储/时钟/执行器） |
| `src/app` | `migration.ts` 45.7K、`sessions.ts` 23.9K、`resources.ts` 21.7K、`store.ts` 20.9K、`assessment.ts` 20.6K、`prompts.ts` 19.2K、`settings.ts` 17.5K、`runtime.ts` 17.2K、`http.ts` 16K、`effects.ts` 9.9K、`cards.ts` 7.4K、`delivery.ts` 4.6K、`passes.ts` 3.1K | 装配层：会话管理、提示词装配、设置与版本、HTTP/面板 API、投递与卡片、定时 pass |
| `src/app/tools` | `main.ts` 20.2K、`executor.ts` 7K、`tool.ts` 2K | 主 Session 的编排工具（dispatch/ack/handback/report 等）与执行器工具 |
| `src/sources` | `feishu*.ts`、`github-channel.ts` | 事件源实现：读位点、去重、线程归属、频道地址 |
| `src/executor` | `dsh.ts` 47.2K、`background.ts` 9.2K | 执行器：Session 生命周期、后台作业、唤醒 |
| `src/infra` | `commands.ts`、`environment.ts`、`health.ts`、`text.ts` | 基础设施：命令白/黑名单、执行环境变量、健康状态 |
| `docs/` | `000`–`008`（183K） | 设计契约，按顺序阅读；`007-architecture.md` 70K 是主体 |
| `prompts/` | `work.md`、`dispatch.md`、`admission.md`、`replies.md`、`shared.md`、`orchestrator.md`、`worker.md`、`handoff*.md`、`subscriptions.md`、`cli.md` | 内置提示词部件（可被团队覆盖，见 `004-prompts.md`） |
| `tests/` | 65 文件 1.06M | 与域模型同构的测试 |

## 三、最有价值的五个设计（与我们痛点直接对应）

### 1. Ticket / Mandate：把"在做什么、凭什么能做"变成数据

`001-domain.md` 定义了 Ticket（授权的工作单元）与 Mandate（授权内容：policy 版本、scope、strength、匹配到的规则、发起人身份）。

三条硬规则：
- **Ticket 定义自己的状态，不跟随执行器的状态**（`001-domain.md:35`）。执行器换了、Session 断了，Ticket 还在。
- **Mandate 不可变；只有人的"停止"能撤回**（`:34`）。规则变了不吊销既有授权，而是把变更**投递**给 Assignment。
- **结束后恢复也要重新校验当前 Policy**（`:34`）。

→ 对照我们：Goal 只是"会话里的一个字段 + 每轮注入的一段文本"，一旦压缩或换执行路径就失去锚点。**Ticket + Mandate 是我们缺的那层"独立于对话历史的授权对象"。**

### 2. 结构化 Report：让"进度"成为枚举而不是散文

`001-domain.md:112` 定死了 Report 的取值：`progress / waiting / ready / blocked / failed / dropped`，并配了 **Ticket stage 推导表**（`003-execution.md:7-18`）：先用 report 状态、再用是否有 Attention、是否有 Assignment，自上而下第一行命中即定档。

反面的设计也被明确拒绝：*"Inferring 'waiting on a person' from a report's free text"* 被列入 Rejected（`001-domain.md`）——因为从自由文本推断状态会错。

→ 对照我们：模型"跑偏了自己不知道"，根因就是**它没有一个必须填的状态字段**。我们靠计划板显示步骤，但"这一步是完成、是卡住、还是在等人"仍是散文。这是投入产出比最高的一条。

### 3. Attention（待人工决定）与"一次只问一个具体问题"

`005-panel.md` 的第一问是"现在有什么需要我？"，面板第一屏只列这类条目；`004-prompts.md:84` 规定：执行器把**一个具体问题**放进 Report 的 `human_action` 字段，主 Session **原样转发**，不重述、不改写。

→ 对照我们：模型把问题埋在长回复里，用户经常没看见；我们的 Resume 提示是"上次被中断"，不是"现在需要你回答什么"。

### 4. Eval 两层：Gate（确定性）+ Assessment（模型）

`001-domain.md:90-91`：Gate 是确定性的第一层（谁可以发起工作、强度多大），Assessment 才是模型判断。`004-prompts.md:67` 给了五级优先级阶梯，第一级是"工具与宿主强制的事实"，**提示词改不动它**。

→ 对照我们：我们的"防跑偏"大量依赖系统提示词与 envelope 文本，属于第 2–4 级；**第 1 级（代码强制）只有零星的失败闸门**。这是方向性差异。

### 5. Prompt parts + 版本 + 覆盖语义

`004-prompts.md:18-42`：提示词拆成有 key 的部件（`runtime/work`、`runtime/policy`…），团队只能覆盖列出的部件；每次保存生成不可变版本（含 revision、predecessor、时间、来源）；覆盖记录"基于哪个内置版本"，内置更新时面板标 **Default updated** 并给出差异，团队选择保留或采用；模板必须保留占位符；**永不静默截断**。

→ 对照我们：已有 `InstructionState` 的 observe/delta/seq 事件流（很好的底座），但缺"部件"这一层与"默认已更新"的交互。这是把我们已有快照升级成可运营提示词系统的路径。

## 四、模块对照与建议（复用 / 复刻 / 跳过）

| 原型模块 | 本项目现状 | 建议 | 理由 |
|---|---|---|---|
| Ticket + Mandate（`core/tickets.ts`, `model.ts`） | `SessionGoalEntity` + `GoalStateMachine`（objective/status/tokenBudget/blockedCondition） | **复刻（最小版）**：Goal 升级为 Task，增加 `scope`、`strength`、`policySeq`（指向指令快照 seq）、`endedAt` | 我们已有快照 seq 与 goal 状态机，缺的是"授权绑定到哪一版规则" |
| Report 枚举 + stage 推导（`001-domain.md:112`, `003-execution.md:7`） | 无。进度靠自由文本 + 计划板 | **复刻**：新增 `report` 工具 + 持久化 + 会话级 stage 推导 | 直接治"跑偏不自知"，且实现成本低（一张表 + 一个工具） |
| Attention / human_action（`004-prompts.md:84`） | 无结构化字段 | **复刻**：Report 内嵌 `question`，UI 单列"待你回答" | 与上条同源，边际成本极小 |
| Eval Gate 确定性准入（`evals.ts`） | `ToolFailureMemory` 硬闸门、`SecurityGateHolder` | **复用思路**：把闸门从"工具失败"扩到"任务准入"（无 Mandate 不建 Task、超 scope 拒绝） | 我们已验证过"代码强制比提示词可靠" |
| 五级优先级阶梯（`004-prompts.md:65-71`） | `MemoryAuthority` envelope（use_as/instruction_authority/trust/origin） | **扩展**：把三档扩成五档，明确"工具强制 > 内置 > 规则 > 团队 > 外部文本" | 现有 envelope 只覆盖记忆片段，阶梯可覆盖全部注入 |
| Prompt parts + 版本（`004-prompts.md:18-42, 91-96`） | `InstructionState` + `InstructionStateStore` + `InstructionReplay` | **增强**：在快照之上加"部件 key + 覆盖 + 默认已更新" | 底座已具备，缺的是产品化那层 |
| Subscription / fan-out（`001-domain.md:37`） | 无。会话续接靠用户再发消息 | **部分复用**：会话内"同一任务后续消息自动归入该 Task" | 我们只有单一线程，语义可简化 |
| Disposition 幂等（`core/tickets.ts`） | 无。重试/重复消息可能重复执行 | **复刻（轻）**：同 trigger 指纹只建一个 Task | 与我们已有的 `ToolFailureMemory` 指纹机制同构 |
| Source / Trigger / Channel（`src/sources`） | 无（我们是聊天 App，非多源 ingress） | **跳过** | 域不同，照搬会引入一个我们不存在的抽象层 |
| Executor 目录 / PR 交付 / prepare 命令（`003-execution.md`） | `SubAgentRunner` + 沙箱工作区 | **跳过流程，复用"后台作业唤醒"** | 目录/PR 是我们不需要的交付形态 |
| Policy 的 Source 授权表 | 无 | **跳过** | 单用户设备，无"谁能发起"问题 |
| `MemoryRepositories.atomic()`（`core/memory.ts:16`） | Room `withTransaction` | **参考**：多表写统一走事务封装 | 我们已有一部分，可收敛为统一入口 |
| 面板三问 + "健康状态不占空间"（`005-panel.md`） | 记忆页 / 计划卡 / 会话列表 | **复用原则**：我们刚做的记忆页统计块即此思路 | 可直接沿用其 7 条面板原则 |

## 五、建议的执行顺序（按性价比）

### P0 — 治已确认的三个症状（改动小、收益直接）

1. **`report` 工具 + 状态持久化**（新文件 `tools/ReportTool.kt` + `data/report/TaskReportStore.kt`）
   字段：`status ∈ {progress,waiting,ready,blocked,failed,dropped}`、`summary`、`blocker`、`question`、`evidence`。
   会话级推导 stage（复用 `003-execution.md:7` 的表），UI 在会话头部显示当前 stage。
   **验收**：单测固定 stage 推导表；长会话中 stage 变化可见。

2. **Attention 列表**
   `question` 非空即生成一条"待你回答"，进入会话顶部与 Resume 判定；回答后清除。
   **验收**：未回答时 Resume 提示改为"需要你回答：<问题>"。

3. **Task 最小版（Goal 升级）**
   `SessionGoalEntity` 增加 `scope`、`strength`、`policySeq`；创建时绑定当前指令快照 `throughSeq`；规则变更时**投递变更**而不是吊销。
   **验收**：快照 seq 与 Task 的 policySeq 可对账；跨规则变更后 Task 仍有效。

### P1 — 让"约束"从提示词挪到代码

4. **五级优先级阶梯**
   把 `MemoryAuthority` 的 use_as/instruction_authority 扩成五档，并在注入装配处显式标注档位。
   **验收**：单测断言每类片段落在预期档位；工具强制项不可被任何注入覆盖。

5. **任务准入闸门**
   无 Mandate 不建 Task；执行工具前校验 scope；同 trigger 指纹幂等。
   **验收**：越界调用被代码拒绝（不是提示词劝阻）。

6. **提示词部件化 + 覆盖版本**
   在现有快照上引入 part key、覆盖记录 base revision、"默认已更新"状态。
   **验收**：改内置默认后，覆盖仍保留并提示差异。

### P2 — 结构性增强

7. **后台作业唤醒**：作业结束唤醒 turn，替代 `delay` 轮询（我们目前在提示词里教模型轮询）。
8. **多表写事务收敛**：参考 `atomic()`，统一走一个入口。
9. **面板三问**：会话列表/记忆页按"需要我 → 是否健康 → 历史"排序。

## 六、明确不建议照搬

- **Source/Trigger/Channel 抽象**：我们没有多源 ingress，引入等于凭空造抽象。
- **English-first 提示词策略**：我们的 UI 与提示词是中文语境，照搬会割裂。
- **Executor 目录 + PR 交付 + prepare 命令**：这是 CI agent 的交付形态，不是手机端 agent 的。
- **Policy 的"来源授权表"**：单用户设备上没有"谁能发起工作"的问题。
- **完整 Ticket 状态机**：我们只有单线程会话，先做"最小版 Task"，别一上来就上 4 态 + Assignment + Subscription。

## 七、与我们已有工作的关系（重要）

上一轮我们已经落地了不少与原型同构的机制，**这次是补差，不是重写**：

| 原型概念 | 我们的等价物（已存在） |
|---|---|
| Policy version | `InstructionState` 的 `epochStartSeq` / `throughSeq` |
| Gate（确定性） | `ToolFailureMemory` 硬闸门 + `SecurityGateHolder` |
| 优先级阶梯（第 5 级"外部文本是数据"） | `MemoryAuthority` envelope |
| 计划/待办 | `AgentPlanStore` + 每轮注入 |
| 经验回流 | `ProjectExperienceRepository`、`MemorySediment` |
| 可复现的"模型看到了什么" | `InstructionReplay` + `[XSessionDiag]` 日志 |

所以 P0 的三条都能在现有结构上加，不需要迁移或推翻。

## 八、参考索引

- 域模型与状态机：`docs/001-domain.md`、`docs/003-execution.md:7-18`
- Report 取值：`docs/001-domain.md:112`
- 优先级阶梯：`docs/004-prompts.md:65-71`
- 提示词部件与版本：`docs/004-prompts.md:18-42`、`:91-96`
- Attention 与一次一问：`docs/004-prompts.md:84`
- 面板三问与原则：`docs/005-panel.md`
- 执行器后台作业语义：`docs/003-execution.md:31-47`
- 原子回滚实现：`src/core/memory.ts:16-24`

---

# 第二部分：多智能体与 AI 群聊

> 前七节讨论的是"单个 agent 如何不跑偏"。本节讨论"多个 agent 之间如何不互相污染、不重复劳动、不越权"，以及我们的 AI 群聊该向它学什么。

## 九、原型怎么组织多智能体

原型只有两种角色，但把两者的边界写死到了代码层：

| 维度 | Orchestrator（主 Session） | Worker（执行器） |
|---|---|---|
| 职责 | 只做评估与路由（`prompts/orchestrator.md:1-2`） | 只做调查与实现（`prompts/worker.md`） |
| 明令禁止 | 不做实现计划、不读代码细节、不自己调查（`dispatch.md:14,40-42`） | 不做路由、不改派、不替别人回消息（`work.md:8-9`） |
| 工具集 | `dsh-superagent-router`，**更小的工具集**（`003-execution.md:29`） | `dsh-superagent-worker`，工作工具 |
| 模型 | 各自可配（`role-models.ts`） | 各自可配（`role-models.ts`） |

五条值得抄的机制：

**1. 提示词层面的"不许越界"写得极具体**
`dispatch.md:38` 直接给了反例："Could we add a dark mode?" 必须以原请求 + 来源引用到达 worker，**不许**路由器自行补配色方案或维护计划。`work.md:3-5` 对 worker 也给反例：局部 CSS 修改不等于"视觉影响很小"，一条间距缺陷不授权顺手重构。

**2. 路由四态 + 幂等**
`dispatch.md:6`：`accept / attach / stop / ignore`，一条候选一个决定。`dispatch.md:46`：**accept 按 trigger ID 幂等**。`dispatch.md:47-49`：附加的 routing note 最多 30 词，只能写路由背景或人的显式约束，**不能写实现指令**，且宿主始终独立附带原始 trigger 引用。

**3. Handoff 契约：决策请求 ≠ 进度汇报**
`prompts/handoff.md`：

- 来自 ticket 主人的**具体决策**走结构化 attention 请求，不是日常进度
- 问题要回到**最初提出它的那个会话**（`dispatch.md:19`）
- 日常进度、模型重试、预算告警都**不构成**发消息的理由（`handoff.md:4`）
- 空 final 不许把报告内容再复述一遍（`handoff.md:6`）

**4. 子代理继承权限但收窄工具**
`003-execution.md:47`：worker 的子代理用 `standard` preset，**继承 worker 的权限、只保留 worker 被允许的工作工具、ticket 结束即失去这些工具**；子代理的作业随子代理结束而被杀；嵌套子代理同理。

**5. 并发留位与压缩后查重**
- `003-execution.md:59`：并发上限之外**额外 1 个槽位**专门给人的消息或状态查询——"新工作不能阻止人继续既有 ticket"。
- `dispatch.md:44`：压缩之后要核对 ticket 摘要，"避免重复劳动"。

## 十、我们现在的多智能体实现

已经相当完整，缺的是边界与契约：

| 能力 | 我们的实现 |
|---|---|
| 子代理种类 | `SubAgentKind`：`worker` / `explore` / `plan` / `general-purpose` |
| 工具收窄 | explore/plan 用 `READ_ONLY_ALLOW` 白名单；`ALWAYS_DENY` 禁嵌套 spawn、check_agent、ask_user、cron、dispatch、wolfpack |
| 轮次预算 | `SIMPLE=10 / MEDIUM=30 / COMPLEX=40 / COMPLEX_MAX=60` + `ABSOLUTE_MAX_TURNS` 防跑飞 |
| 写路径隔离 | `write_paths` 声明 + `WritePathGuard`（同批次 ≥2 worker 时） |
| 批次与泳道 | `SubAgentBatchRegistry`、`SubAgentLane`、`SubAgentTokenBudget`、`SubAgentHistoryCompactor`、`SubAgentBrief`、`SubAgentTypeStore` |
| 主管席位 | `SupervisorSeats`、`SupervisorSessionRunner`、`SupervisorPrompts`、`SupervisorStatusBar` |
| 编排工具 | `spawn_agent` / `run_subagent` / `check_agent` / `dispatch_agents` / `wolfpack_run` |
| 并发与模型 | `MultiAgentSettingsRepository`：`maxConcurrent`、`workerModelEntryId`、`supervisorMaxWorkerTurns`、`selectedModelEntryIds`、thinking levels |

## 十一、多智能体的差距与建议

| 编号 | 差距 | 原型依据 | 建议落地 | 优先级 |
|---|---|---|---|---|
| **G1** | 用户消息可能排在 N 个 worker 后面 | `003-execution.md:59` 预留 1 槽 | `MultiAgentSettings.maxConcurrent` 之外单独留一个"人"的槽；人发起的轮次优先占位 | P0 |
| **G2** | `dispatch_agents` 无幂等 | `dispatch.md:46` | 批次指纹（排序后的 task 哈希）写入 `SubAgentBatchRegistry`，重复派发直接返回已存在批次 | P0 |
| **G3** | 角色间无结构化 handoff | `handoff.md` 全文 | 与第一部分 P0 的 `report` 合并：worker 的 `question` 必须带"原始提问会话 id"，由宿主投递回原处 | P0 |
| **G4** | 子代理"越界"只有写路径约束，没有需求范围约束 | `work.md:3-6`、`dispatch.md:14` | 子代理 brief 必带 `mandateId`（来自 P0 的 Task）；`SubAgentRunner` 拒绝无 mandate 的 spawn；brief 中新增 `out_of_scope` 字段，命中即要求 `report dropped` | P1 |
| **G5** | 压缩后无跨泳道查重 | `dispatch.md:44` | 压缩事件触发时，把在跑泳道的 brief 摘要重新注入，并对新 spawn 的 task 与既有 brief 做词重叠检测，超阈值告警 | P2 |
| **G6** | 后台作业结束不唤醒 | `003-execution.md:33` | 与第一部分 P2 的作业唤醒同源，多智能体侧表现为"worker 必须轮询 check_agent" | P2 |

**不建议照搬**：`Ticket → Assignment → Subscription` 的三层派发（我们只有单会话内的泳道，引入订阅/扇出等于凭空造抽象）；worker 目录与 PR 交付流程。

## 十二、AI 群聊该学什么

严格说原型**没有群聊**——它只有一个 orchestrator 对多个 worker，worker 之间不对话。但它有三条可直接迁移的"多主体协作"规则，恰好是我们群聊缺的：

**G7｜发言权要可校验，不能只靠提示词**
我们的 `GroupChat` 已经写了关键约束：*"Each model speaks as itself. The host only frames the question and writes the closing report; it does not impersonate the others."* 但这条目前是**提示词约束**。原型的做法是把"谁能说什么"做成工具集与身份校验（router 无实现工具、worker 无路由工具）。建议：群聊每一轮由宿主指定 `speaker`，产出消息的 `speaker` 字段与当轮指派不符即拒绝入库。

**G8｜收敛判据要显式，而不是聊到没人说话**
原型用 "stop by impact, not by effort"（`000-philosophy.md:14`）。群聊需要同样显式的停止条件：最大轮数、连续 N 轮无新增论据、出现 `propose_close` 即收敛。目前 `DiscussionGraph` 有图结构，但没有"何时停"的结构化判据。

**G9｜分歧要变成"待人工裁决"，而不是继续互相说服**
这直接复用 P0 的 Attention：两个模型给出不同方案时，生成一条 `attention{options:[{label, rationale, dissent}]}`，交给人选；而不是让它们再聊三轮。原型里对应的是"决策请求走结构化 attention，回到最初提问的会话"（`handoff.md:1`、`dispatch.md:19`）。

**G10｜每轮产出结构化记录，而不是纯文本**
建议把 `GroupChat.Line` 升级为 `TurnRecord{speaker, claim, evidence, objections, decision}` 并持久化进 `DiscussionGraph`。这样"最后为什么选了这个方案"可回溯，也才能做 G8 的"无新增论据"判定。

## 十三、多智能体与群聊的优先级汇总

| 优先级 | 项目 | 理由 |
|---|---|---|
| **P0** | G2 dispatch 幂等 | 一个重试就能把 3 个 worker 变成 6 个，成本最低、风险最高 |
| **P0** | G1 人的槽位 | "新工作不能挡住人"，一行准入判断 |
| **P0** | G3 结构化 handoff | 与 report 同一次改动 |
| **P1** | G4 mandate 绑定 + 越界即 stop | 治子代理自行扩范围 |
| **P1** | G7 发言权校验 | 群聊可信度的底线 |
| **P2** | G10 结构化发言记录 | G8/G9 的前置数据 |
| **P2** | G8 收敛判据、G9 分歧转裁决 | 依赖 G10 |
| **P2** | G5 压缩查重、G6 作业唤醒 | 与第一部分 P2 同源，可合并做 |

**一句话总结**：原型的多智能体价值不在"更多 agent"，而在**把 agent 之间的边界写成代码**——谁有什么工具、谁不许做什么、一个问题回到哪里、重复请求如何幂等。我们的泳道、主管席位、群聊骨架都已经在了，缺的正是这层边界。

---

# 第三部分：落地状态（持续更新）

> 每次合入后回填。已落地写 PR 与合并提交；判定不适用写理由，不留悬空项。

## 已落地

| 报告条目 | 落地内容 | PR | 合并提交 |
|---|---|---|---|
| P0 report 工具 | `agent_report` 六态 + stage 推导 + 每轮注入 | #241 | `fca5e8af` |
| G2 dispatch 幂等 | 三入口统一按波次指纹、10 分钟窗口 | #241 | `fca5e8af` |
| G5 压缩查重 | `renderInFlight` 每轮重渲染未完成泳道 | #242 | `f3043258` |
| G9/G10 分歧转裁决 | `DiscussionOutcome` 合成单问题写入 report | #243 | `da21bd7f` |
| P1-4 五级阶梯 | `PromptLevel` + EXTERNAL 框定与规则行 | #244 | `5fd167e8` |
| P0 Task 最小版 + 准入闸门 | `task_mandate` 声明边界，越界派发由宿主拒绝 | #245 | `711f09f2` |
| P1-6 部件化（注册表半边） | `PromptPart`/`PromptParts` 统一顺序与层级 | #246 | `b7c9dee1` |
| P2/G6 作业可见 | `runtime/jobs` 部件，明确不轮询 | #247 | `7ff5d474` |
| P0 Attention（判定 + 接线） | `WorkStageBannerModel` 决定横幅与详情行；`WorkStageBanner` 接入消息列表 | #250 / #251 | `97f81a6e` / `517efdf0` |

## 判定为已存在或不适用

| 条目 | 结论 | 依据 |
|---|---|---|
| G7 发言权校验 | **已由结构保证** | `GroupChat.Line` 的 speaker 由宿主用成员名写入，模型无法自称他人 |
| G8 收敛判据 | **已存在** | `DiscussionGraph` 七阶段 + `MAX_REVISE=1`，`afterRevote()` 强制进入 MINUTES |
| G10 结构化发言记录 | **已存在** | `DiscussionGraph.Statement(role, phase, body, verdict, objection, ask)` |
| G1 人的槽位 | **不适用** | 我们的并发上限是"群聊模型席位"（`MultiAgentSettings.maxConcurrent`），不是 worker 队列；用户消息不经该队列，不会被 worker 挡住 |

## 仍未落地

| 条目 | 缺口 | 前置条件 |
|---|---|---|
| ~~Attention UI~~ | **已完成**：横幅已接入，无新增字符串（详情用模型原话） | — |
| 部件覆盖与版本 | 只有注册表，没有"团队改某块 / 记录基于哪个版本 / 默认已更新" | 需与 `InstructionState` 快照联动 |
| 事务收敛 | 多表写仍各自为政 | 需一次写路径审计 |
| 面板三问 | 会话列表/记忆页未按"需要我 → 是否健康 → 历史"排序 | UI |
| 作业事件唤醒 | 现为提示层可见，不是事件驱动唤醒 loop | 需改 agent loop 的挂起/恢复 |
## 并行线兼容性核对（2026-11-05 晚）

远端 main 同期出现两条不属于本次采纳工作的提交：

- `057181a2` #249 fix(providers): Gemini usage stats — cache hit rate + 3.x thinking tokens
- `81f88319` #252 看板噪声预算: 紧凑回执 + 快照瘦身 + 骑乘换燃料 + 冻结快照退役

其中 #252 直接改 `boardSnapshotPrompt` 与 `planBoardPrompt`，与本次落地的 `PromptParts` 相邻。核对结果：

- `:app:compileReleaseUnitTestKotlin` 通过
- `com.openminis.app.ui.chat.*` 与 `com.openminis.app.tools.*` 用例全部通过

结论：两条线目前兼容；`PromptParts` 只依赖 `planBoardPrompt()` 这个入口，不关心它内部从 `render()` 换成 `boardSnapshotPrompt()`。后续若 #252 继续调整该函数签名，需同步 `PromptParts.of()`。
## 剩余四项：为什么没做，需要什么

这四项不是"还没轮到"，而是各有具体阻碍。写在这里，避免下一轮重复判断。

### 1. 部件覆盖与版本 —— 调研结论：已由现有机制覆盖，不做

**缺口**：部件只有 key 与层级，没有"团队改过这一块 / 基于哪一版 / 内置更新时提示默认已更新"。

**阻碍**：覆盖语义必须与 `InstructionState` 的快照版本对齐，否则会出现"提示词按第 7 版渲染，快照记第 9 版"的分裂。需要先确认快照的 `epochStartSeq`/`throughSeq` 能否直接充当部件的 base revision。

**调研结论（2026-11-05）**：不做，已有机制覆盖。

- `InstructionStateStore` 以「初始值 + 追加事件」记录每会话的指令值，`epochStartSeq`/`throughSeq` 就是版本游标；
- `InstructionReplay.rebuild(initialValuesJson, updates)` 能把任意 commit N 的值集**重放**出来，即"第 N 版时模型看到了什么"已经可回答；
- 原型的「部件覆盖 + base revision + 默认已更新」存在的前提是**团队在产品内编辑提示词块**；我们的提示词块分两类：
  - 代码定义的运行时块（work_report / in_flight / jobs / plan_board / recall）——随应用版本走，没有"用户覆盖"，也就没有"基于旧默认"的问题；
  - 用户撰写的指令文件（GLOBAL.md / AGENTS.md）——已由上面的事件流 + 重放覆盖。

因此**唯一的残余差异**是：我们没有"用户改过的默认块需要提示更新"这种交互，因为我们根本没有可覆盖的默认块。将来若真要让用户在应用内改提示词块，再引入 part 版本即可，届时可直接复用 `throughSeq`。

### 2. 事务收敛 —— 调研结论：已在关键路径上收敛，不做统一封装

**缺口**：多表写没有统一事务入口。

**阻碍**：目前**没有找到一个真实存在的多表写不一致案例**。在没有失败样本的情况下加 `withTransaction` 包装，产出的是形式统一而不是正确性。

**调研结论（2026-11-05）**：不做统一封装，因为关键路径已经是事务的。

全仓库 `withTransaction` 的调用点：

| 位置 | 覆盖的写 |
|---|---|
| `InstructionStateStore.kt:46` | 冻结指令快照 + 事件追加 |
| `ChatRepository.kt:455` | `deleteSession`：删消息 + 删目标 + 删会话（三表） |
| `ChatRepository.kt:538` | 会话恢复类写入 |
| `ChatRepository.kt:732` | 备份恢复的一批写 |
| `ProviderBackupCoordinator.kt:447` | Provider 备份写入 |

`deleteSession` 就是最典型的多表写，**已经是** `dbRef.withTransaction { deleteMessages; goalDao.delete; deleteSession }`，并且保留了无数据库句柄时退化为顺序写的测试路径。

结论：没有发现**未包裹的多表写**。再往上加一层统一入口，收益只是形式一致；真正的价值在于"新增多表写时记得用事务"，那属于代码评审约定，不是运行时机制。若将来出现跨表不一致的线上问题，再按那一例对症收敛。

**触发条件**：出现"删了会话但消息残留"/"恢复只成功一半"这类可复现的不一致。

### 3. 面板三问

**缺口**：会话列表没有把"需要你回答"排到最前。

**阻碍**：`SessionListScreen`（154K，基线 3252 行）已经在行数上限附近；且排序需要按会话读 `AgentReportStore`，属于文件 IO，不能放在 Compose 组合期同步做——需要 ViewModel 侧预取 + Flow 合并。

**建议**：在 `SessionListViewModel` 增加一个"注意力集合"Flow（会话 id -> stage），列表按 `attention > pinned > updatedAt` 排序。改动集中在 ViewModel，Screen 只消费排序结果。

### 4. 作业事件唤醒 —— 已落地提示层，唤醒本身待产品决策

**缺口**：作业结束时不会唤醒 agent loop，只靠提示词告诉模型别轮询。

**阻碍**：这要改 agent loop 的挂起/恢复语义（作业完成 -> 投递一条内部消息 -> 唤醒同一 run）。风险最高，且与并行的 #252 一直在动的 run 生命周期相邻。

**已落地（T-job-awareness，PR #247）**：作业状态每轮从 `JobRegistry` 渲染进 `<jobs>` 块，模型不必轮询就能看到谁在跑、谁跑完了、退出码多少。

**未做的部分**：作业结束时**自动开一轮**。这是产品决策而不是技术债，三个选项：

| 方案 | 行为 | 代价 |
|---|---|---|
| 1 不做 | 作业列在每轮请求里，下一轮可见 | 无 |
| 2 只插入提示 | 结束时记一条事件，但不启动新轮次 | 几乎无 |
| 3 真唤醒 | 作业结束自动开一轮，Agent 继续干活 | 无人时消耗 token、可能主动发消息 |

**当前选择：方案 1 + 已有提示层**。理由：原型服务团队值班场景，自动唤醒是刚需；本项目是个人手机端，用户不在场时 Agent 自行开工并非期望行为，且会带来不可预期的 token 消耗。

**若将来要做方案 3**，需要先回答：

- 唤醒与用户消息并发时谁优先？
- 无人响应时最多自动开几轮？
- 是否需要"仅通知不出话"的模式？

**触发条件**：用户明确要求"作业跑完自动继续"，或出现"作业完成但被遗忘"的实际投诉。