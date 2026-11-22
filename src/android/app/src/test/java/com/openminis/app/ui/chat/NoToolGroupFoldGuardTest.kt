package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-no-tool-group-fold] The list-level tool-group fold must stay gone.
 *
 * History: #274 added it, `43595461` reverted it on purpose (the maintainer
 * chose the round-card model), and #410 re-added the whole pipeline as a side
 * effect of a design-system refactor — which is how a default-on second fold
 * layer shipped on top of the round cards without any test objecting.
 *
 * The behavioural half of this contract lives in
 * [ChatFlatItemsProcessFoldTest] (`a running tool is reported by the card
 * instead of the list`). This guard covers the structural half: if someone
 * re-introduces the fold by copying the files back, the pipeline files
 * reappear and this test fails at build time.
 */
class NoToolGroupFoldGuardTest {

    private val removedPipelineFiles = listOf(
        "ui/chat/ChatToolFolds.kt",
        "ui/chat/ToolFoldPlanner.kt",
        "ui/chat/ToolGroupRow.kt",
    )

    @Test
    fun theListLevelToolFoldPipelineDidNotComeBack() {
        val root = ProductionSources.mainRoot()
        assertTrue("production sources not found", root != null)
        val present = removedPipelineFiles.filter { java.io.File(root, it).exists() }
        assertTrue(
            "the list-level tool fold pipeline came back: $present. It was reverted " +
                "by 43595461 on the maintainer's decision; the round-card model " +
                "(round cards + every step inside them) is the contract. Delete " +
                "those files again, or delete this test in the same change if you " +
                "really mean to bring the second fold layer back.",
            present.isEmpty(),
        )
    }

    @Test
    fun theFlatItemModelCarriesNoToolGroupRow() {
        val items = ProductionSources.read("ui/chat/ChatFlatItems.kt")
        assertFalse(
            "AssistantToolGroup is the folded tool run's list item; its presence means " +
            "the list-level fold is back",
            items.contains("class AssistantToolGroup("),
        )
        assertFalse(
            "applyToolFolds is the second fold pass over the flat list",
            items.contains("applyToolFolds("),
        )
    }
}