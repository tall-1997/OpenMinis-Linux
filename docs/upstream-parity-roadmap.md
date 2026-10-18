# 上游对齐实施路线图（清单 / 原因 / 分步）

配套文档：[upstream-parity-audit.md](upstream-parity-audit.md)（差异证据）、[upstream-beta5-triage.md](upstream-beta5-triage.md)（逐条点名台账）。

对比基线：`origin/main` vs `upstream/main` = `b4c0661d`（tag `1.14`）。

## 0. 排序原则

1. **上游在修 bug 的项插到最前**——它不是新功能，是我们正在出错的路径。
2. **小、独立、能单 PR 验证的优先**——先清掉「一个文件 + 一个接入点」的项，避免大件拖着不动。
3. **依赖前置**：已有运行时（计划任务、子代理、备份、语音）先补 UI 与配置面，而不是重造运行时。
4. **大件后置但占位**：helper 控制台（4286 行）与终端模拟器（861 行 + 自研测试）放后，避免它们阻塞前面的可见收益。
5. **无上游参考的 4 项**单独穿插，不混进移植批。

## 1. 批次总表

| 批 | 内容 | 为什么（证据） | 新文件 / 行数 | 上游可移植测试 | 新增文案键 |
| --- | --- | --- | --- | --- | --- |
| **P0** | Claude OAuth 端点与客户端指纹 | 我们用 `console.anthropic.com/v1/oauth/token`（旧域名），上游 `ClaudeCliMimicryHeaders` 记录了这就是登录/静默刷新被 Cloudflare 打回 403 HTML 的原因（上游 #360）；我们的聊天路径 `AnthropicProvider:1086-1095` 已发 CLI 指纹，**只有 auth 路径没发** | 1 / ~60 | 0 | 0 |
| **1 (A)** | Markdown 表格对齐 + 流式解析补齐 | 上游 `StreamingMarkdownText.kt` 4570 行 / 我们 1483 行，缺 `ParsedTable`、`MdTableAlign`、`parseTableAlignments`、`fencedCodeContent`、`safeInlineSplitOffset`、`isSealedBoundary` 等 10 个顶层符号；即台账 B4-X6 | 2 / 277（另需合并 4570 行的共享文件） | 2 | 0 |
| **2 (B)** | 全屏看图存相册/看原图 + 存储清理 | `FullscreenImageViewer` 缺 `saveToGallery`/`saveImageToGallery`/`loadOriginalImage`/`OriginalImage`；`StorageManagementScreen` 上游 772 行 / 我们 401 行，缺 `LogsAndCachesCleaner`、`aggregateSessionStorage`，**没有「清理日志与缓存」入口** | 1 / 657（另合并 1127 行） | 0 | 10 |
| **3 (F)** | 容器 DNS 设置 | 只有 `RootfsManager` 的底层写入，没有自动/自定义 DNS 的设置面与配置字段；改动面最小 | 3 / 448 | 0 | 14 |
| **4 (I)** | 聊天页门控件批量移植 | 15 个互相独立的小件，合计 2227 行、10 个上游单测；含台账 B4-X1（`SheetScrollGuard`）、P2 的一部分（`FallbackPulseGate`），以及与我们 X12 同域但不冲突的 `SettleAfterInteractionGate` | 15 / 2227 | 10 | 5 |
| **5 (C)** | 计划任务补全（详情/触发/投递/卡片） | 文案成组缺口 65 键；缺 `ScheduledTriggerKind`、完成触发、投递目标、详情页与聊天内卡片；承接我们已完成的 CLI 与编辑页（X11） | 7 / 1846 | 3 | 65 |
| **6 (E)** | 备份运行侧 + 恢复收尾 | 缺 `BackupRunController`、`BackupForegroundService`、`BackupPackageName`、`BackupRecordMapper`、`BackupSubAgents`、`RestoreEta`、`RestoreReportIssues`；文案 12 键；承接 X1/F4 的备份线 | 7 / 1431 | 5 | 12 |
| **7 (H)** | 语音链路（ASR 重试/VAD/音色/智谱） | 缺 9 个文件、8 个上游单测，0 文案；与台账 X7（语音纠正回退链）同域，可并做 | 9 / 950 | 8 | 0 |
| **8 (J)** | 沙箱与浏览器稳定性 | `GlobalShellThrottle` 是**稳定性缺口**：我们的 `shell_execute` 走 `executeShellCommand` 直连沙箱，只有会话级（5）与子代理级（8）上限，**没有跨会话 child-process 总预算**；另有 `FreshProcessShell`、`ForegroundCommandGroup`、`BrowserActionGuard`、`BrowserTabPoolRegistry`、`HeadlessRenderHost` | 6 / 1769 | 4 | 0 |
| **9 (D)** | helper / 子代理控制台 | 最大件：文案缺口 95 键（`agent_detail_*` 24、`sub_agent_*` 20、`helper_*` 14、`agent_error_*` 8、`agent_control_*` 7、`agent_settings_*` 5、`agent_strategy_*` 4、`agent_tools_*` 4、`agent_callback_*` 3、`agent_status_*` 3）；我们子代理能跑但看不见、不能配 | 8 / 4286 | 2 | 95 |
| **10 (G)** | Copilot / Codex 接入 | 缺 6 个文件含设备码流程与 `CodexModelsApi`；上游自带 `CopilotDisclaimerDialog`（10 文案）说明其合规姿态 | 6 / 1651 | 3 | 10 |
| **11 (L)** | 诊断 / 杂项 / 首启引导 | `ExitInfoCollector`（崩溃）、`ContextOverflowGuard`、`PayloadSizeAudit`、`DeviceIdentity`、`OnboardingScreen`、`ModelSearch`、`HardwareKeyboard`、`DecorativeAnimation`、`ReopenOnResume`、`ModelEntryRanking`、`ModelReconciler` | 11 / 1541 | 8 | 10 |
| **12 (K)** | 终端模拟器 | 上游 `ui/terminal/AnsiParser` + `canvas` + `emulator`；**上游无单测**，需要自研测试与实机验证 | 3 / 861 | 0 | 0 |
| **自研** | F2 堆叠趋势图、P1 大会话占位、B4-F3 提问缩略预览、B4-X2 问题类型推断 | 四个方向在上游 Android 树里都查无实现（双向 grep 均无），必须自研 | — | — | — |

## 2. 统一分步模板（每批都走这 7 步）

1. **取源**：`git show b4c0661d:<上游路径> > <我们的路径>`；若上游文件本属 `data/model/*` 之类，按我们的模块布局归位（`core/model` / `provider/api`）。
2. **依赖适配**：先编译，逐个消掉未解析引用。各批「未解析依赖包」实测为 0–2 个（A/B/C/D/F/K 各 1–2，其余 0），所以适配量小。
3. **接入口**：接到真实入口（每批的接入点见 §3），不接受「文件已加但没人调用」——批次 2 的接线审查已经证明这类空接必须防。
4. **文案**：新增键写进 `values/strings.xml`，并同步 **17 个 locale**（`pr-check` 的 l10n 门禁会拦）。
5. **测试**：连同上游单测一起移植（上游共 423 个单测文件，其中 **289 个我们还没有**，是现成的测试资产）。
6. **门禁**：`:app:compileReleaseKotlin`、`:app:testReleaseUnitTest` 全量、`check_architecture_policy.py`（新文件 ≤800 行；baselined 文件不得增长——需要时按批拆新文件或抽公共件）、`check_cjk_literals.py`。
7. **交付**：分支 → PR → CI 绿 → squash 合并 → 验证增量非零；把该批的实机验收项单独列出（下面每批的「验收」列）。

## 3. 每批的接入点与验收

| 批 | 接入点（必须真的接上） | 验收（实机/单测） |
| --- | --- | --- |
| P0 | `auth/ClaudeOAuthManager.kt:117-118` 的 `tokenURL` 与请求构造；新增 `ClaudeCliMimicryHeaders` 并应用到 token exchange / refresh 两个请求 | 单测覆盖「auth 路径带上与聊天路径一致的指纹、且不含 `anthropic-beta`」；实机：Claude 订阅登录 + 静默刷新不再 403 |
| 1 (A) | `StreamingMarkdownText.kt` 的块解析入口；`MarkdownParser` 的表格分支 | 单测：表格对齐/列宽/嵌套表格（移植上游 2 个测试）；实机：流式输出中表格不再错列 |
| 2 (B) | `FullscreenImageViewer` 的菜单（新增「保存到相册」「查看原图」）；`StorageManagementScreen` 新增清理入口 | 单测：`cleanerLogsAndCaches` 的计算纯函数；实机：API 29+ 存图落到相册、清理后有回收量 |
| 3 (F) | `RootfsManager` 的 DNS 写入改为读 `ContainerDns` 配置；设置页新增网络段 | 单测：模式解析（auto/custom/system）+ 非法值拒绝；实机：改 DNS 后 guest 内解析生效 |
| 4 (I) | `ChatScreen` / `ChatModelPickerSheet` / 输入区 / 会话列表各自的调用点（逐件接） | 每件带上游单测（10 个）；实机：B4-X1 滚边不再回弹、P2 不再重复脉冲 |
| 5 (C) | `ScheduledTask` 增加 `triggerKind`；`ScheduledTaskManager` 落完成触发；聊天内卡片接 `ScheduledTaskCard` | 单测：触发判定与投递目标解析；实机：任务完成后按配置投递、聊天里能看到卡片与详情 |
| 6 (E) | 备份运行起点接 `BackupRunController`；前台服务注册；恢复流程接 `RestoreEta` | 单测：包名生成/记录映射；实机：备份期间有进度通知、恢复可中止并出报告 |
| 7 (H) | ASR 管线接 `VoiceAsrRetry`/`SkippableAsrAttempts`；VAD 接采集窗口；音色接 `VoiceRole` | 上游 8 个单测；实机：弱网 ASR 失败可重试/丢弃、长按结束采集行为正确 |
| 8 (J) | `executeShellCommand` 入口接 `GlobalShellThrottle`（FIFO 排队，不拒绝）；浏览器接 ActionGuard/TabPoolRegistry | 上游 4 个单测；实机：并发 20 条 shell 不把设备拖死、浏览器动作被拦时有明确回执 |
| 9 (D) | `SubAgentActivityTracker`/`SubAgentEventBus` 接作业注册表；设置页接 `SubAgentsScreen`/`AgentToolsSettingsScreen`；聊天页接 `HelperUi` | 上游 2 个单测 + 自研：作业状态机；实机：子代理列表、单条详情、控制（停止/续跑/接管）可用 |
| 10 (G) | `OAuthManager` 家族接 Copilot；`OpenAIModelsApi` 家族接 `CodexModelsApi`；免责声明弹窗在首次使用前弹 | 上游 3 个单测；实机：Copilot 设备码登录、Codex 模型列表可拉取 |
| 11 (L) | `MinisApp`/`MainActivity` 接崩溃收集与上下文守卫；首启接 `OnboardingScreen` | 上游 8 个单测；实机：首启引导可跳过、崩溃信息可查 |
| 12 (K) | 新增终端入口（设置或会话内）；`TerminalCanvasView` ← `TerminalEmulator` ← `AnsiParser` | **需自研测试**（上游无）；实机：`htop` 类交互程序可显示、按键与滚屏正确 |
| 自研 | F2 接用量页趋势图；P1 接会话打开路径；B4-F3/B4-X2 接提问卡片 | 各自纯函数单测；实机：大会话打开有占位、提问卡片有类型与缩略 |

## 4. 不做 / 需评估

| 项 | 判断 |
| --- | --- |
| `ClaudeCliMimicryHeaders` | **做**（已列 P0）。它不是伪装爬取，而是把聊天路径早已在发的同一组客户端指纹补到 auth 路径，修 Cloudflare 403；上游刻意**不**发 `anthropic-beta`，移植时保持这个边界 |
| Copilot / Codex 接入 | **做但保留上游的合规姿态**：上游自带 `CopilotDisclaimerDialog`（7 段免责正文）与 `util/CopilotFeatureFlag`，移植时不得省略免责与开关 |
| `HeadlessRenderHost` | VirtualDisplay + ImageReader 无头渲染，资源占用与多显示器行为需实机评估；若只为截图，可先不做 |
| 终端模拟器 | 上游无测试，属于「移植后要自己兜住质量」的大件，排在最后 |
| iOS-only 内容 | `upstream/v1.14` 里若干 Swift 侧改动（`docs(ios)` 提交）不适用，已排除在清单外 |

## 5. 节奏与依赖

```
P0 (Claude OAuth 修复)        ── 独立，先做
1 A (Markdown)                ── 独立
2 B (看图/存储)               ── 独立
3 F (容器 DNS)                ── 独立，最小
4 I (聊天页门控件)             ── 独立（15 件可拆 3–4 个 PR）
5 C (计划任务)                ── 依赖已有 scheduled 线（CLI/编辑页已完成）
6 E (备份/恢复)               ── 依赖已有备份线（X1/F4 已完成）
7 H (语音)                    ── 与 X7 同域，建议合并做
8 J (沙箱/浏览器)             ── 独立；GlobalShellThrottle 可提前单独发
9 D (helper 控制台)           ── 依赖已有 SubAgentActivityTracker/EventBus
10 G (Copilot/Codex)          ── 独立
11 L (诊断/引导)              ── 独立
12 K (终端)                   ── 依赖 emulator → canvas 顺序；需自研测试
自研 4 项                      ── 穿插进行，互不阻塞
```

每批一个（大件拆多个）PR，PR 描述固定带三样：**上游出处**（文件/符号/字符串键）、**我们的接入点**、**验收方式**。这样任何一批都能被独立复核，不会出现「文件加进来了但没人调用」的空接。
