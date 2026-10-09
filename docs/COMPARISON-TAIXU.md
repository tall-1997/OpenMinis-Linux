# OpenMinis-Linux ↔ taixu 对齐分析

对照对象：[wkbin/taixu](https://github.com/wkbin/taixu) `bf3565a`
分析日期：2026-10-09
本方基线：`17577a1`

**许可**：双方均为 **GPL-3.0** → 源码可直接移植，保留原作者版权头与来源标注即可（本仓库已有此惯例，见 `security/Capability.kt` 等文件的 `Adapted from` 标注）。既有 `COMPARISON-ROADMAP.md` 的「不复制其源码」针对的是 LGPL/AGPL 污染许可的情形；taixu 与本仓库同为 GPL-3.0，该条不适用，本次对齐不设此限制。

> 本文替代口头报告。口头版有两处口径错误，已在本文修正：
> - provider 规模：**16,448 行**（口头版误用 5,266，那是仅顶层文件、漏了 9 个厂商子包）
> - offload 规模：**28 个 `*OffloadHandler.kt`**（口头版误称"90+"，那是 `sandbox/` 全部文件数）

---

## 一、对账基准

| 维度 | OpenMinis-Linux | taixu |
|---|---|---|
| kt 文件 / 行数（含测试，排除 build） | **1,264 / 302,277** | 848 / 181,206 |
| 模块数 | 7（`:app` `:core:{model,common}` `:provider:api` `:hostcapability:api` `:benchmark`） | 30+（`core`×7 `feature`×16 `runtime` `harness` `tools` …） |
| 最大单模块 | `:app` 1,228 文件 / 299,593 行 | `harness` 240 / 44,808 |
| 测试 | **354 文件 / 46,391 行** | 218 / 27,197 |
| DI | 无（手写装配） | Koin |
| 序列化 | kotlinx.serialization | kotlinx.serialization |
| 持久化 | Room + DataStore | Room + DataStore |

**结论：不是单向落后，是强项互补。** 我方总量更大、测试更厚；taixu 模块化更彻底、harness 工程化程度更高。

---

## 二、双向强项盘点

### 我方领先（对齐时不要退化）

| 项 | 证据 |
|---|---|
| Provider 厂商适配广度 | `provider/` 60 文件 / 16,448 行，9 个子包：`anthropic antigravity api gemini openai openrouter thinking voice xai`；含 `ZenDisguise`（免费通道伪装）、`StallResume`、`FirstEventWatchdog`、`ToolJsonRepair`、`StreamTraceRecorder` |
| 宿主 offload 体系 | `sandbox/offload/` **28 个 handler**（通知/日历/联系人/剪贴板/媒体/位置/无障碍/Shizuku/Su/Doze/防火墙…）+ `offload/` 15 文件 |
| 沙箱资源治理 | `TokenBucket` `DiskPressure` `IoGovernor` `SandboxMemoryPressure` `ProcessBudget` `GuestLimits` `SandboxResourceGate` — taixu 无对应体系 |
| 测试密度 | 46,391 行 vs 27,197 行 |
| 工具长尾调度 | `FindTools` 关键词打分 + 按需启用集 |
| MCP OAuth | `MCPOAuthController` + `McpPkce` + `MCPTokenBridge` 完整 |

### taixu 领先（本次对齐目标）

| 项 | 规模 | 我方现状 |
|---|---|---|
| Agent harness 独立模块 | `harness/` 240 文件 / 44,808 行，**22 个子包** | 无。逻辑内嵌 `ui/chat/` **145 文件 / 53,220 行** |
| 工作流 DAG | harness workflow 16 文件 / 2,854 行 + `core/model/workflow` 1,514 行 + `feature/workflow` UI ≈ 7,625 行 | **无** |
| 端侧 Git | `feature/git` 3,014 行（JGit） | `GitCli.kt` 335 行 / 21 函数（CLI 包装）+ `GitCommitMessageGenerator.kt` 113 行 |
| 上下文窗口策略 | `ContextWindowPolicy`：工具 schema 预留**实测校准**（6,300）、请求体 **4MB 硬限**、巨型消息截断 | 有 `ContextPolicy`（4 档阈值）+ `ToolOutputSpill`（16k/6k/4k），**无请求体字节上限、无 schema 预留校准** |
| 工具 schema 校验 | `ToolSchemaValidator` 355 行 | **无** |
| 检查点回滚 | `checkpoint/` 720 行（`RewindController` 179 行） | 有 `FileCheckpointTool`，**无 rewind** |
| 压缩链路 | `compaction/` 1,011 行，5 个测试 | `ContextAssemblySnapshot`（单文件） |
| 提示词组装 | `prompt/` 1,233 行（`PromptRouter` `SystemPromptBuilder` `PromptVariableResolver`） | 散落各处 |
| 双 Agent 分离 | `dual/` + `DualAgentCoordinator` + `PlannerProtocolParser` | 无（`AgentPlanTool` 是单循环内） |
| A2UI 表面协议 | `A2uiSurfaceBus` + `feature/a2uipoc` 929 行 | 无 |
| 运行时环境 | `runtime/` 164 文件 / 30,069 行，含 `pty` `ftp` `webchat` `virtualdisplay` `bridge/adb` `privilege` `rootfs` `scripts` | 沙箱在 `:app` 内，无 FTP/WebChat/虚拟屏/无线 ADB |
| 工作区管理 | `WorkspaceManager` + `WorkspaceFileService` + ZIP 导入（Zip Slip 防护） | 无 |
| 设置检索 | `SettingsSearchRegistry` | 无（`ui/settings/` 24,078 行无检索） |
| 架构治理 | `architecture-policy.json` + `architectureCheck` 挂 preBuild | 无 |

---

## 三、缺口清单

### P0 — 架构级（越晚改越贵）

**P0-1　Agent harness 无独立模块**
`ui/chat/` 已 53,220 行，`ChatViewModel*` 一族同时承载 agent 循环、工具派发、子代理、上下文治理。
**后果**：工作流 / 检查点 / 恢复这些能力无处安放，只能继续堆进 ChatViewModel。
**taixu 参照**：`harness/` 下 22 个子包 `agent approval browser checkpoint compaction dual effects events mcp metrics operation projection prompt queue recovery session skill subagent task text validation workflow`。

**P0-2　子代理只做路径前缀校验，无租约与结果裁定**
`WritePathGuard` 是 ThreadLocal 前缀白名单——只挡路径，不挡语义。
taixu 有两层缺失：
- `SubagentClaim.kt`（252 行）：模型提交的"我完成了"是**主张不是事实**；host 用自落库 receipts 逐条核验 `acceptance_criteria`；`manual` 类永远不能自证；凭据不背书者降级 `unsatisfied`，整体降级 `partial`；**host 永不升格状态**。
- `SubagentLaneRunner` + `SubagentWritePathTest`：写路径租约。

**P0-3　无统一重试/恢复策略层**
缺 `RetryPolicy` `RecoveryManager` `DanglingToolCallPlanner` `ToolReplayPolicy`。我方只有 HTTP 层 `HttpRetryAfter` 与 `StallResume`，agent 循环层无统一策略。

### P1 — 能力级

| # | 缺口 | taixu 参照 | 我方现状（已核实） |
|---|---|---|---|
| P1-1 | 可视化工作流 DAG | 7,625 行，条件边 / 并行 / 审批 / 调度 | 无。仅 `CronScheduler`（AlarmManager 单发） |
| P1-2 | 端侧 Git 工作台 | JGit 3,014 行：改动/暂存/提交/分支/提交图/标签/push-pull/AI 提交说明 | `GitCli.kt` 335 行 CLI 包装 |
| P1-3 | 工具 schema 校验 | `ToolSchemaValidator` 355 行：required/type/enum/min/max/pattern/anyOf/items + **参数解包 + 键扁平化还原 + 别名规整** | **无**（模型参数错误只能靠工具内部 try/catch） |
| P1-4 | 上下文窗口策略补强 | 工具 schema 预留实测校准 + 请求体 4MB 硬限 + 巨型消息截断 | 有基础版，缺字节上限与校准 |
| P1-5 | 提示队列 | `PromptQueueManager` 135 行 | 无 |
| P1-6 | UnifiedDiff 生成 | `UnifiedDiffGenerator` 142 行 | 无 |
| P1-7 | 检查点回滚 | `RewindController` 179 行 + `SessionForkConversationRewinder` | 有 `FileCheckpointTool`，无 rewind |
| P1-8 | A2UI 表面协议 | `A2uiSurfaceBus` + `feature/a2uipoc` | 无 |
| P1-9 | Dual Agent（planner/executor） | `DualAgentCoordinator` + `PlannerProtocolParser` | 无 |
| P1-10 | 无线 ADB / mDNS 配对 | `service/adb` + `runtime/bridge/adb` | 无 |
| P1-11 | FTP / 局域网 WebChat | `runtime/ftp` `runtime/webchat` | 无 |
| P1-12 | 浏览器 CDP / Fetch 拦截 | `BROWSER_DESIGN.md` 全套 | `browser/` 13 文件，无 CDP 断点、无 Worker 级 Fetch 拦截 |
| P1-13 | 工作区管理 / ZIP 导入 | `WorkspaceManager` + `WorkspaceFileService` | 无（有 `BuildEnvironmentProfile`） |
| P1-14 | 设置检索 | `SettingsSearchRegistry` | 无 |
| P1-15 | 架构治理 policy-as-code | `architecture-policy.json` + preBuild 检查 | 无 |
| P1-16 | 端侧本地模型 | `LocalLlmManager`（沙箱内 llama.cpp/Ollama） | 无（项目既有立场：不捆权重进包） |

---

## 四、可复用件清单（已逐一核实存在）

纯逻辑、无 Android 依赖、带配套测试，移植摩擦最小：

| 复用件 | taixu 参照行数 | 配套测试（本仓） |
|---|---|---|
| `harness/validation/ToolSchemaValidator.kt` | 355 | `ToolSchemaValidatorTest.kt` |
| `harness/text/UnifiedDiffGenerator.kt` | 142 | `UnifiedDiffGeneratorTest.kt` |
| `harness/effects/RetryPolicy.kt` | 26 | `RetryPolicyTest.kt` |
| `harness/queue/PromptQueueManager.kt` | 135 | `PromptQueueManagerTest.kt` / `FilePromptQueuePersistenceTest.kt` |
| `harness/metrics/RunMetrics.kt` | 119 | `RunMetricsTest.kt` |
| `harness/operation/OperationCoordinator.kt` | 348 | `OperationCoordinatorTest.kt` |
| `harness/checkpoint/RewindController.kt` | 179 | `RewindControllerTest.kt` / `CheckpointStoreTest.kt` / `CheckpointByteBudgetTest.kt` |
| `harness/subagent/SubagentClaim.kt` | 252 | `SubagentClaimTest.kt` |

整目录复用（行数为 taixu 原始目录内 kt 合计，非本仓实测）：

| 目录 | 行数 |
|---|---|
| `harness/validation/` | 542 |
| `harness/text/` | 359 |
| `harness/effects/` | 158 |
| `harness/operation/` | 386 |
| `harness/checkpoint/` | 720 |
| `harness/subagent/` | 1,257 |
| `harness/compaction/` | 1,011 |
| `harness/prompt/` | 1,233 |

**移植摩擦点**：taixu 用 **Koin 构造器注入**，我方无 DI 框架 → 有状态件需改为手写装配；纯逻辑件（object / 顶层函数）不受影响。

---

## 五、执行建议（四批，可独立验证）

### 第一批 — 纯逻辑移植（低风险，立即可做）
1. `ToolSchemaValidator` + 测试
2. `UnifiedDiffGenerator` + 测试
3. `RetryPolicy` + 测试
4. `RunMetrics` + `PromptQueueManager`
5. 引入 `architecture-policy.json` 等价物，先立"新增模块依赖必须登记"

### 第二批 — Agent 可靠性
6. `harness/checkpoint` 全套 → 接上已有 `FileCheckpointTool`
7. `SubagentClaim` 结果裁定 → 接上 `WritePathGuard`
8. `harness/compaction` → 替换 `ContextAssemblySnapshot`
9. `harness/prompt` → 收敛散落的提示词组装
10. `OperationCoordinator`

### 第三批 — 大件（需先抽模块）
11. **抽 `:harness` 模块** ← P0-1，10–13 的前置
12. 工作流 DAG 全套（模型 + 执行器 + 调度 + 审批 + UI）
13. JGit Git 工作台
14. 工作区管理 + ZIP 导入

### 第四批 — 对齐补齐
15. A2UI
16. Dual Agent
17. 无线 ADB / FTP / WebChat
18. 浏览器 CDP / Fetch 拦截
19. 设置检索

---

## 六、待决策

1. **抽 `:harness` 模块是否执行** — 第三批全部依赖它。不抽，工作流与 Git 工作台只能继续堆进 53k 行的 `ui/chat`。
2. **移植方式** — 纯逻辑件直接移植；有状态件按接口重写（接我方 Room 与手写装配）。
3. **P1-10 / P1-11（无线 ADB、FTP、WebChat）安全审查** — 属新增局域网/宿主攻击面，建议单独过威胁模型，不与其他项混批上线。
