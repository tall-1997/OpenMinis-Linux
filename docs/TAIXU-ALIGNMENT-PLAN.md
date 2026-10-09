# taixu 对齐执行计划

基线：`17577a1`
移植源：`/var/minis/workspace/refs/taixu` @ `bf3565a`（对照分析见 `COMPARISON-TAIXU.md`）
决策：① 抽 `:harness` 模块 ② **直接复用源码** ③ ADB/FTP/WebChat 同批

> **状态同步（2026-10-09）**：勾选 = 已入库（含测试、architectureCheck 过）。
> 勾选项分两档：**[x]** 已落地；**[x]（已接线）**  additionally 有 app 主源码调用点。
> 只落地未接线的件在 :harness 里带测试但零 app 引用，按 `COMPARISON-TAIXU.md`
> 的口径**不算能力补齐**，只是「可复用件已入库」。接线清单见各条备注。
>
> **验证基线修正**：原写「基线 5220 tests」从未被复现。实测口径为
> `:app:testReleaseUnitTest` = **2893**（359 个结果 XML）+ `:harness:test` = **143**，
> 均 0 失败（2026-10-09，c28a10e 之后：app **2927** / harness **155**）。
> 后续每批以这两个数为新基线。**P0-2（子代理租约与结果裁定）与 P0-3 均已闭环。**
> 后续每批以这两个数为新基线。**P0-3（统一重试/恢复层）已于 `62fc977` 闭环。**

## 模块策略（已定）

taixu `harness/` 是 **Android 库**（依赖 Room/OkHttp/Ktor/Koin，14 文件引 `android.*`）。
我方 `:harness` 做成 **JVM 库**（对齐 `:core:model` / `:core:common` 既有模式，用 `minis.kotlin.library` 约定插件）。

| 类 | 处理 |
|---|---|
| 纯 JVM（零 `android.*`） | 直接进 `:harness` |
| `android.util.Log` | 换成 `:harness` 内日志接缝（`System.err` / 宿主注入） |
| `android.content.Context` | 构造参数注入，调用方传值 |
| Room 实体 | 留 `:app`，`:harness` 只放接口 |

## 批次

### 第一批 — 纯逻辑移植（`5a9c721`，全完成）
- [x] 1.1 建 `:harness` 模块骨架（settings + build.gradle.kts + 约定插件）
- [x] 1.2 `text/UnifiedDiffGenerator.kt`（`a50de17`，**已接线**：file_edit 结果附加有界 unified diff）
- [x] 1.3 `effects/RetryPolicy.kt`（`62fc977`，**已接线**：ToolRetry 接 web_fetch
      与宿主工具分支，只重试 NETWORK_ERROR / TIMEOUT 瞬态失败）
- [x] 1.4 `metrics/RunMetrics.kt`（`a50de17`，**已接线**：agent 循环埋点 + 收尾单行日志）
- [x] 1.5 `validation/ToolSchemaValidator.kt`（`f796181`，**已接线**：executeTool 派发入口；MCP 暂跳过）
- [x] 1.6 `text/TextReplacers.kt` + `validation/ToolCallLoopDetector.kt`
- [x] 1.7 配套测试全部移植
- [x] 1.8 `architecture-policy.json` 等价物 + preBuild 检查（`2e75a0a`）

### 第二批 — Agent 可靠性
- [x] 2.1 `checkpoint/`（720 行，纯 JVM）（`123db12`，**已接线**）
      接线面：`CheckpointBridge` + file_write/file_edit 自动捕获 +
      `ChatRewindDialog`「撤回到此轮」+ 会话删除清盘。
      **偏离计划**：不是「接我方 FileCheckpointTool」，而是**移除**该模型侧工具——
      rewind 是用户动作不是模型工具（taixu 语义），捕获改为写工具自动挂钩。
- [x] 2.2 `operation/OperationCoordinator.kt`（348 行，纯 JVM）（`6b0d8bc`，**未接线**）
      落地面：Coordinator 本体 + 收窄的 `OperationRuntimeRepository` 接缝（11 方法）
      + 运行时实体形状（model/HarnessRuntimeEntities）+ 事件总线（events/）+
      `HarnessLanes.MAIN_LANE` 单源（queue 改引用它）。9 例移植测试全绿。
      接线（把 ChatViewModel 的 send/工具/收尾挂到 Coordinator 转移上）留待后续批次。
- [x] 2.3 `subagent/SubagentClaim.kt`（252 行）（`ca70311` 前置 + `c28a10e` **已接线**）
      轨迹从 lane 的工具执行 lambda 收口 plum 出（被拦调用也记），收尾经
      adjudicateLaneOutcome 折进报告：无 claim 块 fail-open 逐字节不变；有则
      干净报告 + 裁定段进父汇总（spawn_agent 返回值 / check_agent collect 同源）。
      写路径租约（SubagentLaneRunner）未移植——我方 WritePathGuard 前缀校验仍在，
      租约语义留待批次三编排器
- [ ] 2.4 `compaction/`（1011 行，替换 `android.util.Log`）
      前置已落地：`SessionTreeStore` + `SessionTreeRepository` 接缝 + `HarnessLog`
      （`2b61fa7`，12 例自定验收测试）。仍缺 ProviderClient /
      ContextWindowPolicy 接缝—— compaction 的投影要读 provider 形状。
- [ ] 2.5 `prompt/`（1233 行，Context 注入）
- [x] 2.6 `queue/PromptQueueManager.kt`（135 行）（`ebd0b59`，**已接线**）
      **超出计划**：不止 Room 接口化——做了文件持久化 + 完整宿主接线
      （忙时入队落盘 / 消费确认 / 冷开还原 / 撤回·重试·截断删盘），
      并修掉上游两个持久化缺陷（会话 id→文件名非单射、反查二次转义静默不删）。
- [x] 2.7 `effects/DanglingToolCallPlanner.kt` + `ToolReplayPolicy.kt` + `ToolOutputRetention.kt`
      （`123db12` 落地，`62fc977` **已接线**）：planner 进冷启动恢复（SAFE 只读
      悬空调用重放）与 dropOrphanedToolParts 占位文案；retention 进 ToolOutputSpill
      截断方向（命令尾偏向 / 读取头偏向 + 整行对齐 + 超长行折叠）。

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

每批结束跑 `./gradlew :app:testReleaseUnitTest` + `:harness:test` +
`:architectureCheck`，确认零失败、棘轮不越线。每批独立 commit。
当前基线：app **2927** / harness **155**（c28a10e）。
