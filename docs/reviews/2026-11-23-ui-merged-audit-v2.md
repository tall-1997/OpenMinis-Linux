# Minis Ultra UI 审查报告（修订版）——尺寸/大小/比例保持现状

> 基准：`fb0f9f2e`（origin/main，#428）。
> 来源：本会话三路审查 + 另一模型审查（`D:\minis-ultra\docs\ui-deep-review-2026-11-23.md`），合并去重并逐条实测校验（校验过程详见 `2026-11-23-ui-merged-audit.md`，其结论全部保留有效）。
>
> **范围裁定（用户决定，2026-11-23）**：现有**尺寸、大小、比例一律不做更改**。所有修复方案涉及改动渲染尺寸（dp/sp）、触控目标大小、圆角值、间距值、字号/行高比例的条目，从修复清单中**移除**，完整记录于附录 A（只存档、不实施）。本报告的活跃清单只保留：**行为 bug、颜色语义、动态取色接线、图标体系一致性、布局行为、无障碍标签、i18n、架构与门禁**。

---

## 一、活跃缺陷清单（不含任何尺寸类改动）

### A. 行为 bug（确凿、机械可修，5 项）

| # | 缺陷 | 位置 | 修复方向 |
|---|---|---|---|
| A1 | **Turn changes 卡片开关不生效**：`remember { showTurnChangesCardEnabled(context) }` 无 key，切换设置后不重读，须重建列表才刷新 | `ui/chat/ChatMessageListSection.kt:470-472` | 接入 appearance state 流（与 `dynamicColor` 同型的 `prefs.observe`），或给 `remember` 加 `keys =` |
| A2 | **状态栏图标亮暗不跟应用内主题**：浅色走 `SystemBarStyle.auto`，auto 按系统 uiMode 选图标色、不按内容亮度；注释"by luminance"是错的。应用内浅色 + 系统深色 → 浅底白图标 | `MainActivity.kt:575-590` | 按 `darkTheme` 显式走 `SystemBarStyle.light/dark`（深色分支已经是显式 dark，补浅色分支），修正注释；`QuickActivity` 同批 |
| A3 | **XML 首帧主题只跟 night 限定符**：`themeMode` pref 不映射 XML 主题，应用内浅色 + 系统深色时冷启动闪黑 | `values/themes.xml` + `values-night/themes.xml` | `AppCompatDelegate.setDefaultNightMode()` 映射 themeMode（Application/Activity onCreate），XML 层即随之切换 |
| A4 | **子代理状态机用中文文案做键**：`when(phase){ "调用工具"→… "执行中"→… }`，17 个 locale 下中文泄漏，且状态与文案耦合 | `ui/chat/ChatSubAgentBar.kt:521-526`（另有 `:139` `ifBlank { "思考中" }` 默认值） | 状态枚举化（Phase.Calling/Running/Replying），文案全部进 `strings.xml`；连带下调 CJK 冻结基线 |
| A5 | **QS 磁贴用系统废弃图标** `@android:drawable/ic_menu_send` | `AndroidManifest.xml:626` | 换自家 vector（与 launcher monochrome 同族线宽） |

### B. 颜色语义（Apple 契约补完，只改色值不改任何尺寸，7 项）

| # | 缺陷 | 位置 | 实测 | 修复方向 |
|---|---|---|---|---|
| B1 | 浅色 `tertiaryText` 1.74:1（α0.30） | `ChatColors.kt:72` | WCAG 手算 | 提高不透明度或换实色，≥4.5:1 |
| B2 | 浅色 `secondaryText` 3.44:1（α0.60，80 处引用） | `ChatColors.kt:71` | 同上 | α≈0.72 或 `#6E6E73` |
| B3 | 浅色 `inlineCodeText #FF9500` on `#F2F2F7` = 1.97:1 | `ChatColors.kt:83-84` | 同上 | 换深橙/棕（`#B25000` = 4.66），**背景色不动** |
| B4 | **FAB 白图标 2.19:1** on 米色 `#B7AF96` | `ChatColors.kt:96-97` | 3.0 图形线 | `fabOnAccent` 改深墨 `#1C1C1E`（7.77:1）；**FAB 尺寸不动** |
| B5 | 填充按钮白字 on `primary #5A8AD2` = 3.50:1（primary 作文字 3.14） | `Theme.kt:51-52` | 4.5 | primary 加深至 `#2E6BC0`（白字 5.28）/`#2B62AE`；**圆角、尺寸全部不动** |
| B6 | `outline ≡ outlineVariant` 同值，两层语义不可分，87 处 `.copy(alpha)` 折中 | `Theme.kt:113-114,137-138` | L* 实测 | 拆槽：outlineVariant 调淡一档（装饰线），outline 保持结构性浓度 |
| B7 | surfaceContainer 五档只有三个色值、两段同色（`cHigh→cHighest` 1.00:1，24 处消费者永远同色）；明色 `cLow` 比 `surface` 更亮，方向与 M3 相反 | `Theme.kt:108-112,132-136` | 相邻档对比度实测 | **保持"卡片比页面亮"的 Apple 观感不变**，只把同值的档拆成可区分的色值（补两个中间色），方向按现有 Apple 语义 |

### C. 动态取色接线（4 项）

| # | 缺陷 | 位置 | 修复方向 |
|---|---|---|---|
| C1 | **动态深色把聊天面层压平**：`inputBg = surfaceContainerLowest`（tone 4）比页面背景（tone 6）还暗，只差 5/255；与项目自己在静态深色的"防糊成一团"结论相反 | `Theme.kt:288-296` | 只换**角色映射**（input→`surfaceContainerHighest` 等），不引入新尺寸；加"层间对比 ≥1.2"单测 |
| C2 | 动态深色"思考等级"选中胶囊白字压 tone-80 主色 = 1.70:1 | `ChatComposerWidgets.kt:423-436` + `Theme.kt:314` | `ChatPalette` 增加 `thinkingOn` 槽，动态取 `scheme.onPrimary`（7.72:1），静态保持 White |
| C3 | 动态模式 15 种身份色全部塌缩为 `primaryContainer`，session 分类颜色编码消失；red/gray 的"hue IS the message"保留策略与表内策略自相矛盾 | `AccentTokens.kt:73-93,117-130` | 分类色映射到不同 role（primary/secondary/tertiary + container）或 HCT harmonize（保持可识别） |
| C4 | accent 桥覆盖不全：`LocalMonetDynamic` 仅 3 读取点；约 40+ 处 `IosX` 直用未桥接（backup/skills/storage/mounts/provider）；`RawColors` 33 行工具 hue 不受控；`MinisMenu` 硬编码 `Color.White` 容器 | `AccentTokens.kt:135/145/154`；`ChatToolFormatting.kt:56-89`；`MinisMenu.kt:81` | 直用点收口到 `monet*` 桥；`MinisMenu` 容器改 `surfaceContainerHigh`；加"新装饰色必须进表"源码守卫 |

### D. 图标体系（3 项，只涉风格一致性，不涉尺寸）

| # | 缺陷 | 位置 | 修复方向 |
|---|---|---|---|
| D1 | 双轨覆盖率 36.3%：240 处 `Icon(Icons.*)` 直写绕过 `m3Icon()`（83 文件；对方报 73 处系低估），动态开关拨动时同屏 Filled/Outlined 混排 | `SessionListScreen` 49 处、`BackupAndRestoreScreen` 39 处、`ChatToolFormatting` 37 处等 | 两种路线二选一：(a) 收口——把直写点过 `m3Icon()`；(b) 撤双轨——删除 `LocalUseRoundedIcons`/`M3Icons.kt` 映射表，图标风格恒定。**建议 (b)**：风格切换从未被独立验证过价值，且是"动态取色开关连带改形态"这个 Familiarity 违规的源头之一 |
| D2 | `LocalUseRoundedIcons` 名不副实：实现是 Filled↔Outlined 互换，Rounded/Material Symbols 从未使用 | `Theme.kt:153-155`、`M3Icons.kt:491-499` | 若保留双轨则改名 + 改注释；若走 D1(b) 则随之删除 |
| D3 | 图标家族混用：Filled 系 803 vs Outlined 427（chat 偏 Filled、settings 偏 Outlined），无成文规则 | 全仓统计 | 在 `AccentTokens.kt` 式的契约注释里写死"哪类表面用哪族"，新代码可 grep |

### E. 布局行为（3 项，无尺寸改动）

| # | 缺陷 | 位置 | 修复方向 |
|---|---|---|---|
| E1 | 3 个全屏表单无 `imePadding`，键盘遮挡输入框（edge-to-edge 下 `adjustResize` 不缩窗） | `automation/ui/AutomationRuleEditScreen.kt:166`、`ui/scheduled/ScheduledTaskEditScreen.kt:242`、`ui/settings/LogcatPanelScreen.kt:131` | 改用 `SettingsScaffold`（内部已 `.imePadding()`）或补 `.imePadding()` |
| E2 | RTL 三处结构错位：分屏拖把手物理 LTR offset、滑动 reveal 方向未镜像、菜单 `anchorBounds.right` 对齐 | `ChatSplitScaffold.kt:652,670-672`、`SwipeRowActions.kt:112,131-136,173`、`MinisMenu.kt:137-141` | 按 `LocalLayoutDirection` 取符号；补 RTL 渲染测试 |
| E3 | 1 处多元素可删列表缺 key：cookie 域名列表删除后行身份漂移 | `BrowserSettingsSheet.kt:408-409` | `key = { domain }` |

### F. 主题/动效/i18n（5 项）

| # | 缺陷 | 位置 | 修复方向 |
|---|---|---|---|
| F1 | 崩溃兜底屏用裸 `MaterialTheme{}` 恒浅色，深色模式白底闪屏 | `StartupFailureScreen.kt:91`、`NewerDatabaseGuidanceScreen.kt:39` | 包 `MinisTheme` 或复用主题入口 |
| F2 | 唯一的无限动画未接共享 30fps 时钟（`DecorClock` 注释自称"8 处全部迁移"与事实不符）；`LocalMotionDurationScale` 全仓 0 读取，无"减少动效"分支 | `ComposerContextGlow.kt:239-246` | 接 `LocalDecorPhase` 派生器；补 duration-scale 分支 |
| F3 | 工具详情 5 处硬编码英文（"Minis Computer"/"Open full output"/"Open in terminal"/"Open in session browser"/"Shell command"） | `ChatToolDetailUI.kt:200,253,269,302-303` | 进 `strings.xml`，17 语种补齐 |
| F4 | 死映射：`ChatPalette.toastBg` 动态映射后全仓无消费者 | `Theme.kt:315` | 删槽或接到真实 toast 组件 |
| F5 | 注释与代码脱节：`MinisShapesM3` 不存在；"Material3 规范拷贝"缺 15 个 letterSpacing；WCAG 历史数值（3.62/5.93）与现值不符 | `Theme.kt:166,329-345,31-50` | 修注释（纯文档改动） |

### G. 架构与门禁（4 项，根因层）

| # | 缺陷 | 实测 | 修复方向 |
|---|---|---|---|
| G1 | **对比度门禁测错了对象**：`check_token_semantics.py` 只检 `MinisColors`（57 槽、22 死槽、12 近死，唯一主要消费者是 `ChatToolDetailUI`），真实渲染的 `ChatPalette`（286 处）与 `colorScheme`（1411 处）零校验；基线还豁免 `Light.textTertiary 2.92`、`link 3.0` | `scripts/ci/check_token_semantics.py:66-82` | 把 ChatPalette 34 槽 + ColorScheme 关键配对纳入对比度断言（色值修复会更新基线，**不含尺寸**） |
| G2 | `Icon(Icons.` 直写无棘轮；`remember {` 内读 pref 无检查；`SystemBarStyle` 语义无守卫 | — | 三道新棘轮（仿 `check_cjk_literals` 基线机制，只降不升） |
| G3 | `check_token_coverage` 只扫 `ui/`：`cards/`（dp42）、`automation/ui/`（dp24）、`webapp/`（dp25）、`quick/`（dp16）在门外 | `check_token_coverage.py:49` | 纳入扫描（**只统计、不要求改值**） |
| G4 | `MinisColors` 死槽清理：57 槽 22 死 + 12 个仅 1 消费者 | slot 实测 | 删除死槽或标 `@Deprecated`，消费者随 C4 收口自然增加 |

---

## 二、修复批次（重排，零尺寸改动）

**批次一（行为 bug，全部机械修）**
A1 Turn changes 开关接 state · A2 状态栏显式 light/dark + 修注释 · A3 `setDefaultNightMode` 映射 · A4 子代理状态枚举化 · A5 QS 磁贴换 vector

**批次二（颜色语义，Apple 契约补完）**
B1-B5 五处对比度（只改色值）· B6 outlineVariant 拆槽 · B7 surfaceContainer 拆同值档（保持现有 Apple 观感方向）· F5 注释修正

**批次三（动态取色收尾 + 图标决策）**
C1 暗色层角色重映射 · C2 `thinkingOn` 槽 · C3 身份色 harmonize · C4 accent 桥收口 · D1-D3 图标双轨决策（建议撤双轨）· F4 死槽清理

**批次四（行为补全 + 门禁）**
E1 imePadding ×3 · E2 RTL 三处 · E3 LazyColumn key · F1 兜底屏随主题 · F2 动画接时钟 · F3 硬编码英文 · G1-G4 四道门禁

**长期（版本门控，非疏漏）**
material3 1.5.0（需 AGP 9.1.2 + compileSdk 37）→ 届时 ButtonGroup 替换 29 处 SegmentedButton、`MaterialExpressiveTheme`/`MotionScheme` 可用。**约束：升级时 Shapes/间距档位值保持现状映射，不因升级改观感尺寸。**

---

## 三、附录 A：已裁决保持现状的项（只记录，不实施）

以下条目经用户决定（2026-11-23）**不做更改**——它们的共同点是修复方案会改动渲染尺寸/大小/比例。全部实测证据保留在 `2026-11-23-ui-merged-audit.md`，此处仅存目：

1. 触控目标类：主发送/停止键 38dp、overlay 按钮 14dp、代码块动作 16dp、GenUi 16/20dp、附件删除 20dp、浏览器标签 16dp、滚动 FAB 36dp、设置步进器 36dp、上下移 24dp、工具详情 32dp、TTS 胶囊 22dp 高、`touchTargetMin=48f` 零消费者
2. 字号/行高类：动态取色模式吞掉 App 内置字号档（功能 bug，但修复会改变设置了档位的用户的渲染字号）、系统+内置双重缩放 2.42×、`scaledTypography` 行高不随缩放、`MinisType` 305 处不响应档位、10sp/8sp 低于最小档
3. 圆角/间距类：契约"16dp 圆角"未落地（主导 12dp、19 个圆角值）、`AppleShapes` 档位、间距离网 95 处（`14dp×71`）、半像素 `2.5/0.75/1.6/1.8dp`
4. 图标尺寸类：`Modifier.size` 20+ 档、13/17/9dp 非光学档、图标不随字号缩放
5. M3E 形状升级：8 档 Shapes 档位值、`MaterialShapes` 形状张力（与"形态随 Apple"冲突，且会改尺寸）

> 备注：批次一里的 A2/A3（状态栏/XML 首帧）是**行为**修复——它们让"应用内主题选择"真正生效，不改变任何默认尺寸；B 类色值修复同样不改尺寸。若未来要翻案附录 A 的任何一项，证据与行号都在原合并报告里。

---

## 四、合规确认（两报告一致，保持有效）

- adaptive icon 三层齐全（bg/fg/monochrome + night 色板 + 备选图标 + roundIcon）
- 动态取色 SDK 门控正确（唯一调用点在 `SDK_INT >= S` 分支内；<S 禁用而非隐藏；默认关且三处一致）
- 壁纸换色经系统 `CONFIG_ASSETS_PATHS` 重建生效（AOSP 链路 + material3 KDoc；OEM 差异为推测项）
- 深浅 ChatPalette 33/34 槽显式成对；深色三处专项对比修复史有效（userBubble 11.17:1 等）
- RTL 方向 API 卫生：`padding(left/right)` 0 处、裸 ArrowBack 0 处、`AutoMirrored` 114 处、`supportsRtl=true`
- 长列表 key 稳定（50 容器/100 builder 中 67 带 key；聊天列表结构化 key + contentType + reverseLayout）
- 无限动画收敛至 1 处 + `DecorClock` 共享时钟有消费者计数与后台门
- edge-to-edge 顶层安全区无缺口；`SettingsScaffold` 统一 imePadding
- material-icons-extended 已显式声明；contentDescription 缺失仅 5 处

---

*修订版报告。与上一版（`2026-11-23-ui-merged-audit.md`）的差异：全部尺寸/大小/比例类条目移入附录 A 存档，活跃清单与批次已重排为纯行为/颜色/接线/门禁四批。所有实测数据与校验结论不变。*
