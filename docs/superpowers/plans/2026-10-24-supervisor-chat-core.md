# Supervisor-Worker 会话核心逻辑 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task in this session (no subagents — session rule). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the pure-JVM core of the Supervisor-Worker chat: DONE line protocol, instruction envelope validation, turn state machine, user inbox — fully unit-tested before any pipeline/UI wiring.

**Architecture:** One new pure-logic file `SupervisorChat.kt` in `tools/` (no Android deps, no Compose, no LLM calls — the state machine is driven by caller-supplied strings). PR #1 of three; PR #2 (group-chat pipeline + worker model slot) and PR #3 (UI + slash entry + locale) get their own plans after this lands.

**Tech Stack:** Kotlin, JUnit4 (`org.junit.Assert`), repo-local static gates (`scan_kt.py`, `check_kdoc3.py`, `check_architecture_policy.py`), CI compile+test as the only true compiler.

**Spec:** `docs/superpowers/specs/2026-10-24-supervisor-worker-loop-design.md` (v2, all four decisions locked)

## Global Constraints

- Local has no kotlinc: every task's "run tests" step is (a) local static gates green, (b) CI green after push. Never claim compile locally.
- New file = new baseline entry is fine (ceiling only shrinks); run `check_architecture_policy.py --update-baseline` only if the gate complains.
- CJK gate: zero Chinese string literals in new source files (comments OK, string literals NOT — translate to English).
- KDoc: every public symbol in the new file gets KDoc; `check_kdoc3.py` + `/tmp/scan_kt.py` must pass.
- No new tool registered in `AgentTools` in this PR (that's PR #2) — so `check_announced_tools.py` is untouched.
- Default worker turn cap 50, clamp 1..200 (spec §3.7, user-locked).
- DONE protocol regex: `(?im)^DONE:\s*(.+)$` (spec §3.5).
- Instruction envelope requires `## Task` and `## Expected result` sections (spec §3.4, reuses `SubAgentBrief` section names).

## Review Focus

Five input classes the spec implies but tasks might not cover:

1. Supervisor outputs `DONE:` mid-paragraph (not line start) — must NOT terminate; only line-anchored DONE counts.
2. DONE summary present but references no acceptance criterion (rubber-stamp) — rejected once, second offense terminates as FAILED.
3. Worker turn cap hit while a DONE verdict is pending — cap wins, state = force-closed with unfinished list.
4. Instruction envelope with sections in wrong order or extra whitespace — still valid (order-free section detection).
5. Inbox drained between turns must be exactly-once (a message injected twice = duplicate instruction).

Each has a pinning test in the owning task below.

---

### Task 1: DONE line protocol

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/tools/SupervisorChat.kt`
- Test: `src/android/app/src/test/java/com/openminis/app/tools/SupervisorChatDoneTest.kt`

**Interfaces:**
- Produces: `object SupervisorChat` with `val DONE_LINE: Regex`, `data class DoneVerdict(val summary: String, val evidenceRefs: List<String>)`, `fun parseDone(text: String, acceptance: List<String>): DoneVerdict?`, `fun isRubberStamp(v: DoneVerdict, acceptance: List<String>): Boolean`

- [ ] **Step 1: Write failing tests** — `parseDone` returns verdict when text contains a line-anchored `DONE: summary`; returns null when DONE appears mid-sentence only; verdict carries the summary text; `isRubberStamp` true when no acceptance keyword appears in summary, false when summary references an acceptance term (case-insensitive substring match on any acceptance word ≥ 4 chars).
- [ ] **Step 2: Local gates** — `python3 /tmp/scan_kt.py` + `python3 /tmp/check_kdoc3.py` on both files; expected pass (test file compiles by inspection; true compile is CI).
- [ ] **Step 3: Implement** — regex constant, data class, two pure functions. Evidence matching: acceptance terms = acceptance strings split on whitespace, filtered to length ≥ 4, lowercased; verdict is rubber-stamp iff none appears in lowercased summary.
- [ ] **Step 4: Commit** — `feat(supervisor): DONE line protocol with rubber-stamp rejection`

### Task 2: Instruction envelope validation

**Files:**
- Modify: `tools/SupervisorChat.kt`
- Test: `src/android/app/src/test/java/com/openminis/app/tools/SupervisorChatInstructionTest.kt`

**Interfaces:**
- Consumes: `SubAgentBrief.TASK`, `SubAgentBrief.EXPECTED` section constants.
- Produces: `data class Instruction(val task: String, val acceptance: List<String>, val writePaths: List<String>)`, `sealed class InstructionCheck` with `object Valid : InstructionCheck()` (carries `Instruction`) and `data class Rejected(val reason: String) : InstructionCheck()`, `fun validateInstruction(raw: String): InstructionCheck`.

- [ ] **Step 1: Write failing tests** — envelope with Task+Expected sections → Valid with task body and acceptance lines (bullet lines under Expected); missing Task → Rejected naming the missing section; missing Expected → Rejected; sections in reversed order → still Valid; `write_paths:` bullet under Task → parsed into `writePaths` via `WritePathGuard.parse`; empty body → Rejected.
- [ ] **Step 2: Local gates** — same as Task 1.
- [ ] **Step 3: Implement** — section split on the two markers (order-free), bullet extraction (`- ` prefix), write_paths line routed through `WritePathGuard.parse`.
- [ ] **Step 4: Commit** — `feat(supervisor): instruction envelope validation`

### Task 3: Turn state machine + cap

**Files:**
- Modify: `tools/SupervisorChat.kt`
- Test: `src/android/app/src/test/java/com/openminis/app/tools/SupervisorChatTurnTest.kt`

**Interfaces:**
- Consumes: Task 1 `parseDone`.
- Produces: `enum class Phase { INSTRUCTING, EXECUTING, REVIEWING, DONE, FAILED, INTERRUPTED, USER_HALTED }`, `class TurnMachine(val maxWorkerTurns: Int)` with `fun onSupervisorMessage(text: String, acceptance: List<String>): Phase`, `fun onWorkerReport(): Phase`, `fun onUserHalt(): Phase`, `val workerTurns: Int`, `val rubberStampStrikes: Int`, `fun forceClosed(): Boolean`.

- [ ] **Step 1: Write failing tests** — fresh machine starts INSTRUCTING; `onSupervisorMessage` without DONE → EXECUTING (worker turn begins, `workerTurns` +1); `onWorkerReport` → REVIEWING; DONE verdict → DONE; rubber-stamp DONE → stays REVIEWING with strikes=1, second rubber-stamp → FAILED; `workerTurns` reaching cap on next worker-turn start → force-closed (Phase FAILED with cap reason exposed via `forceClosed()`); `onUserHalt` from any live phase → USER_HALTED; cap wins over pending DONE (Review Focus #3).
- [ ] **Step 2: Local gates** — same.
- [ ] **Step 3: Implement** — explicit phase enum + counter transitions; no coroutines, caller drives.
- [ ] **Step 4: Commit** — `feat(supervisor): turn state machine with hard cap`

### Task 4: User inbox (exactly-once drain)

**Files:**
- Modify: `tools/SupervisorChat.kt`
- Test: `src/android/app/src/test/java/com/openminis/app/tools/SupervisorChatInboxTest.kt`

**Interfaces:**
- Produces: `class SupervisorInbox` with `fun push(message: String)`, `fun drain(): List<String>`, `val size: Int`.

- [ ] **Step 1: Write failing tests** — push two, drain returns both in order and empties; second drain returns empty (exactly-once); blank pushes ignored; size reflects pending count.
- [ ] **Step 2: Local gates** — same.
- [ ] **Step 3: Implement** — synchronized list, drain = take-all.
- [ ] **Step 4: Commit** — `feat(supervisor): user inbox with exactly-once drain`

### Task 5: Ship PR #1

- [ ] **Step 1: Full local gate suite** — `scan_kt.py`, `check_kdoc3.py`, `check_symbols.py` (symbol→import), `check_architecture_policy.py`, CJK literal scan on new files.
- [ ] **Step 2: Branch + push** — `feat/supervisor-chat-core` from local main; `push_chain.py` flow per repo convention.
- [ ] **Step 3: PR + CI green** — PR body cites spec §3.4/3.5/3.6/3.7; poll PR Checks to green.
- [ ] **Step 4: Squash merge + verify increment non-zero + delete branch + ff local main.**

---

## PR #2 / PR #3 (outlined — detailed plans after #1 lands)

- **PR #2**: group-chat pipeline specialization (writable worker seat via `SubAgentRunner` tool surface + `WritePathGuard`), `workerModelEntryId` + `supervisorMaxWorkerTurns` in `MultiAgentSettingsRepository`, `SupervisorRunRecord` persistence + INTERRUPTED recovery, prompt fragment. Touches `ChatViewModelGroupChatExt` (new logic externalized to new files — ceiling discipline).
- **PR #3**: UI (bubbles, session header with `W n/50` counter, inbox badge, DONE summary into main chat), slash panel entry (no composer button), locale ×17, `PromptExt` fragment. Requires awesome-design-md + ui-ux-pro-max skills loaded first (Apple design language).
