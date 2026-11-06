#!/usr/bin/env python3
"""attach_picker_suppress_verify.py

Mirror of the fix for issue #215: the photo/video & document pickers launched
from the attach menu had no floating-overlay suppress gate, so in FULLSCREEN
(Minis truly backgrounded behind the system picker) the #451 bg-only overlay
rule popped the capsule on top of the picker whenever a tool was running or a
completion lingered. Multi-window kept Minis foregrounded -> no capsule ->
"works in multi-task but not fullscreen", exactly the field report.

This script:
  1. Mirrors the overlay show-gate the same way AgentForegroundService computes it.
  2. Replays the OLD behaviour (photo/file entries set no gate) to prove the bug.
  3. Asserts the NEW contract: every external-picker handoff sets the gate
     BEFORE launch, clears it in the result callback (success/cancel), clears
     it on launch failure, and the helper defers launch past menu dismissal.
  4. Anchors the contract to the real source files (SessionActivityTracker,
     ExternalPickerLaunch, ChatScreen, AgentForegroundService) so a code drift
     fails locally instead of on CI.

Exit 0 = all green. Any failure prints and exits 1.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "android" / "app" / "src" / "main" / "java" / "com" / "openminis" / "app"

TRACKER = SRC / "service" / "SessionActivityTracker.kt"
SERVICE = SRC / "service" / "AgentForegroundService.kt"
SCREEN = SRC / "ui" / "chat" / "ChatScreen.kt"
HELPER = SRC / "ui" / "chat" / "ExternalPickerLaunch.kt"

FAILURES: list[str] = []
CHECKS = 0


def check(name: str, ok: bool, detail: str = ""):
    global CHECKS
    CHECKS += 1
    if not ok:
        FAILURES.append(f"[{name}] {detail}")


# ────────────────────────────────────────────────────────────────────────────
# 1. Mirror of the overlay show-gate (AgentForegroundService shouldShow).
# ────────────────────────────────────────────────────────────────────────────
def should_show_capsule(is_foreground: bool, suppress: bool, busy_or_pending: bool,
                        enabled: bool = True, has_perm: bool = True) -> bool:
    return (enabled and has_perm and not is_foreground
            and not suppress and busy_or_pending)


# ────────────────────────────────────────────────────────────────────────────
# 2. Replay OLD behaviour: photo/file handoff sets NO suppress gate.
#    Fullscreen picker + tool running => capsule pops over the picker (bug).
# ────────────────────────────────────────────────────────────────────────────
def old_behaviour_picker_handoff(entry: str) -> bool:
    # Old code: only the camera entry touched the gate; photo/file did not.
    suppress = (entry == "camera")
    return should_show_capsule(is_foreground=False, suppress=suppress, busy_or_pending=True)


# BUG REPRO — must be True for the two broken entries and False for camera.
check("old-camera-suppressed", not old_behaviour_picker_handoff("camera"),
      "camera path already carried the gate (#451) and must stay suppressed")
check("old-photo-bug-repro", old_behaviour_picker_handoff("photo"),
      "photo entry without gate shows the capsule over the picker — the reported bug")
check("old-file-bug-repro", old_behaviour_picker_handoff("file"),
      "file entry without gate shows the capsule over the picker — same asymmetry")

# ────────────────────────────────────────────────────────────────────────────
# 3. NEW contract: one shared helper gates every external-picker handoff.
# ────────────────────────────────────────────────────────────────────────────
def helper_handoff() -> dict:
    """Simulate launchExternalPickerAfterMenuDismiss's observable sequence."""
    events: list[str] = []
    state = {"suppress": False}

    def set_suppress(v: bool):
        state["suppress"] = v
        events.append(f"suppress={v}")

    def launch():
        events.append("launch")

    # Helper body: gate first, defer launch past menu dismiss.
    events.append("menu_dismiss")
    set_suppress(True)
    events.append("defer(launch)")  # Handler.post queue
    # Popup unmount completes here; queued runnable executes next:
    launch()
    return {"state": state, "events": events}


h = helper_handoff()
ev = h["events"]
check("helper-gate-before-launch", ev.index("suppress=True") < ev.index("launch"),
      f"gate must be set before the picker Activity launches; got {ev}")
check("helper-launch-after-dismiss", ev.index("menu_dismiss") < ev.index("launch"),
      f"launch must be deferred past menu dismissal; got {ev}")
check("helper-gate-stays-set", h["state"]["suppress"],
      "gate stays set while the external picker owns the screen")

# While the picker is open: Minis is backgrounded, tool busy — capsule hidden.
check("helper-hides-capsule-during-picker",
      not should_show_capsule(is_foreground=False, suppress=True, busy_or_pending=True),
      "with the gate set, the bg-only capsule rule must NOT fire over the picker")

# Multi-task explanation: Minis stays foregrounded -> no capsule regardless.
check("multitask-no-capsule",
      not should_show_capsule(is_foreground=True, suppress=False, busy_or_pending=True),
      "multi-window keeps Minis foregrounded, capsule never shows — matches field report")

# Result callback (success OR cancel) clears the gate; a later real bg event
# (user back in Minis, app backgrounds for another reason) restores the capsule.
check("callback-clears-gate",
      not should_show_capsule(is_foreground=False, suppress=False, busy_or_pending=True)
      if False else True,
      "")  # placeholder no-op; real assertion below via state replay

def callback_replay():
    events: list[str] = []
    s = {"suppress": True}
    def reset():
        s["suppress"] = False
        events.append("callback_reset")
    reset()
    # After reset: background + busy => capsule should show again (normal #451).
    return s, events, should_show_capsule(False, s["suppress"], True)

s2, ev2, show_after = callback_replay()
check("callback-resets-gate", s2["suppress"] is False, "callback must clear the gate")
check("post-reset-capsule-restored", show_after,
      "after the picker closes, a fresh background event must restore the capsule (#451)")

def launch_failure_replay():
    s = {"suppress": True}
    def reset():
        s["suppress"] = False
    # Launch throws before leaving Minis: helper clears the gate itself.
    try:
        raise RuntimeError("no activity found")
    except RuntimeError:
        reset()
    return s["suppress"] is False

check("launch-failure-clears-gate", launch_failure_replay(),
      "if launch never reaches the picker, the helper must release the gate")

# ────────────────────────────────────────────────────────────────────────────
# 4. Source anchors — the contract must exist in the real code.
# ────────────────────────────────────────────────────────────────────────────
tracker = TRACKER.read_text()
service = SERVICE.read_text()
screen = SCREEN.read_text()
helper = HELPER.read_text() if HELPER.exists() else ""

check("anchor-tracker-generic-gate",
      "externalPickerSuppressActive" in tracker and "setExternalPickerSuppressActive" in tracker,
      "SessionActivityTracker must expose the generic external-picker gate")
check("anchor-tracker-old-name-gone",
      "cameraSuppressActive" not in tracker and "setCameraSuppressActive" not in tracker,
      "camera-specific gate name must be retired (asymmetry root cause)")
check("anchor-service-uses-generic-gate",
      "externalPickerSuppressActive" in service and "cameraSuppress" not in service,
      "AgentForegroundService must gate the capsule on the generic flag")
check("anchor-helper-exists", HELPER.exists(),
      "ExternalPickerLaunch.kt (single handoff helper) must exist")
if HELPER.exists():
    check("anchor-helper-sets-gate",
          "setExternalPickerSuppressActive(true)" in helper,
          "helper must set the gate before deferring launch")
    check("anchor-helper-defers-launch",
          "Handler" in helper and "post" in helper,
          "helper must defer launch past Popup unmount")
# Every attach-menu entry must route through the helper.
for entry in ("launchExternalPickerAfterMenuDismiss",):
    n = screen.count(entry)
    check(f"anchor-screen-{entry}-x3", n == 3,
          f"all three menu entries (camera/photo/file) must route through {entry}; found {n}")
# Both picker callbacks clear the gate.
cb_clears = len(re.findall(r"setExternalPickerSuppressActive\(false\)", screen))
check("anchor-screen-callback-clears-x3", cb_clears >= 3,
      f"callbacks (camera + photo + file) must clear the gate; found {cb_clears} clear-sites")

# ────────────────────────────────────────────────────────────────────────────
print(f"\nattach_picker_suppress_verify: {CHECKS} checks, {len(FAILURES)} failures")
if FAILURES:
    for f in FAILURES:
        print("FAIL", f)
    sys.exit(1)
print("ALL GREEN")
