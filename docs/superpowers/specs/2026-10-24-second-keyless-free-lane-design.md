# 第二条内置零凭据免费车道（Kilo）+ 把"免费车道"从 Zen 特例变成概念

日期：2026-10-24
状态：待实现
前置：PR #139（免费车道恢复）、#141（未映射 error type 不得变成凭据裁决）

---

## 1. 起因

Zen 免费车道今天在本机实测被限流到 **retry-after 82790 秒（≈23 小时）**：

```
429 ra=82795 {"type":"error","error":{"type":"FreeUsageLimitError",
  "message":"Free usage limit reached. Subscribe to OpenCode Go to keep going"}}
```

#141 已经保证了这个 429 **不会**把模型误判成付费/死亡（分类正确，证据不落盘成裁决）。
但分类正确不等于车道可用：用户面对的仍然是"整条免费车道当天不能用"。

同一个下午，Kilo 的公共网关零凭据应答了 15/17 个免费模型，中位 ~1s，18 次连发无一被限流。
所以本次要做的不是修 Zen，而是**让内置免费车道不止一条**——一条被榨干时另一条还在。

## 2. 活体证据（本机 2026-10-24，全部实测）

### 2.1 Zen（`https://opencode.ai`）

| 探测 | 结果 |
|---|---|
| `/zen/v1/chat/completions` + 4 工具 + prod UA + stream | 429 `FreeUsageLimitError` ra≈82790s |
| `/inference/v1/chat/completions` 同形状 | 429 同形 → **两条路径今日等价，endpoint 不需迁移** |
| `/zen/v1` + `[read]` 单工具 | 429（过了 gate） |
| `/zen/v1` **无 tools** | **403 `FreeTierError`** → gate 仍按 body 形状判定 |
| `/zen/v1` + `[read]` + `stream:false` | **403 `FreeTierError`** → 必须流式 |
| `/zen/v1` + `[read]` + 无 UA 头 | **Cloudflare HTML 拦截页** → UA 必需 |
| `/zen/v1` + `x-opencode-request:1` | 429（过 gate）→ 上游文档说的 v1 头 403 今日不成立 |
| `/zen/v1/models`、`/inference/v1/models` | 200，85 个模型，**9 个免费** |

Zen 今日免费 id：`big-pickle`、`beam-free`、`flint-40b-free`、`mimo-v2.6-flash-free`、
`nemotron-3.5-super-vl-free`、`nemotron-3.5-lightning-free`、`jev-1.13-free`、
`deepseek-v4.1-flash-free`、`glm-5.3-flash-free`。
现有规则（`-free` 后缀 + `big-pickle`）**9/9 全中**，无需硬编码清单。

### 2.2 Kilo（`https://api.kilo.ai/api/gateway`）

- `GET /models` → 200，**402 个模型，其中 17 个 `isFree: true`**
- 17 个免费 id 逐个打真请求 → **15 个 200**（中位 ~900ms）
  - `poolside/laguna-xs-2.1:free` → 429 "temporarily rate-limited upstream"（transient）
  - `nvidia/nemotron-3-super-120b-a12b:free` → **404**，`error.metadata.raw` = `Function ... Not found for account`（上游 provider function 已移除 = 真 dead）
- 连发 10 次同一模型 + 4 轮两模型交替 = **18/18 全 200**，无墙
- 工具调用可用（正确返回 `tool_calls` + `reasoning` + `reasoning_details`）
- **非流式也可用**（与 Zen 不同，Kilo 不要求 `stream:true`）
- `reasoning_effort` 被接受
- SSE 里有 `: KILO PROCESSING` **注释行**（调度期间发送）
- 17 个免费模型**全部**声明 reasoning；5 个带 image 输入；1 个带 audio；上下文窗口 65K–1M
- **17 个全部 `mayTrainOnYourPrompts: true`**

### 2.3 Kilo 鉴权边界（决定实现方式）

| 请求头 | 结果 |
|---|---|
| 无 auth 头 | **200** |
| `Authorization: Bearer`（无 token） | **200** |
| `Authorization:`（空值） | **200** |
| `api-key: bogus` | 200 |
| `Authorization: Bearer public` | **401 `{"error":{"code":"INVALID_TOKEN"},"error_type":"authentication_required"}`** |

**任何非空假 token 都被当真凭据校验。** Zen 用的占位符 `public` 打到 Kilo 上会直接 401。
→ Kilo 实例必须**完全不存凭据**，不能照抄 Zen 的 `saveApiKey(id, "public")`。

### 2.4 付费 id 与未知 id 同形

```
401 {"error":{"code":"PAID_MODEL_AUTH_REQUIRED"},"error_type":"paid_model_auth_required"}
```

付费模型和**打错的 id** 返回同一个形状。→ 只能判"不在免费池"，**绝不能判 dead**
（否则一个拼写错误就把模型永久藏起来，正是 #141 修的那类错误）。

### 2.5 死模型在目录里看不出来

`nvidia/nemotron-3-super-120b-a12b:free`（404）与 `nvidia/nemotron-3.5-lightning:free`（200）
的 listing 条目**逐字段同形**：同样 `isFree: true`、同样有 `top_provider`、`supported_parameters`、
`opencode.variants`。目录层没有任何信号。
→ **健康存储对 Kilo 不是可选优化，是必需品**：只有真请求的 404 能判死。

## 3. 现有架构已经支持的（不需要新传输层）

逐条核对过，Kilo 走现成的 OpenAI 兼容路径即可：

| 能力 | 现有实现 | 对 Kilo |
|---|---|---|
| 空凭据是合法配置 | `ProviderInstance.allowsEmptyAPIKey` = apiKey 模式 + 非空 customBaseURL + openAI/anthropic | Kilo 实例天然合格 |
| 空凭据不被 refresh 跳过 | `usableApiKey()` 对该形状返回 `""` 而非 null（`[T-android-refresh-models-empty-key]`） | ✓ |
| 空凭据不发 auth 头 | `OpenAIProvider.applyKeyAuth`：`if (token.isEmpty()) this`（`[T-empty-key-compat-endpoints]`） | ✓ 正好是 Kilo 要求的 |
| UI 显示"无需密钥" | `ProviderInstanceRow` → `provider_no_key_required` | ✓ 已有文案 |
| 算作"已配置" | `ProviderListScreen:116` `!apiKey.isNullOrBlank() \|\| allowsEmptyAPIKey` | ✓ |
| SSE 注释行 | `SseEventParser.feed`：`if (line.startsWith(":")) return null`（按规范） | ✓ `: KILO PROCESSING` 安全 |
| 上下文/输出长度 | `EndpointModelLimits.read` 已读 `context_length` + `top_provider.max_completion_tokens` | ✓ Kilo 用的正是这两个字段名 |
| 模态 | 解析循环已读 `architecture.input_modalities/output_modalities` | ✓ 5 个视觉模型可路由 |
| 带 `/` 和 `:` 的 id | `ProviderKeyGate.normalizeModel` 早已处理 `|`→`/` | ✓ OpenRouter 同形状先例 |
| Zen gate 不会误伤 Kilo | `ZenFreeLaneGate` 只对 `ZenEndpoint.isZenHost` 生效 | ✓ 主机名不同 |

**结论：新增一条车道 = 新增一个内置实例 + 目录收窄 + 证据方言，不改传输。**

## 4. 根因级改动（5 项）

### R1 免费车道从"Zen 特例"变成有 N 个实现的概念

`OpenAIModelsApi:146` 现在是硬编码单车道判断：

```kotlin
val zenFreeLane = ZenFreeLaneDirectory.isKeylessLane(apiKey) && ZenEndpoint.isZenHost(baseURL)
...
if (zenFreeLane) ZenFreeLaneDirectory.resolve(context, baseURL, enriched) else enriched
```

再加一条车道就要在这里写 `||`，第三条继续叠。这是特例堆叠，不是抽象。

改法：`FreeLane` 接口 + `FreeLanes` 注册表。车道自己声明三件事——
1. **归属**：`owns(baseURL, apiKey)`（主机 + 零凭据；用户自建带真 key 的实例不算）
2. **目录裁决**：从 listing 的原始 JSON 对象判免费（Zen：id 命名规则；Kilo：`isFree` 标志）
3. **证据方言**：`classify(id, code, body) -> Verdict`（Zen：嵌套 `error.type`；Kilo：`error.code` + `error_type`）

共享的是**流程**（收窄 → 离线兜底 → 后台增量探测 → 证据落盘），不是**规则**。
`ZenFreeLaneGate` / `ZenSession` / `ZenEndpoint` 保持 Zen 专属命名——它们确实只属于 Zen。

### R2 健康存储的键比它记录的身份窄（与 PR #140 同类根因）

`ZenFreeLaneHealth` 的 `dead_ids` 是**全局** `id → timestamp`。两条车道共用时，
同一 id 在一条车道上死了会在另一条被藏起来，而且无从区分是谁记的。

今天不会撞（Zen 是裸名 `beam-free`，Kilo 是 `owner/name:free`），但"今天不会撞"
不是不变式——#140 的"一个会话两块板"、#141 的"未映射 type 落穿"都是同一类：
**存储/判定的键比它承载的身份窄**。

改法：`ZenFreeLaneHealth` → `FreeLaneHealth`，持久化键带 laneId。
迁移：读到的旧扁平键（无前缀）一律归 Zen，因为 Zen 是唯一写过的车道。单向、无损。

同时把证据词表变成车道中立的共享词表（`ZenFailureEvidence` → `FreeLaneEvidence`）：
- 复用 `GONE`：Zen 的 410 与 Kilo 的 404 "Function not found for account" 语义相同（上游撤了模型）
- 复用 `RATE_LIMITED`：Zen 429 `FreeUsageLimitError` 与 Kilo 429 "rate-limited upstream"
- 新增 `CREDENTIAL_REJECTED`（**marksDead = false**）：Kilo 401 `INVALID_TOKEN`。
  这是**我们自己的 bug**——零凭据车道上发出了非空 token。记成模型死亡会把我们的错误
  变成用户的模型消失，正是 #141 立的规矩：我们的 bug 不是模型的属性。

### R3 模型列表请求无条件发送 `Authorization: Bearer $apiKey`

`OpenAIModelsApi:112`：

```kotlin
.header("Authorization", "Bearer $apiKey")
```

而 chat 路径的 `applyKeyAuth` 明确决定"空 key 不发头"，注释写着
"strict gateways reject the empty form, an absent header is universally fine"。
**同一个决定在两个地方不一致。** Kilo 容忍 `Bearer `（实测 200），严格网关不容忍。

改法：模型列表走同一条规则。一处决定，处处生效。

### R4 `supportsReasoning` 靠硬编码 id 前缀猜，而端点已经权威声明

`OpenAIModelsApi` 现在：

```kotlin
val knownReasoning = idLower.startsWith("gpt-5") || idLower.startsWith("o1") ||
    idLower.startsWith("o3") || idLower.startsWith("o4") || idLower.contains("codex")
```

端点其实说了：`supported_parameters` 含 `reasoning` / `reasoning_effort`，
`opencode.variants` 还给出具体 effort 档位（lightning 是 `instant`/`thinking(high)`，
super 是 `none`/`low`/`medium`/…）。

17 个 Kilo 免费模型**全部**声明 reasoning，但**没有一个**匹配上面的前缀
→ thinking pill 全关，用户拿不到推理能力，而元数据就在响应里躺着。

改法：**端点声明优先于前缀猜测**，前缀猜测降为端点没说话时的兜底。
这对所有 OpenRouter 兼容网关都生效（`isCustomBase` 那一类），不是 Kilo 专属补丁。
`opencode.variants` → `reasoningEffortValues`，没有档位但声明了 reasoning → `supportsReasoning = true`。

### R5 免费池训练告知

Kilo 全部模型 `mayTrainOnYourPrompts: true`。用户可能把密钥、隐私、客户数据发进
一个会被上游用于训练的池子，而界面上一个字都没提。

改法：车道级隐私告示，出现在**决定点**上——
- 恢复行的副标题（用户点"恢复"之前就看到）
- 该内置实例的详情页常驻一行

不做每次发送前弹窗（侵入性过高，且用户已经选定了模型）。

## 5. Kilo 车道语义

| 面 | 取值 |
|---|---|
| 实例 id | `builtin-kilo-free` |
| 标签 | `Kilo Free` |
| endpoint | `https://api.kilo.ai/api/gateway`（`appendV1Suffix = false`，与 Zen 同理） |
| providerType / credentialType | `openAI` / `apiKey` |
| 凭据 | **不存**（`saveApiKey` 不调用；空 key 才是正确配置） |
| 归属判定 | `id == INSTANCE_ID` 或（Kilo 主机 且 凭据为空） |
| 免费裁决 | listing 的 `isFree == true`（服务端声明，强于命名规则） |
| 需要探测吗 | 需要，但只为**收集死亡证据**：免费性由 listing 决定，不由探测决定 |
| 探测节奏 | 后台、增量、只对没有健康记录的 id；永不阻塞刷新 |
| dead | 404 + "Not found for account" → `GONE` |
| transient | 429 → `RATE_LIMITED`，`UNKNOWN` 裁决，不落盘 |
| 不在免费池 | 401 `PAID_MODEL_AUTH_REQUIRED` → `PAID` 裁决（隐藏，**不判死**；付费 id 与打错的 id 同形） |
| 我们的 bug | 401 `INVALID_TOKEN` → `CREDENTIAL_REJECTED`，`UNKNOWN` 裁决，大声记日志 |
| 未映射 `error_type` | `UNSPECIFIED_ERROR_TYPE`，`UNKNOWN`（#141 的不变式直接继承） |
| 离线兜底 | 解析为空 → 走既有 cache fallback（非 Zen 车道的空解析本来就保留旧条目） |

**乐观纳入，证据剔除**：listing 说免费就先给用户看见，后台探测发现死了下次刷新才消失。
与 Zen 的规则形 id 立即纳入是同一套 UX。

## 6. 不做的事

- **不迁移 Zen endpoint**：`/zen/v1` 与 `/inference/v1` 今日同形，`ZenEndpoint` 已同时认两者
- **不给 Kilo 造 wire gate**：它不要求 body 改写、不要求流式、不要求会话头
- **不硬编码 Kilo 免费清单**：`isFree` 是服务端声明，清单会漂（Zen 的 8 个硬编码里 5 个已死就是教训）
- **不把 Kilo 探测接到 live 流量**：与 Zen 同理，`recordSuccess` 只接探测裁决；
  但 `recordFailure` 可以接 live——错误 body 的方言是自解释的，与 200 形拒答不同
- **不做跨车道"恢复所有已删除模型"**：误伤别的 provider 上有意的删除（#139 已定）

## 7. 测试点

1. `FreeLaneHealth` 键隔离：同一 id 在 lane A 判死，lane B 仍可用；旧扁平键迁移归 Zen
2. `CREDENTIAL_REJECTED.marksDead == false`（穷尽 when 由编译器强制）
3. Kilo `classify`：404+"Not found for account"→DEAD；429→UNKNOWN；401 PAID_MODEL_AUTH_REQUIRED→PAID；401 INVALID_TOKEN→UNKNOWN；未映射 error_type→UNKNOWN
4. Kilo 目录：402 个 listing 收窄到 17；带真 key 的用户自建实例不收窄
5. 归属判定：空凭据 + Kilo 主机 = 我们的；真 key + Kilo 主机 = 用户的，永不触碰
6. 播种/恢复/删除墓碑：与 Zen 六状态矩阵同形（S1–S6）
7. R3：空 key 时模型列表请求**不含** Authorization 头
8. R4：`supported_parameters` 含 reasoning → `supportsReasoning = true`；`opencode.variants` → effort 档位；端点没声明时前缀兜底仍生效（gpt-5 等不回归）
9. 反向验证：python 镜像模拟器 `--regress` 必须精确复现失败，证明不是永绿

## 8. 落地顺序

1. python 镜像模拟器先跑通两条车道的 classify + 健康键隔离 + 目录收窄（本地无 JDK）
2. `FreeLaneEvidence` / `FreeLaneHealth`（含迁移）→ 改 Zen 调用点
3. `FreeLane` 接口 + `FreeLanes` 注册表；Zen 实现收进去
4. `KiloFreeLane` + `KiloFreeLaneBootstrap` + 目录/探测
5. `OpenAIModelsApi` 换成注册表驱动 + R3 + R4
6. `ProviderRepository` 播种/恢复/遗忘；`KiloFreeLaneRecovery`
7. UI：恢复行 + 详情页隐私告示 + 图标（Apple 风格，先读 awesome-design-md / ui-ux-pro-max）
8. 字符串 × 全 locale
9. 四道门禁 + 花括号/KDoc/符号导入扫描 → 分支 → push → PR → CI 绿 → squash → 验增量 → 删分支
