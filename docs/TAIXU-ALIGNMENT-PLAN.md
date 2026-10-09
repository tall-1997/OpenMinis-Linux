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
> `:app:testReleaseUnitTest` + `:harness:test`，当前 **app 2870 / harness 273**
> 均 0 失败。后续每批以这两个数为新基线。
>
> ## 进度总账（2026-10-09，`671f7d8`）
>
> **完成度**：第一批 **8/8**（全接线）；第二批 **5/7 闭环**（2.4 策略半接线、
> 本体明确推迟；2.5 第一刀 BM25 已落）；第三批 **接缝基建 4/4 + 循环片段一**
> （特性件 3 件**暂停**）；第四批 5 件**暂停**。
> **范围决策（2026-10-09，老板拍板）**：批次三/四特性件暂不做——接缝基建
> 是 P0-1 前置、已完成不受影响；3.x/4.x 条目挂起不排期，重启需老板点头。
> P0 三项：P0-2 ✓（claim 裁定 + 租约判定半边）、P0-3 ✓、
> P0-1 ◐（纯逻辑层与四道接缝已立，循环本体未迁）。
>
> **:harness 规模**：主源码 **47 文件 / 6010 行**（15 包），测试 **31 文件 /
> 4043 行**。对照 taixu harness 240 文件 / 44,808 行（22 子包）——已迁的是
> 我方缺口对应的纯逻辑子集，不是镜像搬运。
>
> **批次三接缝**（P0-1 剩余 + 2.3 执行半边 + 2.4 本体的共同前置）：
>
> | 接缝 | commit | 消费状态 |
> |---|---|---|
> | 一 ProviderStreamClient | `6445c13` 立体，片段三消费 | 流入口已走接缝 |
> | 二 ToolExecutorPort | `6eb3f4b` 立体，本刀消费 | 工具轮顺序执行已走接缝 |
> | 三 ConversationPort | `6eb3f4b` | 恢复链已消费 |
> | 四 UiEventSink | `b96cde4` | 落地即消费（两条拒绝路径） |
>
> **循环迁移**：AgentLoopExt **2286 → 2177**（片段一拒绝路径 + 片段二工具轮
> 本体 + 片段三流入口与 id 去重，三刀累计 -109——循环变小、harness 变大是
> P0-1 剩余的唯一正确方向）。四道接缝全部消费。台账基建：
> FileHarnessRuntimePersistence（单文件五表）+ OperationBridge 循环转移挂点 +
> 崩溃窗口恢复链（`cc81fc5` / `4e99a80` / `d75c920`）。

## 模块策略（已定）

taixu `harness/` 是 **Android 库**（依赖 Room/OkHttp/Ktor/Koin，14 文件引 `android.*`）。
我方 `:harness` 做成 **JVM 库**（对齐 `:core:model` / `:core:common` 既有模式，用 `minis.kotlin.library` 约定插件）。

> **P0-1 迁移路线**（详见顶部进度总账）：纯逻辑层已于 `63fbb89` 迁入；四道
> Android 接缝已立齐（ProviderStreamClient / ToolExecutorPort / ConversationPort /
> UiEventSink）；循环片段一（执行前拒绝）已上接缝，AgentLoopExt 首次收缩。
> 剩余 = 工具轮本体（接缝二消费）→ 循环骨架（接缝一开窗）→ 上下文治理，
> 逐片段推进，每片独立可回滚。

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
- [x] 1.6 `text/TextReplacers.kt` + `validation/ToolCallLoopDetector.kt`（**已接线**：
      TextReplacers 进 file_edit 文本替换；LoopDetector 进主循环工具记账与拒绝路径）
- [x] 1.7 配套测试全部移植
- [x] 1.8 `architecture-policy.json` 等价物 + preBuild 检查（`2e75a0a`）

### 第二批 — Agent 可靠性
- [x] 2.1 `checkpoint/`（720 行，纯 JVM）（`123db12`，**已接线**）
      接线面：`CheckpointBridge` + file_write/file_edit 自动捕获 +
      `ChatRewindDialog`「撤回到此轮」+ 会话删除清盘。
      **偏离计划**：不是「接我方 FileCheckpointTool」，而是**移除**该模型侧工具——
      rewind 是用户动作不是模型工具（taixu 语义），捕获改为写工具自动挂钩。
- [x] 2.2 `operation/OperationCoordinator.kt`（348 行，纯 JVM）（`6b0d8bc` 落地，`cc81fc5`+`4e99a80` **已接线**）
      落地面：Coordinator 本体 + 收窄的 `OperationRuntimeRepository` 接缝（11 方法）
      + 运行时实体形状（model/HarnessRuntimeEntities）+ 事件总线（events/）+
      `HarnessLanes.MAIN_LANE` 单源（queue 改引用它）。9 例移植测试全绿。
      接线面：磁盘运行时台账 FileHarnessRuntimePersistence（单文件五表，settle/
      finish 事务天然原子）+ OperationBridge 循环转移挂点（begin/providerIntent/
      providerSettled/toolIntent/toolSettled/suspend/finish，挂点在主循环派发点，
      lane 调用不进主台账）+ 崩溃窗口恢复链（台账合成 → DanglingToolCallPlanner）。
- [x] 2.3 `subagent/SubagentClaim.kt`（252 行）（`ca70311` 前置 + `c28a10e` **已接线**）
      轨迹从 lane 的工具执行 lambda 收口 plum 出（被拦调用也记），收尾经
      adjudicateLaneOutcome 折进报告：无 claim 块 fail-open 逐字节不变；有则
      干净报告 + 裁定段进父汇总（spawn_agent 返回值 / check_agent collect 同源）。
      写路径租约（SubagentLaneRunner）判定半边已于 `b28c566` 接线：lease 闸门在
      lane 派发层拒越界写，被拒调用进轨迹清单供裁定段引用。执行半边（lane 运行器
      本体）仍留批次三
- [ ] 2.4 `compaction/`（1011 行，替换 `android.util.Log`）
      **策略半已接线**（`69d2320`）：ContextWindowPolicy 缺的三件（分桶估算 /
      巨型用户消息截断 / 4MB 请求体硬限 + 实测 schema 预留）移植并接进
      effectiveAgentHistory 与 Anthropic 序列化出口。**compaction/ 本体替换明确
      推迟**：我方压缩带 marker 分离 / digest 注入 / detached anchor 等上游没有的
      语义，整换是退化；ProviderStreamClient 接缝已立（`6445c13`），本体评估
      窗口已开，排在循环骨架迁移之后
- [ ] 2.5 `prompt/`（1233 行，Context 注入）
      **第一刀已落**：MemoryRecallScorer（BM25+CJK bigram+泛化抑制）进
      harness 并嫁接 MemoryRecallEngine.recall（我方新近度/召回计数/标题语义
      保留）。纯三件套无资产可路由暂不搬；SystemPromptBuilder(682) 深绑
      prompts/system/*.md——是否引入分层资产架构待决策。
      **形状已勘明（2026-10-09）**：PromptRouter(135)/PromptVariableResolver(70)/
      PrivilegeSectionRenderer(37)/DistroCatalog(23) 纯逻辑可直迁；MemoryRecallSelector
      (252) 对应我方记忆注入（有真实嫁接点）；SystemPromptBuilder(682) 深绑上游
      资产结构（prompts/system/*.md + load_rule 工具），整搬需先决策是否引入
      分层资产架构——嫁接面在 runAgentLoop 调用方的 systemPrompt 组装处。
- [x] 2.6 `queue/PromptQueueManager.kt`（135 行）（`ebd0b59`，**已接线**）
      **超出计划**：不止 Room 接口化——做了文件持久化 + 完整宿主接线
      （忙时入队落盘 / 消费确认 / 冷开还原 / 撤回·重试·截断删盘），
      并修掉上游两个持久化缺陷（会话 id→文件名非单射、反查二次转义静默不删）。
- [x] 2.7 `effects/DanglingToolCallPlanner.kt` + `ToolReplayPolicy.kt` + `ToolOutputRetention.kt`
      （`123db12` 落地，`62fc977` **已接线**）：planner 进冷启动恢复（SAFE 只读
      悬空调用重放）与 dropOrphanedToolParts 占位文案；retention 进 ToolOutputSpill
      截断方向（命令尾偏向 / 读取头偏向 + 整行对齐 + 超长行折叠）。

### 第三批 — 大件（**特性件暂停，2026-10-09 老板拍板**）

> 接缝基建（四道接缝 + 循环片段）是 P0-1 前置，**已完成、不受本决策影响**。
> 下列特性件挂起不排期，重启需老板点头。

- [ ] 3.1 工作流 DAG（harness workflow 2854 + core/model/workflow 1514 + UI）
- [ ] 3.2 JGit Git 工作台（3014 行，替换 `GitCli.kt` 335 行）
- [ ] 3.3 工作区管理 + ZIP 导入（Zip Slip 防护）

### 第四批 — 补齐（**整批暂停，2026-10-09 老板拍板**）

> 4.3 是新增攻击面（ADB/FTP/WebChat 对外服务），暂停期内威胁模型不立项。

- [ ] 4.1 A2UI 表面协议
- [ ] 4.2 Dual Agent
- [ ] 4.3 无线 ADB / FTP / WebChat
- [ ] 4.4 浏览器 CDP / Fetch 拦截
- [ ] 4.5 设置检索

## 验证约定

每批结束跑 `./gradlew :app:testReleaseUnitTest` + `:harness:test` +
`:architectureCheck`，确认零失败、棘轮不越线。每批独立 commit。
当前基线：app **2870** / harness **273**。
