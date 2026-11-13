#!/usr/bin/env python3
"""[T-thinking-ultra-manual] Python equivalence check for the three changed chains.

Mirrors the Kotlin logic 1:1 so the branch behavior is verified locally without
compiling (per the project rule: local = python verification, CI = compile).

Chains verified:
  1. selectableThinkingLevels / catalogMaxThinkingLevel — declared-set mapping
     incl. the new "ultra" rung.
  2. clampEffort — ladder incl. "ultra", snap-down-then-up.
  3. wireEffort + declared-ultra exception in the three resolver branches.
  4. effectiveMaxThinkingLevel — capability guard + override precedence.
  5. decoded() fallback → ULTRA.
"""

# ── ThinkingLevel enum (rank mirrors declaration order) ──────────────────────
OFF, LOW, MEDIUM, HIGH, XHIGH, MAX, ULTRA = range(7)
NAME = {OFF: "OFF", LOW: "LOW", MEDIUM: "MEDIUM", HIGH: "HIGH",
        XHIGH: "XHIGH", MAX: "MAX", ULTRA: "ULTRA"}

# ── Chain 1: catalog mapping ─────────────────────────────────────────────────
MAPPING = [("low", LOW), ("medium", MEDIUM), ("high", HIGH),
           ("xhigh", XHIGH), ("max", MAX), ("ultra", ULTRA)]


def selectable(declared, ladder="Distinct", last_distinct=None):
    if not declared:
        return []
    tiers = [lvl for wire, lvl in MAPPING if wire in {d.lower() for d in declared}]
    if ladder == "NoControl":
        return []
    if ladder == "CollapsesAbove":
        return [t for t in tiers if t <= last_distinct]
    return tiers


def catalog_max(declared, declared_rule_max=None, supports_reasoning=None):
    if supports_reasoning is False:
        return OFF
    sel = selectable(declared)
    if sel:
        return sel[-1]
    return declared_rule_max if declared_rule_max is not None else XHIGH


# ── Chain 2: clampEffort ─────────────────────────────────────────────────────
LADDER = ["none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"]


def clamp_effort(effort, values):
    if not values:
        return effort
    if effort in values:
        return effort
    want = LADDER.index(effort) if effort in LADDER else -1
    if want < 0:
        return effort
    declared = sorted((LADDER.index(v), v) for v in values if v in LADDER)
    if not declared:
        return effort
    below = [v for i, v in declared if i <= want]
    return below[-1] if below else declared[0][1]


# ── Chain 3: wireEffort + declared-ultra exception ───────────────────────────
def wire_effort(level):
    return {OFF: "low", LOW: "low", MEDIUM: "medium", HIGH: "high",
            XHIGH: "xhigh", MAX: "max", ULTRA: "max"}[level]


def declares_ultra(declared):
    return declared is not None and any(d.lower() == "ultra" for d in declared)


def resolver_effort(level, declared):
    if level == ULTRA and declares_ultra(declared):
        return "ultra"
    return wire_effort(level)


# ── Chain 4: effectiveMaxThinkingLevel ───────────────────────────────────────
def effective_max(override, supports_reasoning, declared, declared_rule_max=None):
    if supports_reasoning is False:
        return OFF
    return override if override is not None else catalog_max(declared, declared_rule_max)


# ── Chain 5: decoded ─────────────────────────────────────────────────────────
KNOWN = {"OFF": OFF, "LOW": LOW, "MEDIUM": MEDIUM, "HIGH": HIGH,
         "XHIGH": XHIGH, "MAX": MAX, "ULTRA": ULTRA}


def decoded(raw):
    return KNOWN.get(raw, ULTRA)


# ── assertions ────────────────────────────────────────────────────────────────
def check(label, actual, expected):
    status = "PASS" if actual == expected else "FAIL"
    print(f"[{status}] {label}: got {actual!r}, want {expected!r}")
    return actual == expected


ok = True

# Chain 1: declared ultra lifts ceiling + offers the rung
ok &= check("selectable full set incl ultra",
            selectable(["low", "medium", "high", "xhigh", "max", "ultra"]),
            [LOW, MEDIUM, HIGH, XHIGH, MAX, ULTRA])
ok &= check("catalog max reaches ULTRA",
            catalog_max(["low", "medium", "high", "xhigh", "max", "ultra"]),
            ULTRA)
ok &= check("sparse high-ultra keeps both",
            selectable(["high", "ultra"]), [HIGH, ULTRA])
ok &= check("case-insensitive Ultra",
            selectable(["high", "Max", "Ultra"]), [HIGH, MAX, ULTRA])

# Chain 1 regression: no ultra declared → unchanged behavior
ok &= check("legacy full ladder (no ultra) unchanged",
            selectable(["low", "medium", "high", "xhigh", "max"]),
            [LOW, MEDIUM, HIGH, XHIGH, MAX])
ok &= check("sparse high-max unchanged",
            selectable(["high", "max"]), [HIGH, MAX])
ok &= check("no declaration → empty (id-rule ceiling applies)",
            selectable(None), [])
ok &= check("non-reasoning → OFF ceiling",
            catalog_max(["low", "max"], supports_reasoning=False), OFF)

# Chain 2: clampEffort with the extended ladder
ok &= check("ultra exact hit passes through",
            clamp_effort("ultra", ["low", "medium", "high", "xhigh", "max", "ultra"]), "ultra")
ok &= check("ultra undeclared walks down to max",
            clamp_effort("ultra", ["low", "medium", "high", "xhigh", "max"]), "max")
ok &= check("xhigh on high-max set snaps to high (regression)",
            clamp_effort("xhigh", ["high", "max"]), "high")
ok &= check("ultra on high-only set snaps to high",
            clamp_effort("ultra", ["high"]), "high")
ok &= check("max on high-ultra sparse set walks down to high",
            clamp_effort("max", ["high", "ultra"]), "high")

# Chain 3: wire emission
ok &= check("ULTRA folds to max when undeclared",
            resolver_effort(ULTRA, ["low", "medium", "high", "xhigh", "max"]), "max")
ok &= check("ULTRA literal when declared",
            resolver_effort(ULTRA, ["low", "medium", "high", "xhigh", "max", "ultra"]), "ultra")
ok &= check("ULTRA literal case-insensitive",
            resolver_effort(ULTRA, ["high", "Max", "Ultra"]), "ultra")
ok &= check("MAX unchanged on declared-ultra model",
            resolver_effort(MAX, ["low", "medium", "high", "xhigh", "max", "ultra"]), "max")
ok &= check("HIGH unchanged", resolver_effort(HIGH, ["high", "max"]), "high")
ok &= check("no declaration → wireEffort passthrough",
            resolver_effort(ULTRA, None), "max")

# Chain 3 end-to-end: request → clamp on the wire
full_ultra_set = ["low", "medium", "high", "xhigh", "max", "ultra"]
ok &= check("e2e ULTRA on declared-ultra model",
            clamp_effort(resolver_effort(ULTRA, full_ultra_set), full_ultra_set), "ultra")
legacy_set = ["low", "medium", "high", "xhigh", "max"]
ok &= check("e2e ULTRA on legacy model clamps to max",
            clamp_effort(resolver_effort(ULTRA, legacy_set), legacy_set), "max")

# Chain 4: effectiveMaxThinkingLevel
ok &= check("override wins over catalog",
            effective_max(HIGH, None, ["low", "medium", "high", "xhigh", "max"]), HIGH)
ok &= check("override ULTRA honored",
            effective_max(ULTRA, True, ["low", "high"]), ULTRA)
ok &= check("capability guard: non-reasoning → OFF even with override",
            effective_max(ULTRA, False, ["low", "high"]), OFF)
ok &= check("null override → catalog ceiling",
            effective_max(None, True, ["high", "max"]), MAX)

# Chain 5: decoded
ok &= check("decoded known MAX", decoded("MAX"), MAX)
ok &= check("decoded known ULTRA", decoded("ULTRA"), ULTRA)
ok &= check("decoded unknown → ULTRA", decoded("SUPREME"), ULTRA)
ok &= check("decoded empty → ULTRA", decoded(""), ULTRA)

print()
print("ALL PASS" if ok else "FAILURES PRESENT")
raise SystemExit(0 if ok else 1)
