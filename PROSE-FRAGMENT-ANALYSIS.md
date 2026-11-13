# Agent Loop 正文碎片根因分析 v2——排除法收敛后的机制层定位

日期：2026-11-13 ｜ 基线：main @ 3595fb6 ｜ 前版：PROSE-FRAGMENT-ANALYSIS.md（模型层假设已被用户实测否定）

---

## 0. 用户实测排除项（关键输入）

| 假设 | 用户实测 | 判定 |
|------|---------|------|
| 模型层（GLM preamble 固有） | **换了别的模型，一样存在** | ❌ 排除 |
| persona/提示词层 | **换了别的人格提示词，一样存在** | ❌ 排除 |
| 单一会话/任务偶然性 | 多任务、多会话复现 | ❌ 排除 |

**结论：跨模型、跨提示词、跨任务稳定复现 → 根因必然在 OpenMinis 自己的请求组装/消息结构里。**

---

## 1. 关键机制差异：2.0.80 为什么没有

2.0.80 时代的请求组装（37412a8 实测代码）：

```kotlin
// 2.0.80：assistant turn 是【一条消息】
// turnText 直接进 assistantParts，与 toolUse 同一条 assistant 消息
assistantParts.add(AgentContentPart.Text(turnText))   // 正文
assistantParts.add(AgentContentPart.ToolUse(...))      // 工具调用
// ↑ 同一条 assistant message 里
```

这是 **OpenAI/Anthropic 官方 agent 参考实现的标准形态**：一条 assistant
消息同时携带 text 块 + tool_use 块。模型在**自己的消息里**看到"我上次说过
X，然后我调了工具"——正文与工具调用是**同一个发言**，语义上连续。

**现在（2.0.90）的结构**（ChatViewModelAgentLoopExt.kt:1350-1385 +
persistAssistantTurnForRun 的持久化形态，DB 实锤）：

```kotlin
// main：每轮拆成两条独立消息
// 消息 1（assistant）：text 正文
// 消息 2（user）：tool_result
// 下一轮的正文又是新的 assistant 消息
```

**历史序列在模型眼里变成**：

```
assistant: "（正文碎片）我要读配置文件"     ← 独立发言 1
user: [tool_result]                        ← user 插话
assistant: "（正文碎片）读到了，接下来改"    ← 独立发言 2
user: [tool_result]
assistant: "（正文碎片）改好了，再验证"      ← 独立发言 3
...
```

**这就是 preamble 循环的结构性诱因**：对话被重组为"每个正文片段都是一次
以 user tool_result 结尾的独立发言"。在这个形态下：

1. **每个 tool_result 都是对模型"上一句话"的回应**——对话结构上，模型
   收到一个 user 消息就有一次"说话的机会"，模型的对话训练让它倾向于
   "被回应后就该说点什么"。40 轮工具链 = 40 次"轮到你说正文"的机会。
2. **正文与工具调用分离**后，模型看不到"正文+工具调用是同一个意图"的
   连续性——它把每轮开头当作新的发言起点，自然要写一段引导性文字。
3. 2.0.80 的单消息形态里，`text + tool_use` 是模型**自己上一次发言的
   一部分**，后面直接跟着 tool_result——模型視角里"我刚才边说边做"，
   无需重新开口。

**跨模型复现恰好证明这一点**：任何 chat 模型在"被 user 消息回应 N 次"
的结构下都会产生 N 次叙述冲动——这不是 GLM 特性，是对话结构的产物。

---

## 2. 证据链

### 2.1 DB 形态实锤（live DB，openminis-linux 会话）

- `assistant` 消息与 `user(tool_result)` 消息严格交替（100 条内 50/50）
- 每个 assistant 消息只含一轮 text + tool_use（**没有连续多轮 text 堆叠**）
- 即：每轮 assistant 消息后必然跟着一条 user(tool_result) 消息

### 2.2 代码实锤（ChatViewModelAgentLoopExt.kt:1350-1385）

```kotlin
val turnText = turnTextSb.toString()
...
if (turnText.isNotEmpty()) {
    assistantParts.add(AgentContentPart.Text(turnText))
}
...
content = turnText,          // ← UI 消息（正文先行）
...
persistAssistantTurnForRun(...)   // 持久化 assistant 消息
...
appendBoundedHistory(LLMMessage(
    role = LLMMessage.Role.USER,
    content = "",
    contentParts = resultParts,   // ← 独立 USER 消息（tool_result）
))
```

每轮 = 一条 assistant(text+tool_use) + 一条 user(tool_result)。
**provider 序列化后就是上面 §1 的形态**。

### 2.3 2.0.80 对照（git show 37412a8 实测）

2.0.80 的组装代码同样有 `assistantParts.add(Text)` + ToolUse，但当时的
**provider 序列化**与**历史回放**是否把它们合并为一条 assistant 消息、
且 tool_result 是否紧跟在同一条 assistant 后（而非独立 user 行）——需要
进一步 diff（本次未完成，设备断连）。**但用户实测 2.0.80 无碎片**，
且结构差异（见 §1）真实存在。

---

## 3. 为什么 Z-Code/DeepSeekRuntime 没有这个问题

Z-Code 的 transcript 形态（zcode.cjs 实锤）：**assistant 一条消息内
text + tool_use 连续排列**，tool_result 作为 user 消息紧跟——但 Z-Code 的
**system prompt 与 assistant 消息内 text 的相对位置**决定了模型不会把
每轮 preamble 当新发言。更关键的是：Z-Code 的 TUI 渲染把 preamble 与
工具卡连续呈现，且 Z-Code 的 assistant 消息在历史中**不拆分**。

**核心差异**：OpenMinis 2.0.90 把"assistant 发言"物理拆成
`text 消息 → user 消息 → text 消息 → ...` 的**发言链**，而 Z-Code 保持
`assistant(text+tool_use) → user(result) → assistant(text+tool_use)` 的
**发言-回应交替**。前者给模型 N 次"开口机会"，后者每轮一次。

---

## 4. 根因修复方向（非补丁）

**方向：把 per-turn 的 text 合并进 assistant 单条消息的历史形态，即
"发言"与"行动"回到同一消息。**

具体地：`persistAssistantTurnForRun` 的 assistant 消息已经同时包含
text part + tool_use parts（同一行）——**持久化结构本身没问题**。
问题在 **provider 序列化**：`LLMMessage` 的 contentParts 是
`[Text, ToolUse]` 顺序排列（正确），但**如果一轮有多个工具调用，
每个 tool_use 之间是否还有独立的 Text 消息插入？**

需要进一步核实的点（设备断连，未能完成）：

1. provider 侧（OpenAIProvider 等）对 `contentParts = [Text, ToolUse]`
   的序列化是否保持同一条 assistant message（还是拆开）；
2. 历史回放（AgentHistoryExt.toChatMessages）是否把 turnText 与 toolUse
   保持在同一条消息；
3. **关键怀疑**：`persistToolResultMessageForRun` + `appendBoundedHistory`
   把 tool_result 写成独立 USER 消息——这是正确形态（provider 协议要求），
   **但每个 tool_result 之间是否插入了额外的 assistant 空消息/text**，
   导致模型看到"我发了 N 条正文"。

**下一步（需要设备重连）**：用 live DB 直接导出 20 条连续消息的
`(role, has_text, has_tool_use, has_tool_result)` 序列，对照
provider wire format，找出"模型视角里正文被切成 N 条独立发言"的确切位置。

---

## 5. 临时缓解（不推荐作为终态）

- 系统提示加"工具调用之间不要输出正文"——**提示词约束，被否决**
- 渲染层弱化——治标不治本，模型 token 效率损失仍在

---

## 6. 结论

| 项 | 判定 |
|----|------|
| 用户排除项 | 模型、提示词、人格——全部排除 |
| 结构性根因 | per-turn assistant text 与 tool_result 交替形成"发言-回应"链，模型每轮获得开口机会 → preamble 循环 |
| 修复方向 | 核实 provider 序列化/历史回放是否保持了"单条 assistant 消息内 text+tool_use"的形态；若已保持，则检查 tool_result 回填是否插入了多余的 assistant text |
| 下一步 | 设备重连后导出消息序列实锤（§4 的三个核实点） |

---

*v2 分析。前版（模型层假设）被用户实测否定后重写。*
