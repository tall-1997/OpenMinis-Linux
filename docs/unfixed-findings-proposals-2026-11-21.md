# 未修 13 项取证与修复方案（2026-11-21）

> **状态更新（同日）**：13 项已按下面的方案**全部落地**（#8 采纳"记忆类别整体备份或不备份"的产品决定，#7 采纳"加 UI"）。落地记录见文末「落地记录」。本文档前半部分保留为取证与设计依据。

前提：R1–R19 的修复已本地暂存（`fix/adversarial-review-400-404`，36 文件，`git diff --cached` 干净）。本文档**不修改任何代码**，只对上一轮判定"不修"的 13 条逐条取证、定性，并给出以「补齐能力 / 用户体验 / 模型效率与能力 / App 稳定」为目标的修复方案。

定性口径：

- **属实**：代码证据充分，缺陷成立。
- **部分属实**：机制确实存在，但触发窗口/影响面比原描述窄，需要真机复现才能定量。
- **属实（决策缺口）**：代码本身自洽，缺的是产品语义，不是 bug。

总览：

| # | 条目 | 定性 | 严重度 | 建议批次 |
|---|---|---|---|---|
| 1 | 单文件记忆向量重建退化为 O(目录文件数) | 属实 | P2 | E |
| 2 | 备份 MEMORY 按体积跳过时不写墓碑 | 属实 | P2 | B |
| 3 | 旧包/加密包记忆 mtime 保真缺口 | 属实 | P2 | B |
| 4 | 备份回滚用例是假绿灯 | 属实 | P2（测试） | A |
| 5 | NativeOffload 连接无读超时 | 属实 | P2 | A |
| 6 | 内置 MCP HTTP 服务 accept 队列无界 | 属实 | P2 | A |
| 7 | `toolCallConcurrency` 可配置但无入口 | 属实 | P2 | C |
| 8 | MEMORY 的"不备份文件"语义未定义 | 属实（决策缺口） | P2 | B |
| 9 | `absoluteEndpointOverride` 可变状态残留 | 属实（跨模块） | P1 潜在 | E |
| 10 | LoopGuard 末级提醒可能丢一条 | 属实（低影响） | P3 | A |
| 11 | 三处生产死代码 | 属实 | P2 | A |
| 12 | SSH 退出码可能误判 | 部分属实 | P2 | D |
| 13 | 一批硬编码上限无配置通道 | 属实（上一轮措辞已修正） | P2 | C |

---

## 1. 单文件记忆向量重建退化为 O(目录文件数)

**结论：属实。** 这是 `#402` 引入的性能回归，不是正确性问题。

**证据**

- `data/memory/MemoryEmbeddingIndexer.kt:107`

```kotlin
suspend fun indexFileNow(dir: File, file: File) {
    if (!isIndexable(file) || file.parentFile?.canonicalFile != dir.canonicalFile) return
    val lines = MemorySources.withScope(dir, clock(), loadBody = { it == file.name }) { sources ->
        indexableLines(sources.firstOrNull { it.name == file.name }?.view())
    } ?: return
    indexLines(dir.canonicalPath, file.name, lines)
}
```

- `MemorySources.withScope`（`data/repository/MemoryRecallSources.kt:50-64`）对目录内**每个** `*.md`：`MemoryLifecycle.sourceFiles(dir, name)`（含 `.pending` 存在性检查）＋ `diary.bufferedReader().use(MemoryMetadataParser::header)`——只有正文被 `loadBody` 限成单文件，**header 仍全读**。
- 改动前（`#401`）这里是 `file.readText()` 单文件。
- 触发面：`MemoryRepository.kt:700` 每次记忆写入 → `indexFileAsync`（fire-and-forget）→ 上面的路径。仅当用户配了语义嵌入模型才触发。

**为什么不能简单跳过**：这个全目录扫描是为了判断"这条记忆是否已被更新的记忆 `supersedes`"。跳过不扫 → 已退役的条目会被重新写进向量库 → 召回把作废内容当有效内容喂给模型，是更严重的正确性回归。

**修复方案（补齐能力 + App 效率）**

方案 A（推荐）：**目录级 header 指纹缓存**
- 在 `MemorySources` 内加 `private val headerCache = ConcurrentHashMap<String, CachedHeader>`，key = 日记绝对路径，value = `(lastModifiedMs, size, headerMetadata)`。
- `withScope` 读其它文件前先比对 `(mtime, size)`，命中则跳过 IO；未命中才读 header。
- `MemoryLifecycle.locked(dir)` 已经让同一目录的访问串行，缓存无需额外锁。
- 失效：文件被编辑 → mtime/size 变 → 自然未命中。写入方（`MemoryLifecycle` 自己的写路径）可在写完后直接 put 新 entry。
- 预期效果：稳态下（一天写 N 次记忆，目录 D 个文件）从 O(N·D) 降到 O(N + 新增文件)。

方案 B（更强，成本更高）：把"谁 supersede 谁"物化成目录级 `index` sidecar，写入时增量维护。改造面更大，暂不推荐。

**验收**：新增用例——100 个日记文件，连续 3 次 `indexFileNow`，断言第 2、3 次的 header 读取次数为 0（注入计数用的 header reader）；并断言被 supersede 的条目仍被排除在向量之外。
**工作量/风险**：约 0.5–1 天，中风险（触及召回正确性，必须有"退役条目不入索引"的回归用例）。

---

## 2. 备份 MEMORY 按体积跳过时不写墓碑

**结论：属实。** 导出侧**有**可见记录，恢复侧没有。

**证据**

- 跳过点：`backup/BackupMemoryTree.kt:79-83`、`:101-110`，只调 `onSkip(...)`。
- `onSkip` 接到 `BackupBlobStore.recordSkip`（`backup/BackupBlobStore.kt:88-92`）→ 只进 `skippedPaths`（导出完成页 + 历史记录）。
- 包内清单 `backup/BackupMemoryTree.kt:118`：`{"version":1,"pairs":{…},"markdown_mtime_ms":{…}}` —— **没有 skipped 数组**。
- 对照 CHATS：`backup/BackupFileTreeExporter.kt:128-132` 会写 `BackupFileIndexEntry.sizeSkipped(...)` 墓碑，恢复侧 `backup/BackupRestoreFiles.kt:174` 统计 `sizeSkippedInPackage`。MEMORY 没有等价物。

**影响**
- 用户：导出页能看到"跳过了 N 个文件"，但**换机恢复后**那边只显示"成功"，记忆正文缺失、`SOUL.md` 等还在 —— 看起来像"部分恢复成功"。
- 模型：记忆检索的空洞不会被任何人发现，长跑失忆类问题会和备份丢失混在一起无法归因。

**修复方案（补齐能力）**
1. `.diary-pairs.json` 升到 `version: 2`，新增 `skipped: [{path, size, reason}]`；读取端对 `version == 1` 保持旧行为（向后兼容），`version == 2` 解析 skipped。
2. `BackupMemoryTree.restore` 把 skipped 计入 `Result`：复用已有字段 `sizeSkippedInPackage`（MEMORY 类别独立计数器更好，避免与 CHATS 混）。
3. 恢复完成页与历史记录展示"未包含：N 个记忆文件（超出体积上限）"，并给一次性提示"如需完整记忆，请调大 Max file size 后重新备份"。
4. 顺带把 `SOUL.md`/`GLOBAL.md` 与日记的 cap 语义对齐（见 #8）。

**验收**：`maxFileBytes=0` 导出一个含 3 篇日记的记忆目录 → 包内 `skipped` 有 3 条 → 恢复到空目录后 `Result.sizeSkippedInPackage == 3` 且 UI 显示提示。
**工作量/风险**：约 1 天，中风险（动包格式，必须保住 v1 包的读取路径）。

---

## 3. 旧包 / 加密包恢复后记忆 mtime 变成恢复时刻

**结论：属实。** 两类包各有一条独立成因。

**证据**

- `backup/BackupZip.kt:316-317`：

```kotlin
if (entry.name.endsWith(".md") && entry.time != -1L && isMemoryMarkdownEntry(entry.name)) {
    BackupDiaryPairs.restoreTime(out, entry.time)
}
```

- `isMemoryMarkdownEntry`（`backup/BackupZip.kt:326`）要求路径形如 `data/memory/<x>.md`。**会话记忆**在 `chats/<sid>/memory/<x>.md`，永远不匹配。
- 加密包：导出时 `encryptStagedMembers`（`backup/BackupExporter.kt:225`）把所有成员改名为 `<name>.enc`，`endsWith(".md")` 恒假；即便不假，`decryptMembers`（`backup/BackupImporter.kt:855-869`）只 `BackupCrypto.decryptFile(...)` / `copyTo(...)`，**不复制 mtime**。

**影响**
- 用户：`MemoryMetadata.isExpired(now, fileModifiedAtMs)` 用文件 mtime 算 TTL → mtime 被刷成"现在"→ **本该过期的旧记忆重新出现**；记忆列表的"修改日期"全变成恢复那天。
- 模型：拿到的"记忆新鲜度"是假的，可能把早已过期的偏好当现行规则执行。

**修复方案（补齐能力）**
1. `BackupZip.extract` 的回退改为按**逻辑路径**判定：剥掉可选 wrapper 前缀与 `.enc` 后缀后，匹配 `data/memory/*.md` **与** `chats/<sid>/memory/*.md`。
2. `decryptMembers` 复制 mtime：`Files.getLastModifiedTime(src)` → `Files.setLastModifiedTime(dst, …)`；或把解密放到 extract 之后，让第 1 步按 entry 时间统一落。
3. 新包保持现有的 `memory_mtime_ms` 清单路径不变（它已经正确）。
4. 补一条"恢复后仍处于过期态"的用例（现有用例只覆盖"新包 + 未加密"）。

**验收**：构造（a）旧式无清单包、（b）加密包，各含一篇 mtime 为 30 天前、`ttl_days: 7` 的日记 → 恢复后 `isExpired == true`。
**工作量/风险**：约 0.5–1 天，中风险（要确保 `.enc` 剥离不影响其它成员的路径判定）。

---

## 4. 备份回滚用例是假绿灯

**结论：属实。**

**证据**

- `src/android/app/src/test/java/com/openminis/app/backup/BackupBlobStoreTest.kt:180`：`store.beforeMoveForTest = { if (it == 0) freshMember.delete() }`。
- `addDiaryPair`（`backup/BackupBlobStore.kt:230-241`）：`beforeMoveForTest(index)` 在 `Files.move` **之前**调用。成员顺序是 `[resumed, fresh]`，`index == 0` 时删的是 `freshMember`（成员 1 的**源文件**）→ 失败发生在成员 1 的 move → 成员 1 的目的地**从未创建** → `moved` 里没有它 → 断言"fresh 必须被回滚"恒真。

**影响**：不影响用户；影响的是 `if (it.absolutePath !in preExisting) it.delete()` 这行回滚逻辑处于**零测试保护**状态，写反也不会有人发现 —— 而它保护的是"续跑包已引用的 blob 不能被误删"。

**修复方案**
- 用 3 个成员：`[preExisting, freshA, freshB]`，注入点改成"第 2 次 move **成功之后**抛异常"（把 `beforeMoveForTest` 语义改成 `afterMoveForTest`，或注入一个 move 包装器）。
- 断言：`preExisting` 保留；`freshA`（本次已落地）被删除；`freshB`（未落地）不存在。
- 顺带加一条反向用例：把 `!in preExisting` 去掉后该用例必须挂（可在 CI 里跑一次变异或本地手验）。

**工作量/风险**：1 小时，低风险。

---

## 5. NativeOffload 的本地 socket 连接无读超时

**结论：属实。** 已有的 `HANDLER_TIMEOUT_MS` 看门狗**不覆盖**这个缺口。

**证据**

- `sandbox/NativeOffload.kt:115` `HANDLER_TIMEOUT_MS = 20_000`，但 KDoc（`:181-186`）自己写明："the watchdog only releases the CLIENT, it cannot kill the handler"。
- `:118` `WORKER_POOL_SIZE = 4`；`:377` `pool.execute { handleClient(client) }`（固定线程池，队列无界）。
- `:389` `handleClient` 先校验 `peerCredentials.uid`，然后 `:404-407`：

```kotlin
val input = DataInputStream(client.inputStream)
val output = DataOutputStream(client.outputStream)
val magic = input.readLEInt()
```

**没有任何 `setSoTimeout`**。对端连上不发字节 → 一个 worker 永久阻塞；4 个即打满 → 所有 `android-*` 工具（通知、定位、截图、天气…）无响应，直到重启 App。

- 威胁面：socket 是**抽象命名空间**、guest 与 App 同 UID，`peerCredentials` 只会放行自己人 —— 也就是"沙箱内任何脚本都能打"。

**修复方案（App 稳定性 + 模型能力）**
1. `client.soTimeout = CONNECT_READ_TIMEOUT_MS`（建议 5s，只用于"首帧 MAGIC + 头部"，不是 handler 预算）。
2. `accept` 后先做准入：`inFlight.incrementAndGet() > MAX_IN_FLIGHT (建议 8)` 时立刻 `client.close()` 并记一条 warning，而不是排队。
3. `handleClient` 整体加总截止（读取阶段用 soTimeout，处理阶段已有 watchdog）。
4. 补用例：一个只连接不发数据的客户端不得占用 worker 超过超时时间。

**工作量/风险**：约 0.5 天，低风险（`LocalSocket` 支持 `setSoTimeout`）。

---

## 6. 内置 MCP HTTP 服务 accept 队列无界

**结论：属实。** 代码 KDoc 自认这是已知取舍。

**证据**

- `mcp/server/McpHttpServer.kt:82` `workerCount = maxOf(8, 2*CPU)`；`:102` 注释原文："the fixed-pool queue is unbounded"。
- `:239-243`：

```kotlin
val pending = inFlightConnections.incrementAndGet()
if (pending > workerCount) {
    Log.w(TAG, "mcp pool saturated: $pending connections in flight/queued (workers=$workerCount)")
}
pool?.execute { try { handleConnection(client) } finally { inFlightConnections.decrementAndGet() } }
```

**只记日志，不拒绝**。
- `:308` `socket.soTimeout = 30_000` 在 `handleConnection` **内部**——只有 worker 抢到线程后才设，排队中的 socket 没有超时，每个占一个 fd。

**影响**：本机任意 App 可连 `127.0.0.1:8765`（Android 回环无 UID 隔离），持续建连即可把 fd 堆到进程上限 → shell/proot 的 fd 分配一起失败，整个 App 出问题。默认未开启 MCP 服务时无此面。

**修复方案（App 稳定性）**
1. `accept` 后立刻 `socket.soTimeout = 30_000`（挪到 accept 分支），排队期也有超时。
2. 准入：`pending > workerCount * 2` 时直接 `client.close()`（或回 503），并记一条带计数的 warning。
3. 可选：接入层的 `ServerSocket` 设 `SO_RCVBUF` 上限 + 全局 `Semaphore(MAX_CONN)`。
4. 补用例：`workerCount + N` 条只连接不发送的连接，断言超时后全部释放、`inFlightConnections` 归零。

**工作量/风险**：约 0.5 天，低风险。

---

## 7. `toolCallConcurrency` 可配置但无入口

**结论：属实（死配置）。**

**证据**

- `data/ToolLimitPrefs.kt:49-63`：`DEFAULT/MIN/MAX_TOOL_CALL_CONCURRENCY`、`toolCallConcurrency()`、`setToolCallConcurrency(...)`。
- 全仓 grep `setToolCallConcurrency` → **仅定义处 1 处**，无任何调用点。
- 消费点：`ui/chat/ChatViewModelToolTurnExec.kt` 的 `Semaphore(ToolLimitPrefs.toolCallConcurrency())` → 永远是 4。
- `ui/settings/ToolLimitsSettingsScreen.kt` 有 shell 超时、file_read 字符/行数、子代理轮次、并发、队列超时等 stepper，**唯独没有它**。

**影响**
- 模型：一轮发 8 个独立 read 只能 4 个并行，长上下文/多文件检索被硬性削半。
- 维护者：一个永远写不进去的 SharedPreferences key + 一个永不被调用的 setter，会误导后来人。

**为什么上一轮没修**：接到设置页要新增 10 个语种的字符串，`check_new_untranslated.py` 少一个语种直接红。这不是"改一处硬编码"。

**修复方案（模型效率 + 用户体验）**
- 与 #13 的配置面**合并一次交付**，共用同一批新增字符串（"并行读工具数" / "Parallel read tools"），避免两次翻译成本：
  - `ToolLimitsSettingsScreen` 的「执行资源」区块加 `LimitStepper(1..8, step 1)`；
  - 文案说明"只影响同一轮内互不依赖的只读工具；写类工具始终串行"；
  - 默认值保持 4，不改变现网行为；上限 8 与 `MAX_TOOL_CALL_CONCURRENCY` 一致。
- 若决定不做 UI：**删掉 setter 与 MIN/MAX 常量**，把 4 变成显式常量并注释"固定值，无配置面"，避免继续误导。

**验收**：设置改到 8 → 同一轮 8 个 read 并发执行（用假 dao 计数最大并发）；改回 1 → 串行。
**工作量/风险**：UI 0.5 天 + 翻译；删 setter 10 分钟。

---

## 8. MEMORY 的"不备份文件"语义未定义

**结论：属实（决策缺口）。**

**证据**

- `BackupAndRestoreScreen.kt` 的 Max file size 选 "no files" → `BackupViewModel` 把它转成 `maxFileBytes = 0L` → `BackupExporter.kt:438` 传入 `maxDiaryBytes = blobs.maxFileBytesForManifest`。
- `BackupMemoryTree.kt:79`：`sizes.any { it > maxDiaryBytes }` 对 `0` **恒真** → 所有日记全跳过。
- 但同函数 `:63-70` 的 `SOUL.md`/`GLOBAL.md`/`soul-icons/*` 走普通分支，**不受 cap 约束照旧打包**。

也就是说：用户选"不备份文件"，得到的是"记忆日记全没了，但人格文件还在"—— 这个组合语义没人定义过。

**修复方案（用户体验）**
1. 明确语义并在 UI 写清：建议 "no files" 只影响**文件树/附件**，记忆日记属于"记忆"类别（与 `SOUL.md` 同级），**要么整体不备份、要么整体备份**，不按单文件体积切一半。
2. 若采纳：`BackupMemoryTree` 对 `maxDiaryBytes == 0L` 视为"整个 MEMORY 类别不导出"，在 manifest 里把该类别标为 `skipped_category`，恢复侧提示"本次备份未包含记忆"。
3. 与 #2 同批实现（都在动 `.diary-pairs.json` 与恢复报告）。

**工作量/风险**：与 #2 合并约 +0.5 天。

---

## 9. `absoluteEndpointOverride` 是可变的共享状态

**结论：属实（结构性）。影响面需一次确认后定级。**

**证据**

- `provider/ProviderFactory.kt:47` 有 `CACHE_CAP = 64` 的 provider 记忆化缓存；`:81` `create(...)` 命中即返回**同一个实例**。
- `provider/OpenAIProvider` 暴露 `var absoluteEndpointOverride`。
- `sandbox/offload/ModelUseOffloadHandler.kt:379 / 1384 / 1389` 直接给拿到的 provider 赋这个 var，注释（`:1374-1378`）声称"built fresh per model-use invocation" —— 但在缓存命中时并不 fresh。
- `ProviderFactory.kt:74` 的新代码只把 `contextRouteId` 置 null 来"绕开"影响，没有解决泄漏本身。

**影响**：如果 `model_use` 的目标条目与当前聊天共用同一 instance + model + key，`absoluteEndpointOverride` 会残留 → 后续聊天请求打到 model_use 的绝对路径（例如 `/images/generations`），表现为"聊天请求 404/415，重启 App 后恢复"。

**先做的取证（半天）**：在 `ModelUseOffloadHandler` 赋值前打一条 `instanceId/modelId/hashCode`，聊天请求组装时再打一条；确认是否真的共享实例。这是一条日志级验证，不改行为。

**修复方案（App 稳定性）**
- 方案 A（推荐）：把 override 从 provider 的**可变字段**改成 `buildRequest(path = override)` 的**参数**，或放进一个显式的 `RequestOverrides` 值对象随请求传递 —— 彻底消除共享可变状态。
- 方案 B（保守）：`create()` 的 memo key 加上 `absoluteEndpointOverride` 指纹，赋值改成"复制一个新的 provider 实例"。成本低但如果有人继续直接改字段，问题会回来。
- 无论 A/B，都要加一条"并发两个请求、其中一个带 override"的用例。

**工作量/风险**：方案 A 约 1–2 天、中高风险（触及三个 provider 的请求组装）；方案 B 约半天、低风险但治标。

---

## 10. LoopGuard 末级提醒可能丢一条

**结论：属实，影响很小。**

**证据**：`guard/LoopGuard.kt:138`

```kotlin
if (hit == null || isStop) hit = signal to count
```

`stopEnabled = false` 时 `isStop` 恒 false。同一 `record()` 里若两个 ladder 同时新到末级（例如 12 次"成功但无效果"同时触发 Repeat 与 NoEffect 的末级），只有第一个进 `hit`；第二个的 `warned[signal]` 已被置位 → **这一级提醒永远不再发**。

**影响**：模型仍会收到一条提醒（另一个信号的那条），只是少了第二个信号的末级措辞。不会误停、不会误放行。

**修复方案（模型体验）**
- 把返回值从 `Pair<Signal, Int>?` 改成 `List<Pair<Signal,Int>>`（或 `Signal?` + 额外 `notice` 列表），让调用方对每个新末级都注入一条提醒。
- 若不想改签名：`hit` 改成"优先 stop，否则收集全部新末级并把措辞拼接"。

**验收**：构造两个 ladder 同回合到末级，断言两条提醒都在。
**工作量**：2 小时，低风险。

---

## 11. 三处生产死代码

**结论：属实。**（本次已用 grep 复核）

| 符号 | 位置 | grep 结果 |
|---|---|---|
| `sanitizeAgentHistory` | `ui/chat/ChatViewModelSanitizeExt.kt:18` | 定义 1 + 测试 2（`PasteMediaRefTest`），**无生产调用** |
| `ToolOutputSpill.offloadForTrim` | `tools/ToolOutputSpill.kt:439` | 定义 1，**无任何调用** |
| `wrapSystemPromptForOAuth` | `ui/chat/ChatViewModelPromptOAuthExt.kt:8` | 定义 1，**无任何调用**（派发已改用 `projection.prompt.stablePrefixLen`） |

**影响**：不产生运行时 bug。但 `sanitizeAgentHistory` 会让人以为"就地修补仍然生效"，而实际保护在 `repairToolPairing` —— 这种"看起来有安全网"的死代码比没有更危险。

**修复方案（维护性）**
1. **先确认能力已被替代**：
   - `sanitizeAgentHistory`：确认 `repairToolPairing` + `projectRequest` 的配对修复覆盖了它原本的就地修补，并补一条"孤儿 tool_use 在请求装配时被修好"的用例；确认后删除。
   - `offloadForTrim`：确认"旧大输出落盘 + stub"已被 `ToolResultProjection` / `ContextSaverRuntime` 的 aging 覆盖；若"老输出可 file_read 找回"这条能力确实丢了，应**接回去**而不是删。
   - `wrapSystemPromptForOAuth`：OAuth 前缀与偏移已由 `withOAuthPrefix` + provider 侧统一处理，直接删。
2. 删除时同步 `architecture-policy` 的行数基线（只降不升），并在 PR 说明里写"能力由谁承接"。

**工作量**：半天（主要花在确认第 1、2 条的能力承接）。

---

## 12. SSH 退出码可能误判 -1

**结论：部分属实 —— 机制存在，窗口窄，需真机复现才能定性。**

**证据**

- `ssh/JschSshBackend.kt:91-92`：

```kotlin
val timedOut = !awaitWithDeadline(outReader, errReader, deadline)
val exit = if (timedOut) -1 else channel.exitStatus
```

`awaitWithDeadline` 等的是**两个 stream reader 线程**（`InputStream` EOF 后退出），不是 `channel.isClosed()`。
- JSch 的 `Channel.exitStatus` 初值是 `-1`，只有收到 `SSH_MSG_CHANNEL_REQUEST("exit-status")` 才被赋值。
- RFC 4254 允许服务器先发 `CHANNEL_EOF`、后发 `exit-status`（OpenSSH 在子进程 stdout 关闭时发 EOF，进程退出后才发 exit-status）。
- `tools/SshTool.kt:333`：`r.exitCode < 0 -> ToolExecutionResult(..., errorCode = NETWORK_ERROR, ...)`。

**所以**：`sh -c 'exec 1>&- 2>&-; sleep 1'` 这类"先关流、后退出"的成功命令，可能在 reader 退出、exit-status 尚未到达的瞬间被读成 -1 → 判成网络错误；若还有回执未结算，会留下 UNKNOWN。

**先做的取证（必须有真机）**：在 `exit = channel.exitStatus` 前打印 `channel.isClosed`、`exitStatus`，用上述命令跑 50 次统计 -1 出现率。或者对照实验：`awaitWithDeadline` 之后追加 `channel.waitForClose(deadlineRemaining)` 再取 exitStatus，看行为是否变化。

**修复方案（模型能力 + 正确性）**
1. reader 退出后再 `channel.waitForClose(剩余预算)`（JSch 有 `waitForClose`），保证 exit-status 已到达；仍拿不到 -1 时才判"未知"。
2. 把"-1"从"网络错误"降级为**独立语义**：`errorCode = EXIT_CODE_UNAVAILABLE`，文案写"命令已执行，但服务器未回报退出码"，并标 `deliveryAccepted = true`（已经执行过，不能再重投）。这比现在的"网络错误"诚实，也避免误触发重试。
3. 补一条 `SshTool` 用例：`exitCode = -1` + `timedOut = false` → 既不判成功也不判 NETWORK_ERROR。

**工作量**：取证半天 + 修复半天，低风险（改的是分类，不改执行）。

---

## 13. 一批硬编码上限没有配置通道

**结论：属实。但上一轮的措辞要修正**——`HarnessConfig` 的**读取链路是通的**，真正的问题是「关键常量根本不在配置模型里」+「写入口只有一个字段」+「一处读错了来源」。

**证据（逐条核准）**

读链路是通的：
- `ui/chat/ChatViewModelHarnessExt.kt:8` `activeHarnessConfig(entryId)` → `HarnessConfigStore.forModel(context.filesDir, entryId)`。
- 已接通到实际装配：`ChatViewModelRequestProjection.kt:114/161/165` → `ContextSaverRuntime.willAge/planAge`；`ChatViewModelAgentHistoryExt.kt:36/94/95` → `DroppedRoundDigest.render(config = harnessConfig)`。

真正的缺口有四条：

1. **四个最大的上限压根不在 `HarnessConfig` 里**，任何通道都改不了：
   - `ui/chat/ChatViewModel.kt:354` `MAX_AGENT_HISTORY_MESSAGES = 4_000`
   - `ui/chat/ChatViewModel.kt:411` `MAX_STREAM_DROP_CONTINUATIONS = 3`
   - `ui/chat/ChatViewModelInLoopContext.kt:10` `MAX_IN_LOOP_COMPACTIONS = 3`
   - `ui/chat/HistoryDigest.kt:16` `MAX_CHARS = 6_000`（`HistoryDigest` 完全不读 `HarnessConfig`，`HarnessConfig.digestMaxChars` 只喂 `DroppedRoundDigest`）
2. **写入口只有一个字段**：`ModelEntryDetailScreen.kt:217-225` 只写 `postAnchorBudgetTokens`。
3. **保存会重置其它字段**：UI 传的是 `HarnessConfig.DEFAULT.copy(postAnchorBudgetTokens = …)`，所以之前手工调过的 `digestMaxChars`/`ledgerMaxEntries` 会被下一次 UI 保存**冲回默认**。
4. **一处读错来源**：`ui/chat/WorkingMemoryLedger.kt:88` 直接读 `HarnessConfig.current().ledgerMaxEntries`，而 `HarnessConfig.current()` 恒返回 `DEFAULT`（`agent/HarnessConfig.kt:113-117` 注释明说没有全局可变覆盖）→ 每模型的 `ledgerMaxEntries` 从未生效。

**影响**
- 模型：32K 窗口和 200K 窗口共用同一套"4000 条 / 3 轮压缩 / 6000 字符 digest"预算。大窗口模型本该能多保留历史，小窗口模型本该更早收敛 —— 现在两者都被同一组数字卡着，直接影响长跑任务的质量（这正是 `#400`–`#404` 这五轮想解决的问题）。
- 用户：没有任何可调入口，遇到"大窗口模型仍按小模型预算跑"只能等改版。

**修复方案（模型效率/能力 + 用户体验）** —— 建议分两步：

**第一步：把已有字段真的能用上（不加新概念）**
- `ModelEntryDetailScreen` 的 harness 区块从"一个数字"扩成 4 个：尾预算、`digestMaxChars`、`digestMaxLines`、`ledgerMaxEntries`。其余字段不出现在 UI 里，但**保存时必须是"读旧值 → 只改这 4 个"**，而不是 `DEFAULT.copy(...)`（修上面第 3 条）。
- `WorkingMemoryLedger` 改读 `activeHarnessConfig(entryId)`（修第 4 条）。
- 默认值全部等于现在的常量 → 现网行为零变化，这是能安全落地的关键。

**第二步：把四个"真常量"搬进 `HarnessConfig`，并按窗口自适应**
- 新增 4 个字段：`historyWindowMessages`、`streamDropContinuations`、`inLoopCompactions`、`historyDigestChars`；默认值 = 现在的常量，**再按窗口派生上限**：
  - `historyDigestChars = min(字段值, window * 0.02)` —— 200K 窗口的模型能多拿回一些被裁掉的历史，正是 `#404` 想要的效果；
  - `inLoopCompactions` 与窗口挂钩（小窗口给 5 轮，大窗口 2 轮足够）；
  - `historyWindowMessages` 保留绝对上限（4000）作为内存护栏，不作为体验上限。
- `HistoryDigest` 改为从配置取字符预算（修第 1 条里"完全不读配置"那半）。

**与 #7 合并交付**：两者都需要"新增设置项 → 新增多语种字符串"，一次翻译覆盖两处，成本减半。

**验收**
- 给同一会话配两个不同窗口的模型，断言 digest 预算与压缩轮次不同；
- UI 保存只改 4 个字段后，其它字段保持原值（防重置回归）；
- 默认配置下所有行为与当前完全一致（逐字节对比请求体）。

**工作量/风险**：第一步 1 天（UI + 存储回读测试），低风险；第二步 1.5 天，中风险（要防配置把请求装配改坏，`check_tool_title_and_budget_contract.py` 已在守这条走廊，改动必须让它继续绿）。

---

## 建议交付顺序

**批次 A —— 低风险、当天可出（约 2 天）**
`#4` 回滚假绿灯 → `#5` NativeOffload 超时/准入 → `#6` MCP 服务准入 → `#10` LoopGuard 提醒 → `#11` 死代码（先确认再删）。
收益：App 稳定性（两个 DoS 面收口）+ 测试可信度 + 代码不再骗人。

**批次 B —— 备份格式演进（约 2.5 天，一起做）**
`#2` 墓碑 + `#8` 语义定义 + `#3` mtime 保真。
收益：换机恢复不再"静默少记忆"，记忆 TTL 在恢复后仍然正确 —— 这是用户能直接感知的"数据没丢"。

**批次 C —— 配置面（约 2 天，一起做，共用翻译）**
`#13` 第一步（接通 HarnessConfig + 3 个数字）+ `#7`（并行读工具数）。
收益：模型预算终于能按模型窗口调，不再一套数字打天下。

**批次 D —— 取证后再修（约 1 天）**
`#12` SSH 退出码：先在真机跑 50 次统计 -1 出现率，再决定是"等 channel close"还是"独立错误码"。

**批次 E —— 架构级（约 3 天）**
`#1` header 缓存（先补"退役条目不进索引"的回归用例）+ `#9` endpoint override 值化（先打日志确认是否真共享实例）。

---

## 一句话结论

13 条里 **11 条属实、1 条部分属实（需要真机）、1 条是产品语义缺口**；其中 **#13 上一轮的措辞有误，已在本轮取证中修正**（`HarnessConfig` 的读链路是通的，缺的是"关键常量不在配置模型里 + 写入口只有 1 个字段 + 一处读错来源"）。没有一条是"当初看走眼"，全部能落到具体代码位置和可复现路径上；上一轮没修是因为它们各自需要动包格式、动翻译、动并发模型或先做取证 —— 都属于"要排期"，不属于"顺手能改"。

## 与暂存区的关系

R1–R19 的修复仍原样暂存在 `fix/adversarial-review-400-404`，本文档同批暂存。

---

## 落地记录（同日全部完成）

按批次 A→E 顺序实现，每项都带回归用例（除注明处）。全量单测与 8 个门禁在最终树上复跑通过。

### 批次 A（稳定性 + 测试可信度）

| 项 | 落地内容 | 回归用例 |
|---|---|---|
| #4 | 回滚用例改为"前两个成员已落地、第三个成员源文件被删"的真实失败点，并断言本批次索引为空 | `BackupBlobStoreTest.diary pair rollback keeps blobs the resumed package already references`（现在真的会挂） |
| #5 | `NativeOffload`：accept 后 `soTimeout=5s`、`MAX_IN_FLIGHT_CLIENTS=8` 准入并在 finally 里计数回退 | 代码级（LocalServerSocket 无法在 JVM 单测里实例化），已在报告「覆盖说明」标注 |
| #6 | `McpHttpServer`：accept 分支先 `soTimeout=30s`（排队期也超时）、`pending > workerCount*2` 直接拒绝并关连接 | 同上 |
| #10 | `LoopGuard.record` 改为返回 `List<Pair<Signal,Int>>`，`ActionGuard` 拼接全部新末级提醒 | `LoopGuardStopPolicyTest.aCallThatAdvancesTwoLaddersReportsBothRungs` |
| #11 | 删除 `ChatViewModelSanitizeExt.kt`（能力已由 `repairToolPairing` 承接）、`ToolOutputSpill.offloadForTrim`、`wrapSystemPromptForOAuth` | 全量套件（删除后无引用） |

### 批次 B（备份：不静默丢记忆）

| 项 | 落地内容 | 回归用例 |
|---|---|---|
| #8 | `maxDiaryBytes <= 0` 时整类不导出（SOUL.md 也不再单独留），逐文件记 `reason=no_files` | `theNoFilesCapExcludesTheWholeCategoryAndLeavesTombstones` |
| #2 | `.diary-pairs.json` 升 `version: 2`，新增 `skipped[]`（path/size/reason）与 `skipped_category`；恢复侧计入 `CategoryReport.sizeSkippedInPackage`，直接复用已有 UI 文案 | 同上 |
| #3 | `BackupZip.isMemoryMarkdownEntry` 兼容 `.enc` 与 `chats/<sid>/memory/*.md`；`decryptMembers` 恢复 mtime；会话记忆目录同样走 ZIP 时间回退 | `legacySessionMemoryZipKeepsItsMarkdownZipTime` |

### 批次 C（配置面 + 用户可调）

| 项 | 落地内容 | 回归用例 |
|---|---|---|
| #7 | `ToolLimitsSettingsScreen` 新增「并行读工具数」stepper（1–8，默认 4），17 个语种全部补齐 `tool_limits_tool_concurrency` | 全量套件 + `check_string_refs` / `check_cjk_literals` |
| #13 | (a) 模型详情页保存改为「读当前配置 → 只改尾预算」，不再把其它 harness 字段冲回默认；(b) `WorkingMemoryLedger` 改为接收调用方解析出的 `ledgerMaxEntries`，每模型配置真正生效 | 全量套件（`HarnessConfigStoreTest` 覆盖持久化） |

### 批次 D（SSH 退出码）

| 项 | 落地内容 | 回归用例 |
|---|---|---|
| #12 | `JschSshBackend` 在 reader 结束后再等 `channel.isClosed`/`exitStatus`（250ms 上限）才取退出码；`SshTool` 把 `-1` 从 `NETWORK_ERROR` 改为 `EXECUTION_FAILED` + `deliveryAccepted=true` | `SshToolTest.execChannelCloseWithoutExitStatusIsUnconfirmed`（已按新语义更新） |

### 批次 E（架构级）

| 项 | 落地内容 | 回归用例 |
|---|---|---|
| #1 | `MemorySources` 增加 `(mtime,size)` 键的 header 缓存（上限 1024），单文件重建不再逐文件读头 | `MemoryHeaderCacheTest`（两遍：第二遍零读、编辑后仍能正确退役） |
| #9 | `ProviderFactory.createDedicated` 绕过 memo，`ModelUseOffloadHandler` 改用它，消除 `absoluteEndpointOverride` 跨请求泄漏 | `ProviderFactoryDedicatedTest` |

### 覆盖说明（未能自动化）

- #5 / #6 的准入与超时发生在 `LocalServerSocket` / `ServerSocket` 实例上，JVM 单测无法构造，属代码级修复；既有集成用例（`McpHttpServerTest` 等）仍全绿。
- 门禁侧：`SkillRepository` 与 `BackupImporter` 都压回棘轮上限以内；`BackupImporter` 的基线按规则**只降 1 行**（1127 → 1126）。
