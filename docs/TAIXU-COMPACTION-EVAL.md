# taixu compaction 本体移植评估（2.4 交付物）

> 结论先行：**不移植本体，只保留已嫁接的投影链**。我方 compaction 是
> taixu 的严格超集（two-shot、split、leaf、chunk 池、v2 锚点、分离降级、
> 有界回走——taixu 六件全无）；整换是退化，不是补齐。本报告是决策记录，
> 不是移植清单。

## 一、双方勘形

### taixu compaction 本体（4 件 1011 行）

| 件 | 行数 | 职责 |
| --- | --- | --- |
| CompactionManager | 372 | project/compact/latestSnapshot + resolveCompressAnchor（锚点解析） |
| CompactionSummarizer | 406 | 摘要生成（LLM 调用 + 提示模板） |
| BranchSummarizer | 125 | 分支（子代理）摘要 |
| CompactionModels | 108 | 数据形状 |

### 我方 compaction（六刀后）

| 件 | 位置 | 行数 | 职责 |
| --- | --- | --- | --- |
| CompactHistoryProjector | harness/agent | 323 | 三路切片（v2 锚点/v1 遗留/分离）+ 回走 + 剪枝 + 内联 |
| ContextWindowPolicy | harness/context | — | 预算截断（taixu 有同名件，我方已对齐） |
| HistoryDigest | harness/context | 135 | 窗口外摘录确定性裁剪 |
| ChatViewModelCompactExt | app 宿主 | 429 | 触发/摘要生成/标记写入 |
| ChatViewModelCompactLeaf/Plan/Split | app 宿主 | 209 | leaf 修剪 / 计划 / split |
| ChatViewModelTwoShotCompactExt | app 宿主 | 127 | 两段式压缩 |

## 二、能力对比

| 能力 | taixu | 我方 | 判定 |
| --- | --- | --- | --- |
| 锚点切片投影 | ✓（resolveCompressAnchor） | ✓（v2 锚点 + 回走 + cap） | 对等 |
| 摘要生成 | ✓（Summarizer） | ✓（CompactExt，含 two-shot） | **我方超集** |
| 分支摘要 | ✓（BranchSummarizer） | ✓（子代理波报告内联） | 对等 |
| 预算触发 | ✓ | ✓ + 令牌池共享（SubAgentTokenBudget） | **我方超集** |
| 窗口外摘录 | ✗ | ✓（HistoryDigest 确定性裁剪） | **我方独有** |
| chunk 池检索 | ✗ | ✓（summary_chunks + selectSummaryChunks BM25） | **我方独有** |
| 分离锚点降级 | ✗ | ✓（摘要+尾部逐字） | **我方独有** |
| preAnchor 剪枝 | ✗ | ✓（>1kc tool_result 连带配对剥除） | **我方独有** |
| 角色对齐 | ✗ | ✓（剥头部非 user，OpenAI 400 规避） | **我方独有** |
| two-shot 压缩 | ✗ | ✓ | **我方独有** |
| split/leaf | ✗ | ✓ | **我方独有** |
| 工具轮配对修复 | ✗ | ✓（ToolRoundPairing 有序校验） | **我方独有** |

## 三、taixu 值得抄的点（已抄完）

1. **resolveCompressAnchor 的锚点解析形状**——我方 v2 标记（id-only 锚）
   已吸收同等语义，且多了分离降级。
2. **CompactionModels 的纯数据形状**——我方 CompactMarkerEntity 等价。

## 四、taixu 不值得抄的点

1. **CompactionSummarizer 的提示模板**——我方 two-shot 模板更成熟（首段
   抽取 + 二段收敛），整换丢 two-shot。
2. **无角色对齐的切片**——taixu 切片不保证首条 user，OpenAI 系会 400。
3. **无配对修复**——压缩后 tool_use/tool_result 悬空直接发。

## 五、结论

- **不移植**。我方 1365 行（宿主 765 + harness 600）覆盖 taixu 1011 行
  的全部能力且多 8 项独有能力。
- **已嫁接部分保留**：投影链六刀（CompactHistoryProjector/
  ToolRoundPairing/HistoryDigest）就是本对齐的产出，继续持有。
- **后续动作**：无。若上游 compaction 出新能力（如增量摘要），单点评估
  嫁接，不整件搬。

## 六、未验证

本报告基于静态勘形（双方源码逐件对照），未做运行时 token 占用对比。
