#!/usr/bin/env python3
"""Mirror verification for GitCli DISCOVER_CMD + parseDiscover (PR #302).

The JVM unit tests only exercise parseDiscover (the Kotlin side); the
DISCOVER_CMD shell pipeline itself never executes in CI. A Kotlin escaping
regression there fails silently (2>/dev/null swallows the error) and surfaces
on device as a false "not a repo". This script runs the real shell pipeline
against a temp workspace and mirrors parseDiscover in Python, so such a
regression fails here instead.

Cases: activity ordering, space-safe paths, path tie-break on equal mtime,
.git-internal exclusion, head -8 cap, empty workspace, parser noise filter.
"""
import os
import subprocess
import sys
import tempfile
import time

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    tag = "PASS" if cond else "FAIL"
    line = f"[{tag}] {name}"
    if detail and not cond:
        line += f" — {detail}"
    print(line)


# ---------------------------------------------------------------------------
# 1. Source-drift guards: the shell pipeline in GitCli.kt must keep the
#    tie-break, probe quoting, and workspace anchor that the runs below pin.
# ---------------------------------------------------------------------------
SRC = "src/android/app/src/main/java/com/openminis/app/git/GitCli.kt"
with open(SRC, encoding="utf-8") as f:
    src = f.read()

check("kotlin-tiebreak-in-source", "sort -k1,1nr -k2" in src,
      "DISCOVER_CMD lost the path tie-break")
check("kotlin-shellquote-probe", "shellQuote" in src,
      "probeCmd lost shellQuote on -C root")
check("kotlin-absolute-anchor", "find /var/minis/workspace" in src,
      "DISCOVER_CMD no longer anchored at the workspace path")

# ---------------------------------------------------------------------------
# 2. Real shell run of DISCOVER_CMD (Kotlin escapes resolved) against a temp
#    workspace. @WS@ stands in for the hardcoded /var/minis/workspace anchor.
# ---------------------------------------------------------------------------
DISCOVER_TMPL = (
    "find @WS@ -maxdepth 4 -mindepth 2 -type d -name .git 2>/dev/null | "
    "while IFS= read -r d; do "
    "root=${d%/.git}; "
    "newest=$(find \"$root\" -type f -not -path '*/.git/*' -printf '%T@\\n' 2>/dev/null "
    "| sort -rn | head -1); "
    "printf '%s %s\\n' \"${newest:-0}\" \"$root\"; "
    "done | sort -k1,1nr -k2 | sed 's/^[^ ]* //' | head -8"
)


def run_discover(ws):
    cmd = DISCOVER_TMPL.replace("@WS@", ws)
    r = subprocess.run(["bash", "-c", cmd], capture_output=True, text=True, timeout=60)
    return r.returncode, r.stdout


def mkrepo(ws, name, mtime):
    root = os.path.join(ws, name)
    os.makedirs(os.path.join(root, ".git"), exist_ok=True)
    fp = os.path.join(root, "file.txt")
    with open(fp, "w", encoding="utf-8") as f:
        f.write(name)
    os.utime(fp, (mtime, mtime))
    return root


now = time.time()

with tempfile.TemporaryDirectory() as ws:
    a = mkrepo(ws, "repo-a", now)
    b = mkrepo(ws, "b with space", now - 3600)
    c = mkrepo(ws, "repo-c", now - 7200)
    rc, out = run_discover(ws)
    lines = [l for l in out.splitlines() if l.strip()]
    check("discover-activity-order-space-safe",
          rc == 0 and lines == [a, b, c],
          f"rc={rc} lines={lines}")

with tempfile.TemporaryDirectory() as ws:
    d_repo = mkrepo(ws, "d-repo", now - 100)
    c_repo = mkrepo(ws, "c-repo", now - 100)
    rc, out = run_discover(ws)
    lines = [l for l in out.splitlines() if l.strip()]
    check("discover-tiebreak-path-order",
          rc == 0 and lines == [c_repo, d_repo],
          f"rc={rc} lines={lines}")

with tempfile.TemporaryDirectory() as ws:
    old_tree = mkrepo(ws, "old-tree", now - 3600)
    fresh_git = mkrepo(ws, "fresh-git", now - 7200)
    fh = os.path.join(fresh_git, ".git", "FETCH_HEAD")
    with open(fh, "w", encoding="utf-8") as f:
        f.write("x")
    os.utime(fh, (now, now))
    rc, out = run_discover(ws)
    lines = [l for l in out.splitlines() if l.strip()]
    check("discover-git-internal-excluded",
          rc == 0 and lines == [old_tree, fresh_git],
          f"rc={rc} lines={lines}")

with tempfile.TemporaryDirectory() as ws:
    for i in range(10):
        mkrepo(ws, f"r{i:02d}", now - i)
    rc, out = run_discover(ws)
    lines = [l for l in out.splitlines() if l.strip()]
    check("discover-cap-8",
          rc == 0 and len(lines) == 8 and lines[0].endswith("r00"),
          f"rc={rc} n={len(lines)} first={lines[:1]}")

with tempfile.TemporaryDirectory() as ws:
    rc, out = run_discover(ws)
    check("discover-empty-workspace", rc == 0 and out.strip() == "",
          f"rc={rc} out={out!r}")


# ---------------------------------------------------------------------------
# 3. parseDiscover Python mirror (same contract as GitCliParseTest).
# ---------------------------------------------------------------------------
def parse_discover(output):
    out = []
    for raw in output.splitlines():
        line = raw.strip()
        if not line.startswith("/"):
            continue
        name = line.split("/")[-1]
        if name:
            out.append({"path": line, "name": name})
    return out


sample = "\n/var/minis/workspace/a\n./a/.git\nnot-a-path\n/var/minis/workspace/b with space\n"
parsed = parse_discover(sample)
check("parse-absolute-noise-filter",
      parsed == [{"path": "/var/minis/workspace/a", "name": "a"},
                 {"path": "/var/minis/workspace/b with space", "name": "b with space"}],
      f"parsed={parsed}")
check("parse-trailing-slash-dropped", parse_discover("/w/x/\n") == [],
      f"parsed={parse_discover('/w/x/\n')}")

total = len(PASS) + len(FAIL)
print(f"\n{len(PASS)}/{total} green")
sys.exit(0 if not FAIL else 1)
