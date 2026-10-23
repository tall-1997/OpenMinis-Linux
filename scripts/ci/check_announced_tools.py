#!/usr/bin/env python3
"""Announced-tool gate: the prompt may not name a tool the schema hides.

`AgentTools.makeAgentTools(includeLongTail = false)` sends a small *core*
schema plus `find_tools`; everything else is long-tail and only surfaces after
the model happens to search for the right keyword. That is fine for tools
nothing talks about — it breaks the moment a system-prompt fragment *names* a
tool:

  * the plan board fragment says "advance items with agent_plan" while
    `agent_plan` sits behind find_tools, so the board renders for the user, the
    model is instructed to move it, and the model has no schema to do it with.
    The board then freezes at whatever state it was last written in and the
    user watches a stale "step 2 of 4" forever;
  * the tool-list and "Scheduled tasks" fragments say "use the cronjob tool",
    and the memory fragment says "then the evolution tool to accept or reject",
    with the same result.

The codebase already states this invariant three times in `alwaysOn` comments
(mcp: "the prompt fragment announces MCP servers, so the native tool must never
be long-tail-gated away"; check_agent: "gating that tool behind find_tools
would strand the wave"). This gate makes it mechanical instead of tribal:

  every tool name that appears inside a prompt-source string literal must be
  added to the `alwaysOn` build set — unconditionally, or under the same flag
  that gates its fragment.

Conditional adds satisfy the gate (they are inside the build set); the report
prints each announced name with its add-site and gating condition so a reviewer
can check the condition matches the fragment's condition. No baseline file: the
rule is absolute, a newly announced long-tail tool is a defect, not debt.

Prompt sources are detected, not listed: outside `ui/` any file declaring a
`*Prompt*`/`*Fragment*`/`*Section*` builder is scanned in full; inside `ui/`
only `Prompt`/`Fragment` builders qualify, because composables reuse "Section"
for layout and their strings are captions, not model-facing text. Tool names
come from the registry itself (each `add(...definition())` resolved to the name
it emits), so prose words such as "accept" or "done" are never mistaken for
tools. Comments and char literals are skipped. A name that is prose rather than
an instruction goes in IGNORED_PHRASES with a reason.

Usage:
    python3 scripts/ci/check_announced_tools.py [--verbose]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "src" / "android" / "app" / "src" / "main" / "java" / "com" / "openminis" / "app"
AGENT_TOOLS = APP / "tools" / "AgentTools.kt"

# A file is a prompt source when it declares something named like a fragment
# builder. Broad on purpose: fragments live in ui/chat (system prompt), tools/
# (boardSnapshotPrompt), goal/ (goalContextPrompt), agent/ (SystemPromptBuilder)
# and data/repository (skills, MCP), and new ones keep appearing.
# Outside ui/: a Section builder is prompt text. Inside ui/: composables name
# layout pieces "...Section", so only Prompt/Fragment builders count there.
FRAGMENT_DECL = re.compile(r"\b(?:fun|val|var)\s+\w*(?:Prompt|Fragment|Section)\w*", re.IGNORECASE)
FRAGMENT_DECL_UI = re.compile(r"\b(?:fun|val|var)\s+\w*(?:Prompt|Fragment)\w*", re.IGNORECASE)
# Same idea, applied to an already-extracted declaration NAME.
FRAGMENT_NAME = re.compile(r"Prompt|Fragment|Section", re.IGNORECASE)
TOOL_NAME = re.compile(r"(?<![A-Za-z0-9_])([a-z][a-z0-9_]{2,})(?![A-Za-z0-9_])")

# Prose mentions that are not tool instructions. Each entry needs a reason so
# the list cannot grow silently.
IGNORED_PHRASES = {
    # "rules from the evolution pipeline" names the engine, not the tool; the
    # tool itself is announced (and required) by the memory fragment.
    "evolution pipeline": "names the engine, not the `evolution` tool",
}

# A mention only counts when the literal is a SENTENCE: announcements are prose
# ("advance items with agent_plan", "- cronjob: Create/list/remove ...").
# Single-token literals are permission sets and NAME constants — CollabRoles'
# T_READONLY whitelist and `const val NAME = "cronjob"` both look like mentions
# to a naive scanner and neither instructs the model to call anything.
def is_announcement(content: str) -> bool:
    return " " in content.strip()


def strip_noise(text: str) -> str:
    """Blank out comments and char literals, keeping string literals intact."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            out.append(" " * (end - i))
            i = end
        elif c == "/" and text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append(" " * (end - i))
            i = end
        elif c == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            out.append(" " * (j + 1 - i))
            i = j + 1
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(text[i:end + 3])
            i = end + 3
        elif c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i : j + 1])
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def string_literals(text: str) -> list[tuple[str, int]]:
    """Every string literal's (content, start offset); comments/chars skipped."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end
        elif c == "/" and text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
        elif c == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            i = j + 1
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            if end < 0:
                break
            out.append((text[i + 3 : end], i))
            i = end + 3
        elif c == '"':
            j, buf = i + 1, []
            while j < n and text[j] != '"':
                if text[j] == "\\":
                    buf.append(text[j + 1] if j + 1 < n else "")
                    j += 2
                    continue
                buf.append(text[j])
                j += 1
            out.append(("".join(buf), i))
            i = j + 1
        else:
            i += 1
    return out


def kotlin_files() -> list[Path]:
    return sorted(APP.rglob("*.kt"))


def owner_index(files: list[Path], bodies: dict[str, str]) -> dict[str, str]:
    """`object Foo` / `class Foo` -> the filename that declares it."""
    idx: dict[str, str] = {}
    for path in files:
        for m in re.finditer(r"\b(?:object|class)\s+([A-Za-z0-9_]+)", bodies[path.name]):
            idx.setdefault(m.group(1), path.name)
    return idx


def registry_tool_names(files: list[Path], consts: dict[str, str]) -> set[str]:
    """Names the registry actually emits: every `add(...)` in `val all`.

    Resolved from the definition call, not from string literals at large, so
    parameter enums ("accept", "done", "explore") never masquerade as tools.
    """
    text = AGENT_TOOLS.read_text(encoding="utf-8", errors="ignore")
    start = text.find("val all = buildList {")
    if start < 0:
        sys.exit("FATAL: could not find `val all = buildList {` in AgentTools.kt")
    block = braced_block(text, start)
    bodies = {p.name: p.read_text(encoding="utf-8", errors="ignore") for p in files}
    owners = owner_index(files, bodies)
    defs = definition_names(text)
    names: set[str] = set()
    for expr in re.findall(r"add\(((?:[A-Za-z0-9_]+\.)*[A-Za-z0-9_]+\([^)]*\))\)", block):
        parts = expr.split(".")
        fn = parts[-1].split("(")[0]
        if not parts[:-1]:
            # Top-level builder in AgentTools.kt itself: shellExecuteDefinition().
            if fn in defs:
                names.add(defs[fn])
            continue
        owner = parts[-2]
        body = bodies.get(owners.get(owner, ""), "")
        if fn == "definition":
            # `Foo.definition()` whose NAME constant const_map already resolved.
            if owner in consts:
                names.add(consts[owner])
            continue
        # Two definitions in one object (SessionLookupTool, ProductMediaTools).
        m = re.search(rf"\bfun\s+{re.escape(fn)}\s*\(", body)
        if m:
            nm = re.search(r'\bname\s*=\s*"([a-z_][a-z0-9_]*)"', body[m.end() : m.end() + 900])
            if nm:
                names.add(nm.group(1))
    # Aliases the model may be told about (shell_execute's twin, session tools).
    names.update(n for n in consts.values() if n in {"search_sessions", "read_session"})
    return {n for n in names if len(n) >= 3}


def braced_block(text: str, start: int) -> str:
    """The balanced `{...}` region starting at/after `start` (comments blind)."""
    clean = strip_noise(text)
    i = clean.index("{", start)
    depth, n = 0, len(clean)
    while i < n:
        if clean[i] == "{":
            depth += 1
        elif clean[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start : i + 1]
        i += 1
    sys.exit("FATAL: unbalanced build block")


def always_on_block(text: str) -> str:
    start = text.find("val alwaysOn = buildSet {")
    if start < 0:
        sys.exit("FATAL: could not find `val alwaysOn = buildSet {` in AgentTools.kt")
    return braced_block(text, start)


def const_map(files: list[Path]) -> dict[str, str]:
    """`Foo.NAME` / `Foo.CONST` / `Foo` -> the lowercase string it holds."""
    consts: dict[str, str] = {}
    for path in files:
        text = path.read_text(encoding="utf-8", errors="ignore")
        for obj in re.finditer(r"\b(?:object|class)\s+([A-Za-z0-9_]+)", text):
            body = text[obj.end() :]
            for c in re.finditer(r'const val ([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([a-z_][a-z0-9_]*)"', body):
                consts[f"{obj.group(1)}.{c.group(1)}"] = c.group(2)
                if c.group(1) == "NAME":
                    consts[obj.group(1)] = c.group(2)
    return consts


def definition_names(text: str) -> dict[str, str]:
    """`fooDefinition().name` -> the `name = "..."` inside that builder."""
    out: dict[str, str] = {}
    for m in re.finditer(r"\bfun\s+([A-Za-z0-9_]+)\s*\(", text):
        nm = re.search(r'\bname\s*=\s*"([a-z_][a-z0-9_]*)"', text[m.end() : m.end() + 900])
        if nm:
            out[m.group(1)] = nm.group(1)
    return out


def resolve_added_names(block: str, files: list[Path]) -> dict[str, str]:
    """tool name -> "line N (condition)" for every name the build set adds."""
    text = AGENT_TOOLS.read_text(encoding="utf-8", errors="ignore")
    consts = const_map(files)
    defs = definition_names(text)
    added: dict[str, str] = {}
    depth = 0
    conds: dict[int, str] = {}
    for lineno, raw in enumerate(block.splitlines(), 1):
        line = strip_noise(raw)
        cond = re.search(r"\bif\s*\(([^)]*)\)", line)
        opens, closes = line.count("{"), line.count("}")
        new_depth = depth + opens - closes
        if cond and opens:
            conds[new_depth] = cond.group(1).strip()
        # Brace-less `if (flag) add(X.NAME)` gates the names on its own line.
        inline_cond = cond.group(1).strip() if (cond and not opens) else ""
        names: set[str] = set(re.findall(r'"([a-z_][a-z0-9_]*)"', line))
        for ref in re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)\b", line):
            if ref in consts:
                names.add(consts[ref])
        for fn in re.findall(r"\b([A-Za-z0-9_]+)\(\)\.name\b", line):
            if fn in defs:
                names.add(defs[fn])
        scope = new_depth if opens else depth
        gate = conds.get(scope, "") or inline_cond
        for name in names:
            added.setdefault(name, f"alwaysOn:{lineno}" + (f" if {gate}" if gate else " (unconditional)"))
        depth = new_depth
        for d in [d for d in conds if d > depth]:
            del conds[d]
    return added


def prompt_sources(files: list[Path]) -> list[Path]:
    out = []
    for path in files:
        if path.name == "AgentTools.kt":
            continue  # registry descriptions travel with their own tool
        text = strip_noise(path.read_text(encoding="utf-8", errors="ignore"))
        pattern = FRAGMENT_DECL_UI if "ui" in path.parts else FRAGMENT_DECL
        if pattern.search(text):
            out.append(path)
    return out


def defining_files(files: list[Path]) -> dict[str, set[str]]:
    """tool name -> files that define it (NAME constant or `name = `).

    A tool's own file mentions its name in constants, error strings and result
    text; only its fragment builders are announcements. boardSnapshotPrompt's
    "advance items with agent_plan" must stay visible to the gate — it is the
    announcement that strands the board — while `"Error agent_plan: ..."` in the
    same file is a result string.
    """
    out: dict[str, set[str]] = {}
    for path in files:
        text = path.read_text(encoding="utf-8", errors="ignore")
        for name in re.findall(r'const val NAME\s*=\s*"([a-z_][a-z0-9_]*)"', text):
            out.setdefault(name, set()).add(path.name)
        for name in re.findall(r'\bname\s*=\s*"([a-z_][a-z0-9_]*)"', text):
            out.setdefault(name, set()).add(path.name)
    return out


def announced_mentions(files: list[Path], tools: set[str]) -> dict[str, list[str]]:
    defines = defining_files(files)
    found: dict[str, list[str]] = {}
    for path in prompt_sources(files):
        text = path.read_text(encoding="utf-8", errors="ignore")
        literals = string_literals(text)
        decls = [(m.start(), m.group(1)) for m in re.finditer(r"\b(?:fun|val|var)\s+([A-Za-z0-9_]+)", text)]
        for content, offset in literals:
            if not is_announcement(content):
                continue
            if any(phrase in content for phrase in IGNORED_PHRASES):
                continue
            line = text[:offset].count("\n") + 1
            decl = next((name for pos, name in reversed(decls) if pos < offset), "")
            for m in TOOL_NAME.finditer(content):
                name = m.group(1)
                if name not in tools:
                    continue
                if path.name in defines.get(name, ()) and not FRAGMENT_NAME.search(decl):
                    continue  # own constant / result string, not a fragment
                found.setdefault(name, []).append(f"{path.relative_to(ROOT)}:{line} in `{decl}`")
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verbose", action="store_true", help="list every announced tool, not just violations")
    args = parser.parse_args()

    files = kotlin_files()
    consts = const_map(files)
    tools = registry_tool_names(files, consts)
    added = resolve_added_names(always_on_block(AGENT_TOOLS.read_text(encoding="utf-8", errors="ignore")), files)
    announced = announced_mentions(files, tools)
    violations = {n: sites for n, sites in announced.items() if n not in added}

    if args.verbose:
        print(f"prompt sources: {len(prompt_sources(files))}, known tools: {len(tools)}, core names: {len(added)}\n")
        for name in sorted(announced):
            print(f"  {name:<22} {added.get(name, 'MISSING from alwaysOn')}")
        print()

    if violations:
        print("Announced-tool gate FAILED: the system prompt names tools the core schema hides.")
        print("The model is told to call them but is not given them; behind find_tools they stay")
        print("unreachable until the model happens to search for the right keyword.")
        print()
        for name in sorted(violations):
            sites = sorted(set(violations[name]))
            print(f"  {name}  (announced at {sites[0]}" + (f" +{len(sites) - 1} more" if len(sites) > 1 else "") + ")")
        print()
        print("Fix: add the tool to `alwaysOn` in AgentTools.makeAgentTools (under the same flag")
        print("that gates its fragment), or stop naming it in the fragment. A prose mention that")
        print("is not an instruction belongs in IGNORED_PHRASES with a reason.")
        print()
        print(f"{len(violations)} announced but not core: {', '.join(sorted(violations))}")
        return 1

    print(f"Announced-tool gate: OK ({len(announced)} announced tool names, all in the core schema)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
