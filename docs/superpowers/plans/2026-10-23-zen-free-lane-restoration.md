# 恢复内置 OpenCode Zen 免费通道 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让装好/升级的 app 零配置拥有一个可用的 OpenCode Zen 免费实例，能跑完整 agent 循环（真工具调用 + 流式），且模型目录随上游自动变化。

**Architecture:** 上游门禁是**请求体形状检测**（tools 里必须有名为 `read` 的函数定义），不是身份墙。所以配方落在一个 OkHttp `Interceptor` 里 —— 判据（host + `Authorization: Bearer public`）全在 request 上，无需实例上下文，也天然不会碰用户自建的真 key 实例。目录侧复用旧版已有的动态机制（`-free` 后缀规则 + live `/models` 刷新 + `replaceEntries` prune），只把健康记录的证据语义收窄。播种/恢复/迁移全部逻辑外置到独立文件，贴顶大文件只留调用行。

**Tech Stack:** Kotlin, OkHttp 4.12（Interceptor + MockWebServer）, Room, SharedPreferences, Jetpack Compose（Material3）, JUnit4 + Robolectric 4.14。

**Spec:** `docs/superpowers/specs/2026-10-23-zen-free-lane-restoration-design.md`（12 章，含 §6 上游契约精确值、§11 六状态迁移矩阵、§12 恢复渠道）

## Global Constraints

- 新文件 ≤ **800** 行（`architecture-policy.json` 的 `newFileMaxLines`）。
- **贴顶文件（ceiling == 当前行数，slack = 0，一行都不能净增）**：
  `provider/openai/OpenAIRequestBodies.kt`(1309)、`provider/openai/OpenAIProvider.kt`(1772)、
  `data/repository/ProviderRepository.kt`(2767)、`provider/openai/OpenAIRawStream.kt`(1097)、
  `ui/settings/AddProviderScreen.kt`(940)、`ui/settings/ProviderDetailScreen.kt`(1233)。
  改这些文件必须**先外置等量或更多行数**，再 `python3 scripts/ci/check_architecture_policy.py --update-baseline` 收紧并提交 json。baseline 只降不升。
- **有余量的文件（不在 baseline）**：`ProviderConfig.kt`(518)、`OpenAIModelsApi.kt`(228)、
  `ProviderListScreen.kt`(678)、`OpenAIProviderErrors.kt`(186)、`ZenEndpoint.kt`(40)。
- 生产源码**不得出现中文字符串字面量**（CJK ratchet 门禁；注释里的中文不拦，但新文件一律写英文注释）。
- 新增字符串键必须覆盖**全部 17 个语言目录**（`res/values*/`，`values-night` 是主题目录不放 strings）。
- **A5 反目标**：代码里不得出现免费模型 id 枚举。允许的例外只有两个：首装离线种子（`deepseek-v4.1-flash-free`、`big-pickle`）与 `big-pickle` 的无后缀识别，且两者都必须被远端目录与学习缓存覆盖。
- **A1 反目标**：不得因 RegionError 隐藏任何模型。
- **A4 反目标**：不得为非 Zen provider 改动请求构建路径（interceptor 判据不命中即原样放行）。
- 本地只做 python 级验证与门禁脚本；**编译与单测一律交云端 CI**（本机无 JDK/kotlinc）。
- 提交前必跑：括号/KDoc 平衡扫描、符号→导入核对、CJK 扫描、L10n 覆盖、架构门禁。

## Review Focus

1. **离线或空目录时刷新** → 期望 `modelEntries` 逐条不变；绝不能把非 custom 行清光（`replaceEntries` 的 `suspiciousShrink` 只保 group 引用，主体仍 removeAll+addAll，所以守卫必须在调用方）。
2. **用户自建 Zen 实例（真 key）** → 期望 id/label/key/模型行逐字节不变：interceptor 判据不命中、bootstrap 不触碰。
3. **上游收紧门禁（`read` 占位不再足够）** → 期望结构化可行动错误（指出是门禁形状问题），不是笼统 401「key 无效」。
4. **锁区用户命中 RegionError** → 期望模型仍在目录里，错误文案说清「换个网络/代理即可」，且不写进健康拉黑证据。
5. **老设备带着 5 个已死 seed 行且用户改过其中某行的显示名** → 期望死行被 prune、存活行的 `uuid`/`overrides`/`isHidden`/`userModifiedAt` 逐条继承。

---

### Task 1: 拆墓碑，立门禁契约（测试先行）

**Files:**
- Delete: `src/android/app/src/test/java/com/openminis/app/provider/ZenFreeLaneRetirementGuardTest.kt`
- Create: `src/android/app/src/test/java/com/openminis/app/provider/ZenFreeLaneGateTest.kt`
- Modify: `src/android/app/src/main/java/com/openminis/app/provider/ZenEndpoint.kt`（只改 KDoc：把 2026-10-17 的错误诊断替换为 2026-10-23 的对照实验结论，`isZenHost` 实现不动）

**Interfaces:**
- Consumes: `ZenEndpoint.isZenHost(baseURL: String?): Boolean`（已存在，40 行）
- Produces（Task 2 实现，本任务只写测试钉住签名）:
  - `internal object ZenFreeLaneGate : okhttp3.Interceptor`
  - `internal fun ZenFreeLaneGate.appliesTo(request: okhttp3.Request): Boolean`
  - `internal fun ZenFreeLaneGate.rewriteBody(json: org.json.JSONObject): org.json.JSONObject`
  - `internal object ZenSession { fun newId(): String; fun isWellFormed(id: String): Boolean; fun sanitizeOrNull(id: String?): String? }`
  - `internal fun ZenFreeLaneGate.headersFor(sid: String): Map<String, String>`

- [ ] **Step 1: 写失败测试 `ZenFreeLaneGateTest.kt`**

钉住 spec §6.4 的精确值（每条一个 `@Test`，用 `org.json.JSONObject` 直接断言，不起 HTTP）：

```kotlin
@Test fun `body without a read tool gets the read placeholder injected`() {
    val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
    val names = out.getJSONArray("tools").let { a -> (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") } }
    assertTrue(names.containsAll(listOf("read", "shell")))
}

@Test fun `an existing real grep or glob definition is never duplicated`()
@Test fun `stream is forced true even when the caller asked for false`()
@Test fun `tool_choice none is removed so the model may call tools`()
@Test fun `a placeholder notice is appended to the first system message`()
@Test fun `no notice is appended when nothing was injected`()
@Test fun `appliesTo requires zen host AND bearer public`() {
    assertFalse(ZenFreeLaneGate.appliesTo(req("https://api.openai.com/v1", "Bearer public")))
    assertFalse(ZenFreeLaneGate.appliesTo(req("https://opencode.ai/zen/v1", "Bearer sk-real")))
    assertTrue(ZenFreeLaneGate.appliesTo(req("https://opencode.ai/zen/v1", "Bearer public")))
}
@Test fun `session id matches the measured shape`() {
    val sid = ZenSession.newId()
    assertTrue(sid, sid.matches(Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")))
}
@Test fun `the read placeholder carries the exact upstream description and parameter shape`()
```

占位的精确值取自 spec §6.4 表格：`read` 的 description 是 `Read the contents of a file or directory.`，parameters 为 `path`(string, required)/`offset`(integer)/`limit`(integer)，`strict: false`。

- [ ] **Step 2: 跑测试确认红**

本机无 kotlinc → 用 python 复刻 `rewriteBody` 的规则表，对同样 9 个输入断言同样结果（`/var/minis/workspace/sim_zen_gate.py`），确认规则自洽；真正的红/绿由 Task 2 提交后的 CI 判定。

- [ ] **Step 3: 删除墓碑测试**

`git rm src/android/app/src/test/java/com/openminis/app/provider/ZenFreeLaneRetirementGuardTest.kt`

它禁止的七个符号（`ZenDisguise`/`ZenFreeLaneHealth`/`isZenFree`/`zenBudgetTokens`/`bundledZenModels`/`zenVisibleModels`/`ZEN_BUNDLED_ENDPOINT`）中，本计划会重新引入 `ZenFreeLaneHealth` 与 `isZenFreeLaneId`（语义已收窄），所以墓碑必须拆。Task 8 立新的契约守卫替代它的防回归作用。

- [ ] **Step 4: 改写 `ZenEndpoint.kt` 的 KDoc**

保留 `isZenHost` 实现与「Zen 说自己的错误方言」这段理由（仍然成立）。把「refusal is not a header shape a client can imitate」整段替换为 2026-10-23 的对照实验结论：唯一变量是 tools 数组，`read` 单独一个就 200，`bash`/`glob`/`grep`/`write`/`edit`/`task`/`webfetch` 单独都 403 → 判据是请求体形状。KDoc 里保留实测矩阵（无 tools → 403；tools=[read] → 200）。

- [ ] **Step 5: 跑门禁自检**

```bash
cd /var/minis/workspace/OpenMinis-Linux
python3 scripts/ci/check_architecture_policy.py
python3 scripts/ci/check_announced_tools.py
```
Expected: 两个都 exit 0（本任务没动生产逻辑行数）。

- [ ] **Step 6: 提交**

```bash
git add -A src/android/app/src/test src/android/app/src/main/java/com/openminis/app/provider/ZenEndpoint.kt
git commit -m "test: replace the zen free-lane tombstone with gate contract tests"
```

---

### Task 2: 门禁本体 —— ZenFreeLaneGate + ZenSession

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/provider/ZenSession.kt`
- Create: `src/android/app/src/main/java/com/openminis/app/provider/ZenFreeLaneGate.kt`
- Create: `src/android/app/src/main/java/com/openminis/app/provider/ZenClientVersion.kt`

**Interfaces:**
- Consumes: `ZenEndpoint.isZenHost`；Task 1 钉住的全部签名
- Produces（Task 3 用）:
  - `internal object ZenFreeLaneGate : Interceptor`，`override fun intercept(chain: Chain): Response`
  - `internal fun ZenFreeLaneGate.appliesTo(request: Request): Boolean`
  - `internal fun ZenFreeLaneGate.rewriteBody(json: JSONObject): JSONObject`
  - `internal fun ZenFreeLaneGate.headersFor(sid: String): Map<String, String>`
  - `internal object ZenSession { fun newId(): String; fun isWellFormed(id: String): Boolean; fun sanitizeOrNull(id: String?): String? }`

- [ ] **Step 1: 实现 `ZenSession.newId(): String`**

按 spec §6.3 的位运算（毫秒 << 12 + 同毫秒计数，取反掩 48 位，转 12 位 hex，接 14 位 Base62 随机）。Base62 字母表 `0-9A-Za-z`。计数器用 `AtomicInteger`，跨毫秒重置为 1。不恢复旧的 SHA-256 派生实现。

- [ ] **Step 2: 实现 `rewriteBody(json: JSONObject): JSONObject`**

顺序固定（spec §6.4）：
1. `json.put("stream", true)`
2. 取 `tools`（缺失则建空数组）；对 `{shell, glob, grep, read}` 四个名字，若已存在同名且 `parameters.properties` 非空 → 视为满足；否则注入占位定义
3. 若 `tool_choice` == `"none"` → `remove("tool_choice")`
4. 若本次注入了任何占位 → 找第一条 role 为 `system`/`developer` 的消息，把 spec §6.4 的固定英文提示追加到其 content 末尾（content 可能是字符串或数组，两种都要处理；都没有 system 消息则不追加）
5. 返回同一个 JSONObject（就地改写，调用方负责序列化）

占位定义（`strict: false`，description **逐字照抄 spec §6.4 表格**，不得改写）：
- `shell`: `Execute a shell command and return its output.` / `command`(string, required), `workdir`(string), `timeout`(integer, ms), `background`(boolean)
- `read`: `Read the contents of a file or directory.` / `path`(string, required), `offset`(integer), `limit`(integer)
- `glob`: `Search file paths using a glob pattern (examples: "**/*.ts", "src/**/*.tsx").` / `pattern`(string, required), `path`(string), `hidden`(boolean), `limit`(integer)
- `grep`: `Search file contents using ripgrep syntax.` / `pattern`(string, required), `path`(string), `include`(string), `literal`(boolean), `caseSensitive`(boolean), `limit`(integer)

追加到 system 消息末尾的提示（spec §6.4 第 5 条，逐字，含前导两个换行）：

```
\n\nThe tools named shell, glob, grep and read in this request are placeholders. Do not call them; use the other tools you have been given.
```

注：注册表里有真 `grep`(GrepTool) 与真 `glob`(GlobTool)，其 `parameters.properties` 非空 → 按第 3 条的"已满足"判定，**实际只注入 `shell` 和 `read` 两个占位**；四个定义仍要全部实现，因为上游将来可能收紧、且客户端 tools 可能被裁剪。

- [ ] **Step 3: 实现 `headersFor(sid: String): Map<String, String>`**

spec §6.2 的精确值：`Authorization: Bearer public`、`User-Agent: opencode/prod/2.0.29/cli`、`x-opencode-client: cli`、`x-opencode-project: global`、`x-opencode-session`/`x-opencode-session-id`/`x-session-affinity`/`x-session-id` 全部 = sid、`Accept: text/event-stream`、`Content-Type: application/json`。

UA 版本号来源：`GET https://registry.npmjs.org/opencode-ai/latest` 的 `version`，若 < `2.0.0` 或请求失败 → 兜底 `2.0.29`（实测 npm 最新是 1.19.4，所以恒为兜底值；这段解析逻辑放 `ZenClientVersion.kt`，可独立测）。

- [ ] **Step 4: 实现 `appliesTo(request: Request): Boolean`**

`request.method == "POST"` && `ZenEndpoint.isZenHost(request.url.toString())` && `request.header("Authorization") == "Bearer public"`。GET（目录拉取）不命中 → 原样放行（实测 `/zen/v1/models` 匿名 200）。

- [ ] **Step 5: 实现 `intercept(chain: Chain): Response`**

不命中 → `chain.proceed(request)` 原样返回（A4：非 Zen 路径零影响）。
命中 → 读 body（`RequestBody` → UTF-8 字符串 → JSONObject；解析失败则原样放行并 `AppLogger.warn`）→ `rewriteBody` → 用改写后的 JSON 重建 RequestBody（同 contentType）→ 加 `headersFor(ZenSession.newId())` 的头 → proceed。
sid 在 chain 内生成一次并复用；401 时换新 sid 重试，最多 3 次（spec §6.3）。

- [ ] **Step 6: python 复刻规则表验证改写语义**

`/var/minis/workspace/sim_zen_gate.py`：把 rewriteBody 的四步规则用 python 重写，跑 Task 1 的 9 个用例 + 边界（tools 缺失、tools 为空数组、content 是数组、无 system 消息、tool_choice="auto" 保留、已有真 grep 不重复注入）。Expected: 全过。

- [ ] **Step 7: 门禁自检 + 提交**

```bash
python3 scripts/ci/check_architecture_policy.py   # 新文件 ≤800
git add src/android/app/src/main/java/com/openminis/app/provider/Zen*.kt
git commit -m "feat: zen free-lane gate — satisfy the upstream read-tool shape check"
```

---

### Task 3: 接线 HTTP client（净减腾位，不动贴顶预算）

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAILlmHttpClient.kt`
- Modify: `src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:440-465`（builder 链 + 其上 7 行注释块 → 一行调用）
- Modify: `scripts/ci/architecture-policy.json`（OpenAIProvider ceiling 1772 → 收紧后的实际行数）

**Interfaces:**
- Consumes: `ZenFreeLaneGate`（Task 2）
- Produces: `internal object OpenAILlmHttpClient { fun build(): OkHttpClient }`

- [ ] **Step 1: 把 `OpenAIProvider.kt:440-465` 整段搬到新文件**

逐字保留 `DohDns`、30/600/30 秒超时、`NetworkMonitor.sharedLLMConnectionPool`（连同解释共享池为什么是 load-bearing 的注释）、`OkHttpNetTraceListener`。在 `.build()` 之前插入 `.addInterceptor(ZenFreeLaneGate)`。新文件顶部 KDoc 说明：gate 只在 host + `Bearer public` 同时命中时改写，其他 provider 零影响。

- [ ] **Step 2: OpenAIProvider 只留一行**

```kotlin
internal val client = OpenAILlmHttpClient.build()
```

原 26 行（440-465）→ 1 行，净减 25。

- [ ] **Step 3: 收紧 baseline 并核对净减**

```bash
python3 scripts/ci/check_architecture_policy.py --update-baseline
git diff --stat scripts/ci/architecture-policy.json
wc -l src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt
```
Expected: OpenAIProvider 实际行数 == 新 ceiling == 1747（1772 − 25），门禁 exit 0。若行数不是 1747，说明搬多了或搬少了，回去核对再收紧。

- [ ] **Step 4: 符号→导入核对（`by` 委托与通配符陷阱）**

新文件需要 `okhttp3.OkHttpClient`、`java.util.concurrent.TimeUnit`、`ZenFreeLaneGate`；OpenAIProvider 删掉 builder 链后 `TimeUnit`/`OkHttpClient` 可能变成孤儿导入（也可能别处还在用）→ 用符号反查确认，不要手打清单。

- [ ] **Step 5: 提交**

```bash
git add src/android/app/src/main/java/com/openminis/app/provider/openai/ scripts/ci/architecture-policy.json
git commit -m "refactor: extract the OpenAI llm http client so the zen gate can register"
```

---

### Task 4: 目录三层机制（规则 / 学习 / 缓存 / 收窄的健康记录）

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/provider/ZenFreeLaneCatalog.kt`
- Create: `src/android/app/src/main/java/com/openminis/app/provider/ZenCatalogCache.kt`
- Create: `src/android/app/src/main/java/com/openminis/app/provider/ZenFreeLaneHealth.kt`
- Modify: `src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIModelsApi.kt`（228 行，有余量：免费档实例的目录 URL 与过滤接线）
- Test: `src/android/app/src/test/java/com/openminis/app/provider/ZenFreeLaneCatalogTest.kt`、`ZenCatalogCacheTest.kt`、`ZenFreeLaneHealthTest.kt`

**Interfaces:**
- Consumes: `ZenEndpoint.isZenHost`
- Produces（Task 5 用）:
  - `internal object ZenFreeLaneCatalog { fun isFreeLaneModelId(id: String): Boolean; fun filterFree(models: List<LLMModel>, learned: ZenLearnedFree): List<LLMModel>; fun needsProbe(models: List<LLMModel>, learned: ZenLearnedFree): List<String> }`
  - `internal data class ZenLearnedFree(val free: Set<String> = emptySet(), val paid: Set<String> = emptySet())` —— 学习缓存的值类型（探测与真实使用产生的证据）
  - `internal class ZenCatalogCache(context: Context?) { fun load(key: String): ZenCatalogSnapshot?; fun save(key: String, snapshot: ZenCatalogSnapshot) }`
  - `internal data class ZenCatalogSnapshot(val ids: List<String>, val learned: ZenLearnedFree, val fetchedAtMs: Long, val isOfflineSeed: Boolean = false)`
  - `internal object ZenFreeLaneHealth { fun isDead(id: String): Boolean; fun recordFailure(id: String, evidence: ZenFailureEvidence); fun recordSuccess(id: String) }`
  - `internal enum class ZenFailureEvidence { GONE, MODEL_UNAVAILABLE, MODEL_NOT_SUPPORTED, REGION, PROTOCOL_UNSUPPORTED, FREE_TIER, RATE_LIMITED, SERVER, NETWORK }`

- [ ] **Step 1: 写失败测试（三个测试文件）**

`ZenFreeLaneCatalogTest`：
```kotlin
@Test fun `any -free suffix counts regardless of case`() {
    assertTrue(ZenFreeLaneCatalog.isFreeLaneModelId("deepseek-v4.1-flash-free"))
    assertTrue(ZenFreeLaneCatalog.isFreeLaneModelId("Some-New-Model-FREE"))
}
@Test fun `big-pickle is the only known suffix-free exception`()
@Test fun `a newly learned free id survives the filter`()
@Test fun `paid ids never appear even when the catalogue lists them`()
@Test fun `needsProbe returns only ids that are new and suffix-free`()
@Test fun `no hardcoded free-id list exists in production sources`() {
    // A5 守卫：扫 ProductionSources，白名单只有首装离线种子与 big-pickle
}
```

`ZenFreeLaneHealthTest`：
```kotlin
@Test fun `region evidence never marks a model dead`() {
    ZenFreeLaneHealth.recordFailure("beam-free", ZenFailureEvidence.REGION)
    assertFalse(ZenFreeLaneHealth.isDead("beam-free"))
}
@Test fun `free-tier evidence never marks a model dead`()   // 那是我们的门禁 bug，不是模型属性
@Test fun `protocol-unsupported evidence never marks a model dead`()
@Test fun `rate-limited and server evidence never mark a model dead`()
@Test fun `gone evidence marks dead and self-heals after the ttl`()
@Test fun `a success clears prior death evidence`()
```

`ZenCatalogCacheTest`：
```kotlin
@Test fun `empty remote data is treated as a failure and never overwrites the cache`()
@Test fun `first install with no network falls back to the offline seed`()
@Test fun `the offline seed is retired permanently after the first successful fetch`()
```

- [ ] **Step 2: 实现 `ZenFreeLaneCatalog`**

`isFreeLaneModelId(id)` = `id.equals("big-pickle", true) || id.endsWith("-free", true)`（与旧实现同形，A5 允许的唯一两个例外）。
`filterFree(models, learned)` = 保留 `isFreeLaneModelId` 或 `id in learned.learnedFree` 的项，再剔除 `ZenFreeLaneHealth.isDead(id)` 的项。
`needsProbe(models, learned)` = `models.map{it.id}` 中既不在 `learnedFree` 也不在 `learnedPaid`、且不带 `-free` 后缀的项（增量探测，绝不全量扫 85 个）。

- [ ] **Step 3: 实现 `ZenFreeLaneHealth`（收窄证据集）**

只有 `GONE`(410)、`MODEL_UNAVAILABLE`(400 `Model is unavailable.`)、`MODEL_NOT_SUPPORTED`(401 ModelError "not supported") 三种证据写进 dead 集合；其余六种**只记日志，不改目录**。持久化沿用旧机制：SharedPreferences `zen_free_lane_health` / key `dead_ids`，**7 天 TTL 自愈**，`recordSuccess` 立即清除该 id。无 `initialize(context)` 时走内存态（Pure-JVM 可测）。

- [ ] **Step 4: 实现 `ZenCatalogCache`**

磁盘 JSON（`context.filesDir/zen_catalog.json`），TTL 过期或手动刷新时重拉；`save` 拒绝空 `ids`（离线不变坏的第一道守卫，第二道在 Task 5 的调用方）；首装无网络且无缓存 → 返回离线种子快照（`deepseek-v4.1-flash-free`、`big-pickle`）并置 `isOfflineSeed = true`，首次成功 fetch 后种子标记永久失效。

- [ ] **Step 5: 接线 `OpenAIModelsApi`**

免费档实例的目录请求走 `GET {baseURL}/models`（baseURL = `https://opencode.ai/zen/v1`，`appendV1Suffix = false`，实测匿名 200 返回 85 个模型）。拉到后：
1. 空 `data` → 视为失败，**不调用 `replaceEntries`**，返回上次缓存
2. 非空 → `filterFree` → 交给调用方
3. 异步对 `needsProbe` 的结果做轻量探测（`max_tokens=1` + 门禁形状）：200 → 写 `learnedFree`；「需要凭据」类 → 写 `learnedPaid`（不展示不重试）；网络/429 → 什么都不写（下次重试）
4. 复用既有的 `[T-provider-custom-user-agent]` UA override 机制给目录请求带上 CLI UA（非必需，实测匿名可用，但降低被限流概率）

- [ ] **Step 6: python 模拟三层机制的判定矩阵**

`/var/minis/workspace/sim_zen_catalog.py`：构造 6 组输入（全 -free / 混合付费 / big-pickle / 已 learnedFree / 已 learnedPaid / RegionError 死亡候选）× 3 种缓存状态（空 / 有缓存 / TTL 过期），断言 filterFree 与 needsProbe 的输出。Expected: 18 组全过。

- [ ] **Step 7: 门禁自检 + 提交**

```bash
python3 scripts/ci/check_architecture_policy.py
git add src/android/app/src/main/java/com/openminis/app/provider/Zen*.kt src/android/app/src/test src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIModelsApi.kt
git commit -m "feat: zen free-lane catalogue — suffix rule, learned probes, offline-safe cache"
```

---

### Task 5: 播种、恢复与迁移（反转 #61，修普适根因）

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/data/repository/ZenFreeLaneBootstrap.kt`
- Create: `src/android/core/model/src/main/kotlin/com/openminis/app/data/model/InstanceRemoval.kt`
- Modify: `src/android/core/model/src/main/kotlin/com/openminis/app/data/model/ProviderConfig.kt:457` 附近（加 `removedInstanceIds` 字段；518 行，有余量）
- Modify: `src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt`（`:267` 调用点、`:602-620` retire 函数体删除、`:2767` 常量移走、`:668` removeInstance 补两行）
- Modify: `scripts/ci/architecture-policy.json`（ProviderRepository ceiling 收紧）
- Test: `src/android/app/src/test/java/com/openminis/app/data/repository/ZenFreeLaneBootstrapTest.kt`

**Interfaces:**
- Consumes: `ZenFreeLaneCatalog`（Task 4）、`ProviderConfig` / `LLMInstance` / `ModelEntry` / `ModelDeletion`
- Produces（Task 6/8 用）:
  - `internal object ZenFreeLaneBootstrap { const val INSTANCE_ID: String; const val LABEL: String; const val ENDPOINT: String; fun ensureSeeded(config: ProviderConfig): Boolean; fun restore(config: ProviderConfig): Boolean; fun markRemovedByUser(config: ProviderConfig, instanceId: String); fun isBuiltinFreeInstanceId(id: String): Boolean; fun isZenFreeInstance(i: LLMInstance): Boolean; fun repairDefaultSlot(config: ProviderConfig): Boolean }`
  - `internal object InstanceRemoval { fun record(config: ProviderConfig, instanceId: String); fun wasRemovedByUser(config: ProviderConfig, instanceId: String): Boolean; fun clear(config: ProviderConfig, instanceId: String) }`
  - `ProviderRepository.restoreBuiltInZenProvider(): Boolean`（public，UI 入口调用）

- [ ] **Step 1: 写失败测试（S1-S6 矩阵，Robolectric 真 ProviderConfig）**

```kotlin
@Test fun `S1 fresh install seeds the builtin instance with a public key`()
@Test fun `S2 legacy device keeps user customisation while dead seed rows are pruned`() {
    // 造 8 行旧 seed（含 5 个已死 id），给其中一行设 overrides + isHidden + userModifiedAt
    // 喂假目录（不含死 id）→ 断言：死行消失、新行加入、存活行的 uuid/overrides/isHidden/userModifiedAt 逐条相等
}
@Test fun `S3 a retired instance is re-seeded under the same id so old references revive`()
@Test fun `S4 an instance removed by the user is not re-seeded on next launch`()
@Test fun `S5 a user-built zen instance with a real key is never touched`() {
    // 断言 id/label/apiKey/modelEntries 逐字节不变（序列化前后对比）
}
@Test fun `S6 model-level tombstones are respected and only cleared by the explicit channel`()
@Test fun `an empty or failed catalogue fetch leaves modelEntries byte-identical`()   // Review Focus 1
@Test fun `removing an instance also clears its model tombstones`()                   // 普适根因
@Test fun `repairDefaultSlot fills a slot that this migration emptied but not one the user left empty`()
```

- [ ] **Step 2: 实现 `InstanceRemoval`（实例级墓碑）**

与 `ModelDeletion`（模型级）**并列不混用**。`ProviderConfig` 加 `val removedInstanceIds: MutableList<String> = mutableListOf()`（紧邻 `deletedModelIds`，同样的持久化路径）。
`record` / `wasRemovedByUser` / `clear` 三个纯函数。**#61 的 retire 不写这个墓碑** —— 那是我们的决定不是用户意愿，所以 S3 能被无感恢复。

- [ ] **Step 3: 实现 `ZenFreeLaneBootstrap`**

常量：`INSTANCE_ID = "builtin-opencode-zen"`（**复用 #61 删掉的同一个 id**，让旧会话与槽位引用自动复活）、`ENDPOINT = "https://opencode.ai/zen/v1"`、`LABEL = "OpenCode Zen (Free)"`（撞名时加 `· Built-in` 后缀）、`API_KEY = "public"`、`appendV1Suffix = false`。

`ensureSeeded(config)`：
1. `config.instances.any { isZenFreeInstance(it) }` → 已存在则返回 false（S2/S5/S6 交给刷新路径，不重建）
2. `InstanceRemoval.wasRemovedByUser(config, INSTANCE_ID)` → true 则返回 false（S4）
3. 否则播种（同 id / key=public / label / endpoint / appendV1Suffix=false）→ 返回 true

`isZenFreeInstance(i)` 判据 = `i.id == INSTANCE_ID || (ZenEndpoint.isZenHost(i.customBaseURL) && apiKeyOf(i) == "public")`。用户自建真 key → false → 永不触碰（S5）。

`restore(config)`：清实例级墓碑 → 缺则播种 → 清该实例的模型墓碑 → 返回是否改动（UI 据此触发目录刷新）。

`repairDefaultSlot(config)`：仅当 `defaultPrimaryGroupId == null` 且本次迁移确实清空过它（由调用方传入 before/after 判定）→ 指向一个当前可用的免费模型 `entry:<uuid>`。用户本来就有意留空的不覆盖。

- [ ] **Step 4: 反转 `ProviderRepository` 的启动通道（净减腾位）**

`:267` 的 `retireBuiltInZenProvider()` → `ZenFreeLaneBootstrap.ensureSeeded(config)`；
`:602-620` 的函数体整段删除（逻辑已外置）；`:2767` 的 `LEGACY_ZEN_BUILTIN_ID` 常量删除（移到 bootstrap）。
净减 ≥ 20 行 → `--update-baseline` 收紧 → 之后新增的 `restoreBuiltInZenProvider()` public API 与 removeInstance 的两行有位置可放。

- [ ] **Step 5: 修 `removeInstance`（普适根因，不只服务 Zen）**

在 `:668` 的函数体内、`saveConfig(config)` 之前补：

```kotlin
config.deletedModelIds.remove(instanceId)
if (ZenFreeLaneBootstrap.isBuiltinFreeInstanceId(instanceId)) {
    InstanceRemoval.record(config, instanceId)
}
```

第一行是普适修复：实例都没了，它的模型墓碑不该活下来等着继承给下一个同 id 实例。第二行让「用户主动删内置免费实例」从新版起可辨识（S4）。

- [ ] **Step 6: 目录刷新的离线守卫做在调用方**

`replaceEntries` 的调用点（zen 免费档路径）必须先判：拿到的目录为空或 fetch 失败 → **直接 return，不调用 `replaceEntries`**。理由写进注释：`suspiciousShrink` 只保住 group 引用，主体仍是 `removeAll` + `addAll(newEntries)`，喂空列表进去会清光全部非 custom 行。

- [ ] **Step 7: python 模拟六状态迁移**

`/var/minis/workspace/sim_zen_bootstrap.py`：把 ensureSeeded / restore / repairDefaultSlot 的判定树用 python 复刻，跑 S1-S6 × {离线, 在线} × {有墓碑, 无墓碑} = 24 组，断言每组的最终 config 形态。Expected: 24 组全过，且 S5 在所有 8 种组合下逐字段不变。

- [ ] **Step 8: 门禁自检 + 提交**

```bash
python3 scripts/ci/check_architecture_policy.py --update-baseline
python3 scripts/ci/check_architecture_policy.py
git add src/android/app/src/main/java/com/openminis/app/data/repository/ src/android/core/model/ scripts/ci/architecture-policy.json src/android/app/src/test
git commit -m "fix: re-seed the zen free lane; clear model tombstones when an instance dies"
```

---

### Task 6: 三个恢复入口（Apple 风格 UI）

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/ui/settings/ZenFreeLaneRestoreRow.kt`
- Create: `src/android/app/src/main/java/com/openminis/app/ui/settings/ZenFreeLanePreset.kt`
- Create: `src/android/app/src/main/java/com/openminis/app/ui/settings/ZenDeletedModelsRestoreRow.kt`
- Modify: `src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderListScreen.kt`（678 行，余量 122：直接加一行调用）
- Modify: `src/android/app/src/main/java/com/openminis/app/ui/settings/AddProviderScreen.kt`（940 贴顶：预设列表数据外置，净减腾位）
- Modify: `src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderDetailScreen.kt`（1233 贴顶：恢复行外置，净减腾位）
- Modify: `src/android/app/src/main/java/com/openminis/app/ui/theme/M3Icons.kt`（双向注册 Filled/Outlined 成对）
- Modify: `src/android/app/src/main/res/values*/strings.xml`（17 个语言目录）

**Interfaces:**
- Consumes: `ProviderRepository.restoreBuiltInZenProvider()`（Task 5）、`ZenFreeLaneBootstrap.INSTANCE_ID`
- Produces:
  - `@Composable internal fun ZenFreeLaneRestoreRow(visible: Boolean, onRestore: () -> Unit)`
  - `@Composable internal fun ZenDeletedModelsRestoreRow(count: Int, onRestore: () -> Unit)`
  - `internal object ZenFreeLanePreset { val entry: ProviderPreset }`

- [ ] **Step 1: 先加载设计技能（会话规则要求，不可跳过）**

读 `/var/minis/skills/awesome-design-md/SKILL.md` 里 Apple 品牌那份 DESIGN.md + `/var/minis/skills/ui-ux-pro-max/SKILL.md`，按 Apple 设计语言定这三行的字重/间距/图标尺寸/次级文案颜色。动态取色不参与（本任务不做取色）。

- [ ] **Step 2: 实现入口① —— Providers 列表末尾的恢复行**

出现条件：内置免费实例缺失（有无实例级墓碑都显示）。主标题「恢复 OpenCode Zen（免费）」，副标题「无需 API key，模型随上游目录自动更新」。点击 → `restoreBuiltInZenProvider()` → 触发目录刷新 → 行消失。
放在 `ProviderListScreen.kt` 的列表末尾（678 行有余量，直接加一行 `ZenFreeLaneRestoreRow(...)` 调用，本体在新文件）。

- [ ] **Step 3: 实现入口② —— 添加 provider 的预设常驻项**

`ZenFreeLanePreset.entry` 始终出现在预设列表。已存在实例 → 跳详情并触发刷新；不存在 → 播种。
`AddProviderScreen.kt` 贴顶：把预设列表的数据构造段外置到 `ZenFreeLanePreset.kt`，屏幕里只留一行引用，净减后 `--update-baseline` 收紧。

- [ ] **Step 4: 实现入口③ —— 模型列表页的「恢复已删除的免费模型（N）」**

出现条件：内置实例存在 且 `deletedModelIds[INSTANCE_ID]` 非空。N = 墓碑数，让用户知道会回来几个。点击 → **需确认**（R9：清的是整个实例的墓碑）→ 清墓碑 → 刷新目录。
`ProviderDetailScreen.kt` 贴顶：恢复行 composable 外置，屏幕里一行调用。
**不做**跨实例的全局「恢复所有已删除模型」按钮（会误伤用户在别的 provider 上有意的删除）。

- [ ] **Step 5: 图标走 M3Icons 双向注册**

新增图标必须注册 (Filled, Outlined) **成对**，否则动态取色开关拨了没变化（#127 的教训：单向表只认 Filled 键，喂 Outlined 必 miss）。注册后跑 `M3IconsTest` 的双向/幂等/往返断言。

- [ ] **Step 6: 字符串键 ×17 locale**

新增键（约 6 个：恢复行主标题/副标题、预设名/描述、恢复已删除模型标题/确认文案）写进 `values/strings.xml` 后，同步到全部 17 个语言目录。跑 L10n 门禁：

```bash
python3 scripts/ci/check_l10n.py   # 或仓库现有的 L10n 检查脚本名
```
Expected: exit 0，无缺键。生产源码里**不得出现中文字面量**（CJK ratchet）—— 中文只进 `values-zh*/strings.xml`。

- [ ] **Step 7: 门禁自检 + 提交**

```bash
python3 scripts/ci/check_architecture_policy.py --update-baseline && python3 scripts/ci/check_architecture_policy.py
git add src/android/app/src/main/java/com/openminis/app/ui src/android/app/src/main/res scripts/ci/architecture-policy.json
git commit -m "feat: three ways to get the built-in zen free lane back"
```

---

### Task 7: 错误映射与占位工具兜底

**Files:**
- Modify: `src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProviderErrors.kt`（186 行，有余量）
- Create: `src/android/app/src/main/java/com/openminis/app/provider/ZenPlaceholderToolMapping.kt`
- Modify: 工具调用分发入口（若贴顶则外置腾位）
- Test: `src/android/app/src/test/java/com/openminis/app/provider/ZenPlaceholderToolMappingTest.kt`

**Interfaces:**
- Consumes: `ZenEndpoint.isZenHost`（错误映射已 gated on zen host，沿用）
- Produces: `internal object ZenPlaceholderToolMapping { fun map(name: String, args: JSONObject): MappedToolCall? }`；`internal data class MappedToolCall(val name: String, val args: JSONObject)`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `read maps to file_read with path preserved`()
@Test fun `read offset and limit are translated to the real tool's parameter names`()
@Test fun `shell maps to shell_execute with command preserved`()
@Test fun `glob and grep are not mapped because real tools with those names are registered`()
@Test fun `an unmappable placeholder call yields a structured error the model can self-correct from`()
@Test fun `a repeated identical placeholder call is refused instead of looping`()
```

- [ ] **Step 2: 实现 `ZenPlaceholderToolMapping`**

`read` → `file_read`（`path` 保留；`offset`/`limit` 翻译成 `file_read` 的实际参数名 —— 按该工具的真实 schema 对照，不猜）；`shell` → `shell_execute`（`command` 保留，`workdir`/`timeout` 按真实 schema 处理）。`glob`/`grep` 不映射（注册表里有同名真工具，占位不会被注入）。映射不掉 → 返回 null，调用方给结构化错误（说明「这是上游门禁要求的占位工具，请改用你真正拥有的工具」）。同名占位连续调用两次 → 拒绝，避免死循环。

- [ ] **Step 3: 调整 zen 错误文案为可行动（spec §6.6 + Review Focus 3/4）**

`OpenAIProviderErrors.kt` 已有 zen 结构化映射（`FreeTierError`/`ModelError`/`RegionError` gated on `isZenHost`）→ 只改语义与文案：
- `FreeTierError` → 不再说「非官方客户端被拒」，改为指出**门禁形状未满足**（tools 里缺 `read`），并说明这是我们的 bug 而非用户的 key 问题
- `RegionError` → 明说「该模型在当前出口 IP 的地区不可用，换网络或代理即可；模型仍会留在目录里」，**且不写进 `ZenFreeLaneHealth` 的死亡证据**（A1）
- 401 → 提示 sid 已刷新重试（spec §6.3），不是笼统「key 无效」

- [ ] **Step 4: 接线工具分发（一行调用）**

在工具调用执行的入口加 `ZenPlaceholderToolMapping.map(name, args)?.let { ... }`。若该文件贴顶 → 外置腾位后再加，并收紧 baseline。

- [ ] **Step 5: python 验证映射表 + 门禁自检 + 提交**

```bash
python3 scripts/ci/check_architecture_policy.py
git add src/android/app/src/main/java/com/openminis/app/provider src/android/app/src/test
git commit -m "fix: actionable zen refusals and a fallback for gate placeholder tool calls"
```

---

### Task 8: 契约守卫（防止再次被当成多余代码删掉）

**Files:**
- Create: `src/android/app/src/test/java/com/openminis/app/provider/ZenFreeLaneContractTest.kt`
- Consumes: `ProductionSources`（`src/android/app/src/test/java/com/openminis/app/ProductionSources.kt`）

- [ ] **Step 1: 写守卫测试（替代 Task 1 删掉的墓碑）**

```kotlin
@Test fun `the gate is registered on the openai llm client`() {
    val src = ProductionSources.read("provider/openai/OpenAILlmHttpClient.kt")
    assertTrue(src, src.contains("addInterceptor(ZenFreeLaneGate)"))
}
@Test fun `the provider store seeds instead of retiring`() {
    val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
    assertTrue(repo, repo.contains("ZenFreeLaneBootstrap.ensureSeeded"))
    assertFalse(repo, repo.contains("retireBuiltInZenProvider"))
}
@Test fun `the builtin instance id lives in exactly one production file`() {
    val hits = ProductionSources.allKotlinFiles().filter { "builtin-opencode-zen" in it.readText() }
    assertEquals(listOf("ZenFreeLaneBootstrap.kt"), hits.map { it.name })
}
@Test fun `no production file enumerates free model ids beyond the two allowed exceptions`()  // A5
@Test fun `region evidence is not a death sentence in the health record`()                   // A1
@Test fun `removeInstance clears the model tombstones of the instance it removed`()          // 普适根因
@Test fun `the gate only rewrites requests that are zen-hosted AND bearer public`()          // A4/S5
```

- [ ] **Step 2: 反向验证守卫真的会红**

临时把 `OpenAILlmHttpClient.kt` 里的 `addInterceptor(ZenFreeLaneGate)` 注掉 → 用 python 扫同样的断言（本机无 kotlinc）确认会失败 → 恢复。**不是永绿的守卫等于没有守卫**（#137 的教训）。

- [ ] **Step 3: 提交**

```bash
git add src/android/app/src/test/java/com/openminis/app/provider/ZenFreeLaneContractTest.kt
git commit -m "test: pin the zen free-lane contract so it cannot be retired by accident again"
```

---

### Task 9: 端到端验证与合并

**Files:**
- Create: `src/android/app/src/test/java/com/openminis/app/provider/ZenFreeLaneEndToEndTest.kt`

- [ ] **Step 1: MockWebServer 假 zen 端点跑完整回路**

断言服务端**收到的**请求：URL 路径含 `/chat/completions`、body 的 `stream == true`、`tools` 含 `read`、无 `tool_choice: "none"`、头集含 `User-Agent: opencode/prod/2.0.29/cli` 与四个 session 头且值相同、`Authorization: Bearer public`。
返回一段 SSE（含一个 `tool_calls` 项，name = 我们注册表里的真工具）→ 断言 provider 正确解析出工具调用，且**没有**把原始 JSON 吐进可见流（#136 的教训）。
再返回一次 401 → 断言换新 sid 重试且最多 3 次。

- [ ] **Step 2: 真机实测（本机出口，spec §6 的同一套判据）**

用 `curl` 对 `https://opencode.ai/zen/v1/chat/completions` 复跑一次门禁矩阵（无 tools → 403；tools=[read] → 200），确认上游契约**在实现当天仍然成立**（spec 是 2026-10-23 测的，若已变化，先更新 spec 再改实现）。同时跑一次真 agent 会话（长系统提示 + 30 个工具 + `max_tokens` > 4k），补上 R6 的未验证项。

- [ ] **Step 3: 本地门禁全跑**

```bash
cd /var/minis/workspace/OpenMinis-Linux
python3 scripts/ci/check_architecture_policy.py
python3 scripts/ci/check_announced_tools.py
python3 scripts/ci/check_l10n.py
# 括号/KDoc 平衡、符号→导入核对、CJK 字面量扫描（沿用仓库既有脚本）
```
Expected: 全部 exit 0。

- [ ] **Step 4: 分支 → push → PR**

```bash
git checkout -b feat/zen-free-lane-restoration
git push -u origin feat/zen-free-lane-restoration
```
PR 描述里带上：#61 的诊断错在哪（只测头没测体）、2026-10-23 的对照实验矩阵、六状态迁移表、三个恢复入口、以及顺带修的普适 bug（`removeInstance` 漏清模型墓碑）。关联 spec 与 plan 两个文档路径。

- [ ] **Step 5: 等 CI 绿 → squash 合并 → 验增量非零 → 删分支**

CI 失败按 systematic-debugging 走（先找第一个真错，级联的「Unresolved reference」不当独立 bug 修）。合并后：

```bash
git checkout main && git fetch origin
git rev-list --count <old-main>..origin/main   # 必须 ≥1，增量为零说明合并没落地
git push origin --delete feat/zen-free-lane-restoration
git branch -D feat/zen-free-lane-restoration
```

---

## Self-Review

**1. Spec 覆盖**：§5 五件事 → Task 4（目录三层）/ Task 5（播种）/ Task 7（占位映射）/ Task 5+6（无感升级与恢复渠道）。§6 上游契约 → Task 2（头/sid/body 改写）+ Task 9 Step 2（当天复测）。§7 架构 → Task 2/3 的 interceptor 落点。§9 二十条测试点 → Task 1（1-9）、Task 4（10-14 含 A5 守卫）、Task 5（15-20）、Task 8（契约防回归）。§10 R1-R9 → R7 落在 Task 5 Step 3（首次一律播种）、R8 落在 Task 4（离线种子）、R9 落在 Task 6 Step 4（N + 确认）。§11 六状态 → Task 5 Step 1 逐条。§12 三入口 → Task 6 Step 2/3/4。**无遗漏**。

**2. Step 扫描**：每步一个可验证动作。测试步给了断言与精确值；代码步给了签名、文件、spec 钉住的具体值（占位 description 逐字、UA 版本、sid 形状、TTL 7 天、种子两个 id）；验证步给了命令与期望输出。函数体只在算法不被签名与测试决定处出现（sid 位运算、rewriteBody 的五步顺序）。

**3. 类型一致性**：`ZenFreeLaneGate.appliesTo/rewriteBody/headersFor`、`ZenSession.newId/isWellFormed`、`ZenClientVersion`、`ZenFreeLaneCatalog.isFreeLaneModelId/filterFree/needsProbe`、`ZenLearnedFree(free, paid)`、`ZenCatalogSnapshot(ids, learned, fetchedAtMs, isOfflineSeed)`、`ZenFailureEvidence` 九值、`ZenFreeLaneHealth.isDead/recordFailure/recordSuccess`、`ZenFreeLaneBootstrap.INSTANCE_ID/LABEL/ENDPOINT/ensureSeeded/restore/markRemovedByUser/isBuiltinFreeInstanceId/isZenFreeInstance/repairDefaultSlot`、`InstanceRemoval.record/wasRemovedByUser/clear`、`ZenPlaceholderToolMapping.map` → `MappedToolCall(name, args)` —— 跨任务引用处签名逐一核对一致（pre-flight 扫描机械核对后修掉三处漂移：`ZenLearnedFree` 被引用但未定义、`isBuiltinFreeInstanceId` 未入 Produces、`ZenClientVersion.kt` 未入 Files）。字段名统一用 `removedInstanceIds`，与既有 `deletedModelIds` 区分层级。

**4. Review Focus 覆盖**：离线/空目录 → Task 4 Step 1（cache 测试）+ Task 5 Step 1/6；用户自建真 key → Task 5 Step 1 的 S5 + Task 8 的 gate 判据守卫；上游收紧门禁 → Task 7 Step 3 的 FreeTierError 文案；RegionError → Task 4 Step 1（health 测试）+ Task 7 Step 3；老设备死行与定制继承 → Task 5 Step 1 的 S2。五条都有归属任务与测试。

**5. 比例**：spec 428 行 / plan ~430 行。代码块以测试断言与签名为主体，只有 `rewriteBody` 的五步顺序、sid 位运算、`removeInstance` 的两行补丁给了实现级细节（这三处是签名与测试决定不了的）。未把程序写成转录。
