package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamSessionControllerTest {
    @Test
    fun throttleTiersMatchLegacyLadder() {
        val c = StreamSessionController(
            scope = TestScope(),
            messages = MutableStateFlow(emptyList()),
            streamingById = MutableStateFlow(emptyMap()),
            newlineFlushMinChars = 50,
            newlineFlushMaxLen = 5_000,
        )
        assertEquals(200L, c.streamFlushThrottleMs(0))
        assertEquals(200L, c.streamFlushThrottleMs(499))
        assertEquals(300L, c.streamFlushThrottleMs(500))
        assertEquals(500L, c.streamFlushThrottleMs(2_000))
        assertEquals(1_000L, c.streamFlushThrottleMs(32_000))
        assertEquals(1_500L, c.streamFlushThrottleMs(64_000))
        assertEquals(2_000L, c.streamFlushThrottleMs(128_000))
    }
}
