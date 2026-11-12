package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

/**
 * Everything about the endpoint and the model that a rule may look at. Passed explicitly
 * rather than read off the provider so the resolver stays a pure function — which is what
 * makes it testable without a network stub.
 */
data class ThinkingResolveContext(
    val modelId: String,
    /**
     * [T-android-thinking-rules-phase2] Owning provider-instance id, or null. When
     * set, the resolver prepends this instance's user-authored custom rules (from
     * [ThinkingRuleResolver.customRulesFor]) ABOVE the built-in list. Null → built-ins
     * only, byte-identical to Phase 1.
     */
    val instanceId: String? = null,
    val supportsReasoning: Boolean?,
    val declaredEffortValues: List<String>?,
    /** Numeric `budget_tokens` bounds from models.dev, when the catalog has them. */
    val budgetTokensMin: Int? = null,
    val budgetTokensMax: Int? = null,
    /**
     * [OpenMinis#163] The catalog affirmatively declares this model has NO effort
     * tiers (it reasons, but takes no `reasoning_effort`). Distinct from
     * `declaredEffortValues == null`, which also means "the catalog never heard
     * of it" — only the affirmative case suppresses the field. Defaults false so
     * every existing construction site keeps its current behaviour.
     *
     * Only consulted together with [isXAI] — see the skip in the reasoningEffort
     * branch.
     */
    val declaresNoEffortTiers: Boolean = false,
    val level: ThinkingLevel,
    val maxTokens: Int,
    /**
     * Vendor predicates, resolved by the caller from the base URL. The resolver never
     * parses URLs itself — that keeps URL-sniffing in one place and lets Phase 2 replace
     * these with user-authored scopes without touching this file.
     */
    val isOpenRouter: Boolean,
    val usesUnifiedReasoningEffort: Boolean,
    val isMistral: Boolean,
    val isDashScope: Boolean,
    /**
     * [OpenMinis#163] Endpoint is xAI's own API (api.x.ai), not a relay that
     * merely serves grok-named models. Scopes the empty-tier skip to the vendor
     * where the 400 was actually observed. Defaults false so existing
     * construction sites are unchanged.
     */
    val isXAI: Boolean = false,
    /**
     * The vendor's documented off tier, or null to omit the field when thinking is off.
     * Already an ALLOWLIST decision made by the caller (iOS ff60c818).
     */
    val offEffort: String?,
)

/**
 * Why a particular wire shape was chosen. Design §8 / GH OpenMinis#100: the resolved
 * outcome must be inspectable, otherwise a user-editable rule layer just replaces one
 * hidden variable with a more complicated one.
 */
data class ThinkingResolveTrace(
    val matchedRuleLabel: String,
    val matchedRuleKind: ThinkingRule.Kind,
    val formatSource: String,
    val emittedKeys: List<String>,
    val clampedFrom: String? = null,
    val clampedTo: String? = null,
) {
    /** One-line form for `AppLogger("Thinking")`. */
    val logLine: String
        get() = buildList {
            add("rule=$matchedRuleLabel")
            add("kind=$matchedRuleKind")
            add("src=$formatSource")
            if (clampedFrom != null && clampedTo != null && clampedFrom != clampedTo) {
                add("clamp=$clampedFrom->$clampedTo")
            }
            add("keys=[${emittedKeys.sorted().joinToString(",")}]")
        }.joinToString(" ")
}

/**
 * Data-driven replacement for the thinking-parameter if-return chain.
 * Mirrors iOS `ThinkingRuleResolver.swift`.
 *
 * PHASE 1 SCOPE — read before extending:
 *  • Covers the OpenAI-compatible family only. Gemini and Anthropic keep their own
 *    emitters; their formats are declared in [ThinkingWireFormat] so the vocabulary is
 *    complete, but nothing resolves to them here. Wiring them in is Phase 2.
 *  • Built-in rules only. No persistence, no user rules, no UI. [ThinkingRule.Kind.CUSTOM]
 *    and [ThinkingWireFormat.CustomPath] exist so Phase 2 need not change these types.
 *  • Behaviour must stay byte-for-byte identical to the pre-refactor chain. That is not an
 *    aspiration — ThinkingWireGoldenSnapshotTest was generated against the old
 *    implementation and committed before this file existed (fdc28e2b).
 *
 * EVALUATION MODEL (design §4), two stages:
 *  Stage A — walk the rules top to bottom, first scope match wins, stop. Ordering is
 *            priority. The PROVIDER_TYPE_DEFAULT at the bottom has AllModels scope so a
 *            match is guaranteed and stage A can never fall through.
 *  Stage B — a matched rule that leaves wireFormat null defers to the fallback chain.
 *            Kept separate from stage A on purpose: cross-rule field merging would make
 *            "why did this value come from there" unanswerable in a trace.
 */
object ThinkingRuleResolver {

    /**
     * [T-android-thinking-rules-phase2] Process-wide cache of user-authored custom
     * rules, keyed by provider-instance id, each list in stored (priority) order.
     * Mirrors iOS `ThinkingRuleCache`: [apply] is a sync call reached from the
     * provider's request builder, but rules live in Room (async), so the repository
     * publishes them here on load and on every mutation. A cache miss yields an empty
     * list ⇒ built-in-only behaviour, never a wrong shape.
     */
    @Volatile
    private var customRulesCache: Map<String, List<ThinkingRule>> = emptyMap()

    /** Replace the whole cache (called once after the repository loads config). */
    @Synchronized
    fun setAllCustomRules(byInstance: Map<String, List<ThinkingRule>>) {
        customRulesCache = byInstance
    }

    /** Replace one instance's custom rules (called after an add/edit/delete/reorder). */
    @Synchronized
    fun setCustomRules(instanceId: String, rules: List<ThinkingRule>) {
        customRulesCache = customRulesCache.toMutableMap().apply {
            if (rules.isEmpty()) remove(instanceId) else put(instanceId, rules)
        }
    }

    /** This instance's custom rules in priority order, or empty. */
    fun customRulesFor(instanceId: String?): List<ThinkingRule> =
        instanceId?.let { customRulesCache[it] } ?: emptyList()

    /**
     * [OpenMinis#306] Cold-start state of the user-authored rule cache.
     *
     * The cache is filled asynchronously from Room by
     * `ProviderRepository.loadAllThinkingRulesIntoCache()`; a request built
     * before that first fill used to resolve with built-ins only, silently
     * ignoring every custom rule — the "my rule does nothing" report. The
     * request path cannot suspend to wait for Room, so it keeps falling back to
     * the built-in defaults, but the fallback is now VISIBLE: a warning is
     * logged once per state change and the provider-detail screen renders a
     * banner while the cache is not loaded.
     */
    enum class CustomRuleCacheState {
        /** No fill has happened yet in this process (cold start). */
        NOT_LOADED,

        /** The last fill succeeded; [customRulesFor] is authoritative. */
        LOADED,

        /** The last fill threw; custom rules fall back to built-ins. */
        FAILED,
    }

    @Volatile
    var customRulesState: CustomRuleCacheState = CustomRuleCacheState.NOT_LOADED
        private set

    /** Human-readable reason for [CustomRuleCacheState.FAILED], else null. */
    @Volatile
    var customRulesLoadError: String? = null
        private set

    /** One-shot cold-start warning latch (see [apply]). */
    @Volatile
    private var coldStartWarned = false

    @Synchronized
    fun markCustomRulesLoaded() {
        customRulesState = CustomRuleCacheState.LOADED
        customRulesLoadError = null
    }

    /** [OpenMinis#306] Record a failed fill so the UI can show it instead of silently using defaults. */
    @Synchronized
    fun markCustomRulesLoadFailed(message: String) {
        customRulesState = CustomRuleCacheState.FAILED
        customRulesLoadError = message.take(300)
        AppLogger.warning(
            "Thinking",
            "[rules] custom-rule cache load FAILED, falling back to built-in defaults: ${customRulesLoadError}",
        )
    }

    /** Reset for tests / a fresh profile. */
    @Synchronized
    fun resetCustomRulesForTest() {
        customRulesCache = emptyMap()
        customRulesState = CustomRuleCacheState.NOT_LOADED
        customRulesLoadError = null
    }

    /**
     * The vendor rules, in priority order — deliberately not alphabetical. The most
     * specific predicate must be consulted first, and Mistral leads because its rule is a
     * total prohibition that outranks every shape below it.
     */
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
        // These re-host third-party families behind one OpenAI surface where thinking is
        // controlled ONLY by root `reasoning_effort`; the vendor-native `thinking:{}`
        // object is not honoured, and on Venice an unknown root key is a hard 400.
        // Registered as ONE concept rather than three flags so they cannot drift apart.
        //
        // Placed AFTER the OpenAI-native and qwen patterns so it claims exactly what the
        // old chain's `usesUnifiedReasoningEffort` checks claimed — the deepseek-v4
        // branch and the self-reasoning families below — and nothing more.
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

    /**
     * Resolve and apply the thinking parameters for one request. The body is mutated in
     * place so the call site stays identical to the function this replaced; the trace is
     * returned for logging.
     *
     * [customRulesOverride] exists for the rule EDITOR's live preview: it lets the preview
     * resolve a hypothetical rule without seeding/clearing the process-wide cache (the old
     * code did exactly that, so a preview running while a chat request was being built
     * could race the real cache — and an exception mid-preview left the throwaway entry
     * behind). Production callers pass nothing and keep reading the cache.
     */
    fun apply(
        body: JSONObject,
        ctx: ThinkingResolveContext,
        customRulesOverride: List<ThinkingRule>? = null,
    ): ThinkingResolveTrace {
        val before = body.keys().asSequence().toSet()

        // ---- Stage A: first-match-wins ----
        // [T-android-thinking-rules-phase2] User-authored custom rules (stored order)
        // are prepended above the built-ins, so a custom rule can override a vendor
        // default by matching first — but never remove a built-in. An empty custom
        // list makes `rules` == `builtInRules(ctx)`, byte-identical to Phase 1.
        //
        // [OpenMinis#306] A cache that has not been filled yet (cold start, or a failed
        // Room read) yields an empty list here — the built-in defaults are the fallback.
        // That fallback is logged once per state change instead of being silent.
        customRulesOverride?.let { override ->
            val rules = override + builtInRules(ctx)
            return resolveWith(rules, body, ctx, before)
        }
        // [OpenMinis#306] Make the silent cold-start fallback observable. The
        // provider-detail screen renders the FAILED reason from
        // [customRulesLoadError]; the log covers the "first message landed
        // before Room filled the cache" window, once per process, so a chatty
        // agent loop cannot flood the log.
        if (ctx.instanceId != null && customRulesState != CustomRuleCacheState.LOADED && !coldStartWarned) {
            coldStartWarned = true
            AppLogger.warning(
                "Thinking",
                "[rules] custom rules not loaded yet (state=$customRulesState); " +
                    "resolving instance=${ctx.instanceId} with built-in defaults — " +
                    "a just-created custom rule may not apply until the next request",
            )
        }
        val rules = customRulesFor(ctx.instanceId) + builtInRules(ctx)
        return resolveWith(rules, body, ctx, before)
    }

    /** The Stage A/B body of [apply]; extracted so the preview override shares it. */
    private fun resolveWith(
        rules: List<ThinkingRule>,
        body: JSONObject,
        ctx: ThinkingResolveContext,
        before: Set<String>,
    ): ThinkingResolveTrace {
        val winner = rules.firstOrNull { it.scope.matches(ctx.modelId) }
            ?: return ThinkingResolveTrace(
                matchedRuleLabel = "none",
                matchedRuleKind = ThinkingRule.Kind.PROVIDER_TYPE_DEFAULT,
                formatSource = "no-match",
                emittedKeys = emptyList(),
            )

        // ---- Stage B: fill in what the rule left unspecified ----
        var formatSource = "rule"
        val format = winner.wireFormat ?: run {
            formatSource = "providerTypeDefault"
            ThinkingWireFormat.ReasoningEffort(ctx.offEffort)
        }

        val clamp = emit(format, ctx, body)

        val emitted = body.keys().asSequence().toSet() - before
        return ThinkingResolveTrace(
            matchedRuleLabel = winner.label,
            matchedRuleKind = winner.kind,
            formatSource = formatSource,
            emittedKeys = emitted.toList(),
            clampedFrom = clamp.first,
            clampedTo = clamp.second,
        )
    }

    /**
     * Write the fields for one wire format. Each branch reproduces the corresponding
     * branch of the pre-refactor chain exactly — including its guards, which are the part
     * that carries the field evidence.
     */
    private fun emit(
        format: ThinkingWireFormat,
        ctx: ThinkingResolveContext,
        body: JSONObject,
    ): Pair<String?, String?> {
        val lid = ctx.modelId.lowercase()

        // [T-thinking-off-explicit] Strict-enum families never receive an off tier:
        // sending "minimal" to MiMo/Agnes killed the whole request (iOS c5efeb1e).
        val strictEffortEnum = lid.contains("mimo") || lid.contains("agnes")
        // [OpenMinis#377] Every OFF tier goes through the declared-set clamp. The
        // allowlist value ("none" on official OpenAI, "minimal" on Ark) is only
        // correct for the models whose protocol documents it: a compaction /
        // title / sub-agent request pinned to OFF used to send `reasoning_effort
        // = "none"` to gpt-5 / o-series, which reject it (400) because their
        // legal sets stop at low/minimal. The clamp snaps the value DOWN onto the
        // lowest declared tier and omits the field entirely when the model only
        // declares high/max (forcing "high" would invert the user's OFF intent).
        val offEffort = if (strictEffortEnum) {
            null
        } else {
            ThinkingEffort.clampOffEffort(ctx.offEffort, ctx.declaredEffortValues)
        }

        return when (format) {
            is ThinkingWireFormat.OmitEverything -> null to null

            is ThinkingWireFormat.ReasoningEffortNested -> {
                if (!ctx.level.isEnabled) return null to null
                val effort = ThinkingEffort.wireEffort(ctx.level)
                body.put("reasoning", JSONObject().put("effort", effort))
                effort to effort
            }

            is ThinkingWireFormat.ReasoningEffort -> {
                val isOpenAINative = lid.startsWith("o") || lid.startsWith("gpt-5")
                if (!ctx.level.isEnabled) {
                    // OFF is a separate dispatch on Android, reproduced verbatim from the
                    // pre-refactor chain. Order matters: OpenAI-native ids send the tier
                    // unconditionally; the self-reasoning families only do so when the
                    // endpoint is a unified gateway OR the model declares that tier; and
                    // everything else sends it unless the model declares a set without it.
                    // [OpenMinis#377] `offEffort` has already been snapped onto the
                    // model's declared set by clampOffEffort (and is null when no
                    // legal off tier exists). It is NOT further clamped through
                    // clampEffort: that walks UP when nothing at or below is
                    // declared, so a ["high","max"] model would turn an OFF
                    // request into "high", inverting the user's intent — which is
                    // why clampOffEffort returns null instead.
                    if (offEffort == null) return null to null
                    val isSelfReasoningFamily = listOf("deepseek", "glm", "kimi", "minimax")
                        .any { lid.contains(it) }
                    when {
                        isOpenAINative -> {
                            body.put("reasoning_effort", offEffort)
                            return offEffort to offEffort
                        }
                        isSelfReasoningFamily -> {
                            if (ctx.usesUnifiedReasoningEffort ||
                                ctx.declaredEffortValues?.contains(offEffort) == true
                            ) {
                                body.put("reasoning_effort", offEffort)
                                return offEffort to offEffort
                            }
                            return null to null
                        }
                        ctx.supportsReasoning != false -> {
                            if (ctx.declaredEffortValues?.contains(offEffort) != false) {
                                body.put("reasoning_effort", offEffort)
                                return offEffort to offEffort
                            }
                            return null to null
                        }
                        else -> return null to null
                    }
                }
                if (isOpenAINative) {
                    val effort = ThinkingEffort.clampEffortForModel(ThinkingEffort.wireEffort(ctx.level), lid)
                    body.put("reasoning_effort", effort)
                    return effort to effort
                }
                // Generic path. The legacy self-reasoning skip stays keyed on "declares
                // nothing" rather than family name — iOS 22647505 replaced the
                // id-substring skip-list after GLM behind a relay silently received no
                // thinking field at all.
                val declaresEffort = !ctx.declaredEffortValues.isNullOrEmpty()
                if (!ctx.usesUnifiedReasoningEffort && !declaresEffort &&
                    listOf("deepseek", "glm", "kimi", "minimax").any { lid.contains(it) }
                ) {
                    return null to null
                }
                // [OpenMinis#163] xAI-scoped skip. grok-build-0.1 answers
                // reasoning_effort with "HTTP 400: Model grok-build-0.1 does not
                // support parameter reasoningEffort"; the catalog describes
                // exactly that state as "reasoning": true with
                // "reasoning_options": [] (also true of grok-4.20-0309-reasoning).
                //
                // DELIBERATELY NOT data-driven across all vendors. The same
                // empty-tier shape appears on 2090 bundled catalog entries —
                // relay-hosted Claude, GPT-5, Qwen, and grok behind poe /
                // fastrouter / anyapi — and honouring it everywhere would change
                // the wire format for all of them at once. Omitting the field is
                // arguably more correct for some of those too (Anthropic uses
                // thinking.budget_tokens, not effort), but none of those routes
                // has been verified, so the skip stays at the vendor where the
                // 400 was actually observed. Widening it later is a one-line
                // change to this condition, backed by new evidence.
                //
                // Ordered AFTER the family list on purpose: that list keys on
                // "declares nothing" and must keep firing for relay-hosted
                // deepseek/glm/kimi/minimax ids the catalog is silent about.
                // Unified-effort gateways stay exempt for the same reason as
                // above — they normalize the field and own their model list.
                if (ctx.isXAI && !ctx.usesUnifiedReasoningEffort &&
                    ctx.declaresNoEffortTiers && !declaresEffort
                ) {
                    return null to null
                }
                if (ctx.supportsReasoning == false) return null to null
                val requested = ThinkingEffort.clampEffortForModel(ThinkingEffort.wireEffort(ctx.level), lid)
                val clamped = ThinkingEffort.clampEffort(requested, ctx.declaredEffortValues)
                body.put("reasoning_effort", clamped)
                requested to clamped
            }

            is ThinkingWireFormat.DeepSeekSibling -> {
                // [OpenMinis#356] The alias `deepseek-flash` has no modern
                // catalog row (the catalog describes the old chat model), so
                // `declaredEffortValues` is either null or a pre-V4 set. Because
                // the alias is served by V4, clamp against V4's legal set so an
                // xhigh/undeclared tier cannot reach the endpoint. The canonical
                // `deepseek-v4*` ids keep their catalog-driven clamp untouched.
                val validEfforts = if (lid.contains("deepseek-v4")) {
                    ctx.declaredEffortValues
                } else {
                    ThinkingEffort.DEEPSEEK_V4_EFFORT_VALUES
                }
                if (ctx.level.isEnabled) {
                    val requested = ThinkingEffort.wireEffort(ctx.level)
                    val clamped = ThinkingEffort.clampEffort(requested, validEfforts)
                    body.put("thinking", JSONObject().put("type", "enabled"))
                    body.put("reasoning_effort", clamped)
                    requested to clamped
                } else {
                    body.put("thinking", JSONObject().put("type", "disabled"))
                    null to null
                }
            }

            is ThinkingWireFormat.BooleanToggle -> {
                // [OpenMinis#306] The editor offers this format, but the emitter
                // used to fall into the "programmer error" `else` branch and
                // threw at REQUEST TIME — a custom rule the user could create but
                // never use. A plain switch is on/off; there is no tier.
                ThinkingEffort.putDotted(body, format.path, ctx.level.isEnabled)
                null to null
            }

            is ThinkingWireFormat.ExtraBodyToggle -> {
                // [OpenMinis#306] Nested switch under `extra_body` (DeepSeek's
                // documented native shape). The path may or may not carry the
                // `extra_body.` prefix; both spellings must work because the
                // editor defaults to the prefixed one.
                val path = format.path.removePrefix("extra_body.").ifEmpty { format.path }
                val extra = body.optJSONObject("extra_body") ?: JSONObject().also {
                    body.put("extra_body", it)
                }
                ThinkingEffort.putDotted(extra, path, ctx.level.isEnabled)
                null to null
            }

            is ThinkingWireFormat.CustomPath -> {
                // [OpenMinis#306] Escape hatch: a dotted path plus a per-tier
                // value map. The old `else -> error(...)` made EVERY custom rule
                // using this format crash the request builder (and made the
                // editor preview print "(preview unavailable)"). Unknown tiers
                // fall back to the wire effort string; OFF uses offValue when the
                // user supplied one, else omits.
                if (ctx.level.isEnabled) {
                    // Prefer the exact tier; otherwise the nearest lower tier the
                    // user DID provide, then the lowest provided one. Only when
                    // the map is empty do we fall back to the standard wire
                    // effort — a custom rule's own vocabulary is authoritative,
                    // so we never invent a tier it did not author.
                    val value = format.values[ctx.level]
                        ?: format.values.entries
                            .filter { it.key.rank <= ctx.level.rank }
                            .maxByOrNull { it.key.rank }?.value
                        ?: format.values.entries.minByOrNull { it.key.rank }?.value
                        ?: ThinkingEffort.wireEffort(ctx.level)
                    ThinkingEffort.putDotted(body, format.path, value)
                    null to null
                } else {
                    val off = format.offValue ?: return null to null
                    ThinkingEffort.putDotted(body, format.path, off)
                    null to null
                }
            }

            is ThinkingWireFormat.QwenDual -> {
                // [T-thinking-rules-phase1] ANDROID-SPECIFIC OFF SEMANTICS — do not
                // "align" this with iOS without a deliberate behaviour change.
                //
                // Android's pre-refactor chain dispatched OFF through a separate block
                // from the enabled path, and in that block Qwen/DashScope hit an early
                // `return` ("keep their native enable_thinking mechanism — never route
                // them through reasoning_effort"), so NOTHING was emitted at OFF. iOS
                // instead falls into its qwen branch at every level and emits
                // `enable_thinking:false` + a null-budget extra_body.
                //
                // Both platforms therefore leave Qwen thinking-off to the vendor default,
                // but by different wire shapes. Phase 1 is a pure refactor, so each
                // platform keeps its own shape and the golden snapshots pin them
                // separately. Unifying them is a real behaviour decision for Phase 2 —
                // and the fact that this discrepancy was invisible until the snapshots
                // existed is itself an argument for having built them.
                if (!ctx.level.isEnabled) return null to null

                val enabled = true
                // [T-thinking-ladder-shared] REPLACES a hardcoded ABSOLUTE ladder
                // (LOW 4096 / MEDIUM 16384 / HIGH 32768 / XHIGH·MAX·ULTRA 65536) that
                // collapsed on two independent counts, both reproduced by hand before
                // this change:
                //
                //  (1) XHIGH/MAX/ULTRA were byte-identical constants (65536) on every
                //      model. At maxTokens 8192 the whole ladder above LOW collapsed.
                //  (2) The clamp ate the MIDDLE of the ladder too, which is the
                //      subtler half: `margin = maxOf(2048, maxTokens / 8)` is a FLAT
                //      2048 for every maxTokens <= 24576, and `if (budget >= ceiling)`
                //      pins every rung that reaches it to the SAME ceiling. Measured
                //      (margin, ceiling → LOW/MED/HIGH/XH/MAX/ULTRA):
                //        maxTokens   8192: 2048,  6144 → 4096/6144/6144/6144/6144/6144
                //        maxTokens  16384: 2048, 14336 → 4096/14336/14336/14336/14336/14336
                //        maxTokens  24576: 2048, 21504 → 4096/16384/21504/21504/21504/21504
                //      i.e. at 8192 five of six levels sent one number.
                //
                // WHY THE LADDER IS SCALED AGAINST `ceiling`, NOT `maxTokens`:
                // ThinkingResolveContext exposes NO model capacity — its complete field
                // list is modelId, instanceId, supportsReasoning, declaredEffortValues,
                // declaresNoEffortTiers, level, maxTokens, isOpenRouter,
                // usesUnifiedReasoningEffort, isMistral, isDashScope, isXAI, offEffort.
                // `LLMModel.maxOutputTokens` exists but is not threaded through here, and
                // adding it would mean editing the shared data class plus its OpenAI
                // construction site. So `maxTokens` is the only capacity in scope here —
                // and against it, keeping the margin as a SEPARATE post-clamp does NOT
                // work, because ThinkingLadder's top rung (15/16 of capacity) lands
                // ABOVE `maxTokens - margin` and the `if (budget >= ceiling)` clip then
                // re-creates exactly the collapse being fixed (verified: at maxTokens
                // 8192 ULTRA lands ON 6144; at 4096 MAX and ULTRA both become 2048).
                //
                // So the margin is folded INTO the capacity instead of applied after:
                // the ladder is generated against `ceiling` and there is no subsequent
                // clip at all. DashScope's "budget < max_completion_tokens" requirement is
                // then satisfied by construction (every rung is <= ceiling - 1 <
                // maxTokens), and no second step can re-collapse two rungs. The cost is
                // that the whole ladder shrinks by the margin (7.8% at maxTokens 32768,
                // 12.5% at 16384) — paid uniformly, which is the opposite of the old
                // behaviour where the margin was paid once, by the top five rungs.
                //
                // Verified strictly increasing AND strictly below the ceiling for
                // maxTokens in {8192, 16384, 32768, 65536}; below `2 * floor` the ladder
                // degrades to honest duplicates rather than a fake gradient (see
                // ThinkingBudgetWireTest).
                var budget = 0
                if (ctx.maxTokens < 2) {
                    // [T-thinking-ladder-shared] Degenerate guard PRESERVED. With
                    // maxTokens <= 1 no positive budget can be strictly below it, and
                    // DashScope 400s on an equal or larger value. Reached for
                    // maxTokens 1 and, unlike the old `if (budget > 0 && maxTokens > 0)`
                    // gate, also for 0 / negatives — which previously emitted an UNCLAMPED
                    // 4096 against a zero budget. Strictly safer, no test pinned it.
                    budget = 0
                } else {
                    val margin = maxOf(2048, ctx.maxTokens / 8)
                    var ceiling = maxOf(1, minOf(ctx.maxTokens - margin, ctx.maxTokens - 1))
                    // [T-modelsdev-budget-tokens] The catalog may publish an explicit
                    // upper bound (`reasoning_options[type=budget_tokens].max`). When
                    // present it overrides the generic request-level ceiling so that
                    // e.g. a model whose catalog max is 32k sends a 32k-scaled budget
                    // even when the user side asks for 128k output tokens.
                    ctx.budgetTokensMax?.takeIf { it > 0 }?.let { bmax ->
                        ceiling = minOf(ceiling, bmax)
                    }
                    // [T-thinking-ladder-shared] `ThinkingLadder` is only meaningful
                    // when it can host its floor: `budgetFor` coerces into
                    // `[FLOOR, capacity - 1]` before it reaches its own degenerate
                    // branch, so a capacity at or below FLOOR (512) throws rather than
                    // degrading. The margin is a flat 2048, so `ceiling` collapses to
                    // 1 for every maxTokens <= 2560 — reached here whenever a caller
                    // requests a very small completion budget. OMIT the budget instead:
                    // with `enable_thinking:true` and no `thinking_budget` the vendor
                    // picks its own default, which is both legal and better than the
                    // old code's `thinking_budget: 1` (a one-token thinking budget is
                    // not a weaker thinking mode, it is a broken one).
                    budget = if (ceiling > ThinkingLadder.FLOOR) {
                        ThinkingLadder.budgetFor(ctx.level, ceiling)
                    } else {
                        0
                    }
                    // [T-modelsdev-budget-tokens] Some models (e.g. qwen3.5-4b)
                    // declare a minimum budget in the catalog. Floor the ladder
                    // output to at least that minimum for enabled levels, but never
                    // exceed the capacity ceiling.
                    if (budget > 0) {
                        ctx.budgetTokensMin?.takeIf { it in 1 until ceiling }?.let { bmin ->
                            budget = maxOf(budget, bmin)
                        }
                    }
                }
                body.put("enable_thinking", enabled)
                if (budget > 0) body.put("thinking_budget", budget)
                body.put(
                    "extra_body",
                    JSONObject().apply {
                        put("enable_thinking", enabled)
                        // ANDROID-SPECIFIC: OMIT the key when there is no budget, rather
                        // than sending an explicit JSON null as iOS does. Preserved
                        // verbatim from the pre-refactor chain; changing it would alter
                        // the request for the pathological maxTokens<2 case.
                        if (budget > 0) put("thinking_budget", budget)
                    },
                )
                null to null
            }

            else -> {
                // Phase 1: declared for vocabulary completeness, never resolved to on this
                // path. Reaching here means the registry named a format the OpenAI emitter
                // cannot produce — a programmer error, not a runtime condition.
                error("ThinkingWireFormat $format is not emitted on the OpenAI path in Phase 1")
            }
        }
    }

    /** [OpenMinis#470] Gemini thinking config; impl in ThinkingShapes.kt. */
    fun geminiThinkingConfig(
        modelId: String,
        level: ThinkingLevel,
        budgetTokensMax: Int? = null,
        hasAudioOutput: Boolean = false,
    ): JSONObject? = ThinkingShapes.geminiThinkingConfig(modelId, level, budgetTokensMax, hasAudioOutput)

    /** Anthropic thinking shape; impl in ThinkingShapes.kt. */
    fun anthropicThinkingShape(
        modelId: String,
        supportsReasoning: Boolean?,
        level: ThinkingLevel,
        maxTokens: Int,
    ): Map<String, Any> = ThinkingShapes.anthropicThinkingShape(modelId, supportsReasoning, level, maxTokens)
}