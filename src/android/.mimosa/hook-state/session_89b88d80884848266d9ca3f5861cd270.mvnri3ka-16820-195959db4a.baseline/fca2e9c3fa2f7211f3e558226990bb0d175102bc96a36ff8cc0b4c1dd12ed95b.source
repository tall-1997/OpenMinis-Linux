package com.openminis.app.data

import com.openminis.app.ui.chat.ChatViewModel
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactSummaryTailTest {
    @Test
    fun `summary clipping preserves both head and tail`() {
        val value = ChatViewModel.preserveSummaryEdges("HEAD-" + "x".repeat(600) + "-TAIL", 100)
        assertTrue(value.startsWith("HEAD-"))
        assertTrue(value.endsWith("-TAIL"))
        assertTrue(value.length <= 100)
    }
}
