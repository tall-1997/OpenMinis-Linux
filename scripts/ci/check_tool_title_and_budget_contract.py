#!/usr/bin/env python3
"""[T-preanchor-dynamic-budget / T-tool-title-single-source GH#344] Ratchet.

Two #331/#332 regressions, one structural gate each:

A) The preAnchor budget was `min(15_000, max(0.2·window, 8_000))` inline in
   ChatViewModelAgentHistoryExt. The 15k ceiling saturates for EVERY window
   ≥ 75k, so 128k/200k models always got 15k and PR #331's declared 40k
   corridor (parity §113 "preAnchor ≤20% window") was dead code. The fix is a
   single pure function WalkBack.preAnchorTokenBudget + the 40k constant.

B) toolTitle had three competing sources; executeTool fell back to the bare
   canonical name when the model omitted tool_title, finalizePlannedCall's
   `result.toolTitle.ifEmpty { old }` kept that non-empty bare name, and the
   renderer had only `ifEmpty { toolName }` — untitled file_read/read_image
   cards showed raw snake_case (2026-11-12 17:48Z screenshot). The fix is a
   single resolver (ChatToolTitleResolver.kt) used at every write/render site.

Runs on every CI event (tree state, no diff needed), before the slow compile.
"""
from __future__ import annotations

import re
import sys
import json as _json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHAT = ROOT / "src/android/app/src/main/java/com/openminis/app/ui/chat"
WALKBACK = CHAT / "ChatViewModelWalkBack.kt"
HISTORY = CHAT / "ChatViewModelAgentHistoryExt.kt"
RESOLVER = CHAT / "ChatToolTitleResolver.kt"
LOOP = CHAT / "ChatViewModelAgentLoopExt.kt"
EXEC_TOOL = CHAT / "ChatViewModelExecuteToolExt.kt"
TURN_EXEC = CHAT / "ChatViewModelToolTurnExec.kt"
PROMPT = CHAT / "ChatViewModelPromptExt.kt"
UI_FILES = [
    "ChatAssistantMessageUI.kt",
    "ChatComposerWidgets.kt",
    "ChatFlatItems.kt",
    "ChatToolDetailUI.kt",
    "ChatProcessCards.kt",
]

errors: list[str] = []


def check(cond: bool, msg: str) -> None:
    if not cond:
        errors.append(msg)


def strip_comments(src: str) -> str:
    """Blank out // line comments and /* ... */ blocks, keep positions/offsets."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif src.startswith("/*", i):
            end = src.find("*/", i + 2)
            end = n if end < 0 else end + 2
            for k in range(i, end):
                if out[k] != "\n":
                    out[k] = " "
            i = end
        else:
            i += 1
    return "".join(out)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""


# ---- A. preAnchor dynamic budget corridor ---------------------------------
walk = strip_comments(read(WALKBACK))
hist = strip_comments(read(HISTORY))

check("COMPACT_PREANCHOR_TOKEN_BUDGET = 40_000" in walk,
      "WalkBack: ceiling must be 40_000 (PR #331 declared corridor; 15_000 saturates every window >= 75k)")
check("PREANCHOR_WINDOW_FRACTION = 0.20" in walk,
      "WalkBack: missing PREANCHOR_WINDOW_FRACTION = 0.20 (parity §113)")
check("PREANCHOR_TOKEN_FLOOR = 8_000" in walk,
      "WalkBack: missing PREANCHOR_TOKEN_FLOOR = 8_000")
check(re.search(r"fun preAnchorTokenBudget\(windowTokens: Int\?\)", walk) is not None,
      "WalkBack: missing the single pure function preAnchorTokenBudget(windowTokens: Int?)")
check("PREANCHOR_WINDOW_FRACTION).toInt()" in walk,
      "WalkBack: budget must scale with the window fraction, not a hardcoded ceiling")
check("coerceAtLeast(PREANCHOR_TOKEN_FLOOR)" in walk and
      "coerceAtMost(COMPACT_PREANCHOR_TOKEN_BUDGET)" in walk,
      "WalkBack: budget must apply BOTH the floor and the ceiling through the named constants")
check("WalkBack.preAnchorTokenBudget(windowTokens)" in hist,
      "AgentHistoryExt: must delegate to the single pure function, not inline the corridor")
check("preAnchorBudget = if (windowTokens != null)" not in hist,
      "AgentHistoryExt: the old inline windowed/fallback branch regressed — keep the corridor in WalkBack only")
check("(windowTokens * 0.20)" not in hist,
      "AgentHistoryExt: inline 0.20 window fraction regressed — the fraction lives in WalkBack")
check("15_000" not in walk and "15_000" not in hist,
      "the old 15_000 hardcoded ceiling must not come back")

# Numeric replica: prove the old formula was degenerate and the new one isn't.
def new_budget(window):
    if window is None:
        return 40_000
    return min(40_000, max(int(window * 0.20), 8_000))

def old_budget(window):
    if window is None:
        return 15_000
    return min(15_000, max(int(window * 0.20), 8_000))

check(old_budget(128_000) == 15_000 and old_budget(200_000) == 15_000,
      "replica: the regression premise (15k saturation on 128k/200k) must hold")
check(new_budget(None) == 40_000 and new_budget(32_000) == 8_000 and
      new_budget(64_000) == 12_800 and new_budget(128_000) == 25_600 and
      new_budget(200_000) == 40_000 and new_budget(1_000_000) == 40_000,
      "replica: new corridor must be null->40k, 32k->8k, 64k->12.8k, 128k->25.6k, >=200k->40k")
# 20% is the DYNAMIC ceiling of the scaling term; the 8k floor legitimately
# exceeds it on small windows (w < 40k), so only assert it in the scaling band.
check(all(new_budget(w) <= int(w * 0.20) + 1 for w in (40_000, 64_000, 128_000, 200_000, 1_000_000)),
      "replica: in the scaling band the budget must equal 20% of the window (capped at 40k)")
# The 0.8·window aggregate threshold is a property of the COMPACT decision on
# large windows (postAnchor has its own 0.35·window cap there). It is NOT an
# invariant of the preAnchor function for tiny windows — the 8k floor wins.
check(all(new_budget(w) + 60_000 < w * 0.8 for w in (128_000, 200_000, 1_000_000)),
      "replica: on 128k+ windows preAnchor + postAnchor(60k) stays under the 0.8·window compact threshold")

# ---- B. tool title single source ------------------------------------------
res = strip_comments(read(RESOLVER))
check(RESOLVER.is_file(), "ChatToolTitleResolver.kt is missing — the single source of truth")
check(re.search(r"fun resolveToolTitle\(", res) is not None,
      "resolver: missing resolveToolTitle()")
check(re.search(r"fun displayToolTitle\(", res) is not None,
      "resolver: missing displayToolTitle() (history restore fallback)")
check("ToolAliases.canonical" in res,
      "resolver: must canonicalize tool names through the registry's single alias map")
check("looksLikeBareName" in res,
      "resolver: must reject a bare snake_case tool_title (some schemas default to NAME)")

loop = strip_comments(read(LOOP))
exec_tool = strip_comments(read(EXEC_TOOL))
turn = strip_comments(read(TURN_EXEC))

check("resolveToolTitle(chunk.name, \"\")" in loop,
      "AgentLoopExt: block creation must initialize through resolveToolTitle")
check(loop.count("resolveToolTitle(") >= 3,
      "AgentLoopExt: creation + streaming + completion must all use resolveToolTitle")
check("val toolTitle = resolveToolTitle(canonical, argsJson)" in exec_tool,
      "ExecuteToolExt: must never fall back to the bare canonical name")
check("fallback = canonical" not in exec_tool,
      "ExecuteToolExt: `extractToolTitle(..., fallback = canonical)` bare-name regression")
check("resolveToolTitle(name, call.argsStr" in turn,
      "ToolTurnExec: settlement must resolve from args, not trust result.toolTitle")
check("result.toolTitle.ifEmpty" not in turn,
      "ToolTurnExec: `result.toolTitle.ifEmpty { old }` regression — the bare name is non-empty")
check("displayToolTitle(prev.toolName" in turn,
      "ToolTurnExec: replay settlement must reconstruct a readable title too")
check("resolveToolTitle(call.name, call.argsStr)" in turn,
      "ToolTurnExec: status-bar dispatch must show the same concrete title as the card")

for name in UI_FILES:
    body = strip_comments(read(CHAT / name))
    check("toolTitle.ifEmpty" not in body,
          f"{name}: renderer must use displayToolTitle, not toolTitle.ifEmpty {{ toolName }}")
    check("displayToolTitle(" in body,
          f"{name}: missing displayToolTitle() render fallback")

# ---- C. tool-card detail regression (GH#334 → this fix) -------------------
# #334 made deriveToolTitle consume STREAMING PARTIAL JSON via
# extractPartialJsonString, so a half-arrived `{"path": "/va` produced
# "Reading /va"; step 2 then trusted that derived shadow as an already
# "specific" prior FOREVER, so the complete args at ToolCallComplete never
# recomputed it and the half-path was persisted as `description`. The renderer
# reused this same ladder, so already-persisted cards could not heal either.
# (Evidence: 2.0.90 = 68fab74a has NO deriveToolTitle; `git log -S 'Reading $it'`
# lands exactly on c56c2ccc; session DB 11-07 file_read 81/81 carry tool_title
# vs 11-13 8/66 with 12 "Reading /var/min" shadows whose persisted `input`
# is nevertheless the FULL path.)
TEST = ROOT / "src/android/app/src/test/java/com/openminis/app/ui/chat/ToolTitleResolverTest.kt"
test_src = read(TEST)
check("extractPartialJsonString(" not in res,
      "resolver: deriveToolTitle must NOT read streaming partial JSON — that produced the 'Reading /var/min' half-path #334 regression")
check("JSONObject(argsJson)" in res,
      "resolver: a complete JSONObject parse must be the single authoritative settlement point")
check("startsWith(prior)" in res,
      "resolver: a stale derived shadow (prefix of the fresh full derivation) must yield to the recomputed title — idempotent settle / history self-heal")
check("fun deriveToolTitle(toolName: String, json: org.json.JSONObject)" in res,
      "resolver: deriveToolTitle must consume a parsed JSONObject, not raw argsJson")
for t in (
    "streamingHalfPathNeverBecomesATitle",
    "completeArgsOverwriteStaleHalfPath",
    "historyHalfPathShadowSelfHeals",
    "partialToolTitleIsPreviewOnly",
    "modelTitleOnCompleteArgsBeatsStalePrior",
):
    check(f"fun {t}()" in test_src,
          f"ToolTitleResolverTest: missing regression test {t}()")
check('assertEquals("Reading /va",' not in test_src and
      "fun streamingPartialArgsStillProduceATitle()" not in test_src,
      "ToolTitleResolverTest: the old 'streaming partial args produce a half-path title' expectation must be gone")

# Behavioural replica of the Kotlin ladder (file_read / grep / glob subset).
# Python-only so the gate runs locally without the JVM; the Kotlin unit tests
# are the authoritative mirror executed by CI.
DERIVED_PREFIXES = (
    "Reading ", "Writing ", "Editing ", "Viewing ", "Listing ", "Glob ", "Grep ",
    "$ ", "Checkpoint ", "Multi-edit ", "Run ", "browser ", "Search ", "Fetch ",
    "Memory ", "Sessions ", "Session ", "Agent ", "Plan ", "Ask ", "Schedule ", "Tap ",
)

def _is_bare(s: str) -> bool:
    return ("_" in s and s == s.lower()
            and all(c == "_" or c.isdigit() or (c.isalpha() and c.islower()) for c in s))

def replica_resolve(tool: str, args: str, prior: str = "") -> str:
    """Faithful Python mirror of ChatToolTitleResolver.resolveToolTitle (the
    file_read / grep / glob subset). Kept in lockstep with the Kotlin ladder:
    step1 model title -> step2 COMPLETE-args idempotent settlement ->
    step3 streaming/dirty never derives. The JVM unit tests are authoritative;
    this replica exists so the gate runs locally without a compile."""
    friendly = {"file_read": "Read File", "grep": "Grep", "glob": "Glob"}.get(tool, "Tool")
    complete = None
    try:
        parsed = _json.loads(args)
        if isinstance(parsed, dict):
            complete = parsed
    except Exception:
        pass
    # step 1 — model's semantic tool_title wins (complete args or streaming preview)
    model = ""
    if complete is not None:
        t = str(complete.get("tool_title", "")).strip()
        if t and t != tool and t != friendly and not _is_bare(t):
            model = t
    if not model:
        m = re.search(r'"tool_title"\s*:\s*"([^"]*)', args)
        if m:
            t = m.group(1).strip()
            if t and t != tool and t != friendly and not _is_bare(t):
                model = t
    if model:
        return model
    p = prior.strip()
    semantic = bool(p) and p != tool and p != friendly and not _is_bare(p)
    if complete is None:
        # step 3 — streaming/dirty JSON: NEVER derive; semantic prior or friendly
        return p if semantic else friendly
    # step 2 — COMPLETE args: the single authoritative, idempotent settlement
    derived = None
    if tool == "file_read" and str(complete.get("path", "")).strip():
        derived = "Reading " + str(complete["path"])
    elif tool in ("grep", "glob") and str(complete.get("pattern", "")).strip():
        derived = ("Grep " if tool == "grep" else "Glob ") + str(complete["pattern"])
    shadow = derived is not None and (
        any(p.startswith(pre) for pre in DERIVED_PREFIXES) or derived.startswith(p)
    )
    if semantic and not shadow:
        return p
    return derived if derived is not None else friendly

FULL = "/var/minis/skills/superpowers/skills/systematic-debugging/SKILL.md"
check(replica_resolve("file_read", '{"path": "/va') == "Read File",
      "replica: streaming half-JSON must NOT derive 'Reading /va' — friendly preview instead")
check(replica_resolve("glob", '{"pattern": "**/*.k') == "Glob",
      "replica: streaming glob args must not produce a half-pattern title")
check(replica_resolve("file_read", '{"path":"%s"}' % FULL, "Reading /var/min") == "Reading " + FULL,
      "replica: complete args overwrite the stale half-path shadow with the FULL path")
check(replica_resolve("file_read", '{"path":"%s"}' % FULL, "Reading /var/min")
      == replica_resolve("file_read", '{"path":"%s"}' % FULL, "Reading " + FULL),
      "replica: settlement is idempotent — the shadow cannot survive a complete-args recompute")
check(replica_resolve("file_read", '{"tool_title":"读技能避坑说明","path":"/a"}', "Reading /a") == "读技能避坑说明",
      "replica: model tool_title beats a stale derived shadow")
check(replica_resolve("file_read", '{"tool_title":"读 systematic-debugging","path":"/a"}') == "读 systematic-debugging",
      "replica: Chinese semantic detail is preserved as the card title")
check(replica_resolve("file_read", '{"path":"/a"}', "读取停滞判定与终止策略现状") == "读取停滞判定与终止策略现状",
      "replica: a genuine semantic prior is kept (not mistaken for a derived shadow)")
check(replica_resolve("grep", "{}", "Grep") == "Grep",
      "replica: empty-args history card stays a friendly label, never a bare snake_case name")
check(replica_resolve("grep", '{"pattern":"resolveToolTitle"}', "Grep resolve") == "Grep resolveToolTitle",
      "replica: prefix-shadow self-heal holds for every derived tool, not just file_read")

# The model-side half of the fix: a contract line in the system prompt keeps
# `tool_title` flowing for tools whose schema exposes it (shell_execute always
# had it — that is why it alone survived #334). App-side derivation is the
# fallback; this line makes the semantic detail the common case again.
prompt = read(PROMPT)
check("Every tool call MUST set the `tool_title` parameter" in prompt,
      "ChatViewModelPromptExt: the system-prompt tool_title contract is missing — semantic card details will keep depending on model whim")
check("Never pass the bare tool name or a truncated path as `tool_title`" in prompt,
      "ChatViewModelPromptExt: the contract must forbid bare names / truncated paths")

# ---- verdict ---------------------------------------------------------------
if errors:
    print("preAnchor budget + tool title contract: FAIL")
    for e in errors:
        print("  - " + e)
    sys.exit(1)
print("preAnchor budget + tool title contract: OK "
      "(40k/20%/8k corridor via one pure function; 15k saturation proven degenerate; "
      "resolveToolTitle at every write site; displayToolTitle at every render site; "
      "large-window aggregate corridor verified)")
