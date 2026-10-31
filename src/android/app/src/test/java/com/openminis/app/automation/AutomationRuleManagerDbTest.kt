package com.openminis.app.automation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [v3 P1-11 / v3 二-9] JVM tests for the AutomationRuleManager DB-backed
 * behaviours this review batch touched, driven against a real in-memory Room
 * database via the [AutomationRuleManager.daoOverride] seam (Robolectric runs
 * with a plain Application, so the MinisApp lookup would be null):
 *
 *  - the process-level hasActiveRules short-circuit: no-rules devices must
 *    probe the DB once per process, then never again per event, and must not
 *    probe at all once sync() says rules exist,
 *  - the automation_runs 30-day rolling retention enforced by sync(),
 *  - the app_opened producer → matcher → run-row chain end to end.
 */
@RunWith(RobolectricTestRunner::class)
// Plain android.app.Application, NOT MinisApp — same rationale as
// ScheduledNewSessionPersistenceTest (no sandbox wiring under Robolectric).
@Config(sdk = [35], application = android.app.Application::class)
class AutomationRuleManagerDbTest {

    private lateinit var db: AppDatabase
    private lateinit var countingDao: CountingAutomationRuleDao
    private lateinit var manager: AutomationRuleManager
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Fixed clock so the retention window arithmetic is exact. */
    private val nowMs = 1_800_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        countingDao = CountingAutomationRuleDao(db.automationRuleDao())
        manager = AutomationRuleManager(context, clock = { nowMs })
        manager.daoOverride = countingDao
    }

    @After
    fun tearDown() {
        manager.daoOverride = null
        db.close()
    }

    /** Delegating DAO that counts enabled() reads — the probe the short-circuit must eliminate. */
    private class CountingAutomationRuleDao(
        private val delegate: AutomationRuleDao,
    ) : AutomationRuleDao by delegate {
        val enabledCalls = AtomicInteger()

        override suspend fun enabled(): List<AutomationRuleEntity> {
            enabledCalls.incrementAndGet()
            return delegate.enabled()
        }
    }

    private fun rule(
        id: String,
        trigger: Trigger,
        enabled: Boolean = true,
    ) = AutomationRuleEntity(
        id = id,
        name = "rule-$id",
        specJson = RuleSpec(
            name = "rule-$id",
            trigger = trigger,
            action = RuleAction.Agent(prompt = "do things"),
        ).toJsonString(),
        enabled = enabled,
        createdAt = nowMs,
        updatedAt = nowMs,
    )

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
    }

    @Test
    fun `sync reflects rule existence in hasActiveRules`() = runBlocking {
        manager.sync()
        assertFalse("empty DB → no active rules", manager.hasActiveRules)

        db.automationRuleDao().upsert(rule("r1", Trigger.AppOpened("com.target")))
        manager.sync()
        assertTrue("enabled rule → active", manager.hasActiveRules)

        // Disabling the only rule (every CRUD path ends in sync) drops the flag.
        assertTrue(manager.setEnabled("r1", false))
        assertFalse("disabled rule → no active rules", manager.hasActiveRules)

        assertTrue(manager.setEnabled("r1", true))
        assertTrue(manager.hasActiveRules)
        assertTrue(manager.delete("r1"))
        assertFalse("deleted rule → no active rules", manager.hasActiveRules)
    }

    @Test
    fun `no-rule events probe the DB once then short-circuit`() = runBlocking {
        // First event in a fresh process: the one-shot probe reads enabled().
        manager.onEventSuspend(AutomationEvent.Notification("com.a", "t", "b"))
        assertEquals(1, countingDao.enabledCalls.get())

        // Subsequent no-rule events must NOT hit the DB again (P1-11).
        manager.onEventSuspend(AutomationEvent.AppOpened("com.b"))
        manager.onEventSuspend(AutomationEvent.Screen("on"))
        assertEquals("probe must run exactly once", 1, countingDao.enabledCalls.get())

        // Once sync() has seen a rule, events skip the probe branch entirely.
        db.automationRuleDao().upsert(rule("r1", Trigger.ScreenOn))
        manager.sync()
        assertTrue(manager.hasActiveRules)
        manager.onEventSuspend(AutomationEvent.Screen("on"))
        manager.onEventSuspend(AutomationEvent.AppOpened("com.c"))
        assertEquals("flagged-active manager must never probe", 1, countingDao.enabledCalls.get())
    }

    @Test
    fun `sync enforces the 30-day automation_runs retention`() = runBlocking {
        val day = TimeUnit.DAYS.toMillis(1)
        val dao = db.automationRuleDao()
        dao.insertRun(AutomationRunEntity(ruleId = "r1", firedAt = nowMs - 31 * day, outcome = "ok"))
        val boundary = dao.insertRun(
            AutomationRunEntity(ruleId = "r1", firedAt = nowMs - 30 * day, outcome = "ok"),
        )
        val fresh = dao.insertRun(AutomationRunEntity(ruleId = "r1", firedAt = nowMs - day, outcome = "ok"))

        manager.sync()

        val remaining = dao.recentRunsBlocking("r1", 50).map { it.runId }.toSet()
        assertTrue("31-day-old run must be purged", remaining.none { it != boundary && it != fresh })
        assertTrue("run exactly at the cutoff stays (fired_at < cutoff)", boundary in remaining)
        assertTrue("fresh run stays", fresh in remaining)
        assertEquals(2, remaining.size)
    }

    @Test
    fun `appOpened event fires a matching rule into the runs table`() = runBlocking {
        val dao = db.automationRuleDao()
        dao.upsert(rule("r1", Trigger.AppOpened("com.target")))
        manager.sync()
        assertTrue(manager.hasActiveRules)

        manager.onEventSuspend(AutomationEvent.AppOpened("com.target"))

        // fire() is async (SupervisorJob scope): wait for the run row to be
        // finalized. Under Robolectric the agent start degrades to an error
        // outcome (no MinisApp), which is the expected terminal state here.
        awaitUntil {
            dao.recentRunsBlocking("r1", 10).firstOrNull()?.outcome?.startsWith("error:") == true
        }
        val fired = dao.recentRunsBlocking("r1", 10).firstOrNull()
        assertNotNull("matching app_opened must record a run", fired)
        assertEquals("error: could not start unattended run", fired?.outcome)

        // A different package must not fire (exact-package matcher).
        manager.onEventSuspend(AutomationEvent.AppOpened("com.other"))
        Thread.sleep(150) // give any (wrongful) async fire a beat
        assertEquals("non-matching package records nothing", 1, dao.recentRunsBlocking("r1", 10).size)
    }
}
