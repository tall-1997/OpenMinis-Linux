#思考参数：普适性修复方案（非补丁）

##一、根因（架构层，不是某条判断）

思考参数的 wire format决策有 **5个判据**，全部是静态预判：

| # |判据 |位置 |性质 |可靠性 |
|---|---|---|---|---|
|1 |模型 ID模式 `*deepseek-v4*` | `ThinkingRuleRegistry.kt:131` |硬编码字符串 |低 |
|2 |家族名单 deepseek/glm/kimi/minimax | `ThinkingRuleResolver.kt:740` |硬编码列表 |低 |
|3 | `endpointHost.contains("deepseek")` | `ThinkingRuleResolver.kt:317` | **子串匹配** |低（路径含该词即误判） |
|4 | `declaredEffortValues`非空 |来自模型目录同步 |依赖外部数据 |手动配置的 relay永远为空 |
|5 | `EndpointReasoning`读 `supported_parameters` | `EndpointReasoning.kt:47` | **端点自述** |高，但多数 relay不提供 |

###致命缺陷：默认方向反了

现在是 **默认按模型 ID猜厂商私有形态**，只有证明是 relay才改用通用形态。

但**失败代价严重不对称**：

|猜错方向 |后果 |
|---|---|
|猜「私有」给 relay | relay不认识该字段 → **静默忽略 →无思考，且不报错**（当前处境） |
|猜「通用」给厂商官方 | `reasoning_effort`是 OpenAI兼容标准，官方也认 →无害 |

**正确默认应该是「未知 →通用形态」（安全侧），而不是「未知 →私有形态」（危险侧）。**

这正是 `f8460cba`尝试的方向，但它没走到底：它把「非厂商 host」作为判据之一，
却又要求「端点必须声明了档位」（`declaredEffortValues`非空）才降级——
而手动配置的 relay永远不满足这条，于是修复分支永不触发。

##二、普适方案：四层，从强到弱

###层1 ·端点自述优先（已有基础，补用途）

`EndpointReasoning`已能从 `supported_parameters`解析出：
- `reasoning` / `reasoning_effort` / `include_reasoning` →该发哪种形态

**现状**：解析结果只喂给「能力开关」（`supportsReasoning`），**没参与形态决策**。

**补法**：让 `EndpointReasoningFacts`增加 `preferredWireFormat`，直接决定形态。
端点说了算，优先级最高。

###层2 ·通用形态默认（核心：反转默认值）

```
判据：是否【确证】为厂商官方 host

官方 host判定：精确 host白名单（不用子串）
 api.deepseek.com / api.openai.com / api.anthropic.com / ...

非官方（含未知）→一律发 reasoning_effort（OpenAI兼容最大公约数）
```

**这一条是所有厂商通用的**：新增任何 relay都不需要改代码。

关键改动：把 `contains("deepseek")`换成精确 host列表匹配。
`susu.wiki/deepseek/v1`这种路径不会再误判成官方。

###层3 ·400自适应（兜底：让端点纠正我们）

任何静态判断都可能错。最后一层：

```
发送带思考参数的请求
 →400且错误体提到该字段（unknown field / extra_forbidden / invalid param）
 →自动去掉该字段重试一次
 →记住（providerInstanceId + modelId维度，落盘到 provider配置）
 →后续该模型不再发被拒字段
```

这是唯一能覆盖「我们猜错 +端点严格校验」场景的机制。

###层4 ·硬编码退役

前3层就位后：
- `isSelfReasoningFamily`的4个名字 →不再决定成败
- `ModelPattern("*deepseek*")`两条规则 →只作为官方 host上的优化提示

##三、为什么这是普适而非补丁

|场景 |现在的行为 |四层方案 |
|---|---|---|
|新增 relay（susu.wiki） |需要加代码 | **自动正确**（非官方 →通用形态） |
|新增厂商（moonshot、yi…） |需要加名字到名单 | **自动正确** |
| relay严格校验拒绝参数 |400失败 | **层3自动降级并记住** |
|端点声明了能力 |只用于开关 | **层1用于形态** |
|厂商官方 host |正确 | **层2白名单保持正确** |
| relay路径含厂商名 | `contains`误判 | **层2精确匹配修正** |

**补丁 =增加枚举项（每来一个厂商加一条）**
**普适 =改变默认值的方向（未知落到安全侧，从此不需要枚举）**

##四、实施顺序与风险

|阶段 |内容 |风险 |验证 |
|---|---|---|---|
| **A** |层2核心：去掉「必须声明档位」条件 +精确 host匹配 |低（仅影响非官方 host） |单测：relay+deepseek →无 `thinking`、有 `reasoning_effort`；官方 →保持 `thinking` |
| **B** |层1：`EndpointReasoning`产出 `preferredWireFormat` |中（改动声明解析链路） |单测：声明 `reasoning_effort` →该形态优先 |
| **C** |层3：400自适配 +记忆 |中高（涉及重试与落盘） |单测：模拟400 →去字段重试 →记忆命中 |
| **D** |层4：退役硬编码 |低（有前3层兜底） |全量回归 |

**阶段 A就能解决你的问题**（susu.wiki + deepseek手动条目），且不带400重试的复杂度。

##五、验证方法（真机）

`f8460cba`已加出站轨迹记录。打开开关后：

```
设置 →日志 →流式轨迹 →打开
发一条消息
查看 /data/data/com.openminis.linux/files/diagnostics/stream-traces/*.jsonl
```

期望：
- **修复前**：同时有 `thinking:{type:enabled}`和 `reasoning_effort`
- **修复后**：只有 `reasoning_effort`
- **若只有 reasoning_effort但仍无 thinking chunk** →上游剥离，需换通路
