# Goal 全产品化设计（C 档 · 融合单卡版）

日期：2026-10-23
状态：待用户批准（v2：应用户反馈从「两条堆叠」改为「一张卡两段」）

## 意图与为什么

用户实际体验「goal 从未生效」。根因诊断（同日，宿主库直查）：引擎健全——分发特判、
GoalStateMachine、agent loop 自动续跑（ChatViewModelAgentLoopExt.kt:1617 守卫链）全链路
无 bug；但 goal 从未被创建过（session_goals 0 行、messages 全历史无 /goal 输入）。
原因：入口断裂（斜杠面板点 Goal 只打印一条不持久化的用法文本）、零可见性（goal 激活后
无任何常驻 UI）、无任务绑定（与计划看板互不感知）。

本设计把 goal 从隐藏命令变成产品面：可发现、可创建、可见、可管理。goal 与计划看板
**融合为一张卡**：goal 是卡片头部行（为什么干），看板是卡身（怎么干）——一个视觉
单元，不叠两条。数据层两系统保持独立（各自单一职责），仅展示层组合。

## 目标

1. 斜杠面板点 Goal → 打开 GoalSheet（无 goal=创建表单、有 goal=管理面板）
2. goal 存在时在卡片头部行常驻：objective + 状态图标 + token 用量
3. goal 完成 → 头部行转绿 linger 4s 自动退场；看板段照旧自消失
4. 创建后自动触发 goal run（复用现有 internalGoalRun 路径，零引擎改动）
5. 暂停/恢复/预算/清除全部有 UI 入口

## 非目标

- 不改 agent loop 续跑引擎（已健全）
- 不把 plan steps 挂进 goal 实体——展示层融合足够
- 不做 goal 历史列表（COMPLETE 行留 DB，/goal show 可查）
- 不做跨会话 goal

## 反目标（做砸了长这样）

- 卡片抢戏：比消息流还显眼、常驻闪烁、两条卡叠着占两个视觉块（用户已否决）
- 表单提交后 goal run 没启动，用户还得手打命令
- 贴顶文件超 ceiling（ChatScreen 7544=7544 零余量）
- 融合改造弄坏 #134 验证过的看板自消失语义

## 架构

### 文件布局

| 文件 | 动作 | 职责 |
|---|---|---|
| `ui/chat/PlanGoalCard.kt` | **改造**（原 StickyPlanBar.kt，git mv） | 一张卡：卡片外壳 + goal 头部行 + 看板段 + 展开态 + 双段自消失状态机 |
| `goal/GoalBoardStore.kt` | 新增 | UI 侧单例门面：version 信号读、DB 读、用户操作包装（内部走 GoalManager 状态机） |
| `ui/chat/GoalSheet.kt` | 新增 | ModalBottomSheet：创建表单 / 管理面板 |
| `ui/chat/ChatViewModelGoalUiExt.kt` | 新增 | `startGoalRun()` 扩展 + `GoalSheetSignal` 单例（MutableStateFlow） |
| `ui/chat/ChatScreen.kt` | 净 0 | `StickyPlanBar(sessionId)` → `PlanGoalCard(sessionId, viewModel)` 一行换一行 |
| `ui/chat/ChatViewModelSlashExec.kt` | 净 0 | `"goal"` 分支 appendSystemInfo → `GoalSheetSignal.open()` |
| `goal/GoalManager.kt` | +~15 行 | companion 加 version StateFlow；写路径成功后 bump；新增 clearByUser |
| `strings.xml` ×16 | — | 新键 ~18 个 |

PlanGoalCard 预估 ~450 行（现 StickyPlanBar 300 + goal 段 ~150），低于 800 ceiling。
#134 的看板自消失语义（空板 linger 600ms / done linger 4s / settle 400ms / linger 期间
新条目取消消失）原样保留，扩展为双段。

### 数据流

```
GoalManager 写（表单 / 模型 update_goal / recordUsage）
  → bump GoalManager.version（companion 静态信号）
  → PlanGoalCard LaunchedEffect(version, sessionId) 重读 DB → 头部行更新

面板 Goal 行 / 头部行点击 → GoalSheetSignal.open()
  → ChatScreen collect → GoalSheet 展开
  → 提交 → GoalBoardStore.create（状态机校验）→ version bump → 头部行出现
  → viewModel.startGoalRun() → sendMessage("", internalGoalRun=true) → 现有续跑引擎
```

仓库句柄：`(context.applicationContext as MinisApp).chatRepository`（canonical 实例）。
DB 读走 `AppDatabase.getInstance(context).goalDao()`（VoiceCorrection 同款先例）。

## UI 规格（Apple 设计语言 · 融合单卡）

### 卡片结构（一张卡，两段）

```
┌──────────────────────────────────────────┐
│ 🚩 把 OpenMinis 的 CI 修到全绿   12.3k/50k │ ← goal 头部行（有 goal 才渲染）
│ ──────────────────────────────────────── │ ← hairline（两段都有才有）
│ ✓ 本地跑 L10n 门禁（16 locale）    3/7 ▾ │ ← 看板段（现状原样）
│ ▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░           │ ← 进度轨道
└──────────────────────────────────────────┘
```

- 外壳照旧：Surface + 16dp 圆角 + surfaceContainer + outlineVariant hairline
- **头部行**：状态图标 18dp + objective 单行 ellipsis（bodyMedium SemiBold）+
  tokens 徽标 labelSmall（"12.3k/50k"）。无进度轨道——轨道是看板段的
- **看板段**：现状 StickyPlanBar 原样（当前步骤 + n/m + 4dp 轨道 + 展开步骤列表）
- **点击区分离**：头部行点击 → GoalSheet；看板段点击 → 展开/收起（现状行为）
- **展开态**：头部行 + goal 详情一行（状态点 + 状态词 + elapsed）+ hairline + 步骤列表
- **只有 goal 没看板**：卡片只有头部行（无 hairline 无轨道）
- **只有看板没 goal**：卡片 = 现状原样（头部行不渲染）
- **都没有**：整卡不渲染，零足迹
- 触觉：头部行点击 LongPress haptic（与看板一致）

### 状态语义（头部行图标/颜色）

| 状态 | 图标·颜色 | 头部行 | GoalSheet 操作 |
|---|---|---|---|
| ACTIVE | Flag · primary | 正常 | 暂停 / 清除 |
| PAUSED | Pause · 灰 | 文字灰化（保留显示——恢复要入口） | 恢复 / 清除 |
| BLOCKED | Error · 红 | 正常 + 条件在 sheet | 恢复（清 blocked）/ 清除 |
| BUDGET_LIMITED | DataUsage · amber | 正常 | 调预算 / 清除 |
| COMPLETE | CheckCircle · ToolCheckColor | 整卡绿 tint，linger 4s 后头部行退场 | 新建目标 / 清除 |

### 自消失状态机（双段扩展）

- goal COMPLETE → 绿 tint linger 4s → 头部行退场（看板还在 → 卡片回到纯看板形态；
  看板也没 → 整卡退场）
- 看板全完成 → 绿 linger 4s → 清板（goal 还在 → 回到纯 goal 形态）
- 空板 linger 600ms / settle 400ms 语义原样保留
- linger 期间新事件（新步骤 / goal 状态变化）取消消失（effect restart 天然取消）
- COMPLETE 头部行退场后：本组合内 remember dismissed，重进会话展示一次再退场
  （完成是信息不是噪音）；/goal show 仍可查
- DB 读失败：头部行降级隐藏，看板段照常——不崩 composer

### GoalSheet（ModalBottomSheet + DragHandle）

- 创建态：objective 多行 OutlinedTextField（placeholder 引导 + caption 计数 0/4000）、
  budget 行（数字输入 + 「无限制」FilterChip 二选一）、错误就近红字、
  主按钮 pill primary「创建并开始」
- 管理态：objective 只读 + 状态行（色点 + tokens + elapsed + blockedCondition）、
  操作组（暂停/恢复、清除）、COMPLETE 态主按钮「新建目标」（clear → 创建表单）
- 提交成功 haptic + sheet 自关

clear = 用户主权操作，任意状态可清（GoalDao.delete 已存在，无需迁移）。
createByUser 拒绝已存在 goal 的 check 保持不变（「新建目标」先 clear 再 create）。

## 字符串（~18 键 ×16 locale）

goal_sheet_create_title / goal_sheet_manage_title / goal_objective_label /
goal_objective_placeholder / goal_budget_label / goal_budget_unlimited /
goal_sheet_create_action / goal_sheet_new_goal / goal_action_pause /
goal_action_resume / goal_action_clear / goal_status_active / goal_status_paused /
goal_status_blocked / goal_status_budget_limited / goal_status_complete /
goal_card_open（cd）/ goal_tokens_of / goal_elapsed_format

## 测试（TDD）

- JVM 单测：version bump（每写路径成功 +1、失败不 bump）、clearByUser 任意状态可清、
  create 拒绝已存在（现有契约保持）
- Python 模拟（双段状态机，扩展 sim_planbar.py）：goal COMPLETE linger→头部行退场
  （看板在/不在两分支）、看板 done linger→清板（goal 在/不在两分支）、双 linger 交错、
  linger 期间新事件取消、dismissed 后 bump 不复活（新 goal 除外）、DB 失败降级、
  无 goal 无板零渲染
- 门禁：符号导入（含未跟踪文件+字符串剥离）、括号/KDoc、L10n 16、架构、CJK ratchet

## 留给实现的决定

- tokens 千分位缩写用 Locale-aware compact formatting
- sheet 预算校验错误直接展示状态机 require 文案
