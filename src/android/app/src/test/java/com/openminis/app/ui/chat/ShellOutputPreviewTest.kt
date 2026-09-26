package com.openminis.app.ui.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShellOutputPreviewTest {
    @Test fun outputBurstIsConflatedBoundedAndEndsWithLatestLine() = runTest {
        val frames = mutableListOf<String>()
        val preview = ShellOutputPreview(this, maxChars = 128, maxLines = 5) { frames += it }
        repeat(10_000) { preview.append("line-$it") }
        runCurrent()
        assertTrue(frames.isEmpty())
        advanceTimeBy(150)
        runCurrent()
        assertEquals(1, frames.size)
        assertTrue(frames.single().length <= 128)
        assertTrue(frames.single().lines().size <= 5)
        assertTrue(frames.single().endsWith("line-9999"))
        preview.append("final")
        preview.finish()
        assertEquals(2, frames.size)
        assertTrue(frames.last().endsWith("final"))
        advanceTimeBy(1000)
        assertEquals(2, frames.size)
    }

    @Test fun oneHugeLineAndCancellationCannotRetainOrPublishUnboundedText() = runTest {
        val frames = mutableListOf<String>()
        val preview = ShellOutputPreview(this, maxChars = 64) { frames += it }
        preview.append("x".repeat(100_000))
        preview.finish()
        assertEquals(64, frames.single().length)
        val cancelled = ShellOutputPreview(this) { frames += it }
        cancelled.append("never publish")
        cancelled.cancel()
        cancelled.append("ignored")
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, frames.size)
    }
}
