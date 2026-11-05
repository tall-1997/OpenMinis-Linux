#!/usr/bin/env python3
"""[T-plan-receipt-diet] Python mirror of the plan-board noise budget.

Mirrors AgentPlanTool's receipt + snapshot rendering and pins the budget:
- change receipts are O(1) in board size: id + title + progress, no board echo
- the riding snapshot drops landed descriptions (O(open work), not O(board))
- empty / all-done boards inject nothing
- the full view stays on demand behind op=list (render unchanged)

Run: python3 scripts/plan_noise_verify.py
"""
import re
import sys

UUID = "3f2a1b0c-1111-2222-3333-444455556666"


def render(plans):
    """Full render (op=list + miss receipts) - unchanged by the diet."""
    if not plans:
        return ("(no plan items: this chat has no board yet - when the work spans "
                "multiple steps or several tool rounds, create one with op=add so the "
                "user sees progress live)")
    out = ["plans: %d" % len(plans)]
    marks = {"done": "x", "active": ">", "failed": "!", "pending": " "}
    for i, p in enumerate(plans):
        mark = marks.get(p["status"], " ")
        line = "[%s] %d. %s {%s} [%s]" % (mark, i + 1, p["title"], p["id"], p["status"])
        if p["description"]:
            line += " — %s" % p["description"]
        out.append(line)
    return "\n".join(out) + "\n"


def board_snapshot_prompt(plans):
    """Slim riding snapshot: no uuids, done rows drop descriptions."""
    if not plans:
        return None
    if all(p["status"] == "done" for p in plans):
        return None
    out = ["<plan_board>",
           "Current plan board. Advance it with agent_plan — target an item by "
           "its number (update id=\"2\", status=\"done\"), by its title, or rebuild "
           "the whole board with op=replace; the user sees it live:"]
    for i, p in enumerate(plans):
        line = "%d. [%s] %s" % (i + 1, p["status"], p["title"])
        if p["description"] and p["status"] != "done":
            line += " — %s" % p["description"]
        out.append(line)
    out.append("</plan_board>")
    return "\n".join(out)


def progress_line(plans):
    if not plans:
        return "(board empty)"
    done = sum(1 for p in plans if p["status"] == "done")
    return "(%d/%d done)" % (done, len(plans))


def add_receipt(p, plans):
    return "Added {%s}: %s %s" % (p["id"], p["title"], progress_line(plans))


def update_receipt(p, plans):
    return "Updated {%s}: %s [%s] %s" % (p["id"], p["title"], p["status"], progress_line(plans))


def remove_receipt(ref, plans):
    return "Removed: %s %s" % (ref, progress_line(plans))


def replace_receipt(items, plans):
    titles = ", ".join(it["title"] for it in items[:10])
    if len(items) > 10:
        titles += " +%d more" % (len(items) - 10)
    return "Replaced plans: %d items — %s %s" % (len(items), titles, progress_line(plans))


def old_add_receipt(p, plans):
    """Pre-diet receipt: full render echo on every mutation."""
    return "Added %s: %s" % (p["id"], render(plans))


CHECKS = []


def check(name, ok, detail=""):
    CHECKS.append((name, bool(ok), detail))


def main():
    fat = "D" * 300
    plans = [
        {"id": UUID, "title": "recon", "description": fat, "status": "done"},
        {"id": "b" * 36, "title": "implement", "description": fat, "status": "active"},
        {"id": "c" * 36, "title": "ship", "description": fat, "status": "pending"},
    ]

    # 1-2: add receipt keeps the {id} round-trip + title contract
    r = add_receipt({"id": UUID, "title": "new task"}, plans)
    check("add receipt carries {id} for round-trip",
          re.search(r"\{([^}]+)\}", r).group(1) == UUID)
    check("add receipt carries the title", "new task" in r)

    # 3-4: receipts are O(1) - no board echo, no descriptions
    check("add receipt has no board header", "plans: " not in r)
    check("add receipt has no descriptions", fat[:50] not in r)

    # 5-6: update receipt shape
    u = update_receipt({"id": UUID, "title": "ship", "status": "done"}, plans)
    check("update receipt carries id + title + status",
          "{" + UUID + "}" in u and "ship" in u and "[done]" in u)
    check("update receipt has no descriptions", fat[:50] not in u)

    # 7: remove receipt
    rm = remove_receipt("2", plans)
    check("remove receipt is one line, no board echo",
          "plans: " not in rm and rm.count("\n") == 0)

    # 8-9: replace receipt - titles yes, descriptions/uuids no
    items = [{"title": "one", "description": fat}, {"title": "two"}]
    rep = replace_receipt(items, plans)
    check("replace receipt lists titles", "two" in rep)
    check("replace receipt has no descriptions or uuid braces",
          fat[:50] not in rep and "{" not in rep)

    # 10: replace receipt caps the title list at 10
    items15 = [{"title": "t%d" % i} for i in range(15)]
    plans15 = [{"id": "%036d" % i, "title": "t%d" % i, "description": "",
                "status": "pending"} for i in range(15)]
    rep15 = replace_receipt(items15, plans15)
    check("replace receipt caps the title list at 10",
          "+5 more" in rep15 and "t14" not in rep15)

    # 11: receipts carry the progress line
    check("receipts carry the progress line", "(1/3 done)" in r)

    # 12: snapshot wraps exactly one plan_board pair (strip contract)
    snap = board_snapshot_prompt(plans)
    check("snapshot wraps exactly one plan_board pair",
          snap.count("<plan_board>") == 1 and snap.count("</plan_board>") == 1)

    # 13-14: snapshot diet - done drops description, open keeps it
    recon_line = next(l for l in snap.split("\n") if "recon" in l)
    check("snapshot drops the done row's description", fat[:50] not in recon_line)
    check("snapshot keeps the open rows' descriptions", fat[:50] in snap)

    # 15: snapshot has no uuids
    check("snapshot carries no uuid braces", "{" not in snap)

    # 16-17: guards - empty and all-done boards inject nothing
    check("empty board injects nothing", board_snapshot_prompt([]) is None)
    check("all-done board injects nothing",
          board_snapshot_prompt([{"id": UUID, "title": "x",
                                  "description": fat, "status": "done"}]) is None)

    # 18: teaching copy survives the diet
    check("snapshot still teaches number targeting",
          'update id="2"' in snap and "op=replace" in snap)

    # 19: full view unchanged behind op=list
    check("render keeps uuids + descriptions (op=list full view)",
          "{" + UUID + "}" in render(plans) and fat[:50] in render(plans))

    # 20-21: budget math on a realistic 10-item board (8 done, fat descriptions)
    board10 = [{"id": "%036d" % i, "title": "step %d" % i, "description": fat,
                "status": "done" if i < 8 else "active"} for i in range(10)]
    old_rider = render(board10)
    new_rider = board_snapshot_prompt(board10)
    check("riding cost drops below a third on a done-heavy board",
          len(new_rider) < len(old_rider) / 3,
          "old=%d new=%d" % (len(old_rider), len(new_rider)))
    old_add = old_add_receipt({"id": UUID, "title": "x"}, board10)
    new_add = add_receipt({"id": UUID, "title": "x"}, board10)
    check("add receipt stays under 120 chars where the echo was ~3k",
          len(new_add) < 120 < len(old_add),
          "old=%d new=%d" % (len(old_add), len(new_add)))

    failed = [c for c in CHECKS if not c[1]]
    for name, ok, detail in CHECKS:
        print(("PASS " if ok else "FAIL ") + name + (("  [%s]" % detail) if detail else ""))
    print("\n%d/%d checks green" % (len(CHECKS) - len(failed), len(CHECKS)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
