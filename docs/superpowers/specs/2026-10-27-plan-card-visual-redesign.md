# Plan/Goal 卡片视觉重设计：去掉条形进度与底部拖拽条，把开关并进摘要行

日期：2026-10-27
状态：已实现（待评审）
前置：#134（看板自消失语义）、PlanGoalCard/PlanBoardStyle 三件套

---

## 1. 起因

composer 上方那张 plan/goal 卡片在收起状态下要吃掉约 84dp 高度，且信息密度很低：

```
[ ✓== ]  3/5 · rewrite the token cache                      ← 摘要行 ~30dp
[ ▓▓▓▓▓▓░░░░░░░░░░░░░░ ]                                    ← 4dp 进度条
[ ──────  ˄  ────── ]                                        ← 32dp 全宽拖拽条
```

三个都是为同一件事服务的：告诉用户"进度到哪、点这里展开"。结果是同一句话说了三遍，
而唯一的交互目标（展开/收起）被放在卡片正中间——单手拇指最难够到的位置。

## 2. 现状问题（逐条）

### 2.1 信息层级：三处同级竞争

- 摘要行 `3/5 · title`（bodyMedium + 加粗计数）
- 进度条（4dp，全宽，M3 `LinearProgressIndicator`）
- 拖拽条（32dp，居中 pill + 方向箭头）

阅读顺序上，进度条出现在"数字已经把进度讲清楚"之后，视觉重量却比数字更重；
它还在摘要行下方紧贴，读起来像标题的下划线，而不是独立组件。

### 2.2 空间：收起了但没省下空间

| 区段 | 高度 |
|---|---|
| 摘要行（bodyMedium + 上下 5dp） | ~30dp |
| 间距 | 8dp |
| 进度条 | 4dp |
| 间距 | 10dp |
| 拖拽条（min 32dp + 2dp top） | 34dp |
| **合计（不含卡片 12dp 内边距）** | **≈ 84dp** |

用户的心智是"我把它收起来了，所以它应该很小"。84dp 不满足这个预期。

### 2.3 交互：命中区和拇指位置双输

- 展开/收起的**唯一**入口是底部 32dp 条，横向居中；
- 摘要行本身不可点——它是文字，不是控件；
- 手机上单手（右手小指托底）时，拇指舒适区在屏幕下半部靠右；卡片在 composer 上方，
  底部条位于卡片中线以下，但仍是"屏幕中段"位置。

### 2.4 颜色：两套强调色打架

- 卡片本身的强调色是 App 主色 `#5A8AD2`（或动态取色 `colorScheme.primary`）；
- 看板沿用了聊天工具卡 `agent_plan` 的身份：Dashboard 图标 + 硬编码靛紫 `#5856D6`。

同一张卡上，goal 头用主色、board 段用靛紫，两个"主色"互相削弱。

### 2.5 图标：同一列里三种笔重

展开后一列状态图标：`CheckCircle`（实心盘）、`Error`（粗圆）、`RadioButtonUnchecked`（细环）、
active 用 2dp 描边的 spinner。实心/粗/细混排，视觉上像三个不同组件拼在一列；
Material filled 的圆盘在 iOS 风格的那一列文字旁边尤其突兀。

### 2.6 细节

- 卡内分隔线用满强度 `outlineVariant`，与卡片外边框同强度 → 卡片被切成几个方框；
- goal 头整行没有最小高度（约 24dp），可点区域小于 44dp；
- 分隔线与行的垂直节奏随手（4dp / 5dp 混用）。

## 3. 方案

### 3.1 结构：一个行高 44dp 的摘要行承担全部职责

```
[✓==]  3/5 · rewrite the token cache                    [˅]
```

- 摘要行本体就是开关：`heightIn(min = 44.dp)` + `clickable`，
  命中区 = 整行宽度（iOS HIG 最小 44pt）；
- 展开指示器放在摘要行 **行尾**（iOS disclosure 位置），180° 旋转 200ms 动画；
- 方向手势保留但降级为加分项：上滑展开、下滑收起，24dp 阈值、一次手势只触发一次；
- 分隔线移入摘要行之下，只在展开时出现；展开后行列表仍是 `heightIn(max = 360.dp)` 可滚动。

### 3.2 去掉进度条

`3/5` 已经精确表达进度，进度条只是把同一个数字重复一遍。计数改用
`colorScheme.primary` 的 SemiBold 字重承担强调——数字本身就是进度指示器。

### 3.3 一套强调色

- 身份由图标承担（checklist 图标），颜色不再承担身份；
- 统一用 `colorScheme.primary`（动态取色时自动跟随），全部完成时切 `ToolCheckColor`（iOS green）；
- 硬编码 `#5856D6`、`toolIconFor/toolAccentColor` 依赖全部移除。

### 3.4 自定义图标集（`PlanBoardIcons.kt`）

统一"描边优先、闭合形状、圆角端点、约 9% 笔画/画布比"的语言，也就是这一列文字
旁边的线条图标应有的笔重：

| Glyph | 用途 | 形状（24 画布） |
|---|---|---|
| `Checklist` | 摘要行身份 | 两组"勾 + 横线"，2.3 笔画 |
| `ChevronDown` | 展开指示 | 单折线，2.2 笔画（小尺寸要略加粗） |
| `Check` | done | 纯折线，2.6 笔画 |
| `Ring` | pending | 8r 圆环，2.0 笔画 |
| `XCircle` | failed | 圆 8.8r + 叉，1.9 / 2.0 笔画 |
| `AlertCircle` | goal 预算超限 | 圆 + 感叹号，1.9 / 2.1 笔画（点用零长线段 + 圆帽绘制） |
| `CheckCircle` | goal 完成 | 圆 9r + 勾，1.9 / 2.1 笔画 |
| `Pause` | goal 暂停 | 两根竖条，2.2 笔画 |

笔画单位是 24 画布内的绝对值，缩放到 13–17dp 时约落在 1.1–1.5dp：
和相邻一行正文的笔画重量对齐，而不是 Material filled 图标的实心块。

全部 `stroke = SolidColor(Color.Black)`，实际颜色由 `Icon(tint=…)` 决定——一份
`ImageVector` 单例服务所有状态色。

### 3.5 行与节奏

- 行高 `heightIn(min = 30.dp)` + 上下 1dp；
- 状态图标固定 14dp 槽位，行文本统一落在 26dp 文本原点
  （14 + 12 = 26，摘要行 16 + 10 = 26，goal 头 17 + 9 = 26）——三段文字一列对齐；
- 卡内分隔线改为 `outlineVariant @ 40%`（`planCardHairline`），让外边框成为卡上最强的一条线。

### 3.6 高度对比

| | 收起 | 说明 |
|---|---|---|
| 改前 | ≈ 84dp | 摘要 + 进度条 + 拖拽条 |
| 改后 | 44dp | 摘要行（含开关） |

目标行也补上 `heightIn(min = 44.dp)`，可点区域从 ~24dp → 44dp。

## 4. 语义不变

本次只动视觉与命中区，**不动状态机**：

- 看板出现/消失/空板 linger/完成绿 linger 后清盘，全部保持 #134 原样；
- goal 头 4s 后退场且不删 DB 行、`id@updatedAt` 去重，保持原样；
- `planBoardCurrentStep` / `planBoardSummary` 纯函数签名与语义不变（`PlanBoardStyleTest` 原样通过）；
- 展开状态仍 `rememberSaveable(sid)`，切换会话回到收起。

## 5. 验收

1. `python3 scripts/ci/check_architecture_policy.py`、`check_cjk_literals.py`、
   `check_string_refs.py`、`check_announced_tools.py` 全绿；
2. `PlanBoardStyleTest` 不变通过；
3. 视觉：按实现值核对（44dp 行、16dp 图标、10dp 间距、0.40 hairline），明暗两套主题下
   摘要行 / 展开行 / 目标状态九宫格均无层级错乱。本地 mock（`planboard_mock/v4.html`，
   会话产物不入库）与 `PlanBoardIcons.kt` 的真实 path 数据做过逐像素比对；
4. 云端 CI（Unit tests + Build APK）绿。
