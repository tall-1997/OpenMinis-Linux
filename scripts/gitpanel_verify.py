#!/usr/bin/env python3
"""Python mirror of GitPanelGraphTest — verifies the Kotlin graphLayout /
changesTotals / filterHistory algorithms against the same cases, so the
JVM tests and this mirror must agree. Run: python3 scripts/gitpanel_verify.py
"""
import sys
from typing import Dict, List, Optional, Tuple

Commit = Tuple[str, List[str]]  # (hash, parents)
Row = Dict

PASS = 0
FAIL = 0


def check(name: str, actual, expected):
    global PASS, FAIL
    if actual == expected:
        PASS += 1
        print(f"  ok  {name}")
    else:
        FAIL += 1
        print(f"FAIL  {name}\n      expected: {expected}\n      actual:   {actual}")


def commit(h, parents=None):
    return (h, parents or [])


# ------------------------------------------------------------- graphLayout

def graph_layout(commits: List[Commit]) -> List[Row]:
    """Mirror of GitPanelState.graphLayout (newest-first window)."""
    if not commits:
        return []
    hashes = {h for h, _ in commits}
    index_of = {h: i for i, (h, _) in enumerate(commits)}
    lane_of: Dict[str, int] = {}
    children_of: Dict[str, int] = {}
    for h, parents in commits:
        for p in parents:
            if p in hashes:
                children_of[p] = children_of.get(p, 0) + 1

    # Lane assignment — getOrPut with nextLane++, Kotlin verbatim.
    next_lane = 0

    def get_or_put(h):
        nonlocal next_lane
        if h not in lane_of:
            lane_of[h] = next_lane
            next_lane += 1
        return lane_of[h]

    for h, parents in commits:
        ps = [p for p in parents if p in hashes]
        if not ps:
            get_or_put(h)
        else:
            own = get_or_put(h)
            for k, p in enumerate(ps):
                if k == 0:
                    lane_of.setdefault(p, own)
                else:
                    lane_of.setdefault(p, next_lane)
                    next_lane += 1

    # Rails — first parent at own lane, others at their lane; roots run to
    # the window's last row so the oldest node still has a stub rail.
    rails = []
    last = len(commits) - 1
    for i, (h, parents) in enumerate(commits):
        lane = lane_of[h]
        ps = [p for p in parents if p in hashes]
        end = last if not ps else index_of[ps[0]]
        rails.append((lane, i, end))
        for p in ps[1:]:
            rails.append((lane_of[p], i, index_of[p]))

    rows: List[Row] = []
    for j, (h, _) in enumerate(commits):
        active = sorted({lane for lane, a, b in rails if a <= j <= b})
        rows.append({
            "hash": h,
            "lane": lane_of[h],
            "active": active,
            "is_merge": sum(1 for p in [p for _, p in [(h, ps)] for ps in [p] if False]) > 1 if False else 0,
        })
    # isMerge / isFork computed from the window, mirroring Kotlin.
    for r in rows:
        h, parents = next(c for c in commits if c[0] == r["hash"])
        r["is_merge"] = sum(1 for p in parents if p in hashes) > 1
        r["is_fork"] = children_of.get(h, 0) >= 2
    return rows


# --------------------------------------------------------- mirror test cases

commits = [commit("c3", ["c2"]), commit("c2", ["c1"]), commit("c1")]
rows = graph_layout(commits)
check("linear lanes", [r["lane"] for r in rows], [0, 0, 0])
check("linear active", [r["active"] for r in rows], [[0], [0], [0]])
check("linear merge flags", any(r["is_merge"] for r in rows), False)

commits = [commit("m", ["a", "b"]), commit("a", ["base"]), commit("b", ["base"]), commit("base")]
rows = graph_layout(commits)
check("merge ring", rows[0]["is_merge"], True)
check("merge lane", rows[0]["lane"], 0)
check("merge connectors", len(rows[0]["active"]), 2)
check("side lane exists", any(r["lane"] >= 1 for r in rows), True)

commits = [commit("b2", ["base"]), commit("b1", ["base"]), commit("base")]
rows = graph_layout(commits)
check("fork flag", rows[2]["is_fork"], True)
check("non-fork flag", rows[0]["is_fork"], False)

check("empty window", graph_layout([]), [])
one = graph_layout([commit("c1")])
check("single lane", one[0]["lane"], 0)
check("single active", one[0]["active"], [0])
check("single merge", one[0]["is_merge"], False)

commits = [
    commit("h5", ["h4"]), commit("h4", ["h3"]), commit("h3", ["h2"]),
    commit("h2", ["h1", "s1"]), commit("s1", ["h1"]), commit("h1"),
]
rows = graph_layout(commits)
check("first-parent chain tail", rows[-1]["lane"], 0)
check("first-parent chain head", rows[0]["lane"], 0)
check("connector row lanes", rows[3]["active"], [0, 1])
check("side branch lane", rows[4]["lane"], 1)

# ------------------------------------------------------------- changesTotals

def changes_totals(entries, staged, unstaged):
    added = deleted = 0
    for path, is_staged in entries:
        stat = (staged if is_staged else unstaged).get(path)
        if stat and not stat[2]:
            added += stat[0]
            deleted += stat[1]
    return (len(entries), added, deleted)


check(
    "totals merge",
    changes_totals([("a.kt", True), ("b.kt", False), ("c.bin", False)],
                   {"a.kt": (12, 3, False)},
                   {"b.kt": (0, 9, False), "c.bin": (-1, -1, True)}),
    (3, 12, 12),
)
check("totals null status", changes_totals([], {}, {}), (0, 0, 0))
check("totals untracked", changes_totals([("x", False)], {}, {}), (1, 0, 0))

# ------------------------------------------------------------- filterHistory

def filter_history(log, query):
    q = query.strip()
    if not q:
        return log
    return [c for c in log if q.lower() in c[0].lower() or q.lower() in c[1].lower()]


log = [("aabbccd", "Fix null crash"), ("eeddccb", "Alice"), ("ffeeaab", "WIP")]
check("filter subject", len(filter_history(log, "null crash")), 1)
check("filter author ci", len(filter_history(log, "ALICE")), 1)
check("filter hash", len(filter_history(log, "aabbccd")), 1)
check("filter short hash", len(filter_history(log, "aabb")), 1)
check("filter blank", filter_history(log, ""), log)
check("filter blank spaces", filter_history(log, "   "), log)
check("filter no match", filter_history(log, "zzz"), [])

# ------------------------------------------------------- splitRename / remote

def split_rename(path):
    arrow = path.find(" => ")
    if arrow < 0:
        return None
    lhs, rhs = path[:arrow], path[arrow + 4:]
    if "{" in lhs and rhs.endswith("}"):
        prefix = lhs[: lhs.rfind("{")]
        return prefix + lhs[lhs.rfind("{") + 1 :], prefix + rhs[: rhs.rfind("}")]
    return lhs, rhs


check("split rename plain", split_rename("src/old.kt => src/new.kt"),
      ("src/old.kt", "src/new.kt"))
check("split rename braces", split_rename("src/{old.kt => new.kt}"),
      ("src/old.kt", "src/new.kt"))
check("split rename not rename", split_rename("src/a.kt"), None)

def parse_remote(output):
    fallback = None
    for raw in output.splitlines():
        line = raw.strip("\r")
        if not line.strip():
            continue
        f = line.split("\t")
        if len(f) < 2:
            continue
        url = f[1].split(" (")[0].strip()
        if not url:
            continue
        if "(push)" in f[1]:
            return url
        if fallback is None:
            fallback = url
    return fallback


check("remote push wins", parse_remote(
    "origin\thttps://github.com/a/b-fetch.git (fetch)\n"
    "origin\tssh://git@github.com/a/b.git (push)\n"),
    "ssh://git@github.com/a/b.git")
check("remote equal urls", parse_remote(
    "origin\thttps://github.com/a/b.git (fetch)\n"
    "origin\thttps://github.com/a/b.git (push)\n"),
    "https://github.com/a/b.git")
check("remote blank", parse_remote(""), None)

def numstat_parse(output):
    m = {}
    for raw in output.splitlines():
        line = raw.strip("\r")
        if not line.strip():
            continue
        f = line.split("\t")
        if len(f) < 3:
            continue
        path = "\t".join(f[2:])
        if f[0] == "-" and f[1] == "-":
            m[split_rename(path)[1] if split_rename(path) else path] = "B"
            continue
        m[path] = (int(f[0] or 0), int(f[1] or 0))
    return m


check("numstat counts", numstat_parse("12\t3\tsrc/a.kt\n0\t9\tREADME.md"),
      {"src/a.kt": (12, 3), "README.md": (0, 9)})
check("numstat braces rename", numstat_parse("-\t-\tsrc/{old.kt => new.kt}"),
      {"src/new.kt": "B"})
check("numstat binary", numstat_parse("-\t-\timg.png\n12\t4\tsrc/c.kt"),
      {"img.png": "B", "src/c.kt": (12, 4)})

print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
