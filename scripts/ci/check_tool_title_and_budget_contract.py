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

# v337: the collapsed/detail renderers now use the ONE two-field settlement
# (presentToolCard + ToolCardLabel). The old invariant — "every UI file must
# call displayToolTitle()" — belonged to the collapsed-field era; accepting a
# file that still emits a bare `toolTitle.ifEmpty { toolName }` would undo the
# #331/#332 fix, so both forms are checked against the new contract.
for name in UI_FILES:
    body = strip_comments(read(CHAT / name))
    check("toolTitle.ifEmpty" not in body,
          f"{name}: renderer must not fall back to a bare snake_case name")
    check("displayToolTitle(" in body or "presentToolCard(" in body,
          f"{name}: renderer must use the single settlement point, not raw toolTitle")
    check("ToolCardLabel(" in body or "displayToolTitle(" in body,
          f"{name}: renderer must show the semantic title (v337 two-line or legacy single-line)")

# ---- C. tool-card semantic/argument separation (GH#334 → #336 → v337) ------
# #334 made deriveToolTitle consume STREAMING PARTIAL JSON via
# extractPartialJsonString, so a half-arrived `{"path": "/va` produced
# "Reading /va"; step 2 then trusted that derived shadow as an already
# "specific" prior FOREVER. #336 fixed that to a complete-args idempotent
# settlement but kept the THREE meanings collapsed into ONE persisted
# `toolTitle` string, so a card without a model `tool_title` still showed only
# a path — the user reported the regression again after #336 shipped.
# (Evidence: 2.0.90 = 68fab74a has NO deriveToolTitle; `git log -S 'Reading $it'`
# lands on c56c2ccc; 2026-11-13 device DB parts_json: file_read WITHOUT
# tool_title -> description="Reading /var/minis/workspace/facts.txt", the same
# tool WITH tool_title -> description="kan IAM shell page".)
TEST = ROOT / "src/android/app/src/test/java/com/openminis/app/ui/chat/ToolTitleResolverTest.kt"
test_src = read(TEST)
check("extractPartialJsonString(" not in res,
      "resolver: partial JSON must never fabricate a title/detail (#334 half-path regression)")
check("JSONObject(argsJson)" in res,
      "resolver: a complete JSONObject parse must be the single authoritative settlement point")
check("fun presentToolCard(" in res,
      "resolver: ONE settlement point must produce both title and detail")
check("fun resolveToolDetail(" in res,
      "resolver: argument detail must be an independent pure source, not a title")
check("startsWith(it)" in res or "DERIVED_LABEL_PREFIXES.any" in res,
      "resolver: a persisted derived shadow must self-heal into a semantic title")
for t in (
    "streamingHalfPathNeverBecomesATitle",
    "completeArgsOverwriteStaleHalfPath",
    "historyHalfPathShadowSelfHeals",
    "partialToolTitleIsPreviewOnly",
    "modelTitleOnCompleteArgsBeatsStalePrior",
    "untitledFileReadShowsThePathAsDetail",
    "aliasesRouteThroughTheSingleCanonicalMap",
):
    check(f"fun {t}()" in test_src,
          f"ToolTitleResolverTest: missing regression test {t}()")
check('assertEquals("Reading /va",' not in test_src and
      "fun streamingPartialArgsStillProduceATitle()" not in test_src,
      "ToolTitleResolverTest: the old 'streaming partial args produce a half-path title' expectation must be gone")

# Behavioural replica of the v337 Kotlin settlement (file_read / grep / glob /
# shell). Python-only so the gate runs locally without the JVM; the Kotlin
# unit tests are the authoritative mirror executed by CI.
DERIVED_PREFIXES = (
    "Reading ", "Writing ", "Editing ", "Viewing ", "Listing ", "Glob ", "Grep ",
    "$ ", "Checkpoint ", "Multi-edit ", "Run ", "browser ", "Search ", "Fetch ",
    "Memory ", "Sessions ", "Session ", "Agent ", "Plan ", "Ask ", "Schedule ", "Tap ",
)

def _is_bare(s: str) -> bool:
    return ("_" in s and s == s.lower()
            and all(c == "_" or c.isdigit() or (c.isalpha() and c.islower()) for c in s))

def _semantic(s: str, tool: str, friendly: str) -> bool:
    # Model's own `tool_title` is authoritative: it may legitimately start with
    # a verb ("Plan the fix", "Search related docs"). Derived-shadow self-heal
    # belongs ONLY to a persisted prior, applied separately in replica_present.
    return (bool(s) and s != tool and s != friendly and not _is_bare(s)
            and not s.startswith(("/", "http://", "https://")))

def _is_shadow(prior: str) -> bool:
    """Shape-based mirror of Kotlin isDerivedShadow(): a derived label's tail
    is a machine value (path/pattern/command/single token), a model title's
    tail is natural language (multi-word phrase or CJK). Prefix alone never
    condemns a legitimate 'Plan the fix' / 'Search related docs' title."""
    p = prior.strip()
    head = next((x for x in DERIVED_PREFIXES if p.startswith(x)), None)
    if head is None:
        return False
    tail = p[len(head):]
    if not tail:
        return True
    if any("\u4e00" <= ch <= "\u9fff" for ch in tail):
        return False
    if (tail.startswith(("/", ".", "~")) or "*" in tail or "://" in tail
            or any(c in tail for c in "|>&;")):
        return True
    return " " not in tail and "\t" not in tail or head == "$ "

def replica_present(tool: str, args: str, prior: str = "") -> tuple[str, str]:
    """Faithful Python mirror of presentToolCard(): (semantic title, full
    argument detail). Kept in lockstep with the Kotlin ladder. The JVM unit
    tests are authoritative; this replica lets the gate run without a compile."""
    friendly = {"file_read": "Read File", "grep": "Grep", "glob": "Glob",
                "shell_execute": "Execute Shell"}.get(tool, "Tool")
    complete = None
    try:
        parsed = _json.loads(args)
        if isinstance(parsed, dict):
            complete = parsed
    except Exception:
        pass
    # 1. model's semantic tool_title wins (complete args or streaming preview)
    model = ""
    if complete is not None:
        t = str(complete.get("tool_title", "")).strip()
        if _semantic(t, tool, friendly):
            model = t
    if not model:
        m = re.search(r'"tool_title"\s*:\s*"([^"]*)', args)
        if m:
            t = m.group(1).strip()
            if _semantic(t, tool, friendly):
                model = t
    # 2. a genuine semantic prior survives; derived shadows self-heal
    p = prior.strip()
    shadow = _is_shadow(p)
    semantic_prior = p if _semantic(p, tool, friendly) and not shadow else ""
    title = model or semantic_prior or friendly
    # 3. detail is INDEPENDENT of the title, from COMPLETE args only
    detail = ""
    if complete is not None:
        detail = str(complete.get("path") or complete.get("pattern") or
                     complete.get("command") or "")
        if tool == "shell_execute" and detail:
            detail = "$ " + detail
        if len(detail) > 160:
            detail = detail[:160] + "…"
    return title, detail

FULL = "/var/minis/skills/superpowers/skills/systematic-debugging/SKILL.md"
check(replica_present("file_read", '{"path": "/va')[0] == "Read File",
      "replica: streaming half-JSON never derives 'Reading /va'")
check(replica_present("glob", '{"pattern": "**/*.k')[0] == "Glob",
      "replica: streaming glob args never produce a half-pattern title")
check(replica_present("file_read", '{"path":"%s"}' % FULL, "Reading /var/min")[0] == "Read File",
      "replica: a persisted half-path shadow self-heals into the semantic label")
check(replica_present("file_read", '{"path":"%s"}' % FULL, "Reading /var/min")
      == replica_present("file_read", '{"path":"%s"}' % FULL, "Reading " + FULL),
      "replica: settlement is idempotent — the shadow cannot survive a complete-args recompute")
check(replica_present("file_read", '{"tool_title":"du ji neng bi keng shuo ming","path":"/a"}', "Reading /a")[0]
      == "du ji neng bi keng shuo ming",
      "replica: model tool_title beats a stale derived shadow")
check(replica_present("file_read", '{"path":"/a"}', "du qu ting zhi pan ding")[0]
      == "du qu ting zhi pan ding",
      "replica: a genuine semantic prior is kept (not mistaken for a derived shadow)")
check(replica_present("grep", "{}", "Grep")[0] == "Grep",
      "replica: empty-args history card stays a friendly label, never a bare snake_case name")
check(replica_present("grep", '{"pattern":"resolveToolTitle"}', "Grep resolve")[0] == "Grep",
      "replica: prefix-shadow self-heal holds for every derived tool, not just file_read")
# Shape-based detector: a model/semantic title colliding with a derived prefix
# ("Plan the fix", "Search related docs") is NOT a shadow; a real derived
# label with a machine-value tail IS. No prefix blacklist guesswork.
check(replica_present("agent_plan", '{"tool_title":"Plan the fix","title":"v337"}')[0]
      == "Plan the fix",
      "replica: model title beginning with a derived verb stays authoritative")
check(replica_present("file_read", '{"path":"/a"}', "Plan the fix")[0] == "Plan the fix",
      "replica: multi-word semantic prior is kept despite a colliding prefix")
check(replica_present("glob", '{"pattern":"**/*.kt"}', "Glob **/*.k")[0] == "Glob",
      "replica: pattern-shaped derived shadow self-heals")
# THE regression the user reported: no tool_title -> path was the whole line.
check(replica_present("file_read", '{"path":"/var/minis/workspace/facts.txt"}')
      == ("Read File", "/var/minis/workspace/facts.txt"),
      "replica: untitled file_read has a semantic headline AND the full path as a separate detail line")
check(replica_present("glob", '{"pattern":"**/*.kt"}')[1] == "**/*.kt",
      "replica: untitled glob exposes its pattern as detail, never as the headline")
check(replica_present("grep", '{"pattern":"resolveToolTitle"}')[1] == "resolveToolTitle",
      "replica: untitled grep exposes its pattern as detail, never as the headline")
check(replica_present("shell_execute", '{"tool_title":"cha kan yuan ma shu","command":"ls -la"}')
      == ("cha kan yuan ma shu", "$ ls -la"),
      "replica: shell keeps its model title AND shows the command as detail")

# The model-side half of the fix: a contract line in the system prompt keeps
# `tool_title` flowing for tools whose schema exposes it. App-side separation
# of title/detail is the fallback; this line keeps the semantic detail the
# common case for models that read the prompt.
prompt = read(PROMPT)
check("Every tool call MUST set the `tool_title` parameter" in prompt,
      "ChatViewModelPromptExt: the system-prompt tool_title contract is missing")
check("Never pass the bare tool name or a truncated path as `tool_title`" in prompt,
      "ChatViewModelPromptExt: the contract must forbid bare names / truncated paths")

# ---- D. semantic title / argument detail separation (v337) -----------------
# ROOT CAUSE (2026-11-13, user: "read card only shows a path, not the detail
# it used to show; grep/glob too"): resolveToolTitle collapsed THREE meanings
# into ONE persisted title field -- the model's semantic `tool_title`, a
# streaming preview, and an argument-derived summary ("Reading /full/path").
# When the model omitted tool_title, the argument value became the whole card
# line, so the card carried a path and NO semantic detail. #334/#336 only
# changed half-path -> full-path; the collapsed field itself survived, so the
# regression was reported again after #336 shipped.
#
# v337 separates the meanings at ONE settlement point:
#   ToolCardPresentation.title  = semantic label (model title > friendly name)
#   ToolCardPresentation.detail = full argument summary (path/pattern/command)
# and the collapsed row renders both, Apple-style (primary + secondary line).
# These structural checks are the local RED phase of the TDD cycle: the Kotlin
# unit tests run in cloud CI, this gate is the runnable local proxy.
pres = read(RESOLVER)
check("data class ToolCardPresentation" in pres,
      "v337: ToolCardPresentation(title, detail) missing -- the two meanings are still collapsed")
check(re.search(r"fun presentToolCard\(", pres) is not None,
      "v337: presentToolCard() single settlement point is missing")
check(re.search(r"fun resolveToolDetail\(", pres) is not None,
      "v337: argument detail has no independent pure source")
check(re.search(r"fun isDerivedShadow\(", pres) is not None,
      "v337: historical 'Reading /path' shadows must still self-heal")
pc = read(CHAT / "ChatProcessCards.kt")
check("ToolCardLabel(" in pc,
      "v337: collapsed process row must render title + detail as two Apple-style lines")
check((CHAT / "ToolCardLabel.kt").exists(),
      "v337: ToolCardLabel composable file is missing")
new_test = ROOT / "src/android/app/src/test/java/com/openminis/app/ui/chat/ToolCardPresentationTest.kt"
check(new_test.exists(),
      "v337: ToolCardPresentationTest.kt (TDD RED) is missing")

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
