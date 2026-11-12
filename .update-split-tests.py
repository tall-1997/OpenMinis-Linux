# -*- coding: utf-8 -*-
"""Update two source-scanning tests after the merge-time file splits."""

# 1) EmptyTurnFallbackTest: the reminder injection now lives in EmptyTurnRecoveryInject.kt
p = r"D:/minis-worktrees/integration/src/android/app/src/test/java/com/openminis/app/ui/chat/EmptyTurnFallbackTest.kt"
s = open(p, encoding="utf-8").read()
old = (
    "    @Test\n"
    "    fun `the reminder is still once per run`() {\n"
    "        assertEquals(2, branch.split(\"didInjectEmptyToolReminder = true\").size - 1)\n"
    "    }"
)
new = (
    "    @Test\n"
    "    fun `the reminder is still once per run`() {\n"
    "        // 注入实现已拆到 EmptyTurnRecoveryInject.kt：两个注入分支各返回 (true, true)，\n"
    "        // 委托处再置位一次，保证每轮只注入一次（拆分后位置变化，行为不变）。\n"
    "        val injectSrc = ProductionSources.read(\"ui/chat/EmptyTurnRecoveryInject.kt\")\n"
    "        assertEquals(2, injectSrc.split(\"true to true\").size - 1)\n"
    "        assertEquals(1, branch.split(\"didInjectEmptyToolReminder = true\").size - 1)\n"
    "    }"
)
assert old in s, "EmptyTurnFallbackTest block not found"
s = s.replace(old, new)
open(p, "w", encoding="utf-8", newline="").write(s)
print("EmptyTurnFallbackTest updated")

# 2) MultiAgentPageCleanupTest: the dispatch store lookup moved with runOneSubAgentInner
p = r"D:/minis-worktrees/integration/src/android/app/src/test/java/com/openminis/app/ui/settings/MultiAgentPageCleanupTest.kt"
s = open(p, encoding="utf-8").read()
old2 = (
    "        val subAgent = ProductionSources.read(\"ui/chat/ChatViewModelSubAgentExt.kt\")\n"
    "        assertTrue(subAgent.contains(\"SubAgentTypeStore.find\"))"
)
new2 = (
    "        // 执行主循环已拆到 ChatViewModelSubAgentRun.kt（架构行数上限），\n"
    "        // 两处都必须保持经 SubAgentTypeStore 解析类型。\n"
    "        val subAgent = ProductionSources.read(\"ui/chat/ChatViewModelSubAgentExt.kt\") +\n"
    "            ProductionSources.read(\"ui/chat/ChatViewModelSubAgentRun.kt\")\n"
    "        assertTrue(subAgent.contains(\"SubAgentTypeStore.find\"))"
)
assert old2 in s, "MultiAgentPageCleanupTest block not found"
s = s.replace(old2, new2)
open(p, "w", encoding="utf-8", newline="").write(s)
print("MultiAgentPageCleanupTest updated")
