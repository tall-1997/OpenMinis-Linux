# 01 · UI 架构

本文覆盖：信息架构、设计令牌、组件规范、设置页数据模型、导航模型、大屏形态、状态订阅规范。
每节末尾的「任务」编号对应 `03-task-board.md`。

---

## 1. 信息架构（现状 → 目标）

### 1.1 现状

设置入口 `ui/settings/SettingsScreen.kt`（569 行）用 30 个手写 `SettingsRow` 平铺，
分 8 个 `SettingsSection`：

| 分组 | 行数 | 代表项 |
|---|---:|---|
| LLM Providers | 3 | 管理供应商 / 模型组 / Token 用量 |
| Appearance | 1 | 外观 |
| （后续若干分组） | … | 多 Agent / 插件 / 技能 / 翻译 / 记忆 / 日志 / 终端 / 语言 / 存储 / 文件夹 / 备份 |

问题：
- 每一行是 8~12 行 Compose 代码（icon / iconColor / title / subtitle / onClick / showDivider），
  新增一项要同时改：屏幕文件、导航路由、字符串资源（18 个 locale）。
- 30 行里 icon 重复严重：`Icons.Outlined.Settings` 出现 3 次，`Extension` 2 次，`Terminal` 2 次，
  `Folder` / `FolderShared` 各 1 次。图标语义已失效。
- 分组标题用 `stringResource(R.string.settings_section_*)`，分组本身没有模型，无法做搜索/折叠/权限门控。

### 1.2 目标：设置项声明式化

引入三个类型（放 `ui/settings/model/`）：

```kotlin
/** 一个可点击的设置行。 */
@Immutable
data class SettingsEntry(
    val id: String,                       // 稳定 id，用于搜索/埋点/深链
    val title: String,                    // 已 resolve 的文案
    val subtitle: String? = null,
    val icon: ImageVector,
    val tint: SettingsTint = SettingsTint.Primary,
    val badge: SettingsBadge? = null,     // 未读数 / "需授权" / "Beta"
    val enabled: Boolean = true,
    val visibleWhen: (SettingsContext) -> Boolean = { true },
    val action: SettingsAction,
)

sealed interface SettingsAction {
    data class Navigate(val route: Any) : SettingsAction   // 类型安全路由对象
    data class Toggle(val key: PrefKey<Boolean>) : SettingsAction
    data class Sheet(val id: String) : SettingsAction
    data class Intent(val uri: String) : SettingsAction
}

/** 一个分组。 */
@Immutable
data class SettingsGroup(
    val id: String,
    val title: String?,
    val footer: String? = null,
    val entries: List<SettingsEntry>,
)

/** 构建期上下文：注入权限状态、功能开关，用于 visibleWhen。 */
data class SettingsContext(
    val hasShizuku: Boolean,
    val hasSu: Boolean,
    val hasA11y: Boolean,
    val isPro: Boolean,
    val featureFlags: Set<String>,
)
```

页面渲染变成单一 `LazyColumn`：

```kotlin
@Composable
fun SettingsScreen(groups: List<SettingsGroup>, onAction: (SettingsAction) -> Unit) {
    LazyColumn {
        groups.forEach { g ->
            item(key = "h:${g.id}") { SettingsGroupHeader(g.title) }
            items(g.entries, key = { it.id }) { e -> SettingsRowV2(e, onAction) }
            g.footer?.let { item(key = "f:${g.id}") { SettingsGroupFooter(it) } }
        }
    }
}
```

分组数据由 `SettingsCatalog` 提供（`ui/settings/model/SettingsCatalog.kt`），
一个 `@Composable fun rememberSettingsGroups(ctx: SettingsContext): List<SettingsGroup>`，
内部只做 `stringResource` 解析 + 列表拼装。**新增设置项 = 往列表加一行，不改布局。**

### 1.3 目标：信息架构重排

当前 8 组 30 项，目标收敛为 6 组，按「用户意图」而非「实现模块」分组：

| 组 | 含项 | 合并自 |
|---|---|---|
| **模型与能力** | 供应商、模型组、Token 用量、Web 搜索、翻译、思考翻译 | LLM Providers + Web Search + Translation |
| **对话与人格** | 灵魂(SOUL)、记忆、多 Agent、Agent Loop、工具限额、插件市场 | Soul + Memory + MultiAgent + Plugins |
| **设备与终端** | 终端/rootfs、Shizuku、SU、无障碍、后台、存储、挂载文件夹、共享文件夹 | Terminal + Permissions + Storage + Folders |
| **数据** | 备份恢复、日志、配置审计、用量统计 | Backup + Logs + Audit |
| **外观** | 主题、字号、字重、行距、语言 | Appearance |
| **关于** | 版本、开源许可、反馈、更新 | About |

**任务**：`C1-1`（类型定义）`C1-2`（catalog 迁移 30 项）`C1-3`（分组重排）`C1-4`（搜索）

---

## 2. 设计令牌

### 2.1 现状

`ui/tokens/MinisTokens.kt` 是生成物，由 `scripts/ui/gen_tokens.py` 从源码反推。

本轮复跑生成器（`--ui src/android/app/src/main/java/com/openminis/app/ui`）实测输出：

```
ui 文件      : 426
颜色         : 284 处 / 131 个不同值  -> 命名覆盖 123 处 (43%)
品牌色       : 浅色 20 / 深色 18
dp           : 3464 处 /  83 个不同值
sp           :    0 处 /  0 个不同值
```

`sp` 已完全归零（上一轮工作成果），但 **`dp` 仍有 3464 处散落在 83 个不同数值上**，
颜色命名覆盖只有 43%。

令牌层文件：
- `MinisTokens.kt` — 生成的常量
- `token_data.json` — 生成的数据源
- `ComposeAdapters.kt` — `TextUnit` / `Dp` 桥接

### 2.2 目标：dp 角色化

`sp` 的经验（第三轮）：**曲线插值必然错，按用途命名的角色表才对**。同一字号能配 4 个不同行高
（13sp 正文配 19、等宽 13sp 配 18、diff 行 13sp 配 17、输入框 16.5sp 配 16.25）。
`dp` 同理，83 个值里大量是「同一角色、不同人写的不同值」。

目标：`MinisSpacing` 角色表，按**语义**而非数值命名：

| 角色 | 用途 | 典型值 |
|---|---|---|
| `hairline` | 1px 分隔线 | 0.5 |
| `insetXs` | 图标与文字 | 4 |
| `insetSm` | 行内元素间距 | 8 |
| `insetMd` | 卡片内边距 | 12 |
| `insetLg` | 区块内边距 | 16 |
| `insetXl` | 页面边距 | 20 |
| `stackSm` | 紧凑堆叠 | 6 |
| `stackMd` | 常规堆叠 | 10 |
| `stackLg` | 宽松堆叠 | 14 |
| `radiusSm` | 小圆角 | 8 |
| `radiusMd` | 卡片圆角 | 12 |
| `radiusLg` | 弹层圆角 | 20 |
| `radiusPill` | 胶囊 | 999 |
| `iconSm` | 小图标 | 16 |
| `iconMd` | 常规图标 | 20 |
| `iconLg` | 大图标 | 24 |
| `rowMin` | 行最小高度 | 44 |
| `barSm` / `barMd` | 顶栏 / 底栏高度 | 44 / 56 |

**关键：不做 1:1 数值映射**。83 个值映射到 18 个角色，必须逐个判断用途，
判断后必须用 difflib 逐点反转原值比对（上一轮踩过：37 处里 4 处角色映射错，
纯靠「同块内数值邻近」配对会跨 Composable 块误配）。

### 2.3 颜色与字体

已有：`MinisColor`（含 `IosBlue` / `IosGreen` 等 iOS 观感令牌）、
`MinisLineHeight`（12 个行高角色）、`MinisType`（字号角色）。

目标：`IosXxx` 命名改为语义命名（`AccentBlue` / `SuccessGreen`），
但**保留旧名作为 `@Deprecated` 别名**，避免 400+ 处引用一次性全改。
`scripts/ci/check_token_semantics.py` 已存在，扩展它检查语义命名。

**任务**：`C3-1`（`MinisSpacing` 角色表）`C3-2`（83 值角色映射 + difflib 反转校验）
`C3-3`（`gen_tokens.py` 支持 spacing 生成）`C3-4`（颜色语义化 + 别名保留）

---

## 3. 组件规范

### 3.1 现状问题

- `ui/chat` 225 文件 64,829 行，`ChatScreen.kt` 单文件 5585 行。
- `MinisTextKitGesture.kt` 1668 行 + `MinisTextKitSelection.kt` 1244 行 = 2912 行，
  一套自研文本选择/手势系统。
- `StreamingMarkdownText.kt` 1399 行。
- 组件之间通过参数透传状态，无统一 `UiState` 契约。

### 3.2 目标：三层组件模型

```
Screen（页面）       —— 只做：拿 state、渲染 Layout、转发 event
   ↓
Section（区块）      —— 只做：一屏内的一个语义区块，可独立预览
   ↓
Primitive（原子）    —— 只做：一个视觉单元，无业务、无状态
```

规则（写进 `check_component_layers.py` 守卫）：

1. `Screen` 文件 ≤ 300 行，只允许一个公开 `@Composable`。
2. `Screen` 内不允许出现 `LaunchedEffect` 之外的状态修改；所有 event 通过 `onEvent: (ChatEvent) -> Unit` 上抛。
3. `Primitive` 不允许 import `data.*` 或 `provider.*`。
4. 每个 `Section` 必须有 `@Preview`。

### 3.3 文本选择系统

`MinisTextKit*` 2912 行自研。Compose 1.9+ 的 `BasicTextField2`（现 `BasicTextField` 新版）
已提供 `TextFieldState` + `Selection` API，能覆盖大部分场景。

策略：**不重写**，先做边界收窄 —— 把 2912 行拆成
`selection/`（选区模型 + 手势）/ `menu/`（上下文菜单）/ `render/`（高亮绘制）三个子包，
每个 ≤ 800 行。是否迁移到新 API 放到最后一批评估。

**任务**：`C5-1`（三层模型守卫）`C5-2`（ChatScreen 拆分 5585 → 若干 ≤300 行 Screen）
`C5-3`（MinisTextKit 拆包）`C5-4`（StreamingMarkdownText 拆包）

---

## 4. 导航模型

### 4.1 现状

`ui/navigation/AppNavigation.kt`（1053 行）+ `navigation/Routes.kt`（203 行）。
实测：36 个 `composable(...)` 目的地，引用 70 个 `Routes.*` 常量（25 处单行直引 +
11 处多行带参数）。全部是 `const val` 字符串，参数靠手写拼接与解析。
`Routes.kt` 里已有 70 行「入口矩阵」注释，记录 11 个入口（launcher / shortcut / assistant /
widget / QS tile / notification / share sheet / 外部深链 / HTML 预览 / 应用内链接）各自落到哪个路由 ——
这说明入口与路由的对应关系已经复杂到需要单独维护一张表，字符串路由是根源。

### 4.2 目标：类型安全导航（navigation-compose 2.10.2）

```kotlin
@Serializable data object SettingsRoute
@Serializable data class ProviderDetailRoute(val instanceId: String)
@Serializable data class ChatRoute(val sessionId: String, val draft: Boolean = false)
@Serializable data class FilePreviewRoute(val path: String, val line: Int? = null)

NavHost(navController, startDestination = ChatRoute(sessionId = "__new__")) {
    composable<ChatRoute> { backStackEntry ->
        val r: ChatRoute = backStackEntry.toRoute()
        ChatScreen(sessionId = r.sessionId, isDraft = r.draft, ...)
    }
    composable<SettingsRoute> { SettingsScreen(...) }
}
```

收益：路由参数编译期检查、`toRoute()` 免手写解析、深链天然对齐（`minis://` 已是既有约定）。

**任务**：`C2-1`（定义 36 个路由对象，替代 70 个 `Routes` 常量）`C2-2`（NavHost 迁移）`C2-3`（深链对齐）
`C2-4`（守卫：禁止 `composable(Routes.` 常量形态）

---

## 5. 大屏形态

### 5.1 现状

依赖已引入：`material3-adaptive:1.2.0`、`adaptive-layout`、`adaptive-navigation`、
`material3-window-size-class`。但实际使用面很窄 —— 列表页与详情页在大屏上仍是「推入替换」。

### 5.2 目标

用 `ListDetailPaneScaffold` 统一三类主从：

| 场景 | 小屏 | 大屏 |
|---|---|---|
| 会话列表 ↔ 对话 | 推入 | 双栏 |
| 供应商列表 ↔ 供应商详情 | 推入 | 双栏 |
| 设置主页 ↔ 设置子页 | 推入 | 双栏（设置项可常驻） |

`SupportingPaneScaffold` 用于工具详情 / 文件预览等辅助面板。

**任务**：`C4-1`（会话主从）`C4-2`（供应商主从）`C4-3`（设置主从）

---

## 6. 状态订阅规范

### 6.1 现状

- 423 处 `collectAsState`（非 lifecycle 版本）。
- 59 条 `MutableStateFlow` 各自暴露，UI 侧自由订阅。

问题：`collectAsState` 在后台仍收集，浪费；且 59 条流意味着 UI 侧无法拿到「一致快照」——
两条流分别更新时，界面会渲染出中间态。

### 6.2 目标

1. **统一 `collectAsStateWithLifecycle`**（lifecycle 2.11.1），后台自动停止收集。
2. **单一 `ChatUiState`**（见 `02-backend-architecture.md` §2），UI 只订一条流。
3. **事件用 `Channel` 而非 `SharedFlow`**：一次性事件（滚动、Toast、导航）用
   `Channel(Channel.BUFFERED)` + `receiveAsFlow()`，避免重放。

守卫 `check_state_subscription.py`：
- 禁止裸 `collectAsState(`（必须 `collectAsStateWithLifecycle`）
- 禁止 UI 层直接 `MutableStateFlow`（必须在 state holder 内）

**任务**：`B3-1`（423 处替换）`B3-2`（事件改 Channel）`B3-3`（守卫）

---

## 7. 验证基础设施（`verify_all.py`）

### 7.1 为什么需要一个入口

改造前：16 个守卫是 16 个独立 Python 脚本，CI 里 16 个 step 各自调用。本地要跑全得
背一串命令，实际上没人跑全 —— 于是「改完只跑了自己记得的那两条」是常态，漂移在下一个
PR 的 CI 里才暴露。

`scripts/ci/verify_all.py`：一条命令，`-j 4` 并行，输出统一汇总表，任一失败即非零退出。
实测 18 项，串行 ~147s → 并行 ~41s（4 路）。

```bash
python3 scripts/ci/verify_all.py            # 全跑
python3 scripts/ci/verify_all.py -j 4       # 四路并行
python3 scripts/ci/verify_all.py --selftest # 只跑静态检查的自测
python3 scripts/ci/verify_all.py --list     # 列出所有 gate
python3 scripts/ci/verify_all.py --only prefs   # 名字子串过滤
```

### 7.2 合并时暴露的三个真缺陷（最有价值的产出）

为了做「括号配平」这条静态检查，需要先把源码里的注释与字符串掩成空白 ——
`check_token_scope.py` 里已有一份 `mask_source`。直接复用后发现 **26 个文件报不配平**，
逐个查下去全是假阳性，每一个都对应 `mask_source` 的一个真实缺陷：

**缺陷一：不支持 Kotlin 字符串模板 `${...}`**

```kotlin
val s = "${c.optString("data").length} chars"
```

朴素实现从第一个 `"` 开始找下一个 `"`，于是在 `"data"` 的引号处收尾 —— 但那是
**表达式内部的字符串**，不是外层串的边界。收尾之后，后面的 `).length} chars"` 被当成
代码，整段偏移。`McpJsonRpc.kt` 因此少算 5 个右括号。

正解：进入 `${` 后按**代码**状态继续扫描（其中的括号要计数、可以嵌套字符串），
只在深度归零的 `}` 处回到字符串状态。

**缺陷二：不支持反引号标识符**

```kotlin
fun `restoreForDisplay only restores that display's entries`() {
```

Kotlin 允许用反引号包住任意函数名，里面可以有撇号。朴素实现看到 `'` 就进字符字面量分支，
一路找闭合的单引号，找不到就吞到行尾 —— 后续整段代码被当字符串。**17 个测试文件**因此报错。

正解：加反引号分支，整段（含内部撇号）掩掉。

**缺陷三：原生串（`"""`）结尾引号规则错**

Kotlin 原生串里，内容可以以引号结尾，写法是连续 4 个引号 —— 前 1 个是内容，后 3 个是结束符：

```kotlin
val json = """{"name="image""""   // 内容 {"name="image  + 结束符 """
val re   = Regex(""""v"\s*:\s*"((?:\\.|[^"\\])*)"""")
```

朴素实现遇到第一个 `"""` 就收尾，剩下的引号又开一个新串，把后文全部吞掉。
`OpenAIEditImageTest.kt`、`HistoryDigest.kt` 等 5 个文件因此报错。

正解：遇到引号时先数连续个数 N，N≥3 才收尾（用后 3 个），前面的 N-3 个归内容。

### 7.3 自测是这类工具的必需品

三个缺陷全部是「假阳性」，也就是工具报错、代码没错。这类错误最危险的地方在于：
它的修法是**放宽工具**，而放宽工具很容易顺手放过真错误。

所以 `verify_all.py` 的自测里，每个缺陷都配两条用例：一条证明特征被正确掩掉，
一条证明**在同样特征下的真实不配平仍然报错**。当前 18 条用例：

```
注释 / 块注释 / 字符串 / 三引号 / 字符字面量左括号 / 字符字面量右括号 / 转义字符
真实不配平 / 模板内嵌套字符串 / 模板内嵌套字符串带括号 / 模板内块 / 模板里真实不配平
反引号含撇号 / 反引号含括号 / 原生串内容首尾各一引号 / 原生串内含单引号
原生串后接代码 / 正则原生串
```

### 7.4 覆盖范围与边界

| 检查 | 手段 | 能抓什么 | 抓不到什么 |
|---|---|---|---|
| `static-balance` | 掩码后数 `{}` `()` | 括号/引号层面的结构性破坏 | 类型错误、符号不存在 |
| 逐守卫 | 见各脚本 | 各自的策略约束 | 编译期与运行期问题 |
| — | — | — | **编译**（本环境 kotlinc/gradle 跑不动） |

`static-balance` 是**兜底**，不是编译替代品。它能保证「改完的 Kotlin 至少结构完整」，
符号是否存在仍需逐个 grep 定义处核对 —— 这是 `02-backend-architecture.md` §7 记录的
工作方式。
