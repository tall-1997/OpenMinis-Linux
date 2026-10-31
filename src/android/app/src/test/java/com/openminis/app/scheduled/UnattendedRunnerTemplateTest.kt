package com.openminis.app.scheduled

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [v3] JVM test for the shared unattended-runner template
 * [ScheduledAgentRunner.runUnattended]: the MinisApp readiness guard must
 * degrade to a null dispatch (never a crash) when the process is not a
 * half-built MinisApp — the exact shape GH#156 produced — and must report the
 * abort through [runUnattended]'s onReady hook exactly once, with no dispatch
 * and no completion side effects.
 *
 * A plain android.app.Application is NOT a MinisApp, so the guard's
 * `as? MinisApp` branch is exercised directly (same rationale as
 * ScheduledNewSessionPersistenceTest: no sandbox wiring under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UnattendedRunnerTemplateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `guard aborts a non-MinisApp process with onReady(null) and a null return`() = runBlocking {
        val ready = mutableListOf<String?>()
        val dispatched = mutableListOf<String>()
        val completed = mutableListOf<String>()

        val result = ScheduledAgentRunner.runUnattended(
            context = context,
            logTag = "UnattendedTemplateTest",
            abortSuffix = "skipping rule test-1",
            subject = "rule test-1",
            sessionTitle = "auto_rule_test-1_123",
            source = "automation",
            prompt = "hello",
            onReady = { ready.add(it) },
            onDispatched = { dispatched.add(it) },
            onCompleted = { _, sessionId, _ -> completed.add(sessionId) },
        )

        assertNull("half-built app must degrade to a skipped run", result)
        assertEquals("onReady reports the abort exactly once", listOf<String?>(null), ready)
        assertTrue("nothing dispatched", dispatched.isEmpty())
        assertTrue("no completion side effects", completed.isEmpty())
    }

    @Test
    fun `template defaults are inert - no hooks means no observable work on abort`() = runBlocking {
        // All hooks at their defaults: the template must still return cleanly.
        val result = ScheduledAgentRunner.runUnattended(
            context = context,
            logTag = "UnattendedTemplateTest",
            abortSuffix = "quick send aborted",
            subject = "quick",
            sessionTitle = "",
            source = "quick",
            prompt = "hi",
        )
        assertNull(result)
    }
}
