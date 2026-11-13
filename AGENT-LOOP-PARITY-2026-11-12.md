# Agent Loop 对齐 ZCode/Harness——逐项核对版（v2）

日期：2026-11-12 ｜ OpenMinis 基线：main @ 04075ea ｜ 基准来源：**本机发行版包实测**
（`D:\ZCode\resources\glm\zcode.cjs` 15.4MB 主程序 + `D:\DeepSeek Harness`，全部字符串证据引自该打包源）
文档取代同名 v1——v1 的三处"待真机确认"已在代码层实锤，一处基准表述（合成轮）按 ZCode 实测修正。

---

## 0. 结论

机制层经 #327/#329/#330 三轮已换成 ZCode/Harness 形状（五原则全落地）；
**体验层仍有 2 项 P0 差距 + 1 项 P1 + 1 项零空间微增强**——P0：stop 不杀进程、goal 无完成验证（B1 工具输出不可见、
B3 打断不 kill 进程、B5 无完成验证）。全部差距已在**代码层实锤**（不是猜测），
路线图三批可开工。

---

## 1. 基准实锤（ZCode 发行版原文引用）

| # | 维度 | zcode.cjs 实锤（原文） |
|---|------|------------------------|
| B1 | 工具过程可见 | 工具状态机六态：`Started / Retrying / Skipped / Completed / Failed / Interrupted` + `pendingApproval` + 结果态 `completed/failed/killed`——Interrupted 与 killed 是一等状态 |
| B2 | 进度行 + 排队 | `activeStatusHint: "esc to interrupt"`；忙碌时输入框 = `busyPlaceholder: "Type to queue input"`（输入即排队） |
| B3 | 打断 | `interruptedStreamDiscarded: "Interrupted model stream discarded."`；工具结果态含 `killed`；CLI 侧 SIGINT/SIGTERM/SIGHUP 处理 |
| B4 | 中途插话 | 同 B2 排队输入 + 工具结果边界独立轮 |
| B5 | 完成交付 + 验证 | **goal 提示词原文**："Do not mark the goal complete yourself. **The runtime will run a completion verifier after this turn**…"；"inspect any todo list, TodoRead result, or TodoWrite result… **every explicit requirement, named file, command, test, gate, and deliverable** in the objective is complete"——runtime 级完成验证器，模型无权自判完成 |
| B6 | compact 透明性 | 数据模型字段：`contextWindow{usedTokens, maxTokens, autoCompactThresholdTokens, breakdown}` + `truePostCompactTokenCount` + `willRetriggerNextTurn`——阈值显式、compact 后真实计数、**"下轮会不会再触发"可预测**；用户可见文案 `compacting: "Compressing context..."` / `compacted: "Conversation compacted."` |
| B7 | goal 续跑 | **结构化合成轮**：`<system-reminder source="goal-continuation">` + `Continue working toward the active session goal.`，带 `source`/`metadata.source` 字段、有专门的 `isGoalContinuation()` 识别函数——**ZCode 不禁止合成轮，禁止的是"裸合成轮"**：合成轮必须带结构化 source 标记、host 可识别、可去重 |

---

## 2. OpenMinis 现状（逐项代码实锤）

| # | 维度 | 现状（文件:行号） | 判定 |
|---|------|-------------------|------|
| B1 | 工具过程可见 | **已完整实现（v4 复核修正——此前 grep 的实现名找错了）**：ShellOutputPreview（150ms 节流，50 行 tail）逐块推送 toolBlocks.content；process card 运行中默认展开（`stepExpanded = step.running`，ChatProcessCards.kt:209）+ visibleThinkingTail 尾 8k + ThinkingAutoFollow 自动滚动 + 实时计时；点击进详情 sheet 看全量 | ✅ 已对齐 |
| B2 | 进度行 + 排队 | **活动 pill 已覆盖**（用户空间顾虑采纳，不加新行）：RUNNING 工具卡自带 工具名 + 实时计时 + 红色停止钮（ChatComposerWidgets.kt:340-368）；忙碌输入排队已有。微差距：运行中输入框 placeholder 未切换为排队/可停提示（ZCode 双态——进度提示长在输入框身上，零空间） | 🟡 微差距 |
| B3 | 打断 | `finishStoppedRun`（StopRunExt:43-110）把 RUNNING/STREAMING/PENDING 块即刻标 **CANCELLED** + partial text 抢救 + 持久化打断轮——UI 即时 ✓；**但全程无 JobRegistry.signal/进程 kill 调用**：沙盒里的进程（900s build）在标记 CANCELLED 后继续跑完 | ❌ 差距 |
| B4 | 中途插话 | 排队 + 边界独立轮（QueueInterrupt→drainQueueIntoStandaloneTurn） | ✅ |
| B5 | 完成交付 + 验证 | #328 修了 goal 完成后交付**时机**；**runtime 完成验证器：无**（grep verifier/isGoalComplete 全仓 0 命中）——模型自判 goal 完成，无 host 验证；turn 上限/异常退出为 inline error 而非进度交接 | ❌ 差距 |
| B6 | compact 透明性 | **水位全程可见**（用户核对确认）：顶栏 ContextRing 常驻（ChatTopBarSection）+ 菜单 Token Usage 单一入口（[#25] 融合：进度条 + Used/Window/Max Output + 分块占比同在一 sheet，原独立 Context Breakdown 面板已并入）；compacting/compacted 以系统气泡呈现在聊天流；判据与预算在（#327 A1/A2 幸存）。仅剩 willRetriggerNextTurn 预测位等内部字段，零体感影响 | ✅ 已对齐 |
| B7 | goal 续跑合成轮 | goal/plan/recall 走 commit-once + NoticeLedger（#330）；**裸合成轮已废**——但 ZCode 式**带 source 标记的结构化合成轮**（goal-continuation）我们没有等价物；#328 的 goal 完成交复是时机修复，续跑驱动仍是注入式 | 🟡 需按 ZCode 形态补结构化标记 |

---

## 3. 差距清单（6 项，按用户体感排序）

| 优先级 | 差距 | 用户体感 | 对齐动作 |
|--------|------|----------|----------|
| P0-1 | **B3 stop 不杀进程** | 点停止后 build 还在后台烧 CPU/电量，会话显示已停——"打断不了" | finishStoppedRun 内对 currentTool 快照调 JobRegistry.signal(进程组) + LLM 请求 cancel（已有）+ 卡片 CANCELLED（已有）；给工具结果态补 `killed` 语义 |
| P0-2 | **B5 无完成验证** | 模型自说"完成了"但验收标准没核——交付不可信 | goal 状态机加 host 侧 verifier：turn 结束后按 objective 的显式验收项（文件存在/命令退出码/test 通过/gate）逐项核验；不通过 → 结构化 system-reminder（source=goal-continuation 形态）回灌一轮；通过 → 交付回复（#328 已修时机） |
| P1-1 | **B2 运行中输入框提示缺双态** | 排队提示不在输入框身上（活动 pill 已覆盖工具名/时长/停止） | 运行中 placeholder 切换为「输入将排队，任务结束后发送」——ZCode busyPlaceholder 同款，零空间占用 |
| P1-2 | **B7 goal-continuation 结构化合成轮** | goal 长任务的续跑驱动不稳（注入式靠 prompt 组装，易被 trim） | goal 续跑改为带 `source=goal-continuation` 标记的结构化 USER 轮（host 生成、ledger 去重、isGoalContinuation 识别函数）——ZCode 正牌形态 |

## 4. 已对齐项（不再动）

B4 插话排队边界轮；B6 的 A1 判据 + PostAnchorTail 预算；B7 的 commit-once 体系；
工具单次执行账本（Ledger）；失败染色/可重试（B7 错误呈现）；MAX_AGENT_TURNS 可调。

---

## 5. 路线图（三批）

### 第一批（P0 两件）
1. **stop = kill**：finishStoppedRun 接 JobRegistry.signal + 工具结果补 killed 态；验证 900s build 第 5 秒停止第 6 秒进程死。
2. **goal host verifier**：objective 解析显式验收项（文件/命令/test/gate）→ turn 后逐项核验 → 不过回灌 `source=goal-continuation` 结构化轮 → 过则交付。
- **验收（用户屏幕所见）**：第 5 秒停止进程真死；"完成"必然对应验收项真实通过。（live output tail 已在——ShellOutputPreview 逐块推送 + 默认展开 + 自动跟随，v4 复核修正，不重做。）

### 第二批（P1 两件，控制权与透明）
3. 运行中输入框 placeholder 双态（「输入将排队…」，ZCode busyPlaceholder 同款，零新增空间——用户空间顾虑采纳，不做常驻进度行）。
4. goal-continuation 结构化合成轮（source 标记 + ledger 去重 + 识别函数）。
- **验收**：运行中一眼可停（活动 pill 停止钮 + 输入框排队提示）；goal 断点续跑跨重启。（进度行不新增——用户空间顾虑采纳，活动 pill + 输入框双态已覆盖。）

### 第三批（真机回归）
5. 重放 minis-2026-11-12.log 场景：992 条历史 + cargo build 循环——核对 ledger 无重执行、compact 触发、hint 形态、插话边界、重启不重播通知、verifier 行为。
6. DeepSeek Harness 发行版（`D:\DeepSeek Harness`）同法提取对照（其 primary-runtime 为 pnpm 环境目录，主 loop 在 app.asar 内，需 asar 解包）——确认无族内差异遗漏。

---

## 6. 附录：逐项对照总表

| 关注点 | ZCode 实锤（zcode.cjs） | OpenMinis 现状 | 差距 | 批次 |
|--------|--------------------------|----------------|------|------|
| 工具状态机 | Started/Retrying/Skipped/Completed/Failed/Interrupted + pendingApproval + killed | STREAMING/PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT/CANCELLED | 缺 Retrying 一等态与 killed 语义 | 一/二 |
| 工具输出 | 卡片流式尾随（用户全程看着干活） | 详情 sheet 才有 | ❌ | 一 |
| 进度行 | `esc to interrupt` + busy 输入排队 | 活动 pill（工具名+计时+停止钮）✓；输入框运行中双态提示缺 | 🟡 | 二 |
| 打断 | 流丢弃文案 + 工具 killed 态 | 块标 CANCELLED、进程不杀 | ❌ | 一 |
| 完成验证 | runtime verifier + 提示词禁自判 | 无 verifier | ❌ | 一 |
| goal 续跑 | `<system-reminder source="goal-continuation">` 结构化合成轮 | 注入式（无 source 标记） | 🟡 | 二 |
| compact | threshold/truePostCompact/willRetrigger + 用户文案 | ContextRing + Token Usage（含分块占比，[#25] 融合后单入口）+ 系统气泡 ✓（仅缺内部预测字段） | ✅ | — |
| 重复调用 | 结果内 hint（ZCode 2-3 次） | ToolRepeatTracker 同型 ✓ | ✅ | — |
| 执行账本 | 一次一结果 | ToolExecutionLedger ✓ | ✅ | — |
| 通知一次性 | durable ledger | NoticeLedgerStore ✓ | ✅ | — |
| 状态恢复 | host 写回快照 | SessionStateSnapshot ✓ | ✅ | — |
| 插话排队 | busy 排队 + 边界轮 | ✓ | ✅ | — |
| persona | 一次 | ✓（PersonaStackOnceTest） | ✅ | — |

*注：ZCode 字符串证据均可再按 `grep -oE "…"` 于 `D:\ZCode\resources\glm\zcode.cjs` 复现。*

---

## 7. 实现状态（2026-11-13）

| 项 | 状态 | 落点 |
|----|------|------|
| WalkBack token-first（preAnchor 不再被 cap 一票否决） | ✅ | ChatViewModelWalkBack.kt + AgentHistoryExt（预算随窗口缩放 ≤20%） |
| postAnchor 尾预算随窗口缩放（≤35%，上限 60k） | ✅ | AgentHistoryExt（#327 A2 幸存 + 动态化） |
| WorkingMemoryLedger 驱逐再交付 | ✅ | WorkingMemoryLedger.kt + HiddenNote hydration 分支 + ToolTurnExec 双 lane append |
| fanOutSpawnCalls 单次执行契约 | ✅ | 采纳手机端 #332 executeOnce 单一边界（原子声明 + in-flight join + replay 卡 settle + Python 状态机 CI 门），WorkingMemory 记账在 owner-only producer 内 |
| live output tail | ✅ 已在（ShellOutputPreview + 默认展开 + 自动跟随——v4 修正） |
| stop=kill / goal verifier | 未实现 | 路线图第一批剩余（见 §5） |

集成说明：本分支 rebase 到 fix/spawn-lane-single-execution（#332）之上——#332 的 executeOnce 单一执行边界与本分支的 WorkingMemory 记账互补（owner-only producer 内记账，replay 不重复记账）。走廊不变量：summary + preAnchor(≤20% 窗口) + postAnchor(≤35% 窗口) ≤ 80% 阈值，任何窗口无震荡。
