# Plan/Goal 卡片：向上展开 + 压缩高度

日期：2026-10-27
状态：已实现
接续：`docs/superpowers/specs/2026-10-27-plan-card-visual-redesign.md`（PR #158 的视觉重设计）

## 起因

PR #158 把收起态从 84dp 压到 44dp 一行，但用户实测后两点反馈：

1. **卡片整体还是偏高。** 44dp 只是摘要行本身，卡壳外面还包着 `vertical = 12.dp` 的内边距，收起态实际是 44 + 24 = 68dp，再加卡片下方 8dp 外边距，composer 上方被占掉约 76dp。行高已经是 iOS 44dp 最小命中区，不能再砍；能砍的是行高之外的空白。
2. **展开方向反了。** 卡片锚在 composer 上方，列表却渲染在摘要行**下方**：一点展开，摘要行（也就是开关）被整张列表顶上去，拇指刚按的位置变成了列表最后一行，开关滑到了屏幕中段。单手操作需要反过来——**列表向上长进对话区，开关留在拇指下**。

## 方案

- **列表移到摘要行上方**（`PlanBoardBody` 的 Column 内，`AnimatedVisibility` 在 summary `Row` 之前）。展开时卡片上沿上移，摘要行的 y 不变，开关始终停在原位。
- **动画方向跟着内容方向**：`expandVertically(expandFrom = Alignment.Bottom)` + `shrinkVertically(shrinkTowards = Alignment.Bottom)`，行从摘要行背后滑出/滑回，180ms；chevron 旋转仍是 200ms。
- **手势语义不变**：上滑展开、下滑收起。方向与内容移动方向一致（上滑 = 内容向上展开），不需要重新学习。
- **压缩垂直空白**：
  - 卡片内边距 `vertical 12dp → 2dp`。行自己已经是 44dp 命中区，行外的留白是纯高度。水平 14dp 保留（文本不贴边）。
  - 卡片下外边距 `8dp → 6dp`。
  - goal 与看板之间的分隔线垂直 padding `4dp → 2dp`。
  - 行高 `30dp → 28dp`，去掉行内 `vertical = 1.dp` padding（28dp 已含呼吸空间）。
  - 列表滚动上限 `360dp → 300dp`：列表现在是向上推的，上限过高会把最后几条消息完全盖住。
- **净效果**：收起态 68dp → 48dp（-20dp，约 29%）；goal+看板双段卡同比例变矮；展开态开关位置固定。

## 语义不变

#134 的出现/linger/清盘、goal 4s 退场不删 DB 行、`id@updatedAt` 去重、`rememberSaveable(sid)` 展开态、`planBoardCurrentStep`/`planBoardSummary` 纯函数、上滑展开/下滑收起的手势方向，全部原样。只动布局顺序与尺寸常量。

## 验收

- 收起态卡片高度 = 44dp 行 + 4dp 内边距 = 48dp（不含 6dp 下外边距）。
- 展开后摘要行屏幕 y 坐标不变；新增高度全部出现在卡片上沿之上。
- 展开动画从下往上生长，收起从上往下收缩回摘要行。
- 上滑展开、下滑收起、整行点击切换，三者行为与改前一致。
- `PlanBoardStyleTest` 原样通过（纯函数未动）。
