# OpenMinis-Linux 对照路线图

对照对象：Operit、Operit2、Eta、shiyi-agent、OmniBot、[metano](https://github.com/qqzijin/metano)。原则：**吸收产品能力，不复制其源码**；不引入 Xposed/LSPosed 系统注入；不捆绑 Shizuku APK；不在 APK 内塞本地 LLM 权重。进化层专文见 [METANO-EVOLUTION.md](METANO-EVOLUTION.md)。

---

## 已实现（含 1.15-linux 及本 fork 已有能力）

| 能力 | 来源启发 | OpenMinis-Linux 落地 |
| --- | --- | --- |
| Ubuntu PRoot 沙箱 + `shell_execute` / 文件工具 / 浏览器 | 自身（OpenMinis） | 完整 Agent 循环 |
| 子 Agent `run_subagent` | OpenMinis / 多 Agent | 已有，并行上限可配 |
| 技能系统 + `skill-creator` | Eta / OpenMinis | 已有；另增 `android-sdk-mirrors`、`android-device-ops` |
| 计划讨论 | 自身 | 聊天菜单 Plan discussion |
| 人设 persona | Operit 人设 | 已接入 |
| 后台浮窗看工具状态 | Operit 悬浮窗 | `ToolOverlayController`；点胶囊回对应会话 |
| 浮窗停止任务 | Operit 悬浮窗停止 | 1.14：运行中按钮 = cancel stream + stop command |
| 通知栏 Agent 状态 / Stop | Operit 前台服务 | `AgentForegroundService` |
| Host su | 各 Root 助手 | `minis-su-cli`，设置里显式开关 |
| Shizuku 后端 | Operit / Eta | 检测并使用**用户已装**的 Shizuku，不随包分发 |
| 无障碍 GUI 自动化 | OmniBot / Operit | 已有 a11y 服务与修复引导 |
| 联网搜索 | Operit / shiyi | 1.15：可配 DDG / SearXNG / Bing，失败回退 |
| 大输出不炸上下文 | shiyi 长任务 | 1.14 spill；1.15 UI 打开 spill 文件 |
| 对话分享为图 | Operit2 分享卡片 | 1.15：深色/浅色/纸张、隐藏工具、分页 |
| 子 Agent 状态条 | Operit 多 Agent 面板 | 1.15：聊天顶栏胶囊 |
| 浮窗迷你聊天 | Operit 悬浮窗 | 1.15：胶囊展开输入并注入会话 |
| 技能订阅 | Eta 远程源 | 1.15：URL/目录 + SHA-256 + 自动更新 |
| 无障碍场景录制 | OmniBot | 1.15：用户录制成 skill，无预置黑产脚本 |
| 国内镜像 | 国内使用场景 | SDK / 下载镜像 |
| 滚动预发布 APK | 自身 CI | `android-latest`；tag `v*` 出正式版说明 |
| 原生进化层 LEARNED.md | metano 闭环 | 1.25：提案审批、Be-ACTIVE、闲时收割、技能补丁、信念衰减/周反思/场景注入；默认关。见 [METANO-EVOLUTION.md](METANO-EVOLUTION.md) |

---

## 待实现

1. **正式签名密钥** — CI 仍是 debug-signed release，上架/覆盖旁路包会受影响。
2. **Codex 式 `/goal` 追求目标** — 可行性与对齐规格见 [specs/codex-goal.md](specs/codex-goal.md)。未开工。

---

## 不建议实现

| 想法 | 原因 |
| --- | --- |
| LSPosed / Xposed / 系统框架注入（Eta 路线） | 高对抗、易封号/变砖、与「用户可感知授权」相反 |
| APK 内捆绑 Shizuku 或 Magisk 模块 | 许可与分发风险；用户应自行从官方渠道装 |
| 设备端 MNN / llama 权重打进包 | 体积爆炸、电量与发热、与云端模型产品定位冲突 |
| 复制 Operit / Operit2 源码或技能包 | LGPL / AGPL 会污染本仓库许可 |
| vendor metano 源码 / FastAPI 面板 / 消息网关进 APK | 那是 Claude Code 常驻网关，不是手机 Agent；进化层只吸收闭环，见 [METANO-EVOLUTION.md](METANO-EVOLUTION.md) |
| 进化进程自改 APK / Kotlin | 等于关掉验证门；PRoot 只允许改客户文件 |
| 静默无障碍连点、抢红包、刷量 | 恶意软件形态，拒绝 |
| 读取短信/通话记录/通知内容默认全开 | 隐私越权；除非用户当轮明确要求且已授权 |
| Google 结果页爬虫 | ToS；用 DDG / 自建 SearXNG 即可 |
| 绕过 Play Protect / 隐藏自身 | 对抗检测，不做 |

---

## 和对照项目的定位差

- **OpenMinis-Linux**：手机上的 Linux Agent（PRoot + 工具循环），Android 权限是「需要时再爬梯子」。
- **Operit / Operit2**：更像全能手机助手（搜索、悬浮窗、人设、分享）。
- **Eta**：深度系统改机（LSPosed），我们不走。
- **shiyi-agent**：工程向长任务；我们用 spill / 沙箱对齐其「别卡死」而不是对齐其全部工具链。
- **OmniBot**：无障碍 GUI 机器人；我们有 a11y，但不当成默认人格。
- **metano**：Claude Code 的 Python 进化网关；我们只把 Observe→Act 做成 Kotlin 提案层，不搬其进程。见 [METANO-EVOLUTION.md](METANO-EVOLUTION.md)。
