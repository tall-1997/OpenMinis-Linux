# 会话页全量复用上游 · 计划书

状态：**待确认，未开工**
仓库：`tall-1997/OpenMinis-Linux`（目标） ← `OpenMinis/OpenMinis`（上游，分支 `main`）
日期：2026-11-06

---

## 1. 为什么要重做

过去几轮我按"单点修复"改会话页（思考块展开、工具块内联、看板合并、目标页弹窗），
每改一处就与既有的折叠/截断逻辑冲突一次，最终变成互相打架的补丁堆。当前会话页
**已偏离上游结构**，且偏离处没有测试保护。

结论：停止增量修补，**整层替换**为上游实现，之后只做"上游 + 我们的后端差异"适配。

## 2. 证据：我们与上游的结构差异（实测，非推测）

上游 `src/android/app/src/main/java/com/openminis/app/ui/chat/` 共 **126 个文件**；
本地同目录 **约 250 个文件**，其中 **约 170 个是上游没有的**。

关键文件体量对比：

| 文件 | 上游 | 本地 | 说明 |
|---|---:|---:|---|
| `ChatScreen.kt` | 546 KB | 353 KB | 上游更大：布局/滚动/竞态都在这 |
| `ChatViewModel.kt` | **1.17 MB（单文件）** | 拆成 ~100 个 `ChatViewModel*Ext.kt` | **最大结构性差异** |
| `ChatAssistantMessageUI.kt` | 68 KB | 52 KB | 上游含 tool-pill + thinking 全套 |
| `ChatFlatItems.kt` | 57 KB | 63 KB | 上游含 `applyToolFolds`；本地被改出额外折叠 |
| `ChatModels.kt` | 21 KB | 11 KB | 上游块模型更完整 |
| `ChatToolDetailUI.kt` | 92 KB | — | **本地缺失** |
| `HelperUi.kt` | 89 KB | — | **本地缺失** |

上游独有的、正是"工具卡片 / 思考卡片 / 过程折叠"的实现：

| 上游文件 | 作用 |
|---|---|
| `ToolFoldPlanner.kt`（13 KB） | 决定哪些连续工具调用合并成一行 |
| `ToolFoldRows.kt` / `ToolGroupRow.kt` | 折叠行与分组的 UI |
| `ThinkingRowSettle.kt`、`ThinkingChunking.kt`、`ThinkingFullDialog.kt` | 思考行的收敛时机、分块渲染、全文弹窗 |
| `TaskCardFrame.kt`、`ScheduledTaskCard.kt`、`ScheduledPillModel.kt` | 任务卡 |
| `ContextTrimPlanner.kt`（25 KB） | 上游自己的上下文裁剪（本地对应的是我改过的 `ProgressiveTrim`） |
| `OrphanedRunningToolGate.kt`、`FallbackPulseGate.kt`、`SettleAfterInteractionGate.kt` | 竞态/收敛闸门（"显示顺序、竟态显示"就是这些） |
| `SelectionDocumentOrder.kt`、`ReadingAnchor.kt`、`MessageBoundsRegistry.kt` | 选择顺序与锚点 |

上游的两条渲染契约（源码原文）：

- `ChatAssistantMessageUI.kt:470` — **"Render blocks in original order — text, thinking, and tool calls interleaved"**：块按原始顺序交错渲染，不做二次排序。
- `ChatFlatItems.kt:483` — **"One folded run of consecutive tool calls"**：连续工具调用**作为一整个 run** 折叠，`applyToolFolds` 在构建列表之后插入折叠项，不改变消息本身。

这两条正是我们改乱的地方：本地把折叠写进了块级渲染，导致"思考块与工具块谁先谁后"随折叠状态漂移。

## 3. 目标

1. 会话页（列表、气泡、思考卡、工具卡、过程折叠、任务卡、计划/目标行、输入区）**以上游为准**。
2. 顺序、折叠、收敛时机、竞态行为**逐条对齐上游**，不再自创规则。
3. 我们已有的后端能力**只适配、不重写**：Goal / 计划看板、正文外置（body spill）、
   工具输出外置（ToolOutputSpill）、`GomobileNet`、调度倒计时。
4. 全过程中仓库保持可构建、可回滚。

## 4. 范围与做法

### 4.1 直接替换（上游为准，整文件覆盖）

`ui/chat/` 下的**渲染与布局层**：

```
ChatScreen.kt            ChatAssistantMessageUI.kt   ChatUserMessageUI.kt
ChatFlatItems.kt         ChatModels.kt               ChatMiscViews.kt
ChatComposerWidgets.kt   ChatToolDetailUI.kt         HelperUi.kt
ChatIndicators.kt        ToolFoldPlanner.kt          ToolFoldRows.kt
ToolGroupRow.kt          ThinkingChunking.kt         ThinkingRowSettle.kt
ThinkingFullDialog.kt    TaskCardFrame.kt            ScheduledTaskCard.kt
ScheduledPillModel.kt    LargeContentGuard.kt        StreamingMarkdownText.kt（分片系列）
```

### 4.2 需要适配后再落的（不能照抄）

| 上游文件 | 冲突点 | 适配方式 |
|---|---|---|
| `ChatViewModel.kt`（1.17 MB 单体） | 我们已拆成 `ChatViewModel*Ext.kt`，且含 Goal/看板/外置/调度等本地扩展 | **不整文件替换**。只把上游 VM 中与"块构建、折叠输入、顺序"相关的段落移植到对应 Ext 文件；其余保持现状 |
| `ContextTrimPlanner.kt` | 与本轮已修的 `ProgressiveTrim` 职责重叠 | 二选一：以上游为准（删 `ProgressiveTrim`）或保留我方并只对齐档位。**见第 6 节决策点 D2** |
| `ChatSubAgentBar.kt` / `PlanGoalCard.kt` / `PlanBoardStyle.kt` | **上游没有**，是本仓库自研 | 保留但**降级为只读展示**：不再参与布局高度，合并进上游的任务卡行（见渲染图 P4） |
| `GoalSheet.kt` | 上游没有 Goal 概念 | 保留，作为独立 sheet；不再从会话页自动弹出 |

### 4.3 明确删除

- 本地在块级渲染里自创的折叠/截断开关（`ProcessStepRow` 的 `stepExpanded` 逻辑、
  `ThinkingBlock` 的 `thinkingWindowSize` 窗口、`ChatMessageListSection` 的扁平化分叉）。
- 我再前几轮加的 `ChatAssistantMessageUI` 内联展开补丁。

## 5. 分阶段计划（每阶段一个 PR，可独立回滚）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P0 基线** | 冻结改动；拉起上游 126 文件快照到 `docs/upstream/chat/`（只读参考） | 快照可 diff |
| **P1 块模型** | 换成上游 `ChatModels.kt`；`AssistantBlock.kind` 收敛为 `text/tool_use/thinking/info` | 编译通过；块顺序单测（交错顺序） |
| **P2 渲染层** | 替换 `ChatAssistantMessageUI` / `ChatUserMessageUI` / `ChatMiscViews`；**不做任何本地改动** | 截图对照：思考卡、工具 pill、交错顺序 |
| **P3 折叠** | 引入 `ToolFoldPlanner` + `ToolFoldRows` + `ChatFlatItems.applyToolFolds`；删除本地块级折叠 | 连续工具 N 个 → 折叠一行；展开顺序与上游一致 |
| **P4 卡片** | `TaskCardFrame` / `ScheduledTaskCard` / `LargeContentGuard`；计划+目标并入任务卡行 | 看板+目标只占一行（渲染图 P4） |
| **P5 竞态与收敛** | `ThinkingRowSettle`、`SettleAfterInteractionGate`、`OrphanedRunningToolGate`、滚动 deadband | 流式结束 500ms 内收敛、无闪烁、顺序不跳 |
| **P6 清理** | 删除被替代的本地文件与 `ProgressiveTrim`（视 D2 结论）；架构基线收紧 | 棘轮/CJK/全量测试绿 |

每阶段结束都要求：
- `:app:compileReleaseKotlin` 通过
- 相关单测通过（P2 起补"块顺序""折叠 run 边界"的纯函数测试）
- 出 APK（main 滚动构建）供真机对照
- 出对比截图（上游 vs 本仓库，同一会话数据）

## 6. 需要你决策的点（开工前必须定）

- **D1｜`ChatViewModel` 怎么办**
  (a) 只移植块构建/折叠输入相关段落（推荐；风险可控，VM 仍是我们拆分版）
  (b) 整文件替换上游 1.17MB 单体，再逐步接回本地扩展（彻底但会大面积冲突）

- **D2｜上下文裁剪用谁的**
  (a) 换成上游 `ContextTrimPlanner`，删除 `ProgressiveTrim`
  (b) 保留 `ProgressiveTrim`，只对齐档位（更安全，但永远不是"全量上游"）

- **D3｜计划看板 / Goal 的归属**
  (a) 保留，并入任务卡行（推荐，渲染图 P4）
  (b) 完全移出会话页，只在设置里看
  (c) 直接删掉这套功能（上游没有）

- **D4｜上游没有但我们在用的**：`ScheduledCountdownPill`、`ChatSubAgentBar`、
  `WorkStageBanner` 等——保留（并入任务卡）还是暂时摘掉？

- **D5｜验收方式**：是否需要我每一步都给出"上游 / 本地"双截图对照？真机由谁验？

## 7. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 上游 `ChatViewModel` 是 1.17 MB 单体，与我们拆分版冲突 | P1/P2 编译大面积红 | D1 选 (a)；分批移植，每批可编译 |
| 上游依赖我们缺失的包（`HelperUi`、`ChatToolDetailUI` 等 89/92 KB） | 需要连带移植，工作量翻倍 | P0 先做依赖闭包分析，给出完整清单 |
| 我们的后端改动（body spill、GomobileNet、Goal、调度）与上游渲染假设不一致 | 运行期异常/空块 | 每个后端点在渲染层加适配层并补测试 |
| 上游 `StreamingMarkdownText.kt` 251 KB | 移植成本高 | 只替换分片入口，保留现有 markdown 内核实现（待 P2 评估） |

## 8. 渲染图

见同目录：

- `docs/plans/mockups/chat-page-upstream-01-components.png` —— 组件级（思考卡三态 / 工具折叠 / 任务卡 / 计划+目标单行）
- `docs/plans/mockups/chat-page-upstream-02-flow.png` —— 顺序与竞态（交错渲染、折叠 run、流式收敛时序）

渲染图是**布局与状态示意**，用于确认"应该长什么样"，不是像素级还原。
