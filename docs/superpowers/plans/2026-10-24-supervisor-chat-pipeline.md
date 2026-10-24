# Supervisor-Worker Chat Pipeline Implementation Plan (PR #2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task in this session (no subagents — session rule). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the PR #1 protocol core into the live chat pipeline: a writable Worker seat, a Worker model slot + supervisor turn cap in settings, run-record persistence with INTERRUPTED recovery, and the detached two-seat session runner — all machinery, no new UI entry (PR #3).

**Architecture:** Four new pure-logic files in `tools/` (seats, prompts, run store) plus one runner file `ui/chat/ChatViewModelSupervisorExt.kt` that reuses the group-chat bubble pipeline (`speakModel` / `upsertGroupBubble` / `persistGroupUtterance`, relaxed from `private` to `internal`) and the sub-agent execution stack (`SubAgentRunner.run` inside `WritePathGuard.withPaths` + `SubAgentLane`). The session runs detached on `viewModelScope.launch(SupervisorJob() + Dispatchers.Default)` — the launch-detached-batch precedent — so the main chat is never blocked. Live state lives in a `SupervisorSessionHost` singleton keyed by session id (the GoalSheetSignal precedent: zero new fields on `ChatViewModel.kt`, which is at its 6292-line ceiling).

**Tech Stack:** Kotlin, kotlinx.serialization JSON, JUnit4 (`org.junit.Assert`), repo-local static gates (`scan_kt.py`, `check_kdoc3.py`, `check_symbols.py`, `check_architecture_policy.py`, `check_cjk_literals.py`), CI compile+test as the only true compiler.

**Spec:** `docs/superpowers/specs/2026-10-24-supervisor-worker-loop-design.md` (v2) §3.2–3.8; protocol core already merged as `tools/SupervisorChat.kt` (PR #1, b61a8aca).

## Global Constraints

- Local has no kotlinc: "run tests" = (a) local static gates green, (b) CI green after push. Never claim compile locally.
- `ChatViewModel.kt` is at ceiling (6292) — zero new lines in it. `ChatViewModelGroupChatExt.kt` is at ceiling (820) — only `private`→`internal` visibility word changes, zero line growth.
- CJK gate watches `ui/` only: new `ui/chat/ChatViewModelSupervisorExt.kt` must have ZERO Chinese string literals (comments OK); user-visible text comes from existing `R.string.group_chat_*` keys. `tools/` files may carry Chinese prompt literals (GroupChat.kt precedent).
- No new `R.string` keys in this PR (l10n ×17 is PR #3). No new tool registered in `AgentTools` (`check_announced_tools.py` untouched). No `PromptExt` fragment in this PR (a fragment announcing an unreachable mode is dead text; it lands with the PR #3 entry).
- Worker seat per-round turn budget reuses existing `subagentMaxTurns` (spec §3.3). Session-level cap is the NEW `supervisorMaxWorkerTurns` (default 50, clamp 1..200, spec §3.7).
- Model resolution order (spec §3.7, locked): instruction-level `model` param > `workerModelEntryId` slot > session model. Blank slot = session model = current behavior (regression-safe).
- User message during a live session routes to the inbox and fires NO model turn (spec §3.6 single command chain; group-chat interception precedent at `ChatViewModelSendExt.kt:371`).
- Worker seat never spawns sub-agents (spec §3.3 nesting ban — enforced by `SubAgentKind.blocks(WORKER, …)` + `SubAgentLane` context).

## Review Focus

1. Worker seat surface keeps `file_write`/`file_edit`/`shell_execute` but drops every spawn tool, `cronjob`, and `ask_user_question` — pinned in Task 1.
2. Supervisor seat surface is the established PLAN recon surface (keeps `file_read`/`grep`/`glob`/`code_graph`/`shell_execute` recon; drops `file_write`/`file_edit` + all control tools) and the runner wraps it in `WritePathGuard` NO_WRITE — pinned in Task 1.
3. Model resolution: requested > slot > session; blank slot falls back to session model — pinned in Task 2.
4. A stale RUNNING record from a dead process reads as INTERRUPTED; a record created by THIS process never self-interrupts on read — pinned in Task 3.
5. Round digests are capped (instruction 200 / report 500 chars) so a 50-round record stays a bounded file — pinned in Task 3.

---

### Task 1: Seat tool surfaces

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/tools/SupervisorSeats.kt`
- Test: `src/android/app/src/test/java/com/openminis/app/tools/SupervisorSeatsTest.kt`

**Interfaces:**
- Consumes: `SubAgentKind.blocks(kind, toolName)`, `AgentToolDefinition` (`data/model`).
- Produces: `object SupervisorSeats` with `fun workerTools(base: List<AgentToolDefinition>): List<AgentToolDefinition>` and `fun supervisorTools(base: List<AgentToolDefinition>): List<AgentToolDefinition>`.

- [ ] **Step 1: Write failing tests** — build a base list of `AgentToolDefinition` fixtures (name only, empty description/params) covering `file_write`, `file_edit`, `shell_execute`, `file_read`, `grep`, `glob`, `code_graph`, `spawn_agent`, `check_agent`, `cronjob`, `ask_user_question`, `browser_use`. Assert: `workerTools` keeps the first seven, drops the last five; `supervisorTools` keeps `file_read`/`grep`/`glob`/`code_graph`/`shell_execute`/`browser_use` (the PLAN recon surface), drops `file_write`/`file_edit` and all five control tools; both preserve base order; empty base → empty result.
- [ ] **Step 2: Local gates** — `python3 /tmp/scan_kt.py` + `python3 /tmp/check_kdoc3.py` on both files; expected pass.
- [ ] **Step 3: Implement** — `workerTools` = `base.filter { !SubAgentKind.blocks(SubAgentKind.WORKER, it.name) }`; `supervisorTools` = `base.filter { !SubAgentKind.blocks(SubAgentKind.PLAN, it.name) }` (the PLAN recon surface — shell recon included, file mutation tools already absent). KDoc on both: worker = nesting ban (no spawn/cron/ask), supervisor = review-with-evidence, mutation denied (runner additionally wraps the seat in `WritePathGuard` NO_WRITE).
- [ ] **Step 4: Commit** — `feat(supervisor): seat tool surfaces`

### Task 2: Worker model slot + supervisor turn cap

**Files:**
- Modify: `src/android/app/src/main/java/com/openminis/app/data/repository/MultiAgentSettings.kt`
- Modify: `src/android/app/src/main/java/com/openminis/app/data/repository/MultiAgentSettingsRepository.kt`
- Test: `src/android/app/src/test/java/com/openminis/app/data/MultiAgentSettingsTest.kt` (add cases)

**Interfaces:**
- Consumes: existing `MultiAgentSettings` clamp helpers pattern.
- Produces: `MultiAgentSettings.MIN_SUPERVISOR_TURNS = 1`, `MAX_SUPERVISOR_TURNS = 200`, `DEFAULT_SUPERVISOR_TURNS = 50`, `fun clampSupervisorTurns(v: Int): Int`, `fun resolveWorkerModelId(requested: String?, slotId: String, activeEntryId: String): String`; repository `val workerModelEntryId: StateFlow<String>` ("" = follow session), `fun setWorkerModelEntryId(id: String)`, `val supervisorMaxWorkerTurns: StateFlow<Int>`, `fun setSupervisorMaxWorkerTurns(v: Int)`; slot participates in `retainLive` (blanked when the model is deleted).

- [ ] **Step 1: Write failing tests** — `clampSupervisorTurns(0) == 1`, `(99) == 99`, `(201) == 200`, `(50) == 50`; `resolveWorkerModelId("req", "slot", "active") == "req"`, `(null, "slot", "active") == "slot"`, `(null, "", "active") == "active"`, `("  ", "", "active") == "active"` (blank requested treated as absent).
- [ ] **Step 2: Local gates** — same.
- [ ] **Step 3: Implement** — clamp mirrors `clampConcurrent`; resolve = `requested?.trim()?.takeIf { it.isNotEmpty() } ?: slotId.takeIf { it.isNotBlank() } ?: activeEntryId`. Repository: two prefs-backed `MutableStateFlow`s + setters, same shape as `subagentMaxTurns`; add worker id to the `retainLive` blanking pass.
- [ ] **Step 4: Commit** — `feat(supervisor): worker model slot and supervisor turn cap settings`

### Task 3: Run record store + INTERRUPTED recovery

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/tools/SupervisorRunStore.kt`
- Test: `src/android/app/src/test/java/com/openminis/app/tools/SupervisorRunStoreTest.kt`

**Interfaces:**
- Consumes: `SessionWorkspace`/`ExecutionCoordinator.ownerSessionId` (same file placement as `SubAgentBatchRegistry`), `processStartedAt` injection pattern.
- Produces: `object SupervisorRunStore` with `STATE_RUNNING/DONE/FAILED/INTERRUPTED/USER_HALTED` consts; `@Serializable data class RoundRecord(val n: Int, val instructionDigest: String, val reportDigest: String, val atMs: Long)`; `@Serializable data class RunRecord(val id: String, val sessionId: String, val objective: String, val state: String, val workerTurns: Int, val rounds: List<RoundRecord>, val doneSummary: String?, val createdAt: Long, val updatedAt: Long)`; `fun start(sessionId: String, context: Context?): RunRecord`; `fun appendRound(sessionId: String, context: Context?, n: Int, instructionDigest: String, reportDigest: String)`; `fun markTerminal(sessionId: String, context: Context?, state: String, summary: String? = null)`; `fun interrupted(sessionId: String, context: Context?): RunRecord?`; `fun clear(sessionId: String, context: Context?)`; internal `encode/decode`; `internal var processStartedAt`.

- [ ] **Step 1: Write failing tests** — `start` returns RUNNING record with objective + workerTurns 0; `appendRound` appends and bumps `updatedAt` + `workerTurns`; digests are capped at 200/500 chars (feed 1000-char strings, assert stored lengths); `markTerminal(DONE, summary)` sets state + summary; `interrupted()` on a RUNNING record seeded with `createdAt < processStartedAt` flips it to INTERRUPTED and returns it; with `createdAt >= processStartedAt` returns null and leaves state RUNNING; `encode`/`decode` round-trip preserves rounds + summary; `clear` empties the session.
- [ ] **Step 2: Local gates** — same.
- [ ] **Step 3: Implement** — mirror `SubAgentBatchRegistry` shape: per-session lock + memory map + JSON file `supervisor-runs/runs.json` under `SessionWorkspace.base(context.filesDir, owner)`, `MAX_RUNS = 10` newest-first, `context == null` → memory-only (tests). `interrupted()` reconciles on read: only a RUNNING record whose `createdAt < processStartedAt` flips to INTERRUPTED (persisted); this-process records are never touched. Digest capping inside `appendRound` (`take(200)` / `take(500)`).
- [ ] **Step 4: Commit** — `feat(supervisor): run record store with interrupted recovery`

### Task 4: Seat prompts

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/tools/SupervisorPrompts.kt`
- Test: `src/android/app/src/test/java/com/openminis/app/tools/SupervisorPromptsTest.kt`

**Interfaces:**
- Consumes: `SupervisorRunStore.RunRecord`, `LLMMessage` (`data/model`).
- Produces: `object SupervisorPrompts` with `fun supervisorSystem(displayName: String): String`, `fun firstTurn(objective: String, contextDigest: String, inbox: List<String>, recovery: String?): String`, `fun reviewTurn(objective: String, lastReport: String, acceptance: List<String>, inbox: List<String>, workerTurns: Int, maxWorkerTurns: Int, bounceNote: String?): String`, `fun bounceEnvelope(reason: String): String`, `fun bounceRubberStamp(acceptance: List<String>): String`, `fun capReached(objective: String, workerTurns: Int): String`, `fun recoveryIntro(record: RunRecord): String`, `fun contextDigest(messages: List<LLMMessage>, maxChars: Int): String`.

- [ ] **Step 1: Write failing tests** — `supervisorSystem` contains the literal `DONE:` protocol line, the envelope contract (`## Task` / `## Expected result`), and the rubber-stamp rule; `firstTurn` echoes objective verbatim, includes each inbox line, includes recovery text when non-null, omits the recovery section when null; `reviewTurn` includes lastReport, each acceptance term, `workerTurns`/`maxWorkerTurns` numbers, and the bounce note only when non-null; `recoveryIntro` includes round count and last report digest from the record; `contextDigest` formats `role: text` lines, drops blank contents, and caps total to `maxChars` keeping the TAIL (most recent context).
- [ ] **Step 2: Local gates** — same.
- [ ] **Step 3: Implement** — Chinese prompt copy (tools/ is outside the CJK gate; match `GroupChat.kt` tone). `supervisorSystem` states: you are the supervisor seat; decompose and instruct the worker; every instruction must carry the `## Task` + `## Expected result` envelope; review worker reports against the acceptance list; when done output a standalone line `DONE: <one-sentence summary>` that cites acceptance evidence; user interjections arrive via inbox — absorb them into the next instruction. `contextDigest`: join non-blank messages as `role: content` lines, then tail-cap by chars.
- [ ] **Step 4: Commit** — `feat(supervisor): seat prompts`

### Task 5: Session runner pipeline

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSupervisorExt.kt`
- Modify: `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelGroupChatExt.kt` (visibility relax only: `private`→`internal` on `speakModel`, `upsertGroupBubble`, `showGroupStatus`, `persistGroupUtterance`, `publishGroupNotice`, `removeGroupBubble`)
- Modify: `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSendExt.kt` (inbox routing hook, ~4 lines)

**Interfaces:**
- Consumes: Task 1–4 outputs; `SupervisorChat.validateInstruction/parseDone/TurnMachine/SupervisorInbox` (PR #1); `SubAgentRunner.run`, `WritePathGuard.withPaths`, `SubAgentLane`, `providerForModelEntry`, `agentHistorySnapshot`, group bubble helpers (relaxed to internal).
- Produces: `internal object SupervisorSessionHost` (live map keyed by session id: `isLive(sessionId)`, `push(sessionId, message)`, `halt(sessionId)`, internal `Live(objective, machine, inbox, job)`); `internal fun ChatViewModel.startSupervisorSession(objective: String): Boolean` (false when already live or no model); private `suspend fun ChatViewModel.runSupervisorLoop(...)`.

- [ ] **Step 1: Write the runner** — `startSupervisorSession`: guard `isLive`; resolve worker entry via `MultiAgentSettings.resolveWorkerModelId(null, repo.workerModelEntryId.value, activeEntryId)` → entry from `providerRepository.config.value.modelEntries` (fallback active entry; none → false); supervisor entry = active entry; `SupervisorRunStore.start`; create `Live` with `TurnMachine(repo.supervisorMaxWorkerTurns.value)` + fresh `SupervisorInbox`; launch detached (`viewModelScope.launch(SupervisorJob() + Dispatchers.Default)`); return true.
- [ ] **Step 2: Loop body** — each iteration: drain inbox; build prompt (`firstTurn` on iteration 0 with `contextDigest(agentHistorySnapshot().takeLast(30), 4000)` + `recoveryIntro(store.interrupted(...))` once, else `reviewTurn` with last report/acceptance/bounce note); supervisor speaks via `WritePathGuard.withPaths(listOf(WritePathGuard.NO_WRITE)) { speakModel(provider, SupervisorPrompts.supervisorSystem(...), prompt, SupervisorSeats.supervisorTools(...), Mutex(), thinkingLevel, stopWhenClosing = false) }`; render + persist supervisor bubble (`upsertGroupBubble` + `persistGroupUtterance` with `snapshotFor(entry)`). `validateInstruction`: Rejected → bounce counter +1 (≥3 → `markTerminal(FAILED)` + notice + exit, else `bounceNote = bounceEnvelope(reason)` and continue); Valid → `machine.onSupervisorMessage(text, acceptance)`; EXECUTING → worker turn; DONE → `markTerminal(DONE, verdict.summary)` + `publishGroupNotice` + exit; FAILED (rubber-stamp ×2) → terminal + exit; force-closed (cap) → one final `speakModel` with `capReached(...)` (no validation), `markTerminal(FAILED, text)` + notice + exit.
- [ ] **Step 3: Worker turn** — `WritePathGuard.withPaths(instruction.writePaths) { withContext(SubAgentLane(laneId)) { SubAgentRunner.run(provider = workerProvider, modelDisplayName = workerEntry.model.displayName, userPrompt = instruction raw message text, role = null, roleContext = null, skillsHint = null, tools = SupervisorSeats.workerTools(makeAgentTools(supportsImageInput, visionGroupConfigured, memoryEnabled = false, subAgentEnabled = false) + makeSubAgentExtraTools()), maxTokens = (workerEntry.model.maxOutputTokens ?: 4096).coerceIn(256, 8192), executeTool = { name, json -> blocks check + GrepSourceTool special-case + generic `executeTool(name, json, "", mutableListOf(), "", "")` }, onStep/onUi = worker bubble status via `showGroupStatus`, kind = SubAgentKind.WORKER, writePaths = instruction.writePaths, maxTurns = multiAgentSettings.subagentMaxTurns.value, thinkingLevel = worker entry level) } }` → report; `machine.onWorkerReport()`; `SupervisorRunStore.appendRound`; render + persist worker bubble (speaker = worker model displayName). Worker LLMError → caught, report = "worker failed: …" (supervisor correction IS the retry mechanism this PR).
- [ ] **Step 4: Halt + cleanup** — `halt(sessionId)` cancels the Live job; the launch block's `finally` (in `withContext(NonCancellable)`) runs `machine.onUserHalt()`, `markTerminal(USER_HALTED)`, removes Live. CancellationException re-thrown after cleanup.
- [ ] **Step 5: SendExt hook** — in `sendMessage`'s launch, before `SessionConcurrencyManager.acquireSlot`: `if (SupervisorSessionHost.isLive(activeSessionId)) { SupervisorSessionHost.push(activeSessionId, <user text variable>); return@launch }` — user bubble already appended/persisted upstream; no model turn fires. English comment only.
- [ ] **Step 6: Local gates** — full suite: `scan_kt.py`, `check_kdoc3.py`, `check_symbols.py`, `check_architecture_policy.py`, `check_cjk_literals.py` (new ui file must be clean), `check_announced_tools.py`, `check_new_untranslated.py` (no new keys). Verify GroupChatExt diff is visibility-word-only (`git diff --word-diff`).
- [ ] **Step 7: Commit** — `feat(supervisor): detached two-seat session runner`

### Task 6: Ship PR #2

- [ ] **Step 1: Branch + push** — `feat/supervisor-chat-pipeline` from local main; `push_chain.py` flow per repo convention (base = local parent commit, remote-head = full 40-char sha).
- [ ] **Step 2: PR + CI green** — PR body cites spec §3.2–3.8 and lists the PR #1 outline deviations (prompt fragment + settings UI row + locale moved to PR #3; single-lane worker turn, parallel `tasks[]` lanes deferred). Poll PR Checks to green (latest run id only — old runs pollute the list).
- [ ] **Step 3: Squash merge + verify increment non-zero + delete remote+local branch + ff local main + tree hash == remote.**
- [ ] **Step 4: Poll main APK workflow** (five jobs, steps-level detail — job-level in_progress is cache-writeback noise). Then memory_write.
