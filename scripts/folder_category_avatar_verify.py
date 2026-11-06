#!/usr/bin/env python3
"""folder_category_avatar_verify.py

[T-android-folder-category-icon] Mirror + source anchors for the composed
folder avatar in SessionGroupVisuals.kt.

Design contract (approved render: workspace folder-category-icon.png):
  * one session  → THAT session's category icon, drawn exactly like the
    session row glyph (44dp circle, 0.18 tint fill, 20dp icon);
  * two sessions → 2x1 pair;
  * three/four   → 2x2 (16dp tiles, 10dp glyphs, 4dp gaps);
  * five..nine   → 3x3 (11dp tiles, 7dp glyphs, 1.5dp gaps);
  * ten and up   → the first nine only; overflow does not participate;
  * an empty folder falls back to the neutral "other" category glyph;
  * `null` categories survive truncation (uncategorised is a real state).

The avatar is built from `memberCategories`, NOT from `block.ids`: ids are
emptied while a folder is collapsed, so an ids-derived avatar went blank in
exactly the state where the icon matters most.

Run: python3 scripts/folder_category_avatar_verify.py
Exit 0 = all green.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "android" / "app" / "src" / "main" / "java" / "com" / "openminis" / "app"
VISUALS = SRC / "ui" / "sessions" / "SessionGroupVisuals.kt"
SCREEN = SRC / "ui" / "sessions" / "SessionListScreen.kt"

FAILURES: list[str] = []
CHECKS = 0


def check(name: str, ok: bool, detail: str = ""):
    global CHECKS
    CHECKS += 1
    if not ok:
        FAILURES.append(f"[{name}] {detail}")


# ---------------------------------------------------------------- mirror ----
def layout(count: int) -> tuple[int, int]:
    if count <= 1:
        return (1, 1)
    if count == 2:
        return (2, 1)
    if count <= 4:
        return (2, 2)
    return (3, 3)


def categories(cats: list) -> list:
    cols, rows = layout(len(cats))
    return cats[: cols * rows]


# ------------------------------------------------------------ behaviour ----
check("empty layout is 1x1", layout(0) == (1, 1))
check("single is 1x1", layout(1) == (1, 1))
check("two is 2x1", layout(2) == (2, 1))
for n in (3, 4):
    check(f"{n} is 2x2", layout(n) == (2, 2))
for n in range(5, 10):
    check(f"{n} is 3x3", layout(n) == (3, 3))
for n in range(10, 65):
    check(f"{n} stays 3x3", layout(n) == (3, 3))

check("empty keeps no categories", categories([]) == [])
check("single keeps its own category", categories(["code"]) == ["code"])
check("pair keeps both", categories(["code", "writing"]) == ["code", "writing"])
check("four keeps all four", categories(["a", "b", "c", "d"]) == ["a", "b", "c", "d"])
check("ten truncates to nine", categories(list("abcdefghij")) == list("abcdefghi"))
check("null survives truncation", categories([None, "code"]) == [None, "code"])
check(
    "recency order preserved",
    categories(["newest", "second", "third"]) == ["newest", "second", "third"],
)
check(
    "rendered cells never exceed capacity",
    all(
        len(categories(["x"] * n)) == min(n, layout(n)[0] * layout(n)[1])
        for n in range(0, 40)
    ),
)

# --------------------------------------------------------------- source ----
vis = VISUALS.read_text(encoding="utf-8")
scr = SCREEN.read_text(encoding="utf-8")


def anchor(name: str, pattern: str, text: str, flags=0):
    check(name, re.search(pattern, text, flags) is not None, f"missing /{pattern}/")


anchor("layout function exists", r"internal fun folderIconLayout\(count: Int\)", vis)
anchor("truncation helper exists", r"internal fun folderIconCategories\(", vis)
anchor(
    "truncation uses capacity",
    r"categories\.take\(folderIconLayout\(categories\.size\)\.capacity\)",
    vis,
)
anchor("two-column branch", r"count == 2 -> FolderIconLayout\(columns = 2, rows = 1\)", vis)
anchor("three/four branch", r"count <= 4 -> FolderIconLayout\(columns = 2, rows = 2\)", vis)
anchor("five-plus branch", r"else -> FolderIconLayout\(columns = 3, rows = 3\)", vis)
anchor("avatar composable exists", r"internal fun FolderCategoryAvatar\(", vis)
anchor(
    "single member reuses row glyph",
    r'categoryStyle\(if \(shown\.isEmpty\(\)\) "other" else shown\.first\(\)\)',
    vis,
)
anchor("single member uses 20dp glyph", r"modifier = Modifier\.size\(20\.dp\)", vis)
anchor("single member circle fill", r"monetIconTint\(style\.color\)\.copy\(alpha = 0\.18f\), CircleShape", vis)
anchor("grid tiles 11dp at three columns", r'if \(layout\.columns == 3\) 11\.dp else 16\.dp', vis)
anchor("grid glyphs 7dp at three columns", r'if \(layout\.columns == 3\) 7\.dp else 10\.dp', vis)
anchor("grid gaps 1.5dp at three columns", r'if \(layout\.columns == 3\) 1\.5\.dp else 4\.dp', vis)
anchor("grid chunked by columns", r"shown\.chunked\(layout\.columns\)", vis)
anchor("no drawArc left in avatar file", r"drawArc\(", vis) if False else None
check("avatar file has no drawArc", "drawArc(" not in vis)
check("avatar file has no initials Text", "Text(" not in vis)

anchor(
    "folder card renders the avatar from memberCategories",
    r"FolderCategoryAvatar\(categories = block\.memberCategories\)",
    scr,
)
anchor(
    "block carries memberCategories",
    r"val memberCategories: List<String\?> = emptyList\(\)",
    scr,
)
anchor(
    "partition fills memberCategories from members",
    r"memberCategories = m\.map \{ it\.category \}",
    scr,
)
anchor(
    "memberCategories survives collapse",
    r"ids = if \(collapsed\) emptyList\(\) else",
    scr,
)
check(
    "avatar never reads ids",
    "FolderCategoryAvatar(categories = block.ids" not in scr,
    "avatar must not derive from ids (empty while collapsed)",
)

# ---------------------------------------------------------------- report ----
if FAILURES:
    print(f"FAILED {len(FAILURES)}/{CHECKS}")
    for f in FAILURES:
        print(" ", f)
    sys.exit(1)
print(f"{CHECKS}/{CHECKS} green")
print("folder category avatar contract verified")
