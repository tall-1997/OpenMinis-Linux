# 对抗审查与修复报告（2026-11-19 第二轮）

审查对象：本地未推送的4 个提交（`origin/main..a0114698`）及其后未提交的修复集。
审查维度：逻辑/边界、异常处理、性能、接线有效性、兼容性、用户体验、报告真实性、硬编码阈值、故障隔离与幂等、资源释放与超时。

流程（严格按序）：第一轮对抗审查 → 修复全部 → 第二轮审查（校验修复引入的次生问题）→ 修复 → 最终全局核验 → 本地提交。

---

## 一、第一轮：发现与修复

### A. 逻辑错误与边界（3 名审查者 + 自查）

| # | 缺陷 | 位置 | 修复 |
|---|---|---|---|
| A1 | **HIGH** `history_search` 关键词被小写化后用 `contains(ignoreCase)` 匹配，但定位函数 `excerptAt` 按码点精确比较 → `README`/`TODO`/CamelCase 标识符/文件名全部匹配成功却定位失败，被静默丢弃，工具回复"没有匹配"（模型据此认为"这事没发生过"） | `tools/HistorySearchTool.kt` | 定位改为大小写无关码点比较（`sameCodePointIgnoringCase`）；偏移仍指向原文，契约不变；新增测试 |
| A2 | **HIGH** 会话日志记录的是「图片预算裁剪之前」的历史，而真正发出的是裁剪后的列表 → journal 的 digest 与现实请求分歧，`firstDigestDivergence` 在首张被裁剪图片处误报 | `ChatViewModelAgentLoopExt.kt` / `ChatViewModelJournalExt.kt` | 未改（见"未修复项"）；已确认当前无生产读取方，风险为"未来对拍失真" |
| A3 | **MED** 压缩重试冷却用墙钟：时钟回拨后 `now - failed < 0` 永不满足，压缩被锁死数小时；in-loop 场景会判定"上下文无法恢复"中止回合；提示文案还是"另一个压缩正在运行"（实际什么都没跑） | `CompactionTransaction.retryAllowedNow` | 回拨视为到期（`nowMs < failed.second || gap >= cooldown`）；**并修掉提示文案**（见 C1） |
| A4 | **LOW** `TimeContextGate` 时钟回拨后长时间不再注入时钟 | `agent/TimeContextGate.kt` | 同上：负数间隔视为到期 |
| A5 | **LOW** journal `trim()` 与并发 `append` 无互斥，`setLength(0)+write` 之间落地的行被覆盖 | `ui/chat/SessionJournal.kt` | 每文件锁把 trim+append 包成一个临界区 |
| A6 | **LOW** `interruptedCompactions` 扫描窗口按 `TRIM_KEEP_BYTES`(2MB) 推导，但裁剪阈值是 `MAX_BYTES`(4MB) → 2–4MB 区间的 `compaction/start` 仍在文件里却扫不到，崩溃留下的付费尝试可能漏报 | `ui/chat/SessionJournal.kt` | 窗口改为 `MAX_BYTES / BYTES_PER_LINE` |
| A7 | **LOW** `validated()` 只校验"天花板"，生产用的是"按窗口缩放后的预算"：2K 窗口缩放到 716 < 1200 预留 → 尾部塌成 1 token | `agent/HarnessConfig.postAnchorBudgetFor` | 缩放下限钳到 `noticeReserveTokens + 1`；新增测试钉住 |
| A8 | **LOW** `reportInterruptedCompactions` 在 detached 协程里读可变 `realSessionId`，快速切会话会把 B 的中断写到 A 头上 | `ChatViewModelLoadExt.kt` / `CompactionTransaction.kt` | 启动前捕获 sessionId 并作为参数传入 |
| A9 | **LOW** 工具回执的 unknown 警告没有清除路径 → 一次崩溃后每个工具结果都挂同一条告警到会话结束 | `ui/chat/ToolReceiptStore.kt` | 回执增加 `reported` 集合：每条未决回执只播报一次；同签名重试会重新武装 |
| A10 | **LOW** `HistorySearchTool` 的 `catch (Exception)` 会把协程取消当成工具失败 | `tools/HistorySearchTool.kt` | 先 `catch (CancellationException) { throw }` |
| A11 | **LOW** `keywords()` 对大段粘贴 query 无上限（CJK 二元全量物化后才 take(8)） | `tools/HistorySearchTool.kt` | 查询截断 1200 字符 + 收满 8 个词提前返回 |

### B. 性能、资源、故障隔离

| # | 缺陷 | 位置 | 修复 |
|---|---|---|---|
| B1 | **HIGH** `ToolReceiptStore.unsettled()`/`conflictOf()` 每次工具调用都 `readLines()` 全量解析回执文件：1000 次调用≈每次读 350KB、解析 2000 个 JSON，累计数百 MB 重复读 + 200 万次解析，全在模型等待的关键路径 | `ui/chat/ToolReceiptStore.kt` | 进程内投影缓存（`State{open,ops,reported}` + 每文件锁），首用读一次文件，之后由写操作维护；`trim` 后失效重建 |
| B2 | **MED** 日记忆目录被反复整读：`MemoryIndex` 构建 TOC 时读两遍（本轮新增的 supersedes 扫描把上一轮"单次扫描"优化抵消）、`MemoryFragmentBuilder` 窗口读两遍、`MemoryRecallEngine` 每次召回读两遍 | `MemoryIndex.kt`、`MemoryFragmentBuilder.kt`、`MemoryRecallEngine.kt` | 三处改单次读取：一次读到的文本同时服务元数据判定与扫描；recall 的来源解析抽到新文件 `MemoryRecallSources.kt`（同时也是引擎行数上限的需要） |
| B3 | **MED** `disabled` / TTL 过期的日记忆仍可被召回：prompt 窗口与索引过滤了，召回没过滤 → 被刻意隐藏的记忆从 recall 重新进 prompt | `MemoryRecallEngine.kt` | 召回来源统一走 `recallSources()`，与窗口同一套 eligibility |
| B4 | **MED** 记账写入失败会打断回合：`saveRecallCounts` 的 `writeTextAtomic` 抛 IOException，不是 `LLMError`，被当成 provider 失败走重试阶梯，最终用户看到错误并丢掉整条回复 | `MemoryRecallEngine.kt` | `runCatching` 包住（与其它 ledger 一致） |
| B5 | **MED** `MemoryUsageLedger` 全局 map 无上限、每会话键混写、每次投递全量重写 | `MemoryUsageLedger.kt` | 按天前缀裁剪 30 天 + 硬上限 5000 键，写入前 prune |
| B6 | **LOW** 大 query 的 CJK 二元物化 | 同 A11 | 同 A11 |

### C. 接线有效性（20 项逐项核验）

11 项 WIRED、9 项 HALF-WIRED、0 DEAD。半截项与处置：

| # | 半截项 | 处置 |
|---|---|---|
| C1 | 压缩冷却提示文案错误（"already running"，实际未在跑） | **修复**：改为如实文案（见"遗留"说明：需要新字符串资源；本轮先用现有 `vm_compaction_failed` + 秒数） |
| C2 | `HarnessConfigStore` 只有 `install` 没有 `save`（生产无写入方）→ 每会话 override 不可达；且 override 是进程级 `@Volatile`，一旦有写入方会跨会话串值；`install` 还在主线程读文件 | **修复**：删除 `HarnessConfigStore`、`HarnessConfig.install/resetForTest` 与加载点调用。`current()` 恒为默认值；窗口自适应仍由 `postAnchorBudgetFor(window)` 提供。文档同步降级该验收项 |
| C3 | reranker 注册表无生产安装方，且引擎按会话缓存 → 即使在设置里 install 也只影响新引擎 | **修复**：引擎构造改为 `rerankerProvider: () -> MemoryReranker`，每次召回读注册表；注册表本身保留为 seam（默认 identity） |
| C4 | `MemoryUsageLedger` 只写不读（"使用计数可见"未达成） | **修复**：投递时打一条 `MemoryUsage` 日志（含每行累计次数），让"这条记忆被展示过几次"可从诊断日志回答；UI 展示仍需一个设置页入口（未做，见遗留） |
| C5 | `LoopGuard.stopEnabled` 生产无写入方 | **保留为 seam**（默认 true = 原有硬边界）。文档标注"策略开关就位、无 UI 入口" |
| C6 | 修复路径 `repairToolPairing` 仍是第三份配对 fold | **保留并说明**：它必须"修补"而非"检测"，但身份归一化已统一走 `ToolPairing.key`（与 `ToolPairingCore` 同源），不是第二次实现不同的规则 |
| C7 | journal `turn/end` 从不写、`tool/call` 在结果之后才写 | **修复**：新 turn 开始时补写上一 turn 的 `turn/end`；`tool/call` 前移到 dispatch 之前（`recordToolCallJournal` / `recordToolResultJournal`） |
| C8 | placeholder 的 `length` 是 preview 长度而非 spill 文件大小 → 模型照着回读会截断真实输出 | **修复**：只写 `(offset N)`，不再声称 length |
| C9 | frontmatter 泄漏进 prompt 预览与 TOC（新日志首行是 `---`） | **修复**：新增 `MemoryMetadataParser.strip()`，TOC 预览与注入的日记忆都用剥离后的正文 |

### D. 兼容性与体验

| # | 缺陷 | 修复 |
|---|---|---|
| D1 | `TokenMeter.forDisplay` 曾被改成 `maxOf(provider, estimate)`，环图/Token Sheet 数字相对 origin/main 变化约 10%，而注释声称"历来如此" | **恢复 origin/main 语义**：display 有 provider 读数就用它；门限走 `pressure()`（两者取大）。注释改成如实描述并写明为何门限与展示不同 |
| D2 | `SecretMasking` 的赋值规则把任意 4+ 非空白字符当凭据 → `secret: family recipe` 被改写成 `secret=[redacted] recipe`，而该函数输出会被持久化（压缩摘要、记忆沉积、hidden note） | **收紧为"凭据形状"**：带引号的 4+ 字符值，或 8+ 字符且含数字/符号的凭据字母表 token |
| D3 | `ESTIMATED_IMAGE_CHARS` 在 `TokenMeter` 与 `ContextTokenEstimator` 各有一份 | 改为从 `ContextTokenEstimator` 派生（单一来源） |

---

## 二、第二轮：修复引入的次生问题

（本轮在修复集之上做，结论见下）

| # | 次生问题 | 处置 |
|---|---|---|
| R1 | journal `turn/end` 与 `tool/call` 顺序改动引入重复写入风险（原 `recordToolJournal` 仍在 producer 尾部） | 删除旧合并调用，只保留 seam 内的 call/result 两次写入 |
| R2 | `MemoryRecallEngine` 因新增逻辑涨到 806 行，超过 800 行新文件上限 | 抽出 `MemoryRecallSources.kt`（规则而非机制），引擎回到 789 行 |
| R3 | 单次读取改造后 `MemoryIndex`/`MemoryFragmentBuilder` 的 `Triple` 解构与日偏移 | 编译 + 全量测试通过；`memory` 相关测试全绿 |
| R4 | 删掉 `HarnessConfigStore` 后 5 个消费方仍调 `current()` | 保留 `current()` 恒返回默认值，编译与预算契约测试通过 |
| R5 | 测试与新语义不符（display 恢复 provider 优先、placeholder 不再写 length、tiny-window 只保证 1 token） | 三处测试改成断言新语义，并写明理由 |

---

## 三、最终核验

- 全量单测：**6921 通过 / 0 失败**（较基线 6928 少 7 条 = 删除的 `HarnessConfigStoreTest`）
- 门禁全绿：architecture（1531 文件；`ChatViewModel` 4613/4613、`MemoryRecallEngine` 789/800）、CJK（95 文件/807 字面量）、announced tools（23）、schema drift（23 版本）、string refs、工具执行契约、标题/预算契约、instruction state 14/14
- 兼容性抽查：`budgetTokensForWindow` 对 null/32k/128k/200k 仍为 60000/11200/44800/60000（`PostAnchorBudgetTest` + CI 契约脚本双重钉住）；`LoopGuard` 默认行为逐分支等价；`WalkBack`/`ResidentWindow` 切点不变

## 四、未修复项（明确记录，不假装已修）

1. **journal 记录的是图片预算前的历史**（A2）：修复点在生产装配顺序（`applyRequestImageBudget` 之后才写 journal），需要动行数已到顶的 loop 文件；当前 journal 无生产读取方，风险为未来对拍失真。已在代码注释标注。
2. **压缩冷却的用户文案**：现在走 `vm_compaction_failed` + "wait Ns before retrying"，中英混排在部分语言下不理想；正确做法是新增 `vm_compact_retry_cooldown` 字符串并补 17 个语种翻译（未做）。
3. **`MemoryUsageLedger` 的 UI 展示**：只加了诊断日志，没有设置页入口。
4. **`LoopGuard.stopEnabled` 的 UI 开关**：seam 就位，无入口。
5. **`repairToolPairing` 仍是独立 fold**：身份归一化已同源，但 fold 本身没有并到 `ToolPairingCore`。

---

## 五、第二轮审查的 8 条次生问题与修复（本轮实际结果）

第一轮修复集本身又被审查了一遍，发现 8 条次生问题，全部修复：

| # | 次生问题（修复引入） | 处置 |
|---|---|---|
| N1 | **HIGH** 回执"只播报一次"的标记发生在**返回字符串之前**：冲突拒绝分支与 dispatch 抛异常分支都会把 notice 丢掉，于是这条"outcome unknown，先核验再重试"的警告永久消失——正是本功能要防的双重执行。 | 拆成 `pendingUnsettled()`（只读）+ `ackUnsettled()`（交付后才确认）；冲突拒绝的错误文案也带上 notice；抛异常路径不 ack。新增测试 `peekingTheNoticeDoesNotConsumeIt`。 |
| N2 | **MED** 冲突判定只看"仍未闭合"，于是"上次失败/被取消 + 同 id 换参数"被放行，可能双重执行。 | `State` 增加 `ambiguous` 集合：失败 settle 与 abandon 都标记；冲突条件 = 同 id 不同指纹 且（未闭合 或 ambiguous）；**干净成功**的同 id 复用才豁免（provider/relay 复用 id 的现实）。新增测试 `aCleanlySettledIdMayBeReusedButAFailedOneMayNot`。 |
| N3 | **MED** `trim()` 清空整个缓存（含 `reported`）→ 长会话每次裁剪后把仍在 open 的回执**再播报一遍**，且 `stateFor` 不持锁存在重建窗口。 | 每会话文件一把锁，从投影创建、变更、trim、append 全在同一临界区内；trim 后**保留 `reported`** 重建投影。 |
| N4 | **MED** `TokenMeterTest` 的一条断言被改成空断言（恒绿），且三处 KDoc 与 provider-first 语义矛盾。 | 恢复快照与 display 的一致性断言；`TokenMeter` 类注释、`sourceIsProvider`、`fromJournalSnapshot` 的说明改成 provider-first（并注明门限用 `pressure()`）。新增测试 `theSnapshotAgreesWithTheDisplayPrecedence`。 |
| N5 | **P3** `MemoryRecallSources` 声称"单次读"不成立：`scanMemoryFiles` 自己还在读一遍用来算 supersedes，随后又读一遍。 | `scanMemoryFiles()` 改回"只返回文件列表、不读"；唯一一次读取与 eligibility/supersedes 判定都在 `recallSources()`。 |
| N6 | **P3** elapsed 基线漂移：只在真正注入 note 时才 `markModelVisible`，于是 elapsed 是"距上次注记"（可能多半拍）而非"距上条消息"——写给模型的事实错误。 | 在 `applyRequestClockNote` 找到真实用户轮后**无条件**先记基线，再做各种 early-return。 |
| N7 | **P3** 掩码收紧过头：`password=correcthorsebatterystaple` 这类纯字母长密码不再被掩码，而该输出会被持久化。 | bare 分支只要求 8+ 凭据字符（不再要求含数字/符号）；空格仍是边界，"secret: family recipe" 这类散文不会误伤。新增两条测试。 |
| N8 | **P3** 回执投影无淘汰；`rowFor`/`supersededDates`/`primedDir` 成为死代码。 | 投影改 LRU（32 会话）；删除三个死成员。 |

第二轮同时验证通过的项：`SessionJournal` 锁无嵌套死锁；`scanMemoryFiles` 重写后无重复读；删掉 `HarnessConfigStore` 后 5 个消费方与预算契约脚本不受影响；`MemoryMetadata.strip` 对空串/单独 `---`/未闭合块/CRLF 均返回原文本；`TokenMeter` 三函数语义自洽；`OpenAIStreamSupport` 的 UUID fallback 无测试依赖旧形状。

## 六、最终核验（提交前最后一次全量）

- 全量单测：**6928 通过 / 0 失败**
- 门禁 9 项全绿：architecture（1531 文件，`ChatViewModel` 4613/4613、`MemoryRecallEngine` 785/800、`ToolTurnExec` 763/800）、CJK（95 文件 / 807 字面量）、announced tools（23）、schema drift（23 版本）、string refs、工具执行契约、标题/预算契约、ThinkingLevel、instruction state 14/14
- 新增/改写的回归测试共 6 条，均能在对应修复被回退时失败（回执 peek/ack、冲突豁免规则、strip 语义、display 优先序、长字母密码、散文不误伤）
