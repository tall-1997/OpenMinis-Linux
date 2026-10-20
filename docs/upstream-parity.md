# 上游对齐进度台账

本文件是本仓库相对上游 `OpenMinis/OpenMinis` 的唯一进度记录。历史上的逐次审计报告已删除，结论与仍在生效的部分收在下面；需要原始细节时从 git 历史取。

- **更新**：2026-10-20，`main` = `3415f87e`
- **上游基准**：`b4c0661d`（= tag `1.14`；`upstream/v1.14` 只多一条文档提交）
- **结论**：12 批中 7 批关闭，3 批进行中，2 批未开始

> 说明：`upstream-beta5` tag 与 `1.17-linux`…`1.36.53-linux` 系列都不是更完整的实现。前者指向同一个 `b4c0661d`，后者是更早、更瘦的 Linux 快照（android 文件 845–1102），不含本台账列出的特性。

## 1. 基准差异（2026-10-19 快照，未重测）

按下述四层方法比对（文件名配对 → 文案键集合差 → 同名文件顶层声明差 → 特征符号放回全仓反查）：

| 指标 | upstream | 本仓库 |
| --- | ---: | ---: |
| 主源码 Kotlin 文件 | 581 | 968 |
| `values/strings.xml` 键 | 2084 | 2538 |
| 上游有、本仓库无同名文件 | 112 | — |
| 同名文件缺上游顶层符号 | — | 28 个文件 / 55 个符号 |
| 上游独有文案键 | 339 | — |
| 本仓库独有文案键（自研功能） | — | 793 |

这组数字是**批次 2–8 开工前**的底数，之后的合并已消化其中一部分（终端、helper、Copilot、备份运行侧仍未动）。要刷新请重跑四层比对，不要拿旧数字当现状。

## 2. 12 批次总表

| 批 | 主题 | 状态 |
| --- | --- | --- |
| P0 | Claude OAuth 端点 + 客户端指纹 | **完成** #91 |
| 1 | Markdown 表格 + 流式解析 | **部分** #82（剩 `MathLineBreaker` + 4 个流式符号） |
| 2 | 看图 + 存储清理 | **完成** #83 |
| 3 | 容器 DNS | **完成** #85 |
| 4 | 聊天页门控件（15 件） | **进行中** 4/15 接线，2 件剔除 |
| 4b | 门控件续（5 件） | **进行中** 4/5 |
| 5 | 计划任务补全 | **完成** 6/6：#92 #93 #94 #99 #100 #102 |
| 6 | 备份运行侧 + 恢复收尾 | **完成** 3/3：#95 #96 #97 #98 |
| 7 | 语音链路 | **完成** 5/5：#103 #104 #105 #107 #108 |
| 8 | 沙箱 / 浏览器稳定性 | **完成** 6 件：#111 #112 #117 |
| 9 | helper / 子代理控制台 | 未开始（最大件，约 4286 行 + 95 键） |
| 10 | Copilot / Codex 接入 | **完成** #118 |
| 11 | 诊断 / 杂项 / 首启引导 | **进行中** #119：首启引导、构建时间、进程退出诊断、设备名、模型排序已落地；`data/session` 4 件依赖批次 9 |
| 12 | 终端模拟器 | 未开始（约 861 行，无上游单测） |
| 自研 | F2 堆叠趋势图、P1 大会话占位、B4-F3 提问缩略、B4-X2 问题类型推断 | 未开始（无上游参考，可穿插） |

## 3. 已完成批次：交接要点

### 批次 1 · Markdown（#82）

已落地：`TableColumnPlanner`（含 iOS 金样本 14 例单测）、流式表格对齐标记 `:---:`/`---:`/`---`、按内容比例分配列宽（「最长不可断词」为硬下限）。

仍缺：`MathLineBreaker`（在关系符处断超宽行内公式）与 `StreamingMarkdownText` 的 `fencedCodeContent` / `safeInlineSplitOffset` / `isSealedBoundary` / `LocalMarkdownBlankTapHandler`。

**两者有依赖关系**：本仓库的 KaTeX 行内公式是布局前定尺寸的 Placeholder，没有换行机制，单独移植 `MathLineBreaker` 会变成无调用的死码。正确顺序是先让 `InlineMathPlaceholder` 支持按关系符断行，再移植断行器，同一 PR。

### 批次 2 · 看图 + 存储（#83）

`ImageExport.kt`（按字节嗅探 MIME，有原始字节就保格式与元数据）、两个看图入口的「原图」动作、`StorageCleaner`（不碰同级目录、目录本身保留）+ 存储页「日志与缓存」行。

### 批次 3 · 容器 DNS（#85）

`ContainerDns` 的 `parse`/`plan`/`customResolvConf`、手写 IPv4/IPv6 校验（不经 `InetAddress`）、`network.dnsMode`/`network.dnsCustomServers` 两个 minis-config 字段、Rootfs 页「容器网络」段。顺带把 `ConfigValue.decode` 变严格（拒绝尾随垃圾）。

### 批次 5 · 计划任务（6 段全关）

| 段 | 内容 | PR |
| --- | --- | --- |
| 1 | 存储锁进程级 + ONCE 投递原子认领 | #92 |
| 2 | `ScheduledTriggerKind` 统一 + 旧 INTERVAL 行反序列化迁移 | #93 |
| 3 | 相对触发器在编辑器如实显示 + `ON_COMPLETION` 驱动 | #94 |
| 4 | 投递目标缺省 follow-up + 交付说明 | #99 |
| 5 | 聊天内任务卡 + 任务详情页（62 键 ×17） | #100 |
| 6 | 预置工具调用（全链路 `prefill`） | #102 |

关键教训留在代码里的：`mergeBookkeeping` 保证「只改名字」不会把「每 10m ×6」降级成每日日历任务；触发器迁移放在唯一读取点 `ScheduledTask.fromJson`，由存量键推导因而幂等。

### 批次 6 · 备份 / 恢复（3/3）

#95 包名对齐 iOS + 恢复报告按严重程度分区 / #96 `RestoreEta`（滑窗速率，不按全程平均）/ #97 `BackupRunController` 进程级单例 + `BackupForegroundService` 保活 / #98 恢复可中止 + 取消传播到导入器每个阶段。

### 批次 7 · 语音（5/5）

#103 ASR 重试、VAD 采集窗口、麦克风安全 / #104 `TtsLocaleResolver`（此前除中日文外一律用美音）/ #105 屏幕上下文 + 混语术语还原（「下一个 Issue」不再被修成「下一个遗穴」）/ #107 语音模态推断 + OpenRouter 双端点 + Zhipu glm-tts 端点修正 / #108 `VoiceSendGate`（录音未转写完不许发，失败进重试队列）。

### P0 · Claude OAuth（#91）

token 端点迁到 `claude.ai/v1/oauth/token`，并把聊天路径与 auth 路径收敛到同一份 `ClaudeCliMimicryHeaders`（11 个头）。边界保持：token 请求刻意不发 `anthropic-beta`。

## 4. 进行中

### 批次 4 / 4b · 聊天页门控件

**已接线 4 件**：`FallbackPulseGate`、`SheetScrollGuard`、`SendReturnModeMenu`（#86）、`VoiceSendGate`（#108）。

**已剔除 2 件**（本仓库已有更严格实现或该行为已被移除）：`EvictionGuard`（`ChatViewModel.canEvictFromMemory()` 覆盖更全）、`SettleAfterInteractionGate`（滚动策略已明确移除 settle 重钉）。

**4b 4/5**：`OrphanedRunningToolGate`（#87）、`BubbleRowLocator` + `EmptyTurnRecovery` + `SelectionDocumentOrder`（#89），其中 `SelectionDocumentOrder` 的接线在 #90。

**唯一剩余**：`ContextUsageHint` 的 UI 接线。文件 `ui/chat/ContextUsageHint.kt` 已就位但无调用点；前置是批次 11 的 `DecorativeAnimation`，以及把 `data.model.ContextUsage`（带 `ratio`/`known`）适配到门控件自带的 `(usedTokens, windowTokens)` 形状。

**未做 9 件**中的 3 件各有硬前置：`ImageInputPreflight`（需 `LLMError.httpStatus` 与 `effectiveInputModalities`）、`QueuedPromptBatching`（需 `QueuedPrompt.origin`/`isScheduledFire`）、`TitleCandidates`（需改标题管线签名）。

### 批次 10 · Copilot / Codex（#118）

**已落地**：`CopilotDeviceFlow`（纯协议）+ `CopilotOAuthManager`（GitHub 账号令牌 → 25 分钟会话令牌，懒刷新、单飞）、`CopilotDisclaimerDialog` + `CopilotFeatureFlag`（同意门，取消不发请求）、`ProviderType.githubCopilot` 全链路（OAuthManager / ProviderFactory / 模型发现 / 备份导入导出 / 调试接口 / 颜色 / 17 locale）。

**关键行为**：Copilot 的 `X-Initiator` / `X-Request-Id` / 视觉标记由请求体逐次推导（构造期写死会把 agent 流量误标成人类）；`/models` 的上下文窗口、输出上限、推理档位、视觉能力随模型透传。

**Codex 动态发现**：`GET /backend-api/codex/models` 优先，失败回退内置清单；显式刷新 401/403 抛 `CodexDiscoveryAuthException`，界面提示重新登录，不把旧列表伪装成刷新成功。

**OAuth 前台门**：回调到达时若应用在后台，等回到前台再换 token（后台网络被系统拒绝，表现为 UnknownHostException）；Claude 交换 3 次重试；回调页改为「Return to Minis to finish signing in.」。

**已知边界**：非中文 locale 的免责声明暂用英文原文（键位齐全，CI 的 l10n 门通过）；`OAuthProgressLine` 那套「等待授权/正在完成」进度界面未移植，上游两条 UI 断言已裁掉并注明原因。

### 批次 8 · 沙箱 / 浏览器（6 件）

- #111 `GlobalShellThrottle`：跨会话 child-process 总预算（24），按 `/proc` 后代计数 FIFO 排队；`pid<=0` 直接返回 0，否则在 stub 环境下会数到全机所有进程。
- #112 浏览器韧性：`BrowserActionGuard` 把「等锁 60s」与「动作 75s」拆开，长取消判 wedged，下次 `acquireTab` 前清扫并重建；7 处 WebView 宿主全接 `onRenderProcessGone`（只记日志不 destroy，各家 cleanup 走自己的入口）。
- #117 批次 8 收官三件：
  - `FreshProcessShell`：独立 proot 进程执行、seccomp 失败自动 retry（`PROOT_NO_SECCOMP=1`）、Stop 按钮跨路径 kill（proot + traced guest 后代）
  - `BrowserTabPoolRegistry`：全局 tab 仲裁（`GLOBAL_TAB_CAP` 上限）、内存压力驱逐（`onTrimMemory` → `evictAllIdleForMemoryPressure`）
  - `HeadlessRenderHost`：GPU 截图（`PixelCopy.request`）+ Scroll Offset 修复（CSS scroll 双源防 double-count）

## 5. 未开始

按前置依赖排序，前置越少的越先做：

```
批次 11 诊断/引导        ← 进行中：data/session 并入批次 9
批次 9 helper 控制台     ← 最大件；解锁 4b 的 EmptyTurnRecovery 后半 + 批次 11 的 session 4 件
批次 12 终端            ← 无上游单测，需实机验证 htop 类交互程序
批次 1 收尾            ← MathLineBreaker + InlineMathPlaceholder 断行
自研 4 项               ← 穿插做
```

## 6. 设计决策记录（不要重议）

这些是当时权衡后定下的口径，改之前先确认前提是否还成立。

1. **Offload 权限弹窗超时保持 120s，不跟上游的 300s。** 本仓库 120s 口径已与各 caller 的 timeout、20s handler watchdog 联动；等待人类的窗口长于看门狗周期只会延长一个卡死的 handler 占着 slot 的时间。见 `tools/ToolConcurrency.kt`。
2. **`SEC-2` SandboxHttpProxy peer 认证 / 目标 allowlist 暂缓**（用户决定）。开启 `log`/`cut` 时同设备 App 仍可把它当本地代理。
3. **`F5` 五个新语言排到最后**：纯翻译、量最大（约 2500 条 × 5），收益取决于目标市场。`values-hr`（克罗地亚语）是上游 1.14 就有、本仓库唯一缺的那个语言，可单独顺手补。
4. **子代理作业注册表（`agent/jobs`）属批次 9**，`EmptyTurnRecovery` 等的「等后台子任务完成」那半在它落地前只有消费者没有生产者。

## 7. 已知技术债（2026-10-20 逐条核过现状）

| 项 | 现状 | 位置 |
| --- | --- | --- |
| `ChatScreen` 未做物理拆分 | 7497 行，消息区/输入区已有独立重组 scope，但没搬成 `ChatMessagesPane`/`ChatComposerPane` | `ui/chat/ChatScreen.kt` |
| 消息搜索无 FTS | `LIKE` + `preview` 列 + `LIMIT 200`；中文子串使 `unicode61` 不可用，要自定义分词/trigram 才能上 FTS | `data/db/ChatSearchDao.kt` |
| MCP server 的 CORS | 仍发 `Access-Control-Allow-Origin: *` | `mcp/server/McpServerCore.kt` |
| 配置确认门窗口 | 20s watchdog 会取消等待并清弹窗，但确认门自身 120s 窗口未缩短 | `config/` 确认流 |
| MCP 文件传输峰值 | 已去掉整文件 `byte[]`，base64 最终字符串仍在内存；彻底流式化要改 HTTP 响应写出 | `mcp/` 文件传输 |
| `DebugServer` 的 CORS | 同样发 `*`（调试口，风险面比 MCP 小） | `debug/DebugServer.kt` |

`BrowserUseManager` 的拦截文件响应已经改成回显请求 origin，不在上表。

**性能优化的历史分析**（五方向：冷启动 / 后台留存 / 流式 / 非流式 / 线程模型）已随文档整理删除，其任务清单未逐项复核，引用前需重新取数：`git show <整理前提交>:docs/performance-optimization-roadmap.md`。

## 8. 每批的执行模板（已验证）

1. **取源**：`git show b4c0661d:<上游路径>`；`data/model/*` 按本仓库模块布局归位。
2. **依赖适配**：先编译，逐个消未解析引用（各批实测 0–2 处）。
3. **接真实入口**：不接受「文件加了没人调用」；接线后 grep 确认调用点。
4. **文案**：新键同步 17 个 locale；英文 `'` 转义成 `\'`。
5. **测试**：一并移植上游单测；断言依赖上游私有函数的裁掉并写明原因。
6. **门禁**：`compileReleaseKotlin` → `testReleaseUnitTest` → `check_architecture_policy.py` → `check_cjk_literals.py`。本地 PRoot 编译可能 OOM（`Java heap space`，与代码无关），这种情况下把验证交给 CI。
7. **交付**：分支 → PR → CI 绿 → squash 合并 → `git diff --shortstat` 验增量非零。PR 必须提到 `tall-1997/OpenMinis-Linux`（`gh` 默认仓库是上游）。

### 架构约束（会反复撞）

- **卡在上限的文件**：`ChatScreen` 7497、`ChatViewModel` 6291、`ChatModelPickerSheet` 1106、`MinisTextKitSelection` 1205、`ProviderRepository` 2724、`RootfsManager` 1828、`ConfigBuiltins` 1207。改动要么等量置换，要么先拆。
- **不受约束的位置**：`ui/components/*` 新件、`ui/chat/*` 新门控件、`config/fields/*` 新字段。
- **基线只降不升**：改完被 baseline 跟踪的文件必须重跑 `--update-baseline` 再提交，且要在最后一次编辑之后跑（本地检查通过 ≠ 提交版本通过）。
- **结构差异**：上游把决策写成「私有函数 + 源码扫描断言」，本仓库常内联在调用点。移植时保留行为、改写断言，不要照搬函数名。

### 移植时最常踩的三个坑

1. **守卫锚点会随抽取消失**：测试用 `substringBefore("private fun xxx(")` 切 body，函数被移到别的文件后锚点不存在，`substringBefore` 返回整文件尾部，断言假过。移动任何函数前先 grep 测试里以它为锚的字符串。
2. **跨行链式调用让 grep 守卫失效**：`contains("A.b(")` 不命中 `A\n.b(...)`。源码守卫只钉稳定符号名。
3. **抽取会把 `private` 带出类**：陪伴对象的 `private` 成员搬成顶层后，类外调用点全部编译错。目标文件外还有调用点就一律 `internal`。

## 9. 文档整理说明（2026-10-20）

删除的一次性文档（结论已在上面各节，原始内容见 git 历史）：

| 文件 | 删除理由 |
| --- | --- |
| `CODE-AUDIT-2026-10-17-FINAL.md`（755 行） | 一次性审计清单，P0–P4 已全部结案 |
| `CODE-AUDIT-2026-10-17-VERIFICATION.md`（265 行） | 对上一份的复核记录 |
| `AUDIT-STATUS-2026-10-17.md` | 修复进度索引，结论收进 §7 |
| `P0..P4-FIXES-2026-10-17.md`（5 份） | 逐阶段落地记录，工作已合并 |
| `REVIEW-FOLLOWUP-2026-10-17.md` | 追加核查记录 |
| `TEST-VERIFICATION-2026-10-17.md` | 一次性回归记录（3361 tests / 0 failed） |
| `FIX-BROWSER-UPLOAD-FILE-SAVE-2026-10-17.md` | 单点修复记录 |
| `upstream-parity-audit.md`（436 行） | 缺口清单基于 `5ee238b8`，本台账取代 |
| `upstream-beta5-triage.md`（269 行） | beta.5 分诊已执行完；仍生效的设计口径收进 §6 |
| `performance-optimization-roadmap.md`（479 行） | 任务清单未复核且至少一项已完成未勾选，见 §7 末 |
