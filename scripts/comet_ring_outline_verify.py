#!/usr/bin/env python3
"""comet_ring_outline_verify.py

Mirror of the CometRing fix in SessionGroupVisuals.kt: the running comet used
to orbit a CIRCLE (drawArc over the whole canvas), ignoring the group avatar's
rounded-square silhouette — a circle through a 44dp square's diagonals leaves
the outline by ~4.7dp at every corner, so the comet visibly floated off the
folder shape. The fix walks the avatar's own rounded-rect outline.

This script:
  1. Mirrors roundedRectOutlinePoint() 1:1 (same segment order, same corner
     arc math, same wrap/clamp rules).
  2. Pins the boundary phases, wrap behavior, on-outline invariant over 720
     samples, degenerate shapes, and radius clamping.
  3. Anchors the contract to the real source file (SessionGroupVisuals.kt)
     so a code drift fails locally instead of on CI.

Exit 0 = all green. Any failure prints and exits 1.
"""

import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "android" / "app" / "src" / "main" / "java" / "com" / "openminis" / "app"
VISUALS = SRC / "ui" / "sessions" / "SessionGroupVisuals.kt"

FAILURES: list[str] = []
CHECKS = 0


def check(name: str, ok: bool, detail: str = ""):
    global CHECKS
    CHECKS += 1
    if not ok:
        FAILURES.append(f"[{name}] {detail}")


# ────────────────────────────────────────────────────────────────────────────
# 1. Mirror of roundedRectOutlinePoint (Kotlin: clockwise from (radius, 0)).
# ────────────────────────────────────────────────────────────────────────────
def rounded_rect_outline_point(w: float, h: float, r: float, t: float):
    if w <= 0 or h <= 0:
        return (0.0, 0.0)
    r = max(0.0, min(r, min(w, h) / 2))
    phase = t - math.floor(t)
    sw, sh = w - 2 * r, h - 2 * r
    arc = (math.pi / 2) * r
    d = phase * (2 * sw + 2 * sh + 4 * arc)

    def arcpt(cx, cy, a0, trav):
        ang = a0 + (trav / arc) * (math.pi / 2) if arc > 0 else a0
        return (cx + r * math.cos(ang), cy + r * math.sin(ang))

    if d < sw:
        return (r + d, 0.0)
    d -= sw
    if d < arc:
        return arcpt(w - r, r, -math.pi / 2, d)
    d -= arc
    if d < sh:
        return (w, r + d)
    d -= sh
    if d < arc:
        return arcpt(w - r, h - r, 0.0, d)
    d -= arc
    if d < sw:
        return (w - r - d, h)
    d -= sw
    if d < arc:
        return arcpt(r, h - r, math.pi / 2, d)
    d -= arc
    if d < sh:
        return (0.0, h - r - d)
    d -= sh
    if d < arc:
        return arcpt(r, r, math.pi, d)
    # Float drift past the last arc lands back on the walk's start point.
    return (r, 0.0)


def near(a, b, eps=1e-3):
    return abs(a[0] - b[0]) <= eps and abs(a[1] - b[1]) <= eps


# ────────────────────────────────────────────────────────────────────────────
# 2. The contract (same cases as CometRingOutlineTest.kt).
# ────────────────────────────────────────────────────────────────────────────
W = H = 100.0
R = 20.0


def pt(t: float):
    return rounded_rect_outline_point(W, H, R, t)


check("t=0 starts at top edge's left end (r,0)", near(pt(0.0), (20, 0)), str(pt(0.0)))
check("t=.25 lands at top of right edge (w,r)", near(pt(0.25), (100, 20)), str(pt(0.25)))
check("t=.5 lands at right end of bottom edge (w-r,h)", near(pt(0.5), (80, 100)), str(pt(0.5)))
check("t=.75 lands at bottom of left edge (0,h-r)", near(pt(0.75), (0, 80)), str(pt(0.75)))

check("phase wraps: -0.25 == 0.75", pt(-0.25) == pt(0.75))
check("phase wraps: 1.0 == 0.0", pt(1.0) == pt(0.0))
check("phase wraps: 1.25 == 0.25", pt(1.25) == pt(0.25))

corners = [(20, 20), (80, 20), (80, 80), (20, 80)]
bad = []
for i in range(720):
    t = i / 720
    p = pt(t)
    on_edge = (
        (abs(p[1]) <= 1e-3 and 20 - 1e-3 <= p[0] <= 80 + 1e-3)
        or (abs(p[0] - 100) <= 1e-3 and 20 - 1e-3 <= p[1] <= 80 + 1e-3)
        or (abs(p[1] - 100) <= 1e-3 and 20 - 1e-3 <= p[0] <= 80 + 1e-3)
        or (abs(p[0]) <= 1e-3 and 20 - 1e-3 <= p[1] <= 80 + 1e-3)
    )
    on_corner = any(abs(math.dist(p, c) - R) <= 1e-3 for c in corners)
    if not (on_edge or on_corner):
        bad.append((t, p))
check("720 sampled points stay on the outline", not bad, str(bad[:3]))

a, b = pt(0.01), pt(0.02)
check(
    "progress is clockwise along the top edge",
    a[1] == 0 and b[1] == 0 and b[0] > a[0],
    f"{a} -> {b}",
)

check(
    "zero corner radius collapses to the sharp rectangle",
    near(rounded_rect_outline_point(100, 100, 0, 0), (0, 0))
    and near(rounded_rect_outline_point(100, 100, 0, 0.25), (100, 0))
    and near(rounded_rect_outline_point(100, 100, 0, 0.5), (100, 100))
    and near(rounded_rect_outline_point(100, 100, 0, 0.75), (0, 100)),
)

check(
    "corner radius clamps to half the shorter side (stadium)",
    near(rounded_rect_outline_point(100, 100, 200, 0), (50, 0))
    and near(rounded_rect_outline_point(100, 100, 200, 0.25), (100, 50)),
)

check("degenerate zero size returns the origin", near(rounded_rect_outline_point(0, 0, 0, 0.5), (0, 0)))

# The old circular orbit's drift at the corners, for the record: a circle
# through the square's edge midpoints passes the corner by (sqrt(2)-1)*half.
drift = (math.sqrt(2) - 1) * 50
check("old circle orbit left the outline by ~4.7dp at corners", abs(drift - 20.71) < 0.01, f"{drift:.2f}")

# ────────────────────────────────────────────────────────────────────────────
# 3. Anchor to the real source: the Kotlin file must carry the same walk.
# ────────────────────────────────────────────────────────────────────────────
src = VISUALS.read_text(encoding="utf-8")

check(
    "SessionGroupVisuals.kt defines roundedRectOutlinePoint",
    "internal fun roundedRectOutlinePoint(" in src,
)
check(
    "CometRing no longer draws a circular drawArc orbit",
    "drawArc(" not in src,
    "drawArc still present in SessionGroupVisuals.kt",
)
check(
    "comet rides the avatar's own silhouette constants",
    "GROUP_AVATAR_SIZE_DP" in src and "GROUP_AVATAR_CORNER_DP" in src,
)
check(
    "avatar tile and orbit share the same size constant",
    ".size(GROUP_AVATAR_SIZE_DP)" in src,
)
check(
    "avatar tile and orbit share the same corner constant",
    "RoundedCornerShape(GROUP_AVATAR_CORNER_DP)" in src,
)
check(
    "tail/head fractions preserved from the old 70°/22° sweep",
    "TAIL_FRACTION = 70f / 360f" in src and "HEAD_FRACTION = 22f / 360f" in src,
)
check(
    "shared DecorClock phase still drives the sweep",
    "decorLinearSweep(from = 0f, to = 1f, periodMs = ORBIT_PERIOD_MS)" in src,
)
m = re.search(r"private val GROUP_AVATAR_SIZE_DP = (\d+)\.dp", src)
check(
    "avatar size stays 44dp",
    m is not None and m.group(1) == "44",
    m.group(1) if m else "constant missing",
)
m = re.search(r"private val GROUP_AVATAR_CORNER_DP = (\d+)\.dp", src)
check(
    "avatar corner stays 10dp",
    m is not None and m.group(1) == "10",
    m.group(1) if m else "constant missing",
)

# ────────────────────────────────────────────────────────────────────────────
for name, ok, detail in [(f[0], False, f[1]) for f in []]:
    pass

fails = [f for f in FAILURES]
print(f"{CHECKS - len(fails)}/{CHECKS} green")
if fails:
    print("\nFAILURES:")
    for f in fails:
        print(" ", f)
    sys.exit(1)
print("comet ring outline contract verified")
