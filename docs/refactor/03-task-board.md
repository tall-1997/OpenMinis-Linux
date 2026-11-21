# 03 · 任务板

> 每一条任务都是可独立提交、可独立验证的单元。
> 编号规则：`主线-批次`。**批次边界才跑 `verify_all.py`**，批次内部不做全量验证。

## 执行原则

1. **依赖顺序不可乱**：A → B/C/D 并行 → 模块化收尾。
2. **每批次一个 commit**，commit message 带任务编号。
3. **验证只在批次边界**：`python3 scripts/ci/verify_all.py`，全绿才进下一批。
4. **守卫先写再改**：能写成守卫的约束，先写守卫（失败基线允许存在），再逐条消。
5. **数字只降不升**：`check_architecture_policy.py` 的 baseline 只允许下调。

---

## 批次 0：地基（必须先做）——✅ 已完成

| 编号 | 任务 | 涉及 | 验收 | 状态 |
|---|---|---|---|---|
| `E1-1` | `verify_all.py` 骨架 + 共享文件扫描 | `scripts/ci/verify_all.py`（新） | 单进程跑完，输出汇总表，非零退出码 | ✅ 501 行 |
| `E1-2` | 并入 14 条现有守卫 | 同上 | 14 条全部通过，总耗时 < 单条之和 | ✅ 串行 70s → 并行 41s |
| `E1-4` | 静态验证合并（括号配平 + 符号存在性） | 同上 | 用 `mask_source` 掩码后配平 | ✅ 1939 文件 0 问题 |
| `A1-1` | 版本目录单点化 | `gradle/libs.versions.toml` `app/build.gradle.kts` | 41 处内联版本 → 0 | ✅ 42 版本键 / 53 库 |
| `A1-2` | `check_no_inline_versions.py` | `scripts/ci/` | 守卫能抓出内联版本 | ✅ 自测 8/8 |
| `A1-3`（新增） | `check_version_catalog_sync.py` | `scripts/ci/` | build-logic 与根目录版本一致 | ✅ 自测 4/4 |
| `A1-4`（新增） | SDK 版本入目录 | `gradle/libs.versions.toml` | compileSdk/minSdk/targetSdk 无硬编码 | ✅ 含 benchmarkMinSdk=28 |

**实际产出**：`verify_all.py` 建成后才发现，项目原有的 `mask_source` 有三个真缺陷（
字符串模板 `${...}`、反引号标识符、原生串结尾引号），共造成 26 处误报。已全部修掉，
自测从 8 条扩到 18 条。详见 `01-ui-architecture.md` 的“验证基础设施”一节。

**批次 0 完成后跑一次 `verify_all.py`。** —— 已跑：18 项 17 通过 1 跳过，0 失败。

---

## 批次 1：构建现代化（原子批次，不可拆）

| 编号 | 任务 | 涉及 | 验收 |
|---|---|---|---|
| `A2-1` | AGP 8.7.3 → 9.5.1 + Gradle 升级 | `build.gradle.kts` `gradle-wrapper.properties` | `./gradlew :app:assembleDebug` 通过 |
| `A2-2` | Kotlin 2.1.0 → 2.4.21 + KSP 对齐 | 同上 + `libs.versions.toml` | 编译通过 |
| `A2-3` | compileSdk 36 → 37 / targetSdk 35 → 36 | `app/build.gradle.kts` | lint 无新错误 |
| `A2-4` | Compose BOM 2025.09.00 → 2026.11.01 | 同上 | 编译 + 单元测试通过 |
| `A2-5` | material3-adaptive 1.2.0 → 1.3.0 | 同上 | 编译通过 |
| `A2-6` | navigation-compose 2.8.5 → 2.10.2 | 同上 | 编译通过 |
| `A2-7` | lifecycle 2.8.7 → 2.11.1 / activity 1.9.3 → 1.13.0 | 同上 | 编译通过 |
| `A2-8` | Room 2.6.1 → 2.8.5 | 同上 + `data/db` | schema 兼容 |
| `A2-9` | 609 个单测全绿 | `app/src/test` | `./gradlew test` 通过 |

**为什么原子**：material3 1.3.0 硬性要求 compileSdk 37 + AGP 9.1.0（`app/build.gradle.kts:401`
已有注释记录这个约束）。拆开做会出现「升了 BOM 但 compileSdk 不够」的中间态，无法编译。

**批次 1 完成后跑一次 `verify_all.py`。**

---

## 批次 2：DI + 数据层

| 编号 | 任务 | 涉及 | 验收 |
|---|---|---|---|
| `A3-1` | Hilt 接入（AGP 9 的 Hilt 插件） | `build.gradle.kts` `MinisApp.kt` | `@HiltAndroidApp` 生效 |
| `A3-2` | DI 模块定义（Data / Prefs / Provider / Sandbox） | `di/`（新） | 编译期图校验通过 |
| `A3-3` | 7 个 `ViewModelProvider.Factory` → `@HiltViewModel` | 5 个已知 + 2 处待定位 | 工厂数 → 0 |
| `A3-4` | `@AssistedInject` 处理 `ChatViewModel(sessionId)` | `ui/chat/ChatViewModel.kt` | 会话切换正常 |
| `D1-1` | `DataStorePrefsBackend` | `config/`（新） | 读写 API 与 `SharedPrefsBackend` 等价 |
| `D1-2` | `PrefsFileSpec` 加 `backend` 字段 | `scripts/prefs/gen_prefs_registry.py` | 生成器输出含 backend |
| `D1-3` | 旧 SharedPreferences → DataStore 迁移器（幂等） | `config/`（新） | 重复启动不重复搬 |
| `D1-4` | 分批切换：93 个文件中 84 个明文 → DataStore | `config/PrefsRegistry.kt` | 加密 9 个原样保留 |
| `D1-5` | 清理旧 prefs 文件（保留 1 版本周期） | — | 升级不丢配置 |

**批次 2 完成后跑一次 `verify_all.py`。**

---

## 批次 3：状态架构（最大的一块，按 8 组分 8 批）

### 3.0 骨架

| 编号 | 任务 | 验收 |
|---|---|---|
| `B1-1` | `ChatUiState` + 8 个字段组定义；用 `combine` 从 59 条流派生（不改写入方） | 59 条流仍在，UiState 可渲染 |
| `B1-3` | `ChatEvent` Channel 定义 | 一次性事件不重放 |

### 3.1 逐组迁移（每批一组，改写入方 + 删旧流）

| 编号 | 组 | 涉及扩展文件 |
|---|---|---|
| `B1-2a` | `run`（RunState） | `AgentLoopExt` `ExecuteToolExt` `RunLoopGate` `StopRunExt` `InLoopContext` `SubAgentExt` |
| `B1-2b` | `transcript` | `AgentHistoryExt` `HistoryExt` `LoadExt` `Store` `RerunTailExt` `RevertExt` `RetryExt` |
| `B1-2c` | `composer` | `SendExt` `DrainQueueExt` `QueueInjectExt` `ResumeQueueExt` `SteeringExt` `MentionExt` `SlashExt` `SlashExec` |
| `B1-2d` | `context` | `CompactExt` `CompactLeaf` `CompactPlan` `CompactReconcileExt` `CompactSplitExt` `ContextBreakdownExt` `ImageBudgetExt` `PreSendContext` `PromptExt` |
| `B1-2e` | `header` / `session` | `EnsureSession` `MigrateDraft` `ResumeExt` `Binder` |
| `B1-2f` | 附件 | `AttachmentExt` `ImageExt` `ReadImage` `OffloadExt` |
| `B1-2g` | `goal` / `plan` | `GoalExt` `GoalUiExt` `PlanContextExt` `DiscussionOutcomeExt` `DispatchDedupeExt` |
| `B1-2h` | 群聊 | `GroupChatExt` `GroupMembers` `GroupResolve` |

每组验收：该组流数 → 0，`ChatUiState` 对应字段有值，界面行为不变。

### 3.2 委托抽取

| 编号 | 委托 | 吸收文件数 | 目标行数 |
|---|---|---:|---:|
| `B2-1` | `ChatComposer` | 8 | ≤ 800 |
| `B2-2` | `ChatTranscript` | 7 | ≤ 800 |
| `B2-3` | `ChatAgentRunner` | 6 | ≤ 800 |
| `B2-4` | `ChatContext` | 9 | ≤ 800 |
| `B2-5` | `ChatSession` | 4 | ≤ 400 |
| `B2-6` | `ChatTools` | ~6 | ≤ 800 |
| `B2-7` | `ChatGoal` | 5 | ≤ 600 |
| `B2-8` | `ChatViewModel` 瘦身 | — | 6208 → ≤ 400 |

### 3.3 订阅规范

| 编号 | 任务 | 验收 |
|---|---|---|
| `B3-1` | 423 处 `collectAsState` → `collectAsStateWithLifecycle` | 非 lifecycle 版本 → 0 |
| `B3-3` | `check_state_subscription.py` | 守卫能抓出两种违规 |

**批次 3 完成后跑一次 `verify_all.py`。**

---

## 批次 4：UI 清晰化（可与批次 3 并行）

### 4.1 设置页数据驱动

| 编号 | 任务 | 验收 |
|---|---|---|
| `C1-1` | `SettingsEntry` / `SettingsGroup` / `SettingsAction` / `SettingsContext` 类型 | 类型可编译，含 `@Preview` |
| `C1-2` | `SettingsCatalog` 迁移现有 30 项 | `SettingsRow(` 手写 → 0 |
| `C1-3` | 分组重排为 6 组（模型与能力 / 对话与人格 / 设备与终端 / 数据 / 外观 / 关于） | 30 项全部可达 |
| `C1-4` | 设置搜索（按 `id` + `title` 模糊） | 输入「备份」能定位 |

### 4.2 类型安全导航

| 编号 | 任务 | 验收 |
|---|---|---|
| `C2-1` | 36 个 `@Serializable` 路由对象替代 70 个 `Routes` 常量 | 常量 → 0 |
| `C2-2` | `NavHost` 迁移到 `composable<Route>` | 36 个目的地全通 |
| `C2-3` | 深链对齐（`Routes.kt` 里的 11 个入口矩阵逐条验证） | 11 个入口全部落地正确 |
| `C2-4` | `check_typed_routes.py` | 守卫能抓出 `composable(Routes.` |

### 4.3 设计令牌

| 编号 | 任务 | 验收 |
|---|---|---|
| `C3-1` | `MinisSpacing` 角色表（18 个角色） | 角色定义可编译 |
| `C3-2` | 83 个 dp 值 → 18 个角色映射 + **difflib 逐点反转原值比对** | 3464 处逐点一致 |
| `C3-3` | `gen_tokens.py` 支持 spacing 生成 | 重跑不丢（生成物不能手改） |
| `C3-4` | 颜色语义化（`IosBlue` → `AccentBlue`，旧名 `@Deprecated` 别名） | 命名覆盖 43% → ≥ 90% |

**C3-2 的坑（上一轮踩过）**：纯靠「同块内数值邻近」配对会跨 Composable 块误配。
必须：预演 → difflib 逐点反转原值比对 → 不等即暴露 → 修正后 100% 一致才算完。

### 4.4 组件分层

| 编号 | 任务 | 验收 |
|---|---|---|
| `C5-1` | `check_component_layers.py`（Screen ≤300 行 / Section 有 Preview / Primitive 无 data import） | 守卫可跑，基线允许存在 |
| `C5-2` | `ChatScreen.kt` 5585 行 → 若干 ≤300 行 Screen | 最大 Screen ≤300 |
| `C5-3` | `MinisTextKit*` 2912 行 → `selection/` `menu/` `render/` 三子包 | 每包 ≤800 |
| `C5-4` | `StreamingMarkdownText` 1399 行拆包 | ≤800 |

### 4.5 大屏

| 编号 | 任务 | 验收 |
|---|---|---|
| `C4-1` | 会话主从 `ListDetailPaneScaffold` | 平板双栏，手机推入 |
| `C4-2` | 供应商主从 | 同上 |
| `C4-3` | 设置主从 | 同上 |

**批次 4 完成后跑一次 `verify_all.py`。**

---

## 批次 5：后端化繁为简

| 编号 | 任务 | 验收 |
|---|---|---|
| `D2-1` | `skills` / `mcp` / `audit` 三库补 Room entity（逐列对齐） | schema 与现有表一致 |
| `D2-2` | 合并 Migration（`ATTACH` + `INSERT SELECT`） | 单测覆盖 |
| `D2-3` | 行数校验 + 失败回滚（幂等） | 注入失败能回滚 |
| `D2-4` | 删 5 个手写 `SQLiteOpenHelper` + `check_no_sqlite_helper.py` | helper 数 → 0 |
| `D2-5` | `DatabaseStartupGate` 收口 | 启动无并发异常 |
| `D3-1` | Provider 四段式接口 | 接口可编译 |
| `D3-2` | 抽 `SseLineReader` | OpenAI / Anthropic 共用 |
| `D3-3` | 抽 `RetryPolicy` | 5xx / 429 / 超时统一 |
| `D3-4` | 抽 `ToolCallAccumulator` | 流式工具调用统一 |
| `D3-5` | `OpenAIProvider` 1697 行拆分 | ≤800 |
| `D3-6` | `AnthropicProvider` 1226 行拆分 | ≤800 |
| `D4-1` | `Tool` 接口 + `ToolSpec` | 可编译 |
| `D4-2` | KSP 生成 schema | 手写 schema → 0 |
| `D4-3` | `ToolRegistry` + 能力分组 + 风险声明 | 80 个工具可注册 |
| `D4-4` | 80 个工具逐个迁移 | 全部走注册表 |
| `D4-5` | `check_schema_drift.py` 扩展：禁手写 schema | 守卫生效 |
| `D5-1` | `RootfsManager` 1712 行拆 3 类 | 每类 ≤600 |
| `D5-2` | `OffloadHandler` 接口统一 + 注册表 | 3 个实现统一 |

**批次 5 完成后跑一次 `verify_all.py`。**

---

## 批次 6：模块化（最后做）

| 编号 | 任务 | 验收 |
|---|---|---|
| `A4-1` | `check_module_deps.py`（5 条硬规则） | 守卫可跑 |
| `A4-2` | 抽 `:core:common` `:core:design` | 编译通过 |
| `A4-3` | 抽 `:infra:sandbox` `:infra:provider` `:infra:tools` `:infra:speech` `:infra:browser` `:infra:backup` | 编译通过 |
| `A4-4` | 抽 `:domain`（无 Android 依赖） | `grep android.*` 为空 |
| `A4-5` | 抽 `:feature:chat` `:feature:settings` | 编译通过 |
| `A4-6` | `:app` 瘦身为装配层 | `:app` ≤ 20k 行 |

**为什么最后做**：模块边界画错回退成本极高；`feature:chat` 65k 行在状态架构理清前切模块，
等于把混乱固化进构建系统。

---

## 最终验收

| 指标 | 前 | 后 |
|---|---:|---:|
| 最大单文件 | 6208 | ≤ 800 |
| `ChatViewModel` StateFlow | 59 | ≤ 8 |
| `ChatViewModel*.kt` 扩展文件 | 70 | 0（7 个委托类） |
| 裸 `collectAsState` | 423 | 0 |
| 手写 `SettingsRow` | 30 | 0 |
| `Routes` 常量 | 70 | 0 |
| 手写 `SQLiteOpenHelper` | 5 | 0 |
| 内联版本号 | 41 行 | 0 |
| `ViewModelProvider.Factory` | 7 | 0 |
| dp 硬编码（ui） | 3464 / 83 值 | ≤ 18 角色 |
| 颜色命名覆盖 | 43% | ≥ 90% |
| prefs 文件（明文） | 84 | 约 20 DataStore |
| 数据库文件 | 4 | 1 |
| 守卫入口 | 14 个独立进程 | 1 个 |
