# 设计：计划模式 + 会话看板 UI（plan mode / kanban board）

日期：2026-10-23。参考 PeakCode「看板即文件、状态回写归运行时、计划是可接受的产物、手机适配看板」四点，
映射到本项目已有架构。每项先写失败测试再改实现（编译验证交云端 CI）。

## 现状盘点（先查证再动手）

- `agent_plan` 工具 + `AgentPlanStore`（会话私有目录 JSON，status: pending/active/done/failed）已存在；
  子代理 lane 完成时运行时回写 done/failed（`ChatViewModelSubAgentExt:698`）——「状态回写归运行时」已覆盖派发场景。
- **缺口**：① 看板零 UI 消费者（全仓库只有工具/子代理读写，用户看不见）；② 无计划模式（模型直接动手，
  没有「先计划 → 人批准 → 再执行」链路）；③ 存储无版本信号，UI 无法感知变更。
- 「看板即文件」的落点：真相仍是 `AgentPlanStore` 的 JSON（会话私有目录是修过的跨会话覆盖 bug，
  不回退到 workspace 共享目录）；agent 经 `agent_plan` 读写同一份真相；**UI 是它的纯投影**——
  本设计补的就是投影层 + 批准链。

## 方案

### 1. 存储版本信号（UI 投影的地基）

`AgentPlanStore` 增加 `version: MutableStateFlow<Long>`，`saveLocked` 内 +1。
UI 侧 `combine(version, sessionKey)` 重载列表——写方（工具/子代理/批准链）不需要知道 UI 存在。

### 2. PlanBoardController（新文件，全部状态集中）

ChatViewModel 已贴顶（6292=ceiling），新状态一律外置：ChatViewModel 只加一行
`internal val planBoard by lazy { PlanBoardController(this) }`（净行数用删死导入抵消）。
Controller 持有：`board: StateFlow<List<Plan>>`、`modeEnabled: StateFlow<Boolean>`、
`pendingReview: StateFlow<PlanReview?>`、review deferred、approve/revise/reject/toggleMode。
会话切换由 `ChatViewModelBinder.bind` 后的既有 ensure 路径调 `planBoard.bind(sessionId)`。

### 3. 计划模式（先计划 → 批准 → 执行）

- **状态**：`PlanModeStore`（新文件）会话级持久化 enabled 标志，JSON 落会话私有目录（镜像 AgentPlanStore 模式）。
- **提示词注入**：`buildSystemPrompt` 尾部追加 `<plan_mode>` 段（enabled 时）：只读调研、产出计划、
  调 `exit_plan_mode` 提交；看板快照（非空时）注入 `<plan_board>` 段，模型每轮可见当前进度。
  放系统提示词尾部保持前缀缓存稳定（PromptExt 已有 stable/dynamic 边界设计）。
- **写守卫**：`ChatViewModelExecuteToolExt` 分发入口统一拦截变更类工具（file_write/file_edit/multi_edit/
  shell_execute/su_exec/env_exec/execute_code/子代理派发/ui_action/browser 动作/cronjob/skill 写），
  返回「计划模式生效，先提交计划」错误——单一咽喉点，不逐工具打补丁。只读工具照常。
- **exit_plan_mode 工具**（新文件 `ExitPlanModeTool`）：参数 `plan_markdown` + `items[]`（title/description）。
  执行走 `executeAskUserQuestion` 同款 deferred 暂停：UI 弹审阅卡 →
  - 批准：`AgentPlanStore.clear()` 后逐条 add（批准的计划就是看板），模式关闭，工具返回 approved，模型开始执行；
  - 修订：用户反馈文本返回给模型，模式保持，模型改计划再提交；
  - 拒绝：模式关闭，返回 rejected，模型收尾。

### 4. 看板 UI（手机适配）

- **紧凑进度条**：composer 上方（AskUserQuestionsCard 同挂载位），看板非空或模式开启时显示：
  「计划 · 2/5」+ 线性进度 + 模式角标；点击展开看板 sheet。
- **看板 sheet**：三列分段（待办/进行中/完成）+ failed 角标（红点 + 文案），横向滑动，Apple 风格圆角卡片
  （16dp 圆角、surfaceContainer、outlineVariant 描边，对齐 AskUserQuestionsCard）。
- **审阅卡**：`PlanReviewCard`（新文件）渲染 plan_markdown + items 摘要 + 批准/修订/拒绝。
- **模式开关**：composer 按钮行（`/` 按钮后）一个 InputCircleButton，图标 Flag/Checklist，激活态高亮。

## 文件清单

新文件：`tools/PlanModeStore.kt`、`tools/ExitPlanModeTool.kt`、`ui/chat/PlanBoardController.kt`、
`ui/chat/ChatViewModelPlanExt.kt`、`ui/chat/PlanBoardUi.kt`、`ui/chat/PlanReviewCard.kt`、
测试 `tools/PlanModeStoreTest.kt`、`tools/ExitPlanModeToolTest.kt`。
改文件：`AgentPlanTool.kt`（version）、`AgentTools.kt`（注册 +1）、`ChatViewModel.kt`（+1 行，删死导入抵）、
`ChatViewModelExecuteToolExt.kt`（分发 + 写守卫）、`ChatViewModelPromptExt.kt`（注入）、
`ChatScreen.kt`（挂载点，删死导入抵）、`strings.xml` ×2（默认 + zh）。

## 验证

本地：Python 静态检查（花括号/KDoc/符号导入映射/架构门禁/CJK 字面量/死导入逐个 grep 复核）。
云端 CI：编译 + JVM 单测（PlanModeStore 持久化往返、ExitPlanModeTool 解析、守卫名单、批准链状态机）。
