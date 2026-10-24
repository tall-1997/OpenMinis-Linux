# Supervisor-Worker 会话 · 设计文档（v2 · 模型互聊拓扑）

日期：2026-10-24（v2 重写，用户拍板：要像模型之间互相聊天）
状态：待用户批准 → 批准后写实施计划 → 本会话执行
目标版本：2.0.80

## 0. v1 → v2 为什么改

v1 是工具循环拓扑（`supervise_run` 一次调用 + verdict JSON 状态机）。用户否了，原话：

> 要就像模型之间互相聊天一样，贵模型负责给便宜模型下达指令，便宜模型干活，输出内容贵模型可以看到，有问题由贵模型或用户发送消息进行纠正

翻译成架构语言：**消息传递，不是工具调用**。指令、产出、纠正全是「消息」；贵模型看得到便宜模型的输出流；纠正来自贵模型**或用户**，都是发消息。循环不是迭代计数器，是**对话轮转**——聊到达标为止。

这个拓扑仓库里已经有直系血亲：**群聊模式**（`runGroupChat`：host + 多成员各自模型、逐个发言、气泡渲染、轮次锚点）。v2 = 群聊的一个特化变体，不是新物种。

## 1. 需求五事实（不变，拓扑换了）

1. **角色分离**：Supervisor（贵模型）与 Worker（便宜模型）是两个模型位。
2. **对话式验收**：Supervisor 看 Worker 的输出消息，发现问题就**发消息**纠正，Worker 改完再回消息，循环到达标。
3. **用户是第三发言者**：用户随时插话，纠正指令直接进对话流。
4. **Worker 真干活**：不是只出主意——有工具（文件读写、shell），产出落在沙箱里。
5. **成本可控**：贵模型只花在「下指令 + 复核」短回合，长执行全在便宜模型。

## 2. 现状盘点（复用面更大了）

| 能力 | 现有实现 | v2 用法 |
|---|---|---|
| 多模型互聊 | `ChatViewModelGroupChatExt.runGroupChat`：host（会话模型）+ 成员（各自 provider）逐轮发言 | **直接特化**：固定两席 = Supervisor(host) + Worker |
| 成员模型位 | `groupMembers`：槽位 id → 各自 `providerForModelEntry` | Worker 席钉到「Worker 默认模型」设置 |
| 气泡/轮次 UI | `upsertGroupBubble` / `anchorGroupRound` / 具名发言者 | 直接复用，Worker 气泡带工具活动 |
| 子代理工具面 | `SubAgentRunner`：写工具 + shell + `WritePathGuard` + turn 预算 | **嫁接**：Worker 席的工具面从这来（群聊现在是只读） |
| 自包含 brief | `SubAgentBrief` 五节信封 | Supervisor 下指令时套用（指令=brief，消息形态） |
| 中断/恢复 | 群聊 closing 语义 + `SubAgentBatchRegistry` 进程死亡恢复 | 轮次记录持久化沿用 |
| 看板联动 | `AgentPlanStore` | Supervisor 拆解时自动落板 |

**缺口（v2 要造的）：**

1. **群聊成员是只读的**：`groupTools` 过滤掉全部写工具 + shell。Worker 要真干活，需要一条「可写成员」路径（带 `WritePathGuard`，不能裸放开）。
2. **没有角色化模型位**：槽位是并发位。要「Worker 默认模型」一个设置位。
3. **轮转是轮询的，不是主从的**：群聊成员轮流说。Supervisor-Worker 要 host 驱动：host 发指令 → worker 干 → host 复核 → 再指令。轮转顺序要固定为 S→W→S→W，用户插话插在任意点。
4. **没有终止契约**：聊到达标——Supervisor 需要一个显式「收工」标记（仓库先例：`DiscussionGraph` 的 `VERDICT: ACCEPT` 行协议），加轮次硬顶防聊不完。
5. **用户插话的接线**：群聊里用户消息已有通道（host opening / 队列），要接到「下一轮 Supervisor 回应它」。

## 3. 架构（v2 · 对话拓扑）

### 3.1 总体形状

```
        用户（第三发言者，随时插话）
          │ 指令/纠正
          ▼
   ┌────────────── Supervisor（host 席 = 会话模型/贵）
   │  发指令（消息，套 brief 信封）
   │  看 Worker 输出流（完整可见）
   │  复核：继续纠正 或 宣布收工（DONE 标记）
   ▼
   ┌────────────── Worker（成员席 = 便宜模型，钉死）
   │  收指令 → 干活（写文件/跑 shell，WritePathGuard 守卫）
   │  产出 + 报告以消息回流，Supervisor 与用户全程可见
   └──────► 回到 Supervisor 复核 ──► 循环
```

**一次 Supervisor-Worker 会话 = 一段有界的双席群聊**。不是工具调用嵌在主 loop 里，是主对话之上的一层对话面。

### 3.2 会话生命周期

```
用户在普通聊天里发起（见 3.5 入口）
  → 开 Supervisor 会话：objective 定格（用户原话，全程回显）
  → Supervisor 第一轮：拆解 + 给 Worker 下第一条指令（可多任务并行，见 3.4）
  → Worker 轮：执行 + 回报（工具活动实时渲染在 Worker 气泡内）
  → Supervisor 轮：复核输出 →
       a) 有问题 → 发纠正消息（指出问题 + 期望）→ 回 Worker 轮
       b) 达标   → 输出 DONE 标记行 → 会话收束，产出摘要落回主聊天
  → 任意点用户插话 → 进队列 → Supervisor 下一轮先回应用户消息再继续
  → 轮次硬顶（缺省 50 轮 Worker 回合，可调 1..200）或用户点停 → 收束（带未完成清单）
```

**全程不阻塞主会话**（用户拍板 2026-10-24）：Supervisor 会话跑在自己的协程上（先例：`launchDetachedSubAgentBatch` 的 background 波次），主聊天的输入框、发送、模型对话完全可用——用户可以一边看着 Supervisor 会话干活，一边继续跟会话模型（贵模型）聊别的。两条对话流各自独立历史，互不污染。

### 3.3 双席定义（新文件 `SupervisorChat.kt`，纯逻辑可测）

```kotlin
data class SupervisorSeat(
    val role: String,            // "supervisor" / "worker"
    val modelEntryId: String?,   // worker 席来自设置位；supervisor 席 = 会话模型
    val tools: ToolSurface,      // worker = SubAgentRunner 级（可写+shell）；supervisor = 只读+派发
    val turnBudget: Int,         // worker 席每轮 turn 上限（复用 subagentMaxTurns）
)
```

- **Supervisor 工具面**：只读（file_read/grep/glob/code_graph…）——复核要看证据，但不亲手改（亲手改 = 角色越界，贵模型的时间不该花在打字上）。
- **Worker 工具面**：`SubAgentRunner` 全套（file_write/file_edit/shell_execute…），`WritePathGuard` 按 Supervisor 指令里声明的 `write_paths` 收紧。**Worker 不能 spawn 子代理**（嵌套禁止，沿用 `SubAgentLane` 标记）。

### 3.4 指令消息契约（Supervisor → Worker）

指令是**消息**，但有信封（复用 `SubAgentBrief` 五节：Task/Expected/Constraints/Workflow/Collaboration）。运行时对 Supervisor 的指令做轻校验：

- 必须含 Task 与 Expected（验收标准）——没有验收标准的指令直接打回（错误消息指回契约），**这是「复核有判据」的根**，比 verdict JSON 轻得多但同样钉死语义
- `write_paths` 声明 → 进 `WritePathGuard`
- 多任务并行：一条指令消息里 `tasks[]` → Worker 席内并行 lane（复用 `executeRunSubAgent` 的波次机制，但 lane 产出回流为 Worker 消息而不是工具返回值）

### 3.5 终止契约（DONE 行协议）

仓库先例：`DiscussionGraph` 用 `VERDICT: ACCEPT|OBJECT` 行协议收束投票。同款：

- Supervisor 复核回合提示词要求：继续纠正就正常发消息；宣布达标必须单独一行 `DONE: <一句话交付摘要>`
- 解析：`(?im)^DONE:\s*(.+)$` 命中 → 收束；未命中 → 下一轮 Worker
- **防橡皮图章**：DONE 行必须引用至少一条验收标准的满足证据（提示词约束 + 解析层校验引用存在，纯空话 DONE 打回一次）
- 轮次硬顶：Worker 回合数 ≥ 上限（缺省 50，设置可调 1..200）→ 强制收束，Supervisor 被要求输出「当前状态 + 未完成清单」
- 用户点停（现有停止按钮语义）→ 立即收束，同上

### 3.6 用户插话（第三发言者，单一指挥链）

**已拍板（2026-10-24）：用户插话统一经 Supervisor 转发，不直接指挥 Worker。** 单一指挥链——Worker 永远只听 Supervisor 的指令，用户纠正由 Supervisor 吸收后并入下一条指令。好处：Worker 的指令来源单一不会打架，用户意图由贵模型消化成精确指令再下发。

- 会话运行中用户发消息 → **不打断当前 Worker 轮**（半成品文件状态比等待更糟），进 `SupervisorInbox`（内存 + 持久化）
- Supervisor 下一轮**先**看到 inbox 内容（提示词注入「用户插话：…」），决定：吸收进纠正指令 / 直接回答 / 建议收束
- 用户显式停止 → 立即收束（不等轮结束，复用现有中断机制）
- UI：Worker 气泡区顶部「N 条待注入」徽标；输入框常驻可用

### 3.7 模型位与设置

`MultiAgentSettingsRepository` 新增：

- `workerModelEntryId: String?`（null = 跟随会话模型 = 现状行为，回归安全）
- `supervisorMaxWorkerTurns: Int`（缺省 50，夹 1..200，用户拍板 2026-10-24）

解析顺序：指令级 `model` 参数 > `workerModelEntryId` > 会话模型。设置页（Multi-agent 屏）加「Worker 默认模型」一行（复用现有模型选择组件，Apple 风格）。

### 3.8 持久化与恢复

- 轮次记录：`SupervisorRunRecord`（objective、每轮指令/产出摘要/DONE 状态、Worker 回合计数）存 SharedPreferences（同 `SubAgentBatchRegistry` 存储层）
- 进程死亡：会话回 `INTERRUPTED`，重开时 Supervisor 收到「上次跑到第 N 轮，最后一轮产出：…」的恢复提示，决定续跑还是收束
- 产出本身在沙箱文件系统，天然持久

### 3.9 UI

复用群聊气泡管线（`upsertGroupBubble`）：

- Supervisor 气泡：贵模型品牌头像（复用 `SoulModelIconAvatar` 管线）+ 指令消息（brief 折叠渲染，点开全文）
- Worker 气泡：便宜模型头像 + 工具活动内联（复用子代理卡片的 lane 渲染）+ 产出报告
- 用户气泡：普通样式
- 会话头部条：objective + 轮计数（`W 3/12`）+ 状态徽标（指令中/执行中/复核中/收工/中断）+ inbox 徽标
- 收束时：交付摘要 + 产出文件链接落回主聊天流
- Apple 风格，图标走 M3Icons 双向映射注册（动态取色除外）

### 3.10 入口与提示词接线

- **入口（已拍板 2026-10-24）：斜杠面板项，不加输入框按钮**。输入框已挂附件、斜杠、语音、发送四个入口，再塞一个模式开关就是第五个——拥挤。斜杠面板里加「Supervisor 会话」项（面板已有 Goal 等先例），点开后下一条用户消息成为 objective 并开启会话；多代理设置里可设默认开启。零新增 chrome，入口可发现性靠面板。
- `PromptExt` 的 multi-agent fragment 扩一段：何时用 Supervisor 会话（需求 = 拆解 + 执行 + 验收闭环）vs 裸 `spawn_agent`（一次性并行无复核）vs 群聊讨论（平等多角色出主意）
- 门禁：新工具名若被 fragment 点名必须进核心 schema（`check_announced_tools.py` 先例）；本设计**不新增主 loop 工具**——Supervisor 会话是模式开关不是工具调用，门禁面更小

## 4. 不做什么（v2 明确出界）

- **不做 verdict JSON 状态机**（v1 方案废弃）：纠正就是消息，达标就是 DONE 行。结构化留给「指令信封校验」这一层，够了
- **不做 Worker 审 Worker**：复核权只在 Supervisor + 用户
- **不做 Supervisor 独立模型位**：会话模型即 Supervisor
- **不做跨会话 Supervisor 会话**：绑定当前聊天，同群聊语义
- **不动 `DiscussionGraph`/`CollabRoles`**：那是平等讨论拓扑，与主从干活拓扑并存不合并

## 5. 测试点（TDD 先行）

1. **DONE 行协议**：命中收束 / 未命中续轮 / 空 DONE 打回一次 / 轮次硬顶强制收束 / 用户停止立即收束
2. **指令信封校验**：缺 Task 或 Expected 打回 / write_paths 进守卫 / tasks[] 并行展开
3. **轮转状态机**：S→W→S 固定顺序 / 用户插话进 inbox 不打断 / inbox 在 Supervisor 轮前排空 / 停止语义
4. **模型位解析**：指令级 > 设置位 > 会话模型；null 位 = 现状回归
5. **恢复**：进程死亡 → INTERRUPTED → 恢复提示注入
6. **工具面**：Worker 席可写但禁嵌套 spawn；Supervisor 席只读
7. **UI 契约**：轮计数徽标 / inbox 徽标 / 收束摘要落主聊天

## 6. 实施切分（批准后按此走 PR）

| PR | 内容 | 风险 |
|---|---|---|
| #1 | `SupervisorChat.kt` 纯逻辑：双席定义、轮转状态机、DONE 协议、指令信封校验 + 全部单测 | 低 |
| #2 | 群聊管线特化：可写 Worker 席（工具面 + WritePathGuard）、模型位设置、恢复记录 | 中（触碰群聊管线，新逻辑外置防贴顶） |
| #3 | UI：气泡特化、会话头部条、inbox 徽标、入口开关、locale ×17、提示词 fragment | 中 |

## 7. 已拍板与开放问题

已拍板（2026-10-24）：
1. **Worker 回合硬顶缺省 50，手动可设**（范围 1..200）——上限是防死循环的保险丝，不是预算；50 给长任务留足余量
2. **入口：斜杠面板项**，不加输入框按钮（输入框已四个入口，不挤第五个）
3. **用户插话统一经 Supervisor 转发**，不直接指挥 Worker——单一指挥链
4. **主会话不被阻塞**：Supervisor 会话独立协程运行，主聊天全程可用（贵模型同时服务两条对话流，各自独立历史）

待拍板：无——全部已定，可写实施计划。
