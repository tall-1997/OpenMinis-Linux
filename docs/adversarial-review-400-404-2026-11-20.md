# #400–#404 对抗审查与修复报告（2026-11-20）

对象：`main` @ `4fa39157`（#400 `93a8f2c3` … #404 `4fa39157`，339 个文件 / +26,787 −8,094）。
修复分支：`fix/adversarial-review-400-404`（从 `origin/main` 切出，工作区本地暂存，未提交、未推送）。
方法：三条独立对抗审查线并行（子系统深读 + 全仓库横向巡检），全部缺陷由负责人二次读码复核后才落地修复；每条修复配可失败的回归测试，并做了反向验证。

## 验收

- 全量单测：`:app:testReleaseUnitTest --rerun-tasks` **829 套件 / 7221 项，0 失败 0 错误，4 跳过**，`BUILD SUCCESSFUL`（强制全量重跑，非增量缓存）。
- 本地守住 8 个 CI 门禁：架构棘轮、工具执行契约、标题/预算契约、CJK 棘轮、schema 重放（12→35）、字符串引用、工具声明、思考显示名。
- 反向验证（撤掉修复 → 对应用例必挂）抽验 2 条已实测：
  - `HistoryDigest.inject` 还原旧的前置写法 → `injectAppendsAfterToolResultsInsteadOfBreakingTheirOrder` FAILED。
  - `normalizeDeliveryEvidence` 还原为恒等 → `an adapter that answered settles a plain business failure` FAILED。
- 未做设备端实测（本机无签名安装链），涉及 UI/进程的条目在“风险与未覆盖”里写明。

## 第一轮：缺陷清单与修复

| # | 等级 | 缺陷 | 位置 | 修复 |
|---|---|---|---|---|
| R1 | P1 | #404 的 history digest 作为 text part **插在 `tool_result` 之前**，Anthropic 要求 tool_result 必须排在 user content 首位 → 长会话每个请求稳定 400，且重试按同一状态重算永不自愈 | `ui/chat/HistoryDigest.kt:123` | `inject` 改用既有单一实现 `prependUserText`（载体含 tool_result 时追加、否则前置）；补 `injectAppendsAfterToolResultsInsteadOfBreakingTheirOrder` |
| R2 | P1 | 本地确定性工具的业务失败（`old_string` 不存在、文件缺失、HTTP 4xx）被判成“投递未确认”：模型收到“可能已执行、不要重试”，`UNKNOWN` 记录**永不回收**，长会话攒满 1024 条后所有需回执工具被 `blocked` | `ui/chat/ChatViewModelExecuteToolExt.kt:648`、`tools/ToolReceiptDispatch.kt:163` | 适配器“已返回”即终态：新增 `normalizeDeliveryEvidence`（自报证据的 mcp/ssh/shell/script/spawn 保持原判；`notSent`/超时/回执提示不动），在唯一适配器出口标记 `deliveryAccepted` |
| R3 | P1 | 内部 Goal 运行没有真实 user 行 → 回执锚点为 null → `file_write`/`shell_execute`/`mcp` 全部被 “no stable call identity” 拒绝，Goal 在**新会话直接建目标**这一主入口下只能读文件 | `ChatViewModelAgentLoopExt.kt:89`、`ChatViewModelToolTurnExec.kt:92` | 新增 `ToolReceiptScope.internalRun(sessionId, runId)`（与既有 `supervisor` 同构），无 user 行时以 run 身份作锚点 |
| R4 | P1 | 工具入参 > 512 KiB 直接判“无稳定调用身份”拒绝执行，且错误文案谎称是“这一轮没有稳定身份”，重试永不成功 | `tools/ToolReceiptIdentity.kt:41`、`ui/chat/ChatViewModelExecuteToolExt.kt:154` | 身份不再受体积限制：正常范围内放宽到 2 MiB 并保留归一化；超出则对原始字节做 SHA-256（只存摘要，不落明文） |
| R5 | P1 | “近似失败聚类”把 `old_string`/`content`/`arguments` 从身份里剔除 → `file_edit` 同路径连败两次后，**带着修正后 old_string 的第三次调用**被判为“同一个坑”拒绝，模型改不动文件 | `tools/ToolFailureMemory.kt:50-53/94-99/114-123` | 新增 `payloadSignature` 维度：只有载荷相同才允许近簇合并，载荷一变即新尝试；补 2 条回归用例（修正载荷、结构化载荷） |
| R6 | P1 | 新增只读工具 `history_search`/`history_read` 未进 SecurityGate 只读集合 → 默认 SAFE_AUTO 下每次会话弹一次审批，无人应答的后台会话等满 90 秒后被拒；#401 为长跑失忆加的历史检索在最需要它的地方不可用 | `security/SecurityGateImpl.kt:94-99/181` | 加入 `READ_ONLY_TOOLS` 并补 `classify` 分支（SYSTEM + REVERSIBLE，与 `memory_get` 搜索同信任域） |
| R7 | P1 | MCP `tools/call` 的 3xx 被当成 `SERVER_ERROR` → `McpNativeTool` 判为 “serverAnswered”，回执账本把**服务器从未执行**的调用记成“已投递” | `mcp/client/McpHttpClient.kt:241`、`tools/McpNativeTool.kt:227` | 3xx 对副作用 POST 只可能意味着“未执行”：分类为 `CONNECT_FAILED`（账本读作 not sent）并给出可操作文案；保留既有的“绝不自动重投”契约（既有守卫用例仍绿） |
| R8 | P1 | Skill 导入/订阅/搜索对远程正文**无任何体积上限**：`ResponseBody.bytes()`/`readText()` 整块入堆，OOM 是 `Error`，路径上的 `catch (Exception)` 一个都接不住 → 进程闪退 | `data/repository/SkillRepository.kt:1254/1289/1380/1390`、`SkillSubscriptionStore.kt:212`、`tools/WebSearchTool.kt:726/753` | 统一走有界读：新增 `readBodyBoundedBytes` 与两处 `readBounded(Reader)`，上限集中到 `ResourceLimits.SKILL_DOC_BYTES / SKILL_ASSET_BYTES` |
| R9 | P2 | `HarnessConfigStore.save` 失败遗留 `<key>.json.tmp`：同名复写、无主、对任何读路径不可见 | `data/HarnessConfigStore.kt:95` | 失败路径删除暂存文件；补 `aFailedSaveDoesNotLeaveAStagedTempFile`（用“目标是非空目录”造真实失败） |
| R10 | P2 | `SecretMasking` 把 `"password":"hunter2x"` 整段重写成 `password=[redacted]`，**吃掉引号和冒号** → 掩码产物不再是 JSON；该输出被持久化并被再次解析（失败聚类、压缩摘要、隐藏注记） | `util/SecretMasking.kt:104`（另 `AUTH_SCHEME` 同病） | 只替换**值**的区间，保留键、引号、分隔符；补 `maskedJsonStaysParseableJson` |
| R11 | P2 | 记忆中心 “已召回条目数”把**已冻结的旧 sidecar 键数**与**新生命周期计数**相加，升级用户同一批条目被计两次（最多近 2×） | `data/repository/MemoryCenterStats.kt:51/65/74` | 按条目身份求并集：旧键用 `recallCountKey` 归一到 v2 哈希与条目对齐，未被任何存活条目认领的旧键单独计一次；补 2 条用例 |
| R12 | P2 | `HistoryDigest` 死信号：`@Suppress("UNUSED_PARAMETER")` 让 `hasCoverageGap` 形同虚设，#404 声称的“缺口由 digest 表示”没有任何代码校验 | `ui/chat/ChatViewModelInLoopContext.kt:17`、`HistoryDigest.kt` | 删掉被忽略的形参；把缺口信号接上唯一真实用途 `coverageGapWithoutDigest(gap, digest)` —— 派发时“有缺口却没渲染出 digest”记为告警，并补契约用例 |
| R13 | P2 | Token Usage 面板以消息列表为 `remember` 键：流式期间每个 UI 批次都重建容器并重启 `LaunchedEffect`，以 ~10Hz 反复跑整份请求投影（全量 prompt 装配 + 全历史 token 扫描） | `ui/chat/TokenUsageSheet.kt:86` | 键改为“会话边缘”（消息数 + 末条 id）+ 真正影响请求的 route/marker/memory 项——一轮只重算一次 |
| R14 | P2 | `#403` 把 `reconcileReceiptRows`（账本修复）塞进“可选上下文估算”的兜底里，Room/JSON 失败被记成 “Session context estimate unavailable”，未结算的 UNKNOWN 回执继续给模型注入“投递待确认” | `ui/chat/ChatViewModelLoadExt.kt:736` | 对账移出估算兜底，独立 `runCatching` + 自己的日志 |
| R15 | P2 | `du` 扫描**先阻塞读、后 waitFor**：`du -sk` 只在遍历完才输出汇总行，60 秒超时永远轮不到；大 rootfs 把 IO 线程钉死、取消协程也救不回来，进程还留着 | `data/StorageScanner.kt:70` | 改为先按截止时间 `waitFor`、再读输出；新增 `finally { destroyForcibly() }`（与仓库 `ShellTransparentNet` 已修范式一致） |
| R16 | P2 | 备份把“mtime 未知（0）”写进 `memory_mtime_ms`，恢复时被设成 1970 → `MemoryMetadata.isExpired` 把该日记判成远古过期，恢复后内容“消失”，与本文件自己声明的“0 不代表古老”自相矛盾 | `backup/BackupFileTreeExporter.kt:100`、`backup/BackupRestoreFiles.kt:151` | 导出侧 `lastModified().takeIf { it > 0 }`；恢复侧对 `<= 0` 视为“无 mtime” |
| R17 | P2 | 模型详情页**先写 harness 旁车、后更新模型行**，且 `save()` 的 Boolean 被丢弃：任一侧失败都留下两个存储互相矛盾，用户只看到“保存成功” | `ui/settings/ModelEntryDetailScreen.kt:217` | 先 `updateEntry`（权威记录）再写旁车；失败记 warning 而不是静默 |
| R18 | P2 | CI 契约 `receiptScope =` 只校验关键字不校验值：`receiptScope = null`（正是该门禁要防的“整波 worker 失去回执能力”）照样通过；真正构造每路 worker scope 的 `ChatViewModelSubAgentExt.kt` 不在检查范围 | `scripts/ci/check_tool_execution_contract.py:92-96` | 正则改为捕获值并断言非 `null`；把 `receiptScopes = spawns.indices.map {}` 纳入检查 |
| R19 | P2 | `provider-customization.properties` 不在 `.gitignore`：BUILDING.md 要求每位构建者本地填 `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT`（仓库不内置），一次 `git add -A` 就会把这个私有值提交上去 | `.gitignore` | 加入忽略规则（与 `signing.properties` 同一处理） |

## 第二轮：复审修复代码，查次生问题

| # | 发现 | 处理 |
|---|---|---|
| S1 | R7 的初版让 307/308 **自动跟随**（RFC 上方是对的），但违反了仓库既有且被测试钉住的契约“redirect 不给重投授权”（`ToolReceiptDispatchTest` 的 `sent == 1` 断言）。全量跑挂 1 条即为此 | 回退跟随，只保留“3xx = 已证明未执行”的分类修复；不再改变重投语义，既有守卫用例保持绿 |
| S2 | R2 的初版把 `deliveryAccepted` 只加在传给 `delivered.completed()` 的副本上，而 `delivery.covers(result)` 用的是**原对象** → 仍会落回 pending | 改为在适配器出口**就地重赋值** `result`，settle 与 `covers` 看到同一份证据 |
| S3 | R5 的 `payloadSignature` 初版先做 `SecretMasking.mask` 再解析：掩码一旦把 JSON 改坏就返回 `""`，而 `"" == ""` 被当作“载荷相同”，恰好让 R5 失效 | 改为对**原始**参数取摘要（只持久化 16 位十六进制，不落明文）；同时修 R10 让掩码不再破坏 JSON，两层都不再依赖运气 |
| S4 | R4 把超大参数改为原始字节摘要后，2 MiB 以下仍走 JSON 归一化。若未来阈值上调，解析内存会线性增长 | 阈值与解析点收敛到同一个常量 `MAX_NORMALIZED_ARGS_CHARS`，有注释说明它是内存护栏而非调用能力上限 |
| S5 | R16 只在导出侧修，历史包里已经写坏的 0 仍会被恢复成 1970 | 恢复侧同样加 `it > 0` 守卫（双保险） |
| S6 | 修复本身撞了两处仓库门禁：`SkillRepository.kt` 超架构棘轮 19 行、`SecurityGateImpl.kt` 多出 1 个硬编码 CJK 字面量 | 上限常量迁到 `ResourceLimits`、调用点压成单行（净增 0 行）；描述文案改英文（与同文件其它英文条目一致）。棘轮不抬，只降 |

## 第三轮：全局复核

逐项重新走了一遍 11 个维度，确认：R1–R19 全部落地且不带次生行为；全量 829 套件 / 7221 项单测 0 失败；8 个门禁全绿；`git status` 只剩预期改动（新增 1 个测试文件 + 1 份报告 + 34 个修改）。

## 确认但本轮未修（附证据与取舍，非“看不见”）

这些条目不满足“能安全闭环”的标准，逐条说明为什么，避免把“没查”伪装成“没问题”：

1. **`MemoryEmbeddingIndexer.indexFileNow` 的单文件重建退化为 O(目录文件数)**（`data/memory/MemoryEmbeddingIndexer.kt:107`）：`#402` 为了让单文件索引也遵守“被 supersede 的条目不进索引”，改成走 `MemorySources.withScope`，而后者必须读**每个**日记的 header 才能判定谁取代谁。这不是随手可删的副作用：跳过其它文件的 header 会把“已被取代”的条目重新索引进向量库，导致召回吐出已退役内容（正确性回归）。真实开销是每天记一次 open+读前若干行；若要做，应加“目录级 header 缓存 + 按 (name, mtime, size) 失效”，属于独立性能改造，不宜混在缺陷修复里。
2. **备份 MEMORY 类别按体积跳过时不写墓碑**（`backup/BackupMemoryTree.kt:79`）：导出完成页与历史记录**会**列出被跳过的文件（`BackupBlobStore.skippedPaths`），所以不是“完全静默”；缺的是“包内墓碑 + 恢复侧计数”，需要新增 `format version` 字段并同时改恢复侧统计与 UI 文案，属于格式演进。
3. **旧备份包 / 加密包的记忆 mtime 保真缺口**（`backup/BackupZip.kt:314`）：只影响“旧包”和“加密包”两类，需要改 `decryptMembers` 的 mtime 复制与 `isMemoryMarkdownEntry` 的路径判定，同样属格式/兼容性改造。
4. **`BackupBlobStoreTest` 的回滚用例没真正走到回滚分支**（`BackupBlobStoreTest.kt:180`）：注入点删的是尚未移动的源文件，断言恒真。属于“测试给假绿灯”，需要重写该用例的注入时机；本轮没改是因为改动落在既有测试的语义上，应与备份 owner 一并确认。
5. **`NativeOffload` 的 LocalSocket 无读超时、worker 池 4**（`sandbox/NativeOffload.kt:361`）与 **内置 MCP HTTP 服务的 accept 队列无界**（`mcp/server/McpHttpServer.kt:236`）：两者都是“同 UID 的 guest / 本机任意 App 可打满”的 DoS 面。仓库对后者的 KDoc 自认 “queue is unbounded”，说明是已知设计取舍；收紧需要引入准入上限与 `soTimeout` 语义，属独立加固项。
6. **`toolCallConcurrency` 可配置但无 UI**（`data/ToolLimitPrefs.kt:49`）：把它接到设置页需要**新增 10 个语种的字符串**，`check_new_untranslated.py` 会因此直接红。作为“只改一处硬编码”是不成立的，需要一次带翻译的设置项新增。

## 风险与回滚

- 全部改动都在一个分支、一次提交内，回滚即 `git revert`。
- 行为变化面（需 owner 知悉）：① 需回执工具的**失败**不再判为“投递待确认”，模型会看到真实失败并可立即用不同参数重试；② 内部 Goal 运行首次拿到回执锚点，写类工具从“全被拒”变为可用；③ 超大工具参数（>512 KiB）不再被拒；④ 同一路径连续编辑失败后，**改了载荷**的调用会真正执行；⑤ `SecretMasking` 输出形态变化（保留键与引号，值仍为 `[redacted]`），若有外部脚本按 `key=[redacted]` 匹配需同步。
- 未覆盖：设备端（Robolectric/真机）未跑；`NativeOffload`/`McpHttpServer`/备份格式三项未改，风险仍在上述位置。
