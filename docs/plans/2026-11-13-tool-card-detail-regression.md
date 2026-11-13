# 工具卡片「详情」丢失回归修复（2.0.90 → #334）

日期：2026-11-13
分支：`fix/tool-card-detail-regression`
基线：`2.0.90-linux` = `68fab74a`；回归提交 = `c56c2ccc` (#334)

## 1. 现象

- 2.0.90 正式版：`file_read` / `grep` / `glob` 工具卡片折叠行显示模型给的中文语义详情（"查看现有源码和参考项目目录"、"读 magiclight 技能避坑说明"）。
- 当前 `.334`：同一位置显示半截路径 `Reading /var`、`Reading /var/min`、`Reading /var/minis/work`，语义详情消失。折叠行只有一行主标题，没有第二行参数副行。

## 2. 证据链

- 会话库按天统计（`_vcard.db`，`file_read`/`grep`/`glob` 卡片）：
  - 11-07（2.0.90 发版）：`file_read` `81/81` 携带 `tool_title`，派生描述 `0` 条。
  - 11-08 起携带率断崖 `36/113`，11-13 为 `8/66`，12 条固化成 `Reading /var/min…` 半截路径。
  - 中文详情卡片的 `description` == `input.tool_title`（OLD 记录实证）；路径卡片的完整 `input` 里 `0` 条带 `tool_title`，且路径是流式残缺值（`/var/min` 而非完整 `/var/minis/skills/.../SKILL.md`）。
- 2.0.90 与当前三工具 `tool_title` schema **逐字相同**：`git diff 2.0.90-linux HEAD -- FileReadTool.kt GrepTool.kt GlobTool.kt` 为空，`required = [tool_title, path|pattern]`、`propertyOrdering` 首位。
- 2.0.90 **无** `ChatToolTitleResolver.kt`；`git log -S 'Reading $it' 2.0.90-linux..HEAD` 精确命中 `c56c2ccc` —— 参数派生标题是 #334 引入。
- 2.0.90 的 `executeTool`：`extractToolTitle(argsJson, fallback = canonical)`，`toolTitle` 只装模型 `tool_title`，不派生、不固化流式残影。
- 11-08/11-09 的 `Grep` / `Read File` / `input={}` 是 #303 类"参数未落地"历史旁路，与 #334 的半截路径不是同一条链，已排除。
- 仓库侧 system prompt 与 schema 在两版间无 `tool_title` 契约差异；模型是否传 `tool_title` 属外部波动，App 不该让"详情"依赖模型自觉。

## 3. 根因

#334 把三种语义压进同一个【持久化】字段 `toolTitle`：

1. 模型权威 `tool_title`（语义详情）
2. 流式临时预览
3. 参数派生摘要

且：

- `deriveToolTitle` 用 `extractPartialJsonString` 从【残缺 JSON】抠 `path`，`{"path":"/va` → `Reading /va`（半截路径）；
- `resolveToolTitle` step2 无差别信任任何"看起来具体"的 prior，把流式残影【焊死】，`ToolCallComplete` 时完整 args 的重派生永不执行；
- 残影经 `buildTurnParts` 写入 `description` 持久化；
- `displayToolTitle` 复用同一阶梯，历史坏卡片无法自愈。

2.0.90 的"正常"依赖模型稳定传 `tool_title`（当天 81/81）。传不传本就波动，App 把"详情"外包给模型自觉是脆弱面；#334 把"没传时的兜底"从体面友好名恶化成误导性半截路径，才是显示崩坏的直接原因。

## 4. 修复（职责分离 + 幂等结算，普适、非补丁）

只改 `ChatToolTitleResolver.kt` 内部阶梯，签名与全部 16 个调用点不变：

- **COMPLETE args**（`JSONObject` 解析成功）= 唯一结算点：一律从 args 重算，**忽略 `existingTitle`** → 历史坏卡片自动自愈；
- 模型 `tool_title`（完整 args 中 / 持久化 `input` 中）最高优先 → 中文详情稳定显示；
- **禁止从残缺/流式 JSON 派生路径** → `Reading /var/min` 从源头消失；
- 残缺阶段只给 `tool_title` 预览或 friendly 名（体面临时态），完整 args 到达即被覆盖，绝不固化；
- 保留 #331/#332 成果：裸 snake_case 名永不进 UI，Title-Case 兜底。

`deriveToolTitle` 改为消费**已解析的 `JSONObject`**（删掉 partial 扫描），单一来源不变。

## 5. 防回退

- 扩展 `scripts/ci/check_tool_title_and_budget_contract.py` 新增 C 段：结构门禁（resolver 不含 `extractPartialJsonString` / 不含 `return prior` / 含 `deriveToolTitle(canonical, json)` / 含完整 JSON 解析）+ Python 行为 replica（5 个关键场景）+ 回归单测存在性断言。
- 新增/重写 Kotlin 单测（云端 CI 跑 Unit）：`streamingHalfPathNeverBecomesATitle`、`completeArgsOverwriteStaleHalfPath`、`historyHalfPathShadowSelfHeals`、`partialToolTitleIsPreviewOnly`、`modelTitleOnCompleteArgsBeatsStalePrior`；重写旧错误契约 `streamingPartialArgsStillProduceATitle` / `existingSpecificTitleIsKept`。

## 6. 验证策略（遵守会话规矩）

- 本地：只跑 Python 全门禁（含新增 C 段 replica），**不本地编译**。
- 云端：CI 跑 Compile + Unit（Kotlin 单测）+ 全部静态门禁。
- Red/Green：先落 C 段门禁（旧源码 RED）→ 再改 resolver + 单测（GREEN）。

## 7. 流程

分支 → 提交 → push → PR → 等 CI 绿 → squash 合并 → 验证增量非零 → 删分支 → 拉最新 main。

## 8. 运行期补充观察与验证（2026-11-13，本轮）

用户最新原话（逐字）：

> 运行过程中，我发现除了shell工具卡片和思考卡片正常显示详情。其他卡片都存在问题

### 分叉线判定

- `shell_execute` 的 schema 带 `tool_title` 且模型稳定传：`resolveToolTitle` step1 命中，直接返回中文语义标题。
- 思考卡片不是工具块，走独立渲染路径，与 `AssistantBlock.toolTitle` 无关。
- 其余每一个工具块的标题汇到同一个 `ChatToolTitleResolver.resolveToolTitle`，四个位置同源：
  - 折叠行 `ChatProcessCards.kt:251` → `displayToolTitle(block.toolName, block.toolArgs, block.toolTitle)`
  - 详情面板 `ChatToolDetailUI.kt:1064` → `displayToolTitle(...)`
  - 状态栏 `ChatViewModelToolTurnExec.kt:520` → `resolveToolTitle(call.name, call.argsStr)`
  - 持久化 `ChatModels.kt buildTurnParts` → `resolveToolTitle(toolName, toolArgs, toolTitle)`
- `deriveToolTitle` 的 `when` 覆盖 20+ 工具（file_read/file_write/file_edit/read_image/list_dir/glob/grep/browser_use/web_search/web_fetch/memory_*/search_sessions/read_session/spawn_agent/agent_plan/ask_user_question/cronjob/ui_action/file_checkpoint/multi_edit/shell 系），集体中招。
- 2.0.90 详情面板标题行 `git show 2.0.90-linux:...ChatToolDetailUI.kt | grep` = `1061: text = block.toolTitle.ifEmpty { block.toolName },`；内容区 `when(block.toolName)` 七个分支两版逐行对齐（368/492/672/697/880/905 对 368/498/678/703/886/911），内容区无回归。
- 结论：坏点是唯一标题结算点，改一处即普适覆盖全部工具；详情面板正文区保持原样。

### 契约落地验证

- `Tool call style:` 契约行落在 `val base = identitySection + """`（`ChatViewModelPromptExt.kt:165` 起、`:274` 收尾）字符串常量内部，会随 systemPrompt 发给模型；225 行之前未闭合 `/*` 计数 1 属于 KDoc 扫描的既有形态，`check_cjk_literals.py` 判定该文件 hits=1、cjk-baseline.json 允许 1，门禁绿。
- 契约内容：每次调用在 schema 暴露 `tool_title` 时必填 5-10 词用户语言摘要；禁止把裸工具名或截断路径当 `tool_title`；schema 无该参数时由 App 用完整参数派生可读标签。

### 本地验证结果（只跑 Python，未本地编译）

| 门禁 | 结果 |
|---|---|
| check_schema_drift.py | OK |
| check_cjk_literals.py | OK |
| check_string_refs.py | OK |
| check_architecture_policy.py | OK |
| check_tool_execution_contract.py | OK |
| check_tool_title_and_budget_contract.py | OK（RED→GREEN 已留证） |
| check_announced_tools.py | OK |

Kotlin 静态自检：`()` 93/93、`{}` 37/37、`[]` 0/0 平衡；`deriveToolTitle(toolName: String, json: org.json.JSONObject)` 签名在位；`extractPartialJsonString(` 已从 resolver 移除；16 个调用点参数个数全兼容；`ToolAliases.MAP` 覆盖 `describe_image`/`find_files`/`ls`/`shell`/`grep_source`，alias 单测不破。

### 语义闭环（逐调用点）

- `ChatViewModelAgentLoopExt.kt:811` delta 段：残缺 JSON 走 step3，只出模型 `tool_title` 预览或友好名，`Reading /va` 不再写进 `allToolBlocks`。
- `ChatViewModelAgentLoopExt.kt:872` ToolCallComplete：完整 args 走 step2 无条件重算覆盖 prior。
- `ChatViewModelToolTurnExec.kt:396` finalizePlannedCall、`ChatViewModelExecuteToolExt.kt:110` executeTool、`:520` 状态栏、渲染 `displayToolTitle`：共用同一阶梯，持久化半截路径残影在渲染时自愈。
- 11-13 会话库 12 条 `description='Reading /var/min…'` 样本的 `input` 均为完整路径，自愈路径有真实数据支撑。


## 9. v337：#336 没修好的根因与彻底修复（2026-11-13 本轮）

### 为什么 #336 之后爸爸仍然看到「只显示路径」

#336 把「半截路径」修成「完整路径」，但**三个语义仍压在同一个持久化字段 `toolTitle`**：

1. 模型权威 `tool_title`（中文语义详情，如「查看现有源码目录」）
2. 流式临时预览
3. 参数派生摘要（`Reading /var/minis/workspace/facts.txt`、`Grep <pattern>`、`Glob <pattern>`）

真机证据（2.0.90 dex 含 #336 字面量；`minis.db` 的 `parts_json` 同屏两条 `file_read`）：
- 未传 `tool_title`：`description="Reading /var/minis/workspace/facts.txt"` —— 卡片整行就是路径；
- 传了 `tool_title`：`description="看IAM壳页完整内容"` —— 中文详情正常。

`resolveToolTitle` 在模型漏传 `tool_title` 时用 `deriveToolTitle` 的**参数值**顶替主标题，于是「详情」是否存在取决于模型自觉。#336 只改了兜底文案的完整性，没拆字段，所以回归照旧。`shell_execute` 正常仅因模型稳定传 `tool_title`；思考卡片走独立渲染路径。

上一轮把 `required = listOf("tool_title","path")` 与运行时 `tool_title?:` 的差异误判为「required 在导出链丢失」——已排除：`toResponsesAPIJson()` 里 `put("required", JSONArray(required))` 在位，紧凑工具清单的 `?:` 是另一套文本渲染，不是 function schema。误诊当场作废。

### 修复：语义 / 参数单一结算、职责分离（普适，非补丁）

- `ChatToolTitleResolver.kt`：`ToolCardPresentation(title, detail)` + `presentToolCard()` 成为唯一结算点。`title` 只可能是模型语义标题、真实语义 prior 或友好工具名（`Read File` / `Grep` / `Glob`），**参数值永不进入主标题**；`detail` 是独立且完整的参数摘要（path / pattern / command / query …），只从 COMPLETE `JSONObject` 结算，残缺流式 JSON 不伪造。`resolveToolTitle` / `displayToolTitle` 保持 16 个调用点签名兼容，统一委托到新结算点。
- 历史残影自愈：`isDerivedShadow()` 按**数据形态**甄别（派生动词 + 机器值：路径 / glob / 命令 / 单 token），不靠前缀黑名单猜语义；模型自己写的「Plan the fix」「Search 相关文档」以及 CJK 标题保持权威，#334/#336 存下的 `Reading /var/min` 之类则退回友好标题、参数回到副行。
- `ToolCardLabel.kt`（新）：Apple 风格两行（13sp Medium 语义主标题 / 11sp onSurfaceVariant 完整参数），可选 eyebrow 承载 `toolTitleLabel` 工具类型行；折叠行 `ChatProcessCards` 与详情面板 `ChatToolDetailUI` 共用同一组件，详情面板净减行、architecture ceiling 不升。紧凑单行（消息行 / 芯片 / flat items / 状态栏）保持纯语义标题，完整参数由展开卡片承载——合成行流回持久化会自我污染，故不采用。
- 单测：`ToolCardPresentationTest.kt`（TDD RED 先行）覆盖三工具无 `tool_title`、模型标题、历史残影、流式残缺、别名、长参数截断；`ToolTitleResolverTest.kt` 全量改写旧断言（旧版把「路径当标题」这个病态行为钉成正确期望）。
- 门禁：`check_tool_title_and_budget_contract.py` 新增 C 节 v337 契约（Python 行为镜像 + 结构 ratchet）与 D 节结构检查，B 节渲染契约同步到新单一结算；`architecture-policy.json` ceiling 只降不升。

### 本地验证（不编译项目，编译交云端 CI）

| 验证 | 结果 |
|---|---|
| TDD RED | 门禁 D 节 5 项全报缺失，exit=1，实现未写 |
| 全量 8 个 CI 门禁（pr-check.yml 原样） | 8/8 OK，`TOTAL_FAIL=0` |
| v337 行为对照器（Kotlin 阶梯的忠实 Python 复刻） | 52/52 断言通过 |
| Kotlin 静态符号自检（剥注释后） | 零悬空符号，无 `deriveToolTitle` / `extractPartialJsonString` 残留 |
| 真机历史库复核 | 0 条以派生前缀开头的 `description`，残影甄别无数据冲突 |
| CJK ratchet / architecture ratchet | OK；`ChatToolDetailUI.kt` 1480 → 1469 行，ceiling 同步下调 |

JVM 单测实际执行与 Kotlin 编译由云端 CI 验证；本地按项目约定只用 Python。
