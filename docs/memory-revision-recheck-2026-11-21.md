# 复核报告 · 《OpenMinis 复审报告合集 2026-11-21》属实性与处置（对照 main `abf9e994`）

- **性质**: 只读复核 + 实证复现；**未执行任何修复**（修复方案见 §4，等待确认后实施）
- **复核对象**: 《OpenMinis 复审报告合集 · 2026-11-21》（Part A：#407 记忆 revision 链路复审；Part B：外部《长程 Agent 跑偏四层机制》核验）
- **关键差异**: 原报告对照基线是 `0ca33e38`（**#408 合并之前**的 main）；本复核对照**当前 main `abf9e994`**（#408 已合并，含 69 项修复）。凡"原报告说未修"的条目，必须先过一遍"是否已被 #408 顺带修掉"。
- **实证方法**: 逐条 file:line 取证 + JVM 临时测试实机复现（跑完即删，未留仓库）+ 全仓同类模式扫描。

---

## 1. 逐条复核结论（Part A · 7 条）

| 条目 | 原报告判定 | 对照 `abf9e994` 复核结论 | 证据 |
|---|---|---|---|
| **R1** (P1·部署) | APK 装机 09:30 早于 #407 合并 10:03，运行态将复现假冲突 | **属实（按原始证据采信）**。属发布动作，与代码无关。本机无 adb/设备，装机态无法复验（如实注明）。注意：#408 已修 F9（写返回格式），装新 APK 后"写返回裸哈希→回传→跨午夜误判"整条症状链消失 | 原报告 adb 实测 |
| **F1** (P2·空哈希绕过) | `revision="2026-11-21:"` → 空 hash → `startsWith("")` 恒真 → 并发校验整体失效 | **属实，当前 main 仍在，且已 JVM 实证**：临时测试 `refusal("2026-11-21:", body)` 返回 `null`（放行），断言"必须拒绝"失败即复现。**复核加宽**：洞不止空哈希——`startsWith` 是前缀匹配，1~15 字符前缀全部生效（猜中 1 字符 hex 前缀概率 1/16）；多冒号形态 `2026-11-21:abc:` 的 hash 段 `abc:` 同样前缀匹配 | `MemoryWriteRevisionGuard.kt:53`、`MemoryRevision.kt:54-61`；本机 JVM 复现 |
| **F9** (P2·格式半落地) | 写返回裸哈希 vs get 带前缀，两种格式并存 | **已被 #408 修复**。`MemoryRepository.kt:324` 现为 `MemoryRevision.token(fileName.removeSuffix(".md"), newContent)`（带日期前缀）；`currentDailyRevision()`（:351）同为 token；memory_get 文案（MemoryTools.kt:140）声明带前缀——写/读两侧已一致 | `MemoryRepository.kt:324/351` |
| **F2** (P3·死代码) | guard 的 `existingContent.isEmpty()` 分支不可达 | **属实**。调用点传 `dayFileExists = existing.isNotEmpty()`（`MemoryRepository.kt:289`）：内容空必先路由 NoLogToday 提前 return，49-52 行不可达 | `MemoryWriteRevisionGuard.kt:49-52` |
| **F3** (P3·无前置校验) | revision 参数仅 `isNotBlank`，超长串直通 | **属实**。`MemoryTools.kt:52` `takeIf { it.isNotBlank() }`，无长度/格式校验 | `MemoryTools.kt:52` |
| **F4** (P3·拒绝零日志) | 三类拒绝路径直接返回，无日志 | **属实**。`writeEntry` 拒绝分支（:286-292）直接 `return@write WriteOutcome(it)`；仅成功（:325 Log.i）与异常（:331 Log.e）有日志 | `MemoryRepository.kt:286-292` |
| **F8** (P3·测试缺口) | 12 用例缺空哈希/多冒号/仅 BOM/写读一致性 | **属实**。`MemoryRevisionTest.kt`（12 用例）无空哈希、短前缀、多冒号、写返回与 get 一致性场景；现有两个 guard 用例恰好都传完整 16 位 hash，绕过了 startsWith 的退化形态 | `MemoryRevisionTest.kt` |

**统计**: 7 条全部属实（0 误报）；**1 条已被 #408 顺带修复（F9）**；**1 条经实证加宽（F1：空前缀 → 任意短前缀）**；R1 属部署项。

---

## 2. Part B 复核结论

抽查全部关键证据点，**原报告判定全部成立，无需修正**：

| 证据点 | 复核 | 结果 |
|---|---|---|
| 压缩三上限 6 次 / 8k–32k / 深度 3 | `ChatViewModel.kt:129/131/132/145` | ✅ 一字不差 |
| 证据老化 160/160 字头尾 | `agent/ToolResultAging.kt:19-20` | ✅ 一字不差（原报告证据索引漏写 `agent/` 一级路径，仅笔误） |
| session_goals 表、无验收标准列 | `AppMigrations.kt:303`；全文件无 `acceptance_criteria` | ✅ |
| `<goal_context>` 每轮注入 | `goal/GoalContextInjection.kt`（原报告漏写 `goal/` 一级路径） | ✅ |
| 完成判定硬校验（board 未收口拒判完成） | `ChatViewModelAgentLoopExt.kt:1362-1372` [T-plan-close-nudge] | ✅ |
| 过程级强制自检不存在 | 全仓 grep 无 `stepCount %`/milestone 对账/self-check 循环 | ✅ 仍成立 |

Part B 的"两条仍然成立的真命题"（过程级对账缺位、验收标准未结构化）属**产品功能演进方向**，不在 #408 缺陷审查范围（缺陷审查管"写错的东西"，不管"没写的东西"）——不算对抗审查遗漏，详见 §3 边界说明。

---

## 3. 对抗审查为什么遗漏了 F1（诚实归因）

#408 三轮审查**看到了这条链路**（F9 就是在这条链上修的），却漏掉了同一守卫里的 F1。归因三层：

1. **修复者视角锚定**。第一轮在 Memory 链路上的动线是"发现 F9（格式不一致）→ 修 F9"。修复注意力被已发现的缺陷锚定，没有对**同一条链上相邻的守卫**（`MemoryWriteRevisionGuard`）重新做"从零开始的对抗输入枚举"。三轮循环的复审对象是"修复的 diff"，不是"被修链路的全部防御面"——守卫函数本身没被改动，就不进复审视野。
2. **20 维度清单缺"防御谓词退化输入"维度**。清单有"参数合法性前置校验"（F3 所在维度），但它检查"有没有校验"，不检查"校验谓词本身能否被退化值穿透"。`startsWith(prefix)` 对空前缀恒真、对短前缀弱化，是 Kotlin/Java 语义陷阱——这类"谓词逻辑"问题在清单里无归属，扫描代理不会主动构造 `""` / 1 字符 / 多冒号作为输入。
3. **P3 的取舍是设计而非遗漏**（F2/F3/F4/F8）：三轮终止条件是"无 P1/P2 未修"，P3 只记录。但 **F1 是 P2，属真遗漏**——第 1、2 条根因共同作用的结果。

**已落地的改进**（防再犯，已写入 #408 报告附录与记忆）：今后每轮修复完成后，对"被修链路"追加一次**退化输入遍历**（空串/超长/分隔符畸形/边界长度），作为第四步固定动作。

---

## 4. 同类型问题全仓扫描结论

扫描模式："解析产物做 startsWith/endsWith/contains/matches 决定放行或拒绝" + "分隔符解析产物可能为空进安全谓词" + "空正则"：

- `MemoryWriteRevisionGuard.kt:53` —— **全仓唯一实例**（即 F1）。
- 其余全部 `startsWith` 均为字面量常量前缀（`builtin:`、`127.`、`{"ok":true` 等），模式健康。
- `DebugServer.kt:245`（accept/origin 解析）：空串结果向"拒绝/不启用"方向倒——fail-safe，无危害。
- `FreeLaneHealth.kt:282`：仅测试辅助方法。
- 无 `startsWith("")`/`contains("")`/空正则字面量。

**新增审查维度建议**（补进 20 维度清单，作为第 21 条）：**防御谓词的退化输入**——凡以解析产物决定放行/拒绝，必须以空串、超长串、分隔符畸形、边界长度四种输入验证谓词方向。

---

## 5. 最优修复方案（F1 为核心；兼顾用户体验 / 模型运行 / app 流畅）

原则：一次闭环修掉 F1+F3+F4+F2+F8 五条（同链路同主题），不动 UI、不加轮次、不加 IO。

### 5.1 F1（根因修复，`MemoryRevision.kt` + `MemoryWriteRevisionGuard.kt`）

1. **parse 层格式收紧**（洞的源头）：
   - 带前缀形态：day 段必须匹配 `^\d{4}-\d{2}-\d{2}$`，hash 段必须恰为 **16 个 hex 字符**；两者任一不满足 → 新增 `Rejection.Malformed`。
   - 多冒号自然被上述正则拒绝（`2026-11-21:abc:` 的 hash 段含 `:` 非 hex）。
   - legacy 裸哈希：同样要求 16 hex（#407 之前铸造的本来就是 16 位 sha256 截断，真实历史 token 全部兼容通过；乱造的短串被拒）。
2. **比对改完整相等**：`hashOf(existing).startsWith(hash)` → `hashOf(existing) == hash`（parse 层已保证 16 位）。空前缀/短前缀整个洞族一次性消除，且语义与 memory_get 文案"原样传入"的契约一致。
3. **拒绝文案可操作**（用户体验）：Malformed → "revision 格式不正确（应为 YYYY-MM-DD:hash16）。请把 memory_get 返回的 revision 原样传入，或省略该参数。"——模型拿到一次明确错误即可自纠（模型运行体验），不会陷入重试环。

### 5.2 F3（前置校验，`MemoryTools.kt:52`）

`isNotBlank` → 格式预检（token 正则 or 16-hex）。非法直接工具级错误返回——**省一次磁盘 IO 与文件锁**（app 流畅），错误信息更早、更贴近参数来源。

### 5.3 F4（拒绝日志，`MemoryRepository.kt`）

三类拒绝分支各加一条 `Log.w`（含拒绝类型与文件名，不含正文）。memory_write 频率低，日志量可忽略；事后可审计（可观测性），不触碰主线程。

### 5.4 F2（死代码，`MemoryWriteRevisionGuard.kt`）

删除 `existingContent.isEmpty()` 死分支，KDoc 写明调用点契约（`dayFileExists ≡ existing.isNotEmpty()`）。行为零变化。

### 5.5 F8（测试，`MemoryRevisionTest.kt`）

补 6 类用例：空哈希拒绝、1 字符前缀拒绝、多冒号拒绝、超长/非 hex 拒绝、legacy 16-hex 兼容通过、写返回 token 与 `currentDailyRevision()` 往返一致。Malformed 文案断言可操作性（含"省略 revision"指引）。

### 5.6 三体验核对

| 维度 | 影响 |
|---|---|
| 用户体验 | 拒绝文案保持可操作；错误更早暴露（格式错一次自纠，而不是静默覆盖后丢数据） |
| 模型运行 | 工具级错误一次返回即自纠；无新增轮次；正确用法（原样回传 token）零变化 |
| app 流畅 | 纯函数校验 + 每拒绝一条 Log.w；无新增 IO/锁/线程 |

### 5.7 R1（部署项）

重建 Release APK 并安装（发布动作）。装上含 #408 的包后，F9 已修 + F1 修复落地的链路整体生效，跨午夜假冲突与并发静默覆盖两个事故形态全部消失。

---

## 6. 处置状态

- 本复核：只读 + 实证，未改任何生产代码（临时验证测试已删除）。
- 修复方案待确认后按标准流程实施（分支 → PR → CI → squash）。
- 原报告 Part A 的 F9 由 #408 已修这一事实，应在原报告归档时标注，避免后续轮次按"未修复"重复开工。
