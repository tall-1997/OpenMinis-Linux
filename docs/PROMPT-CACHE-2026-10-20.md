# 提示词缓存命中率根因与修复（2026-10-20）

## 现象

同一模型、同一问题，App 的缓存命中率约 **50%**；opencode / dsh-desktop / codex
同类会话可达 **90%+**。App 的命中率口径是
`cacheRead / (input + cacheRead + cacheCreation)`（`UsageStatsScreen`），
缓存写入计为未命中。

## 根因（按影响排序）

1. **每轮变化的动态上下文放在 system prompt 里**
   `buildSystemPrompt()` 在稳定前缀之后追加 WorldBook 命中、场景学习偏好、
   基于最新用户消息的 FTS 记忆召回、任务锚点、运行时日期/语言/模型数。
   其中召回和 WorldBook 至少每轮变化一次。
   前缀缓存的语义是“从第 0 个 token 起的最长公共前缀”：
   - OpenAI / DeepSeek 自动前缀缓存：system 之后的所有 messages 每轮重新计入；
   - Anthropic：message 级 `cache_control` 断点的前缀**包含**这段动态 system
     块，所以跨轮永远无法命中，只有 tool + 稳定 system 两个断点有效。

   这正好解释了 ~50%：tools+稳定 system 命中，整段对话每轮重算。

2. **记忆文件每轮重读，`memory_write` 会改写“稳定”头部**
   今日 daily log / GLOBAL.md 位于 system 稳定前缀内。Agent 被明确要求主动
   `memory_write`；写入后下一轮 daily 片段变化 → 整个前缀失效。

3. **超窗摘要在头部重写**
   `HistoryDigest.inject()` 把不断增长的摘要插进**第一条 user 消息**；
   每次 trim 都会改写会话开头，缓存从第一个用户消息处断开。

4. **压缩后的分块摘要是查询相关的**
   `effectiveAgentHistoryUncounted()` 用最新用户消息检索 compaction chunks，
   并拼进 anchor 之后的**第一条 user 消息**；压缩后每一轮头部都不一样。

5. **口径与 TTL（不是代码 bug，但会让读数偏低）**
   - Anthropic 的 `cache_creation_input_tokens` 计为未命中：第一轮全是写，
     短会话（2–3 轮）天然只有 ~50–65%，与缓存是否生效无关。
   - 默认 5 分钟 TTL；轮次间隔超过 5 分钟会过期（增强缓存 1h 开关默认关闭）。

## 修复

- **system prompt 改为完全静态**：identity / 工具与环境说明 / skills / MCP /
  冻结的 memory 快照。`systemPromptStablePrefixLen = -1`（整段一个缓存块）。
- **每轮上下文移到最新 user 消息尾部**：
  `buildTurnContextFragment()` 每次 agent run 计算一次，
  `applyTurnContext()` 以 `<system-reminder id="turn-context">` 附到最后一轮
  user-text 消息（不落库）。system 从此逐字节稳定。
- **记忆快照**：`PromptCacheState.frozenMemory` 以
  session+date+memory 开关为 key，会话内不再重读 memory 文件；新会话/次日
  重新加载。模型仍可用 `memory_get` 读取新写入。
- **摘要与压缩分块移到尾部**：`HistoryDigest` 和 compaction excerpts 都进
  turn context，不再改写第一条 user 消息。
- **诊断指纹**：每次 run 打一行
  `[CacheDiag] fingerprint=… systemChars=… tools=… turnCtxChars=… history=…`；
  连续轮次指纹相同即 system/tools 稳定，可直接在日志里验证。

## 其余链路的审计证据

**工具定义顺序 / 序列化不确定性：不是根因（已加测试钉住）**

- `AgentTools.makeAgentTools` 是按字面顺序 `add(...)` 的固定列表；`enabledToolNames`
  只做成员过滤，保持 canonical 顺序，`find_tools` 固定追加在最后；
- 插件工具按 `PluginRegistry.cached()` 的 List 顺序、本地插件按 JSON 数组顺序追加；
- `parameters` 全部由 `mapOf` / `associate`（LinkedHashMap）构建，
  `toOpenAIJson` / `toAnthropicJson` / `toGeminiJson` 按插入顺序迭代，
  `org.json.JSONObject` 也保序；
- `agentTools` 有 memo + 变更 stamp，stamp 只随真实输入变化。
  `ToolSchemaSerializationStabilityTest` 对同一构建连续序列化两次做逐字节比较，
  并钉住“参数声明顺序 / long-tail 工具 canonical 位置 / find_tools 最后”。

**cache_control 布局**

- Anthropic 4 个断点：最后一个 tool、system（现在整段一个稳定块）、最后 2 条
  user 消息的最后一个 block；system 断点的前缀已包含 tools，tool 断点冗余但
  在 system 变化（skills/MCP）时仍能单独命中，保留。
- OpenAI / DeepSeek 无显式断点，走自动前缀缓存；OpenRouter 的 Anthropic 模型
  才按 block 注入 `cache_control`。Gemini 为隐式缓存，App 不控制断点。

**历史重建差异**

- 每轮从 Room 重建（`prepareRunHistory` → `rebuildAgentHistoryFromDatabase`）；
  `dropOrphanedToolParts` / `sanitizeAgentHistory` / `MessageTransformerChain`
  都是确定性变换。
- 请求侧临时内容（turn context、persona steering、stall resume note、goal mount、
  nudge）只加在尾部；digest 与 compaction excerpts 已从头部移到尾部。
- 残留：超预算时 `progressivelyTrimmed` 会按 tier stub 旧工具输出、图片预算会
  按总字节把更老的图片换成占位符——两者都只在阈值变化时一次性失效。

**provider 差异**

- Anthropic：显式断点 + 5 分钟 TTL（增强缓存 1h 开关）；
- OpenAI / DeepSeek：自动前缀缓存，无需 key；`prompt_cache_key` 只用于
  Responses API，值由首条 user 文本派生，稳定；
- 同一会话切换 provider/模型会换 tools/system，属一次性失效，不是每轮问题。

## 预期与残留

- 稳定态命中率 ≈ 上一轮 prompt / 本轮 prompt。仍会损失**当轮 user 消息**的
  重新计入（turn context 不落库，下一轮该消息回退为原始文本），通常是一轮里
  的几百到几千 token；其余 system + tools + 历史全部命中。
- 次要失效点（未在本次改动）：
  - `find_tools` 动态扩充工具列表、插件安装 → tools 变化，一次全量失效；
  - thinking 开关切换会改变历史 assistant 的 `reasoning_content` 形状；
  - 图片预算裁剪会按总字节把更老的图片替换成占位符；
  - 5 分钟 TTL 过期（建议长间隔测试开启增强缓存 1h）。

## 验证

- 本地全量：`:app:testReleaseUnitTest` 492 suites / 3947 tests 全绿；
  新增/扩充 `TurnContextInjectionTest`（9，含“只有最后一条 user 消息变化、
  更早消息逐字节不变”的前缀保持测试）、`ToolSchemaSerializationStabilityTest`
  （4，工具 schema 两次构建逐字节一致 / 参数声明顺序 / canonical 顺序）、
  `PromptCacheFingerprintTest`、`PromptCacheLayoutContractTest`
  （源码守卫，防止动态片段被加回 system）。
- 三个 CI 门禁（架构/CJK/未翻译）本地通过。
- 真机 A/B：同一会话连续 6+ 轮，`adb logcat -s ChatVMStream` 看
  `[CacheDiag] fingerprint` 是否逐轮一致；用量页看 cache read 占比。
