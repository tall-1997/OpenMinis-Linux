# M3-P0-3 实施计划：免费车道动态化

> 依赖：M2（凭据池轮换）✅ 已合并。M2 计划里给 M3 留的复用点：
> 同一渠道多账号 = 一个 ProviderInstance + 凭据池多 key，发送路径已全量走
> `requestCredential`，M3 调用时不需要改 M2 代码。

## 一、目标

免费车道从「编译期写死两条（Zen/Kilo），加车道 = 发版」进化为：

1. **车道清单动态化** — 车道实例层由清单（内置资产 + 可远程刷新）描述；
   对通用 OpenAI 兼容行为，新增一条免费车道 = 一条 JSON，零发版。
2. **车道级健康退避** — 整条车道被限流/挂掉时目录刷新自动跳过、选择器
   显示冷却徽章，时间到自动恢复。模型级死亡语义不动。
3. **accountToken 型车道池复用** — 按账号发免费额度的渠道 = 一个
   ProviderInstance + 凭据池多 key，轮换/冷却/UI/备份全走 M2 机制。

## 二、现状盘点（读码结论）

- 行为注册表：`FreeLanes.all = listOf(ZenFreeLane, KiloFreeLane)`，编译期。
- 实例层注册表：`BuiltinFreeLanes.ALL`（Zen/Kilo 描述符），被 6 个文件引用：
  FreeLaneBootstrap(4)、BuiltinLaneRetirement(3)、ProviderConfigStore(1)、
  ProviderDetailScreen(1)、AddProviderScreen(1)、FreeLaneRecovery(1)。
- 已有动态机制（直接复用）：目录缓存、增量探测、模型级死亡证据（7d TTL
  自愈 + recordSuccess 对冲）、learned.free/paid、恢复通道、删除墓碑、
  退役清理（RETIRED_IDS → removeInstance 全链）。
- 发送路径 16 处已走 `requestCredential`（池轮换 + 429/401 冷却 + 配额组）。
- `AccountPoolSection` 对所有非 OAuth 实例渲染 — 免费车道实例本就是
  `credentialType=apiKey`，池 UI 已就绪。
- 备份往返（collect/restore pooledKeys/quotaGroupId）已通用。
- Zen 门卫只认 `Bearer public`、Kilo 拒绝一切 token — 现有两条车道都是
  单凭据约定，池机制没有实证对象。

## 三、分块

### 第 1 块：车道清单动态化（核心）

- **`assets/free_lanes.json`**：内置清单，含 zen/kilo 两条（与编译期常量
  一致的实例层描述）。
- **`FreeLaneManifest`**（provider 包）：`ManifestLaneEntry` 数据类 +
  纯函数解析器（JUnit 可测）+ `assets(context)` / `refreshed(context)`
  加载器。远程刷新产物放 `filesDir/free_lanes.json`，成功解析才生效；
  解析失败 = 内置资产兜底。
- 条目字段：`id` / `behavior`(zen|kilo|openaiCompat) / `instanceId` /
  `label` / `baseURL` / `credentialConvention`(none|fixed|accountToken) /
  `credentialValue` / `offlineSeedIds`。
- **`ManifestLane`**（新 FreeLane 实现，参数化）：openaiCompat 行为 —
  OpenAI 兼容上游 + 通用错误方言（对齐 Kilo 的保守规则：401/403 裸状态
  不落永久判定，结构化词才定 PAID/DEAD）。
- **`BuiltinFreeLanes.fromManifest(entries)`**：清单条目 + 行为注册表
  （`FreeLanes.byId`）合成 `BuiltinFreeLane` 列表；`ALL` 常量保留 zen/kilo
  作编译期兜底，合成列表 = ALL + 清单里的非内置条目（去重 by instanceId）。
- **seed 入口**：`seedBuiltInFreeLanes` 改用合成列表；远程清单刷新成功后
  重跑 seed pass → 新车道实例自动 seed（墓碑/恢复/footer/preset 全自动）。
- **动态退役**：`filesDir/free_lanes_active.json` 持久化「上次 seed 成功的
  活跃 instanceId 集」；`BuiltinLaneRetirement.instancesToPurge` 增补规则 —
  `builtin-*` 前缀、不在活跃集、不在 RETIRED_IDS 静态豁免 → 退役。
  远程清单文件缺失/解析失败 = 活跃集取持久化值，车道不消失。
- 验收：仓库测试里用 openaiCompat 清单条目 seed 出第三条车道，全链
  （seed/识别/恢复/退役）跑通，不改 bootstrap 代码。

### 第 2 块：accountToken 池复用（M2 零改动）

- `credentialConvention: accountToken` 条目 → `apiKey=""`（不写占位）、
  池可编辑；`none` 型（Kilo）池 UI 隐藏（贴了 token 上游必 401）。
- `ManifestLane.owns` 对 accountToken 型 = host 匹配（真 key 也是我们的
  车道，这是多账号语义本身）。
- 发送路径零改动（M2 承诺验证）；补测试钉住：accountToken 车道
  requestCredential 轮换顺序、冷却跳过、备份往返。

### 第 3 块：车道级健康退避

- `FreeLaneHealth` 增加车道级聚合：非死亡证据（RATE_LIMITED/SERVER/
  NETWORK）→ 滑动窗口计数（10 分钟、阈值 3）；`laneInCooldown(laneId)`
  返回剩余退避时间；`recordSuccess` 降级为「恢复」对冲之一。
- 探测失败聚合点零侵入：classify 已把三类证据喂给 `recordFailure`
  （marksDead=false 被丢弃），聚合加在那里。
- **refresh 短路**：`OpenAIModelsApi.fetchModels` 对冷却中的免费车道跳过
  网络拉取 → `FreeLaneDirectory.resolve` 空载荷分支自动走缓存兜底
  （已有路径，不是新机制）。
- **UI 徽章**：ProviderListScreen 免费车道行「冷却中」徽章（先读
  awesome-design-md + ui-ux-pro-max，Apple 风格，M3Icons 双向注册）。

## 四、执行节奏

1. plan + design 文档进 `docs/superpowers/`。
2. 分支 `feat/m3-free-lane-dynamization`，三块各一个提交（1 → 3 → 2）。
3. 每块配套 JUnit 测试：清单解析/合成/退役、车道退避窗口、accountToken
   轮换+备份。
4. 四道本地门禁全绿 → push → PR Checks 绿 → squash 合并 → 删分支。
5. UI 部分开工前先读 awesome-design-md + ui-ux-pro-max。

## 五、不变式

1. **零迁移**：无远程清单文件的设备 = 现状（内置两条 + 编译期全链）。
2. **清单失败不丢车道**：远程清单解析失败 → 内置兜底 + 活跃集持久化兜底。
3. **退役只碰 builtin- 实例**：用户自建 provider 永不入选（#211 测试语义
   延伸）。
4. **模型级语义不动**：车道退避是 refresh 级短路，不是模型级证据。
5. **M2 零改动**：池/轮换/冷却/备份代码一个字节不碰，只新增调用方。
