# 长跑失忆的根因与修复（2026-11-19）

> 症状：模型在一个长 agent loop 里"忘事"——忘了当前任务、忘了已经做过的工具轮、
> 忘了自己刚改过哪个文件；历史上修过很多轮（#327/#331/#335/#337/#339/#363/#366），
> 症状仍在。本文记录本轮排查的根因、修复与验证口径。
>
> 结论先说：**不是摘要质量的问题，是"锚点之后、摘要之外"的这块区域被预算直接
> 丢弃，而紧凑门限测的正是被同一个预算压小后的切片，导致锚点第一次压缩后再也
> 不前进**。围绕这条主链，另有 6 处静默丢失/误导点一起修了。

## 一、主链：postAnchor 被丢 + 门限被蒙蔽（P0）

```
compact 锚点 A ──摘要覆盖 A 之前──►  [ preAnchor(≤20%) ][ postAnchor(≤35%) ]
                                              ▲                  ▲
                                     已摘要，冗余细节        未摘要，却被预算丢弃
```

1. `effectiveAgentHistoryUncounted` 把 postAnchor 限制在窗口的 35%（上限 60k）。
2. `contextTokensForPolicy` 的第三项测量的正是这个**已裁剪**的切片：
   `summary + preAnchor(20%) + postAnchor(35%) ≈ 55%`，永远低于 75% 的压缩阈值。
3. 于是第一次压缩之后 compact **再也不触发**，锚点冻结；postAnchor 继续增长，
   每轮都把最老的整轮丢掉。
4. 丢掉的内容没有进摘要（摘要写在锚点之前），旧提示行却声称
   "the summary above covers them"——模型被明确告知不必重读/重跑。

### 修复

| 标记 | 落点 | 行为 |
|---|---|---|
| `T-compact-gate-unblinded` | `ChatViewModelTokenExt.kt`、`ChatViewModelAgentHistoryExt.kt`、`ChatViewModelPostAnchorTail.kt` | 门限测量 **un-budgeted** 切片（`applyTailBudget = false`）；一旦超过阈值，压缩真正触发、锚点前移，后续丢弃变成"被摘要覆盖"。预算值单一来源 `budgetTokensForWindow()`。 |
| `T-postanchor-truthful-notice` | `ChatViewModelPostAnchorTail.kt` | 提示改为真话：这些轮次**不在**摘要里；给出 digest 与 working-memory 指引。 |
| `T-postanchor-dropped-digest` | `DroppedRoundDigest.kt`（新） | 同一请求内保留被丢轮次的**用户指令 + assistant 决策**摘录（≤3k 字符、≤24 行，取最新），工具明细交给 working-memory ledger。 |
| `T-detached-drop-truth` | `ChatViewModelCompactTail.kt` | 锚点被逐出窗口时（原实现静默只留最近 60 条），在 `<context-summary>` 信封内写明丢弃条数 + 最近 40 条摘录，不再无声消失。 |
| `T-compact-reduced-anchor` | `CompactRetryInput.kt`（新）、`ChatViewModelTwoShotCompactExt.kt`、`ChatViewModelCompactExt.kt` | 最后一次缩量重试改为摘要**最老**的 3/5（原来是"最新 3/5"），且锚点只推进到**实际被摘要覆盖**的位置。原来会把未摘要的 2/5 标成已压缩，永久消失。 |
| `T-compact-summary-target` | `ChatViewModelAgentHistoryExt.kt` | 摘要在 postAnchor 里注入到**第一条真实用户消息**，不再注入"丢弃通知"后又被 fold 折回 pre-anchor 区域。 |

## 二、工作记忆 handover 不可用（P0/P1）

`WorkingMemoryLedger` 是 #331 为"被丢轮次"加的记账，但每行是
`turn 42: file_edit(9f3a1b2c) → Successfully edited` —— 8 位 SHA，模型根本不知道
改的是哪个文件、跑的是哪条命令，于是重做。另有两个问题：只发最老的 40 行；
溢出时把未投递的记录也折成一句 "see summary"。

### 修复

| 标记 | 落点 | 行为 |
|---|---|---|
| `T-working-memory-readable` | `WorkingMemoryLedger.kt`、`WorkingMemoryArgs`、`ChatViewModelToolTurnExec.kt` | 复用 tool-card 的 `resolveToolDetail` 提取 path/command/query；哈希只做折叠身份。未知工具回退 `key=value`，永远单行、截断、清洗框架标签。 |
| `T-working-memory-newest-first` | `WorkingMemoryLedger.kt` | 每条一个 delivered 标记；每次投递**最新的** 40 条（紧邻保留尾部的轮次才是下一步会踩到的），其余下一轮再投。溢出折叠只发生在最老端，折叠行不再谎称 "see summary"。 |

## 三、任务锚点一直是"会话第一条消息"（P1）

`_taskAnchorText` 只在 `loadSession` 读一次（`firstUserPartsJson`），之后永不更新。
用户换任务后，系统提示仍每轮重申旧任务；长跑裁剪后旧锚点成了模型唯一能看到的
"任务"，于是继续做旧任务。长首条消息还被 head-only 截断到 1200 字，尾部的验收
标准被砍掉。

### 修复

| 标记 | 落点 | 行为 |
|---|---|---|
| `T-task-anchor-current` | `PromptAnchors.kt`、`ChatViewModelPromptExt.kt` | 锚点优先级：**live window 里最新的实质用户请求** > DB 首条 > 窗口首条。薄跟进（"继续/ok/重试"）不覆盖任务；找不到实质消息时回退上一条。 |
| `T-task-anchor-edges` | `PromptAnchors.kt` | 超长锚点保留**头 + 尾**（尾 400 字），中间标注 omitted；验收标准不再被截掉。 |

## 四、规则块被预算整块丢弃，合同仍称 BINDING（P1）

24k 记忆预算下，project/session/app 三个规则块是 `truncatable = false`：
一旦放不下就**整块丢**，但 `StandingContract` 的布尔来自 dedupe 结果，不是实际
分配结果。模型被告知"这些规则就在 prompt 里、必须遵守"，实际一个字都没有。

### 修复

| 标记 | 落点 | 行为 |
|---|---|---|
| `T-standing-contract-budget-truth` | `PromptAnchors.kt`（`standingRuleContract`）、`ChatViewModelPromptExt.kt` | 合同布尔 = dedupe 保留 **且** 预算实际接纳；被预算丢掉的块追加一行明确说明（NOT included + 去哪里重读），不再声称 BINDING。 |
| `T-publish-gate-purity` | `ChatViewModelPromptExt.kt` | Token Usage sheet 的测量型构建（`publishStablePrefix=false`）不再改写 `residentDailyFiles`、不再覆盖 context-assembly 快照。 |

## 五、工具结果老化：只留头 + 按结果数切并行轮（P1）

`ToolResultAging` 在 30k token 绝对阈值下把"最新 6 条之外"的结果折成 160 字**头部**。
两个问题：

1. 构建/测试日志的关键行在**尾部**（错误、summary、exit），头部通常只是噪声；
2. `keepRecent` 按**结果条数**算，一个 10 并发读取的轮次会占满 6 个名额，
   同一轮里 4 条结果下一轮就被折掉。

### 修复

| 标记 | 落点 | 行为 |
|---|---|---|
| `T-age-head-and-tail` | `ToolResultAging.kt` | placeholder = 头 160 + `… [tail]` + 尾 160；错误仍不折叠。 |
| `T-age-round-coherent` | `ToolResultAging.kt` | keep 窗口按**轮**对齐：从最新保留结果所属的 assistant 消息向前整轮保留，并行轮不会被切开。 |
| `T-budget-ruler-complete` | `ChatViewModelWalkBack.kt` | 预算估计器补上 ToolUse 参数 JSON 与图片（1,500 tok/图），与门限用同一把尺；避免"60k 预算实际 130k"。 |

## 六、其他静默丢失点

| 标记 | 落点 | 行为 |
|---|---|---|
| `T-state-hydration-on-drop` | `ChatViewModelAgentHistoryExt.kt` | 尾预算一丢轮次就重新武装状态快照（plan/report/jobs），不再依赖 ledger 是否还有未投递行；快照本身 sha 去重，不会每轮刷屏。 |
| `T-drain-prompt-refresh` | `ChatViewModelDrainQueueExt.kt`、`ChatViewModelRunPrompt.kt`（新）、`ChatViewModelAgentLoopExt.kt` | 排队消息是**新的用户轮**：drain / 中途插话时重建系统提示（锚点、日记门控、召回查询全部随之更新），不再复用旧提示。 |
| `T-recall-delivery-accounting` | `MemoryRecallEngine.kt`、`ChatViewModelPlanContextExt.kt` | `formatAsPromptFragmentWithHits` 返回**实际渲染**的 hit 集合；只把这些标记为已宣布。被 2400 字符上限截掉的命中不再被永久静默。 |
| `T-recall-cjk-query` / `T-recall-thin-followup` | `ChatViewModelPlanContextExt.kt` | 中文 2 字即可成查询（"部署/回滚"）；"继续/ok"这类薄跟进不算任务，回溯到上一条实质用户消息取查询。 |
| `T-known-failure-readable` | `ToolFailureMemory.kt` | 已知失败闸门的拒绝文案补上参数摘要（path/command/query），模型终于知道"被拒绝的是哪一次调用"，能改对参数而不是盲目重试或放弃。 |

## 七、验证

新增/更新的回归测试：

- `PostAnchorBudgetTest`（预算单一来源、un-budgeted 不丢、估计器补参数、摘要注入目标）
- `DroppedRoundDigestTest`（用户/assistant 摘录、跳过 host note、边界与新近优先）
- `CompactGateArchitectureTest`（门限必须测量 un-budgeted 切片；缩量锚点只覆盖已摘要前缀）
- `CompactDetachedAnchorTest`（detached 缺口提示 + 摘要信封内、digest 取最新）
- `CompactResilienceTest`（缩量重试取最老 3/5）
- `ToolResultAgingTest`（头+尾、并行轮整轮保留、估计器/折叠不增长）
- `WorkingMemoryLedgerTest`（可读参数、最新优先、一次性投递、溢出）
- `TaskAnchorTest`（当前任务优先、薄跟进、头尾裁剪、surrogate 安全）
- `StandingContractTest`（预算丢弃 → 不再称 BINDING + 显式 omission）
- `MemoryRecallEngineBatchTest`（只把实际渲染的 hit 记为已投递）

本地命令（Windows，无 `gradlew.bat`，用 wrapper jar）：

```powershell
cd src/android
java "-Dorg.gradle.appname=gradlew" -classpath gradle\wrapper\gradle-wrapper.jar `
  org.gradle.wrapper.GradleWrapperMain :app:testReleaseUnitTest `
  --tests "com.openminis.app.ui.chat.*" --tests "com.openminis.app.agent.ToolResultAging*" `
  --tests "com.openminis.app.data.Compact*" --tests "com.openminis.app.data.repository.Memory*"
```

门禁：`python scripts/ci/check_architecture_policy.py`、`check_cjk_literals.py`、
`check_announced_tools.py`、`check_string_refs.py`、`check_new_untranslated.py`。

## 八、明确未做 / 后续

1. **WorkingMemoryStore 未持久化**：进程被杀后未投递的 handover 行仍会丢。
   正确做法是像 `NoticeLedgerStore` 一样按会话落盘（含 delivered 标记）。
2. **postAnchor 单轮（一次用户输入 + 300 工具轮）时 `splitRounds` 只有一组，
   尾预算直接跳过**：本轮靠门限修复让压缩兜底，但预算本身仍应支持"轮内按
   assistant 步切分"。属结构性后续。
3. **`ToolFailureMemory` 记录仍按"工具+精确参数"哈希索引**：文案已可读，
   但失败记录本身没有语义检索能力（换一个近似参数就重新踩坑），后续可接
   项目经验库/失败模式聚类。
4. **未做向量召回**：与 #228 结论一致，无后端且关键词+时效已够用。
5. **未改 summary prompt 的 500 字/消息截断**：摘要质量不是本轮根因；若后续
   出现"摘要漏掉关键路径"，再单独评估。
