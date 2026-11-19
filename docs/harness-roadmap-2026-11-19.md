# Harness 上下文/记忆/编排 线路图（2026-11-19）

> 范围：上下文工程（压缩保留、上下文与提示词管理）、模型记忆（会话记忆 / 长期记忆 / 记忆系统）、
> 编排引擎、工具链集成、安全治理、错误处理与恢复机制。
> 目标：提升可靠性、可控性、开发效率、性能与成本。
> 方法：拉取五个参考项目的**最新源码**（浅克隆，记录 HEAD），逐模块对照本仓现状，产出差距清单与分阶段线路图。
> 本文只是线路图，不含代码改动。

---

## 〇、证据基础（真实拉取结果）

五个仓库已浅克隆到 `D:\minis-linux\.agents\refsrc\`，HEAD 与规模如下（`git log -1` 实测）：

| 仓库 | HEAD | 提交日期 | 文件数 | 说明 |
|---|---|---|---|---|
| bytedance/deer-flow | `c9ecf57` | 2026-11-19 | 4,900 | LangGraph 超代理 harness，harness/app 严格单向依赖 |
| Luoyehe/codex-harness | `d2cfe6a` | 2026-11-18 | 3,288 | v2.4.0，单管理员 Codex 网页工作台，固定上游 CLI 0.169.0 |
| HKUDS/OpenHarness | `9b2efd7` | 2026-06-04 | 508 | Python，v0.1.9，`src/openharness` 30+ 模块 |
| zai-org/ZCode | `fec97590` | 2026-11-13 | 18,710 | v3.18.2，五层包结构 + V4 协议投影 |
| deepseek-ai/deepseek-harness | `2e39e704` | 2026-11-18 | 22,275 | dsh 0.2.2-rc.5，Cordis 插件树，一切皆插件 |

**实际读过的文件（不是只看 README 标题）**：

- dsh：`docs/architecture.md`（1-160 行，turn/step 生命周期 + 事件三域 + "model-visible means logged"）、
  `docs/agent-lifecycle.md`（完整 sequenceDiagram）、`docs/capability-seams.md`（827 行 seam 图）、
  `packages/compaction/compaction/src/{checkpoint,tool-pairing}.ts`（全文）、
  `compaction-basic/README.md`（1-150 行：threshold/retain 公式、region transaction、warm prefix）、
  `compaction-tool-result-pruner/README.md`（1-120 行：code-point 预算、shadow-price、replay 引用）、
  `guard/repeat-tool-reminder/README.md`（1-120 行：advisory 语义、WeakMap 链、reset 规则）、
  `session/session-persistence-jsonl/README.md`（1-140 行：vN 代际、fsync、torn-tail、独占发布）、
  `llm/token-meter/README.md`（1-60 行：replay 测量、投影、provider usage 复用条件）、
  `context/time-context/README.md`、`context/harness-dev-context/README.md`、`session/session-title/README.md`。
- codex-harness：`ARCHITECTURE.md`（全文 104 行）、`src/core/ledger.ts`（1-45 行：receipts 表、
  accepted/not_sent/unknown）、`src/core/budget.ts`（全文：count+bytes 预留/释放）、`FEATURES.md` 头部、
  `src/{core,runtime,gateway/src}` 文件清单。
- OpenHarness：`memory/{manager,relevance,schema}.py`（1-80/1-60/1-60 行）、`prompts/context.py`（1-70 行）、
  `engine`/`coordinator`/`tasks`/`state` 文件清单。
- deer-flow：`docs/ARCHITECTURE.md`（1-90 行：四服务拓扑、harness/app 边界、8 段 middleware 链）、
  `docs/task-continuity.md`（1-70 行：task_note / history_search / history_read 三个工具与偏移契约）、
  `backend/packages/harness/deerflow` 模块清单与 memory 后端清单。
- ZCode：`docs/architecture/architecture.md`、`packages/services`（90+ subject）、包分层清单。
- 本仓：`architecture-policy.json`、`ui/chat` 全部 283 个文件名与体积、`data/db` 全部 45 个文件（19 实体 / 12 DAO）、
  `agent`/`guard`/`quota`/`security`/`tools` 文件清单、`scripts/ci` 12 个门禁脚本、
  `agent/{LoopErrorGate,ContextSaverRuntime}.kt`、`guard/{ActionGuard,LoopGuard}.kt`、
  `data/repository/{MemoryIndex,MemoryRepository}.kt`、`ui/chat/ChatViewModelTokenExt.kt`、
  `agent/MessageTransformers.kt`（TimeReminder/WorkspaceReminder）。

**未做**：没有运行五个参考项目的任何代码，没有做性能基准；所有对照来自源码与文档阅读。
本仓状态以当前工作区为准（`0e62a881` + 两个本地提交 `d82f3797`，6809 测试通过）。

---

## 一、本仓现状（可量化的部分）

### 1.1 规模

| 模块 | kt 文件 | 行数（含空行 / 非空） | 备注 |
|---|---|---|---|
| `ui`（全域） | 527 | 148,152 / 141,439 | 含 chat / settings / media / sessions |
| `ui/chat` | 283 | 71,832 | `ChatViewModel.kt` 238,167 B / **4,613 行（架构上限 4,613，已到顶）**、`ChatScreen.kt` 354 KB |
| `sandbox` | 112 | 29,518 / 27,304 | PRoot / Shizuku / root |
| `provider` | 108 | 24,040 / 22,225 | 8 家 provider + 配额适配 |
| `tools` | 89 | 20,173 / 18,730 | 含子代理、MCP 桥、UI 动作 |
| `mcp` | 40 | 7,131 / 6,475 | |
| `agent` | 24 | 5,652 / 5,187 | 含 Aging / LoopErrorGate / ContextSaver / Transformers |
| `quota` | 18 | 4,830 / 4,391 | |
| `security` | 17 | 2,071 / 1,928 | Capability / SecurityGate / OutboundMasker |
| `automation` | 14 | 3,501 / 3,257 | |
| `offload` | 14 | 2,587 / 2,321 | 闹钟/通知/天气/Shizuku |
| `scheduled` | 13 | 2,480 / 2,327 | |
| `evolution` | 12 | 1,804 / 1,654 | 信念维护 / 周反思 / 偏好 |
| `plugins` | 12 | 2,002 / 1,854 | |
| `goal` | 6 | 676 / 617 | |
| `knowledge` | 5 | 826 / 737 | |
| `guard` | 3 | 573 / 505 | ActionGuard / LoopGuard / OutwardPacing |
| `session` | 2 | 69 / 61 | 仅 port 与 attachment |

测试：main 上 **779 个测试文件**；本轮结束后的 **6 893 个用例全绿**（本分支实测）。
CI 门禁：`scripts/ci` 下 **10 个 Python 校验脚本 + 1 个 shell + 3 个基线与白名单 JSON**
（架构棘轮、CJK 字面量、字符串引用、announced tools、schema drift、工具执行契约、
工具标题/预算契约、l10n 未翻译、思考显示名、指令状态）。

### 1.2 已有的记忆/上下文设施

| 设施 | 文件 | 现状 |
|---|---|---|
| 会话历史 | `data/db/{MessageEntity,MessageVersionEntity}.kt` + Room | 消息行 + 版本行；请求投影由内存 `agentHistory` 派生 |
| 压缩标记 | `data/db/CompactMarkerEntity.kt` | v1/v2 两代 marker，v2 有 preAnchor/postAnchor 锚 |
| 压缩实现 | `ui/chat/ChatViewModelCompact*`（实为 9 个文件，实测） | two-shot、split、tail、retry 输入、reconcile |
| 尾预算 | `ChatViewModelPostAnchorTail.kt` | 35% 窗口 / ≤60k，notice+digest 预留 1200 tok |
| 丢弃摘要 | `DroppedRoundDigest.kt` | 24 行 / 3000 字 / 扫描 512 条 |
| 工作记忆 | `WorkingMemoryLedger.kt` | 本轮已补：按会话 JSONL 落盘、commit 后 ack、失败 undo |
| 任务锚点 | `PromptAnchors.kt` | 最新实质用户请求 > DB 首条；薄跟进词表 |
| 记忆仓库 | `data/repository/Memory*.kt`（13 个模块） | 日记忆 markdown + GLOBAL.md + 40 行目录索引 + 预算/分块/每日闸/融合/沉积 |
| 记忆召回 | `MemoryRecallEngine.kt` + `MemoryEmbeddingEntity` | 关键词 + CJK 二元；embeddings 表存在但未用于召回 |
| 工具结果老化 | `agent/ToolResultAging.kt` | 24k 阈值，头 160 + 尾 160 |
| 渐进裁剪 | `ContextSaverRuntime.kt` | BALANCED/AGGRESSIVE/OFF，最便宜优先 |
| 令牌测量 | `data/BPETokenizer.kt` + `ChatViewModelTokenExt.kt` + `TokenUsageCalibration` | 估算 + 校准 + memo；门限/显示/环图三处各取所需 |
| 错误分类 | `data/RetryClassifier.kt`、`provider/ContextOverflowDetector.kt`、`agent/LoopErrorGate.kt` | 配额优先于瞬时；溢出单独判定 |
| 防循环 | `guard/{ActionGuard,LoopGuard,OutwardPacing}.kt` | 四阶梯（Repeat/NoEffect/Reread/Failure），会 **stop** |
| 运行时上下文 | `agent/MessageTransformers.kt` | TimeReminder（关键词门控）、WorkspaceReminder |
| 安全 | `security/{Capability,SecurityGate,OutboundMasker,...}.kt` | 能力 + 门 + 外发掩码 |
| 失败记忆 | `tools/ToolFailureMemory.kt` | 本轮已补：近似参数聚类（同工具 + 同错误类 + Jaccard ≥0.75） |

结论：**记忆/压缩的工具箱已经很满**（`data/repository/Memory*.kt` 就有 13 个），缺的不是"再加一个机制"，而是
**度量口径统一、事务化、可检索、可回执**这四件基础工程件。

---

## 二、五仓对照（按 harness 八个关注点）

| 关注点 | deepseek-harness | codex-harness | OpenHarness | deer-flow | ZCode | 本仓（本轮已落地） |
|---|---|---|---|---|---|---|
| **会话日志** | append-only `SessionEvent`，`deriveMessages()` 投影；"model-visible means logged"；vN 代际 + zstd + fsync + torn-tail 恢复 | receipts 表 + 操作日志，正文不入库 | `state/store.py` + jsonl | LangGraph checkpoint + `checkpoint_*` 5 个模块 | V4 topic → 只读 UI store | Room 消息行 + 内存 `agentHistory`；**已落地 `SessionJournal`**（append-only JSONL；turn/step/request/tool/compaction 事件；按字节封顶） |
| **压缩** | bracket-first 事务（start→summary→summary+replacement→end），孤立 start = 持久锁，retry 需"replacement 代际推进" | 无（交给上游 CLI） | 无独立压缩层 | SummarizationMiddleware + checkpoint 补丁 | 原生压缩阈值 + 范围 | 内存状态机 + marker；**已落地 `CompactionTransaction`**（start→summary→end 括号 + 中断可识别 + 失败冷却） |
| **工具结果裁剪** | 独立 pruner seam：8192/4096/1024 code-point，shadow-price 事件，`sourceEventSeqs` 回指原事件 | — | — | — | — | `ToolResultAging` 24k/160/160，**不按压力门控、无回指** |
| **配对不变量** | `tool-pairing.ts` 单一实现 + 增量缓存，所有剪裁共用 | — | — | — | — | **已收敛为 `ToolPairingCore`**；`ResidentWindow` / `PostAnchorTail` / 修复路径全部委托 |
| **令牌测量** | `ctx.tokenMeter`：replay 确定性、provider usage 仅在信封相同时复用、投影供 UI | budget（count+bytes 预留） | `engine/cost_tracker.py` | `context_usage.py` | `usage-stats` service | 估算 + 校准 + memo，**门限值与显示值曾经分叉（本轮修过一次）** |
| **写入回执** | 无（插件化，靠 session 事件） | **accepted / not_sent / unknown 三态**；operationId；"unknown 不是可重试失败"；跨写边界不自动重放 | — | `persistent_writes.py` + `hitl_approval` | 操作回执 | **已落地 `ToolReceiptStore`**（not_sent / accepted / abandoned；未决回执在结果里告知） |
| **记忆 schema** | session 投影 + checkpoint；无用户长期记忆层 | 记忆存原生 CLI 侧 | **frontmatter 全字段**（type/scope/importance/ttl_days/disabled/supersedes/tags）+ signature 去重 + `already_surfaced` + freshness + 可选 reranker + `mark_memory_used` | deermem/honcho/mem0 三后端 + eviction/retrieval/updater | `memory` service | 日记忆 markdown 无元数据；有反唠叨 streak；embeddings 表未用 |
| **压缩后可检索** | 完整日志可 replay | 历史分页 + 完整性标记 | — | **`task_note` + `history_search` + `history_read`**（8×600 字精确偏移 + 4000 字分页） | — | **已落地 `history_search` / `history_read`**（精确 code-point 偏移；单行读穿 BodyStore） |
| **编排** | profile+bundle+有序 patch 层；一切插件；`--dump-config` | 四进程分权（core/runtime/gateway/web）+ 固定 RPC 表 | coordinator/tasks/swarm | middleware 链（8 段）+ harness/app 单向依赖 CI 断言 | 五层包 + 服务描述符 + client proxy | `ChatViewModel` 单类族承担编排/装配/压缩/门限 |
| **时钟上下文** | 每 eligible step 注入（10 分钟最小间隔），ISO+偏移+IANA 区+elapsed，快照语义 | — | — | — | — | **关键词 + 间隔双路**（默认 10 分钟）；请求装配期注入，带 elapsed |
| **防循环** | advisory only（3/5/8），从不阻塞，逐 agent，内存态 | — | — | — | — | 四阶梯且**会 stop 会话**（Repeat 12 拒发） |
| **门禁** | 生成式文档图 + 配置目录 + 契约套件 | 逐项证据 JSON + WORKLOG | 少量 | harness 边界 CI 测试 | architecture-baseline.json | **12 个脚本，比多数参考更严** |

---

## 三、差距清单（按严重度排序）

每条给：现状证据 → 为什么是问题 → 参考做法 → 建议。

### G1【P0】没有 append-only 会话事件日志，"模型看到过什么"只能靠两处推断

**现状**：`data/db/MessageEntity` 存 UI 行，`agentHistory` 存请求投影，压缩 marker 存边界。
`effectiveAgentHistoryUncounted()` 每次从可变列表重新派生。本轮两个 P0（未摘要轮次被静默丢弃、
门限自蒙蔽）都是**只能靠读代码推演**才定位的，因为没有一个地方记录"这次请求实际带了哪些消息"。

**参考**：dsh 的 `session/event` 是唯一事实源，`deriveMessages()` 只是投影；每次请求可从日志完整重建；
`assistant/message` 内嵌产生它的紧凑时间流，"Model-visible means logged"。

**影响**：任何压缩/裁剪 bug 都只能靠人工比对两条数据路径；无法做 replay 回归测试；
无法回答"模型第 42 步到底看到了什么"。

**建议**：新增 `SessionJournal`（每会话 append-only JSONL，与 `WorkingMemoryStore` 同构），
记录 `turn/start`、`step/start`、`request/assembled`（含 summary 边界、kept 区间、token 估算）、
`tool/call`、`tool/result`、`compaction/start|summary|end`、`turn/end`。
**第一阶段只"写但不读"**（影子日志），跑一周比对 `deriveMessages(journal)` 与现有 `agentHistory`
是否逐字节一致；一致后再切换为读取源。这一步同时也是 G2/G3 的地基。

---

### G2【P0】压缩不是事务，崩溃/中途失败没有持久证据

**现状**：`_isCompacting`、`compactJob`、`_compactSummary`、`_cachedLatestMarker` 全是内存字段。
本轮加了 `succeeded=false` 不写 marker，但那只在**进程内**成立；进程死在中途，
下次启动看到的是"压缩没发生"，而 summary 调用可能已经花掉 1-2 次模型调用。

**参考**：dsh 的 bracket-first——`compaction/start` 同步落盘 → 摘要 → 重校验 →
`compaction/summary` + 替换 user/message → **恰好一次** `compaction/end`。
"未匹配的 start 就是持久锁"；retry 授权条件是"替换代际推进"，不是布尔返回值。

**影响**：重复付费；压缩进度不可观测；"失败的压缩是否该重试"靠调用方猜。

**建议**：在 G1 的 journal 上落 `compaction/start|summary|end` 三个事件；
启动时扫描未配对 start → 标记为 `interrupted`（不阻塞，但要上报并在 UI 显示）；
用 `replacementGeneration` 递增作为唯一 retry 授权判据，替换现在的 `succeeded` 布尔。

---

### G3【P0】令牌测量没有单一权威，门限/显示/环图各算一套

**现状**：`estimateMessagesTokens`（带校准 + memo）在 `ui/chat` 层；
`contextTokensForPolicy` 与 `contextTokensForDisplay` 是两个口径；
`_lastTurnContextTokens` 来自 provider usage。本轮已经因为"门限测蒙蔽切片"修过一次，
又因为"显示 un-budgeted 值"修过一次——同一类 bug 出现两次，说明根因是**没有单一测量服务**。

**参考**：dsh 的 `ctx.tokenMeter.measure(session)` 从日志 replay 得出，确定性、零模型调用；
provider usage **仅在请求信封完全相同时**复用；`contextPressure`/`contextBreakdown`/`tokenUsage`
四个投影供 compaction、占用显示、遥测共享同一结果。

**影响**：每加一处 UI 读数就可能与门限分叉；压缩决策与用户看到数字不一致；成本不可解释。

**建议**：抽 `TokenMeter`（纯对象，入参 `(journalSnapshots, route)`，出参
`totalTokens / surfaceTokens / pressure / breakdown`），所有消费方只读它；
provider usage 作为 anchor 只在信封 hash 相同时覆盖估算。

---

### G4【P1】工具结果裁剪不按压力门控，且裁剪后无法回指原文

**现状**：`ToolResultAging` 在超过 24k 阈值时对"最新 6 条之外"的结果折叠成 160+160 头尾。
`ToolOutputSpill` 已把大输出落盘，但 aging 的占位符没有指向 spill 文件的引用，
模型想回读时只能看到"… [tail]"。

**参考**：dsh 的 pruner 只在"压缩触发条件成立"后运行（低于压力完全不碰），
替换事件通过 `sourceEventSeqs` 回指原事件，replay 能拿到原始输入；
并有 `compaction/prune` 影子价格事件让纯消费者可减。

**影响**：每次请求都在改写工具输出（破坏 provider 前缀缓存）；
模型无法从折叠结果恢复原文，只能重跑工具（浪费一次调用）。

**建议**：aging 前置"压力已超阈值"判定；占位符里写 spill 路径 + 可 `file_read` 的偏移；
记录 `pruned` 标记供 replay 引用。

---

### G5【P1】tool_use/tool_result 配对逻辑有三份实现

**现状**：
1. `PostAnchorTail.splitRounds`（本轮新增 `hasPendingToolUse`）
2. `retention/ResidentWindow.legalBoundaries`（本轮从 private 提到 internal）
3. `ChatViewModel.dropOrphanedToolParts`（下游兜底修复）

三份实现各自演化。本轮 `legalTailStart` 改成复用 (2)，但 (1) 与 (3) 仍是独立逻辑。

**参考**：dsh 只有 `tool-pairing.ts` 一处：`assistant/message` 里 tool-call 数 +1，
`tool/result` −1，累计为 0 的切点才合法；带 `WeakMap<Session, BalanceCache>` 增量缓存，
增删都走同一 fold，且对"result 先于 call"直接抛错（把损坏暴露出来，而不是静默修复）。

**影响**：任何一处改了配对规则，另外两处不会同步 → 孤儿 tool_use 类 bug 反复出现
（本轮修的两个 P0 里就有一个是配对切点算错）。

**建议**：抽 `ToolPairing`（纯对象）：`isBalancedBefore(list, idx)` / `isBalancedAfter` /
`legalCuts(list)`，带按 list 身份缓存的增量 fold；三处全部改调用它；
`dropOrphanedToolParts` 降级为"损坏上报"而不是"静默修复"。

---

### G6【P1】变更类工具没有回执，重试会二次生效

**现状**：`ToolCallLanes` 有 in-flight join 与跨 key 身份，`FileCheckpointTool` 有恢复材料，
`AuditEntryEntity` 有审计行——但没有任何"这次写入到底成没成"的三态回执。
进程在 `file_edit` 落盘后、UI 确认前被杀，重启后模型看到的是失败，重试 → 二次写入。

**参考**：codex-harness 的 receipts 表：`accepted / not_sent / unknown`，
**"unknown 不是可重试的失败"**；复用 operationId 且内容不同必须拒绝；
跨写边界不自动重放；回执只存身份与状态，不存正文/凭据。

**影响**：文件 / git / shell 类工具在崩溃边界上语义不确定；用户看到"失败"但文件已改。

**建议**：新增 `ToolReceiptEntity`（operationId、tool、argsDigest、state、resultDigest、at），
只对**变更类**工具（file_write/file_edit/multi_edit/shell/git/terminal）接线；
模型侧把 `unknown` 呈现为"结果未知，请先读回确认"，而不是"失败可重试"。

---

### G7【P1】长期记忆无结构化元数据，召回无淘汰/替代语义

**现状**：`MemoryRepository` 是 markdown 日记忆 + `GLOBAL.md`；`MemoryIndex` 给 40 行目录；
`MemoryRecallEngine` 关键词 + CJK 二元 + 反唠叨 streak。没有 type/scope/importance/ttl/disabled/supersedes。

**参考**：OpenHarness 的 frontmatter 全字段 + `compute_memory_signature` 去重 +
`select_relevant_memories(already_surfaced, freshness, selector=可选 side-query reranker)` +
`mark_memory_used` 记录使用。

**影响**：旧结论不会被新结论"取代"（只能靠追加）；过期记忆永远参与召回；
无法按 scope（private/project/team）分层；embeddings 表建了但没用。

**建议**：给记忆文件加 frontmatter（缺省 = 默认值，向后兼容），
实现 `supersedes` / `ttl_days` / `disabled` 的扫描期过滤；
把 reranker 做成可插拔 seam（先用现有关键词 shortlist，后续可换 embeddings）。

---

### G8【P1】压缩后的历史不可检索，只能靠 host 选的 digest

**现状**：`DroppedRoundDigest` 由 host 决定摘哪 24 行 / 3000 字；模型不能主动问
"三轮前关于 X 我们决定了什么"。

**参考**：deer-flow 的 `task_continuity`：`task_note`（≤8 条 × 750 字，带 source ID，
容量按模型 tool-call 顺序预留）、`history_search`（关键词 + 中文二元，8 条 × 600 字，
返回 `excerpt_start/end/match` 精确偏移）、`history_read`（4000 字分页 + `next_offset`）。
偏移契约写成 `source_text[start:end] == excerpt`，可被测试断言。

**影响**：长跑里"忘了当时怎么定的"只能靠模型重跑工具或猜；digest 是固定预算，
覆盖不到模型真正需要的那个点。

**建议**：在已落库的完整历史上开两个只读工具：`history_search(query, role?, range?)` 与
`history_read(sourceId, offset)`，范围限定在 marker 之前的已压缩区；
digest 保留为"免费提示"，检索作为"按需深挖"。这一条成本低、收益直接。

---

### G9【P2】运行时上下文是关键词门控，且没有 elapsed

**现状**：`TimeReminder` 只在用户文本命中"现在/今天/几点/时间/weather/天气"等词时
给最新用户消息加 `<system-note>当前时间: yyyy-MM-dd HH:mm</system-note>`。
`WorkspaceReminder` 同理。

**参考**：dsh `time-context` 每 eligible step 注入（默认 10 分钟最小间隔，可设 0 = 每步），
三行：ISO 时间戳 + 数字偏移 + IANA 区；本请求的浏览器时区策略；距上一可见消息的 elapsed。
快照语义：值不变不加，变了在步前追加一条快照消息，不重写历史。

**影响**："20 分钟后提醒我"这类请求，如果没有关键词就完全没有时钟；
长工具链里模型不知道已经过了多久（会误判"刚才"）。

**建议**：改成按间隔注入 + elapsed；关键词路径保留为加速器（命中时立即注入一次）。

---

### G10【P2】门限/预算一类参数全硬编码，无法按模型或会话调

**现状**：`PostAnchorTail.BUDGET_TOKENS=60_000`、`WINDOW_FRACTION=0.35`、
`NOTICE_RESERVE_TOKENS=1_200`、`ToolResultAging` 24k/160/160、`DroppedRoundDigest` 24/3000/512、
`WorkingMemoryLedger` 600/40/128、`LoopGuard` 四组阶梯——全是 `const`。

**参考**：dsh 的 compaction 配置化到 `thresholdRatio / headroomTokens / retainRatio|retainTokens /
maxTokens / compactionRetries / maxOverflowRetries / modelPolicies[{provider,model,...}]`，
并且**加载期校验失败即拒绝**（未知键、重复 override、两种 retain 并存、retain ≥ threshold）。

**影响**：小窗口模型（32K）与大窗口（200K）共用一套常数；改一个阈值要发版。

**建议**：`HarnessConfig` 数据类（默认值 = 现值）+ 每会话 override 持久化 + 加载期校验。
Android 侧不做插件树，只做"带校验的配置闸"。

---

### G11【P2】防循环会直接 stop 会话（可控性风险）

**现状**：`LoopGuard` 的 Repeat 阶梯 [3,6,12]，第 12 次直接**拒发**；
NoEffect/Reread/Failure 在第 12/12/8 次后**终止会话**。

**参考**：dsh 的 `repeat-tool-reminder` 明确"advisory only，从不阻塞或延迟一次合法重复"，
只发提醒；且仅检测**完全相同的参数**（deep key-sort 后 JSON 相等），近似的不管；
per-agent 隔离；resume 重置（内存态）。

**影响**：合法的高频轮询（等构建、等部署）会被判成循环并掐断；
用户没有任何"继续"的出口。

**建议**：把 stop 档位做成策略（默认保留，但可在会话内关闭/延后）；
每次阶梯触发写审计事件；提醒与拒发分离——先提醒 N 次，拒发需要显式策略开启。

---

### G12【P3】会话标题是否进入模型输入，需核验并加断言

**参考**：dsh `session-title` 明确"titles persist through replay, resume, and paging but
**never enter model input**"。

**现状**：`generateSessionTitleIfNeeded()` 在 loop 的 `turn == 0` 调用，未核验其产物是否
写入 `agentHistory`。

**建议**：加一条架构测试断言标题生成路径不 append 到 `agentHistory`；若不成立则修。

---

## 四、线路图

原则：**先立地基（可观测/可度量/配对），再事务化，再开放检索，最后做配置化**。
每一步都必须能独立上线、可回滚、有可失败的回归测试。

### Phase 0 · 地基（建议 5 个工作日）

| # | 交付物 | 验收 |
|---|---|---|
| 0.1 | `SessionJournal` 影子日志：append-only JSONL，记录 turn/step/request-assembled/tool/compaction 事件 | 跑 200 轮真实会话，`deriveMessages(journal)` 与 `agentHistory` 逐字节一致 |
| 0.2 | `TokenMeter` 抽取：从 journal 快照算出 total/surface/pressure/breakdown | 门限、环图、Token Sheet、压缩决策四处读到同一个数；旧的两套估算删除 |
| 0.3 | `ToolPairing` 统一：`legalCuts / isBalancedBefore / isBalancedAfter` + 增量缓存 | 三处调用点全改；`dropOrphanedToolParts` 改为上报；配对回归测试全绿 |

**为什么先做这三件**：G1 是 G2/G3 的前提；G3 消除"每加一个读数就分叉"的复发；
G5 消除本轮两个 P0 的同一根因。三件都是只读或纯函数级别，风险最低。

### Phase 1 · 压缩事务化（建议 8 个工作日）

| # | 交付物 | 验收 |
|---|---|---|
| 1.1 | `compaction/start → summary → end` 三事件落 journal | 崩溃注入测试：中途杀进程，重启后 `interrupted` 可识别，marker 未被推进 |
| 1.2 | `replacementGeneration` 递增作为唯一 retry 授权 | 连续失败不重复付费；成功后 generation 单调递增 |
| 1.3 | 裁剪按压力门控 + 回指原文 | 低于阈值时 aging 完全不运行（字节级不变）；占位符含 spill 路径 + 偏移，`file_read` 可取回 |

### Phase 2 · 压缩后可检索（建议 6 个工作日）

| # | 交付物 | 验收 |
|---|---|---|
| 2.1 | `history_search(query, role?, limit)` | 返回 ≤8 条 × ≤600 字，偏移满足 `source[start:end] == excerpt`（测试断言） |
| 2.2 | `history_read(sourceId, offset)` | 4000 字分页 + `next_offset`；越界与已删除有明确错误 |
| 2.3 | 范围限定在 marker 之前 | 检索不到当前活跃区（活跃区已在上下文里） |

### Phase 3 · 写入回执（建议 6 个工作日）

| # | 交付物 | 验收 |
|---|---|---|
| 3.1 | `ToolReceiptEntity` + 三态机（accepted/not_sent/unknown） | 进程在写后杀，重启读到 `unknown`，模型被要求"先读回确认" |
| 3.2 | 只对变更类工具接线 | 只读工具零开销（短路返回） |
| 3.3 | 复用 operationId 且内容不同 → 拒绝 | 测试覆盖 |

### Phase 4 · 记忆元数据与选择（建议 8 个工作日）

| # | 交付物 | 验收 |
|---|---|---|
| 4.1 | markdown frontmatter（type/scope/importance/ttl_days/disabled/supersedes/tags），缺省=默认 | 旧文件零迁移可读；新写入带全字段 |
| 4.2 | 扫描期过滤：`disabled` / 过期 `ttl` / 被 `supersedes` 取代 | 被取代记忆不再进 prompt 也不再进召回 |
| 4.3 | reranker seam + `mark_memory_used` | 默认走现有关键词；可切 embeddings 后端；使用计数可见 |

### Phase 5 · 运行时上下文（建议 3 个工作日）

| # | 交付物 | 验收 |
|---|---|---|
| 5.1 | 时钟按间隔注入（默认 10 分钟）+ elapsed + IANA 区 | 无关键词也拿到时钟；连跑 30 分钟工具链，elapsed 单调正确 |
| 5.2 | 快照语义：值不变不追加 | 同一请求内不重复注入；跨步变化只追加一条 |

### Phase 6 · 配置闸与策略化（建议 5 个工作日，可与前几阶段并行）

| # | 交付物 | 验收 |
|---|---|---|
| 6.1 | `HarnessConfig`（默认值 = 现值）+ 加载期校验 | 非法组合启动即失败并指明字段 |
| 6.2 | 每会话 override 持久化 | 32K / 128K / 200K 三种模型各用各的阈值 |
| 6.3 | 防循环 stop 档位策略化 + 审计事件 | 默认保持现状；可关闭 stop；每次触发可查 |

**合计约 41 个工作日**（单人），Phase 0-2 是主干（约 19 天），Phase 3-6 可交错。

---

##四之二、落地对照（20/20项 + G12）

全部交付物已在本分支实现。逐项给出承载体，便于核对而不必相信本文档。

| # |承载体 |备注 |
|---|---|---|
|0.1|`SessionJournal` + `lastRequestDigests` / `firstDigestDivergence`|验收改为"投影一致"：journal存 digest不存正文（避免成为第二份 transcript），可断言的是 digest序列逐项一致，分歧返回首个不一致下标；`-2`表示无记录（不是"一致"）|
|0.2|`TokenMeter` + `fromJournalSnapshot`|门限/环图/Token Sheet/压缩决策同源；journal读回供事后核对|
|0.3|`ToolPairingCore` + `repairToolPairing` / `PairingRepair` + `SessionJournal.PAIRING_REPAIRED`|三处实现收敛为一处；修复对模型保持静默（报错会让它重发同一调用），但计数入 journal|
|1.1|`CompactionTransaction`（start→summary→end）+ `interruptedCompactions`|中途死亡重启后可识别 interrupted|
|1.2|`advanceGeneration` / `retryAllowedNow` / `markFailed`|仅成功 settle推进 generation；失败进入30s冷却，用户取消不冷却|
|1.3|`ContextSaverRuntime`压力门控 + `ToolResultAging.placeholder(spillOffset =)`|低于阈值直接 return（字节级不变）；占位符带 offset + length|
|2.1|`HistorySearchTool.executeSearch`|≤8条 × ≤600字；`source[start:end] == excerpt`有测试断言|
|2.2|`HistorySearchTool.executeRead`|4000字分页 + `next_offset`（末页为 null）；越界有明确错误|
|2.3|`ChatRepository.compactBoundaryMs` → `searchMessages(endMs =)`|范围限定在 marker之前；无命中时说明检索范围|
|3.1|`ToolReceiptStore`（JSONL三态 + abandoned）|用 per-session sidecar而非 Room实体：与既有 ledger一致，免一次迁移|
|3.2|`ToolReceiptStore.isMutating`短路|只读工具零写入|
|3.3|`ToolReceiptStore.conflictOf(opId =)`|按 operation id判定：同 id不同指纹 =不同操作 →拒绝；同 id同指纹 =合法重放|
|4.1|`MemoryMetadataParser.frontmatterFor` + `MemoryRepository.writeMemory`|新文件首块写 frontmatter；既有文件不重写（避免并发写撕裂）|
|4.2|`MemoryMetadataParser.supersededIds`|三个读取方共用：日窗口、`MemoryIndex.rows`、`MemoryRecallEngine.scanMemoryFiles`|
|4.3|`MemoryReranker` + `MemoryRerankerRegistry` + `MemoryUsageLedger`|默认仍是关键词 BM25；reranker可运行时替换（置换校验：丢/造 hit即忽略）；交付计数与召回计数分离|
|5.1|`TimeContextGate` + `applyRequestClockNote`|关键词快路径 +间隔；请求装配期注入，带 elapsed|
|5.2|`TimeContextGate.alreadyStamped` / `markStamped`|值不变不追加（关键词路径也不重复），reset清理|
|6.1|`HarnessConfig` + `HarnessConfigTest`|默认值逐项钉住；非法组合构造即抛|
|6.2|`HarnessConfig`（窗口自适应）+ 复审后的处置|**未交付「用户可调 + 持久化」**：复审发现该 store 没有任何生产写入方（不可达）、且进程级 override 会跨会话串值、还在主线程读盘，故整体删除；每模型的差异由 `postAnchorBudgetFor(window)` 自然提供。用户可调阈值需要设置页入口后再做|
|6.3|`LoopGuard.stopEnabled` + `ActionGuard`判定"是否真的停止"|策略开关就位（默认硬边界保持不变）；**生产无 UI 入口**，属 seam，不是可用功能|
|G12|`TitleNotInModelInputTest`|断言标题路径不写 `agentHistory`，只经 `_sessionTitle`发布|

已知取舍（是明确选择，不是遗漏）：

-3.1用 sidecar JSONL而不是 Room实体：免一次迁移，且与既有 ledger同构；代价是无法用 SQL查询回执。
-4.3 reranker默认仍是 identity：接缝、置换校验、运行时注册都已就位，但没有接入 embeddings后端（那需要模型文件与开关，属另一件事）。
-0.1的"逐字节一致"降级为"digest序列一致"：journal不存正文是安全要求，两者无法同时满足。

---

## 五、明确不采纳的部分

写在这里是为了避免以后有人"顺手全抄"：

1. **Cordis 插件树 / profile+bundle+patch 分层**。dsh 的这套依赖 Node 生态与 HMR，
   Android 单进程 + 架构棘轮的环境里成本远大于收益。只取"配置闸 + 校验"这一层。
2. **JSONL vN 代际 + zstd 帧 + 迁移链**。本仓用 Room，已有 schema 版本与迁移测试；
   我们只需要 working-memory / journal 这类小文件的 torn-tail 容错，不需要完整代际体系。
3. **四进程分权（core/runtime/gateway/web）**。codex-harness 是为"单管理员自托管 Web 工作台"
   设计的服务边界；Android 应用内没有这个信任边界，照搬会引入大量 IPC 而无收益。
4. **deer-flow 的 LangGraph 多服务拓扑**（Nginx + Gateway + Frontend + Provisioner）。
   与移动端形态无关；只取 `task_continuity` 的三个工具契约。
5. **ZCode 的 90+ service 描述符 + client proxy 五层**。这是为多窗口/多端/remote 设计的；
   本仓 `ChatViewModel` 已经是单类族，先做内部拆分（G1-G5）比引入服务总线更划算。
6. **advisory-only 的防循环**。我们保留阶梯与拒发，因为移动端无人值守场景确实需要硬边界；
   只把"会终止会话"这一档改成策略。

---

## 六、风险与取舍

| 风险 | 说明 | 缓解 |
|---|---|---|
| journal 双写期间不一致 | 影子期两条路径可能短暂分歧 | 只写不读；比对脚本逐字节；不一致即告警不发版 |
| TokenMeter 切换口径 | 门限数字会变，可能出现"以前不压现在压" | 先双跑比对一周，差值超 5% 才切换；保留 feature flag |
| 回执表写放大 | 每次变更工具多一次 DB 写 | 只对变更类接线；同事务合批；失败降级为无回执（不阻塞） |
| frontmatter 迁移 | 旧记忆文件无字段 | 缺省=默认值；不重写旧文件，只在下次写入时补齐 |
| 配置闸漂移 | override 可能被写成"绕过编码规范" | 校验在加载期；非法键/组合直接拒绝；配置写入走审计 |
| stop 档位放开 | 长轮询可能无限跑 | 保留"总轮次上限"作为兜底；阶梯事件写审计 |

---

## 七、验证口径

- 每个 Phase 的交付物必须带**可失败的回归测试**（回退修复后测试变红）。
- Phase 0 的 journal 一致性用"200 轮实测 + 逐字节比对"，不是抽样。
- 门禁继续跑现有 12 个脚本 + architecture policy 棘轮；
  新增文件保持 <800 行；`ChatViewModel.kt` **当前 4,613 / 上限 4,613，已经到顶，只能缩不能涨**。
- 全量单测基线：本分支实测 6 893 通过；每阶段结束跑全量，不允许净减。
- 报告与代码不一致视为缺陷：本文所有"现状"数字都来自实测，实施中若变化需同步更新本文。

---

## 八、一句话结论

本仓的记忆与压缩**机制数量已经够多**（9 个压缩文件、13 个记忆模块、3 套配对逻辑、10 个门禁脚本），
真正缺的是四件地基：**一份 append-only 的事件日志、一个权威的令牌测量、一处配对的单一实现、
一套写入回执**。这四件做完，前面两轮靠人肉推演才找到的 P0 会变成"看日志就能定位"的常规问题；
再做"压缩后可检索"与"记忆元数据"，长跑体验会有质变。
