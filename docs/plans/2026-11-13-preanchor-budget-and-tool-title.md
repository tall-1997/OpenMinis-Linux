# 2026-11-13 双根因修复：preAnchor 动态预算失效 + 工具卡片摘要丢失

## 背景与证据

### 根因 A：preAnchor 预算被硬编码天花板掐死（#331 遗漏）

| 证据 | 位置 | 事实 |
|----|----|----|
| 动态窗口源存在 | `ChatViewModel.kt:2389` `effectiveContextWindowTokens()` | 读 `_activeEntryId → model.contextWindowTokens`，叠加 `modelGroups.contextLimitTokens`，返回 `min(window, groupLimit)` 或 null |
| 预算表达式 | `ChatViewModelAgentHistoryExt.kt:134-137` | `WalkBack.COMPACT_PREANCHOR_TOKEN_BUDGET.coerceAtMost((windowTokens * 0.20).toInt().coerceAtLeast(8_000))` |
| 硬编码天花板 | `ChatViewModelWalkBack.kt:40` | `COMPACT_PREANCHOR_TOKEN_BUDGET = 15_000` |
| 宣告契约 | PR #331 body L7 | `COMPACT_PREANCHOR_TOKEN_BUDGET=40k` |
| 走廊契约 | `AGENT-LOOP-PARITY-2026-11-12.md:113` | `preAnchor(≤20% 窗口)` |
| 对称参照 | `ChatViewModel.kt:134-135` `compactSegmentTokenBudget` | `(contextWindow/5).coerceIn(8_000, 32_000)` — #327 的动态缩放范式（`COMPACT_SEGMENT_MAX_TOKENS=32_000`；`ContextPolicy.kt:111` 的 `40_000` 是 offload buffer，不是预算） |

**数学证明**：`f(w) = min(15_000, max(0.2w, 8_000))`。当 `w ≥ 75_000` 时 `0.2w ≥ 15_000`，故 `f(w) ≡ 15_000` 恒定；128k / 200k 主流窗口全部落在饱和区，动态项 `0.2w` 从未参与结果。仅当 `w < 75_000` 时动态项才生效，且 `w ≤ 40_000` 时被 `8_000` 地板钳住。结论：所谓"随模型窗口动态调整"是死代码，且天花板（15k）只有宣告值（40k）的 37.5%。

### 根因 B：file_read / read_image 卡片只剩裸工具名（用户 2026-11-12 17:48Z 实机截图）

| 证据 | 位置 | 事实 |
|----|----|----|
| 折叠卡渲染 | `ChatProcessCards.kt:251` | `block.toolTitle.ifEmpty { block.toolName }` — 只有一层兜底 |
| 建块初值 | `ChatViewModelAgentLoopExt.kt:778` | `toolTitle = friendlyToolTitle(name)`（"Read File" / "Read Image"） |
| 流式增量 | `ChatViewModelAgentLoopExt.kt:809-815` | `extractToolTitle(..., fallback = friendlyToolTitle(name))` — 流式阶段有友好标题 |
| 执行期结果 | `ChatViewModelExecuteToolExt.kt:107` | `extractToolTitle(argsJson, fallback = canonical) ?: canonical` — 无 `tool_title` 时回落**裸 snake_case 名** |
| 派发 | `ChatViewModelToolTurnExec.kt:519` | `extractToolTitle(call.argsStr, fallback = null)` 只喂状态栏 |
| 结算覆盖 | `ChatViewModelToolTurnExec.kt:396` | `toolTitle = result.toolTitle.ifEmpty { 旧值 }` — 裸名**非空**，把建块时的友好标题冲掉 |
| 持久化 | `ChatViewModel.kt:6491/6539` `writeAssistantParts` | `block.toolTitle` 写入 `description`；会话列表 `PreviewText.summarizeToolUse:67-118` 有完整 args→摘要能力（`file_read → "Reading /path"`），但**只服务会话列表，未接到卡片** |
| 工具 schema | `FileReadTool.kt:235/242`、`ReadImageTool.kt:19/23` | `tool_title` 是 required 参数，但取值 `args.optString("tool_title", NAME)` 缺省即裸名；是否填写取决于模型，`shell_execute`（同屏有中文描述）本次填了，`file_read` / `read_image` 没填 |
| 对照 | 74477.jpg（历史） | 同 App 同模型，`file_read` 卡片曾显示"读取停滞判定与终止策略现状"等中文描述 |
| 现状 | 74478.jpg + 用户补充 | 三个并行 `file_read` 与 `read_image` 全部退化为裸工具名 + `1s 查看 >` |

**根因定性**：`toolTitle` 存在**三个互相竞争的来源**（建块友好名 / 执行期 canonical / 模型 tool_title），结算用非空裸名覆盖友好名，折叠卡又没有从 `toolArgs` 生成摘要的兜底。凡是模型未填 `tool_title` 的工具全部裸奔；这不是单个工具的 schema 问题，是通用标题链路缺乏单一来源与统一降级策略。#331 合并（16:43Z）与截图时间（17:48Z）吻合，属 #331/#332 结算重构暴露的回归。

## 修复层次（普适，非补丁）

### A. 预算：单一纯函数 + 契约常量

1. `ChatViewModelWalkBack.kt`：`COMPACT_PREANCHOR_TOKEN_BUDGET` 由 `15_000` 改为 `40_000`；新增 `internal object` 内纯函数
   ```kotlin
   internal fun preAnchorTokenBudget(windowTokens: Int?): Int =
       if (windowTokens == null) COMPACT_PREANCHOR_TOKEN_BUDGET
       else (windowTokens * PREANCHOR_WINDOW_FRACTION).toInt()
           .coerceAtLeast(PREANCHOR_TOKEN_FLOOR)
           .coerceAtMost(COMPACT_PREANCHOR_TOKEN_BUDGET)
   ```
   常量：`PREANCHOR_WINDOW_FRACTION = 0.20`、`PREANCHOR_TOKEN_FLOOR = 8_000`、天花板 40_000。
2. `ChatViewModelAgentHistoryExt.kt:134-137` 的分支表达式整体替换为 `WalkBack.preAnchorTokenBudget(windowTokens)`；注释改为「窗口 20%、地板 8k、天花板 40k」。
3. **不动** `compactSegmentTokenBudget`（#327 独立契约 8k..32k）与 `ContextPolicy` 的 40k offload buffer。
4. 走廊校验：preAnchor ≤ 0.2w、postAnchor ≤ 0.35w、+ summary ≤ 0.8w 阈值；40k 天花板只在 w ≥ 200k 饱和，对 128k 给 25.6k，200k 给 40k，均不越 0.35w + 0.8w 约束。

### B. 标题：单一来源纯函数，覆盖全生命周期

1. 新建 `ChatToolTitleResolver.kt`：`internal fun resolveToolTitle(toolName: String, argsJson: String, existingTitle: String = ""): String`
   - 优先级：`tool_title`（含流式 partial JSON）→ `existingTitle`（非裸名、非 toolName 本身）→ 按 `toolName` 从 args 提炼：
     `file_read → "Reading {path}"`；`file_write → "Writing {path}"`；`file_edit → "Editing {path}"`；
     `read_image → "Viewing {path}"`；`shell_execute → "$ {command}"`；`browser_use → "{action} {url}"`；
     `memory_get → "memory_get: {keywords}"`；`memory_write → "memory_write: {content}"`；`agent_plan`/其它通用键（title/objective/query/prompt/description）。
   - 末级兜底 `friendlyToolTitle(name)`（Title Case，永不返回 snake_case）。
   - 纯 Kotlin + `org.json`，无 Android/Compose 依赖 → JVM 单测可直接跑。
2. `ChatViewModelAgentLoopExt.kt:778`：建块改走 `resolveToolTitle`。
3. `ChatViewModelAgentLoopExt.kt:809-815`：流式改走 `resolveToolTitle`。
4. `ChatViewModelExecuteToolExt.kt:107`：`result.toolTitle` 改走 `resolveToolTitle`，`fallback=canonical` 的裸名回落删除。
5. `ChatViewModelToolTurnExec.kt:396`：结算改走 `resolveToolTitle(name, argsStr, 旧值)`，不再无条件信任 `result.toolTitle`。
6. `ChatViewModelToolTurnExec.kt:519`：派发状态栏复用同一函数。
7. `ChatProcessCards.kt:251` + 详情标题栏：渲染层兜底改走 `resolveToolTitle`，覆盖**历史消息 restore**（老会话里已存成裸名的 block 也能显示路径摘要）。
8. `settleReplayedCall`：replay 结算同样走 resolver，避免重放卡片回退。

### 防回退（CI 静态契约门禁）

新增 `scripts/ci/check_tool_title_and_budget_contract.py`，仿 `check_tool_execution_contract.py` 的行级状态机：
- 断言 `COMPACT_PREANCHOR_TOKEN_BUDGET = 40_000`、`preAnchorTokenBudget` 分母/地板/天花板三常量齐全、`AgentHistoryExt` 只调用纯函数（禁止再出现内联 `coerceAtMost(... 15_000)` 或 `0.20` 散落）。
- 断言所有 `toolTitle =` 赋值点与折叠卡 `Text(text = ...)` 全部经过 `resolveToolTitle`，禁止裸 `ifEmpty { toolName }` 与 `fallback = canonical`。
- 挂到 `pr-check.yml` / `android-apk.yml` 的同一门禁步骤（与既有 6 个 python 门并列）。

## TDD 用例（先红后绿，本地仅跑 Python 门禁 + JVM 逻辑由云端 CI）

- `PreAnchorBudgetTest.kt`：`null→40_000`；`32_000→8_000`；`64_000→12_800`；`128_000→25_600`；`200_000→40_000`（饱和）；`1_000_000→40_000`；断言单调不减 + `≤0.2w` + `≤40k` + 与 postAnchor 60k 之和 < 0.8·w。
- `ToolTitleResolverTest.kt`：`file_read` 有/无 `tool_title`；`read_image` 无 `tool_title`；`shell_execute` 有 `tool_title`；流式 partial JSON；脏 JSON；未知工具；已有友好标题保留；历史裸名 block 的渲染兜底。
- Python 门禁自检：把 `40_000` 改回 `15_000` / 把 `resolveToolTitle` 从任一赋值点移除 → 门禁必须 FAIL（负向验证）。

## 验证边界（会话规矩）

- 本地**不编译**：不跑 Gradle/Kotlin 编译、不跑 Robolectric；只跑 `python3 -m py_compile` + 全部 python 门禁 + 门禁负向自检。
- Kotlin 类型/导入/调用面逐条查证：同包 `internal` 调用免 import；`org.json.JSONObject`、`coerceAtLeast/AtMost` 均为既有用法；纯函数无 Android 依赖，JVM 测试可编译。
- 编译与 JVM 单测交由云端 CI（PR Checks / Compile / Unit tests / L10n gate 全绿）。
- 合并后验证增量非零（`main` HEAD 前移且 diff 含两处修复），删除分支，拉取最新源码落地。

## 标准流程

分支 `fix/preanchor-budget-and-tool-title` → 提交 → push（`api.github.com` REST 通道，因 `github.com:443` 直连超时）→ PR → 等 CI 绿 → squash 合并 → 验证增量非零 → 删分支 → 拉最新 → report + memory → 完结 goal。
