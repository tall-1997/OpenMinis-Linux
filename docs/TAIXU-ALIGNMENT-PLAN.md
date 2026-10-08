# taixu 对齐执行计划

基线：`17577a1` · 基线测试 **5220 tests / 0 failures**（`:app:testReleaseUnitTest --offline`，6m50s）
移植源：`/var/minis/workspace/refs/taixu` @ `bf3565a`
决策：① 抽 `:harness` 模块 ② **直接复用源码** ③ ADB/FTP/WebChat 同批

## 模块策略（已定）

taixu `harness/` 是 **Android 库**（依赖 Room/OkHttp/Ktor/Koin，14 文件引 `android.*`）。
我方 `:harness` 做成 **JVM 库**（对齐 `:core:model` / `:core:common` 既有模式，用 `minis.kotlin.library` 约定插件）。

| 类 | 处理 |
|---|---|
| 纯 JVM（零 `android.*`） | 直接进 `:harness` |
| `android.util.Log` | 换成 `:harness` 内 `HarnessLog` |
| `android.content.Context` | 构造参数注入，调用方传值 |
| Room 实体 | 留 `:app`，`:harness` 只放接口 |

## 批次

### 第一批 — 纯逻辑移植
- [ ] 1.1 建 `:harness` 模块骨架（settings + build.gradle.kts + 约定插件）
- [ ] 1.2 `text/UnifiedDiffGenerator.kt`（142 行，零依赖）
- [ ] 1.3 `effects/RetryPolicy.kt`（26 行，零依赖）
- [ ] 1.4 `metrics/RunMetrics.kt`（119 行，依赖 `ChatUsage`）
- [ ] 1.5 `validation/ToolSchemaValidator.kt`（355 行，需改写 `McpToolInfo`/`ProviderClient`/`McpToolApiName` 引用）
- [ ] 1.6 `text/TextReplacers.kt` + `validation/ToolCallLoopDetector.kt`（同目录，一并）
- [ ] 1.7 配套测试全部移植
- [ ] 1.8 `architecture-policy.json` 等价物 + preBuild 检查

### 第二批 — Agent 可靠性
- [ ] 2.1 `checkpoint/`（720 行，纯 JVM）→ 接我方 `FileCheckpointTool`
- [ ] 2.2 `operation/OperationCoordinator.kt`（348 行，纯 JVM）
- [ ] 2.3 `subagent/SubagentClaim.kt`（252 行）→ 接我方 `WritePathGuard`
- [ ] 2.4 `compaction/`（1011 行，替换 `android.util.Log`）
- [ ] 2.5 `prompt/`（1233 行，Context 注入）
- [ ] 2.6 `queue/PromptQueueManager.kt`（135 行，Room 接口化）
- [ ] 2.7 `effects/DanglingToolCallPlanner.kt` + `ToolReplayPolicy.kt` + `ToolOutputRetention.kt`

### 第三批 — 大件
- [ ] 3.1 工作流 DAG（harness workflow 2854 + core/model/workflow 1514 + UI）
- [ ] 3.2 JGit Git 工作台（3014 行，替换 `GitCli.kt` 335 行）
- [ ] 3.3 工作区管理 + ZIP 导入（Zip Slip 防护）

### 第四批 — 补齐
- [ ] 4.1 A2UI 表面协议
- [ ] 4.2 Dual Agent
- [ ] 4.3 无线 ADB / FTP / WebChat
- [ ] 4.4 浏览器 CDP / Fetch 拦截
- [ ] 4.5 设置检索

## 验证约定

每批结束跑 `./gradlew :app:testReleaseUnitTest --offline`（基线 5220）+
新增 `:harness:test`，确认零失败。每批独立 commit。
