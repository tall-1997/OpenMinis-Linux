#!/usr/bin/env python3
"""Mirror verification for GitCli DISCOVER_CMD + parseDiscover (PR #302 + #305).

The JVM unit tests only exercise parseDiscover (the Kotlin side); the
DISCOVER_CMD shell pipeline itself never executes in CI. A Kotlin escaping
regression there fails silently (2>/dev/null swallows the error) and surfaces
on device as a false "not a repo". This script runs the real shell pipeline
against a temp workspace and mirrors parseDiscover in Python, so such a
regression fails here instead.

PR #305 changed the activity signal: newest HEAD commit time (git log -1,
O(1)) max-ed with the newest shallow (maxdepth 2) working-tree mtime, instead
of a full-tree mtime walk (a per-repo full-tree find hung the panel on real
devices where every stat crosses PRoot at 10-50x cost).

Cases: commit-signal ordering, shallow-mtime ordering, deep-path isolation,
space-safe paths, path tie-break on equal scores, FETCH_HEAD exclusion,
head -8 cap, empty workspace, parser noise filter.
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
# 1. Source-drift guards: DISCOVER_CMD/probeCmd live in GitCliCmd.kt (PR #306
#    extraction); probe() and its caller timeout live in GitCli.kt. Both
#    anchors are asserted present so a future move fails loudly instead of
#    silently skipping every guard below.
# ---------------------------------------------------------------------------
SRC_CMD = "src/android/app/src/main/java/com/openminis/app/git/GitCliCmd.kt"
SRC_CLI = "src/android/app/src/main/java/com/openminis/app/git/GitCli.kt"
missing = [p for p in (SRC_CMD, SRC_CLI) if not os.path.exists(p)]
if missing:
    print(f"[FAIL] source-anchor-missing: {', '.join(missing)}")
    sys.exit(1)
with open(SRC_CMD, encoding="utf-8") as f:
    cmd_src = f.read()
with open(SRC_CLI, encoding="utf-8") as f:
    cli_src = f.read()

check("kotlin-tiebreak-in-source", "sort -k1,1nr -k2" in cmd_src,
      "DISCOVER_CMD lost the path tie-break")
check("kotlin-shellquote-probe", "shellQuote" in cmd_src,
      "probeCmd lost shellQuote on -C root")
check("kotlin-absolute-anchor", "find /var/minis/workspace" in cmd_src,
      "DISCOVER_CMD no longer anchored at the workspace path")
check("kotlin-commit-signal", 'log -1 --format=%ct' in cmd_src,
      "DISCOVER_CMD lost the O(1) commit-time signal")
check("kotlin-shallow-signal", '-maxdepth 2 -type f' in cmd_src,
      "DISCOVER_CMD lost the shallow-mtime signal (full-tree walk is a hang)")
check("kotlin-no-fulltree-scan", 'find "$root" -type f' not in cmd_src,
      "DISCOVER_CMD reverted to a full-tree mtime walk")
check("kotlin-probe-timeout", "timeoutMs = 30_000L" in cli_src,
      "probe() lost the 30s caller timeout bound")

# ---------------------------------------------------------------------------
# 2. Real shell run of DISCOVER_CMD (Kotlin escapes resolved) against a temp
#    workspace. @WS@ stands in for the hardcoded /var/minis/workspace anchor.
# ---------------------------------------------------------------------------
DISCOVER_TMPL = (
    "find @WS@ -maxdepth 4 -mindepth 2 -type d -name .git 2>/dev/null | "
    "while IFS= read -r d; do "
    "root=${d%/.git}; "
    "committed=$(git -C \"$root\" log -1 --format=%ct 2>/dev/null); "
    "touched=$(find \"$root\" -maxdepth 2 -type f -not -path '*/.git/*' "
    "-printf '%T@\\n' 2>/dev/null | sort -rn | head -1); "
    "score=$(printf '%s\\n%s\\n' \"${committed:-0}\" \"${touched:-0}\" "
    "| sort -rn | head -1); "
    "printf '%s %s\\n' \"${score:-0}\" \"$root\"; "
    "done | sort -k1,1nr -k2 | sed 's/^[^ ]* //' | head -8"
)


def run_discover(ws):
    cmd = DISCOVER_TMPL.replace("@WS@", ws)
    r = subprocess.run(["bash", "-c", cmd], capture_output=True, text=True, timeout=60)
    return r.returncode, r.stdout


def mkrepo(ws, name, mtime, commit_time=None):
    """Create a repo-ish dir. [mtime] stamps the top-level working-tree file
    (the shallow signal). [commit_time] optionally builds a real commit with
    that timestamp (the git log -1 signal)."""
    root = os.path.join(ws, name)
    os.makedirs(os.path.join(root, ".git"), exist_ok=True)
    fp = os.path.join(root, "file.txt")
    with open(fp, "w", encoding="utf-8") as f:
        f.write(name)
    os.utime(fp, (mtime, mtime))
    if commit_time is not None:
        subprocess.run(["git", "-C", root, "init", "-q"], capture_output=True)
        subprocess.run(["git", "-C", root, "config", "user.name", "t"], capture_output=True)
        subprocess.run(["git", "-C", root, "config", "user.email", "t@t"], capture_output=True)
        subprocess.run(["git", "-C", root, "add", "-A"], capture_output=True)
        env = dict(os.environ,
                   GIT_AUTHOR_DATE=f"@{int(commit_time)} +0000",
                   GIT_COMMITTER_DATE=f"@{int(commit_time)} +0000")
        subprocess.run(["git", "-C", root, "commit", "-q", "-m", "c"],
                       capture_output=True, env=env)
    return root


now = time.time()

# commit signal dominates ordering; space-safe paths ride along
with tempfile.TemporaryDirectory() as ws:
    a = mkrepo(ws, "repo-a", now - 100, commit_time=now)
    b = mkrepo(ws, "b with space", now - 100, commit_time=now - 3600)
    c = mkrepo(ws, "repo-c", now - 100, commit_time=now - 7200)
    rc, out = run_discover(ws)
    lines = [l for l in out.splitlines() if l.strip()]
    check("discover-commit-order-space-safe",
          rc == 0 and lines == [a, b, c],
          f"rc={rc} lines={lines}")

# no commits: shallow working-tree mtime is the signal
with tempfile.TemporaryDirectory() as ws:
    x = mkrepo(ws, "repo-x", now)
    y = mkrepo(ws, "repo-y", now - 100)
    rc, out = run_discover(ws)
    lines = [l for l in out.splitlines() if l.strip()]
    check("discover-shallow-mtime-order",
          rc == 0 and lines == [x, y],
          f"rc={rc} lines={lines}")

# deep-path edits (depth > 2) are invisible to the shallow signal and the
# commit signal must carry them; a repo whose only activity is a deep
# uncommitted edit must not outrank a shallowly-edited one
with tempfile.TemporaryDirectory() as ws:
    shallow = mkrepo(ws, "shallow", now - 1000)
    deep = mkrepo(ws, "deep", now - 5000)
    os.makedirs(os.path.join(deep, "x", "y", "z"))
    dfp = os.path.join(deep, "x", "y", "z", "deep.txt")
    with open(dfp, "w", encoding="utf-8") as f:
        f.write("d")
    os.utime(dfp, (now, now))
    rc, out = run_discover(ws)
    lines = [l for l in out.splitlines() if l.strip()]
    check("discover-deep-path-isolated",
          rc == 0 and lines == [shallow, deep],
          f"rc={rc} lines={lines}")

with tempfile.TemporaryDirectory() as ws:
    d_repo = mkrepo(ws, "d-repo", now - 100)
    c_repo = mkrepo(ws, "c-repo", now - 100)
    rc, out = run_discover(ws)
    lines = [l for l in out.splitlines() if l.strip()]
    check("discover-tiebreak-path-order",
          rc == 0 and lines == [c_repo, d_repo],
          f"rc={rc} lines={lines}")

# FETCH_HEAD freshness must not steer the pick (git log -1 reads HEAD, and
# the shallow find excludes .git)
with tempfile.TemporaryDirectory() as ws:
    old_tree = mkrepo(ws, "old-tree", now - 3600, commit_time=now - 3600)
    fresh_git = mkrepo(ws, "fresh-git", now - 7200, commit_time=now - 7200)
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
        mkrepo(ws, f"r{i:02d}", now - i, commit_time=now - i)
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
