# -*- coding: utf-8 -*-
"""Extract migrateGhostAlarms (T268) out of MinisApp into MinisAppGhostAlarms.kt."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/MinisApp.kt"
s = open(p, encoding="utf-8").read()

start_marker = "    // T268: replay pre-T266 alarms/timers from prefs through SET_ALARM/SET_TIMER, then clear the blob (idempotent, past-dated dropped).\n    private fun migrateGhostAlarms() {"
end_marker = "    /** Coil ImageLoader: MinisImageFetcher resolves `minis://`; SvgDecoder is required for res/raw vendor logos. */"
start = s.index(start_marker)
end = s.index(end_marker)
body = s[start:end]
assert "private fun migrateGhostAlarms()" in body

# Convert the member function into an internal extension in a new file.
member = body
replacement = ""
s = s[:start] + replacement + s[end:]
s = s.replace("runCatching { migrateGhostAlarms() }", "runCatching { migrateGhostAlarmsLegacy() }")

ext = member.replace(
    "    // T268: replay pre-T266 alarms/timers from prefs through SET_ALARM/SET_TIMER, then clear the blob (idempotent, past-dated dropped).\n    private fun migrateGhostAlarms() {",
    "// T268: replay pre-T266 alarms/timers from prefs through SET_ALARM/SET_TIMER, then clear the blob (idempotent, past-dated dropped).\n// 拆分自 MinisApp.kt（架构行数上限），行为逐字节保持。\ninternal fun MinisApp.migrateGhostAlarmsLegacy() {",
)
# dedent one level (member body was indented one level deeper than a top-level function body? keep as-is: body lines already 4-space; top-level function body also 4-space -> fine)
new_file = (
    "package com.openminis.app\n\n"
    "import android.content.Context\n"
    "import android.util.Log\n\n"
    + ext.strip("\n") + "\n"
)
open(p, "w", encoding="utf-8", newline="").write(s)
open(r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/MinisAppGhostAlarms.kt", "w", encoding="utf-8", newline="").write(new_file)
print("extracted; MinisApp splitlines now:", len(s.splitlines()), "| new file lines:", len(new_file.splitlines()))
