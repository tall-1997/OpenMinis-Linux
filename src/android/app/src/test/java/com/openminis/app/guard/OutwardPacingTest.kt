package com.openminis.app.guard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutwardPacingTest {

    private fun args(vararg pairs: Pair<String, String>): JsonObject {
        val content = pairs.joinToString(",") { "\"${it.first}\":\"${it.second}\"" }
        return Json.parseToJsonElement("{$content}") as JsonObject
    }

    @Test
    fun firstOutwardActionForKeyWaitsNothing() {
        val pacing = OutwardPacing { lo, _ -> lo }
        assertEquals(0L, pacing.waitBefore("app", nowMs = 1_000))
    }

    @Test
    fun laterOutwardActionsWaitTheRandomGapCreditingElapsedTime() {
        val pacing = OutwardPacing { lo, _ -> lo }
        pacing.noteDone("app", nowMs = 0)
        assertEquals(8_000L, pacing.waitBefore("app", nowMs = 0)) // full gap, no time passed
        assertEquals(3_000L, pacing.waitBefore("app", nowMs = 5_000)) // 5 s already spent
        assertEquals(0L, pacing.waitBefore("app", nowMs = 8_000)) // gap fully served
    }

    @Test
    fun gapGrowsWithConsecutiveOutwardActionsOfTheSameKey() {
        val lowerBound = OutwardPacing { lo, _ -> lo }
        repeat(3) { lowerBound.noteDone("app", nowMs = 0) }
        assertEquals(9_000L, lowerBound.waitBefore("app", nowMs = 0)) // 8000 + 2*500

        val upperBound = OutwardPacing { _, hi -> hi }
        repeat(10) { upperBound.noteDone("app", nowMs = 0) }
        assertEquals(29_000L, upperBound.waitBefore("app", nowMs = 0)) // 20000 + 9*1000
    }

    @Test
    fun gapNeverExceeds30Seconds() {
        val upperBound = OutwardPacing { _, hi -> hi }
        repeat(11) { upperBound.noteDone("app", nowMs = 0) } // 20000 + 10*1000 would be 30k
        upperBound.noteDone("app", nowMs = 0)
        assertEquals(OutwardPacing.MAX_GAP_MS, upperBound.waitBefore("app", nowMs = 0))
    }

    @Test
    fun entriesOlderThanTheWindowArePruned() {
        val pacing = OutwardPacing { lo, _ -> lo }
        pacing.noteDone("app", nowMs = 0)
        val muchLater = 31 * 60_000L
        assertEquals(0L, pacing.waitBefore("app", nowMs = muchLater))
        // only this one entry is inside the window: a full first gap again, not a raised one
        pacing.noteDone("app", nowMs = muchLater)
        assertEquals(8_000L, pacing.waitBefore("app", nowMs = muchLater))
    }

    @Test
    fun differentKeysArePacedIndependently() {
        val pacing = OutwardPacing { lo, _ -> lo }
        pacing.noteDone("appA", nowMs = 0)
        assertEquals(0L, pacing.waitBefore("appB", nowMs = 0))
    }

    @Test
    fun keywordTableMatchesToolNamesAndTextArguments() {
        assertTrue(OutwardPacing.isOutward("send_message", JsonObject(emptyMap()))) // tool name
        assertTrue(OutwardPacing.isOutward("ui_action", args("text" to "please submit the form")))
        assertTrue(OutwardPacing.isOutward("ui_action", args("text" to "Reply to their comment")))
        assertTrue(OutwardPacing.isOutward("ui_action", args("text" to "发送这条消息")))
        assertTrue(OutwardPacing.isOutward("ui_action", args("text" to "关注这个账号")))
        assertTrue(OutwardPacing.isOutward("ui_action", args("text" to "打招呼")))
        assertTrue(OutwardPacing.isOutward("ui_action", args("text" to "投递简历")))
        assertFalse(OutwardPacing.isOutward("ui_action", args("text" to "type hello world")))
        assertFalse(OutwardPacing.isOutward("ui_read", JsonObject(emptyMap())))
    }

    @Test
    fun appKeyComesFromTheFirstPresentPackageLikeArgField() {
        assertEquals("com.twitter.android", OutwardPacing.appKey(args("package" to "com.twitter.android", "text" to "x")))
        assertEquals("com.other", OutwardPacing.appKey(args("app" to "com.other")))
        assertNull(OutwardPacing.appKey(args("text" to "x")))
    }
}
