# -*- coding: utf-8 -*-
"""Fix visibility + buildCodeMask references introduced by the merge-time splits."""
base = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app"

def patch(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            print("WARN not found in", path.split('/')[-1], ":", old[:60])
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8", newline="").write(s)

# 1) MarkdownParser / MarkdownInlineHtml share buildCodeMask from MarkdownMathHelpers
patch(base + "/ui/markdown/MarkdownParser.kt", [
    ("val codeMask = buildCodeMask(chars)", "val codeMask = MarkdownMathHelpers.buildCodeMask(chars)"),
])
patch(base + "/ui/markdown/MarkdownInlineHtml.kt", [
    ("val codeMask = MarkdownParser.buildCodeMask(markdown)", "val codeMask = MarkdownMathHelpers.buildCodeMask(markdown)"),
])

# 2) thinkingStatus needed by the new speak file
patch(base + "/ui/chat/ChatViewModelGroupChatExt.kt", [
    ("private fun thinkingStatus(", "internal fun thinkingStatus("),
])

# 3) SubAgentExt helpers needed by the moved runOneSubAgentInner
patch(base + "/ui/chat/ChatViewModelSubAgentExt.kt", [
    ("private fun parseThinkingLevel(", "internal fun parseThinkingLevel("),
    ("private val SPAWN_STAGGER_MS", "internal val SPAWN_STAGGER_MS"),
    ("private fun pickRetryEntry(", "internal fun pickRetryEntry("),
    ("private suspend fun publishSubAgentUi(", "internal suspend fun publishSubAgentUi("),
    ("private fun isUpstreamTruncation(", "internal fun isUpstreamTruncation("),
])

# 4) Host composables now live in AppNavigationHosts but are called from AppNavigation
patch(base + "/ui/navigation/AppNavigationHosts.kt", [
    ("private fun HtmlBrowserHost(", "internal fun HtmlBrowserHost("),
    ("private fun AppNavigationShareHost(", "internal fun AppNavigationShareHost("),
])
print("visibility patches applied")
