# -*- coding: utf-8 -*-
"""Fix ChatViewModelHistoryEdges imports/visibility after the split."""
p1 = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelHistoryEdges.kt"
s = open(p1, encoding="utf-8").read()
s = s.replace("import com.openminis.app.agent.HistoryDigest", "import com.openminis.app.ui.chat.HistoryDigest")
open(p1, "w", encoding="utf-8", newline="").write(s)

p2 = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"
s = open(p2, encoding="utf-8").read()
if "    private val _hasOlderMessages" in s:
    s = s.replace("    private val _hasOlderMessages", "    internal val _hasOlderMessages")
    open(p2, "w", encoding="utf-8", newline="").write(s)
    print("ChatViewModel: _hasOlderMessages -> internal")
else:
    print("WARN _hasOlderMessages already internal?")
print("imports fixed")
