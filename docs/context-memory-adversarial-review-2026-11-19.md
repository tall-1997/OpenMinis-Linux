# 长跑记忆修复的对抗复审与二次修复（2026-11-19）

> 对象：`0e62a881`（PR #398，长跑失忆根因修复）。
> 方式：三名独立只读审查员 + 维护者自审，按「逻辑/边界、异常处理、性能、未接线、破坏原功能、用户体验、报告真实性、硬编码上限」八个维度逐项核对；
> 所有修复均带回归测试（VM 内会话切换路径除外，见 §七），并在本地全量单测 + 门禁复验。
> 三项后续补齐见 [context-memory-followups-2026-11-19.md](context-memory-followups-2026-11-19.md)。
> 结论：**两轮共发现 27 个真实问题（其中 8 个 P0/P1 级），已全部修复；另有 5 项确认为设计取舍并记录在案**。

## 一、P0/P1：会直接造成上下文丢失或回合误停

| # | 问题 | 证据 | 修复 | 回归测试 |
|---|---|---|---|---|
| 1 | **detached 锚点 + 去蒙蔽门限 → 压缩范围远超 6 次调用预算 → 全部 stage 失败 → 旧 truncation 兜底把整个 resident 窗口标成已压缩，活跃尾部瞬间消失** | `ChatViewModelAgentHistoryExt` 的 unbudgeted 度量、`runTwoShotCompact` 的 truncation 返回值、`compactAllImpl` 的 marker 写入 | ① `maxCompactRangeTokens(window)` = 4 × segment(8k..32k)（4 叶 = 4 次调用，≤6 预算），`cappedCompactEnd()` 只压缩可完成的**前缀**，下次继续；② `CompactOutcome.succeeded=false` 时**不写 marker**、不推进锚点，明确告知“保留原上下文” | `CompactResilienceTest`（cap 随窗口缩放、`coveredCountFor` 行为）、`CompactGateArchitectureTest`（失败不写 marker、cap 接线） |
| 2 | **<32K 窗口（exhaustedOnly，无压缩档）用 unbudgeted 度量 → 一轮工具后误判 EXHAUSTED → in-loop STOP** | `contextTokensForPolicy()` 改 unbudgeted；`ContextPolicy` <32K `compactThreshold=0` | 只有 `compactThreshold>0` 的档位才测 unbudgeted；exhaustedOnly 档仍测实际（预算后）请求 | 由 `ContextPolicy` 既有单测 + 全量回归覆盖 |
| 3 | **任务锚点被“no/yes/hi/thanks/收到/谢谢/行吧”劫持**：`isSubstantive` 只要求 2 个字母，锚点变成致谢，长跑再也看不到任务 | `PromptAnchors.isSubstantive` / `fragment` | 薄跟进词表补齐（中英致谢/确认），ASCII 至少 4 个字母数字、CJK 至少 2 字；`fragment` 自身也拒绝薄跟进 | `TaskAnchorTest`（`no/yes/hi/thanks/收到/谢谢/明白了/行吧` 非实质；致谢不覆盖 DB 任务） |
| 4 | **摘要注入到 tool-result 载体，或只有 notice 时又折回 pre-anchor** | `firstInjectableUserIndex` 旧实现把 USER+ToolResult 当 real user、无 real user 时回退 0=notice | 只认**纯文本 USER**（无 ToolResult/ToolUse、非 harness note）；无目标时摘要作为独立 USER 插在活跃区头部，notice 随后折叠到它 | `PostAnchorBudgetTest`（载体返回 -1、note-only 返回 -1） |

## 二、隐私 / 安全

| # | 问题 | 修复 |
|---|---|---|
| 5 | 工作记忆 handover 现在把 tool 参数（命令/路径/URL，可能含 `Authorization`、`token=`、`password=`）写进**持久化**隐藏 USER 行；失败闸门的参数摘要也落盘；旧实现只有 SHA | `SecretMasking` 扩展 token 形状 + `key=value`/`key: value` 赋值形状（含 JSON 引号 key、Basic/Bearer/Token scheme、CLI 空格形态，保留 key 名）；`WorkingMemoryArgs`/`ToolFailureMemory` 在截断前掩码；`memory_write` 正文只记 `memory entry (N chars)`；`NON_IDENTITY_KEYS` 跳过 content/text/prompt 等正文键；所有隐藏 note 经 `frameHiddenNote` 落盘前统一掩码 + 框架标签中和；压缩摘要写 marker / MemorySediment 前也掩码 |
| 6 | 工具输出可含 `</system-reminder>`/换行，拼进隐藏 note 会提前闭合框架，后续行泄漏到预览/导出 | `WorkingMemoryArgs.oneLine` 统一消换行 + 框架标签替换；`resultHead` 在 append 时同样处理 |

新增 `SecretMaskingTest`、ledger/failure 掩码与框架安全测试。

## 三、性能

| # | 问题 | 修复 |
|---|---|---|
| 7 | `DroppedRoundDigest` 每次请求扫描**全部** dropped 消息（最多数千条），只为保留 24 行 | 改为从最新往回扫，`MAX_SCAN_MESSAGES=512` + 行/字符上限即停；未扫描部分报数量；whitespace 正则提升为常量（512 是原始消息上限，连续载体仍可能挡住更老文本，此时返回 count-only 行，不静默 null） |
| 8 | `ToolResultAging.placeholder` 头/尾各做一次全量 whitespace collapse | 一次 collapse，头尾共用；`tailOf`/`headlineOf` 复用纯函数 |
| 9 | `WalkBack` 每次预算判断预构建整段 prefix（O(anchor)），且 `ToolUse.input.toString()` 未走 memo | 改流式累加（超预算即返回），`TokenScanMemo.toolUseInputJson` 复用；前缀数组删除 |
| 10 | `contextTokensForPolicy` 同时算 budgeted+unbudgeted 两遍全历史（后者严格支配前者） | 只算一遍；`contextTokensForDisplay()` 单独给 UI/提示用，避免把未预算数字展示给用户 |
| 11 | `WorkingMemoryStore` 每会话一个 ledger，进程内无上限 | 访问序 LRU，最多 128 个；淘汰安全（新 ledger 继续记新轮次） |
| 12 | `WorkingMemoryLedger` 溢出折叠把未投递记录压成一句计数，身份全丢 | 折叠行保留最近 3 条的 `tool(args)` 样本；措辞不再谎称“see summary” |

## 四、正确性 / 边界

| # | 问题 | 修复 |
|---|---|---|
| 13 | `ToolResultAging.age(keepRecent=0)` 越界 `live[scanned]` | `keep==0` 走“折叠全部”分支 + 测试 |
| 14 | 新参数截断（ledger/failure）按 UTF-16 切，可能留下 lone surrogate 并持久化 | 新增 `TextClip.safeTake/safeEllipsize`，所有新截断点改用；测试 emoji 边界 |
| 15 | 尾预算只对 messages 计费，notice+digest 额外叠加（32K 窗口约 +7%） | 预留 `NOTICE_RESERVE_TOKENS=1200`，digest 按预算截断；测试 kept 总量 ≤ budget |
| 16 | detached 尾固定 60 条，60×16K 结果≈240K token 可塞进 128K 窗口 | `detachedTailSize()` 按 token 预算从新到旧选尾（上限 200 条，最新必留）；测试 60×16K ≤ 44.8K |
| 17 | 丢弃轮次后状态快照 sha 只看 `agentHistory`：通知已被请求投影丢掉，sha 仍说“已投递”，模型继续用旧计划 | 请求装配（`forRequest=true`）把已有的 state note 用 `evictedStateNote()` **只对本请求重注入**，不落库、不清 sha；测量路径不新增本修复的副作用，sha 去重保持有效 | `PostAnchorBudgetTest`（evicted + visible/summarized 两用例覆盖三态） |
| 18 | 队列 drain / 中途插话重建提示时若已切会话，会读到另一个会话的人格/规则/锚点；`runCatching` 吞 `CancellationException` | 重建辅助函数接收 `expectedSessionId`，不匹配直接放弃；显式 rethrow cancellation；drain 在会话已切换时不再启动循环 |
| 19 | 记忆关闭时规则仍被 `memoryAssembly` 门控，DISABLED 段却声称“rules still bind every turn”，自相矛盾 | 装配始终包含三层 standing rules，只有日记/TOC 跟随 `memoryOn`；`standingRuleContract` 的 admission 与真实注入一致 |
| 20 | 复审报告/架构测试存在“删掉修复仍通过”的弱断言 | `CompactGateArchitectureTest` 断言失败不写 marker、cap 接线、detached 预算、规则不受 memory 门控；新增 cap/notice/detached/secret/surrogate 行为测试 |

## 四之二、第二轮复审追加（7 项）

> 第二轮针对第一轮修复再做对抗复审，发现以下真实问题（均已修）。

| # | 问题 | 修复 |
|---|---|---|
| 21 | **detached 尾的 `safeTailStart` 会走回唯一真实 user（index 0）**：单用户 + 300 工具轮时，token 预算完全失效，整段历史重新上车 | `legalTailStart()` 用 `ResidentWindow` 的配对合法边界（可停在 assistant tool_use），摘要作为独立 USER 放在配对完整的尾部之前；`detachedTailSize` 再留 10% 余量给边界回退。新增“单用户 300 轮”回归测试 |
| 22 | **<32K exhaustedOnly 档仍测未 ProgressiveTrim 的切片**：21K 历史 trim 后 18K 仍被判 EXHAUSTED，回合被 STOP | `policyMeasurementMode()`：可压缩档测 unbudgeted，exhaustedOnly 档测真实请求（trim + 尾预算）；纯函数单测覆盖 30K/32K/128K/null |
| 23 | **状态快照投影重置把 sha 去重打穿**：note 被尾预算挤出后置 `stateSnapshotSha=null`，下一轮必然重写一条隐藏 USER 行，可能每 iteration 一条 | 不再由投影清 sha。请求装配（`forRequest=true`）把**已有的** state note 只对本请求重注入（`evictedStateNote`），不落库、不重复；resident 驱逐仍走 `HiddenNote` 的既有重新水化路径。新增纯函数 + 接线测试 |
| 24 | **drain 会话守卫在落库/绑定/append 之后**：A→B 切换时 A 的 prompt 被写进 B 的 UI/历史，然后 return 留下无人应答的消息 | 进入 drain 先记 `originSessionId` 并在 claim 前检查；持久化到 A 的 DB 后立刻检查会话，不匹配则不绑定/不 append/不启动循环（消息留在 A 的 DB，重开可见） |
| 25 | **SecretMasking 漏掉 JSON 带引号 key / `Authorization: Basic` / URL `&` 之后的参数**，且 `ToolFailureMemory.lastError`、`commitHiddenUserNote`、压缩摘要未掩码 | 支持可选引号 key、`client_secret/secret_key/private_key`、Basic/Bearer/Token/Digest/Negotiate 方案、CLI 空格形态（`--password x`、`-u u:p`、`sshpass -p`）；值类排除 `&`/`#` 保留 URL 尾部；`lastError` 先掩码再截断；`frameHiddenNote()` 在落库前掩码所有隐藏 note 并中和框架标签；压缩摘要写 marker / MemorySediment 前掩码 |
| 26 | **失败路径仍弹“Truncating older context”**，与新契约“保留原上下文”矛盾，且两条 notice 互相打脸 | 统一为 “Compaction failed (…). The context is kept unchanged.”，删除 “Truncating/before dropping older context”，调用方不再重复弹第二条；架构测试禁止旧文案回归 |
| 27 | **通知/提示显示的是 un-budgeted 门限值**：Token Usage/压缩提示可能显示 180K 而真实请求 55K | 新增 `contextTokensForDisplay()`（真实请求投影）供 ring/sheet/提示使用；门限仍用 un-budgeted，职责分离 |

## 五、确认安全（重点复核项）

- `foldHarnessNotes` 对 carrier 幂等；`prependUserText/appendUserText` 同步 parts；载体文本在 tool_result 之后（Anthropic 顺序）。
- `PostAnchorTail.splitRounds` 绝不拆 tool_use/result；`dropOrphanedToolParts` 只做兜底。
- 缩量压缩的 `coveredEndIdx` 落在 `[effectiveStartIdx, anchorIdx]`；`verifiedAnchorIdx` 走回不越过起点，找不到持久锚点则拒绝写 marker。
- `WorkingMemoryLedger` entries/delivered 平行数组在 fold 后仍对齐；每 entry 只投一次。
- 召回只把 `RenderedFragment.included` 标记已投递；anti-nag streak 未改。
- `publishStablePrefix=false` 的测量构建不再写 `residentDailyFiles`/快照/前缀。
- `PostAnchorTail` Int.MAX 哨兵、预算求和、`budgetTokens<=0` 早退均无溢出/越界。

## 六、明确保留的取舍（不是遗漏）

1. **WorkingMemoryStore 落盘已补**（见 [followups](context-memory-followups-2026-11-19.md)）：JSONL 按会话追加写、commit 后 ack、失败 undo；剩余取舍是 commit 成功但 ack 前崩溃会重复一次。
2. **状态快照是追加而非原地替换**：每次状态**变化**会新增一条隐藏 USER 行（被逐出只做 request-only 重注入，不新增）；原地替换需要删除/重写 transcript 行，风险高于收益，保留追加 + sha 去重。
3. **single-user-turn 的 postAnchor 已补轮内切分**：`splitRounds` 在 assistant 步边界再分组（有未配对 tool_use 时不切），单用户 300 步也能被尾预算裁剪；见 [followups](context-memory-followups-2026-11-19.md)。
4. **summary prompt 仍按 500 字/消息截断**：本轮根因不在摘要质量；若出现“摘要漏关键路径”再单独评估。
5. **历史已落盘的隐藏 note 不做追溯清洗**：新的写入已掩码；旧数据只能靠备份/导出侧另行处理。

## 七、验证口径

- 本地全量 `:app:testReleaseUnitTest` + 新增/更新测试（见各表）。
- 门禁：architecture policy（基线收紧 ChatViewModel 4613 / AgentLoop 1897）、CJK、announced tools、string refs、tool execution contract、tool title/budget、schema drift。
- 除 §七末尾明示的 VM 内会话切换路径外，表中的“已修复”均对应可失败的回归测试；纯接线项只做 source guard，不声称行为已验证。
- VM 内会话切换（drain/中插）没有 JVM 级行为测试：`ChatViewModelDrainQueueExt` 依赖真实 ViewModel/DB/协程，靠源码守卫 + 手工路径推演；`CompactGateArchitectureTest` 对这类路径只做接线断言，不冒充行为测试。
