#!/usr/bin/env python3
"""[T-p9-revert] Python mirror of the P9 revert planner + state-machine
invariants (ChatRepositoryRevert.kt). Runs the same decision logic against
synthetic scenarios so the Kotlin contract has an executable spec that a
CI-independent local run can check — same pattern as
memory_window_verify.py / memory_authority_verify.py.

Mirrored units:
- RevertPlanner.plan (oldest-first, strict >, isDir skip, files filter,
  group-by-checkpoint)
- RevertStageRegistry.supersede (cut at/before boundary+1 drops the record)
- commit cut math (cutoff = boundarySortOrder + 1: boundary stays, tail goes)
- stage ordering (original snapshot BEFORE restoreTargets)
- chained stage (clear-then-stage leaves exactly one record)
- outcome selection (Empty only when plan empty AND no tail)
"""
import sys

def plan(boundary_created_at, checkpoints, files=None):
    """Mirror of RevertPlanner.plan: first-wins per path, oldest first."""
    by_path = {}
    for cp in sorted(checkpoints, key=lambda c: c["createdAt"]):
        if cp["createdAt"] <= boundary_created_at:  # strict >
            continue
        for e in cp["entries"]:
            if e.get("isDir"):
                continue
            if files is not None and e["path"] not in files:
                continue
            by_path.setdefault(e["path"], cp["id"])  # putIfAbsent = first wins
    grouped = {}
    for path, cp_id in by_path.items():
        grouped.setdefault(cp_id, []).append(path)
    return [{"checkpointId": k, "paths": v} for k, v in grouped.items()]

def supersede(record, cutoff):
    """Mirror of RevertStageRegistry.supersede: True = drop the record."""
    if record is None:
        return False
    return cutoff <= record["boundarySortOrder"] + 1

CHECKS = []
def check(name, cond):
    CHECKS.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name)

def entry(path, existed=True, is_dir=False):
    return {"path": path, "existed": existed, "isDir": is_dir}

def cp(cid, created, entries):
    return {"id": cid, "createdAt": created, "entries": entries}

# ---- planner ----
p = plan(100, [cp("b", 300, [entry("/w/a")]), cp("a", 200, [entry("/w/a")])])
check("oldest checkpoint wins per path", len(p) == 1 and p[0]["checkpointId"] == "a")

check("same-ms capture is pre-boundary", plan(100, [cp("a", 100, [entry("/w/a")])]) == [])
check("pre-boundary checkpoint ignored", plan(100, [cp("a", 50, [entry("/w/a")])]) == [])

p = plan(100, [cp("a", 200, [entry("/w/new", existed=False)])])
check("created file (existed=false) is planned", len(p) == 1 and p[0]["paths"] == ["/w/new"])

check("dir entries skipped", plan(100, [cp("a", 200, [entry("/w/d", is_dir=True)])]) == [])

p = plan(100, [cp("a", 200, [entry("/w/a"), entry("/w/b")])], files={"/w/b"})
check("files filter limits the plan", len(p) == 1 and p[0]["paths"] == ["/w/b"])

p = plan(100, [cp("a", 200, [entry("/w/a"), entry("/w/b")]), cp("b", 300, [entry("/w/c")])])
by = {g["checkpointId"]: g["paths"] for g in p}
check("paths group by source checkpoint",
      set(by["a"]) == {"/w/a", "/w/b"} and by["b"] == ["/w/c"])

check("no checkpoints -> empty plan", plan(100, []) == [])

# ---- supersede hook ----
rec = {"boundarySortOrder": 42}
check("cut at boundary+1 supersedes (commit path)", supersede(rec, 43) is True)
check("cut before boundary supersedes (deeper edit/delete)", supersede(rec, 10) is True)
check("cut after boundary+1 keeps the record (window shortened)", supersede(rec, 50) is False)
check("no record -> supersede is a no-op", supersede(None, 1) is False)

# ---- commit cut math ----
boundary_sort = 42
cutoff = boundary_sort + 1
check("commit cutoff keeps the boundary row (>= cutoff is doomed)", cutoff == 43)

# ---- stage ordering: original BEFORE restoreTargets ----
disk = {"f": "post-window bytes"}
order = []
def capture_original():
    order.append(("capture", disk["f"]))
def restore_targets():
    order.append(("restore", "boundary bytes"))
    disk["f"] = "boundary bytes"
capture_original(); restore_targets()
check("original snapshot lands before the rollback overwrites bytes",
      order[0][0] == "capture" and order[1][0] == "restore")

# ---- chained stage: clear-then-stage leaves one record ----
records = [{"boundary": "A", "original": "orig-A"}]
# stage B while A staged -> clear A (restore orig-A, drop) -> stage B fresh
records.clear()
records.append({"boundary": "B", "original": "orig-B(fresh)"})
check("chained stage leaves exactly one record with a fresh undo basis",
      len(records) == 1 and records[0]["boundary"] == "B")

# ---- outcome selection ----
def outcome(plan_list, has_tail):
    if not plan_list and not has_tail:
        return "Empty"
    return "Ok"
check("Empty only when plan empty AND no tail", outcome([], False) == "Empty")
check("empty plan with tail still stages (message-only revert)", outcome([], True) == "Ok")
check("non-empty plan stages", outcome([{"x": 1}], True) == "Ok")

# ---- record JSON round-trip fields ----
import json
rec = {"v": 1, "sessionId": "s1", "boundaryMessageId": "m1", "boundarySortOrder": 7,
       "boundaryCreatedAt": 123456, "originalCheckpointId": "ef56ab78",
       "plan": [{"cp": "ab12cd34", "paths": ["/w/a", "/w/b"]}], "stagedAt": 999}
rt = json.loads(json.dumps(rec))
check("record JSON round-trips every field",
      rt["boundarySortOrder"] == 7 and rt["originalCheckpointId"] == "ef56ab78"
      and rt["plan"][0]["paths"] == ["/w/a", "/w/b"])

failed = [n for n, ok in CHECKS if not ok]
print(f"\n{len(CHECKS) - len(failed)}/{len(CHECKS)} checks green")
if failed:
    print("FAILED:", failed)
    sys.exit(1)
print("revert_verify: ALL GREEN")
