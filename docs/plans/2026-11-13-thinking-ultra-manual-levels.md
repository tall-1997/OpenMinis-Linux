# PLAN — ULTRA 思考等级全链路补齐 + 模型参数页手动思考等级控制 + 自定义模型参数补齐修复

分支：`feat/thinking-ultra-manual-levels`（基于 origin/main 609c16ce）

## 背景与根因

### 现状（侦察结论，全部有源码证据）

1. **ULTRA 等级半成品**：
   - `ThinkingLevel` 枚举已有 `ULTRA`（rank 最高），`ConfigBuiltins` 的 `session.thinkingLevel` schema 已含 `ultra`。
   - 但 `ThinkingLevelCatalog.selectableThinkingLevels`（映射表）与 `catalogMaxThinkingLevel` 的声明集路径**没有 ultra 档**——声明集里出现 `"ultra"` 时映射不到任何枚举值。
   - `OpenAIRequestBodies.mapThinkingLevelToResponsesEffort`：`MAX, ULTRA -> "max"`（ULTRA 在 wire 上折叠到 max，客户端侧概念）。
   - `AnthropicProvider.thinkingEffort`：`XHIGH, MAX, ULTRA -> "max"`。
   - `ChatViewModel.currentModelMaxThinkingLevel` 兜底 `ThinkingLevel.XHIGH`（未知模型默认天花板偏低，与 UNKNOWN_REASONING_EFFORT 含 "max" 不一致）。
   - `applyUnrecognizedModelDefaults` 的 `UNKNOWN_REASONING_EFFORT = ["low","medium","high","xhigh","max"]` —— **没有 "ultra"**，未知模型永远选不到 ULTRA。

2. **模型参数页（ModelEntryDetailScreen）没有思考等级控制**：
   - 只有 `modeldetail_thinking` 开关（supportsReasoning override）。
   - `ModelOverrides.maxThinkingLevel` 字段存在（core/model 已定义），UI 从未写入——用户无法手动增删某个模型可用的思考档位。

3. **自定义模型参数补齐缺陷**：
   - `CustomModelEntryFactory.buildCustomModelEntry` 走 `applyUnrecognizedModelDefaults`，会把空声明集替换成 UNKNOWN_REASONING_EFFORT（5 档无 ultra）。
   - `ModelEntry.model` 折叠（ProviderConfig.kt）应用 overrides 时**漏了 `maxThinkingLevel`**——`baseModel.copy(...)` 只折叠 displayName/maxOutputTokens/contextWindow/supportsReasoning/inputModalities/outputModalities 六项，`overrides.maxThinkingLevel` 根本没进折叠链，`forcedThinkingCeiling` 读了也白读。

### 根因

- ULTRA 是后来加的客户端档位（MAX + 编排），catalog 映射表、未知默认集、天花板兜底三处没有同步补齐。
- `ModelEntry.model` 折叠链写于 maxThinkingLevel override 字段引入之前，折叠白名单漏加。
- 参数页 UI 从未给 maxThinkingLevel 提供编辑入口。

## 改动清单

### A. ULTRA 等级补齐（catalog 映射 + clamp 阶梯 + wire 发射）

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/openminis/app/provider/ThinkingLevelCatalog.kt` | ① 声明集→枚举映射表加 `"ultra" → ThinkingLevel.ULTRA`；② `selectableThinkingLevels` 声明集路径在含 ultra 时把 ULTRA 加进可选档；③ `catalogMaxThinkingLevel` 声明集路径天花板可达 ULTRA |
| `core/model/src/main/kotlin/com/openminis/app/data/model/ModelContextWindowHeuristic.kt` | `UNKNOWN_REASONING_EFFORT` 加 `"ultra"`（未知模型默认 6 档，与 session schema 对齐） |
| `app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt` | `currentModelMaxThinkingLevel` 未知模型兜底 XHIGH → ULTRA（与 UNKNOWN_REASONING_EFFORT 一致；clamp 逻辑保证 wire 安全） |

wire 发射（已正确，仅核对不改）：
- OpenAI Responses：`MAX, ULTRA -> "max"` ✅（endpoint 不收 "ultra" 字面量）
- Anthropic：`XHIGH, MAX, ULTRA -> "max"` ✅
- `clampEffort`（ThinkingEffort.kt）：声明集不含 ultra 时 ULTRA 被夹到声明集最高档 ✅（需补 ultra 进 ladder 常量核对）

### B. 模型参数页手动思考等级控制（Apple 风格 UI）

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/openminis/app/ui/settings/ModelEntryDetailScreen.kt` | Thinking 区块新增"思考等级上限"chips 组：关闭/低/中/高/超高/极致(MAX)/究极(ULTRA)，未选=跟随目录默认。写 `overrides.maxThinkingLevel`。Apple 设计语言：SF 风格 chips、hairline 分隔、Action Blue 选中态、无多余阴影 |
| `app/src/main/res/values/strings.xml` + `values-zh` | 新增 `modeldetail_thinking_level_ceiling` 等字符串 |

### C. 自定义模型参数补齐修复（根因修复）

侦察修正：`ModelEntry.model` 折叠链**没有**漏 `maxThinkingLevel`——`LLMModel` 根本没有该字段，`ModelEntry.effectiveMaxThinkingLevel` 直接读 `overrides.maxThinkingLevel`（一手来源），设计正确。真正的缺陷是：

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/openminis/app/provider/ThinkingLevelCatalog.kt` | `catalogMaxThinkingLevel` 声明集路径：声明集含 `"ultra"` 时 ceiling 升到 ULTRA（当前映射表静默丢弃 ultra 档，模型明明支持却选不到） |
| `app/src/main/java/com/openminis/app/ui/settings/ModelEntryDetailScreen.kt` | 自定义/未知模型（UNKNOWN 5 档集，不含 ultra）通过参数页手动设 ceiling=ULTRA——用户自己的断言覆盖目录默认，wire 上 ULTRA→"max" 安全 |

`UNKNOWN_REASONING_EFFORT` **故意不加** "ultra"：它是"目录没听说过这模型"的猜测标记，声称未知后端接受 ultra 字面量会 400。ULTRA 只来自两个明确断言：后端声明集含 ultra、用户手动设置。

### D. 测试

| 文件 | 改动 |
|---|---|
| `app/src/test/java/com/openminis/app/provider/thinking/ThinkingLevelSelectionTest.kt` | 补：声明集含 ultra 时 selectable 含 ULTRA、ceiling=ULTRA；UNKNOWN 集含 ultra |
| `app/src/test/java/com/openminis/app/provider/ThinkingRulesRegressionTest.kt` | 补：overrides.maxThinkingLevel 折叠进 model；clampEffort ultra 路径 |
| 新增 `ModelEntryFoldTest`（core/model 单测） | 折叠链 maxThinkingLevel 生效断言 |

## 验证

- 本地不编译（约定）：逻辑用 python 等价脚本验证映射/clamp/折叠三链路。
- 单测代码补齐，交云端 CI 编译跑。
- 流程：分支 → 提交 → push → PR → CI 绿 → squash 合并 → 验证增量非零 → 删分支。

## 风险与对策

- **ULTRA 上 wire 的 400 风险**：clampEffort 已把 ULTRA 夹到声明集最高档；声明集显式含 "ultra" 的模型（如未来 gpt-6.x ultra 档）才直发 "ultra"。OpenAI Responses 映射保持 `ULTRA -> "max"` 不变，双保险。
- **旧持久化兼容**：maxThinkingLevel 是可空新字段，旧 JSON 解码为 null，行为不变。
