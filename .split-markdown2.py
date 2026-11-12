# -*- coding: utf-8 -*-
"""Extract MarkdownParser's math helpers into MarkdownMathHelpers.kt (correct line-based slicing)."""
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
start = fn_idx
while start > 0 and not lines[start].strip().startswith("/**"):
    start -= 1
end = find_line(lambda l: "Walk the AST replacing math placeholders" in l, fn_idx)
while end > 0 and not lines[end].strip().startswith("/**"):
    end -= 1

block = "".join(lines[start:end])
assert "stripBlockquoteMarkers" in block and "looksLikeMath" in block, "unexpected block"

body = block.replace("    private fun ", "    fun ")
consts = (
    '    private val blankLineRegex = Regex("\\\\n[ \\\\t]*\\\\n")\n'
    '    private val trailingNlRegex = Regex("\\\\n[ \\\\t]*$")\n'
)
assert consts in s, "constants not found"
s = s.replace(consts, "")

rest = s.splitlines(keepends=True)
# locate the same block again in the post-constant removal text
fn2 = None
for i, l in enumerate(rest):
    if "private fun stripBlockquoteMarkers(" in l:
        fn2 = i
        break
assert fn2 is not None
st2 = fn2
while st2 > 0 and not rest[st2].strip().startswith("/**"):
    st2 -= 1
en2 = None
for i in range(fn2, len(rest)):
    if "Walk the AST replacing math placeholders" in rest[i]:
        en2 = i
        break
assert en2 is not None
while en2 > 0 and not rest[en2].strip().startswith("/**"):
    en2 -= 1
s2 = "".join(rest[:st2]) + "".join(rest[en2:])

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

for name in ("findClose", "findDoubleDollar", "findSingleDollar", "looksLikeMath",
             "stripBlockquoteMarkers", "isPlausibleDisplayBody"):
    s2 = re.sub(rf"(?<![A-Za-z.]){name}\(", f"MarkdownMathHelpers.{name}(", s2)
open(p, "w", encoding="utf-8", newline="").write(s2)
print("MarkdownParser now:", len(s2.splitlines()), "| helpers:", len(new_file.splitlines()))
