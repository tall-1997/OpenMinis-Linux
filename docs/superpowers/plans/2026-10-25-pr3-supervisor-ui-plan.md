# PR#3 实施计划 — Supervisor 会话 UI + 斜杠入口 + locale×17

设计文档: `docs/superpowers/design/2026-10-24-supervisor-worker-loop-design.md` §13-15（UI 层）。
承接 PR#1（协议层）+ PR#2（管线接线）。本 PR 只做 UI 与入口，不改协议层语义。

## 目标

1. 斜杠面板新增 `Supervisor` 条目：点按切换 armed 状态；armed 时下一条消息成为任务指令并启动 supervisor-worker 会话
2. 会话进行中：composer 上方常驻状态条（相位 + worker 轮数 + 收件箱数 + Halt 按钮）；用户插话路由进 inbox
3. 会话中断（进程死亡/被杀）：重开会话时检测 INTERRUPTED 记录，弹恢复对话框（Resume / Discard）
4. 全部新文案进 strings.xml，17 个 locale 全覆盖

## 文件改动

### 1. `ChatViewModelSupervisorExt.kt`（+~150 行，298→~450）

- 新增 `SupervisorUiPhase` enum: `INSTRUCTING / EXECUTING / REVIEWING / DONE / HALTED / FAILED / INTERRUPTED`
- 新增 `SupervisorUiState` data class: `phase / workerTurns / objective`
- 新增 `SupervisorUiHost` object: `MutableStateFlow<SupervisorUiState?>`（进程级，sessionId 键控；`reset(sessionId)` 在会话切换时清）
- `SupervisorUiState` 扩展访问器 `supervisorUiState: StateFlow<SupervisorUiState?>`
- `startSupervisorSession` 成功后置 `INSTRUCTING`；失败原因返回给调用方（改返回 `String?`——null=成功，非 null=失败原因文案）——**不改**，保持 Boolean，调用方自行给通用失败文案（避免签名漂移烧 CI）
- seat 回调里推进相位：supervisor seat 开跑 → `INSTRUCTING`；worker seat 开跑 → `EXECUTING`（带 workerTurns）；review（supervisor 第二次起）→ `REVIEWING`
- `onTerminal` 里按终态置 `DONE / HALTED / FAILED`
- 新增 `toggleSupervisorArmed()`：armed 状态存 VM 字段（`_supervisorArmed`），切换时 appendSystemInfo 提示
- 新增 `supervisorArmed: StateFlow<Boolean>`
- 新增 `persistSupervisorUserTurn(text)`：走 `buildUserPartsJson(text, emptyList(), null)` + `appendMessage` + `_messages` + `notePersistedUiRow`——与 SendExt 正常用户消息同形，但**不进 agentHistory**（supervisor 会话有自己的上下文管线，用户插话走 inbox）
- `pushSupervisorUserMessage` 成功 push 后 bump UI state 的 inboxCount（相位条轮询 inbox.size 即可，无需字段——用轮询，避免跨线程写）

### 2. `ChatViewModel.kt`（+2 行）

- `availableSlashCommands` 列表加 `SlashCommand(id="supervisor", icon=Icons.Outlined.Psychology, title="Supervisor", subtitle="")`

### 3. `ChatViewModelSlashExt.kt`（+6 行）

- `filteredSlashCommands` 加 `"supervisor"` 分支：subtitle 按 armed 态取 `slash_supervisor_subtitle_off/on`

### 4. `ChatViewModelSlashExec.kt`（+1 行）

- `when (cmd.id)` 加 `"supervisor" -> toggleSupervisorArmed()`

### 5. `ChatViewModelSendExt.kt`（+~30 行）

- `sendMessage` 在 trimmed 计算后、`_isStreaming` 检查前插路由块：
  - `supervisorSessionActive()` → `persistSupervisorUserTurn` + `pushSupervisorUserMessage`，失败（会话刚结束）给 system info；return
  - `supervisorArmed` → disarm + `persistSupervisorUserTurn` + `startSupervisorSession(trimmed)`，失败给 system info；return
  - armed + 有附件 → 提示「supervisor 会话只收文本指令」并 return（附件静默丢弃是 bug）
  - 空文本 armed → 不拦截（落回正常路径）

### 6. `ChatViewModelLoadExt.kt`（+~15 行）

- `loadSession` 尾部：`SupervisorUiHost.reset(sessionId)`；若 `pendingSupervisorRecovery()` 非空 → 置 `INTERRUPTED` 相位（对话框由 UI 层读该相位渲染）

### 7. 新文件 `ui/chat/SupervisorStatusBar.kt`（~180 行）

- Composable：读 `supervisorUiState` + 轮询 inbox（`LaunchedEffect` 500ms tick 读 `SupervisorSessionHost.live(activeSessionId)?.inbox?.size`）
- Apple 风格卡片（与 PlanGoalCard 同语言）：相位图标（Psychology/Build/CheckCircle/Stop/Error）+ 相位文案 + worker 轮数徽标 + inbox 徽标 + Halt 按钮
- live 相位（INSTRUCTING/EXECUTING/REVIEWING）显示 Halt；终态 linger 4s 后自消失（复用 PlanGoalCard 的 linger 模式）
- `INTERRUPTED` 相位渲染恢复对话框（Resume → `startSupervisorSession(objective, recovery)`；Discard → `SupervisorRunStore.discard` + 清相位）

### 8. `ChatScreen.kt`（+2 行）

- `PlanGoalCard(viewModel)` 后加 `SupervisorStatusBar(viewModel)`

### 9. strings.xml ×18（base + 17 locale）

21 个新键（见下方键清单），python 脚本批量插入。

## 字符串键清单

- `slash_supervisor_subtitle_off` / `slash_supervisor_subtitle_on`
- `supervisor_bar_instructing` / `supervisor_bar_executing`（带 %1$d）/ `supervisor_bar_reviewing`（带 %1$d）
- `supervisor_bar_done` / `supervisor_bar_halted` / `supervisor_bar_failed` / `supervisor_bar_interrupted`
- `supervisor_bar_halt` / `supervisor_bar_inbox`（%1$d）
- `supervisor_start_failed`（%1$s）/ `supervisor_session_ended`
- `supervisor_recovery_title` / `supervisor_recovery_body` / `supervisor_recovery_resume` / `supervisor_recovery_discard`
- `supervisor_armed_hint` / `supervisor_disarmed_hint`
- `supervisor_attachments_unsupported`

## 验证

- 四道门禁 + scan_kt + check_kdoc + L10n 门禁（18 locale 键覆盖检查）
- python 模拟器 `sim_supervisor_ui.py`：armed 路由状态机（armed→send→启动；live→send→inbox；armed+附件→拒绝；空文本→正常路径；halt→相位条终态）
- 无 kotlinc：符号核对脚本（新引用的 VM 成员在声明处存在）

## 风险

- ChatViewModel.kt 6292 行已贴顶（cap 800 是文件数不是行数——架构基线按文件计）——只加 2 行，无风险
- ChatScreen.kt 7544 行——只加 2 行挂载
- `startSupervisorSession` 返回值不改（Boolean 保持），失败文案用通用键
