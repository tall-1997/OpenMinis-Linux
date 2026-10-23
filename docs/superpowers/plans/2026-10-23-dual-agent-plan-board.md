# Plan-Mode Rollback + Live Plan Board Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Roll back PR #132's plan mode (read-only guard, approval gate, composer toggle) and ship what the product actually needs: the agent_plan todo tool plus a live board UI (sticky bar above the composer).

**Architecture:** The todo tool (AgentPlanTool + AgentPlanStore) stays; everything that exists only to serve plan mode dies with its entry point — no dormant machinery. The board snapshot prompt injection stays (it is todo-tool support, not mode support) and moves next to its data. The board UI is one new Compose file mounted in the composer column, following the PlanReviewCard visual idiom it replaces.

**Tech Stack:** Kotlin, Compose, existing AgentPlanStore (kotlinx.serialization).

**Spec:** /var/minis/workspace/taixu-plan-redesign.md (P1 board-UI section only; P2/P3 dropped by user decision).

## Global Constraints

- Architecture policy: ChatScreen.kt / ChatViewModel.kt / ChatViewModelSubAgentExt.kt are AT ceiling — net-negative or net-zero edits only; new logic in new files (<800 lines).
- No local compilation: symbol checker (untracked files + string-literal stripping), L10n gate (16 locales), arch policy, brace/KDoc/CJK scans pass before push; compile is cloud CI.
- Apple design language for the board UI (Surface + 16dp radius + surfaceContainer + outlineVariant — the PlanReviewCard idiom).
- All new user-visible strings in all 16 locales (de/es/fil/fr/in/ja/ko/ms/pl/pt-rBR/ro/ru/th/tr/zh/zh-rTW).

## Review Focus

- A corrupt board file must not crash the composer — the UI path degrades to an empty board (the store throws by design; the prompt path already wraps in runCatching).
- Removing plan-mode strings must not orphan any other locale reference.
- No symbol from the deleted set may survive anywhere (imports, KDoc references, test files, dispatch branches).
- The sticky bar must render nothing on an empty board (zero footprint when the todo tool is unused).
- Status changes must propagate live (version flow), including the all-done state.

---

## Task 1 — Delete the plan-mode feature set

- [ ] Delete files: PlanModeToggle.kt, PlanModeStore.kt, PlanModeGuard.kt, ExitPlanModeTool.kt, PlanReviewGate.kt, PlanReviewCard.kt, ChatViewModelPlanExt.kt.
- [ ] ChatScreen.kt: remove toggle call block + PendingPlanReviewCard mount; mount StickyPlanBar in the freed slot (net negative).
- [ ] ChatViewModelExecuteToolExt.kt: remove guard intercept block + exit_plan_mode dispatch branch.
- [ ] ChatViewModelPromptExt.kt: planFragment drops the mode branch, keeps the board snapshot (net negative).
- [ ] ChatViewModelEnsureSession.kt: drop PlanModeStore.migrate call.
- [ ] AgentTools.kt: drop ExitPlanModeTool.definition() registration.
- [ ] AgentPlanTool.kt: KDoc loses dead PlanModeStore references; gains boardSnapshotPrompt (moved from PlanModeStore).
- [ ] AgentPlanToolTest.kt: drop the mode-flag migration test block.
- [ ] Remove 9 strings (plan_mode_on/off, plan_review_*) from base + 15 locales.

## Task 2 — Live board UI

- [ ] New StickyPlanBar.kt (ui/chat): reads AgentPlanStore.version; nothing on empty/corrupt board; collapsed = current step + n/m + 4dp progress; expanded = full step list (status icons, strikethrough done); all-done state; haptic + animateContentSize.
- [ ] New strings plan_board_all_done + cd_plan_board_toggle in 16 locales.

## Task 3 — Verify + ship

- [ ] Arch policy, L10n gate, symbol checker (untracked + string-stripped), brace/KDoc/CJK scans.
- [ ] Branch → commit → push → PR → CI green → squash merge → verify increment → delete branch.
