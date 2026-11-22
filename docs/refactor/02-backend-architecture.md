# 02 · 后端架构

本文覆盖：分层与依赖规则、状态容器、prefs/DataStore、Room、Provider、工具注册表、DI、沙箱与进程边界。
任务编号对应 `03-task-board.md`。

---

## 1. 分层与依赖规则

### 1.1 现状

模块只有三个：`:app`、`:core:model`、`:provider:api`。
`app` 内部按包分：`ui` / `data` / `sandbox` / `provider` / `tools` / `speech` / `backup` /
`service` / `config` / `mcp` / `browser` / `quota` / `agent` / `network` / `auth` / …

问题：**包之间没有依赖方向约束**。`ui` 直接依赖 `sandbox`、`provider`、`data` 的具体实现，
`data` 又依赖 `config`，`config` 依赖 `data`（循环）。现有 `check_architecture_policy.py`
只做了「单文件行数上限 + 禁止 import 清单 + baseline」三件事，没有依赖方向检查。

### 1.2 目标：依赖方向单向

```
        ┌─────────────────────────────────────┐
        │  :app (composition root)            │
        │  Application / DI 装配 / 导航        │
        └──────────────┬──────────────────────┘
                       │ 依赖
        ┌──────────────▼──────────────────────┐
        │  :feature:*  (ui + viewmodel)       │
        │  chat / settings / sessions / …     │
        └──────────────┬──────────────────────┘
                       │ 依赖（只依赖接口）
        ┌──────────────▼──────────────────────┐
        │  :domain (usecase + 领域模型)        │
        │  纯 Kotlin，无 Android 依赖          │
        └──────────────┬──────────────────────┘
                       │ 依赖（只依赖接口）
        ┌──────────────▼──────────────────────┐
        │  :core:model / :provider:api        │
        └─────────────────────────────────────┘
                       ▲
                       │ 实现
        ┌──────────────┴──────────────────────┐
        │  :data  :sandbox  :provider  :tools │
        │  (infra，实现 domain 定义的接口)     │
        └─────────────────────────────────────┘
```

硬规则（写进 `check_module_deps.py`）：

1. `:domain` 不允许 import `android.*` / `androidx.*`。
2. `:feature:*` 不允许 import `:data` / `:sandbox` / `:provider` 的实现包（只允许 `:domain` 接口）。
3. `:data` 不允许 import `:feature:*` / `:ui`。
4. `:core:model` 不允许 import 任何其他模块。
5. 任何模块不允许 import `:app`。

### 1.3 模块切分（按能力，不按层）

当前 412k 行在 3 个模块里。目标 14 个模块：

| 模块 | 内容 | 来源包 | 预估行数 |
|---|---|---|---:|
| `:core:model` | 数据模型（已有） | `core/model` | 2k |
| `:core:common` | 工具类、日志、时间、JSON | `util` `logging` | 6k |
| `:core:design` | 令牌、主题、原子组件 | `ui/tokens` `ui/theme` `ui/components` | 9k |
| `:domain` | 领域模型 + 接口 + usecase | 新 | 8k |
| `:data:prefs` | PrefsHandle / DataStore | `config` | 6k |
| `:data:db` | Room 单库 | `data/db` `data/repository` | 27k |
| `:infra:sandbox` | PRoot / rootfs / 进程 | `sandbox` | 26k |
| `:infra:provider` | LLM 供应商适配 | `provider` | 20k |
| `:infra:tools` | 工具实现 | `tools` | 16k |
| `:infra:speech` | TTS / STT | `speech` | 8k |
| `:infra:browser` | WebView 池 / 自动化 | `browser` | 5k |
| `:infra:backup` | 备份恢复 | `backup` | 7k |
| `:feature:chat` | 对话 UI | `ui/chat` | 65k |
| `:feature:settings` | 设置 UI | `ui/settings` | 34k |
| `:app` | 装配 | 其余 | 剩余 |

**注意**：模块化是**最后一批**任务。理由：模块边界一旦画错，回退成本远高于收益，
而 `:feature:chat` 65k 行在状态架构没理清之前切模块，等于把混乱固化进构建系统。

**任务**：`A4-1`（依赖守卫，不改结构）`A4-2`（`:core:*` 抽取）`A4-3`（`:infra:*` 抽取）
`A4-4`（`:domain` 抽取）`A4-5`（`:feature:*` 抽取）`A4-6`（`:app` 瘦身）

---

## 2. 状态容器（最大的一块）

### 2.1 现状实测

```
ChatViewModel.kt                      6208 行
ChatViewModel*Ext.kt                  70 个文件
ChatViewModel 内 MutableStateFlow     59 条
全仓 collectAsState                   423 处
```

70 个扩展文件清单（节选，按职责可归 7 类）：

| 类别 | 文件数 | 代表 |
|---|---:|---|
| 发送/队列 | 8 | `SendExt` `DrainQueueExt` `QueueInjectExt` `ResumeQueueExt` `SteeringExt` `RerunTailExt` `ResumeExt` `RetryExt` |
| Agent 循环 | 6 | `AgentLoopExt` `ExecuteToolExt` `InLoopContext` `RunLoopGate` `StopRunExt` `SubAgentExt` |
| 上下文/压缩 | 9 | `CompactExt` `CompactLeaf` `CompactPlan` `CompactReconcileExt` `CompactSplitExt` `ContextBreakdownExt` `ImageBudgetExt` `PreSendContext` `PromptExt` |
| 历史/存储 | 7 | `AgentHistoryExt` `HistoryExt` `LoadExt` `Store` `EnsureSession` `MigrateDraft` `RevertExt` |
| 附件/图片 | 4 | `AttachmentExt` `ImageExt` `ReadImage` `OffloadExt` |
| Goal/Plan/Group | 6 | `GoalExt` `GoalUiExt` `GroupChatExt` `GroupMembers` `GroupResolve` `PlanContextExt` |
| 其余 UI 胶水 | 30 | `Binder` `SlashExt` `SlashExec` `MentionExt` `InlineError` `SanitizeExt` … |

### 2.2 目标：编排 + 7 个委托

```
ChatViewModel（≤ 400 行）
  ├── 只持有：ChatUiState 单一状态、7 个委托引用、sessionId
  └── 只做：event 分派、生命周期、跨委托协调

ChatComposer        —— 输入框、草稿、mention、slash、附件选择
ChatTranscript      —— 消息列表、历史加载、分页、重跑、回滚
ChatAgentRunner     —— 循环、工具执行、流式、停止、子 Agent
ChatContext         —— 上下文计量、压缩、图片预算、prompt 构建
ChatSession         —— 会话创建/删除/迁移、分组
ChatTools           —— 工具详情、审批、offload
ChatGoal            —— goal / plan / steering 队列
```

每个委托 ≤ 800 行，通过构造注入依赖（不继承 ViewModel）。

### 2.3 单一 UiState

```kotlin
@Immutable
data class ChatUiState(
    val sessionId: String = "",
    val header: ChatHeaderState = ChatHeaderState(),
    val transcript: TranscriptState = TranscriptState(),
    val composer: ComposerState = ComposerState(),
    val run: RunState = RunState(),
    val context: ContextState = ContextState(),
    val goal: GoalState? = null,
    val banners: List<Banner> = emptyList(),
)

@Immutable
data class RunState(
    val phase: RunPhase = RunPhase.Idle,   // Idle / Streaming / ToolRunning / Stopping
    val turn: Int = 0,
    val streamingText: String = "",
    val thinkingText: String = "",
    val toolBlocks: List<ToolBlock> = emptyList(),
    val queuedSteering: Int = 0,
)
```

59 条流归并为 8 个字段组。**关键约束**：所有变更走 `_state.update { it.copy(...) }`，
一次 update 内改多个字段，保证 UI 拿到一致快照。

事件（一次性）：

```kotlin
sealed interface ChatEvent {
    data class ScrollToBottom(val animate: Boolean) : ChatEvent
    data class ShowToast(val text: String) : ChatEvent
    data class RequestPermission(val kind: PermissionKind) : ChatEvent
    data object NavigateBack : ChatEvent
}
private val _events = Channel<ChatEvent>(Channel.BUFFERED)
val events = _events.receiveAsFlow()
```

### 2.4 迁移顺序（不可乱）

1. 先建 `ChatUiState` 与 8 个字段组，**保持 59 条流并存**，让 state 由流组合派生（`combine`）。
2. 逐条把流的写入方改成写 `_state`，删掉旧流。
3. 全部迁完后再合并 70 个扩展文件到 7 个委托。

理由：反过来做（先合并文件）会让 70 个文件同时改状态来源，冲突面不可控。

**任务**：`B1-1`（UiState 骨架 + combine 派生）`B1-2`（逐流迁移，按 8 组）
`B1-3`（事件改 Channel）`B2-1`~`B2-7`（7 个委托逐个抽取）

---

## 3. 配置：prefs → DataStore

### 3.1 现状实测

```
prefs 文件（注册表 PrefsFileSpec）  93 个
其中加密                            9 个
键常量                              166 个
使用点                              166 处 / 97 个文件
```

架构：`Prefs.file(name)` → `PrefsRegistry.require(name)` → `PrefsHandle` → `PrefsBackend`
（`SharedPrefsBackend` 普通 / `EncryptedPrefsFactory` 加密）。

已有守卫三件套（上一轮工作成果，保留）：
- `check_prefs_registry.py` — 生成物与源码一致
- `check_prefs_encryption.py` — 名字含 secret/token/password 必须加密 + baseline 只增不减
- `check_prefs_api_usage.py` — 调用点方法名与接收者类型匹配

### 3.2 目标：双后端

| 类型 | 后端 | 判定 | 数量目标 |
|---|---|---|---:|
| 加密配置（密钥、令牌、密码） | `EncryptedSharedPreferences`（现状保留） | `check_prefs_encryption` 判据 | 9（不变） |
| 普通配置 | **DataStore Preferences**（1.3.0） | 其余 | 93 → 约 20 个 DataStore 文件 |

为什么 DataStore：
- 异步、事务性、无 `commit()` 阻塞主线程
- 天然 `Flow`，与 `ChatUiState` 的响应式模型对齐
- 不需要 `apply()` 之后手动通知

**不做的事**：不把加密配置迁 DataStore。DataStore 没有等价的加密后端，
自建加密 serializer 会引入新的密钥管理面，收益不抵风险。9 个加密文件原样保留。

### 3.3 迁移做法

1. `PrefsHandle` 增加 `PrefsBackend` 的 DataStore 实现 `DataStorePrefsBackend`，
   键名映射保持 `KEY_*` 常量不变（注册表不动）。
2. 按文件逐个切换后端：`PrefsRegistry` 的 `PrefsFileSpec` 增加 `backend` 字段。
3. 一次性迁移脚本：应用启动时检测旧 SharedPreferences 有值、DataStore 无值 → 搬运 → 标记完成。
4. 迁移后保留旧文件 1 个版本周期再删。

**任务**：`D1-1`（DataStore 后端）`D1-2`（registry 加 backend 字段）
`D1-3`（迁移器 + 幂等标记）`D1-4`（分批切换）`D1-5`（清理旧文件）

---

## 4. 持久化：4 个库 → Room 单库

### 4.1 现状实测

| 库 | 用途 | 访问方式 |
|---|---|---|
| `minis.db` | 会话、消息、供应商、模型 | Room（`AppDatabase`）+ 手写 helper |
| `skills.db` | 技能 | 手写 `SQLiteOpenHelper`（`SkillRepository` 2321 行） |
| `mcp.db` | MCP 服务器 | 手写 `SQLiteOpenHelper`（`MCPRepository`） |
| `minis-config-audit.db` | 配置变更审计 | 手写 `SQLiteOpenHelper`（`ConfigAuditLog`） |

另有 `SessionOverrideStore`、`DatabaseStartupGate` 两处手写 helper。

问题：同一份数据两套访问路径（Room entity + 裸 SQL），schema 变更要改两处，
`DatabaseStartupGate` 的存在说明启动期并发访问已有过问题。

### 4.2 目标

单 `AppDatabase`（Room 2.8.5），四个库合并为一个 `minis.db`：

```
minis.db
  ├── chat_*        （会话、消息、附件）
  ├── provider_*    （供应商实例、模型、分组）
  ├── skill_*       （技能、技能源）
  ├── mcp_*         （服务器、工具缓存、审批）
  └── audit_*       （配置审计）
```

Room 的 `@Database(entities=[...], version=N)` 支持多 module，用
`@DatabaseView` / `@Relation` 处理跨域查询。

**迁移风险点**：现有 `minis.db` 已有用户数据，合并库必须走
`RoomDatabase.Callback` + 手工 `Migration`，不能用 `fallbackToDestructiveMigration`。

### 4.3 迁移做法

1. 先给 `skills` / `mcp` / `audit` 三个库补 Room entity（schema 与现有表逐列对齐）。
2. 写 `Migration_OLD_N_NEW_1`：`ATTACH` 旧库 → `INSERT INTO ... SELECT` → 校验行数。
3. 校验通过才 `DROP` 旧表；失败则保留旧库并回滚（幂等）。
4. 删除 5 个手写 `SQLiteOpenHelper`。

**任务**：`D2-1`（三库 entity 对齐）`D2-2`（合并 Migration）
`D2-3`（行数校验 + 回滚）`D2-4`（删手写 helper）`D2-5`（`DatabaseStartupGate` 收口）

---

## 5. Provider 层

### 5.1 现状

```
provider/openai/OpenAIProvider.kt          1697 行
provider/openai/OpenAIRequestBodies.kt     1309 行
provider/anthropic/AnthropicProvider.kt    1226 行
```

三个文件合计 4232 行，都是「请求构造 + SSE 解析 + 错误映射 + 用量统计」四件事混在一起。

### 5.2 目标：四段式

```
LLMProvider（接口，:provider:api）
   ↑
ProviderAdapter（每个厂商一个，只做协议差异）
   ├── RequestBuilder   —— 消息 → HTTP body
   ├── StreamDecoder    —— SSE/NDJSON → StreamEvent
   ├── ErrorMapper      —— HTTP/业务错误 → ProviderError
   └── UsageParser      —— 响应 → TokenUsage
```

公共逻辑抽到 `provider/common/`：
- `SseLineReader`（当前 OpenAI / Anthropic 各写一份）
- `RetryPolicy`（5xx / 429 / 超时）
- `ToolCallAccumulator`（流式工具调用拼装）

**任务**：`D3-1`（接口四段化）`D3-2`（抽 `SseLineReader`）
`D3-3`（抽 `RetryPolicy`）`D3-4`（抽 `ToolCallAccumulator`）
`D3-5`（OpenAI 拆分）`D3-6`（Anthropic 拆分）

---

## 6. 工具系统

### 6.1 现状

`tools` 80 文件 16,182 行。工具声明（name / description / JSON schema）与执行逻辑混在一起。
`check_announced_tools.py` / `check_schema_drift.py` 两条守卫已存在，说明 schema 漂移是历史痛点。

### 6.2 目标：声明与执行分离

```kotlin
interface Tool {
    val spec: ToolSpec                  // name / description / schema（生成 JSON）
    suspend fun execute(ctx: ToolContext, args: JsonObject): ToolResult
}

@Serializable data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonSchema,
)
```

工具注册表 `ToolRegistry`：
- 编译期生成 `ToolSpec` 的 JSON schema（KSP），不手写
- 运行时按能力分组（`fs` / `shell` / `net` / `device` / `agent`）
- 审批策略按 `ToolSpec.risk` 声明

**任务**：`D4-1`（`Tool` 接口）`D4-2`（KSP schema 生成）`D4-3`（注册表）
`D4-4`（80 个工具逐个迁移）`D4-5`（守卫：禁止手写 schema）

---

## 7. 依赖注入

### 7.1 现状

零 DI 框架。实测 7 处手写 `ViewModelProvider.Factory`：

```
automation/ui/AutomationRulesViewModel.kt:110
ui/chat/ChatViewModel.kt:751
ui/git/GitPanelScreen.kt:511
ui/scheduled/ScheduledTasksViewModel.kt:217
ui/sessions/SessionListViewModel.kt:115
（+ 2 处）
```

`ChatViewModel` 构造 7 个参数，其中 3 个可空（`memoryRepository` / `skillRepository` /
`mcpRepository`），工厂里手写 `if (x != null)` 分支。

### 7.2 目标：Hilt

选择 Hilt 而非 Koin 的理由：
- 与 AGP 9.x + KSP 2.4.x 兼容性最好（Koin 无编译期校验）
- `@HiltViewModel` 直接消除 7 个工厂
- `@ApplicationContext` / `@ActivityRetainedScoped` 语义清晰
- 编译期图校验，配置错误在构建期暴露（符合「配置要简单」）

```kotlin
@Module @InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton fun chatRepository(@ApplicationContext ctx: Context): ChatRepository = ...
    @Provides @Singleton fun prefs(): PrefsHandle = ...
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    @Assisted val sessionId: String,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
) : ViewModel()
```

**任务**：`A3-1`（Hilt 接入 + Application）`A3-2`（模块定义）
`A3-3`（7 个工厂 → `@HiltViewModel`）`A3-4`（`@AssistedInject` 处理 sessionId）

---

## 8. 沙箱与进程边界

### 8.1 现状

`sandbox` 93 文件 26,154 行，含 `RootfsManager`（1712 行）、offload handlers
（`ModelUse` 2191 / `Accessibility` 1537 / `Shizuku` 1238）。

### 8.2 目标

沙箱是**基础设施**，不是重构重点。只做两件事：

1. `RootfsManager` 1712 行拆成 `RootfsProvisioner`（下载/解压/校验）/
   `RootfsLifecycle`（挂载/清理/健康检查）/ `RootfsPaths`（路径常量）。
2. offload handler 统一 `OffloadHandler` 接口（`canHandle` / `handle` / `priority`），
   现有 3 个实现 + 注册表。

**任务**：`D5-1`（RootfsManager 拆分）`D5-2`（OffloadHandler 接口统一）

---

## 9. 验证与守卫

### 9.1 现有守卫（14 条，保留）

| 守卫 | 作用 |
|---|---|
| `check_architecture_policy.py` | 文件行数上限 + 禁止 import + baseline 只降不升 |
| `check_prefs_registry.py` | 注册表与源码一致 |
| `check_prefs_encryption.py` | 加密降级防护 |
| `check_prefs_api_usage.py` | 调用点方法名与接收者类型 |
| `check_prefs_call_sites.py` | 常量作用域解析 |
| `check_string_refs.py` | `R.string` 引用可解析 |
| `check_ui_tokens.py` | 令牌可重生成 + 引用可解析 |
| `check_token_scope.py` | 令牌作用域 |
| `check_token_semantics.py` | 令牌语义 |
| `check_token_coverage.py` | 令牌覆盖 |
| `check_cjk_literals.py` | 禁止硬编码中文 |
| `check_composition_locals.py` | CompositionLocal 使用 |
| `check_schema_drift.py` | 工具 schema 漂移 |
| `check_announced_tools.py` | 工具声明与实现一致 |

### 9.2 新增守卫（本轮重构需要）

| 守卫 | 作用 | 对应任务 |
|---|---|---|
| `check_module_deps.py` | 模块依赖方向 | `A4-1` |
| `check_state_subscription.py` | 禁裸 `collectAsState` / UI 层禁 `MutableStateFlow` | `B3-3` |
| `check_component_layers.py` | Screen/Section/Primitive 三层规则 | `C5-1` |
| `check_typed_routes.py` | 禁字符串路由 | `C2-4` |
| `check_no_inline_versions.py` | build.gradle 禁内联版本 | `A1-1` |
| `check_no_sqlite_helper.py` | 禁手写 SQLiteOpenHelper | `D2-4` |

### 9.3 单入口

`scripts/ci/verify_all.py`：单进程跑完所有守卫，共享一次全仓文件扫描。
输出统一表格 + 非零退出码。**只在批次边界跑**，不逐文件跑。

**任务**：`E1-1`（verify_all 骨架 + 共享扫描）`E1-2`（并入 14 条现有守卫）
`E1-3`（并入 6 条新守卫）`E1-4`（静态验证合并：括号配平 + 符号存在性）
