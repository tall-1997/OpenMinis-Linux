# 2026-11-23 会话规则权威性与碎片化来源清理

## 问题

用户报告：「这么多轮了，为什么都没有按我设置的会话级规则+全局规则执行？」

## 已排除（实测取证）

| 假设 | 结论 |
|---|---|
| 规则文件缺失 | ✗ 会话 1174 B / 全局 1843 B 都在 |
| 被 MemoryPromptBudget 裁掉 | ✗ 占 24000 预算 12.6% |
| 被 memoryOn 关掉 | ✗ memoryOn 只 gate 日记忆（MemoryDeliveryExt:118），standing 无条件传入 |
| 被 InstructionDedupe 吞掉 | ✗ 内容不同（InstructionDedupe:35） |
| 没进 prompt | ✗ 进了 PromptSections 第 4 段 memory_standing |

## 根因

**A · 位置劣势**：规则在第 4 段，其后还有世界书/learned/日记忆/任务锚/运行时上下文/**人格提醒（最末段）**。注意力越靠后越强，人格压过规则。

**B · 只有存在声明，无约束声明**：`rulesLine` 生成清单式告知，模型可读作背景信息。

**C · `MAX_CHARS=900` 切尾**：`block.take(maxChars)` 从尾部硬切，先切掉规则提醒与省略告警，留下人格 → 规则静默消失。

**D · 无近场重贴**：规则只在开头出现一次，多轮后注意力衰减。

**E · 祈使句诱发碎片（与 #434 同构）**：`PromptAnchors` 4 处命令模型去「re-read / ask the user」，模型照做 → 中途插话。

## 改动清单

### 文件

- `ui/chat/PromptAnchors.kt` — 规则提醒措辞、MAX_CHARS 分账、删 4 处祈使句
- `ui/chat/PromptSections.kt` — memory_standing 段加约束头
- `ui/chat/ChatViewModelPromptExt.kt` — 动态尾部插入规则锚（人格之前）
- `data/repository/MemoryPromptBudget.kt` — session-global 加「本会话优先」标注

### 任务

1. **T1 去祈使**：`rulesLine` 两段文案改纯状态声明；删 `:33` 的 `re-read it if a long tool loop made you lose track`。测试：断言 rulesLine 输出不含 `re-read` / `Do not assume` / `ask the user`。
2. **T2 分账**：`StandingContract.block` 按 2/5 + 3/5 分配给 persona / rules；省略告警永不截断；MAX_CHARS 900→1200。测试：长人格 + 长规则 + 告警，断言三者都在。
3. **T3 约束头**：`PromptSections` 给 memory_standing 前置 `STANDING RULES` 头（纯声明，无祈使）。测试：PromptSectionsGoldenTest 更新 + 断言头存在。
4. **T4 规则锚**：动态尾部在 personalityReminder 前插入状态声明锚。测试：断言 锚索引 < 人格索引 且 > 规则段索引；五块内容全在。
5. **T5 会话优先标注**：MemoryPromptBudget 的 session-global 前加「govern THIS chat」声明。测试：断言标注存在且不含祈使。

## 验证

- `PromptSectionsGoldenTest` 通过（顺序不变）
- 新增 `StandingRulesPrecedenceTest`：五块齐全 + 索引关系 + 无祈使句
- `MemoryPromptBudgetTest` 不变
- 四门禁全绿

## 约束

- **零删除**：世界书 / learned / 日记忆 / 任务锚 / 运行时上下文 / 人格提醒全部保留
- **零重排**：PromptSections 段顺序不变
- 总增量约 340 字符（预算 1.4%）
- 全部新增文案必须是**状态声明**，不得出现命令模型的祈使句
