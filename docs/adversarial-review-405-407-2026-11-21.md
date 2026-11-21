# 全源码对抗深度审查与修复报告（基线 0ca33e38，分支 `fix/adversarial-review-405-407`）

日期：2026-11-21
范围：全部源码（Android app + core/provider/tools/vd-host 模块 + shared），按 20 维度规范执行三轮强制循环。
原则：只修真实缺陷、只做根因修复；禁止遮盖报错、注释代码、空吞异常、强制返回、降级绕过。

---

## 0. 结论速览

| 项目 | 结果 |
|---|---|
| 真实缺陷修复总数 | **69 项**（第一轮 41 + 次生 2 + 第二轮 24 + 第三轮化妆 2） |
| 误报证伪（审查候选被证据推翻） | 11 项（详见 §5，防止后续轮次重复排查） |
| 编译 | `:app:compileReleaseKotlin` 通过 |
| 完整 Release 测试 | 全量重跑，832 suites / 7240+ tests，0 failures（见 §6 实测数字） |
| 静态门禁 | 10 项全绿（见 §7） |
| 伪修复扫描 | 第三轮全局核验：5603 行新增行逐文件通读，**零发现**（§8） |
| 提交结论 | 可提交（工作区未提交，等待用户指示 push/PR） |

---

## 1. 第一轮审查与修复（41 项真实缺陷）

### 1.1 数据与仓库层

| # | 位置 | 风险 | 缺陷与根因 | 修复结果 |
|---|---|---|---|---|
| 1 | `ChatRepository.querySessionsMeta` / `searchMessages` | P1 | 会话 id 直接拼进 SQL `IN (...)`，超过 999 个参数在 Android ≤11 的 SQLite 上抛 SQLiteException（host parameter 上限） | `chunked(400)` 分块拼接 `OR` 组，参数与占位符严格对齐；0/1/400/401/999 个 id 语义与单 `IN` 等价 |
| 2 | `ChatRepository.restoreArchivedVersions` | P2 | offloaded blob 无法验证时插入 `body_bytes=0` 毒化行 → RequestHistory 永久 fail-closed，会话不可恢复 | 跳过该行、保留 archive 以便后续修复（fail-close 且幂等），已加 INTENTIONAL BEHAVIOR CHANGE 注释 |
| 3 | `ChatRepository.searchMessages` admission 拒绝 | P2 | Admission 预算耗尽时静默 `return emptyList()`——模型把"搜索服务暂不可用"误读为"没有匹配" | 抛 `SearchAdmissionUnavailable`（新类型）；`SessionsOffloadHandler` 回 `TEMP_UNAVAILABLE` 信封 exit 1；`SessionLookupTool` 变为可重试错误文本 |
| 4 | `MemoryRepository.writeMemory` | P1 | 返回的 revision 用纯内容 hash，跨午夜/跨日期重写同一文件时与磁盘 revision（带日期前缀）不一致 → 误报 MEMORY_CONFLICT | 改用 `MemoryRevision.token(fileName, newContent)`，与磁盘格式一致（根因与 #407 修复同源） |
| 5 | `MemoryRepository.getMemory` | P2 | `scope=daily` 把 standing persona 文件（SOUL/PERSONA/AGENTS）当日记搜出 | 非 `all` scope 排除 `MemorySources.isStandingSource`（TTL 生命周期文件与日期日记保留；`all` 语义不变） |
| 6 | `MemoryCenterStats` | P2 | 会话目录按 UUID 名称倒序采样统计，>120 会话时采到随机子集 | 改 `lastModified()` 倒序 |
| 7 | `MemoryFragmentBuilder` | P2 | 双 `@Volatile` 字段缓存（key/value 各一个）非原子发布，可返回新指纹+旧内容的撕裂对注入 prompt | 单一不可变 `CacheEntry` 快照 + 单次 volatile 发布；补 3 个缓存契约测试 |
| 8 | `WorkingMemoryLedger` | P2 | process-wide `@Synchronized` 把不同会话的并行工具写全部串行化 | per-session ledger 锁（第二轮复审后进一步统一锁域，见 §2） |

### 1.2 安全与准入

| # | 位置 | 风险 | 缺陷与根因 | 修复结果 |
|---|---|---|---|---|
| 9 | `SecurityGateImpl` find 判定 | P1（安全） | `-exec/-execdir/-ok/-okdir/-delete/-fprint*` 等副作用参数在 ASK 模式被当只读低风险自动放行 | mutating 参数集合判定（第二轮补 `-fls`，见 §2-B7） |
| 10 | `WritePathGuard.normalize` | P1（安全） | `allowedPrefix/../other` 词法上逃出子代理 write_paths 隔离 | 词法折叠 `.`/`..`，13 条 trace 全验证 |
| 11 | `ConfigBridge` 审计 | P2（安全） | timeout/rejected 路径把明文 apiKey 写入 ConfigAuditLog | 全部审计路径 `redactingSecrets()` + `auditNewValue` |
| 12 | `ConfigConfirmationGate` | P2 | 确认超时从"入队时"起算，排队请求被前面的请求饿死后误超时 | 计时改为"成为 front-of-queue 时"，advance 重启 timer；timeout/cancel/advance 全部同一 mutex 串行 |
| 13 | `LocalToolPluginStore` | P1（安全） | 拒绝集遗漏 `<`——`<(…)` 进程替换透传给 guest bash 执行任意命令 | 拒绝集加 `<`（模板作者的 `<(…)` 仍合法）；用户可见错误文案同步 |
| 14 | `SandboxHttpProxy` IPv6 | P1 | CONNECT 目标/Host 头用 `split(':')`，`[::1]:443` 解析成 host=`[`，所有 IPv6 目的地 UnknownHostException | `parseHostPort()`：括号字面量/无端口/裸多冒号/绝对 URL/端口范围校验，失败回 400 |
| 15 | `SandboxHttpProxy` header 字节 | P2 | header 以 `b.toChar()`（0-255）拼串再按 UTF-8 写回，≥0x80 字节被扩成两字节 | `toByteArray(ISO_8859_1)` 精确还原 |
| 16 | `McpApprovalCore.validId` | P1（安全） | ID 正则含点字符集，`..` 通过校验 → 进入 `taskRootFor` 路径拼接 → 批准后写/递归删除整个 shared 根 | `validId` 追加"至少一个非点字符"；`taskRootFor` fail-closed 抛 IAE；全部消费点核对（RPC catch、delete runCatching、UI 直连路径见 §2-D1） |
| 17 | `McpToolShared.runInGuest` 兜底 | P2 | 宿主故障被伪造成 `exitCode=124`（GNU timeout 惯例）误导调用方 | 兜底改 `-1` + `timedOut=false`；真实超时保留 124 且 `shellExec` JSON 增加 `timed_out` 结构化字段 |

### 1.3 通知与前台服务

| # | 位置 | 风险 | 缺陷与根因 | 修复结果 |
|---|---|---|---|---|
| 18 | `ApprovalNotifier` | P1 | approve/deny PendingIntent request code 不含 requestId，并发审批 A/B 交叉误批 | request code 混入 requestId；small icon 改 alpha `ic_notification_status` |
| 19 | `AskUserNotifier` | P1 | 同 label 的 A/B 问题答案错投（request code 冲突） | request code 混入 requestId+label；alpha icon |
| 20 | `AlarmOffloadManager` | P2 | alarm request code 用 UUID hash，~2^-32 碰撞率；碰撞时第二个闹钟覆盖第一个 | 持久化 `KEY_NEXT_REQUEST_CODE` 计数器（第二轮加锁，见 §2-C3）；alpha icon |
| 21 | `BackgroundTaskNotifier` / `ScheduledNotificationReceiver` | P2 | 非 alpha small icon（framework `ic_dialog_*`），OEM SystemUI 上渲染异常 | 全部改 alpha icon |
| 22 | `AgentForegroundService` | P1 | `mediaPlayback` FGS type 名不副实（无媒体会话，Play 政策风险 + 8.x 超时风险） | `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE=agent_status_tracking`；`startForeground` 用 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`；补 `onTimeout(startId, fgsType)` → `stopSelf()` |

### 1.4 启动、服务与 UI

| # | 位置 | 风险 | 缺陷与根因 | 修复结果 |
|---|---|---|---|---|
| 23 | `SessionActivityTracker.presenceKeepAliveEnabled` | P2 | 流式热路径主线程同步 SharedPreferences 读 | `AsyncPrefs.cached()` 单例（§2-B9 收敛） |
| 24 | `LaunchPhaseProbes` | P1 | wedged filesystem 上 `awaitLaunchScan`/`awaitDbVersionProbe` 无超时 → 启动 ANR | 5s `withTimeoutOrNull` 超时走保守默认 + Log.w 区分"超时放弃/已定论" |
| 25 | `MinisApp` 扩展子系统启动 | P1 | 任一子系统 init 抛异常直接崩溃进程 | `runCatching` degraded 继续 + `subsystemInitFailure`；第二轮补真实 UI 消费端（DegradedStartupBanner）与 io scope CoroutineExceptionHandler（§2-B6） |
| 26 | `ApprovalGate` | P2 | resolve 后不清理空 session flow（慢泄漏） | 删空 flow；`cleanupAll` 覆盖 `pendingBySession`（第二轮 compute 原子化，§2-B12） |
| 27 | `SessionListScreen` BrowserTabPool | P2 | 导航离开不 dispose，浏览器 tab 泄漏 | `DisposableEffect(Unit){ onDispose { dispose() } }` |
| 28 | `MediaPlayerManager` | P2 | sessions map 非 Concurrent；completion 终态不释放 codec | ConcurrentHashMap（第二轮统一条件删除与 onError 释放，§2-C1/C2） |
| 29 | `ReadAloudPlayer.prepare` | P2 | `prepare()` 主线程解码阻塞 | `prepareAsync` + prepared listener 内 start/volume/speed |
| 30 | `ToolLoopDetector` global breaker | P1 | global circuit breaker 到阈值只 WARNING 不阻断——30 次相同调用后循环永不终止 | 改真 `Level.CRITICAL`（阻断消息 LOOP BLOCKED）；测试同步 |
| 31 | `McpHttpServer` 饱和拒绝 | P1 | accept loop 里 `return`——一次饱和杀死整个 listener 且状态仍显示 RUNNING | 改 `continue`（`inFlightConnections` 递减 + client.close 保留） |

### 1.5 补充审查四组（并行深度审查后修复的 16 项）

**组A（请求路径）4 项**：

| # | 位置 | 风险 | 缺陷与根因 | 修复结果 |
|---|---|---|---|---|
| A-1 | `ChatViewModelToolTurnExec` / `ShellExt` / `SubAgentExt` | P2 | UI push 的 `toList()` 快照取在 `synchronized(allToolBlocks)` **外**；shell countdown/预览两条写路径完全无锁——与 IO lane 的持锁写竞争（CME/撕裂发布） | 所有 UI push 改为 monitor 内变更并取快照、monitor 外发布；shell 写路径补同一 monitor；块内无挂起点 |
| A-2 | `ChatViewModelSupervisorExt.startSupervisorSession` | P2 | 主线程协程里 `runBlocking { OAuth validAccessToken() }`——网络刷新阻塞 UI | provider 解析包进 `withContext(Dispatchers.IO)`（§2-A3 收敛注册竞态） |
| A-3 | `ChatViewModelRequestProjection` micro-prune | P2 | micro-prune 使用**老化前** pressure——aging 已把请求压到触发线以下时仍对刻意保留的结果做有损截断 | aging 改动列表后按同一配方重新计量；未改动走零开销快路径；补双重 pin 测试 |
| A-4 | `FirstEventWatchdog` | P2 | upstream catch 在 `timedOutPhase` 已置位时吞掉一切异常——真实的 401/429/5xx 恰在 deadline 到期时到达即被吞 | `capturedUpstreamError` + 白名单裁决（§2-A1 反转为类型白名单，彻底去掉文本嗅探） |

**组B（备份/记忆）5 项**：

| # | 位置 | 风险 | 缺陷与根因 | 修复结果 |
|---|---|---|---|---|
| B-1 | `BackupBlobStore` skipped 统计 | P2 | 三类不同跳过原因混进同一计数；结果卡按 UI 设置贴单一标签；`SkippedEntry.reason` 从未填充 | `SkipReason` 枚举（SIZE_CAP/NO_FILES/UNREADABLE/CREDENTIALS）端到端贯通：产生点→RunResult 计数→结果卡分行渲染→历史记录；17 语种文案 |
| B-2 | `ModelEntryDetailScreen` harness 保存 | P2 | `HarnessConfigStore.save` 失败仅打日志，UI 弹回无提示（注释与实现相悖） | 失败置 `harnessSaveError` 留在本页内联显示，可重试；修正失实注释 |
| B-3 | `BackupZip.extract` | P1 | 一个非法/损坏 entry 抛异常中断整包导入（与自家"前向容损"文档矛盾） | `ExtractionReport(written, unsafePaths, corrupt)`：§5.5 路径守卫不放宽（绝不写出目标外）但具名跳过；坏 CRC entry 删除半截文件、具名报告、继续扫描；完整性哈希仍 fail-closed |
| B-4 | `RcloneChunkedUpload.downloadWholeFile` | P1 | `while(true)` 轮询无总截止；`job/status` 永久失败时连 Cancel 都退不出 | `JOB_MAX_DURATION_MS`(6h) deadline + `job/stop` + 取消 30s 宽限强制退出清理（§2-B1/B2 扩展到 upload 与 legacy 分块） |
| B-5 | `PhoneBackupFolderStore.copyPackage` | P1 | 先删旧包再拷贝——流打开失败/中断/被杀时 SAF 文件夹里唯一的备份副本消失 | 先暂存后替换（§2-B3/B4/B5 收敛 rename 复检、先拷后删、清扫边界） |

**组C（沙箱/offload）3 项**：

| # | 位置 | 风险 | 缺陷与根因 | 修复结果 |
|---|---|---|---|---|
| C-1 | `JobSpawner` watcher | P2 | watcher 阻塞在 `Thread.sleep` 轮询，每个 job 钉死一个共享 IO 线程（8 job×多会话可饿死全 app 文件 IO） | `SpawnedProcess.awaitSuspend()`（默认桥接，JobSpawner 覆盖为 `delay` 轮询）——检查项/节奏/哨兵语义逐字不变；测试 pin 挂起通道 |
| C-2 | `LocalToolPluginStore` | P1（安全） | `<` 不在拒绝集，`<(touch /tmp/pwn)` 进程替换执行任意命令 | 拒绝集加 `<`；测试覆盖拒绝与 allowUnsafe 逃逸通道 |
| C-3 | `SandboxHttpProxy` | P1/P2 | IPv6 字面量全部解析失败；header ≥0x80 字节损坏 | 见 §1.2 #14/#15 |

**组D（MCP/DebugServer）4 项**：

| # | 位置 | 风险 | 缺陷与根因 | 修复结果 |
|---|---|---|---|---|
| D-1 | `McpServerTools.FileTools.fileRead` | P1 | 整读后截断——300MB 日志在低端设备 OOM（agent 侧有 50MB 闸、MCP 侧没有） | `readTextBounded` 流式有界读（内存=输出上限）+ 50MB 闸返回可恢复错误并指路 shell_exec；schema 同步（§2-D2 补代理对边界） |
| D-2 | `DebugServer` | P1 | `ByteArray(contentLength)` 无上限（声明 1GB 即 OOM）；header 行无界 | `MAX_BODY_BYTES`(4MB→413)/`MAX_HEADER_BYTES`(64KiB)+`MAX_HEADER_COUNT`(100→431) 对齐 McpHttpServer；读行内部预算防单条超长行；auth 闸提前到 token lazy 前（§2-D3 修 header 计数差 2） |
| D-3 | `ExecutionCoordinator` 语义 | P2 | 兜底把宿主故障伪造成 124；MCP 面丢弃 `timedOut` | 见 §1.2 #17 |
| D-4 | `McpApprovalCore` taskId | P1（安全） | `..` 通过校验 → 路径穿越写/删 shared 根 | 见 §1.2 #16 |

### 1.6 第一轮证伪的误报（6 项，不改码）

| 候选 | 证伪要点 |
|---|---|
| 加密旧包 `.enc` mtime 死代码 | 全仓不存在该逻辑；`.enc`+mtime 的三处代码均有产生路径与用途 |
| 空轮持久化/换成员路由顺序 | 空轮 parts 为空不落库；三个 persist 调用点互斥；NEXT_MEMBER 路由前先摘除空轮，DB 与内存一致 |
| NativeOffload slow drip 无总截止 | 全仓无 drip 标识符；所有逐块读取路径均有 withTimeout/deadline/armDeadline 总截止 |
| Job input/signal 语义歧义 | 参数集不相交（text→stdin fifo；signal→进程组信号），错误传参得明确报错，无交叉解释 |
| CronJob 会话隔离错位 | 绑定链完整（owner chat 归一→AppendToSession→resolveSessionId 校验→HeadlessChatRunner 同会话） |
| SandboxHttpProxy body 转发/准入 | body 由 tunnel 顺序转发无重写；代理即防火墙执行臂，无"其他网络调用准入表"可对齐 |

---

## 2. 第二轮对抗复审（24 项发现，全部修复）

复审对象 = 第一轮全部修复的 diff，逐 hunk 对抗审查。

### 2.1 复审A（请求路径/watchdog）5 项

| # | 位置 | 风险 | 缺陷 | 修复 |
|---|---|---|---|---|
| A1 | `FirstEventWatchdog.realProviderVerdict` | P2 | 白名单靠 cause.message 文本嗅探识别 cancel 级联，但 teardown 还产生 `NetworkError(cause=SocketException("Socket closed"))`——被误当 provider 真实裁决顶替超时，丢失 `stalledAfterFirstEvent` → 整轮重生成（测试盲区：测了裸 SocketException，没测已映射形态） | **反转白名单**：仅答案型（RateLimited/InvalidApiKey/ProviderError/DecodingError/ContextLengthExceeded/ContentFiltered）可顶替超时；TransientError/NetworkError/Timeout/Unknown/Cancelled 一律让位；删除全部消息文本匹配；测试按白名单语义重写（6 答案型 + 包装链 + 8 让位形态） |
| A2 | `WorkingMemoryLedger` | P2 | append 已改 per-ledger 锁但 ackDelivered/renderUndelivered/undoDelivery/hasUndelivered 仍是 store 级锁——append 与 ack 的 snapshot→persistAll 全量覆写之间互斥丢失，新记录被旧快照抹掉；两个 persistAll 写同一 tmp 交错损坏 | store 方法去 `@Synchronized`，复合操作统一 `synchronized(ledgerFor(sessionId))` 同一 per-session 锁；persistAll tmp 加 nanoTime 唯一后缀；并发压测测试（4 append + 1 ack 线程零丢失） |
| A3 | `ChatViewModelSupervisorExt` | P2 | `isLive` 检查与 `register`（覆盖写）之间的窗口被新增的 IO OAuth 解析放大到秒级——并发 start 第二个覆盖第一个，孤儿 loop 继续跑 | `register` 改 `putIfAbsent` 语义，raced loser 取消新 job 并走与 `!job.start()` 相同清理；UI reset/update 移到胜出之后；测试重写 |
| A4 | `ChatViewModelToolTurnExec` preflight | P2 | 三处拒绝路径 monitor 外写 `allToolBlocks` + 传活列表（今天不可达，但与本文件新注释矛盾） | 三处包进 monitor、推 UI 改快照，注释与实现一致 |
| A5 | `ChatRepositorySessionQueries` / `restoreArchivedVersions` | 知情项 | 提取中捆绑了两处有意行为变更，需防后续轮次误判回归 | 两处补显式 INTENTIONAL BEHAVIOR CHANGE 注释及理由 |

### 2.2 复审B（备份/记忆/启动）12 项

| # | 位置 | 风险 | 缺陷 | 修复 |
|---|---|---|---|---|
| B1 | `RcloneChunkedUpload` 轮询 | P2 | deadline 判定排在 `finished` 处理前——恰好跨 6h 完成的传输被误删误报 | deadline 改 `!finished && now > deadline`；文案不再断言服务器死亡；抽 `runJobWithDeadline` 共享 |
| B2 | 同文件 upload()/legacy 下载 | P2 | 阻塞 `operations/copyfile` 无墙钟上限——桥挂死整个投递无限卡死（deadline 修复不彻底） | upload 与 legacy 分块下载全部改 `_async`+jobid 轮询，共享总 deadline；legacy 循环补 cancelFlag 检查；取消语义保持 |
| B3 | `PhoneBackupFolderStore` 二次 rename | P2 | 只看布尔不复检名字——SAF provider 篡改 displayName 时旧包已删、新包落在过滤器不可见的名字上（数据丢失） | `promote()` 两次 rename 后都复检 `staged.name == displayName`；流式兜底 createFile 后校验 final 名；"验证过的同名替代品完整存在"前绝不触碰 existing |
| B4 | 同文件无 rename 兜底 | P2 | 兜底路径仍是先删后拷——中途失败丢包 | 先拷到临时名并校验字节数，成功后才删旧包；失败保留 temp 供恢复 |
| B5 | 同文件 `.partial` 清扫 | P2 | 无差别清扫删并发备份的暂存；provider 篡改暂存名时孤儿永不清理 | 暂存名 `minis-staged-<ts>-<uuid>.<name>.partial`（前缀+唯一性）；清扫只删同时匹配前缀后缀且 >1h 的文件；mtime 不可读视为在用 |
| B6 | `MinisApp` degraded 状态 | P2 | `subsystemInitFailure` 唯一读取点在 `!subsystemsInitialized` 分支内——失败时永远不可见；io scope 无 CoroutineExceptionHandler | `subsystemInitFailure` 改 StateFlow + 新建 `DegradedStartupBanner`（挂 NetworkOverlayHost，置顶悬浮条含 cause 与关闭）；io scope 挂 CoroutineExceptionHandler；`subsystemsInitialized=false` 方案被否（会把降级可用的应用锁死在故障屏） |
| B7 | `SecurityGateImpl` find | P2 | mutating 集合漏 `-fls`（等价 -fprint 写任意文件） | 补 `"-fls"` |
| B8 | `MemoryRepository.getMemory` | P2 | standing 排除的新语义无测试锁定 | 新建 `MemoryScopeFilterTest`：persona 不出现在 daily / 出现在 all；未过期 TTL 文件在 daily；过期 TTL 治理先行剔除 |
| B9 | `AsyncPrefs.create` 调用点 | P2 | 每次调用新建实例+后台线程，overrides 未加载完前 `getBoolean` 返回默认值（OFF 短暂读成 ON） | companion `cached(context, name)` 按文件名缓存单例；SessionActivityTracker 迁移；Robolectric 测试钉住收敛行为 |
| B10 | `LaunchPhaseProbes` | P3 | 超时分支无日志，无法归因 | 两个 probe 的超时分支补 Log.w（dbVersion 用 timedOut 标志精确区分） |
| B11 | `BackupZip.extract` | P3 | CRC 在 closeEntry 才失败的条目已计入 written 并回调 onProgress | written/onProgress/mtime 回填全部移到 closeEntry 成功后；测试断言 `written==1` |
| B12 | `ApprovalGate` | P3 | resolve 查空后 remove 与并发 requestApproval 的窗口——新 flow 被误删 | `compute(sid){ if none null else existing }` 原子"查空即删"；测试 pin 并发可见性 |

### 2.3 复审C+D（沙箱/MCP/服务器）7 项

| # | 位置 | 风险 | 缺陷 | 修复 |
|---|---|---|---|---|
| C1 | `MediaPlayerManager` completion | P2（修复引入） | 监听器只捕获 key 无条件 remove——旧播放结束回调删掉新 `play` 刚放入的会话（无法停止、codec 泄漏） | 捕获自身 session 对象，`ConcurrentHashMap.remove(key, session)` 条件删除；stop() 同构闭环 |
| C2 | `MediaPlayerManager` onError | P2 | 错误路径不释放 codec、session 滞留 map | onError 与 completion 同构：置 STOPPED→条件 remove→release |
| C3 | `AlarmOffloadManager.nextRequestCode` | P2 | prefs 读-改-写非原子，并发调度铸造相同 requestCode（确定性覆盖，恰是旧 hash 方案的病灶） | companion 锁 `@Synchronized` 临界区；回绕逻辑不变（永不产 0） |
| C4 | `SessionsOffloadHandler` admission 错误 | P2 | 新增拒绝路径产出裸 `{"error":...}`，未用既有 `errorEnvelope` 形状——按 `data.error` 分类的调用方无法归类 | 改 `errorEnvelope("search", "TEMP_UNAVAILABLE", ...)`；新增 JVM 测试断言 exit 1 + 完整 envelope |
| D1 | `McpApprovalRpc.executeApproved` | P2 | UI 直连路径无 catch——prefs 损坏含 `taskId=".."` 时点"运行"即 IAE 崩溃进程（RPC 面和 delete 面都补了，UI 面漏了） | `executeApproved` 内 taskRoot 解析 `runCatching`，失败返回 `Outcome.Err(INVALID_ID)`；测试直接构造损坏状态断言不崩溃零执行 |
| D2 | `FileTools.readTextBounded` | P2 | 截断点切在高代理中间——输出孤立 U+D83D，严格 JSON 客户端拒绝整个响应（实测复现） | truncated 且末 char 为高代理时输出减一；测试钉住 emoji 边界 + 对照组 |
| D3 | `DebugServer.MAX_HEADER_COUNT` | P2 | 请求行与终止空行也计入——99 个 header 合法请求被 431（与注释声称的 nginx 100 不符） | 只统计真正 header 行；恰好 100 条放行、101 条 431；KDoc 修正；边界测试 |

### 2.4 修复过程中即时捕获的次生缺陷（2 项）

| # | 捕获方式 | 缺陷 | 修复 |
|---|---|---|---|
| S1 | `BackupMemoryLifecycleTest.bothZipTreesPreserveFileTtlAgeHeadersAndPerPathMtimes` 失败 | 第一轮 #5 的 getMemory daily 修复**过度收窄**：`MemoryDiaryFormat.dateFile.matches` 把 past.md/present.md 这类 TTL 生命周期文件也排除出 daily 搜索 | 改为 standing 集合判定（`isStandingSource`），TTL/自定义记忆文件保留；语义见 §1.1 #5 |
| S2 | `BackupZipTest` corrupt 用例失败 | 测试用 `ZipOutputStream` 声明假 CRC——`closeEntry()` 写入时就校验，构造不出"文件里 CRC 与数据不符"的真实场景 | 改为先正确归档再翻转 STORED 数据字节（真实位翻转/截断传输形态） |

---

## 3. 第三轮全局核验

只读核验员对 97 个跟踪修改文件（.kt 92 个逐行，约 2454 新增行）+ 13 个新增文件全量通读：

- **伪修复扫描：零发现。** 10 处新增 catch 全部核查为语义保留或失败显性化（清理后重抛 / 类型化报告 / 有理由的防御性等待）；无注释掉的代码；无静默降级；测试断言改动逐条对照产品行为变化，无"改测试凑通过"。
- **调试残留：零发现。** 新增行中 `println`/`System.out`/`printStackTrace`/`Log.d`/`TODO`/`FIXME` 全部为 0；新增 `Log.w`/`Log.e` 均为带上下文的生产级日志且有理由注释。
- **新增文件质量：通过。** 8 个生产文件（6 个提取重构 + DegradedStartupBanner + SearchAdmissionUnavailable）文件头均说明存在理由、可见性最小化、TAG 镜像有文档注明、无 import 遗漏。
- 3 个低危化妆问题，其中 2 个当场修复：`ForegroundNotificationBuilders` 双重 KDoc 合并；`MinisApp` SOUL 块缩进债（19 行 +4 空格）。第 3 个为 `.zcode/` 工作流目录提交卫生——提交时按清单显式 add，不纳入提交。

---

## 4. 架构棘轮

`check_architecture_policy.py` 检出 6 个文件超限（分支修复净增行数所致）。全部按"ceiling 永不抬高"原则**提取内聚块降行数**，随后 `--update-baseline` 收紧快照：

| 文件 | 前 → 后 | 提取产物 |
|---|---|---|
| ChatRepository.kt | 1368 → 1208 | `ChatRepositorySessionQueries.kt`（249 行，querySessionsMeta/searchMessages/loadMessagePage） |
| BackupViewModel.kt | 1066 → 998 | `BackupPackageDelivery.kt`（93 行，deliverToRemotes） |
| AgentForegroundService.kt | 1319 → 1111 | `ForegroundNotificationBuilders.kt`（234 行，5 个 surface 通知构建器） |
| SessionListScreen.kt | 3227 → 3058 | `SessionListOnboarding.kt`（208 行，iOS 三步引导） |
| JobRegistry.kt | 813 → 751 | `JobTailRead.kt`（75 行，有界尾部读取） |
| ReadAloudPlayer.kt | 806 → 736 | `ReadAloudAudio.kt`（96 行，AudioManager/音量增强） |

全部为逐字搬移的结构调整（委托签名逐字一致、可见性 private→同包 internal 且引用面核查）；提取后定向测试全绿。最终门禁：**OK（1579 files, cap 800, 55 on baseline, 34 on forbidden-imports baseline）**。

---

## 5. 第二轮复审确证误报（第三轮免重查清单）

复审三组共验证 30+ 个审查要点，以下为确证无问题的关键点（防止后续轮次重复排查）：

- `parseHostPort` 全边界（`[::1]`/裸 IPv6/畸形 authority/绝对 URL/端口范围）实测通过；ISO_8859_1 精确还原字节。
- `awaitSuspend` 生产实现唯一、无意外继承；watcher 取消语义不变。
- JobTailRead/ReadAloudAudio/ForegroundNotificationBuilders/SessionListOnboarding 四个提取文件逐行 verbatim，常量值不变，句柄关闭语义不变。
- `McpHttpServer` continue 路径的 `inFlightConnections` 递减与 client.close 完整。
- 通知 request code 混入的跨动作碰撞数学上不可能（差值 ≡±1 mod 31）；cancel 全部按 notificationId。
- `realProviderVerdict` 的 `CANCELED_STREAM_TEARDOWN_PREFIX` 常量与原消息字节级一致（od 验证）；白名单对 sealed 分类穷举。
- micro-prune 重新计量配方与首次逐字一致；`aged.messages === normalized` 判定可靠（三条无变化路径返回原实例）。
- `ConfigConfirmationGate` timeout/advance/cancel 全部同一 mutex 串行，后到者 no-op，无双 advance。
- `ConfigBridge` 审计脱敏深挖无可达泄漏（scalar secret reader 一律 PermissionDenied → Null；Obj 载荷递归遮蔽）。
- `WritePathGuard` 词法折叠 13 trace 全对，对 `prefix/../sibling` 严格收紧。
- strings.xml：3+4 个新 key 在 values + 全部 16 语种齐备、无占位符漂移；`check_new_untranslated` origin/main→HEAD 干净。
- `SearchAdmissionUnavailable` 全部调用方核对（2 个，均有处理）；`taskRootFor` 全部调用点核对（RPC catch / delete runCatching / UI 直连已补）。

---

## 6. 验证结果

| 验证项 | 结果 |
|---|---|
| `:app:compileReleaseKotlin` | BUILD SUCCESSFUL |
| 定向测试（三轮累计，~60 个测试类） | 全绿（含新增/扩展的 20+ 个测试文件） |
| 完整 `:app:testReleaseUnitTest --rerun`（全量强制重跑） | **837 suites / 7288 tests / 0 failures / 0 errors / 4 skipped**（较基线 832/7240 净增 5 套件 48 测试，全部通过） |
| 架构策略 | OK（1579 files, cap 800） |
| announced tools / CJK ratchet / untranslated / schema drift（24 版重放）/ string refs（1580 files）/ thinking displayName / tool execution contract / tool title+budget contract / instruction_state（14/14） | 全绿 |

### Linux 环境校验说明（如实报告）

本审查在 Windows（win32 10.0.26300）开发机上完成：编译与全量 JVM（Robolectric）测试均在此环境通过。**本机没有 Linux 桌面/服务器运行环境，未执行 Linux 端的真实运行校验**——此为环境限制，非跳过项。Linux 适配维度已通过源码审查覆盖：guest 执行链（PersistentShell/Bash/Guardian）、`minis-sessions-cli` SQL 参数上限、bash 进程替换（`<(...)`）拒绝、SandboxHttpProxy IPv6 字面量、ISO-8859-1 header 字节保真、GNU find `-fls` 等真实 Linux 行为差异均在本轮修复范围内。如需 Linux 端运行校验，请在 Linux 环境按 LINUX.md 构建运行后另行验证。

---

## 7. 提交状态

- 分支：`fix/adversarial-review-405-407`（基于 origin/main @ 0ca33e38）
- 状态：全部修改在工作区**未提交**（97 个跟踪文件修改 + 20 个新增文件 + architecture-policy baseline 收紧）
- 提交卫生：`.zcode/`（工作流产物）不纳入提交
- 按用户既定流程，push/PR 需用户明确指示后执行
