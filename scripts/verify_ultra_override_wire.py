#!/usr/bin/env python3
"""[T-thinking-ultra-manual] Simulate the fixed wire chain in Python and
verify every contract branch that the Kotlin regression tests assert:

1. wireEffort default fold: ULTRA -> "max" for vocabulary-less models.
2. effortOrDeclaredUltra: declared "ultra" -> literal; manual ceiling ULTRA
   (operator assertion) -> literal even against a declared set without it.
3. clampRequested: the override-asserted "ultra" bypasses the declared-set
   clamp (no walk-down to "max"); a NON-override "ultra" still clamps.
4. Provider-side clampThinkingLevel: override folded onto the model is the
   ceiling (ULTRA survives the send boundary); capability guard wins.
5. DeepSeek vendor clamp: override-ULTRA still folds to "max" (no ultra tier
   in DeepSeek's vocabulary).
6. clampEffort ladder walk-down for undeclared tiers (unchanged contract).
"""
OFF, LOW, MED, HIGH, XHIGH, MAX, ULTRA = range(7)
NAMES = ["OFF", "LOW", "MEDIUM", "HIGH", "XHIGH", "MAX", "ULTRA"]
RANK = {"low": LOW, "medium": MED, "high": HIGH, "xhigh": XHIGH, "max": MAX, "ultra": ULTRA}


def wire_effort(level):
    return {OFF: "low", LOW: "low", MED: "medium", HIGH: "high",
            XHIGH: "xhigh", MAX: "max", ULTRA: "max"}[level]


def declares_ultra(declared):
    return declared is not None and any(d.lower() == "ultra" for d in declared)


def effort_or_declared_ultra(level, declared, ceiling_override=None):
    if level == ULTRA and (declares_ultra(declared) or ceiling_override == ULTRA):
        return "ultra"
    return wire_effort(level)


def clamp_effort(effort, declared):
    """Ladder walk-down: requested tier not in the declared set -> drop one rung."""
    if declared is None or effort in declared:
        return effort
    order = ["low", "medium", "high", "xhigh", "max", "ultra"]
    ranked = [e for e in order if e in declared]
    below = [e for e in ranked if RANK[e] < RANK[effort]]
    return below[-1] if below else ranked[0]


def clamp_requested(effort, declared, ceiling_override):
    """The operator-assertion escape hatch."""
    if ceiling_override == ULTRA and effort == "ultra":
        return effort
    return clamp_effort(effort, declared)


def catalog_max(model):
    """catalogMaxThinkingLevel: capability guard -> folded override -> declared top."""
    if model.get("supports_reasoning") is False:
        return OFF
    if model.get("max_thinking_level") is not None:
        return model["max_thinking_level"]
    declared = model.get("tiers")
    if declared:
        return max(RANK[d] for d in declared)
    return XHIGH


def provider_clamp(level, model):
    ceiling = catalog_max(model)
    return ceiling if level > ceiling else level


def deepseek_sibling_wire(level, declared, override):
    """DeepSeek branch: effortOrDeclaredUltra then the VENDOR legal-set clamp."""
    effort = effort_or_declared_ultra(level, declared, override)
    legal = ["low", "medium", "high", "max"]
    return clamp_effort(effort, legal)


T5 = ["low", "medium", "high", "xhigh", "max"]
fails = []


def check(name, got, want):
    ok = got == want
    print(f"{'PASS' if ok else 'FAIL'}  {name}: got={got!r} want={want!r}")
    if not ok:
        fails.append(name)


# 1. default fold
check("wire ULTRA no-vocabulary", wire_effort(ULTRA), "max")
check("wire MAX", wire_effort(MAX), "max")

# 2. declared-ultra literal
check("declared ultra -> literal", effort_or_declared_ultra(ULTRA, ["high", "ultra"], None), "ultra")
check("declared ultra MAX-level unaffected", effort_or_declared_ultra(MAX, ["high", "ultra"], None), "max")

# 3. manual override assertion (THE user report)
relay = {"tiers": T5, "max_thinking_level": ULTRA, "supports_reasoning": True}
req = effort_or_declared_ultra(ULTRA, T5, ULTRA)
wire = clamp_requested(req, T5, ULTRA)
check("manual ULTRA override -> wire literal", wire, "ultra")
check("no override -> fold to max",
      clamp_requested(effort_or_declared_ultra(ULTRA, T5, None), T5, None), "max")
check("MAX on override model -> max",
      clamp_requested(effort_or_declared_ultra(MAX, T5, ULTRA), T5, ULTRA), "max")

# 4. provider-side clamp honors the folded override (was the silent killer)
check("provider clamp ULTRA+override survives", provider_clamp(ULTRA, relay), ULTRA)
norelay = {"tiers": T5, "max_thinking_level": None, "supports_reasoning": True}
check("provider clamp ULTRA no-override -> MAX", provider_clamp(ULTRA, norelay), MAX)
check("capability guard beats override",
      catalog_max({"tiers": T5, "max_thinking_level": ULTRA, "supports_reasoning": False}), OFF)
check("override beats family rule",
      catalog_max({"tiers": None, "max_thinking_level": MAX, "supports_reasoning": True}), MAX)

# 5. deepseek vendor clamp stays
check("deepseek override-ULTRA -> max",
      deepseek_sibling_wire(ULTRA, None, ULTRA), "max")
check("deepseek MAX -> max", deepseek_sibling_wire(MAX, None, None), "max")

# 6. undeclared xhigh walk-down unchanged
check("xhigh undeclared walks down", clamp_effort("xhigh", ["low", "medium", "high"]), "high")

print()
if fails:
    print(f"FAILED: {len(fails)} -> {fails}")
    raise SystemExit(1)
print("ALL PASS: override-wire chain semantics verified")
