# -*- coding: utf-8 -*-
"""Extract MarkdownParser's math helpers into MarkdownMathHelpers.kt (architecture line cap)."""
import re

p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownParser.kt"
s = open(p, encoding="utf-8").read()
lines = s.splitlines(keepends=True)

def find_line(pred, start=0):
    for i in range(start, len(lines)):
        if pred(lines[i]):
            return i
    raise SystemExit("marker not found")

fn_idx = find_line(lambda l: "private fun stripBlockquoteMarkers(" in l)
# walk back to the KDoc opener
start = fn_idx
while start > 0 and not lines[start].strip().startswith("/**"):
    start -= 1
end = find_line(lambda l: "Walk the AST replacing math placeholders" in l, fn_idx)
# end is the text line inside the restoreMath KDoc; back up to its "/**"
while end > 0 and not lines[end].strip().startswith("/**"):
    end -= 1

block = "".join(lines[start:end])
assert "stripBlockquoteMarkers" in block and "looksLikeMath" in block

# object body: member functions lose the `private` modifier
body = block.replace("    private fun ", "    fun ")
# constants that move with isPlausibleDisplayBody
consts = (
    '    private val blankLineRegex = Regex("\\\\n[ \\\\t]*\\\\n")\n'
    '    private val trailingNlRegex = Regex("\\\\n[ \\\\t]*$")\n'
)
assert consts in s
s = s.replace(consts, "")
s = s[:start] + s[end:]
s = s.replace(
    "// ─── Restoring math placeholders",
    "// ─── Restoring math placeholders",
)
hdr = (
    "package com.openminis.app.ui.markdown\n\n"
    "/**\n"
    " * Markdown 数学提取的纯辅助函数（从 MarkdownParser 拆出，架构行数上限）：\n"
    " * 定界符扫描 / 货币启发式 / 引用块前缀剥离。行为逐字节保持。\n"
    " */\n"
    "internal object MarkdownMathHelpers {\n\n"
    '    private val blankLineRegex = Regex("\\\\n[ \\\\t]*\\\\n")\n'
    '    private val trailingNlRegex = Regex("\\\\n[ \\\\t]*$")\n\n'
)
new_file = hdr + body + "}\n"
open(r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownMathHelpers.kt",
     "w", encoding="utf-8", newline="").write(new_file)

# update call sites inside extractMath
for name in ("findClose", "findDoubleDollar", "findSingleDollar", "looksLikeMath",
             "stripBlockquoteMarkers", "isPlausibleDisplayBody"):
    s = re.sub(rf"(?<![A-Za-z.]){name}\(", f"MarkdownMathHelpers.{name}(", s)
open(p, "w", encoding="utf-8", newline="").write(s)
print("extracted; MarkdownParser splitlines now:", len(s.splitlines()),
      "| helpers lines:", len(new_file.splitlines()))
