package com.openminis.app.browser

import com.openminis.app.ProductionSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * (GH#245) The timing rules behind the browser tab pool's self-healing, tested
 * with virtual-time coroutines (no WebView), plus source pins on the call
 * sites that consume them.
 *
 * Ported from upstream `b4c0661d`. Two upstream cases were rewritten rather
 * than copied because this fork implements the same guarantee differently:
 * upstream extracts `dropAndRebuildUnusableTabs()`/`releaseTabResources()` and
 * reuses tab ids with a "was rebuilt as tab N" notice, while here the drop is
 * inline in `acquireTab` (via the existing `removeTab`) and ids are never
 * reused, so the model is told about the replacement id in
 * `runAcquiredAction` instead. The assertions pin what this fork actually
 * does; the behaviour under test is the same.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserActionGuardTest {

    // -- Cancellation through the per-tab lock ------------------------------

    @Test
    fun `a cancellation inside withLock releases the lock`() = runTest {
        // Guards the conclusion the fix rests on: the CLI's 90s withTimeout
        // cancels into the pool, and the lock must come free with it.
        val mutex = Mutex()
        val holder = launch { mutex.withLock { awaitCancellation() } }
        testScheduler.advanceUntilIdle()
        assertTrue(mutex.isLocked)
        holder.cancel()
        holder.join()
        assertFalse("cancelled holder must release the tab lock", mutex.isLocked)
    }

    @Test
    fun `the split lock-then-finally pattern releases the lock when the CLI times out`() = runTest {
        // Mirrors executeSerialized after the split: lockWithin, then the
        // action in try, unlock in finally. The CLI's outer withTimeout fires
        // while the action is stuck.
        val mutex = Mutex()
        try {
            withTimeout(90_000L) {
                assertTrue(BrowserActionGuard.lockWithin(mutex, 60_000L))
                try {
                    awaitCancellation()
                } finally {
                    mutex.unlock()
                }
            }
            fail("expected the CLI timeout")
        } catch (_: TimeoutCancellationException) {
        }
        assertFalse(mutex.isLocked)
    }

    @Test
    fun `lockWithin reports busy only when another holder keeps the lock`() = runTest {
        val mutex = Mutex()
        assertTrue("a free lock is taken at once", BrowserActionGuard.lockWithin(mutex, 60_000L))
        mutex.unlock()

        val holder = launch { mutex.withLock { delay(120_000L) } }
        testScheduler.runCurrent()
        val start = testScheduler.currentTime
        assertFalse(BrowserActionGuard.lockWithin(mutex, 60_000L))
        assertEquals("gave up exactly at the lock-wait window", 60_000L, testScheduler.currentTime - start)
        assertTrue("the other holder still owns it", mutex.isLocked)
        holder.join()
        assertFalse(mutex.isLocked)
    }

    // -- Action deadline -----------------------------------------------------

    @Test
    fun `a healthy action returns its value and flags nothing`() = runTest {
        val suspects = mutableListOf<String>()
        val out = BrowserActionGuard.runGuarded(
            clock = { testScheduler.currentTime },
            onSuspect = { suspects += it },
        ) { delay(2_000L); "ok" }
        assertEquals(BrowserActionGuard.Outcome.Done("ok"), out)
        assertTrue(suspects.isEmpty())
    }

    @Test
    fun `an action past the deadline is reported as a deadline and flags the tab`() = runTest {
        val suspects = mutableListOf<String>()
        val out = BrowserActionGuard.runGuarded(
            clock = { testScheduler.currentTime },
            onSuspect = { suspects += it },
        ) { awaitCancellation() }
        assertTrue(out is BrowserActionGuard.Outcome.DeadlineExceeded)
        assertEquals(
            BrowserActionGuard.ACTION_DEAD_TIMEOUT_MS,
            (out as BrowserActionGuard.Outcome.DeadlineExceeded).elapsedMs,
        )
        assertEquals("flagged exactly once", 1, suspects.size)
    }

    @Test
    fun `a wedged WebView becomes an outcome, not a thrown exception`() = runTest {
        val suspects = mutableListOf<String>()
        val out = BrowserActionGuard.runGuarded(
            clock = { testScheduler.currentTime },
            onSuspect = { suspects += it },
        ) { throw WebViewWedgedException("browser tab stopped responding") }
        assertEquals(BrowserActionGuard.Outcome.Wedged("browser tab stopped responding"), out)
        // The manager already flagged itself before throwing.
        assertTrue(suspects.isEmpty())
    }

    // -- Cancellation threshold ---------------------------------------------

    @Test
    fun `a quick cancellation (user pressed Stop) is rethrown and does not flag the tab`() = runTest {
        val suspects = mutableListOf<String>()
        var ranOn = false
        try {
            withTimeout(5_000L) {
                BrowserActionGuard.runGuarded(
                    clock = { testScheduler.currentTime },
                    onSuspect = { suspects += it },
                ) { awaitCancellation() }
                ranOn = true
            }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertFalse("runGuarded must rethrow, not return", ranOn)
        assertTrue("a healthy page must not be thrown away", suspects.isEmpty())
    }

    @Test
    fun `a long cancellation (CLI gave up) is rethrown and flags the tab`() = runTest {
        val suspects = mutableListOf<String>()
        var ranOn = false
        try {
            // Deadline above the outer timeout so the CANCEL path is what fires.
            withTimeout(90_000L) {
                BrowserActionGuard.runGuarded(
                    deadlineMs = 300_000L,
                    clock = { testScheduler.currentTime },
                    onSuspect = { suspects += it },
                ) { awaitCancellation() }
                ranOn = true
            }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertFalse("runGuarded must rethrow, not return", ranOn)
        assertEquals(1, suspects.size)
        assertTrue(suspects.single(), suspects.single().startsWith("cancelled after"))
    }

    @Test
    fun `the thresholds are ordered so the pool trips before the CLI gives up`() {
        val cli = 90_000L
        val longestLegitAction = 60_000L // fetch promise await; wait_for_dom_stable cap
        assertTrue(BrowserActionGuard.JS_EVAL_TIMEOUT_MS < BrowserActionGuard.WEDGE_SUSPECT_MS)
        assertTrue(BrowserActionGuard.WEDGE_SUSPECT_MS < BrowserActionGuard.ACTION_DEAD_TIMEOUT_MS)
        assertTrue(BrowserActionGuard.ACTION_DEAD_TIMEOUT_MS > longestLegitAction)
        assertTrue(BrowserActionGuard.ACTION_DEAD_TIMEOUT_MS < cli)
        val handler = ProductionSources.read("sandbox/offload/BrowserUseOffloadHandler.kt")
        assertTrue("CLI timeout still 90s", handler.contains("EXECUTE_TIMEOUT_MS = 90_000L"))
    }

    // -- Source pins on the consumers ---------------------------------------

    private val manager by lazy { ProductionSources.read("browser/BrowserUseManager.kt") }
    private val pool by lazy { ProductionSources.read("browser/BrowserTabPool.kt") }

    @Test
    fun `evaluateJavascript is bounded and flags the tab on timeout`() {
        val body = manager.substringAfter("private suspend fun evaluateJavascript(")
            .substringBefore("private suspend fun evaluateAndReturn")
        assertTrue(
            "must await with a timeout",
            body.contains("withTimeoutOrNull(timeoutMs) { deferred.await() }"),
        )
        assertTrue("must flag the tab", body.contains("markWedged("))
        assertTrue(
            "must fail fast on a dead renderer",
            body.contains("if (isRendererDead) throw WebViewWedgedException"),
        )
        assertFalse("no unbounded await left", body.contains("\n        deferred.await()\n"))
    }

    @Test
    fun `executeSerialized separates the lock wait from the action`() {
        val body = pool.substringAfter("private suspend fun executeSerialized(")
            .substringBefore("private suspend fun runAcquiredAction(")
        assertTrue(body.contains("BrowserActionGuard.lockWithin(mutex, TAB_SERIAL_WAIT_TIMEOUT_MS)"))
        assertTrue(body.contains("mutex.unlock()"))
        assertFalse("the action must not run inside the lock-wait timeout", body.contains("withTimeoutOrNull"))
    }

    @Test
    fun `every page operation runs under the guard`() {
        val body = pool.substringAfter("private suspend fun runAcquiredAction(")
            .substringBefore("private fun armImplicitGraceRelease(")
        assertTrue(body.contains("BrowserActionGuard.runGuarded("))
        assertTrue(body.contains("tab.manager.markWedged(reason)"))
        assertEquals(
            "execute() is called once, inside the guard",
            1,
            Regex("tab\\.manager\\.execute\\(").findAll(body).count(),
        )
    }

    @Test
    fun `acquireTab retires unusable tabs before picking one`() {
        // Dead-tab cleanup moved from acquireTab to runAcquiredAction
        // to avoid removing the per-tab serial lock while the caller holds it.
        // The cleanup runs BEFORE acquireTab is called, preserving the contract.
        val runAction = pool.substringAfter("private suspend fun runAcquiredAction(")
            .substringBefore("acquireTab(")
        assertTrue(
            "cleanup must run before acquireTab is called",
            runAction.contains("_tabs.value.filter { it.manager.isUnusable }"),
        )
        assertTrue(
            "removeTab defers teardown for in-flight calls",
            runAction.contains("removeTab(dead, rememberUrl = true)"),
        )
        // The replacement arrives under a NEW id (ids are never reused), so the
        // model has to be told rather than silently reading the wrong tab.
        val dispatch = pool.substringAfter("val rebuildNote = if (acquireTabId != null")
        assertTrue(dispatch.contains("was rebuilt because its WebView stopped responding"))
    }

    // -- [T-android-webview-render-process-gone] ----------------------------

    @Test
    fun `no WebViewClient in the app can kill the host when its renderer dies`() {
        // (GH#341) One renderer serves every WebView in the process, so an
        // unhandled death takes the WHOLE app down (SIGTRAP: "Render process's
        // crash wasn't handled by all associated webviews"). Counting instead of
        // name-listing means a renamed or newly added client cannot slip past.
        val root = requireNotNull(ProductionSources.mainRoot()) {
            "production sources not found next to the test working dir"
        }
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val src = file.readText()
                val declared = Regex("object : WebViewClient\\(\\) \\{").findAll(src).count()
                if (declared == 0) return@forEach
                val handled = Regex("override fun onRenderProcessGone\\(").findAll(src).count()
                if (declared != handled) {
                    offenders += "${file.relativeTo(root).path} ($declared client(s), $handled override(s))"
                }
            }
        assertTrue("unhandled renderer deaths would crash the app: $offenders", offenders.isEmpty())

        val helper = ProductionSources.read("ui/webview/WebViewRenderProcess.kt")
        assertTrue("the shared handler must report handled", helper.contains("return true"))
    }
}
