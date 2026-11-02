# M4-P1-1：MCP server 批准语义 + stdio（三段式批准 / 幂等 / 任务目录 / 断线撤销）

## 目标

对外 MCP 通道（McpHttpServer loopback + Bearer）从「直通执行」升级为 dsh 三段式：

1. **phone_task_request** — 任务申请 → 系统通知 → 批准页 UI 确认 → ACTIVE
2. **phone_local_run** — 逐次运行申请：requestId = 内容 hash（不可改），UI 批准时记录源文件 hash 快照
3. **phone_operation_execute** — 幂等 operationId（重复 ID 不重放）；epoch/TTL/源文件 hash 校验；参数 ≤64KiB、运行 ≤30s、输出 ≤64KiB

配套：
- **任务目录隔离**：file_* 在任务上下文强制 taskRoot 边界，路径越界 / 符号链接 / >128KiB 普通文件拒绝
- **断线撤销**：服务重启 epoch bump → 全部外部授权失效（重连不恢复不重放）；批准 TTL 10 分钟兜底
- **stdio 桥**：PRoot guest 内 `minis-mcp-bridge.py` 把 stdio JSON-RPC（Content-Length 或 line-delimited）转发到 host loopback McpHttpServer（端口 8765..8774 探测，与 BuiltinLoopbackResolver 对齐）

## 非目标（一期）

- 中继 + 端到端加密（用户自有服务器，后续 P1-1b）
- 工具冻结哈希全量：一期对带 `path` 参数的文件做「批准时 hash → 执行前校验」
- HTTP 断线会话语义：一期 = 服务重启 epoch bump + TTL（loopback HTTP 短连接无会话概念）

## 文件布局

新文件（均 ≤800 行，architecture 门禁）：
- `mcp/server/approval/McpApprovalCore.kt` — 纯状态机（任务/操作/幂等/epoch/TTL/hash），零 Android
- `mcp/server/approval/McpApprovalStore.kt` — prefs JSON 持久化 + 静态序列化纯函数（JVM 可测）
- `mcp/server/tools/ApprovalTools.kt` — 三工具 spec + handler
- `mcp/server/tools/McpTaskRoot.kt` — 任务目录纯校验（越界/..、symlink、大小）
- `mcp/server/McpApprovedExecutor.kt` — 批准执行器（校验链 + 钳制 + 派发）
- `ui/settings/McpApprovalsScreen.kt` — 批准页（Apple 风格，先读 awesome-design-md + ui-ux-pro-max）
- `notification/McpApprovalNotifier.kt` — 通知（channel + 深链批准页）
- `assets/default_mount/usr/local/bin/minis-mcp-bridge.py` — stdio 桥（纯 stdlib）

改动：
- `McpToolDeps`（+taskRoot）、`FileTools`（任务上下文边界）、`McpToolDispatcher`（+taskRoot 参数）、`McpServerManager`（接线 + start 时 bumpEpoch）、`McpToolSchemas`（三 schema）、`DeepLinkHandler`（+mcp-approvals）、`Routes`（+MCP_APPROVALS）、`AppNavigation`（+composable）、`MCPIntegrationsScreen`（批准入口行 + 桥用法展示，行数 ≤1149 baseline）
- 测试：`McpApprovalCoreTest`、`McpApprovalStoreTest`（JSON 往返）、`McpApprovalToolsTest`、`McpTaskRootTest`、`McpApprovedExecutorTest`
- 翻译：新 string key × 17 locale（按 en 字母序插入）

## 关键设计决策

1. **epoch 代际**：批准与执行绑定 `epoch`；`McpServerManager.start()` 每次 bumpEpoch → 重启撤销全部授权。TTL = 10 分钟。
2. **requestId 不可改**：`requestId = SHA-256("$taskId\n$tool\n$argumentsJson").hex.take(32)`；submit 时服务端重算比对。
3. **operationId 幂等**：执行完（无论成败）进 executed 集合；重复 execute → 拒绝。
4. **源文件快照**：批准时对 `arguments["path"]`（file_write / file_transfer_push 类，解析到 host 文件）算 SHA-256 存 approval；execute 前重算，变化 → 拒绝。
5. **执行器隔离**：每次 execute 构造带 taskRoot 的独立 McpToolDispatcher（4 线程池并发安全，无共享可变状态）。
6. **taskRoot** = `/var/minis/mcp-tasks/<taskId>/`，PRoot 与 host 同路径解析（PRootKernel.resolveSessionHostPath，SESSION_ID=mcp-server）。
7. **stdio 桥**：MCP stdio 两种 framing（Content-Length 头 / 换行 JSON）双模式解析；urllib 转发；token 走 `MINIS_MCP_TOKEN` env 或 `--token-file`。

## 任务分解（每任务一个 commit，独立门禁）

- **T1 批准核心 + 存储**：McpApprovalCore + McpApprovalStore + 测试。验收：状态机全链红→绿。
- **T2 三工具 + 任务目录 + 执行器**：ApprovalTools + McpTaskRoot + McpApprovedExecutor + 接线 + 测试。
- **T3 通知 + 批准页 UI + 深链 + 翻译**：先读 awesome-design-md + ui-ux-pro-max；门禁四绿 + 17 locale。
- **T4 stdio 桥 + 设置页用法展示 + 文档**：本地 python 冒烟通过。

## 验证

- 本地门禁四件套：architecture / CJK / R.string / announced-tool
- kotlinc 单文件级联判定法（本地只 parse 不编译）
- TDD：测试先行，python 镜像驱动纯函数契约
- CI：Compile / Unit tests / L10n gate
- 桥冒烟：python 伪 stdio client ↔ bridge ↔ 本地 JVM McpServerCore 转发
