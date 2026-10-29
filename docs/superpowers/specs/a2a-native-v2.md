# a2a 原生化 v2：全功能原生内置

**状态**：侦察与设计（本文件）  
**日期**：2026-10-29  
**看板**：`a2a 原生化 v2：全功能原生内置（非插件）`

## 资产盘点

| 资产 | 位置 | 状态 |
|---|---|---|
| Rust 二进制 `liba2a.so` (aarch64) | `jniLibs/arm64-v8a/liba2a.so` · CI native-deps job 交叉编译 | ✅ 可用 · APK 内 |
| 面板 UI bundle（React SPA） | `assets/builtin_plugins/a2a-ui.zip` · 含 `index.html` + `mobile-columns.js` | ✅ 可用 · 窄屏适配已就绪 |
| UI CSS | `assets/builtin_plugins/a2a-ui-css.zip` | ✅ 可选覆盖 |
| NativeGatewayService | `plugins/builtin/NativeGatewayService.kt` · ProcessBuilder 拉起二进制、loopback health poll | ✅ 可用 |
| NativeGatewayInstaller | `plugins/builtin/NativeGatewayInstaller.kt` · 从 APK assets 部署 UI bundle 到 `files/native-gateways/a2a/ui/` | ✅ 可用 |
| BuiltinPluginCatalog | `Descriptor.isNative` + `nativeBinary` + `panelPath` 通用描述 | ✅ 可用 · 需扩展 |
| BuiltinPluginStore | `NOT_INSTALLED → INSTALLING → INSTALLED` 状态机 | ⚠️ a2a entry 从此退休 |
| BuiltinFreeLanes / Agent2ApiFreeLane | `laneId = A2A_ID` · `providerId = a2a-gateway` | ✅ 保留 |
| 市场卡片 `BuiltinPluginsSection` | 插件市场里 a2a 行：安装按钮 → switch + 打开面板 | ⚠️ 推倒重做 |
| OAuthRedirectActivity | loopback 端口拦截 · 当前覆盖 Claude/OpenAI/Gemini/OpenRouter | ✅ 不参与 a2a（网关 callback_server 自收，登录窗口 WebView 直接加载 loopback 回调） |
| `mobile-columns.js` | ≥767px 桌面表 / ≤767px 卡片化列 | ✅ 手机端面板即开即用 |
| 登录桥注入 `workbuddyDesktop` | 网关 **无条件**注入：`server/static_files.rs::inject_web_shim` 把 `web_shim.rs::shim_js()` 整段内联进 index.html 的 `<head>` | ✅ **由网关自给** · 无需 app 侧注入任何 JS 桥 |
| web_shim 桥能力 | `platform:'web'` + `getState/startLogin/submitLoginCallback/getLoginState/cancelLogin/startAutoclawOauthLogin/onLoginState/refreshModels/…` 全套，纯 HTTP `fetch` 同源 `/api/*` | ✅ 全部已实现 |
| shim 登录状态机 | `runLoginFlow`：`window.open('about:blank')` → 导航 authUrl → 3000ms 轮询 `GET /api/session/login/wait?state=` → 完成抛 `{ok, session}`；popup 被拦时 `showLinkFallback` 弹链接兜底 | ✅ 已实现 · 依赖 `window.open` |
| 面板 isWebPanel 判定 | `app.js:11` 检查 `api?.platform === 'web'` → **shim 注入后恒为 true**，登录按钮在网页形态全程可用 | ✅ 无碍 |
| 网关 API：`/api/session/login/start/wait/cancel/callback` | 上游 server core | ✅ 已实现 · 本机已实测：`login/start` 返回 `{state, authUrl}`；`login/wait` 未知 state 报「登录任务不存在或已过期」 |

## 架构决策

### 推倒内容

1. **插件市场 a2a entry 撤除** — `BuiltinPluginsSection` 不再为 a2a 渲染安装/开关/面板行。
2. **`BuiltinPluginStore` a2a state 退休** — a2a 不再是"可安装插件"，而是 APK 自带的基础设施。
3. **面板打开路径重写** — 当前 `openPanel` 用 `ACTION_VIEW` 把 loopback URL 抛给系统 → 跳到手机浏览器。替换为 app 内全屏 Compose 目的地。

### 保留内容

1. **`NativeGatewayService`** — 二进制启动/停止/健康探测保持不变。
2. **`NativeGatewayInstaller`** — panel bundle 从 APK assets 部署到 files/ 的逻辑保留，但触发时机改为首次进入 a2a settings 页（或启动网关前），不再依赖市场 UI。
3. **`Agent2ApiFreeLane`** — 免费通道保持不变；a2a 网关启动后 lane seeding 照旧。
4. **`BuiltinPluginCatalog.Descriptor` (a2a)** — descriptor 自身保留（nativeBinary、panelPath 等仍是配置源），只是消费方式从 "plugin market card" 变为 "native settings card"。

## 新页面架构

### 入口

设置 → 新行「Agent2API 网关」→ 独立图标（`Icons.Outlined.Api` 或 `Icons.Outlined.Dns`）→ 点击进入全屏面板页。

该行同时承载：
- 开关：启动/停止网关（复用 `NativeGatewayService.toggle`）
- 状态文案：运行中/已停止 + loopback URL

### 路由

`Routes.kt` 新增：
```kotlin
const val AGENT2API_PANEL = "agent2api_panel"
```

### 目的地：`Agent2ApiPanelScreen`

全屏 Compose destination，结构：

```
┌──────────────────────────┐
│  TopAppBar: title+back   │
│   + 「在浏览器打开」动作    │
├──────────────────────────┤
│                          │
│   WebView (fillMaxSize)  │
│                          │
│   ── 加载 URL:          │
│      http://127.0.0.1:  │
│      18901/               │
│   ── 无 JS 注入          │
│   ── 拦截:               │
│      外链/自定义 scheme    │
└──────────────────────────┘
```

**WebView 配置**：
- 独立 WebView（非 BrowserTabPool 复用），生命周期与 Compose 目的地绑定
- `settings.javaScriptEnabled = true`
- `settings.domStorageEnabled = true`
- `settings.mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOWED`（loopback HTTP）
- `settings.setSupportMultipleWindows(true)` + `settings.javaScriptCanOpenWindowsAutomatically = true`（shim 登录靠 `window.open`）
- `webViewClient.shouldOverrideUrlLoading` 拦截（面板窗口）：
  1. `http://127.0.0.1:*` → 允许（面板内导航）
  2. 非 loopback 的 `http(s)://` 新导航 → BrowserSheet 或系统浏览器
  3. 自定义 scheme（`office-raccoon://` 等）→ `ACTION_VIEW` 抛系统
- `webChromeClient.onCreateWindow` → popup 宿主（见登录通道）—— 不靠 `shouldOverrideUrlLoading` 拦 OAuth 回调

### 网关启动绑定

进入页面时：
1. 读 `NativeGatewayService.statusOf("a2a")`。
2. 若 `state != RUNNING`：显示启动中 UI（全屏 loading），调用 `NativeGatewayService.toggle(context, d, true)`，等待 `statuses.flow` 变为 RUNNING，然后加载 WebView。
3. 若 `RUNNING`：直接加载。
4. 退出页面时：**不停止网关**（gateway 后台常驻）。

## 登录通道设计（2026-10-29 侦察重写：零自定义 Bridge）

### 核心发现：网关自带的 web_shim 就是完整答案

v3.0.8 的 `server/static_files.rs` **无条件**把 `web_shim::shim_js()` 注入每次 index.html 响应（`<head>` 后插一段内联脚本），本机已实测 HTML 头里确实带整段 shim。shim 内建 `window.workbuddyDesktop` + `window.__TAURI_INTERNALS__`，纯 HTTP 实现桌面 Tauri `bridge.rs` 的同一契约：

- 登录：`startLogin(edition, mode, provider)` → `POST /api/session/login/start` 拿 `{state, authUrl}` → 交给 `runLoginFlow`
- 状态机：`loginActive/loginProvider` + `onLoginState` 订阅，与桌面壳 `login:state` 事件同构
- 等待：`pollWait(state)` 每 3000ms `GET /api/session/login/wait?state=`，5 分钟 deadline
- 兜底：`window.open` 被拦 → `showLinkFallback(authUrl)` 原生覆盖层给可点链接；`needsManualCallback`（trae/accio/codearts/autoclaw-intl）→ 粘贴回调地址覆盖层与 pollWait `Promise.race`

**结论：app 侧不需要注入任何自定义 JS；唯一要补的是 `window.open` 的宿主管线。** 面板在 WebView 里加载后，登录按钮天然可用（isWebPanel 恒 true、桥恒在），剩余工作全在「popup 窗口开在哪」这一层。

### 手机浏览器登录失败的根因（已定位）

用户在系统 Chrome 里面板可开、登录后无凭证，链路是：

```
window.open('about:blank','a2a-login') → Chrome Android 默认拦弹窗 → null
window.open(authUrl,'_blank')          → 再拦 → null
showLinkFallback(authUrl)              → 覆盖层里的 <a target="_blank"> 链接
```

1. 用户点链接 → Chrome 新 tab 打开 `copilot.tencent.com`（CN）或 Zai（INTL）授权页 → 登录完成 → 重定向回 `http://127.0.0.1:…`（loopback 回调由网关 callback_server 接住，同机可达）
2. 面板 tab 上的 `pollWait` 在 Chrome 后台 tab 定时器被节流（3s → ≥60s）后仍会在 5 分钟窗口内命中完成 —— **理论应通，但实测失败**
3. 失败点候选：
   - 「登录完成」与「回调落网关」之间的断点（INTL 授权页在 Android Chrome 里拒绝「不安全浏览器」场景、loopback 重定向被 Chrome 拦）；
   - `needsManualCallback` 族的粘贴覆盖层在窄屏 Chrome 上不可操作（覆盖层叠在 tab 切走后面板上，用户切回来时状态机已 `finally` 清态）；
   - 用户在「登录后」习惯性关掉新 tab，此时 gateway 尚未完成 code 交换（特指 callback 已 302 到 gateway 但 pollWait 那侧 tab 被 Chrome 冻结）。

这些与「桥不存在」无关 —— 桥一直在；是浏览器 tab 生命周期 + 窄屏覆盖层可用性的问题。app 内 WebView 宿主恰好把这三点全部接管。

### WebView 宿主方案（核心设计，替代原「自定义 Bridge」）

**不写 Kotlin `A2aBridge`，不注入 JS。** WebView 只做三件事：

1. **加载 + 设置**：`http://127.0.0.1:18901/`，`javaScriptEnabled` + `domStorageEnabled` + `mixedContentMode` + `setSupportMultipleWindows(true)` + `settings.setJavaScriptCanOpenWindowsAutomatically(true)`。
2. **`WebChromeClient.onCreateWindow`** → 内建 popup 宿主：
   - 默认（== 桌面「内嵌窗口」语义）：**新开一个 app 内全屏登录 WebView**（Compose Dialog/独立窗口 `WebView`），把 `resultMsg.obj = WebViewTransport(newWebView)` 回给 JS —— `window.open` 返回真对象，shim 的 `popup.location.href = authUrl` / `popup.close()` 都能照桌面语义跑。
   - mode == `external`（「系统浏览器」选项）时：shim 实际忽略 mode（web 端一律 `window.open`），所以 external 语义由 app 侧兜底 —— 见 TopAppBar 的「在浏览器打开」动作：把**面板 URL** 交给系统 Chrome，用户在那个世界里用自带 shim + 链接兜底完成登录。要不要让 `onCreateWindow` 依据某个标志改开 Custom Tab，实现阶段再定（首选：不做，保持「内嵌=app 内窗口、外链=系统浏览器」两条干净通路）。
3. **循环回调不需要 app 拦**：登录窗口内 z.ai/copilot 重定向回 `http://127.0.0.1:*` 时，**让 WebView 正常加载**（网关 callback_server 自己应答并 302），shim 的面板侧 `pollWait` 会命中 done 并 `popup.close()` 触发 `onCloseWindow`。需要拦的只有：
   - `http(s)://` 非 127.0.0.1 的 `target=_blank`/外链 → app 内 BrowserSheet 或系统浏览器；
   - `office-raccoon://`、`intent://` 等自定义 scheme → 系统 `ACTION_VIEW`。

### 登录流程（shim 内部完成，app 侧零参与）

```
面板页 JS (shim)                     WebViewChrome(onCreateWindow)        gateway:18901
   │ window.open('about:blank')            │ → 新建登录 WebView 窗口           │
   │ popup.location.href = authUrl         │ → 登录 WebView loadUrl(authUrl)   │
   │                                        │ → https://copilot.tencent.com…  → │
   │ pollWait(state) 每 3s                 │                                   │
   │ ◄────────── GET /api/session/login/wait?state=  ─────────────────────►│
   │ （用户完成登录 → 授权页重定向 127.0.0.1 → gateway callback_server 收码交换）
   │ ◄────────── {done:true, session} ─────────────────────────────────────│
   │ popup.close() ─────────────────────── │ → onCloseWindow 关闭登录窗口      │
   │ refreshSession / 账户列表刷新          │                                   │
```

### 丢弃的原方案

原「自定义 A2aBridge + addJavascriptInterface + 拦截 oauth/callback 手动 POST」整体作废：
- 网关已注入同名对象 —— 二次 `addJavascriptInterface('workbuddyDesktop')` 会与 shim 的 `window.workbuddyDesktop` 打架（后者是普通 JS 对象属性，addJavascriptInterface 会覆盖它）；
- 拦截回调手动 POST 会绕开 gateway callback_server 的登记端口抢占/302 回填逻辑（`server/api/session.rs::login_oauth_start` 的 `gateway_base` 契约），属重复实现还更脆弱；
- 「面板 JS 适配」一段的前提不再成立：shim 注入后面板就是 web 形态，`isWebPanel()` 恒 true。

### 登录 WebView 窗口设计

- **触发**：`WebChromeClient.onCreateWindow`（面板内任意 `window.open` 都经此，不特判登录与否）。
- **UI**：全屏 `Dialog`（含 TopAppBar「关闭登录」按钮 → `webView.destroy()` 并回 `onCloseWindow` 语义），**不做** 底部半屏（授权页在窄屏半屏里难操作）。
- **Cookie 隔离**：两块 —— 面板 WebView 与登录窗口 WebView **各自独立实例**（独立 CookieManager 域下 127.0.0.1 vs 授权站点天然隔离；连接多账号互不影响即桌面「内嵌窗口」语义）。未来要无痕多开再加 `WebViewProfile`。
- **导航控制**（登录窗口的 `shouldOverrideUrlLoading`）：一律放行（loadUrl 正常加载）；仅 `popup.close()` → `onCloseWindow` 与 Dialog 关闭按钮做清理。
- **UA**：登录窗口与面板 WebView 默认 UA（auth 站点偶发拒绝裸 WebView UA 时再考虑伪装 Chrome UA —— 观察门槛）。

## 外部链接处理

面板里的外链分成三类：

1. **`window.open` / `target=_blank`（非登录）**：由同一套 `onCreateWindow` 接收 —— **实现阶段判定**：一律开 app 内 BrowserSheet（体验一致），或非 127.0.0.1 一律系统浏览器（更简单）。首选 BrowserSheet：`BrowserSheetHost` 在 `AppNavigation.kt` 已挂全局单例，从 panel screen 投递 `browserSheetEvent` 即可（SharedFlow → `LaunchedEffect` 消费链，实现时接线）。
2. **`data-external`（openReleasePage 等「打开原页面」链接）**：`shouldOverrideUrlLoading` 检查 URL 后跳 app 内 BrowserSheet，降级系统浏览器。
3. **`office-raccoon://` / `intent://` 等自定义 scheme**：`ACTION_VIEW` 抛系统。若 scheme 无法解析（Office 客户端未装）→ 面板 shim 自带「粘贴回调地址」覆盖层兜底（`submitLoginCallback`），无需 app 参与。

## Settings 集成

### 新行位置

`SettingsScreen.kt` 的 Settings 主列表（当前在「插件市场」上方某处）新增一行，位于「MCP 集成」与「插件市场」之间：

```kotlin
SettingsItem(
    icon = Icons.Outlined.Api,        // 或 Icons.Outlined.Dns
    iconColor = Color(0xFF5E5CE6),    // 靛紫，区分于 Extension (orange)
    title = "Agent2API 网关",
    subtitle = "本地 AI 模型接入网关",  // 或用资源 string
    onClick = onAgent2ApiClick,
)
```

点击 → `navController.safeNavigate(Routes.AGENT2API_PANEL)`。

### 网关开关

设置行的 trailing 区域放一个 `Switch`，状态绑定 `NativeGatewayService.statuses` flow。点击开关 → `NativeGatewayService.toggle(context, d, target)`。状态穿越：STARTING/STOPPING 时开关 disabled + 小 spinner。

### 没启动的网关

进入 `Agent2ApiPanelScreen` 后的视图 = 全屏 WebView + TopAppBar（后退 + 「在浏览器打开」）。网关没启动时显示启动 loading 画面。

### TopAppBar 动作

右上角「在浏览器打开」→ `ACTION_VIEW` 打开 `http://127.0.0.1:18901/`（系统 Chrome）。作用：
- 追加账号时遇 Google 拒绝内嵌 WebView，用户切到系统浏览器走「shim + 链接兜底」完成登录；
- 提供 App 内全屏面板之外的第二通路，不作为主路径。

## 实现路径

按依赖顺序：

| # | 文件 | 改动 | 危险度 |
|---|---|---|---|
| 1 | `Routes.kt` | + `AGENT2API_PANEL` | 低 |
| 2 | `Agent2ApiPanelScreen.kt`（新）| 全屏 Compose 目的地：面板 WebView + 网关启动绑定 + 外链拦截 + **内置 popup 宿主**（`onCreateWindow` → 登录 Dialog WebView，`WebViewTransport` 回填） | 高 |
| 3 | `AppNavigation.kt` | + composable 路由注册 + BrowserSheet 外链钩子 | 中 |
| 4 | `SettingsScreen.kt` | + 「Agent2API 网关」行 + 开关 | 低 |
| 5 | `BuiltinPluginsSection.kt` | a2a entry 移除（或改为仅读状态卡） | 低 |
| 6 | `BuiltinPluginStore.kt` | a2a state 清理（现存用户数据迁移：INSTALLED → 直接可用） | 中 |
| 7 | `BuiltinPluginCatalog.kt` | `ALL` 列表去掉 a2a（或标记 `hidden` 属性，令 Section 跳过但 descriptor 仍可用） | 低 |

> 原「A2aBridge.kt + addJavascriptInterface」整体作废，见[登录通道设计]。

## 不变

- `liba2a.so` 编译链（`native-deps.yml`）不变。
- `Agent2ApiFreeLane` lane 语义不变。
- `NativeGatewayService` 进程管理不变。
- `NativeGatewayInstaller` panel bundle 部署不变。
- `OAuthRedirectActivity` 不新增 a2a 端口拦截（网关 callback_server 自收；登录窗口 WebView 正常加载 loopback 回调即可）。

## 验收标准

1. **设置入口可见**：Settings 列表出现「Agent2API 网关」行，点击进入全屏面板页面。
2. **面板在 app 内打开**：不跳转系统浏览器，不依赖外部应用。
3. **网关可启停**：设置行的开关能启动/停止 liba2a.so，状态实时反映。
4. **登录全链路（内嵌窗口）**：面板内点击登录按钮 → `window.open` 触发 `onCreateWindow` → app 内登录窗口加载授权页 → 完成登录 → 授权页重定向 127.0.0.1 → 网关收码 → 面板 pollWait 命中 → `popup.close()` 关闭登录窗口 → 账号出现在账户列表。
5. **登录兜底（系统浏览器）**：`在浏览器打开` → Chrome 内 shim 链接兜底完成登录 → 回到 app 刷新可见新账号。
6. **多账号连续登录**：第二次「添加账户」时新开独立登录窗口，与前一次互不串 cookie（内嵌窗口语义）。
7. **外链不跳失**：面板内外部链接在 app 内 BrowserSheet 或系统浏览器打开；自定义 scheme 抛系统。
8. **手机端可用**：`mobile-columns.js` 生效，表格卡片化，触控友好。
9. **退出页面不杀网关**：返回设置后网关继续运行，其他会话的 a2a 通道不受影响。
10. **插件市场 a2a 条目消失**：`BuiltinPluginsSection` 不再显示 a2a 安装/开关行。
11. **存量用户平滑迁移**：已安装 a2a 插件的用户升级到 v2 后，网关直接可用，无重新安装步骤。