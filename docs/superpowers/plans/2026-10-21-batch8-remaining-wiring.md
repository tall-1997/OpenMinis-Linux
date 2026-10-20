# 批次 8 剩余三件接线：Fresh Shell + Registry + Headless Render

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (native execution, single session)

**Goal:** 把磁盘上 5 个上游文件接入 fork 现有架构：FreshProcessShell 给 ExecutionCoordinator 开 fresh 路径、BrowserTabPoolRegistry 替换自旋等待为注册表仲裁、HeadlessRenderHost 给截图补 GPU 路径 + scroll offset 修复。

**Architecture:** 三件独立，无顺序依赖。每件保留 fork 已有逻辑（SandboxResourceGate、BudgetClassifier、ActionGuard）不变，只做最小侵入接线。新建文件直接 `git add`（从上游原样移植），修改文件只改接线点。

**Tech Stack:** Kotlin / Android / WebView / PRoot / JVM unit tests

**Spec:** 上游 `b4c0661d` 中 `ExecutionCoordinator.executeFresh`、`BrowserTabPool : RegistryPool`、`BrowserUseManager.captureWebViewBitmap(gpu)` 三处实现；fork 已有 `SandboxResourceGate`、`BudgetClassifier`、`BrowserActionGuard`、`GlobalShellThrottle`

## Global Constraints

- 修改文件必须保留 fork 已有逻辑（SandboxResourceGate.withCommandLock、BudgetClassifier、MCPToolPolicy、GuestWorkloadPolicy 等）
- 新文件直接复用上游实现，不重新发明
- 所有变更通过 `:app:compileReleaseKotlin` + `:app:testReleaseUnitTest` + `check_architecture_policy.py` + `check_cjk_literals.py`
- 架构基线只降不升；若文件变大需等量置换
- 不引入新的文案键（三件均为纯架构代码）
- 不在本地编译 APK，CI 验证

## Review Focus

1. **Fresh shell seccomp retry 双走**：第一次 seccomp 失败、无输出 → retry with NO_SECCOMP。测试需命中 exit code + duration + empty output 三个条件。
2. **Registry 抢占 in-use tab**：全局 cap 达到且无 idle tab 时，抢占最老的 in-use tab，agent 收到可重试错误。测试需验证 preemptTabForRegistry 被调用且返回 intelligible error。
3. **GPU 截图 fallback**：HeadlessRenderHost.capture 返回 null 时（Surface 创建失败）应回退到软件 draw，不崩。
4. **Scroll offset 双源冲突**：attached View 的 scrollY 和 JS 的 cssScroll 同时非零时不能 double-count（sum 会偏移两倍）。测试需验证 canvas.translate 只取其一。
5. **Stop 按钮跨路径**：warm path kill PersistentShell，fresh path 要 kill FreshProcessShell 集合，两者不能漏。

---

### Task 1: FreshProcessShell 接线（ExecutionCoordinator）

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/sandbox/FreshProcessShell.kt`（已有 untracked，直接 add）
- Create: `src/android/app/src/main/java/com/openminis/app/sandbox/StderrDrain.kt`（已有 untracked，直接 add）
- Create: `src/android/app/src/main/java/com/openminis/app/sandbox/ForegroundCommandGroup.kt`（已有 untracked，直接 add）
- Modify: `src/android/app/src/main/java/com/openminis/app/sandbox/ExecutionCoordinator.kt`

**Interfaces:**
- Consumes: `FreshProcessShell.execute(command, timeout, envVars, lineCallback)` → `Pair<String, Int>`
- Consumes: `SandboxResourceGate.withCommandLock(resourceClass, ...)` 已存在
- Consumes: `BudgetClassifier.classify(command, resourceClass)` + `armMs(budget, command, timeout)` 已存在
- Produces: `ExecutionCoordinator.execute(..., executionStrategy: ShellExecutionStrategy = AUTO)` 新签名
- Produces: `ExecutionCoordinator.stopCurrentCommand(sessionId)` 扩展为同时 kill fresh shells

- [ ] **Step 1: 引入 ShellExecutionStrategy 枚举**

在 ExecutionCoordinator.kt 顶部（文件内或独立 enum）定义：
```kotlin
enum class ShellExecutionStrategy { AUTO, WARM_PERSISTENT, FRESH_PROCESS }
```
AUTO 行为与上游一致：长命令（>30s 或含 heavy 标记）默认 fresh，其余 warm。BudgetClassifier 已有 heavy/light 分类，用 budget 推导。

- [ ] **Step 2: 抽取 executeWarm 方法**

把现有 `execute()` 的方法体（`SandboxResourceGate.withCommandLock` 的 block 内全部 warm 路径逻辑）抽取为私有方法 `executeWarm(sessionId, command, armed, lineCallback, budget)`，返回 `CommandResult`。`execute()` 变为路由门面：
```kotlin
suspend fun execute(
    sessionId: String, command: String, timeout: Long = 600_000L,
    lineCallback: ((String) -> Unit)? = null,
    resourceClass: SandboxResourceGate.ResourceClass = AUTO,
    executionStrategy: ShellExecutionStrategy = AUTO,
): CommandResult
```

- [ ] **Step 3: 实现 executeFresh**

新增私有方法 `executeFresh(sessionId, command, armed, lineCallback, budget, fsSessionId)`：
1. 构建 env map（含 `MINIS_CHAT_SESSION_ID`、`MINIS_SESSION_ID`、envVarRepository 变量、NO_SECCOMP 若已标记）
2. 构建 bind mounts（复用 `buildSessionBindMounts`，`fsSessionId ?: sessionId`）
3. 创建 `FreshProcessShell(context, sessionId, bindMounts, command, env, lineCallback, budget)`
4. 加入 `freshShells[sessionId]` 集合
5. `shell.execute(command, armed, env)` → `Pair<String, Int>`
6.  finally 块从集合移除
7.  seccomp retry：`SeccompFallbackPolicy.shouldRetryWithoutSeccomp` + `attempt(noSeccomp=true)`
8.  返回 `CommandResult(output, exitCode, durationMs)`

- [ ] **Step 4: 扩展 stopCurrentCommand 处理 fresh shells**

`stopCurrentCommand(sessionId)` 现有逻辑只杀 `shells[sessionId]`（PersistentShell）。新增：
```kotlin
freshShells[sessionId]?.forEach { it.stop() }
```
在现有 kill 逻辑之后（不替换）。

- [ ] **Step 5: sessionDidTerminate 清理 freshShells**

`sessionDidTerminate(sessionId)` 现有逻辑清理 `shells`、`mutexes`、`mountedSessionIds`。新增 `freshShells.remove(sessionId)`。

- [ ] **Step 6: Commit 新建文件 + 接线改动**

```bash
git add src/android/app/src/main/java/com/openminis/app/sandbox/FreshProcessShell.kt \
        src/android/app/src/main/java/com/openminis/app/sandbox/StderrDrain.kt \
        src/android/app/src/main/java/com/openminis/app/sandbox/ForegroundCommandGroup.kt
# 接线改动在 ExecutionCoordinator.kt，同 commit
git commit -m "feat(sandbox): FreshProcessShell 接线到 ExecutionCoordinator"
```

---

### Task 2: BrowserTabPoolRegistry 全局 tab 仲裁

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPoolRegistry.kt`（已有 untracked，直接 add）
- Modify: `src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt`
- Modify: `src/android/app/src/main/java/com/openminis/app/MinisApp.kt`

**Interfaces:**
- Consumes: `BrowserTabPoolRegistry.register(pool)` / `unregister(pool)` / `requestSlot(pool)` / `handleMemoryPressure()`
- Consumes: `BrowserTabPoolRegistry.RegistryPool` 接口（registrySnapshot / evictTabForRegistry / preemptTabForRegistry / evictAllIdleForMemoryPressure）
- Produces: `BrowserTabPool : BrowserTabPoolRegistry.RegistryPool`

- [ ] **Step 1: 让 BrowserTabPool 实现 RegistryPool**

类声明改为 `class BrowserTabPool(private val context: Context) : BrowserTabPoolRegistry.RegistryPool`。

实现四个接口方法（参考上游对应方法，但保持我们现有的字段名）：
- `registrySnapshot()`: 映射 `_tabs.value + closingTabs` 为 `TabInfo(id, inUse, lastActivityDate)`
- `evictTabForRegistry(id)`: 找到 tab，调用 `removeTab(tab, rememberUrl=true)`
- `preemptTabForRegistry(id)`: 找到 tab，调用 `removeTab(tab, rememberUrl=true)` + 向持有该 tab 的 agent 返回可重试错误（通过标记 tab 状态或在 removeTab 中处理）
- `evictAllIdleForMemoryPressure()`: 遍历 `_tabs.value`，`!inUse && (runningActions[id] ?: 0) == 0` 的 tab 全部 `removeTab(tab, rememberUrl=true)`，返回计数

- [ ] **Step 2: 替换 reserveTab 自旋等待为注册表请求**

把 `reserveTab()` 中 `while (count() >= MAX_GLOBAL_TABS)` 自旋等待逻辑替换为：
1. `pools[this] = Unit`（保留我们的 WeakHashMap 跟踪）
2. `BrowserTabPoolRegistry.register(this)`（在首次 reserve 时注册到全局注册表）
3. `if (!BrowserTabPoolRegistry.requestSlot(this)) return false`
4. 若 requestSlot 成功但 count 仍 >= MAX_GLOBAL_TABS（本地池满了），走原有的本地 evict 逻辑（从其他 pool 找 idle tab）或返回 false

关键：上游的 `createTab` 中 `if (tabs.size >= effectiveMaxTabs(owner)) return null` 先检查本地上限，然后才 `requestSlot`。我们的 `createTab` 已有 `tabs.size >= MAX_TABS` 检查，只需把 `reserveTab()` 的 while 循环改为注册表请求。

- [ ] **Step 3: MinisApp.onTrimMemory 接入 handleMemoryPressure**

在 `MinisApp.onTrimMemory(level)` 中，现有逻辑之后（或替代现有逻辑）调用：
```kotlin
BrowserTabPoolRegistry.handleMemoryPressure()
```

- [ ] **Step 4: dispose 时 unregister**

`BrowserTabPool.dispose()` 中新增 `BrowserTabPoolRegistry.unregister(this)`。

- [ ] **Step 5: Commit**

```bash
git add src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPoolRegistry.kt
git commit -m "feat(browser): BrowserTabPoolRegistry 全局 tab 仲裁"
```

---

### Task 3: HeadlessRenderHost GPU 截图 + Scroll Offset 修复

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/browser/HeadlessRenderHost.kt`（已有 untracked，直接 add）
- Modify: `src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt`

**Interfaces:**
- Consumes: `HeadlessRenderHost.capture(webView, w, h)` → `Bitmap?`
- Produces: `BrowserUseManager.captureWebViewBitmap(gpu: Boolean = false)` 新签名
- Produces: `BrowserUseManager.readCssScrollOffset()` 新方法

- [ ] **Step 1: 新增 readCssScrollOffset**

在 BrowserUseManager 中新增私有方法 `readCssScrollOffset(): Pair<Float, Float>`：
执行 JS `"JSON.stringify({sx:window.scrollX||0,sy:window.scrollY||0})"`，解析 JSON，返回 `(scrollX, scrollY)` 为 Float。

- [ ] **Step 2: 修改 captureWebViewBitmap 签名并加 GPU 路径 + Scroll Offset**

把 `private suspend fun captureWebViewBitmap(): Bitmap?` 改为 `private suspend fun captureWebViewBitmap(gpu: Boolean = false): Bitmap?`。

在现有逻辑中，在 `webView.layout(0, 0, targetW, targetH)` 之后、`Bitmap.createBitmap` 之前，插入：
```kotlin
if (gpu) HeadlessRenderHost.capture(webView, w, h)?.let { return@withContext it }
```

在 `webView.draw(canvas)` 之前，插入 scroll offset 修复：
```kotlin
val cssScroll = readCssScrollOffset()
val offsetX = if (webView.scrollX != 0) webView.scrollX.toFloat() else cssScroll.first * density
val offsetY = if (webView.scrollY != 0) webView.scrollY.toFloat() else cssScroll.second * density
if (offsetX != 0f || offsetY != 0f) canvas.translate(-offsetX, -offsetY)
```

更新 log 语句包含 offset 信息。

- [ ] **Step 3: 在调用点传递 gpu=true**

把 `evaluateAndScreenshot(...)` 中 `val bitmap = captureWebViewBitmap()` 改为 `captureWebViewBitmap(gpu = true)`。

把 `performFullPageCapture(...)` 中 `captureWebViewBitmap()` 改为 `captureWebViewBitmap(gpu = !didStretch)`。

`captureLiveSnapshot()` 保持 `captureWebViewBitmap()`（gpu 默认 false，缩略图用软件 draw）。

- [ ] **Step 4: Commit 新建文件 + 接线改动**

```bash
git add src/android/app/src/main/java/com/openminis/app/browser/HeadlessRenderHost.kt
git commit -m "feat(browser): HeadlessRenderHost GPU 截图 + scroll offset 修复"
```

---

## Verification

- [ ] `:app:compileReleaseKotlin` 无错
- [ ] `:app:testReleaseUnitTest` 全部通过（或本地环境问题报错已标记）
- [ ] `python3 scripts/ci/check_architecture_policy.py` 通过（或 `--update-baseline` 若收紧）
- [ ] `python3 scripts/ci/check_cjk_literals.py` 通过
- [ ] 新文件行数 ≤ 800 行（已验证：FreshProcessShell 607、StderrDrain <50、ForegroundCommandGroup 201、BrowserTabPoolRegistry 248、HeadlessRenderHost 339）
- [ ] 修改文件等量置换或基线更新
