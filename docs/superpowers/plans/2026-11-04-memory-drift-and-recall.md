# 记忆与会话内记忆：任务偏移 / 注入 / 不丢失 / 召回

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 #228 已完成的记忆收尾之上，解决四件事：长会话里任务不被旧记忆带偏、会话记忆以可预期的顺序与预算交给模型、历史在窗口裁剪后仍可追溯、召回覆盖真正有用的来源。

**Architecture:** 三个新增纯函数/小模块承担判定（项目级指令发现、注入预算分配、多源召回合并），ViewModel 侧只做装配调用；所有触碰 `ChatViewModelAgentLoopExt.kt` 的改动保持净零增长（2081 行上限）。

**Baseline:** `77fd715c`（#228 记忆系统收尾）。

## 证据

外部项目的机制来自实际源码（2026-11-04 抓取）：

| 项目 | 文件 | 机制 |
|---|---|---|
| codex | `codex-rs/core/src/agents_md.rs` | **AGENTS.md 分层发现**：用 `project_root_markers` 从 cwd 向上定位项目根，再从根**向下**收集每一层 `AGENTS.md`；`AGENTS.override.md` 作为本地覆盖优先；用户级与项目级文档用分隔符拼接；**不越过项目根** |
| codex | `codex-rs/core/src/compact.rs` | `assemble_compaction_history` + `insert_initial_context_before_last_real_user_or_summary`：压缩后把初始上下文插到"最后一个真实用户消息或摘要"之前，保留 `retained_image_count` 与 `compaction_summary_tokens` |
| opencode | `packages/core/src/session/compaction.ts` | 参数 `buffer?: number\|string`、`keep: number`、`precompute: boolean`；`keepID` 作为锚；`precomputed` 按会话缓存；触发原因 `auto` / `overflow`；`recentUserMessages(keep)` |
| opencode | `packages/core/src/session/instruction-state.ts` | `observe` 对比 `current_values` 求 delta；`commit` 在 delta 为空且非 initial 时直接跳过；`prepare`/`apply` 用 `applyHashDelta` 落成 durable 状态；initial 只作基线、不算时间线历史 |

DSH 的源码在 `resources/app.asar`（182 MB 归档，未解包），本轮未从中取实证；报告 v4 对 dsh/dsh-phone 的描述保留为二手证据。dsh-go（`JopenChen/dsh-go`）为 2 KB 骨架，无记忆体系可参考——与报告一致。

## 现状盘点（#228 之后已具备，不要再重做）

- **指令快照**：`InstructionState.observe/applyDelta/render*` + `InstructionStateStore.observeAndCommit`（Room，迁移 27）+ `InstructionReplay`（回放）+ 冷启动 log-only 漂移检查。
- **记忆权威**：`MemoryAuthority` envelope（`instructionAuthority` / `trust` / `origin` / boundary），三条注入路径全部包裹，召回侧已补逐行 `stripForgedTag`。
- **召回**：`MemoryRecallEngine`（2-gram + 标题加权 + 时效 + recall-count，sidecar 持久化）+ `MemoryRecallEngineCache`（LRU 8）+ 批量落盘 + 查询截断 1200 + 停用词。
- **压缩**：`ContextPolicy` 四档 + `bufferTokens`（可选，默认不动）+ `WalkBack` token 预算（可选，默认不启用）+ summary / chunk pool / preAnchor 剪枝 / detached 60 条尾。
- **历史窗口**：`MAX_AGENT_HISTORY_MESSAGES = 4000`，被裁掉的行由 `HistoryDigest` 以裁剪预览形式保留并注入。
- **中断**：`GenerationRunStore` + `GenerationRunLifecycle`（逃逸 run 记 FAILED，ABANDONED 只留给真进程死亡）+ `InterruptedTailDetector` 四类断尾。

## 差距分析（按四个问题）

### Q1 长会话任务偏移

| 差距 | 证据 | 参考做法 |
|---|---|---|
| **无项目级指令** | 全仓 `AGENTS.md` 零命中；`folderId` 只出现在工作区/UI/备份，从不进入提示词 | codex 从项目根向下收集 AGENTS.md；opencode 有 global→workspace→目录链 |
| 指令优先级只有两级 | `MemoryFragmentBuilder` 只有 app 级与 session 级 GLOBAL.md，session 覆盖 app | codex：用户级与项目级拼接且 override 优先 |
| 快照未参与恢复 | `InstructionReplay` 目前只在 `loadSession` 打告警，不改变任何状态 | opencode `apply` 把 delta 落成 durable 状态 |
| 无漂移检测 | 只有 `ToolLoopDetector`（重复调用），无"目标是否被带偏"的信号 | 四项目均未做显式漂移检测；codex 靠"授权持久化后才生效" |

结论：**Q1 最值得做的是项目级指令分层**（有明确可抄的机制、与 folder 概念天然对齐），而不是硬造漂移检测器。

### Q2 会话记忆怎么交给模型

| 差距 | 证据 | 参考做法 |
|---|---|---|
| **注入无总预算** | `buildSystemPrompt` 按固定顺序拼接 skills→MCP→GLOBAL→daily→worldbook→learned→recall→runtime，任一段可无限膨胀；只有 daily 有 200 行上限 | opencode `buffer`/`keep` 是显式预算；codex 用 `insert_initial_context_*` 固定插入位 |
| 召回无同轮去重 | 引擎按目录缓存了，但同一次提示词构建内 `recall()` 会对同一 query 重复走全量扫描 | opencode `precomputed` 按会话缓存 |
| 记忆只靠显式写 | 日记靠 `memory_write`；会话结束不沉淀，日记可能长期为空 | codex/opencode 靠全量落库 + 压缩，不需要沉淀 |

### Q3 历史记忆怎么不丢

| 差距 | 证据 | 参考做法 |
|---|---|---|
| digest 是预览裁剪 | `HistoryDigest.clip` + `MAX_CHARS`，被裁行的正文不可回取 | codex 保留 `retained_image_count` / `compaction_summary_tokens` 并显式记录压缩元数据 |
| 压缩后初始上下文位置 | 已有 summary 前缀注入，但未像 codex 那样保证"初始上下文在最后一个真实用户消息之前" | `insert_initial_context_before_last_real_user_or_summary` |
| 快照/账本只写不读（部分） | 快照已有回放；账本只用于启动扫描提示 | — |

结论：**Q3 的实质缺口很小**，主要缺"可回取"（digest 只留预览）与压缩元数据。

### Q4 记忆召回

| 差距 | 证据 | 参考做法 |
|---|---|---|
| **只扫 memory 目录** | `MemoryRecallEngine.scanMemoryFiles()` 只列 `memory/` 下的 `.md` | DSH（lingxi）多源召回；OpenMinis 自己已有 `knowledge_notes.search_text` 与 `project_experience` |
| 无长度/条数配额 | 取 top 4，但单条命中行无长度上限 | lingxi：3 段 / 2400 字符上限 |
| 无 embedding | 明确不引入（无后端） | 保留现状，文档写明 |

## 执行顺序

依赖：Task 1 独立；Task 2 独立；Task 3 依赖 Task 2 的预算模型；Task 4 依赖 Task 1 的发现逻辑。

| # | Task | 依赖 | 交付 |
|---|---|---|---|
| 1 | 项目级指令分层（folder-scoped rules） | — | 目录/项目规则进入提示词，带优先级与 override |
| 2 | 记忆注入预算与降级顺序 | — | 记忆段总 token 预算，超预算按固定顺序降级 |
| 3 | 召回同轮 memo + 命中长度配额 | 2 | 同轮只算一次；单条/总量有上限 |
| 4 | 多源召回（memory + knowledge notes） | 1 | 统一召回入口，结果带来源与 authority |
| 5 | 压缩元数据与初始上下文位置 | — | 压缩记录保留计数，初始上下文位置与 codex 对齐 |
| 6 | 会话沉淀（可选，需产品判断） | 2 | 压缩/结束时把摘要写入记忆，带 provenance |

## 明确不做

- **embedding / 向量召回**：无后端，且 keyword+时效已够用；引入会带来模型分发与体积问题。
- **显式"任务漂移检测器"**：四个参考项目都没有可靠做法；误报代价高于收益。用指令分层 + 权威 envelope + 快照回读压制，而不是猜。
- **DSH asar 深挖**：182 MB 归档，边际收益低于成本；如需，单独开任务解包。
- **通用 revert 到任意消息点**：与上次结论一致，独立 spec。

## Review Focus

- **项目级指令的越权风险**：项目规则来自仓库文件，等同于"仓库可写的 prompt"。必须走 `MemoryAuthority` envelope，并明确 `instructionAuthority` 语义，不能让它覆盖当前用户请求。
- **预算降级顺序要可解释**：降级顺序一旦写错，最先被砍的可能是最该保留的（如召回）。顺序需在代码里显式声明并有测试固定。
- **不得降低默认行为**：所有新参数默认关闭或保持原值，与 #228 的做法一致。
- **行数门禁**：`ChatViewModelAgentLoopExt.kt` 上限 2081，只能靠新文件扩展。

## 验证口径

- 新增纯函数各配单测；`cd src/android && ./gradlew :app:testReleaseUnitTest --tests …` 全绿。
- `python3 scripts/ci/check_architecture_policy.py` 与 `check_cjk_literals.py` 通过。
- 涉及提示词装配的改动，需在 `[XSessionDiag] prompt/memory` 日志中新增字段，便于线上验证。

---

## 实施记录

六项已全部落地。与计划的四处偏差，都是实施时被真实约束倒逼的，记在这里：

1. **Task 4 的召回用 TTL 镜像，不是同步查 Room。**
   召回跑在提示词装配里（同步、非主线程），而笔记在 Room 里（suspend）。在装配里 `runBlocking` 会开一个坏先例，所以改成 `KnowledgeNoteIndex` 内存镜像：`loadSession` 时若镜像超过 5 分钟 TTL 就异步刷新。代价是"新建笔记 + 立刻提问"可能晚一次刷新才可见——召回是收敛的，不是实时的，这一点写进了类注释。

2. **Task 5 没有加数据库列。**
   codex 把 `retained_image_count` / `compaction_summary_tokens` 落到压缩元数据里；OpenMinis 这边加列要走迁移 27→28 + 版本守卫同步。为一行诊断信息动 schema 不划算，所以改为在 `[CompactMeta]` 日志里记录 `summaryChars / summaryTokens / compacted / keepTurns / detachedTail / chunks`。"初始上下文位置"经核对已与 codex 等价（初始上下文在 system prompt，摘要前缀在 anchor 之后的第一条 user 消息），无需改动。

3. **Task 6 默认开启，但受 memoryOn 门控。**
   没有做 UI 开关（要补 16 个 locale），改为：会话记忆开着才沉淀，条目带 `[compaction-summary]` 标签并按内容去重。用户想关掉，关会话记忆即可。

4. **项目级指令受 memoryOn 门控。**
   与 GLOBAL.md 一致。代价是关掉记忆的用户也拿不到项目规则——这是刻意的取舍（记忆开关的语义是"是否注入持久上下文"），已在 `ProjectInstructionStore` 注释中写明。

**新增文件：**
`ProjectInstructionStore.kt`（项目级规则发现 + envelope）、`MemoryPromptBudget.kt`（注入预算纯函数）、`KnowledgeNoteIndex.kt`（笔记镜像 + 检索）、`MemorySediment.kt`（压缩沉淀）、`ChatViewModelKnowledgeIndexExt.kt`（TTL 刷新入口）。

**新增测试：** `MemoryPromptBudgetTest` 7、`ProjectInstructionStoreTest` 7、`KnowledgeNoteIndexTest` 6、`MemorySedimentTest` 7；`InstructionSnapshotValuesTest` 扩到 8。

### 验证结果

| 项 | 命令 | 结果 |
|---|---|---|
| 编译 | `:app:compileReleaseUnitTestKotlin` | BUILD SUCCESSFUL |
| 单测 | `:app:testReleaseUnitTest --tests 'com.openminis.app.data.**'` | **260 用例 / 0 失败 / 0 跳过**（33 个 suite） |
| 架构门禁 | `check_architecture_policy.py` | OK（`ChatViewModelAgentLoopExt.kt` 仍 2081） |
| CJK 门禁 | `check_cjk_literals.py` | OK |

关键行为已由测试固定：预算在紧张时先丢日记、保留 session/项目规则；项目 `AGENTS.override.md` 优先且空文件不遮蔽；召回命中按标题加权、片段以命中词居中；沉淀同内容不重复写。

---

## 第二轮：对照 DSH 的两处补齐

拿到 `deepseek-ai/deepseek-harness` 与 `lxsfful/dsh-go` 的正确仓库后复核。DSH 的 `packages/context/agent-instructions/` 暴露了两条我们缺的性质（`agent-instructions-render.ts` 原文）：

> `More specific instructions take precedence over broader ones. They do not override system, developer, or direct user instructions.`
> `identical trimmed content renders once`
> `an unobservable candidate preserves the last-good state of its group`

第一条上一轮已对齐（注入顺序 + header 声明），后两条逐条补上：

**1. 跨层内容去重**（`T-memory-instruction-dedupe`）
同一段规则同时写进 `GLOBAL.md` 与 `AGENTS.md` 时，过去会把同一份指令连发两遍——既浪费 token，又让模型收到两个「权威来源」。新增 `InstructionDedupe.firstSeen`：在**正文**层面比较（不是渲染后的片段，因为每层都带自己的 header 与 envelope），空白归一化后相同即只保留一次；调用方按「作用域从窄到宽」传入，因此保留的是更具体的那一层。配套给 `MemoryFragmentBuilder` 加了 `loadGlobalMemoryBody`（独立的指纹 memo），让装配层不渲染也能拿到正文。

**2. 读不到时保留 last-good**（`T-memory-project-rules-lastgood`）
原来 `ProjectInstructionStore` 读取失败时返回 null 并清空缓存，一次瞬时 IO 错误就让项目规则静默缺席一轮——模型行为会无声改变。现在区分两种「没有」：**文件合法消失**（未归档、被删除）清空 memo；**读失败**返回同一路径的上一次好值，跨项目的旧值绝不顶替（`cachePath` 校验）。`resolve` 增加可注入 reader，让「读失败」这条路径能被测试覆盖。

**dsh-go 的结论**：`lxsfful/dsh-go` 是 Flutter 客户端 + 构建期捆绑的 DSH 运行时（`runtime/lib/rtbuild.py`、`dsh-0.2.1-rc.4` lock）+ 原生桥接插件；记忆是第三方插件 `@furongjun1999/dsh-memory` 渲染成的 bot 作用域条目卡片（`BotMemoryCard`，带 owner 权限与就地编辑）。**它没有自己的记忆算法可抄**，可迁移的只是「条目化 + 条目级权限」这一产品形态，而片段级 authority envelope 已覆盖同等语义。

**验证：** `com.openminis.app.data.**` 243 用例 0 失败；新增 `InstructionDedupeTest` 6 个、`ProjectInstructionStoreTest` 扩到 10 个（含 last-good 3 个）。
