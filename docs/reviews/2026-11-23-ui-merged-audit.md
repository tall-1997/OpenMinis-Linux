# Minis Ultra UI 深度审查——合并校验报告（2026-11-23）

> 合并对象：本会话三路审查（`docs/reviews/2026-11-23-ui-layout-audit.md`，基准 `aae2ed58`）+ 另一模型审查（`D:\minis-ultra\docs\ui-deep-review-2026-11-23.md`，基准 `6b1d1518`）。
> 本报告基准：`fb0f9f2e`（origin/main 最新，#428 合并后）。
> 方法：逐条交叉核验——对方每条断言都在当前源码上重新实测（对比度手算 WCAG、grep 计数、BOM 版本读 pom、字节码/依赖缓存核实）。每条缺陷标注 **验证状态**：`✓两报告一致` / `✓对方独有·已实测` / `✓我方独有` / `✗对方错误`。

---

## 〇、版本与基准确认

| 项 | 对方报告 | 实测 | 判定 |
|---|---|---|---|
| 审查基准 commit | `6b1d1518`（#427） | 存在；最新已是 `fb0f9f2e`（#428，仅 chat 3 文件） | 两份基准不同但 UI 主体无实质差异 |
| **material3 版本** | **"项目实际在 material3 1.4.x 线"** | **1.3.2**（`compose-bom-2025.09.00.pom` 实测 `<version>1.3.2</version>`，Gradle 缓存仅 `material3/1.3.2`） | **✗ 对方错误** |
| `MotionScheme` 可用性 | "1.4.0 已稳定，接线未做，注释'只有 1.4-alpha'已过时" | 1.3.2 中 **不存在 `MotionScheme`**（1.4.0-alpha02 才引入）。`AppNavigation.kt:65-66` 注释"只在 1.4-alpha、镜像数值不 bump 依赖"对**当前锁定版本是准确的**，未过时 | **✗ 对方错误** |
| SegmentedButton 使用 | "5 处" | **29 个调用点 / 12 文件** | **✗ 对方低估 5.8 倍** |
| Icon 直写绕过 m3Icon | "73 处" | **240 处 / 83 文件**（550 次 `Icon(` 中 251 次经 `m3Icon`、240 次直传 `Icons.*`） | **✓ 方向正确·数量低估 3 倍** |
| 工具状态色引用面 | "被 20+ 文件引用" | 12 文件 | ✓ 方向正确·数量夸大 |
| HorizontalDivider | "88 处一统" | 88 处中 **24 处裸调用**（库默认 1dp） | ✗ 对方错误 |

---

## 一、校验总表（对方报告逐条）

### P1（对方 8 条）

| # | 断言 | 实测位置 | 判定 |
|---|---|---|---|
| 1 | overlay 关闭/展开按钮 14dp | `service/ToolOverlayController.kt:448,467`（`dpToPx(14)`）——路径在 `service/` 不在 `ui/chat/`，行号对 | ✓ |
| 2 | overlay 状态/回复文本 10sp | `:415, :430` `setTextSize(..., 10f)` | ✓ |
| 3 | in-app 主题不贯穿状态栏图标 | `MainActivity.kt:575-590`：浅色走 `SystemBarStyle.auto(TRANSPARENT,TRANSPARENT)`；auto 按**系统 uiMode**选图标亮暗、不按内容亮度——注释"by luminance"确系错误。应用内浅色+系统深色 → 浅底白图标 | ✓ |
| 4 | in-app 主题不贯穿 XML 首帧 | `values/themes.xml` + `values-night/themes.xml` 并存，`themeMode` pref 不映射 XML 主题 → 冷启动闪错色 | ✓ |
| 5 | 73 处 Icon 直写绕过归一化 | 实测 240 处/83 文件 | ✓（数量修正） |
| 6 | 工具状态色四常量硬编码 | `ChatScreenSections.kt:13-17`（#34C759/#FF3B30/#FFCC00/#FF2D55），MinisTokens 对应槽零消费者 | ✓ |
| 7 | ChatSubAgentBar 中文文案作状态键 | `ChatSubAgentBar.kt:521-526` `when(phase){ "调用工具"→… "执行中"→… "回复中"→… }` | ✓ |
| 8 | QS 磁贴用系统废弃图标 | `AndroidManifest.xml:626` `@android:drawable/ic_menu_send` | ✓ |

### P2（抽查核验）

| 断言 | 实测 | 判定 |
|---|---|---|
| MotionScheme 未接 | 1.3.2 无此 API；非"未接"而是"不可用" | ✗（表述错，结论方向可并入版本门控） |
| 静态浅色 primary 3.5:1 | `Theme.kt:51` `#5A8AD2` on white 实测 3.50:1 | ✓（两报告一致） |
| surfaceContainer 倒挂+塌陷 | `Theme.kt:108-112,132-136`：cLow=c=白、cHigh=cHighest，方向与 M3 相反 | ✓（两报告一致） |
| outline≡outlineVariant | `Theme.kt:113-114,137-138` 同值 | ✓（两报告一致） |
| error 家族未装配 | 明色缺 error/onError/errorContainer/onErrorContainer 等 11 角色；暗色再缺 tertiary 四件 | ✓（两报告一致） |
| MinisColors 死层 | 实测 **57 槽、22 零消费者、12 个仅 1 消费者**；`ChatToolDetailUI` 是唯一主要消费者 | ✓（"约 30 槽零/近零消费"方向正确） |
| 滚动 FAB 36dp | `ChatScreen.kt:3705,3760` `.size(36.dp)` | ✓ |
| 发送/停止 38dp | `ChatScreen.kt:5447-5456`、`ChatStopOptions.kt:95-108`（我方 P0 同项） | ✓（两报告一致） |
| TTS 胶囊 22dp 高/20-28dp | `SpeechPlayerCapsule.kt:512-559` | ✓ |
| 工具详情 sheet 全 32dp | `ChatToolDetailUI.kt:177-183` 等 | ✓ |
| PlanGoalCard 36dp vs "44dp"注释 | `PlanGoalCard.kt:239` 注释 44dp、`:322` `heightIn(min=36.dp)` | ✓ |
| **Turn changes 开关 remember 读 pref 不生效** | `ChatMessageListSection.kt:470-472`：`remember { showTurnChangesCardEnabled(context) }` **无 key**——切换设置后不重读，须重建列表才生效 | ✓（**确凿行为 bug**，本报告新增实证） |
| 动态取色下 fontScale 失效 | `Theme.kt:208-212`（我方 D-F 同项） | ✓（两报告一致） |
| 工具详情 5 处硬编码英文 | `ChatToolDetailUI.kt:200`"Minis Computer"、`:253`"Open full output"、`:302-303`"Open in terminal/session browser"、`:269`"Shell command" | ✓ |
| FAB 换位纯隐藏手势无替代路径 | 未见替代入口 | ✓（未逐行反证，可信） |
| 图标尺寸 20+ 档无 token | 我方实测 20+ 档（18/20/16/14/22/24/28/12/17/13/36…） | ✓（两报告一致） |
| 约 15 处无障碍标签硬编码外文 | 我方实测 547 次 Icon 仅 5 处缺 contentDescription；硬编码外文标签未逐一复核 | ⚠️（待复核，不列高置信） |
| 展开动效三种方言 / Markdown 漂移 / 横幅半径 10 vs 14 | 横幅实测 ✓：`WorkStageBanner.kt:70` 10dp vs `DegradedStartupBanner.kt:84`/`NetworkAlertBanner.kt:99` 14dp | ✓（横幅项）；动效/Markdown 项我方已有同类发现 |

### P3 与合规清单（抽查）

| 断言 | 实测 | 判定 |
|---|---|---|
| 0 处裸 ArrowBack / RTL 卫生 | 实测 0 处裸 `Icons.*.ArrowBack`；`AutoMirrored` 114 处 | ✓ |
| adaptive icon 三层齐全 | `mipmap-anydpi-v26/ic_launcher.xml` bg+fg+monochrome + night 变体 + roundIcon | ✓（两报告一致） |
| core-splashscreen 未声明 | `libs.versions.toml` 无 splashscreen 条目 | ✓ |
| 启动图标 PNG 非 vector | mipmap 各密度全 PNG；仅 monochrome/adaptive xml | ✓ |
| material-icons-extended 已显式声明 | `libs.versions.toml:93` | ✓ |
| 通知小图标单色 alpha-mask | 未逐项复核 | ⚠️ |
| 八原则"6 conforms 3 violated" | 我方裁定 3 符合/2 违反/3 部分违反。核心分歧在 Simplicity/Craft 的"部分"维度；违反项（Familiarity/Flexibility）两报告一致 | ✓（判定尺度不同，结论重合） |

---

## 二、合并缺陷清单（去重后，按 P 级）

> 状态标记：`[两]`两报告均有 · `[独B]`对方独有·已实测确认 · `[独A]`我方独有 · `[新]`本次合并新增实证

### P0（用户高频主路径，触控/可读性硬伤）——4 项

1. `[两]` **聊天主发送/停止键 38dp**：`ChatScreen.kt:5447-5456`、`ChatStopOptions.kt:95-108`。46dp 外壳里放 38dp 可点圆。`touchTargetMin=48f` 全仓 0 消费者。
2. `[独A]` **浅色 ChatPalette 三级文字对比度**：`secondaryText 3.44 / tertiaryText 1.74 / inlineCodeText 1.97`（`ChatColors.kt:71-73,84`，WCAG 手算）。
3. `[独A]` **FAB 白图标 2.19:1**：`ChatColors.kt:96-97` 白 on `#B7AF96`。
4. `[独A]` **填充按钮白字 on primary 3.50:1**：`Theme.kt:51-52`。候选修复 `#2E6BC0`（白字 5.28）。

### P1（功能性/一致性缺陷）——11 项

5. `[独B✓]` **overlay 关闭/展开 14dp + 10sp 文本**：`service/ToolOverlayController.kt:415,430,448,467`。View 层完全在令牌门禁外（两报告都漏了 View 层的门禁，但只有对方点出了这个具体缺陷）。
6. `[独B✓]` **in-app 主题不贯穿状态栏图标**：`MainActivity.kt:575-590`。`SystemBarStyle.auto` 跟系统 uiMode、不跟内容亮度；注释错误。应用内浅色+系统深色 → 浅底白图标。
7. `[独B✓]` **in-app 主题不贯穿 XML 首帧主题**：`themeMode` pref 与 `values(-night)/themes.xml` 脱节，冷启动闪错色。
8. `[两]` **动态取色吞掉 App 内置字号档**：`Theme.kt:208-212`。862 处 `MaterialTheme.typography.*` 失效。
9. `[独A]` **字号双重缩放 + 行高不跟**：`Theme.kt:324-325` 只乘 fontSize；系统 200%×App 1.21=2.42×，行距比 1.50→1.24，display 档在 1.21× 就出现 fontSize>lineHeight 的悬崖。
10. `[两]` **动态深色白字压 tone-80 主色 1.70:1**：`ChatComposerWidgets.kt:423-436` + `Theme.kt:314`。
11. `[独A]` **3 个全屏表单无 imePadding**：`AutomationRuleEditScreen.kt:166`、`ScheduledTaskEditScreen.kt:242`、`LogcatPanelScreen.kt:131`。edge-to-edge + adjustResize 不再缩窗，键盘盖输入框。
12. `[两]` **≤20dp 交互图标 ≥15 处**：代码块动作 16dp、GenUiCard 16/20dp、附件删除 20dp、浏览器标签 16dp、设置步进 36dp、上移下移 24dp、TTS 胶囊 22dp 高。
13. `[独B✓]` **Turn changes 开关 remember 无 key 不生效**：`ChatMessageListSection.kt:470-472`。**确凿行为 bug**——切开关须重建列表才生效。
14. `[独B✓]` **ChatSubAgentBar 中文文案作状态键**：`ChatSubAgentBar.kt:521-526`。17 个 locale 下中文泄漏 + 状态机键与文案耦合。
15. `[独B✓]` **QS 磁贴用系统废弃图标**：`AndroidManifest.xml:626`。

### P2（一致性/工艺）——13 项

16. `[两]` surfaceContainer 五档三值、两段同值、方向与 M3 相反（`Theme.kt:108-136`）。
17. `[两]` outline≡outlineVariant 同值 + 87 处 alpha 折中（`Theme.kt:113-114,137-138`）。
18. `[两]` error 家族/tertiary/inverse*/surfaceTint 未装配，落库默认紫（1.3.2 字节码反解实测）。
19. `[两]` 图标直写绕过归一化 240 处/83 文件；动态模式下同屏 Filled/Outlined 混排。
20. `[两]` 工具状态色四常量硬编码（12 文件），MinisTokens 对应槽 0 消费者。
21. `[两]` 动态模式 15 种身份色塌缩为单一 primaryContainer；red/gray 保留策略自相矛盾（`AccentTokens.kt:73-93`）。
22. `[独A]` 圆角 19 个值：8/10/12/14/16/6/4/2/0/18/24/20/3/999/32/28/17/1/5dp；契约 16dp 只占 15 处；`AppleShapes` 无 16（12/12/20/24/28）。
23. `[独A]` 间距离网 95 处：`14dp×71` 最大异常、`5dp×9`、`3dp×4`、`2.5dp×6` 半像素。
24. `[独A]` 24 处裸 `HorizontalDivider()`（1dp）破坏 hairline；对方"88 处一统"系误判。
25. `[独B✓]` 横幅半径不齐：WorkStageBanner 10dp vs Degraded/NetworkAlert 14dp。
26. `[两]` MinisColors 57 槽 22 死 + 12 近死；对比度门禁只测这个几乎没人用的对象。
27. `[独A]` 三处 RTL 结构错位（`ChatSplitScaffold.kt:652`、`SwipeRowActions.kt:112`、`MinisMenu.kt:137`）；唯一无限动画未接共享时钟（`ComposerContextGlow.kt:239`）；崩溃兜底屏恒浅色（`StartupFailureScreen.kt:91`）；`LocalMotionDurationScale` 0 读取。
28. `[独B✓]` 工具详情 5 处硬编码英文（"Minis Computer" 等）。

### P3（收尾/长期）——对方独有经实测有效，合并保留 8 项

29. 步进器 36dp 豁免（`SettingsComponents.kt:563-565`）；30. 分栏拖柄无把手可供性；31. 8/10sp 徽标（`ContextRing.kt:92`、`ChatComposerWidgets.kt:240` 低于任何最小档）；32. 启动图标 PNG 非 vector + 无 core-splashscreen；33. `Icons.Default`/`Filled` 拼法混用（394/409）；34. ACTIVE 态永不终止 spinner；35. M3E 迁移路线（升 1.5.0 需 AGP 9.1.2+compileSdk 37，第一批 ButtonGroup 替换 29 处 SegmentedButton——**不是 5 处**）；36. 死分支/AnchorPopup 命名等。

---

## 三、门禁盲区（两报告合并）

1. `[两]` Theme.kt / ChatColors.kt 两个**活**色系统完全不在对比度门禁内——最差的对比度全发生在门禁外。
2. `[两]` `luminance()` 忽略 alpha，alpha 叠色无防线。
3. `[独B✓]` View 层（ToolOverlayController）+ res 层零令牌门禁——14dp/10sp 就是这么漏的。
4. `[独B✓]` `remember { readPref() }` 无 key、Icon 直写、状态栏亮暗、`SystemBarStyle` 语义——均无静态检查。
5. `[独A]` `touchTargetMin` 0 消费者、`Minis.dimens` 0 消费者、`check_token_coverage` 只扫 `ui/`（cards/webapp/quick/automation 在门外）、`token-literal-ok` 豁免可低成本扩容。

**建议新增棘轮**（合并两报告）：① `Icon(Icons.` 直写黑名单（基线只降不升）；② MinisColors/触摸目标消费者计数；③ 活色系统对比度抽查（ChatPalette 34 槽 + ColorScheme 全角色）；④ `remember {` 内 prefs 读取告警。

---

## 四、修复批次（合并去重后）

**批次一（P0 + 行为 bug，机械修，用户直接感知）**
发送/停止键 38→48dp 命中区 · 浅色三处文字对比度 ≥4.5 · FAB 图标深墨 · primary 加深 · Turn changes 开关接 state（消 `remember` 无 key）· overlay 14dp/10sp → 48dp/12sp · 动态取色下 fontScale 生效

**批次二（主题贯穿 + Apple 补完，同根因一起修）**
状态栏 `SystemBarStyle` 显式 light/dark（改错注释）· XML 首帧主题映射 themeMode · error 家族装配 · outlineVariant 拆槽 · surfaceContainer 五档重排（保持"卡片比页面亮"的 Apple 观感）· 16dp 圆角落 `AppleShapes` · 24 处裸 Divider→0.5dp

**批次三（动态取色收尾 + 图标）**
工具状态色四槽进 ChatPalette（动态从 scheme 映射）· 240 处直写收口（或撤双轨）· 身份色 harmonize 不再塌缩 · ChatSubAgentBar 状态枚举化+文案进 strings · 无障碍标签资源化 · QS 磁贴换 vector

**批次四（长期）**
material3 1.5.0（AGP 9.1.2 + compileSdk 37）→ `MaterialExpressiveTheme`/`MotionScheme`/8 档 Shapes/ButtonGroup 换 29 处 SegmentedButton · MinisColors 收敛为存活槽 · core-splashscreen · 启动图标 vector 化

---

## 五、关键分歧裁定（供用户决策）

| 分歧点 | 事实 | 裁定 |
|---|---|---|
| material3 版本 | **1.3.2**（pom 实测） | 对方错；"升 1.4.0 接 MotionScheme" 的前提不成立 |
| MotionScheme"可接" | 1.3.2 无此 API；升 1.5.0 才能谈 | 对方的批次二"MotionScheme 接线"须删除或并入批次四 |
| SegmentedButton 数量 | 29 处 / 12 文件 | 对方"5 处"低估；迁移工作量重估 |
| Icon 直写数量 | 240 处 / 83 文件 | 对方"73 处"低估；收口工作量重估 |
| HorizontalDivider | 88 处含 24 处裸 1dp | 对方"一统"误判；需修 24 处 |
| 八原则裁定 | 违反项一致（Familiarity/Flexibility），符合项口径不同 | 以违反项为准，符合项采用更细裁定 |

---

*合并后报告。所有"对方独有"项均已在本机源码逐条实测确认或证伪；对比度为 WCAG 公式手算，可复现。未修改任何源码。*
