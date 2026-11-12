# -*- coding: utf-8 -*-
"""Final compile fixes: suspend pushLiveSessionPreview + TAG for the split OAuth block."""
p1 = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelTurnPersistBackfill.kt"
s1 = open(p1, encoding="utf-8").read()
if "internal suspend fun ChatViewModel.pushLiveSessionPreview(" not in s1:
    s1 = s1.replace(
        "internal fun ChatViewModel.pushLiveSessionPreview(",
        "internal suspend fun ChatViewModel.pushLiveSessionPreview(",
    )
open(p1, "w", encoding="utf-8", newline="").write(s1)

p2 = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderOAuthBlock.kt"
s2 = open(p2, encoding="utf-8").read()
if "private const val TAG" not in s2:
    s2 = s2.replace(
        "package com.openminis.app.ui.settings\n",
        'package com.openminis.app.ui.settings\n\nprivate const val TAG = "ProviderDetail"\n',
        1,
    )
open(p2, "w", encoding="utf-8", newline="").write(s2)
print("fixed suspend + TAG")
