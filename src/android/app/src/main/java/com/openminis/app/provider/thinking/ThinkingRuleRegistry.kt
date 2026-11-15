package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel

/**
 * The vendor rules, in priority order — deliberately not alphabetical. The most
 * specific predicate must be consulted first, and Mistral leads because its rule is a
 * total prohibition that outranks every shape below it.
 *
 * [T-thinking-registry-split] Extracted from [ThinkingRuleResolver] when that
 * file hit the architecture-policy line ceiling (the P2 3-9 clamp additions
 * pushed it over the 800-line cap; the registry is a self-contained data
 * table, the natural thing to lift out). The resolver keeps a delegating
 * [ThinkingRuleResolver.builtInRules] so every call site — internal and the
 * ProviderRepository display path — stays unchanged.
 */
internal object ThinkingRuleRegistry {

    fun builtInRules(ctx: ThinkingResolveContext): List<ThinkingRule> = buildList {
        // Mistral — GH OpenMinis#87 / iOS 4592ca9b / 29065ca0. Total prohibition: the
        // request rejects `reasoning` (422 extra_forbidden) and AssistantMessage is a
        // closed schema that rejects `reasoning_content`. Must outrank everything.
        if (ctx.isMistral) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.OmitEverything,
                    reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.NEVER),
                    label = "mistral-official",
                ),
            )
        }

        // OpenRouter — nested `reasoning:{effort}`, OMIT when off so forced-reasoning
        // backends don't reject `effort:"none"`.
        if (ctx.isOpenRouter) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.ReasoningEffortNested(offValue = null),
                    label = "openrouter",
                ),
            )
        }

        // ORDER IS LOAD-BEARING FROM HERE DOWN. It reproduces the pre-refactor `when`
        // chain's evaluation order exactly, which was:
        //     o*/gpt-5* → qwen||isDashScope → self-reasoning skip → generic fallback
        // with `usesUnifiedReasoningEffort` consulted only INSIDE the deepseek-v4 and
        // self-reasoning branches — never by the OpenAI-native or qwen branches.
        //
        // [T-thinking-rules-phase1] The first version of this registry hoisted the
        // unified-gateway rule ABOVE these, which silently changed two real cases: a
        // gpt-5 id on DashScope started emitting `enable_thinking`+`thinking_budget`
        // instead of `reasoning_effort`, and (on iOS, mirrored) a qwen id on
        // Ark/Azure/Venice flipped the other way. That is a user-visible
        // silent-degradation regression of exactly the kind this design exists to
        // prevent. The gateway rule must sit BELOW these two, not above.
        //
        // The golden snapshot did not catch it because every matrix row varied a single
        // dimension; the cross-product rows (qwen×unified, gpt5×dashscope, mimo×unified)
        // were added alongside this fix.

        // OpenAI native o-series / GPT-5.x — root reasoning_effort. Android's original
        // predicate was `startsWith("o") || startsWith("gpt-5")`; the broad "o" prefix is
        // preserved verbatim rather than narrowed to o1/o3/o4, because narrowing it would
        // change behaviour for any id starting with "o" and this phase must not.
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("o*"),
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-native",
            ),
        )
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("gpt-5*"),
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-native",
            ),
        )

        // Qwen / DashScope — dual-send + strict budget inequality (25165700, a5a0de20).
        // The old branch was `lid.contains("qwen") || isDashScope` with NO unified guard,
        // so both the id match and the endpoint match keep their native enable_thinking
        // mechanism even when the endpoint is Ark/Azure/Venice.
        if (ctx.isDashScope) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.QwenDual,
                    label = "qwen-dashscope",
                ),
            )
        }
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("*qwen*"),
                wireFormat = ThinkingWireFormat.QwenDual,
                label = "qwen-dashscope",
            ),
        )

        // Unified gateways (Volcengine Ark / Azure / Venice) — iOS ba055121 + 84f5c9e1.
        // Third-party families behind one OpenAI surface: thinking controlled ONLY by
        // root `reasoning_effort`; the vendor-native `thinking:{}` is not honoured, and
        // on Venice an unknown root key is a hard 400. ONE concept, not three flags, so
        // they cannot drift apart. Placed AFTER the OpenAI-native and qwen patterns so
        // it claims exactly what the old `usesUnifiedReasoningEffort` checks claimed.
        if (ctx.usesUnifiedReasoningEffort) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                    label = "unified-gateway(ark|azure|venice)",
                ),
            )
        }

        // DeepSeek V4 vendor-native sibling shape (iOS 847822eb, Android df776253). Only
        // when NOT on a unified gateway — that rule above already claimed those.
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("*deepseek-v4*"),
                wireFormat = ThinkingWireFormat.DeepSeekSibling,
                reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY),
                label = "deepseek-v4-official",
            ),
        )

        // [OpenMinis#356] `deepseek-flash` is a live ALIAS for the V4 flash
        // model (the catalog row, when there is one, is the older chat entry).
        // Without this rule the alias fell through to the generic
        // reasoning_effort fallback: the wrong wire shape (no root
        // `thinking:{type:…}` switch, which V4 requires) and an unclamped
        // effort (xhigh/max leaking into an endpoint whose legal set is
        // low|medium|high|max). The scope also covers a future `deepseek-flash-*`
        // spelling; `*deepseek-v4*` above stays first so the canonical ids are
        // unaffected.
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("*deepseek-flash*"),
                wireFormat = ThinkingWireFormat.DeepSeekSibling,
                reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY),
                label = "deepseek-flash-alias",
            ),
        )

        // Fallback for the providerType: generic root reasoning_effort, subject to the
        // self-reasoning skip in stage B. AllModels guarantees stage A always matches.
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.PROVIDER_TYPE_DEFAULT,
                scope = ThinkingRule.Scope.AllModels,
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-compatible-default",
            ),
        )
    }
}
