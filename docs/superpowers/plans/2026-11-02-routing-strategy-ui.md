# Routing Strategy UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task (user standing rule: all runs in this session, no sub-agents, no local compile — syntax audit via kotlinc + /tmp JUnit-semantics drivers only, CI is the compile gate). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the five T-routing-strategy-x scheduling strategies (roundRobin / weighted / leastUsed / quotaWindow / smart) and their knobs (member weights, quota window, first-token timeout) in the group settings UI, and fix the two non-exhaustive `when` expressions that the new enum values break.

**Architecture:** Data layer + router + wiring are already done in this working tree (GroupRoutingState.pick, ModelGroup fields, GroupResolve/SendExt/AgentLoopExt/GroupChatExt, 22 pinned tests). This plan only adds the settings UI and the compile adapters. Pure UI helpers (badge labels, numeric parsing, quota reset math) live in one new file `ui/RoutingStrategyUi.kt` as top-level functions so they are unit-testable without Robolectric. All new copy goes through string resources (CJK ratchet) inserted in en-alphabetical order across 15 locales.

**Tech Stack:** Kotlin + Jetpack Compose (Material3, existing `SettingsSection` / `SettingsChoiceRow` / `SectionTextField` components — Apple-style design language already established by these components, no new icons or layout systems).

**Spec:** The working-tree diff of `[T-routing-strategy-x]` (GroupRoutingState.kt KDoc — including its explicit note that "the settings screen syncs the counter via syncQuotaWindow") is the spec this plan argues from.

## Global Constraints

- No new dependencies; reuse `SettingsSection`, `SettingsChoiceRow`, `SectionTextField`, `SettingsCardBlock` from `com.openminis.app.ui.components`.
- All user-visible strings via `R.string` (CJK ratchet: zero CJK literals in .kt files).
- String keys inserted alphabetically (en locale defines order) in all 15 locale files.
- Enum wire values are name-based (kotlinx.serialization default); never rename existing enum entries.
- `updateGroup` stores `group.copy(...)` wholesale — new ModelGroup fields survive any copy-based edit; no repository changes needed.
- Architecture ratchet: `app/src/main` new files must not trip `check_architecture_policy.py`; if `ModelGroupDetailScreen.kt` crosses a ceiling, trim comments before touching the baseline.
- Session rule: no local compile (no gradle). Verification = /tmp driver replicating JUnit cases over the old/new code + `kotlinc` syntax audit filtered to syntax errors (unresolved-reference cascades ignored) + four local gates + CI.

## Review Focus

1. Strategy switch while a strategy-specific section is visible: picking `weighted` then `fallback` must hide the weight editor without writing leftover weight state (weights persist in group config; only the editor is conditional).
2. Weight input garbage: "0", "-3", "abc", empty, "12.5" — clamp to >=1, fall back to 1, never crash, never store a string in `memberWeights: Map<String, Int>`.
3. Timeout input: empty string means "restore default" (null), not 0 ms (which would kill every first-token wait); "0" is rejected the same way.
4. Quota edits must take effect immediately in the running process: after saving total/period, call `GroupRoutingState.syncQuotaWindow(groupId, newRemaining)` so the in-memory counter does not keep serving the stale value until restart.
5. Non-exhaustive `when` compile breaks: `ChatModelPickerSheet.kt:228` and `ModelGroupsScreen.kt:881` must become exhaustive (or route through a shared helper) the same commit that added the enum values.
6. Weight editor rows: row tap must not steal focus from the text field, and the save-on-focus-loss must not fire with stale text when the user switches strategy (edit text state is the source of truth; focus loss reads it, not the old group snapshot).

---

### Task 1: Shared UI helpers + badge compile adapter

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/ui/RoutingStrategyUi.kt`
- Create: `src/android/app/src/test/java/com/openminis/app/ui/RoutingStrategyUiTest.kt`
- Modify: `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatModelPickerSheet.kt:228-230`
- Modify: `src/android/app/src/main/java/com/openminis/app/ui/settings/ModelGroupsScreen.kt:881-884`
- Modify: `src/android/app/src/main/java/com/openminis/app/debug/ProviderMutationMethods.kt:595-598`

**Interfaces:**
- Produces:
  - `fun RoutingStrategyUi.shortBadge(strategy: RoutingStrategy): String` — "FB"/"LB"/"RR"/"WT"/"LU"/"QW"/"SM" (fallback/loadBalance keep today's values).
  - `fun RoutingStrategyUi.parseWeight(text: String): Int` — trim, toIntOrNull, coerceAtLeast(1); invalid → 1.
  - `fun RoutingStrategyUi.parseTimeoutMs(text: String): Int?` — trim, blank → null, toIntOrNull, <=0 → null.
  - `fun RoutingStrategyUi.nextQuotaReset(nowMs: Long, periodMs: Long): Long` — nowMs + periodMs.
  - `fun RoutingStrategyUi.quotaRemainingAfterSave(text: String, total: Int): Int` — parseWeight(text).coerceAtMost(total).

- [ ] **Step 1: Write the failing tests** (JUnit semantics; repo test file first)

```kotlin
class RoutingStrategyUiTest {
    @Test fun `shortBadge keeps FB and LB and covers all five new strategies`() {
        assertEquals("FB", RoutingStrategyUi.shortBadge(RoutingStrategy.fallback))
        assertEquals("LB", RoutingStrategyUi.shortBadge(RoutingStrategy.loadBalance))
        assertEquals("RR", RoutingStrategyUi.shortBadge(RoutingStrategy.roundRobin))
        assertEquals("WT", RoutingStrategyUi.shortBadge(RoutingStrategy.weighted))
        assertEquals("LU", RoutingStrategyUi.shortBadge(RoutingStrategy.leastUsed))
        assertEquals("QW", RoutingStrategyUi.shortBadge(RoutingStrategy.quotaWindow))
        assertEquals("SM", RoutingStrategyUi.shortBadge(RoutingStrategy.smart))
    }
    @Test fun `parseWeight clamps junk to one`() {
        assertEquals(1, RoutingStrategyUi.parseWeight(""))
        assertEquals(1, RoutingStrategyUi.parseWeight("abc"))
        assertEquals(1, RoutingStrategyUi.parseWeight("0"))
        assertEquals(1, RoutingStrategyUi.parseWeight("-3"))
        assertEquals(1, RoutingStrategyUi.parseWeight("12.5"))
        assertEquals(7, RoutingStrategyUi.parseWeight(" 7 "))
    }
    @Test fun `parseTimeoutMs maps blank and nonpositive to null`() {
        assertNull(RoutingStrategyUi.parseTimeoutMs(""))
        assertNull(RoutingStrategyUi.parseTimeoutMs("   "))
        assertNull(RoutingStrategyUi.parseTimeoutMs("0"))
        assertNull(RoutingStrategyUi.parseTimeoutMs("-10"))
        assertEquals(45_000, RoutingStrategyUi.parseTimeoutMs("45"))
    }
    @Test fun `quotaRemainingAfterSave caps at total`() {
        assertEquals(5, RoutingStrategyUi.quotaRemainingAfterSave("9", 5))
        assertEquals(3, RoutingStrategyUi.quotaRemainingAfterSave("3", 5))
        assertEquals(1, RoutingStrategyUi.quotaRemainingAfterSave("junk", 5))
    }
    @Test fun `nextQuotaReset is now plus period`() {
        assertEquals(1_000_000L + 3_600_000L, RoutingStrategyUi.nextQuotaReset(1_000_000L, 3_600_000L))
    }
}
```

- [ ] **Step 2: Verify RED with /tmp driver**
  - Write `/tmp/routing_strategy_ui_driver.kt` (plain Kotlin `main`) replicating these asserts against a stub that throws `UninitializedPropertyAccessException`; run `kotlinc -script` or compile+run with kotlin stdlib on classpath; expected: all asserts fail with "not initialized".

- [ ] **Step 3: Implement `RoutingStrategyUi` in `ui/RoutingStrategyUi.kt`**
  - `object RoutingStrategyUi` with the five top-level-style functions (defined inside the object, matching the test call sites). Bodies are the trivial maps/parses the tests pin.

- [ ] **Step 4: Wire the two UI `when` sites + debug mapper**
  - `ChatModelPickerSheet.kt`: replace `val strategyLabel = when (group.strategy) { fallback -> "FB"; loadBalance -> "LB" }` with `val strategyLabel = RoutingStrategyUi.shortBadge(group.strategy)`. (Line 307 keeps its `== RoutingStrategy.fallback` condition unchanged.)
  - `ModelGroupsScreen.kt:881`: extend the exhaustive `when` with 5 new branches mapping to the 5 new string keys from Task 2 (use the SAME `R.string.model_group_detail_*` labels the detail screen uses); fallback/loadBalance branches unchanged.
  - `ProviderMutationMethods.kt`: extend the debug `when` with `"roundRobin" -> RoutingStrategy.roundRobin` etc. (5 lines, else stays fallback).

- [ ] **Step 5: Verify GREEN with /tmp driver**
  - Point the driver's imports at the real `RoutingStrategyUi.kt` (compile both files together with `kotlinc`, run `main`); expected: all asserts pass. Also compile the two modified screen files via `kotlinc -d /tmp/x.jar` alone and diff syntax-error counts against the pre-change versions (expect no NEW syntax errors; unresolved references ignored).

- [ ] **Step 6: Commit**

```bash
git add src/android/app/src/main/java/com/openminis/app/ui/RoutingStrategyUi.kt \
        src/android/app/src/test/java/com/openminis/app/ui/RoutingStrategyUiTest.kt \
        src/android/app/src/main/java/com/openminis/app/ui/chat/ChatModelPickerSheet.kt \
        src/android/app/src/main/java/com/openminis/app/ui/settings/ModelGroupsScreen.kt \
        src/android/app/src/main/java/com/openminis/app/debug/ProviderMutationMethods.kt
git commit -m "routing: exhaustive strategy badges + parse helpers for strategy settings"
```

(Note: this commit references the 5 new detail-label string keys Task 2 adds — land Task 2 before pushing so CI compiles; local kotlinc audit acknowledges unresolved refs.)

---

### Task 2: Detail-screen strategy picker + strings

**Files:**
- Modify: `src/android/app/src/main/java/com/openminis/app/ui/settings/ModelGroupDetailScreen.kt:212-243`
- Modify: `src/android/app/src/main/res/values*/strings.xml` (15 locales)

**Interfaces:**
- Consumes: `RoutingStrategyUi` from Task 1 (only badge; labels come from resources).
- Produces: 10 new string keys (en order — insert alphabetically per locale):
  - `model_group_detail_least_used` = "Least Used"
  - `model_group_detail_quota_window` = "Quota Window"
  - `model_group_detail_round_robin` = "Round Robin"
  - `model_group_detail_smart` = "Smart"
  - `model_group_detail_weighted` = "Weighted"
  - `model_group_detail_routing_least_used_footer` = "Always sends to the member this session has used the fewest times."
  - `model_group_detail_routing_quota_window_footer` = "Primary member serves until the window quota runs out, then the next member takes over until it resets."
  - `model_group_detail_routing_round_robin_footer` = "Rotates through members one call at a time, in order."
  - `model_group_detail_routing_smart_footer` = "Light requests go to the last (budget) member; long or complex requests go to the first (strong) member."
  - `model_group_detail_routing_weighted_footer` = "Picks members randomly with probability proportional to their weights."

- [ ] **Step 1: Add the 10 en keys in alphabetical position** in `values/strings.xml`; commit nothing yet.

- [ ] **Step 2: Translate + insert into the other 14 locales** at the en-defined alphabetical anchors (reuse `/var/minis/workspace/insert_engine_strings.py` pattern: parse en anchors, insert per locale).

- [ ] **Step 3: Extend the Routing Strategy section** in `ModelGroupDetailScreen.kt`
  - footer `when (strategy)` gains 5 branches (the `_footer` keys above).
  - Add 5 `SettingsChoiceRow`s after loadBalance (divider handling: keep `showDivider = false` on the last row — smart — and drop it from loadBalance's row, matching existing chain style). Each `onSelect` mirrors the existing pattern: set local `strategy` state + `providerRepository.updateGroup(group.copy(strategy = X))`.

- [ ] **Step 4: Verify**
  - `check_cjk_literals.py`, `check_string_refs.py` (all 15 locales), `check_architecture_policy.py` green; `kotlinc` audit of the screen file (no new syntax errors).

- [ ] **Step 5: Commit**

```bash
git add src/android/app/src/main/java/com/openminis/app/ui/settings/ModelGroupDetailScreen.kt src/android/app/src/main/res
git commit -m "routing: offer all five scheduling strategies in group settings"
```

---

### Task 3: Strategy knob editors (weights / quota / timeout)

**Files:**
- Modify: `src/android/app/src/main/java/com/openminis/app/ui/settings/ModelGroupDetailScreen.kt` (after the Fallback Trigger section, before Members header)
- Modify: `src/android/app/src/main/res/values*/strings.xml` (15 locales)

**Interfaces:**
- Consumes: `RoutingStrategyUi.parseWeight / parseTimeoutMs / nextQuotaReset / quotaRemainingAfterSave`, `GroupRoutingState.syncQuotaWindow(groupId, remaining)`.
- Produces: 11 new string keys:
  - `model_group_detail_first_token_timeout` = "First Token Timeout"
  - `model_group_detail_first_token_timeout_footer` = "Seconds to wait for the first response event before timing out. Empty keeps the default budget."
  - `model_group_detail_first_token_timeout_hint` = "Seconds (empty = default)"
  - `model_group_detail_member_weights` = "Member Weights"
  - `model_group_detail_member_weights_footer` = "Relative probability for weighted routing. Higher numbers serve more often. Minimum is 1."
  - `model_group_detail_quota_settings` = "Quota Window"
  - `model_group_detail_quota_settings_footer` = "Sends allowed before the primary member is skipped until the window resets."
  - `model_group_detail_quota_remaining` = "Remaining"
  - `model_group_detail_quota_total` = "Total"
  - `model_group_detail_quota_period` = "Window length (minutes)"
  - `model_group_detail_weight_default_hint` = "weight" (hint shown in each weight field; value text prefilled)

- [ ] **Step 1: RED — write the /tmp driver for the two pure pieces this task adds**
  - A `computeQuotaAfterEdit(remainingText, totalText, periodMinutesText, nowMs): QuotaEdit?` decision function IS worth extracting to `RoutingStrategyUi` so the reset logic is testable: returns null (no-op) when any field parses invalid (remaining empty + total empty + period empty = nothing to do), else `QuotaEdit(remaining: Int, total: Int, periodMs: Long, resetAtMs: Long)` with remaining = parsed ?: existing, capped at total, periodMs = minutes*60_000, resetAt = now + periodMs. Pin: editing only "total" refills remaining to total; editing only "period" keeps remaining and moves resetAt; invalid remaining text keeps the stored remaining.
  - Add these cases to `RoutingStrategyUiTest.kt` in the repo (names: `computeQuotaAfterEdit refills remaining when total is edited`, `computeQuotaAfterEdit moves resetAt on period edit`, `computeQuotaAfterEdit ignores invalid remaining`, `computeQuotaAfterEdit is null when nothing parses`). Driver replicating them must fail first (RED) against the stub.

- [ ] **Step 2: GREEN — implement `computeQuotaAfterEdit`** in `RoutingStrategyUi.kt`
  - Signature: `fun computeQuotaAfterEdit(remainingText: String, totalText: String, periodMinutesText: String, nowMs: Long, stored: ModelGroup): QuotaEdit?` — stored supplies the fallback values; data class `QuotaEdit(val remaining: Int, val total: Int, val periodMs: Long, val resetAtMs: Long)` nested in `RoutingStrategyUi`.

- [ ] **Step 3: Weight editor section** (visible when `strategy == RoutingStrategy.weighted`)
  - `SettingsSection(header = member_weights, footer = member_weights_footer)` containing one `SettingsCardBlock`; inside, a row per resolved member (reuse the `memberBucketById` ordering): member display name (`config.modelEntries` lookup, fall back to entry id) + a `SectionTextField` prefilled with `group.memberWeights[id]?.toString() ?: "1"`, `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)`, width-constrained (weight field `Modifier.width(96.dp)`), singleLine. Local `weightTexts` state: `remember(group.id, memberIds)` map of entryId→String initialized from the group. `onFocusChanged { if (!it.isFocused) saveWeights() }` where `saveWeights()` builds `Map<String, Int>` via `parseWeight` and calls `providerRepository.updateGroup(group.copy(memberWeights = weights))` only when the map differs from `group.memberWeights`.
  - Empty text → weight 1 (parseWeight) and the field resets to "1" in the local map on save.

- [ ] **Step 4: Quota editor section** (visible when `strategy == RoutingStrategy.quotaWindow`)
  - Three `SectionTextField`s (Remaining / Total / Window length in minutes), all `KeyboardType.Number`, local text states keyed on `group.id`. Save on focus loss: call `computeQuotaAfterEdit`; when non-null, `providerRepository.updateGroup(group.copy(quotaWindowRemaining = e.remaining, quotaWindowTotal = e.total, quotaWindowPeriodMs = e.periodMs, quotaWindowResetAt = e.resetAtMs))` then `GroupRoutingState.syncQuotaWindow(group.id, e.remaining)` so the running counter matches immediately.

- [ ] **Step 5: First-token timeout section** (always visible, right after the Routing Strategy section)
  - `SettingsSection(header = first_token_timeout, footer = first_token_timeout_footer)` with one `SectionTextField`, placeholder `hint`, `KeyboardType.Number`, local text state keyed on `group.id` ("" when group.firstTokenTimeoutMs == null, else ms value). Save on focus loss: `parseTimeoutMs(text)`; `providerRepository.updateGroup(group.copy(firstTokenTimeoutMs = parsed))` only when `parsed != group.firstTokenTimeoutMs`.

- [ ] **Step 6: Strings — the 11 keys in en alphabetical order + 14 locales** (same scripted insertion as Task 2).

- [ ] **Step 7: Verify**
  - /tmp driver GREEN over real `RoutingStrategyUi` (old cases + the 4 new quota cases).
  - `kotlinc` syntax audit: ModelGroupDetailScreen.kt error count diff vs pre-change version — zero NEW syntax errors (unresolved references from `KeyboardOptions`/`KeyboardType` etc. are cascades, filtered).
  - Four gates: architecture / CJK / string refs / announced tools all green.

- [ ] **Step 8: Commit**

```bash
git add src/android/app/src/main/java/com/openminis/app/ui/settings/ModelGroupDetailScreen.kt \
        src/android/app/src/main/java/com/openminis/app/ui/RoutingStrategyUi.kt \
        src/android/app/src/test/java/com/openminis/app/ui/RoutingStrategyUiTest.kt \
        src/android/app/src/main/res
git commit -m "routing: weight, quota and first-token timeout editors in group settings"
```

---

### Task 4: Branch → PR → CI green → squash merge

- [ ] **Step 1:** `git checkout -b fix/routing-strategy-ui` from main; cherry-pick / rebuild the three commits on top of the working-tree core changes (core data + GroupRoutingState + wiring + 22 tests) in their own earlier commit `routing: five scheduling strategies + group knobs` (this tree's existing diff).
- [ ] **Step 2:** Run the four local gates; `git push -u origin fix/routing-strategy-ui` (push_gitdata.py tree-verify flow).
- [ ] **Step 3:** Open PR; poll `commits/<sha>/check-runs` until Compile / Unit tests / L10n gate green; fix red rounds by importing the missing pieces (history says: import cascades are the usual first-round kill).
- [ ] **Step 4:** Squash merge via PUT merge endpoint; verify merge-tree == PR head tree; DELETE branch (204); local `git branch -D` + ff to origin/main.

## Self-Review Notes

- Spec coverage: KDoc's "settings screen syncs the counter via syncQuotaWindow" → Task 3 Step 4. Non-exhaustive when sites → Task 1. All 5 strategies reachable in UI → Task 2. All 3 knob groups editable → Task 3.
- Type consistency: `QuotaEdit` nested in `RoutingStrategyUi`, referenced only by `computeQuotaAfterEdit` callers in Task 3; `shortBadge` used by Task 1 call sites only; ModelGroup field names match ProviderConfig.kt exactly (quotaWindowRemaining/Total/PeriodMs/ResetAt, memberWeights, firstTokenTimeoutMs).
- Proportion: plan is shorter than the four files it touches; function bodies are left to the implementer except the pinned parse/clamp math.
