# Minis Ultra UI/布局深度审查报告

> 审查对象：`tall-1997/OpenMinis-Linux` @ `aae2ed58`（Android arm64，Jetpack Compose）
> 审查方式：只读静态审查 + 脚本统计 + WCAG 手算 + 依赖字节码核实（未修改任何文件）
> 依据：先加载 `apple-design` 与 `m3e` 两个技能并实读其 references；数值只有两个来源——技能 reference 原文，或本仓库实测。技能没写的标「推测」。

---

## 零、一句话结论

**UI 的"观感"是到位的（iOS 亮度层、0.5dp 细线、大圆角、克制动效、共享时钟），但"系统"是两张皮：**
令牌层（`MinisTokens` / `MinisColors` / `MinisDimens` / `touchTargetMin`）几乎没有消费者，真实界面由手写 `ChatPalette` + **3700 个 dp 字面量 + 310 个颜色字面量**驱动；而 CI 门禁守的恰恰是没人用的那套。

用户能直接感知的四个维度——**点击目标、浅色模式对比度、字体缩放、动态取色的一致性**——全部有实证缺陷，且**全部零门禁**。

---

## 一、Apple 主题完整性：6 项契约 0 项完整

契约（`~/.dsh/AGENTS.md` §四）：SF 字体度量 / hairline 0.5dp / Action Blue 选中态 / 克制阴影 / **16dp 圆角** / surfaceContainer 分层。

| 项 | 判定 | 实测证据 |
|---|---|---|
| SF 字体度量 | **未实现（仅名义）** | `Theme.kt:348` `val base = Typography()`（Roboto/M3 基线）；全仓 `fontFamily` 只有 `FontFamily.Monospace`；`res/font` 只打包 JetBrains Mono。15 个样式**全部没有 `letterSpacing`**（m3e `design-tokens.md` 的 M3 表里 bodyLarge=0.5sp、labelSmall=0.5sp）。 |
| hairline 0.5dp | **部分** | 主体系已建：`RawSpace.hairline=0.5f`、`SectionDesign.DividerThickness=0.5.dp`、31 处 `thickness=0.5.dp`。但 **24 处裸 `HorizontalDivider()`** 走库默认 1dp。 |
| Action Blue 选中态 | **部分** | 同时存在两个蓝：`Theme.kt:51` `primary=#5A8AD2`（刻意降饱和）与 `ChatColors.kt:85/87` `link/thinking=#007AFF`。同一 App 两种强调蓝。 |
| 克制阴影 | **部分（有自相矛盾）** | `MinisMenu.kt:92` 默认光晕 **24dp**；`SessionListScreen.kt:1473-1474` FAB 同时 `.shadow(8.dp)` + `elevation(6.dp)`（双影叠加）。m3e `design-tokens.md` 的 Level4=8dp 明确是"过渡用、无组件停驻"。 |
| **16dp 圆角** | **未按契约实现** | `AppleShapes = 12/12/20/24/28`（`Theme.kt:167-173`）**根本没有 16**；`SectionDesign.CardShape = RoundedCornerShape(12.dp)`（`SectionDesign.kt:34`）。实测直方图：**8dp×41 / 10dp×47 / 12dp×44 / 14dp×14 / 16dp 仅 15 处**。 |
| surfaceContainer 分层 | **部分（阶梯塌陷）** | 5 档只有 3 个不同值、且两段完全同色：`cLow=c=#FFFFFF`、`cHigh=cHighest=#F7F7FA`。相邻档对比度 `cHigh→cHighest` = **1.00:1（完全不可见，24 处消费者）**。明色 `cLow(100)` 比 `surface(95.6)` **更亮**，方向与 M3 参考阶梯相反。 |

**八原则裁决**（依 `apple-design/references/principles.md`）：

| 原则 | 裁决 | 依据 |
|---|---|---|
| Purpose | ✅ 符合 | 色彩降饱和有明确目的（`Theme.kt:31-50`：1000nit 屏不刺眼且保 WCAG） |
| Agency | ✅ 符合 | `ChatRevertBanner` 可撤销、128 处确认对话框、25 处 haptic |
| Responsibility | ✅ 符合 | 权限说明页独立、DB 损坏隔离让用户选择 |
| Familiarity | ❌ **违反** | 一个叫"动态取色"的开关同时改了字号度量、圆角体系、图标风格（见 §四 D4） |
| Flexibility | ❌ **违反** | 字号双重缩放 2.42× 且行高不跟；动态模式吞掉字号设置；38dp 主发送键；浅色正文对比不足 |
| Simplicity | ⚠️ 部分违反 | 三套颜色系统 + 281 处硬编码圆角 + 两套 Shapes 主题并存 |
| Craft | ⚠️ 部分违反 | 加分：0.5dp 体系、CI 棘轮、共享时钟、189 处决策注释；扣分：注释与代码脱节（`MinisShapesM3` 不存在、`3.62/5.93` 是旧值、"规范拷贝"缺 letterSpacing） |
| Delight | ✅ 符合 | 共享时钟呼吸指示器、菜单锚点缩放、暖米色 FAB、克制动效 |

---

## 二、动态取色：门控与平台链正确，但三处设计错误

### 正确项（已核实）

1. **SDK 门控正确**：`Theme.kt:200` `dynamicColor && SDK_INT >= S`；全仓 `dynamicLightColorScheme` 只有**一个**调用点（`:202`），<S 分支不会解析 `system_*` 资源。设置页同样门控（`AppearanceScreen.kt:216-217`），`<S` 时**禁用而非隐藏**（`enabled = monetSupported`）。
2. **默认关闭且三处一致**（`MainActivity`/`QuickActivity`/`AppearanceScreen` 都是 `false`）。
3. **壁纸换色会生效**（系统机制，非 App 责任）：material3 1.3.2 KDoc 明说 scheme 随壁纸变；AOSP `ThemeOverlayController → OMS → AMS → ATMS assetsSeq++ → CONFIG_ASSETS_PATHS` 触发 Activity 重建，而 `AndroidManifest.xml:224` 的 `configChanges` 未拦截该位。App 不自建监听是合理依赖。
4. **HCT/tonal 无需自建**：系统生成 5 key colors → 13 tone palettes → 两套 scheme，Compose 按 tone 取角色。项目无 `material-color-utilities` 依赖是正确取舍。
5. **测试存在**：`DynamicColorPaletteTest`（3）、`AccentTokensTest`（6）、`M3IconsTest`（9 含源码扫描守卫）、`InAppThemeSourceGuardTest`、`TokenLayerWiringTest`。

### 缺陷

**D-A [P1] 动态深色下"思考等级"选中胶囊：白字压 tone-80 主色 = 1.70:1**
`ChatComposerWidgets.kt:423-436` 用 `Color.White` 压在 `ChatColors.thinking`（动态 = `scheme.primary` = tone 80）上。WCAG 实测 **1.70:1**（需 4.5）。静态深色本来只有 3.65、静态浅色 4.02，此项把它恶化到几乎不可读。
→ 修：`ChatPalette` 增加 `thinkingOn` 槽，动态取 `scheme.onPrimary`（实测 7.72/6.46）。

**D-B [P2] 动态深色把聊天面层压平**：`inputBg = surfaceContainerLowest`（tone 4 = `#0E0E0E`）**比页面背景（tone 6 = `#131313`）还暗**，只差 5/255（1.041:1）。而项目自己在 `ChatColors.kt:100-108` 明确写过 iOS 深灰会"crush into a single near-black wash"，静态深色因此把 input 抬到 `#2C2C30`（1.510:1）。**动态分支把已验证的结论整个推翻了。**
→ 修：按"形态随 Apple"把暗色层强行抬档（input→`surfaceContainerHighest` 等），并用单测钉住"关键层间对比 ≥1.2"。

**D-C [P2] `tertiaryText = scheme.outline`** —— 把描边角色当正文色用。违 `apple-design/references/materials-color.md`「Don't redefine the semantic meaning of a system color」。动态浅色实测 **4.27:1**（需 4.5）。
→ 修：`onSurfaceVariant.copy(alpha≈0.6f)`。

**D-D [P2] 15 种身份色塌缩成一种**：`resolveMonetTile`（`AccentTokens.kt:117-130`）在动态模式下把 settings 分组 + session 12 个分类共 15 个色**全部映射到 `primaryContainer`**。而 m3e `design-research.md` §2 的 harmonize 是"调 hue/chroma 但**保持可识别**"。作者自己又把 red/gray 排除在表外（注释"their hue IS the message"）——策略自相矛盾。结果：动态模式下 session 分类圆点全同色，只剩图标形状区分。
→ 修：分类色映射到不同 role（primary/secondary/tertiary + container），或做 HCT harmonize。

**D-E [P2] accent 桥覆盖不全 + 硬编码绕过**：`LocalMonetDynamic` 只有 3 个读取点（全在 `AccentTokens.kt`）。`RawColors` 的 33 行工具图标 hue（`ChatToolFormatting.kt:56-89`）不受桥控制；约 40+ 处 `IosX` 直用未桥接（backup/skills/storage/mounts/provider）；`MinisMenu.kt:81` 浅色容器硬编码 `Color.White`。**但 `strings.xml:957` 对外文案承诺"surfaces, cards, chat bubbles and icons all follow"——与实现不符。**

---

## 三、对比度实测总表（WCAG 2.x 手算，半透明先合成）

| 组合 | 需要 | 实测 | 判定 |
|---|---|---|---|
| **浅色 ChatPalette** | | | |
| `tertiaryText` α0.30 → `#C4C4C6` on white | 4.5 | **1.74:1** | ❌ |
| `inlineCodeText #FF9500` on `inlineCodeBg #F2F2F7` | 4.5 | **1.97:1** | ❌ |
| **FAB `#FFFFFF` on `fabAccent #B7AF96`** | **3.0（图形）** | **2.19:1** | ❌ |
| `secondaryText` α0.60 → `#8A8A8E` on white | 4.5 | **3.44:1** | ❌ |
| `link #007AFF` on white | 4.5 | **4.02:1** | ❌ |
| `warningText` on 淡橙底 | 4.5 | **3.36:1** | ❌ |
| **MaterialTheme `#FFFFFF` on `primary #5A8AD2`**（填充按钮文字） | 4.5 | **3.50:1** | ❌ |
| `primary #5A8AD2` on `#F2F2F7`（作正文/链接色，7 处） | 4.5 | **3.14:1** | ❌ |
| `outline #747477` on `#F2F2F7`（作正文） | 4.5 | 4.18:1 | ❌（图形合格） |
| **深色 ChatPalette** | | | |
| `tertiaryText` α0.30 → `#47474A` on `#000` | 4.5 | **2.27:1** | ❌ |
| `warningText` α0.45 → `#737373` | 4.5 | **4.43:1** | ❌（差 0.07） |
| **动态深色 白 on `primary`(tone80)** | 4.5 | **1.70:1** | ❌ |
| 动态浅色 `outline` on tone98 | 4.5 | **4.27:1** | ❌ |
| **达标项** | | | |
| 深色 `link #0A84FF` on `#000` | 4.5 | 5.76:1 | ✅ |
| 深色 `onSurfaceVariant` / `primary` | 4.5 | 12.08 / 12.34:1 | ✅ |
| 深色 `userBubble #2F3A5C` + 白字 | 4.5 | 11.17:1 | ✅ |
| 动态 `onSurface` on `primaryContainer`（气泡） | 4.5 | 13.27/7.24:1 | ✅（语义不规范但安全） |
| 深色代码块 `#8CF38C` on `#262626` | 4.5 | 11.04:1 | ✅ |

**共 18 组关键配对，9 组不达标。不达标全部集中在浅色模式 + 一个动态深色例外。**

**门禁为什么没拦住**：`check_token_semantics.py:66-82` 只检 `MinisColors`——而 `MinisColors` **只有 1 个文件在用**（`ChatToolDetailUI.kt`）。真实渲染的 `ChatPalette`（286 处引用）与 `MaterialTheme.colorScheme`（1411 处）**零校验**。且脚本自带豁免：`textTertiary: 3.0`、`link: 3.0`、`CONTRAST_BASELINE['Light.textTertiary'] = 2.92`。

---

## 四、令牌层与门禁：绿得很彻底，也空得很彻底

| 令牌 | 槽位数 | **真实消费者** | 说明 |
|---|---|---|---|
| `Minis.dimens`（尺寸） | 40+ | **0**（`.dimens.` 全仓 0 命中） | `LocalMinisDimens` 一直 provide，**无人读取**；平板 `Compact` 档从未生效 |
| `MinisColors`（语义色 57 槽） | 57 | **1 文件 / 6 处** | 却是唯一被对比度门禁检查的对象 |
| `RawSpace`（间距） | 19 档 | **16 处 / 3 文件** | 对照 3700 个 dp 字面量 |
| `touchTargetMin = 48f` | — | **0** | 无障碍硬要求，无人使用 |
| `MinisMotion` | — | 3 处 | 对照裸 `tween(...)` 55 处 |
| `ChatPalette`（手写） | 34 槽 | **286 处** | 真实系统 |
| `MaterialTheme.colorScheme` | 48 角色 | **1411 处 / 193 文件** | 真实系统 |

**结构性问题：令牌是字面量的镜像，不是约束。**
`scripts/ui/gen_tokens.py` 的工作方式是「扫描 `ui/` 下所有 `Color(0x…)` / `N.dp` / `N.sp` → 聚合成令牌文件」。所以 `check_token_coverage.py` 输出的 `color 310/310 / sp 35/35 / dp 3700/3700` 是个**同义反复**：每个字面量按定义都在令牌表里。它只证明"没变多"，不证明"用得好"。

这就是为什么契约里的"统一间距节奏 / 16dp 圆角"无法落地——**没有任何机制要求组件使用令牌**。

**门禁盲区（AGENTS.md 自查 8 项里 7 项零门禁）**：
- ❌ 点击目标：无任何规则检查 `clickable`/`IconButton` 实测尺寸
- ❌ 对比度：只检没人用的 `MinisColors`
- ❌ 字体缩放：无
- ❌ 深浅色：无（310 处字面量里大量无 dark 变体）
- ❌ RTL / insets / LazyColumn key / 无限动画：无
- ⚠️ 16dp 圆角 / surfaceContainer / hairline：无断言
- ⚠️ 且 `check_token_coverage` 只扫 `ui/`，`cards/`（dp 42）、`automation/ui/`（dp 24）、`webapp/`（dp 25）、`quick/`（dp 16）全在门外

---

## 五、字体缩放：三重问题叠加

**链**：`fontScaleForLevel`（0.88/0.94/1.00/1.06/1.12/1.21，`AppearanceScreen.kt:139-141`）→ `MainActivity.kt:572` → `Theme.kt:208-212`。

**D-F [P1] 动态取色模式下，内置字号档位整段失效**
```kotlin
Theme.kt:208-212
val typography = if (dynamicActive) {
    M3Typography                    // ← fontScale 在这里被丢掉
} else {
    scaledTypography(fontScale)
}
```
Android 12+ 用户一旦开启动态取色，**862 处** `MaterialTheme.typography.*` 文本对内置换字号完全无响应。设置项存在但不生效——比没有更糟。
→ 修：两个分支都施加 `fontScale`。

**D-G [P1] 双重缩放 + 行高不跟**
```kotlin
Theme.kt:324-325
private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(fontSize = fontSize * factor)   // lineHeight 不动
```
`sp` 渲染时已乘系统 fontScale，这里再乘 App 档位 → **乘法**（全仓 0 处 `LocalDensity` 覆盖）：

| 系统 | App 档 | 合计 | bodyLarge 字号/行高 | 行距比 |
|---|---|---|---|---|
| 100% | 1.00 | 1.00× | 16.0 / 24 | 1.50 |
| 100% | XL 1.21 | 1.21× | 19.4 / 24 | **1.24** |
| **200%** | **XL 1.21** | **2.42×** | 38.7 / 48 | **1.24** |

`displayLarge` 若被使用，1.21× 时 68.97sp > lineHeight 64sp → **行重叠**（当前 0 使用，是悬崖不是现症）。
→ 修：不动 `Typography.fontSize`，改用 `LocalDensity provides Density(density, fontScale * appFactor)`，并同比例缩放 `lineHeight`。

**D-H [P1] `MinisType` 完全不响应内置档位**
`Theme.kt:231` `val minisType = MinisType()` —— 裸构造。`MinisTypography.*` **305 处**（加 `fontSize = Minis*` 共 243 处）不随 App 档位变。同一屏里"分组标题变大、正文不变"。

**D-I [P2] 固定高度容器在大字号下裁切**
固定 `.height(N.dp)` **388 处** vs `heightIn(min=)` 46 处；全仓只有 **1 处**字号补偿（`ChatComposerSection.kt:109-133` 的 `slashPickerHeight`，是正确范式）。具体裁切点：`SpeechPlayerCapsule.kt:512-524`（22dp 高 chip 放 11sp 字，2× 即溢出）、`SoulSettingsScreen.kt:354`（24sp emoji 放 30dp 框）、`PickerSheetShell.kt:155-161`（42dp 固定高输入框）。

---

## 六、点击目标：主操作按钮只有 38dp

**D-J [P0] 聊天主发送/停止键 38dp** —— `ChatScreen.kt:5447-5456`、`ChatStopOptions.kt:95-108`：46dp 容器里放 38dp 可点圆。这是**每条消息都要点**的主操作。38dp 低于 Android 48dp 下限，也低于 iOS 44×44pt 默认目标。

**D-K [P1] 其它不达标交互元素**（≥15 处）：
- 输入区圆形按钮组 38dp：`ChatComposerWidgets.kt:182-188`（附件/展开编辑器）、`:218-227`（麦克风）、`:155-162`（附件删除 **20dp**）
- 代码块动作 16dp：`StreamingMarkdownRenderBlock.kt:186-198`（运行/复制）
- `GenUiCard.kt:71` `GenUiIconSize=16.dp` 与全屏态 20dp
- 浏览器标签关闭 16dp：`BrowserSheet.kt:595-598`
- 设置页：`ThinkingRulesSection.kt:310/319` 24dp、`SettingsComponents.kt:570-573` 步进器 **36dp**（且 `LocalMinimumInteractiveComponentSize provides 36.dp` 主动降档）、`ChatToolDetailUI.kt:177-183` 32dp、`SpeechPlayerCapsule.kt:512-519` 速度 chip **46×22dp**

**为什么没拦住**：`touchTargetMin=48f` 全仓 **0 消费者**；`minimumInteractiveComponentSize()` 全仓 **1 处**。48dp 只活在一行注释和生成器字符串里。

---

## 七、图标

### 达标项
- **App 启动图标合规**：`mipmap-anydpi-v26/ic_launcher.xml` 有 `background` + `foreground` + **`monochrome`**（Android 13+ 主题图标可用），day/night 变体、classic light/dark 变体、`roundIcon`、`ic_launcher_foreground_{light,dark}` 全齐。这是本次审查里做得最完整的一块。
- **contentDescription 覆盖率高**：547 次 `Icon()` 调用，仅 **5 处**疑似缺失（`EmptyStateHint.kt:27`、`ProviderQuotaSection.kt:188/200`、`AppearanceScreen.kt:638`、`TerminalScreen.kt:453`）。

### 缺陷

**D-L [P1] 双轨图标系统覆盖率仅 36.3%**
`LocalUseRoundedIcons` 定义/提供各 1 处，但**唯一读取点**是 `M3Icons.kt:497` 的 `m3Icon()` 包装器。实测：`m3Icon(` 调用 **337** 次 vs 直接 `Icons.X` 引用 **928** 次 → **36.3%**，168 个文件完全绕过归一化。动态模式下同一屏会出现 Filled 与 Outlined 混排。

**D-M [P2] `LocalUseRoundedIcons` 名不副实**：名字说 Rounded（Material Symbols 圆角族），实现是 `Icons.Filled ↔ Icons.Outlined` 的互换（`M3Icons.kt:491-494`，141 对映射）。Rounded 族从未被使用；Material Symbols 的 weight/fill/grade/optical-size 四轴一个都没用。

**D-N [P2] 图标尺寸离散度大**：`Modifier.size(N.dp)` 分布 18dp×86 / 20×70 / 16×60 / 14×43 / 22×20 / 24×18 / 28×17 / 12×15 / **17×14** / **13×13** / 36×13 / 8×12 / 10×12 / **46×3** …共 20+ 档。存在 17dp、13dp、9dp 这类非光学尺寸档，且图标不随字号缩放（`fontScale` 变大时图标不变，比例失衡）。

**D-O [P2] 图标家族混用**：`Icons.Default` 409 + `Icons.Filled` 394 + `Icons.Outlined` 427。`Default` 是 `Filled` 的别名，所以实际是 Filled 系 803 vs Outlined 427 混排（chat 系偏 Filled、settings 系偏 Outlined）。

---

## 八、布局与间距：真实数字

**圆角**：19 个不同值 —— `10dp×42 / 12dp×42 / 8dp×39 / 16dp×15 / 6dp×15 / 14dp×13 / 4dp×8 / 2dp×7 / 0dp×6 / 18dp×4 / 24dp×4 / 20dp×3 / 3dp×3 / 999dp×2 / 32dp×2 / 28dp×2 / 17dp×1 / 1dp×1 / 5dp×1`。
**契约要求的 16dp 排第 4，只占 8%。**

**间距**：21 个不同 padding 值，其中 **95 处在 4dp 网格之外**：`14dp×71`（最大异常值）、`5dp×9`、`3dp×4`、`2.5dp×3`、`18dp×3`、`30dp×2`、`9dp×1`、`36dp×1`、`44dp×1`。
`14dp` 出现 71 次却没有对应令牌——它不是 4/8 网格，也不是 M3 间距阶梯（`4/8/12/16/24`）上的值。

**半像素**：`2.5.dp`（6 处）、`0.75.dp`（2 处）、`1.6/1.8.dp` 描边。mdpi（1x）下会被舍入成不对称像素，细线发虚、上下留白不均。

**其他达标项**：
- ✅ **RTL 方向 API 卫生极好**：`padding(left=/right=)` **0 处**、`Alignment.Left/Right` **0 处**；`start/end` 137/100、`Icons.AutoMirrored` 114 处/61 文件、`supportsRtl="true"`。
- ✅ **长列表 key 稳定**：50 个 Lazy 容器 / 100 个 item builder 中 67 处带 key；聊天主列表 `key = item.key` + `contentType` + `reverseLayout` 锚定；`FlatKeys.of` 有 key 不变量断言。仅 1 处多元素可删列表漏 key（`BrowserSettingsSheet.kt:409`）。
- ✅ **无限动画从 15 处收敛到 1 处**，`DecorClock` 有 30fps 共享相位 + 消费者计数 + 后台门（6 文件 14 消费点）。
- ✅ **edge-to-edge 顶层安全区无缺口**：76 个顶层屏幕中 3 个无 inset 的都不在 edge-to-edge 路径或为嵌入组件。

**缺陷**：

**D-P [P1] 3 个全屏表单在键盘弹出时会遮住输入框**（无 `imePadding`）：`AutomationRuleEditScreen.kt:166`（15 个字段）、`ScheduledTaskEditScreen.kt:242`、`LogcatPanelScreen.kt:131`。原因：`enableEdgeToEdge` 已开，API 30+ 下 `adjustResize` 不再自动缩窗，必须自己消费 IME inset；`SettingsScaffold` 之所以没事是靠它内部的 `.imePadding()`。

**D-Q [P2] RTL 三处结构性错位**：`ChatSplitScaffold.kt:652/670-672`（分屏拖拽把手用物理 LTR offset）、`SwipeRowActions.kt:112/131-136/173`（动作列镜像了但滑动方向没镜像）、`MinisMenu.kt:137-141` / `AnchorPopup.kt:60`（`anchorBounds.right` 对齐）。**无任何 RTL 测试，res 也无 RTL 语言**，只能由系统语言触发。

**D-R [P2] 唯一的无限动画没接共享时钟**：`ComposerContextGlow.kt:239-246`，可见期间独立 60fps 重绘。而 `Theme.kt:246-252` 的注释自称"8 处全部迁移"。另全仓 `LocalMotionDurationScale` **0 读取** → 无"减少动效"分支（无障碍缺失）。

**D-S [P2] 崩溃兜底屏不随主题**：`StartupFailureScreen.kt:91`、`NewerDatabaseGuidanceScreen.kt:39` 用裸 `MaterialTheme { }` → 恒浅色，深色模式下白底闪屏。

---

## 九、"M3 Expressive" 宣称名不副实（已用依赖字节码核实）

**事实**：`libs.versions.toml:33` `composeBom = 2025.09.00` → 该 BOM 把 `material3` 钉在 **1.3.2**（本机 Gradle 缓存实测 `material3/1.3.2`、`material3-android/1.3.2`）。

material3 1.3.2 里：
- ❌ 没有 `MaterialExpressiveTheme`
- ❌ 没有 `MotionScheme`（1.4.0-alpha02 才有）
- ❌ `Shapes` 只有 5 个参数（M3E 的 8 档要 1.5.0）

所以代码里这些表述**是错的**：
- `Theme.kt:166`「M3 Expressive 在动态取色时替换（见 **MinisShapesM3**）」—— 该类不存在（实际叫 `M3ExpressiveShapes`）
- `Theme.kt:209`「M3Typography // M3 官方 Type Scale」—— 是手抄且缺 15 个 `letterSpacing`、`labelSmall` 行高 12.98sp（规范 16sp）
- `Theme.kt:205-206`「动态 → M3 Expressive（官方 Shape tokens…）」
- `M3Icons.kt:314`「动态取色（M3 Expressive）→ Outlined（Material Symbols 风格）」

**实际是**：Material You 动态色板 + M3 baseline 字阶 + M3 baseline 5 档圆角 + 手写 easing（`AppNavigation.kt:68-69` 的曲线数值本身与 M3 规范逐位相同，但不受主题驱动）。

**两条路线**：
- **A（真做 M3E）**：compileSdk 36→37、AGP 8.7.3→9.1.2+、material3→1.5.0、`MaterialExpressiveTheme` + `MotionScheme` + 8 档 Shapes、显式声明 `material-icons-core`。**但 M3E 的形态（大圆角/形状张力/弹性动效）与契约"形态随 Apple"直接冲突。**
- **B（推荐）**：承认现状是"M3 baseline 动态取色"，只改注释/命名（零风险）；把 shapes/typography/图标从 `dynamicActive` **解耦**；只补色板语义（D-B/D-C/D-D）。形态继续走 Apple。

---

## 十、按优先级的整改清单

### P0（用户高频可见，且违反自家无障碍契约）
1. **聊天主发送/停止键 38dp → 48dp 命中区**（视觉保持 38dp，外套 46/48dp 可点容器）
2. **浅色 `ChatPalette` 三处对比度**：`secondaryText` 3.44、`tertiaryText` 1.74、`inlineCodeText` 1.97 → 全部 ≥4.5
3. **FAB 图标 2.19:1**：`fabOnAccent` 白 → 深墨（实测 `#1C1C1E` = 7.77:1）
4. **`primary` 作填充按钮文字 3.50:1**：`onPrimary` 白字不达标 → 加深 primary 到 `#2E6BC0`（白字 5.28）或 `#2B62AE`（6.07）

### P1（功能性缺陷）
5. **动态模式吞掉字号设置**（D-F）+ **双重缩放 & 行高不跟**（D-G）+ **`MinisType` 不响应**（D-H）
6. **动态深色白字压 tone-80 = 1.70:1**（D-A）
7. **3 个全屏表单补 `imePadding`**（D-P）
8. **≤20dp 的交互图标补命中区**（D-K，≥15 处）
9. **图标双轨覆盖率 36.3% → 收口**，或干脆撤掉双轨（D-L/D-M）
10. **`touchTargetMin` 真正被消费**：抽一个 `Modifier.minHitTarget()`，替换 30 处显式 `.size(<48dp)`

### P2（一致性/工艺）
11. 圆角收敛到 3-4 档（契约的 16dp 定为卡片层），281 处硬编码 → 令牌
12. `14dp×71` 等 95 处离网 padding 收敛；`2.5/0.75dp` 半像素清理
13. `surfaceContainer` 五档拆成 5 个不同值（现在两段同色 1.00:1）
14. `outline` vs `outlineVariant` 拆开（现在同值）
15. ColorScheme 补齐 11-15 个缺失角色（现在 `error`/`surfaceTint`/`inverse*` 落库默认紫）
16. 24 处裸 `HorizontalDivider()` → 0.5dp
17. `MinisMenu` 24dp 光晕 / FAB 双影收敛
18. 动态模式身份色塌缩（D-D）；accent 桥补齐或改文案（D-E）
19. 三处 RTL 错位；唯一无限动画接时钟；兜底屏随主题；`LocalMotionDurationScale`

### 门禁（根因：没有门禁守真实渲染）
20. **`check_ui_tokens` / `check_token_coverage` 升级**：从"数字面量"改成"数消费者 + 数实测尺寸/对比度"
21. 新增：点击目标检查（扫 `clickable`/`IconButton` 的 size 链）、真实调色板对比度检查（`ChatPalette` 34 槽 + ColorScheme 全角色）、`touchTargetMin` 消费者检查
22. `check_token_coverage` 把 `cards/`、`automation/ui/`、`webapp/`、`quick/` 纳入

---

## 十一、审查方法与证据边界

- **技能**：已加载 `apple-design`（读 principles / accessibility / layout / materials-color / typography-symbols / components / platforms / patterns-gestures）与 `m3e`（读 design-tokens / color-typography-shape / m3e-api-signatures / version-baseline / design-research / compose-patterns / compose-api-full）。
- **对比度**：全部用 Python 标准库手算 WCAG 2.x（`srgb_to_lin` → 相对亮度 → 比值），半透明色先按背景 alpha 合成。与仓库 `check_token_semantics.py:85-98` 的公式一致，结果可复现。
- **依赖事实**：读取本机 Gradle 缓存确认 `material3 = 1.3.2`（不是从训练记忆推断）。
- **统计口径**：`src/android/app/src/main/java/com/openminis/app` 下 1608 个 `.kt`。
- **推测项（明确标注）**：
  - iOS 的「连续曲率 squircle」——`apple-design` 技能 7 个 reference 都没写，按铁律标推测；Compose 侧已核实 `RoundedCornerShape` **无曲率参数**（椭圆/G1 连续），不是 squircle。
  - 壁纸换色的 OEM 兼容性——链路基于 AOSP 源码 + material3 KDoc，未在真机验证（本次只读）。
  - 双影/光晕的"观感"严重度——数值与规范可核，视觉影响需真机截图。
- **未覆盖**：真机多字号布局截图、TalkBack 焦点顺序实测、RTL 实际渲染、广色域 P3、壁纸色板逐角色对比度。

---

*本报告只读产出，未修改仓库任何文件。*
