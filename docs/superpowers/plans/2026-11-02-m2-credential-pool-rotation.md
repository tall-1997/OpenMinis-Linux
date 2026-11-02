# M2-P0-2 实施计划：同厂牌多账号轮换

- 日期：2026-11-02
- 依据：implementation-plan-p0p1p2.md §M2
- 基线：main @ 3d599716（M1 刚刚合并）
- 流程约束：独立分支 → 提交 → PR → CI 绿 → squash 合并 → 验证增量非零 → 删分支；本地只用 python/kotlinc 语法层验证；UI 改动先加载 awesome-design-md + ui-ux-pro-max（Apple 风格）；功能改动先加载 superpowers + android-skills；普适性修复，根因优先

## 一、核心变化（一句话）

每个 ProviderInstance 支持多个 API Key，请求时按顺序轮换；429/401/额度错误时冷却当前 key 并跳过，同 quotaGroupId 的实例共享冷却。

## 二、文件清单与分工

### 2.1 `data/repository/ProviderCredentialPool.kt`（新文件，180 行）

**职责**：凭据池的读写与轮换逻辑。不动现有的单 key 存储（`apikey_<instanceId>`），池是附加层。

```
数据模型：
  EncryptedPrefs key: "credpool_<instanceId>" → JSON Array ["key1", "key2", ...]

API：
  fun add(instanceId: String, key: String)    // 追加到池尾（不重复添加相同 key）
  fun remove(instanceId: String, index: Int)  // 按位置移除
  fun keys(instanceId: String): List<String>  // 只读列表
  fun clear(instanceId: String)               // 清空池
  fun hasPool(instanceId: String): Boolean    // 该实例是否启用多账号
  fun importFrom(instanceId: String, keys: List<String>)  // 备份恢复用

冷却表（进程内，同 M1 GroupRoutingState 模式）：
  // instanceId + index → cooldownUntil epoch ms
  private val credentialCooldowns = ConcurrentHashMap<String, Long>()
  fun markCooled(instanceId: String, keyIndex: Int, untilMs: Long)
  fun isCooled(instanceId: String, keyIndex: Int): Boolean
  fun resetForTest()
```

**开关**：池为空时（`keys().isEmpty()`），`loadApiKey` 退回现存单 key 逻辑——零迁移，老用户无感。

### 2.2 `ProviderConfigStore` 改动（+35 行）

```kotlin
// 现有方法（不动）：
fun loadApiKey(instanceId: String): String?          // 返回 apikey_<id> 单 key
fun usableApiKey(instance: ProviderInstance): String? // 单 key + allowsEmptyAPIKey 兼容

// 新增：
fun poolDepth(instanceId: String): Int = pool.keys(instanceId).size
fun usableApiKeyFromPool(instance: ProviderInstance, cursor: Int): String? {
    // 有池 → 从池取第 cursor 个；空池 → 退回单 key
    val poolKeys = pool.keys(instance.id)
    if (poolKeys.isNotEmpty()) {
        val idx = Math.floorMod(cursor, poolKeys.size)
        return if (pool.isCooled(instance.id, idx)) null else poolKeys[idx]
    }
    return usableApiKey(instance)  // 零迁移路径
}
```

**原子递增游标**：`ProviderCredentialPool.nextCursor(instanceId): Int` 返回当前值并原子+1（AtomicInteger，进程内 restart 从 0）。

### 2.3 `ProviderInstance` 新增字段（+6 行）

```kotlin
// [T-credential-pool-x]
var quotaGroupId: String? = null,  // 同组共享冷却；默认 null = 单账号组
```

- `@Serializable` 默认 null，旧 JSON 反序列化安全。
- `quotaGroupId` 为 null 时，冷却只作用于自身；非 null 时，任一 key 429 → 同一 quotaGroupId 的全部实例的对应 key 同冷。

### 2.4 请求层错误冷却（`GroupRoutingState.kt` +25 行）

M1 已有 `markServingCooldownFor(groupId, error)` 按 entry 冷却。M2 新增**凭据级冷却**：

```kotlin
/**
 * [T-credential-pool-x] Park the serving credential after a 429 so the
 * same key is not retried on the next turn. If this instance belongs to a
 * quotaGroup, every other instance in the same group also cools its
 * matching key index.
 */
fun markCredentialCooldownFor(
    instanceId: String,
    keyIndex: Int,
    quotaGroupId: String?,
    error: LLMError,
    pool: ProviderCredentialPool,
) {
    val retryAfterMs = (error as? LLMError.RateLimited)
        ?.retryAfterSeconds?.takeIf { it > 0 }?.let { it * 1_000L }
        ?: DEFAULT_COOLDOWN_MS
    val untilMs = System.currentTimeMillis() + retryAfterMs
    pool.markCooled(instanceId, keyIndex, untilMs)
    // 同 quotaGroup 全冷
    if (quotaGroupId != null) {
        // 遍历同组其他实例，冷却同位置 key
        ... 
    }
}
```

**调用点**：`ChatViewModelAgentLoopExt.kt` 现有 429 处理分支（~1164~1300 行），在 markServingCooldownFor 之后追加 `markCredentialCooldownFor(instanceId, keyCursorAtSend, group.quotaGroupId, error, pool)`。

### 2.5 发送路径游标追踪（`ChatViewModelSendExt.kt` / `ChatViewModelAgentLoopExt.kt` ±10 行）

发送时：
```kotlin
// pickGroupTarget 选定 entry 后
val apiKey = providerRepository.usableApiKeyFromPool(instance, pool.nextCursor(instance.id))
    ?: // 全冷时退回单 key
```
同时把 `(instanceId, cursor)` 压入当前 request 的 trace 中，等错误回调来冷却。

**记录方式**：在 `GroupRoutingState` 加一个 `keyCursorByInstance: MutableMap<String, Int>`（类似 `servingEntry`），pick 时写入。

### 2.6 UI 改动（`AddProviderScreen.kt` / `ProviderDetailScreen.kt` ±120 行）

**供应商详情页**：（ProviderDetailScreen，Apple 风格——加载 awesome-design-md + ui-ux-pro-max）

- 凭据类型为 apiKey 时，在 API Key 输入框下方新增「账号池」区域
- 显示池中 key 列表（脱敏显示：`sk-***a1b2` + 移除按钮）
- 「添加账号」按钮 → 展开 key 输入框（复用现有输入框样式）
- 开关「启用多账号轮换」——关闭时仍保留池数据，但请求路径走回单 key
- `quotaGroupId` 输入（可选）：同厂牌实例共享冷却组标识

**顺序控制**：池中 key 按添加时间排序（数组位置即优先级），拖拽排序一期不做。

### 2.7 备份导出扩展（`ProviderBackupCoordinator.kt` ±15 行, `BackupSecrets.kt` ±5 行）

```kotlin
// BackupSecrets.ProviderSecret 新字段：
val pooledKeys: List<String>? = null,  // 全量池 key 列表，null = 无池
val quotaGroupId: String? = null,

// ProviderBackupCoordinator 收集时：
pooledKeys = credentialPool.keys(instance.id).ifEmpty { null }
```

恢复时：`credentialPool.importFrom(instanceId, secret.pooledKeys)` + `instance.quotaGroupId = secret.quotaGroupId`

### 2.8 测试（`ProviderCredentialPoolTest.kt` 新文件, ~150 行）

用例表：
1. **单 key 回退**：空池 → usableApiKeyFromPool 退回单 key，与 M2 前一模一样
2. **轮换顺序**：池 [a, b, c] → cursor 0→a, 1→b, 2→c, 3→a（绕回）
3. **冷却跳过**：b 冷却中 → cursor 1 不返回 b → usableApiKeyFromPool 返回 null → 调用方退回单 key
4. **全冷却兜底**：全池冷却 → 退回单 key（degraded answer beats no answer，同 M1）
5. **并发取号**：两个线程各调 nextCursor → 拿到不同的 cursor（AtomicInteger 保证）
6. **同 quotaGroup 波及**：A 组 instance1 key0 冷却 → 同 group 的 instance2 key0 也冷却
7. **池持久化**：add → 重读 → keys() 返回完整列表
8. **备份往返**：池数据 → BackupSecrets → restore → 验证一致

## 三、依赖关系

| 依赖 | 状态 |
|---|---|
| M1（GroupRoutingState + cooldown 基础设施） | ✅ 已合并 |
| M3（免费车道动态化，复用凭据池） | 依赖 M2（M2 先落，M3 再用）→ 设计时留好扩展点 |

M3 需要的复用点：同一渠道多账号 = 一个 ProviderInstance + 凭据池多 key。M2 的 `usableApiKeyFromPool` 签名已适用——M3 调用时传 cursor 即可，不改 M2 代码。

## 四、不变式（设计保证）

1. **零迁移**：无池 → 退回单 key，老用户零影响。
2. **序列化不动**：`ProviderInstance` 只加一个默认 null 字段 `quotaGroupId`，旧 JSON 反序列化安全。
3. **缓存不污染**：ProviderFactory 缓存 key 是 `instanceId|modelHash|credFingerprint|instanceHash`——credential fingerprint 变（不同 key index）→ 自动新条目，不冲突。
4. **冷却非持久化**：进程重启冷却清空（同 M1），不存 DB。
5. **并发安全**：nextCursor AtomicInteger + credentialCooldowns ConcurrentHashMap，无锁。

## 五、分步执行节奏

1. 建分支 `feat/credential-pool-rotation`
2. 新建 `ProviderCredentialPool.kt`（完整实现 + test）
3. 改 `ProviderConfigStore.kt`（pool APIs + usableApiKeyFromPool）
4. 改 `ProviderInstance.kt`（+quotaGroupId）
5. 改 `GroupRoutingState.kt`（+markCredentialCooldownFor + keyCursor trace）
6. 改发送路径（`ChatViewModelAgentLoopExt.kt` error 分支）
7. 改 `BackupSecrets.kt` + `ProviderBackupCoordinator.kt`
8. UI 改动（加载 awesome-design-md + ui-ux-pro-max → 读两个 skill → ProviderDetailScreen）
9. 翻译（17 locale × 7 key）
10. 四道本地门禁 → push → PR → CI 绿 → squash 合并 → 验增量 → 删分支

## 六、关键参数

- 池容量无上限（EncryptedPrefs 存 JSON array，理论上无限制）
- 冷却默认 30s（复用 GroupRoutingState.DEFAULT_COOLDOWN_MS）
- 池 key 脱敏显示：前 3 字符 + `***` + 后 4 字符（`sk-***a1b2`）

## 七、实施快照（2026-11-02）

### ✅ 已完成（步骤 1–6）

| 步骤 | 状态 | 备注 |
|---|---|---|
| 1 建分支 | ✅ | — |
| 2 ProviderCredentialPool.kt | ✅ | 280 行（含 ProviderRepository 扩展）；poolPickKey/credentialCooldownFor/poolAfterAdd/poolAfterRemove/markCooled/lastPick/resetForTest；test 211 行 23 用例 |
| 3 ProviderConfigStore.kt | ✅ | poolKeys/addPoolKey/removePoolKey/requestCredential/markCredentialCooled（含 quotaGroup 遍历同冷）；legacy sync（单 key ↔ pool[0]） |
| 4 ProviderConfig.kt | ✅ | +quotaGroupId（默认 null，旧 JSON 安全） |
| 5 GroupRoutingState.kt | ✅ | +noteServingInstance/servingInstance/markCredentialCooldownFor/markRoutingCooldownFor |
| 6 发送路径 | ✅ | 14 文件切 requestCredential；AgentLoop 429 分支接入 markRoutingCooldownFor（入口+凭据双冷却一线） |

**实施偏离记录**：
- 原计划 `usableApiKeyFromPool(cursor: Int)` → 实现为 `requestCredential(): Pair<String,Int>?`（内部自管理 cursor，调用方零负担）
- `ProviderRepository` 的 5 个委托方法提取为 `ProviderCredentialPool.kt` 顶层扩展（架构天花板约束：ProviderRepository 已达 1542 = ceiling，不能加行）
- `ChatViewModelSubAgentExt.kt` 1061 = ceiling，429 注释压缩 3 行（纯注释收紧，无行为变化）

### 🔜 待完成（步骤 7–10）

| 步骤 | 状态 | 预估 |
|---|---|---|
| 7 Backup | 🔜 | BackupSecrets +pooledKeys/+quotaGroupId；ProviderBackupCoordinator 收集/恢复（±20 行） |
| 8 UI | 🔜 | ProviderDetailScreen 账号池区域（Apple 风格）+ AddProviderScreen 多 key 输入（±120 行，需先读 awesome-design-md + ui-ux-pro-max） |
| 9 翻译 | 🔜 | 7 个新 key × 17 locale |
| 10 门禁 → PR → CI | 🔜 | 步骤 7-9 完成后全量门禁 → push → PR |