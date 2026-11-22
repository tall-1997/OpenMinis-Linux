# Minis Ultra 重构总纲

> 目标（用户原话）：配置要简单，UI 要清晰明了，整体代码化繁为简，架构和设计要用 GitHub 最新技术，
> 先做详细文档（UI 到后端全覆盖），再按文档逐条实现，验证次数最少。

本目录是这套重构的唯一事实来源。代码改动必须能回溯到本目录里的某一条任务编号。

---

## 0. 度量基线（2026-11-20 实测，改动前）

这些数字是后面每一条任务的验收锚点。重构只能让它们变好，不能变差。

### 0.1 代码规模

| 范围 | 文件数 | 行数 |
|---|---:|---:|
| `src/android` 全部 `.kt` | 1939 | 412,169 |
| `app/.../ui` | 426 | 133,270 |
| `app/.../ui/chat` | 225 | 64,829 |
| `app/.../ui/settings` | 92 | 34,160 |
| `app/.../data` | 131 | 27,175 |
| `app/.../sandbox` | 93 | 26,154 |
| `app/.../provider` | 85 | 19,874 |
| `app/.../tools` | 80 | 16,182 |
| `app/.../speech` | 29 | 8,425 |
| `app/.../backup` | 25 | 7,100 |
| `app/.../service` | 26 | 6,062 |
| `app/.../config` | 26 | 5,836 |
| `app/.../mcp` | 33 | 5,586 |
| `app/.../browser` | 20 | 5,110 |
| `app/.../quota` | 17 | 4,650 |
| `app/.../agent` | 19 | 4,639 |
| `app/.../network` | 20 | 4,212 |
| `app/.../auth` | 14 | 3,656 |
| 单测 `app/src/test` | 609 | — |
| 仪器测试 `app/src/androidTest` | 16 | — |

### 0.2 最大的 12 个文件（重构前）

| 行数 | 文件 |
|---:|---|
| 6208 | `ui/chat/ChatViewModel.kt` |
| 5585 | `ui/chat/ChatScreen.kt` |
| 3242 | `ui/sessions/SessionListScreen.kt` |
| 2321 | `data/repository/SkillRepository.kt` |
| 2191 | `sandbox/offload/ModelUseOffloadHandler.kt` |
| 2033 | `ui/chat/ChatViewModelAgentLoopExt.kt` |
| 1873 | `browser/BrowserUseManager.kt` |
| 1791 | `ui/settings/backup/BackupAndRestoreScreen.kt` |
| 1712 | `sandbox/RootfsManager.kt` |
| 1697 | `provider/openai/OpenAIProvider.kt` |
| 1668 | `ui/chat/MinisTextKitGesture.kt` |
| 1537 | `sandbox/offload/AccessibilityOffloadHandler.kt` |

### 0.3 结构性症状（这些才是要治的病）

| 症状 | 实测 | 说明 |
|---|---:|---|
| `ChatViewModel*.kt` 扩展文件数 | 70 | 一个类被拆成 70 个文件，靠 `internal fun ChatViewModel.xxx` 扩展维持，本质仍是上帝对象 |
| `ChatViewModel` 里的 `MutableStateFlow` | 59 | 59 条独立状态流，UI 侧靠 423 处 `collectAsState` 各自订阅 |
| 全仓 `collectAsState` | 423 | 状态读取没有统一入口，重组粒度不可控 |
| 设置页 `SettingsRow` 硬编码 | 30 处 / 569 行 | 每一行都是手写 Compose，新增一项要改代码 + 加路由 + 加字符串 |
| 设置屏文件 | 92 个 / 34,160 行 | 平均每屏 371 行，最大的 1791 行 |
| 导航目的地 | 36 | 70 个 `Routes` 常量，无类型安全（参数靠手写拼接） |
| 颜色令牌命名覆盖 | 43% | 284 处硬编码颜色 / 131 个不同值，只有 123 处有命名 |
| dp 硬编码（ui 目录） | 3464 处 / 83 值 | 生成器实测；全 app 范围 3861 处 / 84 值 |
| prefs 文件（注册表） | 93 | 93 个 SharedPreferences 文件，键分散 |
| 手写 `SQLiteOpenHelper` | 5 个 | 与 Room 并存，同一份数据两套访问路径 |
| 数据库文件 | 4 个 | `minis.db` / `skills.db` / `mcp.db` / `minis-config-audit.db` |
| DI 框架 | 无 | 全靠手写 `ViewModelProvider.Factory` + 构造参数透传 |

### 0.4 技术栈版本（重构前 → 目标）

| 组件 | 当前 | 最新稳定（2026-11 实测 Maven） | 目标 |
|---|---|---|---|
| AGP | 8.7.3 | 9.5.1 | 9.5.1 |
| Kotlin | 2.1.0 | 2.4.21 | 2.4.21 |
| KSP | 2.1.0-1.0.29 | 随 Kotlin 2.4.21 | 2.4.21-x |
| Compose BOM | 2025.09.00 | 2026.11.01 | 2026.11.01 |
| material3 | BOM 管理 | 1.5.0 | BOM 管理（1.5.0） |
| material3-adaptive | 1.2.0 | 1.3.0 | 1.3.0 |
| navigation-compose | 2.8.5 | 2.10.2 | 2.10.2（类型安全路由） |
| lifecycle-*-compose | 2.8.7 | 2.11.1 | 2.11.1 |
| activity-compose | 1.9.3 | 1.13.0 | 1.13.0 |
| Room | 2.6.1 | 2.8.5 | 2.8.5 |
| DataStore | 未用 | 1.3.0 | 1.3.0（替代非加密 prefs） |
| compileSdk / targetSdk / minSdk | 36 / 35 / 26 | — | 37 / 36 / 26 |
| jvmTarget | 17 | — | 17 |

**版本链条约束（实测得出，不是推测）**：`app/build.gradle.kts` 里已有一条注释记录 —— material3 1.3.0
硬性要求 compileSdk 37 + AGP 9.1.0。也就是说 material3 升到 1.5.0 **必须**先升 compileSdk 到 37 且
AGP 到 9.x。这条链是：`AGP 9.5.1 → Gradle 9.x → JDK 17+ → compileSdk 37 → material3 1.5.0`。
所以版本升级是一个**原子批次**，不能拆开做。

---

## 1. 重构的四条主线

四条线互不阻塞，但**有依赖顺序**：

```
主线 A：构建现代化（版本 + 模块化 + 依赖注入）
   └─ 必须先做：它是 B/C/D 的地基（新 API 才可用）

主线 B：状态架构（59 条 StateFlow → 单向数据流 + 单一 UiState）
   └─ 依赖 A（要用 lifecycle 2.11 的 collectAsStateWithLifecycle 新签名）

主线 C：UI 清晰化（设置页数据驱动 + 类型安全导航 + 设计令牌收口）
   └─ 依赖 A（navigation 2.10 类型安全路由）

主线 D：后端化繁为简（prefs 收敛到 DataStore、DB 收敛到 Room、provider 去重）
   └─ 依赖 A（Room 2.8 / DataStore 1.3）
```

### 主线 A：构建现代化

1. **A1 版本目录单点化**。当前 `libs.versions.toml` 只有 13 行（coroutines + serialization 两项），
   其余全部内联在 `app/build.gradle.kts` 的 400+ 行依赖块里。目标：所有坐标与版本进 catalog。
2. **A2 版本原子升级**。按上面的链条一次性升到目标版本，不拆批。
3. **A3 引入 DI**。当前零 DI，实测 7 处手写 `ViewModelProvider.Factory`。目标 Hilt（与 AGP/KSP 兼容最好），
   把 7 处工厂收敛为 `@HiltViewModel`。
4. **A4 模块化收口**。当前只有 `:app` / `:core:model` / `:provider:api` 三个模块，
   而 `ui` 一个模块 13 万行。目标按「能力」切模块，不是按「层」切。

### 主线 B：状态架构

1. **B1 单一 UiState**。`ChatViewModel` 的 59 条 `MutableStateFlow` 归并为
   `ChatUiState`（不可变 data class）+ 少量一次性事件 `Channel`。
2. **B2 扩展文件收敛**。70 个 `ChatViewModel*Ext.kt` 按职责重新归组为 6~8 个委托类
   （`ChatComposer` / `ChatTranscript` / `ChatAgentRunner` / `ChatAttachments` / `ChatCompaction` /
   `ChatSession` / `ChatTools`），`ChatViewModel` 只做编排。
3. **B3 重组边界**。423 处 `collectAsState` 统一改为 `collectAsStateWithLifecycle`，
   并强制「谁用谁订」——不再把整个 state 传给子组件。

### 主线 C：UI 清晰化

1. **C1 设置页数据驱动**。30 处手写 `SettingsRow` → 声明式 `SettingsSpec` 列表，
   新增一项只改数据不改布局。
2. **C2 类型安全导航**。36 个目的地 / 70 个 `Routes` 常量 → `@Serializable` 路由对象 + `NavHost` 类型化。
3. **C3 设计令牌收口**。`MinisTokens.kt` 是生成物（`scripts/ui/gen_tokens.py`），
   当前 `sp` 硬编码已归零、`dp` 仍有 3464 处 83 个值。目标：`dp` 也进令牌。
4. **C4 大屏适配**。已有 `material3-adaptive` 依赖但使用面窄，目标用
   `ListDetailPaneScaffold` / `SupportingPaneScaffold` 统一平板形态。

### 主线 D：后端化繁为简

1. **D1 prefs 收敛**。93 个 prefs 文件 → 加密的留 SharedPreferences（现有
   `EncryptedPrefsFactory` + `check_prefs_encryption` 守卫），明文的迁 DataStore。
2. **D2 DB 收敛**。4 个库 + 5 个手写 `SQLiteOpenHelper` → Room 单库多 module，
   迁移脚本逐表验证。
3. **D3 provider 去重**。`OpenAIProvider` 1697 行 + `OpenAIRequestBodies` 1309 行，
   与 `AnthropicProvider` 1226 行有大量重复的请求构造/流式解析逻辑，抽公共层。
4. **D4 工具注册表化**。80 个 `tools` 文件，工具声明与执行分离。

---

## 2. 验证策略：最少次数

用户明确要求「对验证要最少次数去做」。当前 CI 有 14+ 条独立 Python 守卫，
每条都要单独跑一次（实测单条 30~500 秒，`check_architecture_policy` 最慢）。
这在重构期间是纯浪费。

**做法：一个入口跑完全部，只在批次边界跑。**

- 新增 `scripts/ci/verify_all.py`：单进程内依次调用所有守卫，共享一次全仓文件扫描
  （目前每条守卫都各自 `rglob("*.kt")` 扫 1281 个文件，重复 14 次）。
- 只在**批次结束时**跑一次，不在每次文件编辑后跑。
- 静态验证（括号配平、符号存在性）合并进同一个脚本，不单独手写脚本。

验收节奏：每完成一条主线的一个批次 → 跑一次 `verify_all.py` → 全绿则提交。

---

## 3. 验收标准（可量化）

| 指标 | 重构前 | 目标 | 判定方式 |
|---|---:|---:|---|
| 最大单文件行数 | 6208 | ≤ 800 | `verify_all.py` 输出 |
| `ChatViewModel` 的 StateFlow 数 | 59 | ≤ 8 | grep 计数 |
| `collectAsState`（非 lifecycle） | 423 | 0 | grep 计数 |
| 设置页手写 `SettingsRow` | 30 | 0（数据驱动） | grep 计数 |
| `Routes` 常量路由 | 70 | 0（`@Serializable` 路由对象） | grep 计数 |
| 手写 `SQLiteOpenHelper` | 5 | 0 | grep 计数 |
| 内联版本号（build.gradle.kts） | 41 行 | 0 | grep `implementation("…:数字` |
| 单条守卫独立进程 | 14 | 1 入口 | 文件存在性 |
| dp 硬编码（ui 目录） | 3464 处 / 83 值 | 角色表（≤18 角色） | `gen_tokens.py` 输出 |
| 颜色令牌命名覆盖 | 43% | ≥ 90% | `gen_tokens.py` 输出 |

---

## 4. 文档索引

| 文档 | 内容 |
|---|---|
| [01-ui-architecture.md](01-ui-architecture.md) | 信息架构、设计令牌、组件规范、设置页模型、导航模型、大屏形态 |
| [02-backend-architecture.md](02-backend-architecture.md) | 分层、状态容器、prefs/DataStore、Room、provider、工具注册、DI |
| [03-task-board.md](03-task-board.md) | 逐条任务清单（编号 + 验收 + 涉及文件 + 守卫） |
