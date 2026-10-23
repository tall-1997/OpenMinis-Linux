#!/usr/bin/env python3
"""Every string resource a Kotlin file references must actually exist.

The L10n gate only checks that keys *added to values/strings.xml* reached every
locale. It says nothing about the other direction, so a typo'd or renamed key in
Kotlin sails through it and only explodes in the Kotlin compile step — which is
the slowest gate in the job, and runs before the cheap ones. This checker makes
that failure a two-second local check.

Scope: `R.string.*`, `R.plurals.*` and `R.array.*` references under src/, checked
against the matching default (values/) resource file of the same module. Locale
coverage stays the L10n gate's job; existence is this one's.

Exit 1 on any dangling reference, printing file:line, the missing name, and the
closest defined names so a rename shows up as an obvious fix rather than a hunt.
"""
import difflib
import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

REF = re.compile(r"\bR\.(string|plurals|array)\.([A-Za-z_][A-Za-z0-9_]*)")
# A resource definition line inside a values/*.xml file.
DEF = re.compile(r'<(string|plurals|string-array|integer-array|array)\s+name="([^"]+)"')
# `R.string.foo` inside a string literal is documentation, not a reference.
STRING_LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"')
LINE_COMMENT = re.compile(r"//.*$")

KIND_TO_TAG = {"string": "string", "plurals": "plurals", "array": "array"}


def defined_names(values_dir):
    """{kind: {name}} parsed from the default resource file of one module."""
    out = {"string": set(), "plurals": set(), "array": set()}
    path = os.path.join(values_dir, "strings.xml")
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        src = f.read()
    for tag, name in DEF.findall(src):
        if tag == "string":
            out["string"].add(name)
        elif tag == "plurals":
            out["plurals"].add(name)
        else:
            out["array"].add(name)
    # Arrays also live in arrays.xml in some modules.
    alt = os.path.join(values_dir, "arrays.xml")
    if os.path.exists(alt):
        with open(alt, encoding="utf-8") as f:
            for tag, name in DEF.findall(f.read()):
                if tag != "string":
                    out["array"].add(name)
    return out


def modules():
    """Map module src root -> its parsed default resource names."""
    found = {}
    for root, dirs, files in os.walk(os.path.join(REPO, "src")):
        dirs[:] = [d for d in dirs if d not in ("build", ".git", "test", "androidTest")]
        if "values" in dirs and "strings.xml" in os.listdir(os.path.join(root, "values")):
            names = defined_names(os.path.join(root, "values"))
            if names is not None:
                # src/android/app/src/main/res/values -> src/android/app/src/main
                found[os.path.dirname(root)] = names
    return found


def owner_module(path, mods):
    """The module whose res/ dir is nearest above this source file."""
    best, best_len = None, -1
    for mod in mods:
        # res sits at <mod>/res; sources at <mod>/java or <mod>/kotlin.
        if path.startswith(mod) and len(mod) > best_len:
            best, best_len = mod, len(mod)
    return best


def strip_noise(line):
    """Drop string literals and line comments so docs do not count as refs."""
    line = STRING_LITERAL.sub('""', line)
    return LINE_COMMENT.sub("", line)


def main():
    mods = modules()
    if not mods:
        print("no resource modules found under src/ — nothing to check")
        return 1
    verbose = "--verbose" in sys.argv
    dangling = []
    scanned = 0
    for root, dirs, files in os.walk(os.path.join(REPO, "src")):
        dirs[:] = [d for d in dirs if d not in ("build", ".git")]
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(root, fn)
            mod = owner_module(path, mods)
            if mod is None:
                continue
            names = mods[mod]
            scanned += 1
            in_block_comment = False
            with open(path, encoding="utf-8", errors="replace") as f:
                for lineno, raw in enumerate(f, 1):
                    line = raw.rstrip("\n")
                    # Cheap block-comment tracking; KDoc around a sample call
                    # would otherwise register as a reference.
                    if in_block_comment:
                        if "*/" in line:
                            in_block_comment = False
                        continue
                    stripped = line.lstrip()
                    if stripped.startswith("/*") or stripped.startswith("/**"):
                        if "*/" not in line:
                            in_block_comment = True
                        continue
                    if stripped.startswith("*"):
                        continue
                    clean = strip_noise(line)
                    for kind, name in REF.findall(clean):
                        pool = names[KIND_TO_TAG[kind]]
                        if name not in pool:
                            near = difflib.get_close_matches(name, sorted(pool), n=3, cutoff=0.6)
                            rel = os.path.relpath(path, REPO)
                            dangling.append((rel, lineno, kind, name, near))

    print(f"scanned {scanned} Kotlin files across {len(mods)} resource module(s)")
    if not dangling:
        print("OK: every R.string/R.plurals/R.array reference resolves")
        return 0
    print(f"FAIL: {len(dangling)} dangling resource reference(s)")
    for rel, lineno, kind, name, near in dangling:
        hint = f"  closest defined: {', '.join(near)}" if near else "  (no similar name defined)"
        print(f"  {rel}:{lineno}  R.{kind}.{name}{hint}")
    if verbose:
        for mod, names in mods.items():
            print(f"  module {os.path.relpath(mod, REPO)}: "
                  f"{len(names['string'])} strings, {len(names['plurals'])} plurals, {len(names['array'])} arrays")
    return 1


sys.exit(main())
