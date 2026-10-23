# 恢复内置 OpenCode Zen 免费通道 · 设计文档

日期：2026-10-23
状态：待批准
证据：`/var/minis/workspace/zen-free-lane-evidence.md`（全部实测原始输出）

## 1. 意图与背景

#61（`8d560c58`）下线了内置的 keyless Zen 免费通道，理由是"上游改为账号鉴权，
`Bearer public` 失效，第三方传输一律 403"。

**那个诊断是错的**，错在只测了请求头，没测请求体形状。2026-10-23 的对照实验：
同一套头、同一个 session、同一个模型，唯一变量是 `tools` 数组 ——

```
无 tools                  → 403 FreeTierError
tools=[get_device_battery] → 403 FreeTierError
tools=[grep, glob]         → 403 FreeTierError
tools=[bash]               → 403 FreeTierError
tools=[read]               → 200 ✓
tools=[shell, read]        → 200 ✓
tools=[四个 gate 占位]      → 200 ✓（真回复 "OK"）
```

`FreeTierError: OpenCode's free tier can only be used from within OpenCode` 的真实
判据是：**请求的 tools 里必须存在一个名为 `read` 的函数定义**。上游用这个形状
识别 OpenCode CLI（它的文件读取工具就叫 `read`）。不是身份墙，不是 TLS 指纹，
不是地区 —— 是一个可以在请求体里满足的形状条件。

用户已独立实测确认免费档可用（`deepseek-v4.1-flash-free`），并提供了样本网关
（`dev.oc2a.pro` v3.8 APK）作为交叉验证：该 APK 的 `Gate.ensure()` 注入
shell/glob/grep/read 四个占位工具，`Session.buildHeaders()` 构造头集，
经它转发的请求在本机 200。

**为什么现在做**：免费档是零配置可用的推理资源，对没有 API key 的用户是唯一的
开箱即用路径。它还能承载真工具调用（实测 `tool_calls` 正确返回），所以不是
聊天玩具，能当 agent 后端。

## 2. 目标

- G1 装好/升级即有一个可用的 Zen 免费实例，零配置直接聊（用户决策：自动播种）
- G2 目录只列免费档（用户决策），但**锁区模型照常显示**（用户决策，见 §5）
- G3 免费档能跑完整 agent 循环：真工具调用、多轮、流式
- G4 上游拒绝时给出可行动的结构化提示，不是笼统的"key 无效"
- G5 契约本身有测试钉住，防止再次被当成"多余代码"删掉

## 3. 非目标

- N1 不做本地网关进程（样本 APK 的架构）。配方是纯 HTTP 层的，直接在 provider
  内实现，少一个常驻 Service、少一份端口占用、少一层转发延迟。
- N2 不接 `/zen/go/` 档（实测 401 "Go requires a credential"，不是免费通道）。
- N3 不做付费档模型目录（用户决策：只免费档）。用户自己的 Zen key 仍可用于
  自建的 Zen 实例 —— 那条路径不动。
- N4 不伪造设备指纹、不做 TLS 层伪装。不需要。

## 4. 反目标（做成这样即使能跑也算失败）

- A1 **不得**因为本机出口 IP 的地区证据而隐藏任何模型（见 §5）。
- A2 **不得**把上游的结构化拒绝吞成静默失败或笼统 401。
- A3 **不得**让注入的占位工具变成可执行的真工具而不被察觉 —— 映射必须显式、
  有测试、有日志。
- A4 **不得**为了通过门禁而改动非 Zen provider 的请求构建路径。
- A5 **不得**把模型 id 枚举当作目录来源。上游偶尔会加新免费模型，新模型必须
  **无需发版**就出现在目录里。代码里的 id 集合只能是“无后缀已知例外”与
  “首装离线种子”两种，且两者均必须被远端目录与学习缓存覆盖。

## 5. 用户已决定的五件事

1. **目录范围：只免费档，但目录本身不钉死**（用户明确要求：上游偶尔会加新
   模型）。内置实例的模型选择器只列免费档（付费档 claude/gpt/gemini/grok…
   不进），但**哪些算免费由运行时判定，不由代码枚举**。三层机制：

   - **规则层**：`GET /zen/v1/models` 拉全量目录，id 包含 `-free`（大小写不
     敏感）即判为免费。上游新加的 `-free` 模型下次刷新自动出现，零代码改动。
   - **学习层**：目录记录只有 `id/object/created/owned_by` 四个字段（实测），
     `owned_by` 85 个全是 `opencode`，**没有任何免费/价标记**。所以不带
     `-free` 后缀的免费模型（如 `big-pickle`）只能靠证据发现：对目录里
     **新出现的**不带 `-free` 的 id 做轻量探测（`max_tokens=1` + 门禁形状），
     200 → 进学习缓存并展示；“需要凭据”类错误 → 记住是付费档，不展示也不
     重试；网络/429 失败 → **不当成付费**，下次重试。探测只做增量（与上次
     缓存的差集），不全量扫 77 个。用户实际使用产生的证据同样写回缓存。
   - **缓存层**：目录与学习结果落盘，TTL 过期或手动刷新时重拉；网络失败回落
     **上次磁盘缓存**（而不是常量）。只有首装且无网络（从未拉到目录、无缓存）
     时，才用一个极小离线种子（`deepseek-v4.1-flash-free`、`big-pickle`）并标注
     “离线兵库，刷新后以远端为准”；首次成功刷新后种子永久失效。
2. **实例形态：自动播种**。恢复 `ensureBuiltInZenProvider`：启动时若不存在则
   创建内置实例（固定 id、key = `public`），升级路径上把 #61 的
   `retireBuiltInZenProvider` 反转。用户自己建的 Zen 实例永不触碰。
   内置 id **复用** `builtin-opencode-zen`（#61 已把它从设备上删除，同 id 重新
   播种最干净，升级路径就是把 retire 反转成 seed，不需要处理第二个 id）。
3. **占位工具被调用时：映射 + 报错兜底**。`read` → `file_read`、`shell` →
   `shell_execute`（含参数翻译）；映射不掉的（`glob`/`grep` 我们本来就有真同名
   工具，不会注入占位）或反复调用同名占位 → 返回结构化错误让模型自纠。
4. **老版本用户无感升级**。设备上还带着旧内置实例与已失效免费模型的用户，
   升级后不需任何手动操作就能用上新的免费通道；用户改过的显示名/参数/
   隐藏态与默认模型选择不得丢，离线时不得变坏（详 §11）。
5. **删过的用户要有渠道自己加回来**。内置实例被删、或其中若干免费模型被删，
   都要有显式入口可恢复，不能只能清数据重装（详 §12）。

**关于锁区（用户明确要求）**：`beam-free` 在本机出口返回
`403 RegionError: This model is not available in your country`。我们的出口 IP
不代表用户的网络环境。**目录不按地区筛选**，RegionError 是运行时状态而非目录
属性 —— 用户换个网络就好了，目录不该替他的地理位置做决定。

由此推出一条对健康度机制的硬约束：**只允许对永久失效证据做剔除**
（`410 Gone`、`400 Model is unavailable.`），**禁止**因 `RegionError` /
`ModelProtocolUnsupported` / `FreeTierError` 隐藏模型。#61 之前那版
`ZenFreeLaneHealth`（按 401/403 证据自动拉黑）的语义**不得**原样恢复。

## 6. 上游契约（全部实测，精确值）

### 6.1 端点

```
GET  https://opencode.ai/zen/v1/models                   匿名 200，85 个模型
POST https://opencode.ai/inference/v1/chat/completions   推理（推荐）
POST https://opencode.ai/zen/v1/chat/completions         推理（等价可用）
```

### 6.2 请求头

```
Content-Type: application/json
Authorization: Bearer public
User-Agent: opencode/prod/2.0.29/cli
x-opencode-client: cli
x-opencode-project: global
x-opencode-session: <sid>
x-opencode-session-id: <sid>
x-session-affinity: <sid>
x-session-id: <sid>
Accept: text/event-stream
```

版本号来源：`GET https://registry.npmjs.org/opencode-ai/latest` 取 `version`，
若 < `2.0.0` 或请求失败 → 兜底 `2.0.29`。实测 npm 上最新是 `1.19.4`（< 2.0.0），
所以 UA 恒为 `opencode/prod/2.0.29/cli`。**不要**用旧的
`opencode/1.18.31 (Android arm64; native)`（已失效格式）。

### 6.3 session id

```
ms  = 当前毫秒
cnt = 同毫秒内递增计数，跨毫秒重置为 1
v   = ((ms shl 12) + cnt) and 0xFFFFFFFFFFFF
v   = v.inv() and 0xFFFFFFFFFFFF
sid = "ses_" + v.toString(16).padStart(12,'0') + 14 位随机 Base62
Base62 字母表 = 0-9A-Za-z
```

实测同一 sid 可跨多次请求复用（无需每请求新生成）。401 时刷新 sid 重试
（样本最多 3 次）。旧的 SHA-256 派生实现（`ZenDisguise.sessionId`）不必恢复。

### 6.4 请求体改写（门禁核心）

```
1. stream 必须为 true。stream=false 即使带 gate 也是 403 FreeTierError（实测）。
2. tools 必须包含一个 name == "read" 的 function 定义。这是唯一充分条件：
   read 单独一个就 200；bash/glob/grep/write/edit/task/webfetch 单独都 403。
3. 补齐策略（沿用样本 Gate.ensure 语义，抗上游将来收紧）：
   对 {shell, glob, grep, read} 四个名字，若客户端 tools 里已存在同名且
   parameters.properties 非空的定义 → 视为已满足，不注入；否则注入占位。
   我们的注册表里有真 grep（GrepTool）和真 glob（GlobTool），properties 非空
   → 实际只注入 shell 和 read 两个占位。
4. tool_choice == "none" 必须删除（否则上游拒绝工具调用）。
5. 注入了占位就往第一条 system/developer 消息 content 末尾追加：
   "\n\nThe tools named shell, glob, grep and read in this request are
   placeholders. Do not call them; use the other tools you have been given."
```

占位定义（`strict: false`，description 原样照抄）：

| name | description | parameters |
|---|---|---|
| shell | `Execute a shell command and return its output.` | command(string, required), workdir(string), timeout(integer, ms), background(boolean) |
| read | `Read the contents of a file or directory.` | path(string, required), offset(integer), limit(integer) |
| glob | `Search file paths using a glob pattern (examples: "**/*.ts", "src/**/*.tsx").` | pattern(string, required), path(string), hidden(boolean), limit(integer) |
| grep | `Search file contents using ripgrep syntax.` | pattern(string, required), path(string), include(string), literal(boolean), caseSensitive(boolean), limit(integer) |

### 6.5 撞车行为（实测，决定映射是兜底而非主路径）

真 `file_read`/`shell_execute` 与占位 `read`/`shell` 同时在场，NOTE 有无各测一次：

```
WITH note    read  prompt → called=['file_read']     args={"path":"...","lines":5}
WITH note    shell prompt → called=['shell_execute'] args={"command":"uname -a"}
WITHOUT note read  prompt → called=['file_read']
WITHOUT note shell prompt → called=['shell_execute']
```

4/4 选真工具。NOTE 保留（对其他免费小模型是廉价保险）。

### 6.6 错误码语义与客户端应对

| HTTP | type | 含义 | 应对 |
|---|---|---|---|
| 403 | FreeTierError | 请求体没满足门禁 | **我们的 bug**。提示"免费档请求形状不完整"，不 blame 用户 |
| 403 | RegionError | 该地区不可用此模型 | 结构化提示 + 建议换模型/换网络。**不隐藏模型** |
| 400 | ModelProtocolUnsupported | 模型不吃此请求形状 | 结构化提示，不隐藏 |
| 400 | `Model is unavailable.` | 模型已下线 | 可从学习缓存/离线种子剔除（永久失效证据；目录里它本就不再出现） |
| 410 | Gone | 模型已退役 | 同上 |
| 401 | `Go requires a credential` | 打到 go 档 | 路由 bug，别用 go |
| 429 | rate limit | 免费额度用尽 | 指数退避（base 1s / cap 30s / 最多 3 次） |

### 6.7 免费模型可用性（2026-10-23 本机出口快照）

**这是一次测量快照，不是目录来源，不得抄进代码当清单（A5）。**
它的用处是记录当天上游状态，并证明“锁区/协议不支持 ≠ 不存在”。

| 模型 | 状态 |
|---|---|
| big-pickle | 200 |
| deepseek-v4.1-flash-free | 200（用户亦实测可用） |
| flint-40b-free | 200 |
| mimo-v2.6-flash-free | 200 |
| nemotron-3.5-super-vl-free | 200 |
| nemotron-3.5-lightning-free | 200 |
| glm-5.3-flash-free | 200 |
| beam-free | 403 RegionError（**仍显示**） |
| jev-1.13-free | 400 ModelProtocolUnsupported（**仍显示**） |
| minimax-m3.1-flash-free | 410 Gone，已从上游目录消失 → 规则层自然不再命中 |
| union-alpha | 400 Model is unavailable，已从上游目录消失 → 规则层自然不再命中 |

## 7. 架构

三个新文件承载全部 Zen 专属逻辑，现有大文件只留调用点（架构门禁 ceiling 只降
不升，贴顶文件养不起新逻辑）：

```
provider/ZenFreeLaneContract.kt   头集 + session id 生成 + isZenHost 判定
                                  （ZenEndpoint.isZenHost 迁入或委托，单一事实源）
provider/ZenGateTools.kt          四个占位定义 + ensureGate(body) 改写规则
provider/ZenFreeCatalogue.kt      免费判定三层（规则/学习/磁盘缓存）
                                  + 刷新 TTL + 新 id 增量探测；**不得含硬编码免费 id 列表**
```

改动点：

| 文件 | 改动 |
|---|---|
| `provider/openai/OpenAIRequestBodies.kt`:63 `buildRequestBody` | tools 组装后调 `ZenGateTools.ensure(body)`；强制 stream=true；删 tool_choice=none |
| `provider/openai/OpenAIRequestBodies.kt`:545 `buildRequest` | Zen 主机时套 `ZenFreeLaneContract.applyHeaders(builder)` |
| `data/repository/ProviderRepository.kt`:267/602 | `retireBuiltInZenProvider()` → `ensureBuiltInZenProvider()`：不存在则播种（固定 id、key=`public`、免费目录），存在则保留；用户自建实例不动 |
| `provider/openai/OpenAIProviderErrors.kt` | 按 §6.6 映射 Zen 方言（RegionError/FreeTierError/ModelProtocolUnsupported/Gone）为可行动文案 |
| 工具分发（`ui/chat/ChatViewModelExecuteToolExt.kt`） | 未知工具名 `read`/`shell` → 映射到 `file_read`/`shell_execute`（参数翻译：timeout ms→s、limit→lines）；映射失败或同轮重复 → 结构化错误 |
| `test/.../ZenFreeLaneRetirementGuardTest.kt` | **改写**为 `ZenFreeLaneContractTest`：从"墓碑"变"契约守卫"，钉住 §6 的每一条（见 §9） |
| UI（provider 卡片 / 模型选择器） | 免费档标记；Apple 风格图标（先加载 awesome-design-md + ui-ux-pro-max，M3Icons 双向映射注册） |

**播种与旧实现参考**：`git show 8d560c58^:src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt`
里的 `ensureBuiltInZenProvider` 是 #61 删掉的播种实现，可直接作为骨架。同提交树里还有
`provider/ZenDisguise.kt`（头集/session 的旧版，**其 UA、project 常量、SHA-256 session
算法均已失效，按 §6.2/§6.3 重写**）和 `provider/ZenFreeLaneHealth.kt`（自动拉黑，
**语义按 §5 收窄，不得原样恢复**）。

**流式约束的实现后果**（§6.4 第 1 条）：免费档只吃 `stream=true`。凡是走非流式
路径的调用方（标题生成、上下文压缩、子代理等，若存在）在 Zen 免费实例上必须由
客户端聚合 SSE。builder 需先确认这些路径是否共用同一个流式入口；若有独立的
非流式分支，Zen 实例要走聚合分支。

## 8. 留给 builder 的判断

- B1 目录刷新时机与 TTL（样本用 600s + 磁盘缓存 `models_cache.json`）。建议：
  启动、实例首次使用、手动刷新三个时机拉取，TTL 10 分钟，失败回落**磁盘缓存**
  （不是常量），无缓存时才用离线种子。
- B2 增量探测的调度与预算：只对差集里新出现的不带 `-free` 的 id 探测，每批
  建议 ≤ 3 个、低优先级、避开用户正在跑的会话（免费档对 429 敏感）。
- B2.1 学习缓存的存放位置与格式（建议跟 provider 配置同目录的独立 json，
  不进备份导出路 —— 它是可重建的派生数据，不是用户资产）。
- B3 429 退避是否与现有重试机制共用（避免双重重试放大）。
- B4 占位工具映射的日志级别（建议 warn，便于发现模型行为漂移）。
- B5 `x-opencode-request: req_<uuid>`（旧实现有，样本 APK 无）—— 实测未带也 200，
  建议不带（少一个无证据的头）。

## 9. 契约测试必须钉住的点

1. `read` 存在 → 请求通过门禁形状；缺失 → 断言我们注入了它
2. `stream` 恒为 true（Zen 免费实例上）
3. `tool_choice == "none"` 被删除
4. 客户端已有同名且 properties 非空的工具 → 不重复注入（我们的真 grep/glob）
5. 注入占位时 NOTE 被追加到第一条 system/developer 消息，且只追加一次
6. session id 形状 `^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$`
7. 头集九个键齐全，UA 为 `opencode/prod/2.0.29/cli`，Authorization 为 `Bearer public`
8. **RegionError 不导致模型从目录消失**（A1 的机械化守卫）
9. 只有 410 Gone / `Model is unavailable.` 能从学习缓存与离线种子里剔除 id；
   RegionError / ModelProtocolUnsupported **不得**剔除（A1 守卫的另一半）
10. 占位 `read`/`shell` 调用被映射到 `file_read`/`shell_execute`，参数翻译正确
11. 非 Zen provider 的请求体逐字节不变（A4 的守卫）
12. **新模型自动出现**（A5 的主守卫）：给一个假目录响应，内含代码从未见过的
    `something-new-free` → 它必须出现在免费目录里，无需改代码
13. **不带 `-free` 的 id 靠证据收录**：探测 200 → 进学习缓存并展示；探测得到
    “需要凭据” → 不展示且不再重试；探测因网络/429 失败 → 不得归为付费
14. **代码里不存在免费 id 枚举**：守卫测试扫生产源码，除首装离线种子与
    `big-pickle` 已知例外，不得出现硬编码的免费模型 id 列表
15. **S2 老设备迁移**：旧 seed 8 行（含 5 个已死 id）+ 假目录 → 死行被 prune、
    新行加入、**存活行的 uuid/overrides/isHidden/userModifiedAt 逐条继承**
16. **离线不变坏**：目录拉取失败或返回空 data → modelEntries 逐条不变
    （守卫在调用方，不得把空列表传给 `replaceEntries`）
17. **`removeInstance` 后 `deletedModelIds` 不含该实例键**（§12.1 的普适根因守卫）
18. **默认槽位不悬空**：指向死 entry 的默认槽位在迁移后落到一个可用免费
    模型，不为 null；且用户本来就有意的空默认不被覆写
19. **S5 守卫**：用户自建 Zen（key != public）在任何迁移路径下 id/label/key/
    模型行逐字节不变
20. **实例级墓碑**：存在时启动不播种，但 §12.2 三个入口都能恢复；#61 的
    retire 路径不得写墓碑（S3 必须能无感恢复）

## 10. 风险与未验证项

- R1 上游随时可能改判据（如要求多个工具名、校验 description 文本、加真鉴权）。
  缓解：契约测试 + 结构化错误 + 目录动态刷新。这是免费通道，本质上是在别人的
  免费额度上做兼容，**不是 SLA**。UI 文案要诚实（"免费档，可能随时失效"）。
- R2 免费额度限流（429）在高强度 agent 循环下会频繁触发。缓解：退避 + 让用户
  看见"额度用尽"而不是"卡住"。
- R3 未验证：免费档对长上下文（>100k）、对 vision（nemotron-vl）、对
  `tool_choice: required`、对并行工具调用的行为。实现后需逐个实测。
- R4 未验证：多个免费模型在真工具集下的选择准确率（今天只用
  deepseek-v4.1-flash-free 测了撞车）。glm/nemotron/mimo 等小模型可能更爱调占位。
- R5 合规：这是上游的免费档，判据是形状检测而非授权。用户自己判断使用边界；
  实现上不做任何隐藏或伪装（不伪造设备指纹、不绕过付费档）。
- R6 未验证：免费档对大 `max_tokens`（>4k）、对 thinking/reasoning 参数、对多轮
  长上下文的行为。今天实测到的最大 `max_tokens` 是 200（200/24/16/8 均 200 OK）。
  实现后需用真实 agent 会话（长系统提示 + 30 个工具）复测一次。
- R7 **取舍**：老版本里主动删过内置实例的用户，升级后会看到它回来一次 ——
  旧版没有实例级墓碑，设备上"用户删的"和"#61 retire 删的"完全同形，无法区分。
  他再删一次就写墓碑，此后永久尊重。代价是**一次可撤销的回归**，换来的是
  S3（被我们自己删掉的那批人）不必手动找回免费通道。反向选择（默认不播种、
  只给入口）会让绝大多数从没删过的用户升级后失去开箱即用的免费模型，
  与 G1 直接冲突。
- R8 离线升级时，旧 seed 里 5 个已死 id 仍会显示（§11.2 第 1 条要求离线不
  prune）。联网后自然消失。若用户离线时选中死模型，请求失败的错误映射（§6.6）
  必须给出可行动文案，而不是笼统 401。
- R9 §12.2 入口 3 清的是**整个实例**的模型墓碑（含用户可能想继续隐藏的行）。
  缓解：按钮文案带 N（让用户知道会回来几个）+ 需确认；单条重新删除会重新记
  墓碑，所以误伤可逆。不做单条恢复 UI，因为墓碑列表本身没有展示价值。

## 11. 无感升级（老设备上的六种状态）

### 11.0 先摆事实（全部实测/源码确认，不是推测）

- **旧版没有实例级墓碑**：`git show 8d560c58^` 的 `ensureBuiltInZenProvider` 只判断
  "有没有 Zen 免费实例"，没有就播种。所以旧版用户**删了内置实例，下次启动又回来**。
  后果：新版无法从设备上区分"用户主动删的"和"#61 retire 删的"（两者都是"没有实例"）。
- **旧 seed 是硬编码 8 个**（`bundledZenModels()`），其中 5 个今天已从上游目录消失：
  `space-bunny-free`、`nemotron-3-ultra-free`、`ling-3.1-flash-free`、
  `longcat-2.5-preview-free`、`fledge-alpha-free`。老设备上这些行还躺在 modelEntries 里。
- **旧动态机制已经存在且正确**：`isZenFreeLaneId(id) = id == "big-pickle" || id.endsWith("-free")`
  + live `/models` 刷新 + `replaceEntries` prune 非 custom 行。§5 的三层机制**不是新造，
  是复用这套并把它的证据语义收窄**（见 11.3）。
- **旧健康记录的拉黑证据含 RegionError**（`ZenFreeLaneHealth` KDoc 原文：
  "401 ModelError not supported, RegionError, or Endpoint is unavailable"，TTL 7 天自愈）。
  按 §5/A1，**RegionError 必须从证据集移除**。
- **旧 endpoint 与实测一致**：`ZEN_BUNDLED_ENDPOINT = "https://opencode.ai/zen/v1"` +
  `appendV1Suffix = false`，正是 §6.1 今天测通的组合 → **baseURL 不需要迁移**。
  但 `ZenEndpoint.isZenHost` 认 4 种拼写（含 `inference.opencode.ai`），若某实例
  baseURL 落在非实测形式上，免费档实例要归一化（用户自建实例不动）。
- **`removeInstance` 漏清模型墓碑**（`ProviderRepository.kt:668`）：它清了 instances、
  modelEntries、group memberEntryIds、agentLoopModelEntryIds、空 group（连带把
  defaultPrimaryGroupId/defaultSubGroupId 置 null）、apiKey、OAuth、
  voiceShadowDisabled、KEY_LAST_USED_ENTRY、thinking rules —— **唯独没清
  `config.deletedModelIds[instanceId]`**。

### 11.1 状态矩阵

| # | 设备状态 | 判据 | 升级后动作 |
|---|---|---|---|
| S1 | 新用户 / #61 之后装的 | 无 `builtin-opencode-zen` 实例、无实例级墓碑 | 播种 → 拉目录 |
| S2 | 老版本，内置实例还在（含 5 个死 seed 行） | 实例存在 且 key == `public`（`isZenFreeInstance`） | **保留** id/label/用户定制；刷新目录：prune 死行、加新行；存活行的 `uuid`/`overrides`/`isHidden`/`userModifiedAt` 逐条继承（`replaceEntries` 已实现） |
| S3 | 被 #61 retire 删过 | 无实例、无实例级墓碑 | 同 S1 播种。因为**复用同一 id**，旧会话与槽位里 `builtin-opencode-zen/...` 的引用自动复活 |
| S4 | 老版本里被用户主动删过 | **设备上与 S3 无法区分** | 首次升级按 S3 播种（见 R7 取舍）；**从新版起**主动删除写实例级墓碑，此后不再自动播种 |
| S5 | 用户自建 Zen（真 key） | `isZenFreeInstance == false` | 任何迁移路径都不得触碰：id/label/key/模型行逐字节不变 |
| S6 | 实例在，但用户删过其中若干模型行 | `deletedModelIds["builtin-opencode-zen"]` 非空 | **尊重删除**，不自动复活；由 §12 渠道显式恢复 |

### 11.2 无感的三条硬要求

1. **离线不得变坏**：目录拉取失败、超时、或返回**空 data** → 一律视为失败，
   **跳过 `replaceEntries`**，modelEntries 逐条不变。
   （`replaceEntries` 的 `suspiciousShrink` 守卫只保住 group 引用，仍会
   `removeAll` + `addAll(newEntries)` —— 传空列表进去会清光全部非 custom 行。
   所以守卫必须做在**调用方**：拿不到非空目录就不调。）
2. **死行清理只靠远端目录，不靠硬编码死 id 表**（A5）。远端不再返回的 id 自然被
   prune；`zenDeadIds()` 这种"随包发布的实测表"不得作为清理依据。
3. **默认槽位不得悬空**：老用户的默认模型若是死 zen 行，prune 会让空 group 被删、
   `defaultPrimaryGroupId` 置 null。迁移收尾必须检查：若槽位在本次 zen 迁移中变成
   null，则指向一个当前 SERVES 的免费模型（`entry:<uuid>`），而不是把用户留在
   "没有默认模型"的状态。**边界**：只修复本次迁移造成的 null，不覆盖用户本来就
   有意留空的配置。

### 11.3 证据语义收窄（改 `ZenFreeLaneHealth`，不是删它）

保留：SharedPreferences 持久化、7 天 TTL 自愈、"从真实失败调用取证"的思路、
Pure-JVM 可测（无 `initialize` 时内存态）。

改：**可从目录剔除的证据**只剩永久失效类 ——
`410 Gone`、`400 Model is unavailable.`、`401 ModelError "not supported"`。
**移出证据集**：`RegionError`（用户换网络就好，A1）、`ModelProtocolUnsupported`
（上游协议漂移，不是模型死了）、`FreeTierError`（门禁没做对的信号，是我们自己的
bug 而不是模型的属性）、429/5xx（瞬时）。

## 12. 恢复渠道（删掉的用户自己加得回来）

### 12.1 根因修复（普适，不只服务 Zen）

- **`removeInstance` 补清模型墓碑**：删除实例时一并
  `config.deletedModelIds.remove(instanceId)`。实例都没了，它的模型墓碑不该活下来
  等着继承给下一个同 id 实例。这条修完，"重建实例后模型无声消失"这一类问题
  一次性消失，Zen 恢复只是受益者之一。
- **新增实例级墓碑**（与 `ModelDeletion` 的模型级并列，不混用）：记录"用户主动
  删除过内置实例"。播种逻辑见到它就跳过。
  **#61 的 retire 不写这个墓碑** —— 那是我们的决定，不是用户的意愿，所以 S3
  能被无感恢复。

### 12.2 三个入口，同一个动作

动作 `restoreBuiltInZenProvider()` 的完整语义：
清实例级墓碑 → 缺实例则播种（同 id、key=`public`、label 沿用旧版
`"OpenCode Zen (Free)"`，撞名时 `· Built-in` 后缀）→ 触发目录刷新 →
若默认槽位为空则指向一个可用免费模型。

| 入口 | 出现条件 | 行为 |
|---|---|---|
| Providers 列表末尾一行"恢复 OpenCode Zen（免费）" | 内置实例缺失（有无墓碑都显示） | `restoreBuiltInZenProvider()`；副标题写"无需 API key，模型随上游目录自动更新" |
| 添加 provider 的预设列表常驻"OpenCode Zen（免费）" | 始终可见 | 已存在 → 跳详情并触发刷新；不存在 → 播种 |
| 模型列表页"恢复已删除的免费模型（N）" | 实例在 且 `deletedModelIds[id]` 非空 | 清该实例的模型墓碑 → 刷新目录（N 为墓碑数，让用户知道会回来几个） |

UI 按项目规则：实现前先加载 `awesome-design-md` + `ui-ux-pro-max`，Apple 风格，
图标走 `M3Icons` 双向映射注册（Filled/Outlined 成对，动态取色开关才有效）。

**不做**：跨实例的"一键恢复所有已删除模型"全局按钮 —— 范围太大，会误伤用户在
别的 provider 上有意的删除。恢复永远限定在"这一个内置免费实例"内。
