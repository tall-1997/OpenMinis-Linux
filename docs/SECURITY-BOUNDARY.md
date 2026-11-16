# 安全边界（Security Boundary）

> 本文档说明 Minis 的安全概念，以及边界在哪里、不在哪里。
> 姿态对齐 pi coding-agent 的 `SECURITY.md`：**明示真实边界**，把"隔离"和"授权"分开归责，
> 把 out-of-scope 写清楚，避免无效漏洞噪音。（移植对照：`PI-PORT-ANALYSIS-2026-11-15.md` §2.10）

## 1. 信任模型总述

Minis 是一个运行在用户 Android 设备上、以用户身份执行操作的 AI agent。它有意拥有强大的
本地能力：PRoot 客机里的完整 Linux shell、设备工具（日历/通知/剪贴板/无障碍）、可选的宿主
特权通道（root / Shizuku）。能力的另一面是责任的划分——**隔离责任交给沙箱，授权责任交给
应用内权限门**，两者都不试图在模型层做防御。

### 1.1 进程边界 = 用户边界

Minis 应用进程运行在用户的 app UID 下。应用进程可访问的一切（`/data/data/<pkg>` 私有目录、
数据库、SharedPreferences、已挂载的外部目录）与用户自身可访问的数据处于**同一信任边界内**。
如果攻击者已经能写入应用的私有文件、数据库或配置（例如通过 root、备份提取、或另一个拥有
同 UID 的组件），那么"由此影响 agent 行为"的报告**不是安全漏洞**——除非报告同时证明 Minis
自身授予了那次写入能力、或跨越了操作系统权限边界。

### 1.2 隔离责任：PRoot 沙箱（OS 级隔离的边界与不是什么）

所有 shell 工具、MCP stdio server、生命周期 hook 脚本都运行在 PRoot 客机内
（Ubuntu 24.04 arm64，`sandbox/PRootKernel.kt` 构建命令行，`/var/minis/**` 以 bind-mount
进出）。PRoot 提供的是**用户态文件系统视图与进程命名隔离**：

| PRoot 沙箱提供 | PRoot 沙箱不提供 |
|---|---|
| 独立的根文件系统视图（rootfs） | 内核级隔离（不是容器/VM；基于 ptrace） |
| `/var/minis` 等路径的按需挂载与隐藏 | 对抗性沙箱逃逸防线——**PRoot 不是安全边界** |
| guest 内独立 root 身份（fake-root） | 宿主资源访问的真实隔离（系统调用最终以 app UID 执行） |
| 客机内 `/proc` 隐藏其他 UID（hidepid）、ICMP 禁用 | 对宿主文件的强制访问控制 |

**含义**：guest 内执行的代码以应用身份在宿主上真实运行。凡是 guest 能读写的路径
（含所有 bind-mount 进去的目录），都视为"交给 agent 的数据"，与 pi 对 workspace 的定性一致。
用户不应把不想让 agent 接触的凭据放进挂载目录；需要更强隔离时，应依赖设备自身的 OS 机制
（多用户 / 工作资料 / 第三方容器），而不是把 PRoot 当作防线。

### 1.3 授权责任：应用内权限门

| 门 | 位置 | 管什么 | 不管什么 |
|---|---|---|---|
| **SecurityGate** | `security/SecurityGateImpl.kt` | 工具调用统一分类（Capability / Reversibility / RiskLevel）、按会话权限档位（`SessionPermissionMode`：OFF / ASK / SAFE_AUTO / YOLO）决策、哈希链审计（`AuditEntry.prevHash/hash`，`verifyAuditChain`）、可选授权画像（`FsGrant` 路径前缀读写位） | 不验证模型意图；不防御 prompt injection |
| **ApprovalGate** | `service/ApprovalGate.kt` | 需要用户确认的调用弹出审批（90s 超时）；"本会话总是允许"按参数 gist 记入会话级白名单；`fatalConfirm` 类调用**任何白名单都跳不过** | 不做跨会话永久授权；用户批准过的动作不追溯 |
| **DevicePermission** | `device/DevicePermission.kt` | 设备工具的 Android 运行时权限与特殊访问（通知监听、写设置等）映射，风险标签（READ_ONLY / WRITE / SENSITIVE / DANGEROUS）随工具描述下发 | 不替代系统权限对话框；用户拒绝即拒绝 |
| **WritePathGuard** | `tools/WritePathGuard.kt` | 子代理并行写隔离：`spawn_agent.write_paths` 前缀白名单（ThreadContextElement，跨协程保持）；声明了白名单但全部非法时**毒化（fail-closed，全拒）** | 只约束 file_write/file_edit 的路径；不约束 shell 内的写（shell 由 PRoot 视图约束） |
| **SuPathPolicy** | `security/SuPathPolicy.kt` | 宿主特权通道（`su` / `android-su` / Shizuku 回退）的路径禁区：拒绝读应用数据库、应用私有目录、其他会话/项目目录 | 不审计用户在特权通道里自己敲的命令；root 即用户边界本身 |
| **OutboundMasker** | `security/OutboundMasker.kt`（默认关，`OutboundMaskPrefs`） | 出站请求载荷（发给模型供应商的字节）里的凭据/邮箱/手机号形状文本按模式改写；是形状掩码，不查密钥库 | 不是出站防火墙；不对入站内容做净化 |

辅助层：`EnvVarRedactor`（shell 输出里的环境变量值脱敏，默认开）与 `SecretMasking`
（日志/快照里的凭据形状掩码）覆盖各自的出站面。

## 2. 各信任边界清单

| 边界 | 运行位置 | 信任模型 | 主要缓解层 |
|---|---|---|---|
| **工具执行**（shell_execute / file_* / search 等） | PRoot 客机内进程；特权通道 `su -c` / `android-su`（Magisk/KernelSU，缺省回退 Shizuku） | guest 内代码 = 交给 agent 的数据；bind-mount 进 guest 的宿主目录视为在边界内 | SecurityGate 按档位决策 + ApprovalGate 审批 + SuPathPolicy 特权路径禁区；guest 视图由 PRoot 挂载表决定 |
| **MCP server** | stdio：PRoot 客机内进程（`minis-mcp-cli` / daemon，行分隔 JSON-RPC）；另有 HTTP 端点型 | server 是用户安装的可信组件；其工具结果回流为 tool output，属于不可信文本 | server 启停经 MCP 开关与会话覆盖（MCPRepository）；工具调用仍过 SecurityGate/ApprovalGate；输出长度与解析层（McpOutputLimit） |
| **扩展与技能** | 进程内（Lifecycle 监听器、生命周期脚本）、提示词层（SKILL.md 注入） | **可信源是用户责任**。SKILL.md 正文 / MCP 工具结果 / 网页文本都是 prompt injection 面；技能正文只有被 invoke_skill 显式加载或 manifest 命中后才进入上下文 | 技能默认只注入 name+description+location 清单（渐进加载，`disable-model-invocation` 技能完全隐藏）；生命周期脚本默认关闭且逐条 try/catch 隔离；设备工具调用一律回到权限门 |
| **网页 / 浏览器工具**（browser_use） | 应用内 Android WebView（Chromium 渲染进程自有 OS 级沙箱，Safe Browsing 开启） | 网页内容是不可信输入：可被读取进上下文，不可被当作指令来源信任；`minis://` 方案把应用内文件暴露给预览，仅限本应用解析 | WebView 进程沙箱 + Cookie/存储按会话管理；对设备工具的调用仍走权限门；出站载荷可选 OutboundMasker |
| **凭据存储** | 加密 SharedPreferences（provider API key）；配置 JSON + SHA-256 镜像 | 应用私有存储 = 用户边界内（见 §1.1） | `minis-config get` 永不回显 key/token（读即 `permission_denied`）；配置写入有可回滚审计；shell 输出默认脱敏（EnvVarRedactor）；日志层 SecretMasking |

## 3. Out of Scope

对齐 pi 的姿态——以下类别**不作为安全漏洞受理**。每条注明"为什么"与"现有缓解层"。

| # | 类别 | 为什么 out of scope | 现有缓解层 |
|---|---|---|---|
| 1 | **PRoot 沙箱逃逸** | PRoot 是用户态视图（ptrace），从一开始就**不是安全边界**；把"guest 内代码触达 bind-mount 内的宿主数据"当漏洞，等于把设计当作缺陷 | §1.2 的边界定性；用户应只挂载愿意交给 agent 的目录；更强隔离交给 OS（多用户/工作资料） |
| 2 | **恶意技能 / 扩展 / MCP server** | 可信源是用户责任——安装即授权，与 pi 一致 | 技能渐进加载限制常驻注入面；`disable-model-invocation` 可把技能降级为"仅显式调用"；来源 URL 记录（sourceURL）；工具调用仍过权限门 |
| 3 | **Prompt injection 对设备工具的间接调用**（恶意网页/文件/工具结果指示模型调用设备工具） | 模型层不做注入防御是明示的设计边界；间接调用的**最终动作**仍然要过权限门，这才是兜底位置 | SessionPermissionMode 档位 + ApprovalGate 审批 + DevicePermission 运行时权限；`fatalConfirm` 类调用白名单不可跳过 |
| 4 | **用户已批准 / 用户主动的本地动作** | 审批记录在案（哈希链审计）后重放为"漏洞"，等于推翻用户自己的授权 | ApprovalGate 白名单按会话、按参数 gist；审计可回查 |
| 5 | **以本地写权限为前提的报告**（能写 AGENTS.md、SKILL.md、MEMORY、配置、dotfiles、挂载目录后再影响 agent） | 与 pi 相同：这类先决条件本身已越过用户边界，除非证明是 Minis 授予了那次写能力 | 写路径上的门（WritePathGuard、SuPathPolicy）见 §2；install 均有审计 |
| 6 | **来自可信配置的 DoS / 资源消耗**（超长技能清单、超大 SKILL.md、循环 MCP 调用等） | 属于用户自配内容的质量问题，不是边界穿越 | 描述截断（200 字符）与清单上限（120 条）控制常驻成本；工具结果限流（BoundedOutputBuffer / McpOutputLimit） |
| 7 | **恶意模型输出** | 模型输出在执行侧一律经过工具门，输出内容本身不是执行体 | 同 #3 的权限门链 |
| 8 | **不可信 MITM 代理 / 证书替换导致的请求篡改** | 传输层信任取决于用户配置的代理与端点 | 用户自配 endpoint 属于自主决策；出站掩码（OutboundMasker，默认关）只处理凭据形状，不承诺传输完整性 |
| 9 | **公开暴露 Minis 实例**（root/adb 暴露、把 minis:// 或本地端口发布到公网） | 产品形态是单机应用，公开部署不属于支持的运行姿态 | 无；此类部署不受支持 |
| 10 | **第三方/用户自持凭据的泄露**（用户把 key 粘贴进聊天、放进挂载目录） | 凭据进入用户自己选择的数据面后，Minis 无法区分"任务需要"与"泄漏" | EnvVarRedactor / OutboundMasker / SecretMasking 三层形状掩码可显著缩小窗口（出站层默认关，需用户开启） |

## 4. 防御纵深速查

一次典型的"模型想执行 X"要穿过的层（由内向外）：

```
模型输出 → SecurityGate 分类/档位决策 →（需审批）ApprovalGate 用户确认
        → DevicePermission（设备工具）→ WritePathGuard（子代理写路径）
        → SuPathPolicy（特权通道）→ PRoot 视图（文件系统面）
        → 出站掩码（OutboundMasker / EnvVarRedactor，均为形状掩码）
        → 审计链（SecurityGateImpl.audit）
```

任何一层的存在都不把上一层变成安全边界：最外层的真实边界始终是 §1.1 的用户边界
（OS 权限）与 §1.2 的边界定性（PRoot 非防线）。

## 5. 报告漏洞

属于本文档 scope 内的问题（信任边界穿越、权限门绕过、审计链失效、凭据存储缺陷），请通过
仓库的 GitHub **私有安全通告（Security Advisories）**报告，不要开公开 issue。报告请附：

- 问题与影响的一句话描述；
- 最新 release 或 main 上的可复现步骤 / PoC；
- 影响的组件（对应 §2 表格中的边界）与版本/commit；
- 已知缓解。

最有价值的报告是**当前可复现的边界穿越**。仅展示"预期本地 agent 行为"、prompt injection、
或恶意可信技能/扩展的报告，按本文档模型不属于安全漏洞。

## 6. 与 pi 的对照注记

对齐 `PI-PORT-ANALYSIS-2026-11-15.md` §2.10（pi `SECURITY.md` + `docs/containerization.md`）：

| pi 的姿态 | Minis 的对应 | 差异说明 |
|---|---|---|
| "进程边界 = 用户边界；本地写先决条件不受理" | §1.1 同款定性 | Minis 的 agent 面（guest rootfs + bind-mounts）比 pi 的纯宿主目录更宽，但定性相同 |
| "扩展/技能可信源是用户责任；prompt injection 不设防" | §3 #2、#3 同款 | Minis 额外有权限门兜底（pi 无移动设备工具面），但同样不在模型层设防 |
| "进程沙箱不是默认（无沙箱），建议容器化"（containerization.md） | Minis 默认就有 PRoot 客机；§1.2 明示它**不是**对抗性边界 | pi 用外部容器补隔离；Minis 内置 PRoot 但同样要求用户理解其边界，对外部隔离（工作资料/多用户）的建议一致 |
| Out-of-scope 清单（沙箱行为、用户安装的扩展、untrusted repo、MITM、公开暴露、prompt injection…） | §3 逐条对齐并本地化（+设备工具间接调用、+自配代理） | 条目顺序与措辞按本仓库威胁面重排 |
| 审计：pi 无哈希链审计 | Minis 有（SecurityGateImpl.audit + verifyAuditChain；minis-config 写入另有可回滚审计） | 属于本仓库超出 pi 的部分，作为缓解层而非边界 |
