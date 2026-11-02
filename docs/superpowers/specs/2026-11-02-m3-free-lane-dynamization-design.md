# M3-P0-3 设计：免费车道动态化

> 与 [实施计划](../plans/2026-11-02-m3-free-lane-dynamization.md) 配套。
> 分块顺序：① 清单动态化 → ③ 车道健康退避 → ② accountToken 池复用。

## 1. 分层模型

免费车道拆成两层，清单只动「实例层」，行为仍编译期注册：

```
行为层（编译期，FreeLane 接口实现）
  ZenFreeLane      — zen 方言 + 门卫，object
  KiloFreeLane     — kilo 方言，object
  ManifestLane     — openaiCompat 通用行为，参数化 class

实例层（清单驱动，BuiltinFreeLane 描述符）
  assets/free_lanes.json          内置清单（随 APK 版本更新）
  filesDir/free_lanes.json        远程刷新产物（成功解析才生效）
  filesDir/free_lanes_active.json 上次 seed 成功的活跃实例集（退役判定输入）
```

选择把行为留在编译期的原因：`FreeLane.classify` 的错误方言是车道身份的一半
（Zen 的 `type` 方言 vs Kilo 的 `code` 方言，以及 2026-10-17 Zen 退役和
Kilo 404 陷阱的教训都写在各自 classify 里），JSON 描述不了这种逻辑。
清单驱动的是「哪些实例、指向哪个上游、什么凭据约定」，而「上游说什么话」
由 behavior 字段选择已知实现。全新方言 = 新增一个行为实现（发版一次），
之后同一行为的所有新上游 = 纯清单（零发版）。

## 2. 清单格式（v1）

```json
{
  "version": 1,
  "lanes": [
    {
      "id": "zen",
      "behavior": "zen",
      "instanceId": "builtin-opencode-zen",
      "label": "OpenCode Zen (Free)",
      "baseURL": "https://opencode.ai/zen/v1",
      "credentialConvention": "fixed",
      "credentialValue": "public",
      "offlineSeedIds": ["deepseek-v4.1-flash-free", "big-pickle"]
    },
    {
      "id": "kilo",
      "behavior": "kilo",
      "instanceId": "builtin-kilo",
      "label": "Kilo (Free)",
      "baseURL": "https://api.kilo.ai/api/gateway",
      "credentialConvention": "none",
      "offlineSeedIds": ["kilo-auto/free", "nvidia/nemotron-3.5-lightning:free"]
    },
    {
      "id": "example-account-lane",
      "behavior": "openaiCompat",
      "instanceId": "builtin-example-account-lane",
      "label": "Example (Free)",
      "baseURL": "https://free.example.com/v1",
      "credentialConvention": "accountToken",
      "offlineSeedIds": ["example/free-model"]
    }
  ]
}
```

字段契约：

| 字段 | 语义 |
|---|---|
| `id` | 车道行为 id（FreeLane.id / 健康证据 scope）。openaiCompat 条目任意唯一串 |
| `behavior` | `zen` \| `kilo` \| `openaiCompat`，行为注册表 `FreeLanes.byId` 解析 |
| `instanceId` | 配置实例 id（stable forever）。内置两条沿用历史 id |
| `label` | 显示名（labelFor 冲突后缀逻辑不变） |
| `baseURL` | 覆盖行为默认 baseURL；openaiCompat 必填 |
| `credentialConvention` | `none`（上游拒绝一切 token）/ `fixed`（写死占位凭据）/ `accountToken`（按账号发额度，用户自填，池多 key） |
| `credentialValue` | `fixed` 型要写入的凭据值（Zen: `public`） |
| `offlineSeedIds` | 首装无网时的骨架模型 id |

**有效性规则**（解析器拒绝、拒绝结果不落盘不生效）：
- `version == 1`；`lanes` 非空数组。
- `instanceId` 全局唯一、`^builtin-[a-z0-9-]+$`（退役判定依赖前缀）。
- `behavior` 必须在行为注册表里；`openaiCompat` 必须带 `baseURL`。
- `credentialConvention` 合法枚举；`fixed` 必须带非空 `credentialValue`。
- 内置两条（zen/kilo）的 `instanceId` 若出现在远程清单里，值必须与
  编译期一致（历史引用靠它复活，改了就串线）。

## 3. 关键机制

### 3.1 清单合成与 seed

```
BuiltinFreeLanes.fromManifest(entries) = ALL ∪
    entries.filter { it.instanceId !in ALL.instanceIds }
           .map { it.toBuiltin() }
```

`toBuiltin()` 用 `FreeLanes.byId(behavior)` 拿行为（zen/kilo 返回 object；
openaiCompat 返回 `ManifestLane(entry)`，baseURL 从条目来）。

`ProviderConfigStore.seedBuiltInFreeLanes()` 的遍历源从
`BuiltinFreeLanes.ALL` 换成合成列表；远程刷新成功后（后台 IO）重跑一次。
`FreeLaneBootstrap.ensureSeeded` 签名不变——它是按列表遍历的纯函数，
合成列表是输入而非全局，纯函数可测性保持。

### 3.2 动态退役

`BuiltinLaneRetirement.instancesToPurge(config, activeInstanceIds)`：

```
purge = config.instances.map { it.id }
    .filter { it.startsWith("builtin-") }
    .filter { it !in activeInstanceIds }
    .filter { it !in RETIRED_IDS }        // 静态豁免（历史退役 id 由 removeInstance
                                          // 的 tombstone 语义负责，不进活跃集）
```

`activeInstanceIds` 的来源（按优先级）：
1. 本次成功解析的合并清单 instanceId 集；
2. 否则 `filesDir/free_lanes_active.json` 持久化集（上次 seed 成功的）；
3. 否则 `BuiltinFreeLanes.ALL` 编译期集。

seed pass 成功写入活跃集（合并清单里全部条目）。语义保证：远程清单
解析失败时（网络坏/JSON 坏）车道一个不丢；远程清单成功且删了某车道
→ 下次启动该 `builtin-*` 行被 removeInstance 全链清理。用户自建 provider
id 不带 `builtin-` 前缀，永不入选。

### 3.3 车道级健康退避（第 3 块）

`FreeLaneHealth` 增加车道级聚合（与模型级 store 并存）：

```
LANE_WINDOW_MS   = 10 分钟
LANE_THRESHOLD   = 3 次
recordFailure(lane, id, evidence):
    # 既有模型级逻辑不变（marksDead 才隐藏）
    if (!evidence.marksDead && evidence in {RATE_LIMITED, SERVER, NETWORK})
        laneEvents[lane] += now            # 窗口内计数
laneInCooldown(laneId): Long? =
    窗口内计数 >= LANE_THRESHOLD ? 剩余退避时间 : null
recordSuccess(lane, id):
    # 既有逻辑 + laneEvents[lane].clear()   # 车道级恢复对冲
```

- 退避时长 `LANE_COOLDOWN_MS = 10 分钟`，从最近一次事件起算。
- 聚合点：classify 的 `hide()` 已把 RATE_LIMITED/SERVER/NETWORK 喂进
  `recordFailure`（只是 marksDead=false 不隐藏模型），车道级计数就加在
  recordFailure 内，**车道实现零改动**。
- `OpenAIModelsApi.fetchModels` 在拉目录前短路：
  `lane != null && FreeLaneHealth.laneInCooldown(lane.id) != null` → 直接
  返回 fallback（缓存），`FreeLaneDirectory.resolve` 的离线兜底路径原样
  接住。冷却期不发一个字节网络请求。
- 探测循环顺带被短路覆盖（探测只在 refresh 后启动）。
- 进程级内存结构，不持久化（重启 = 干净，对齐 M2「冷却非持久化」）。

### 3.4 accountToken 池复用（第 2 块）

- 描述符映射：`credentialConvention` → `BuiltinFreeLane.apiKey`：
  `fixed` → credentialValue；`none`/`accountToken` → ""（不写 store）。
- `poolEligible`（BuiltinFreeLane 新派生属性）= convention == accountToken。
- `ManifestLane.owns(baseURL, apiKey)`：host 匹配即 owned，apiKey 不参与
  （真 key 也是我们的车道 = 多账号语义）。fixed 型保持编译期实现语义。
- UI（ProviderDetailScreen）：accountToken 车道显示池区块（现状已渲染，
  验证 + 补齐引导文案）；none 型隐藏池区块（贴 token 上游必 401，
  防呆）。
- 发送路径：`requestCredential` 已在 16 处接线，池语义对 accountToken
  车道天然成立（primary 空 + 池多 key → 轮换 + 冷却 + 配额组）。
- 备份：collect/restore 已通用，测试钉住免费车道实例的往返。

### 3.5 ManifestLane 方言（openaiCompat，保守对齐 Kilo）

- `declaredFree = serverSaysFree == true`；`discoversUndeclaredIds = false`
  （上游清单声明即权威，探测只收集 liveness——Kilo 同款语义）。
- classify：
  - 200 → FREE；404/410 → DEAD(GONE)；400 且 body 含 "unavailable" →
    DEAD(MODEL_UNAVAILABLE)；
  - 结构化词：`PAID_MODEL_AUTH_REQUIRED` / message 含 "paid"|"subscription"
    → PAID(FREE_TIER)；`INVALID_TOKEN` → UNKNOWN(CREDENTIAL_REJECTED)；
  - 429 → UNKNOWN(RATE_LIMITED)；5xx → UNKNOWN(SERVER)；
  - 裸 401/403（无结构化词）→ UNKNOWN（对齐 Kilo：状态码不是模型陈词）。
- 探测头：accountToken 型探测不带 Authorization（匿名探测上游免费模型）？
  不——探测头由行为决定。openaiCompat 探测头 = 空（同 Kilo），因为探测
  目的是「匿名可达性」。约定：accountToken 型车道的免费判定由清单声明
  （serverSaysFree），与探测头无关。

## 4. 测试面

| 文件 | 用例 |
|---|---|
| `FreeLaneManifestTest` | 合法 v1 解析；version≠1 拒绝；instanceId 重复/非法前缀拒绝；openaiCompat 缺 baseURL 拒绝；fixed 缺 credentialValue 拒绝；内置 id 篡改拒绝；合并去重 |
| `ManifestLaneTest` | owns 按 host；classify 各方言词；declaredFree/discoversUndeclaredIds；探测头为空 |
| `BuiltinLaneRetirementTest`（扩展） | builtin- 前缀 + 不在活跃集 + 不在 RETIRED_IDS → purge；用户 id 永不入选；活跃集内不 purge |
| `FreeLaneHealthTest`（扩展） | 窗口计数/阈值/退避剩余时间；recordSuccess 对冲；模型级语义不变 |
| `FreeLanePoolTest`（新） | accountToken 车道 requestCredential 轮换/冷却跳过/配额组；备份往返 |

## 5. 不动的东西

- `FreeLane` 接口（Zen/Kilo 实现一个字节不碰）。
- `FreeLaneDirectory` / `FreeLaneCatalogCache`（resolve 语义不变）。
- M2 全部（池/轮换/冷却/备份/UI 区块）。
- 模型级死亡证据语义（7d TTL / recordSuccess / marksDead）。
- `ProviderRepository` 架构天花板：新代码放新文件 + 扩展函数，ratchet 只降不升。
