package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-perm-three-modes] ZCode-parity session permissions: three tiers, one
 * legacy-read mapping, and the decision each tier produces at the gate.
 */
class SessionPermissionModeTest {

    // ---- Compat read (persisted `permission_mode` string -> tier) ----

@Test
    fun everyLegacyTokenMapsToATierNoWiderThanBefore() {
        // ZCode `yolo` spellings.
        assertEquals(SessionPermissionMode.FULL_ACCESS, SessionPermissionMode.fromStored("YOLO"))
        assertEquals(SessionPermissionMode.FULL_ACCESS, SessionPermissionMode.fromStored("YOYO"))
        assertEquals(SessionPermissionMode.FULL_ACCESS, SessionPermissionMode.fromStored(" yolo "))
        // [T-perm-no-widening] The two-tier "SAFE_AUTO" / "ASK" tokens meant
        // "reads auto-allow, everything else asks" - i.e. today's
        // ASK_BEFORE_CHANGES. Mapping them to AUTONOMOUS would have widened
        // every historical session and every unattended scheduled run into
        // "write files without asking".
        assertEquals(SessionPermissionMode.ASK_BEFORE_CHANGES, SessionPermissionMode.fromStored("SAFE_AUTO"))
        assertEquals(SessionPermissionMode.ASK_BEFORE_CHANGES, SessionPermissionMode.fromStored("ASK"))
        // Only the explicit new token selects the autonomous tier.
        assertEquals(SessionPermissionMode.AUTONOMOUS, SessionPermissionMode.fromStored("AUTONOMOUS"))
        // ZCode `build` spellings.
        assertEquals(SessionPermissionMode.ASK_BEFORE_CHANGES, SessionPermissionMode.fromStored("ASK_ALL"))
        // The retired lockdown tier lands on the strictest remaining tier.
        assertEquals(SessionPermissionMode.ASK_BEFORE_CHANGES, SessionPermissionMode.fromStored("OFF"))
        assertEquals(SessionPermissionMode.ASK_BEFORE_CHANGES, SessionPermissionMode.fromStored("DENY_ALL"))
    }

@Test
    fun unknownValuesNeverWidenTheGate() {
        for (raw in listOf(null, "", " ", "ALLOW_ALL", "READ_ONLY", "PLAN", "nonsense")) {
        assertEquals(
            "token=$raw must land on the safest tier",
            SessionPermissionMode.ASK_BEFORE_CHANGES,
            SessionPermissionMode.fromStored(raw),
        )
        }
    }

@Test
    fun storedTokenRoundTrips() {
        for (tier in SessionPermissionMode.entries) {
        assertEquals(tier, SessionPermissionMode.fromStored(tier.toStoredToken()))
        }
    }

@Test
    fun onlyTheTopTierIsAutomaticEverywhere() {
        assertFalse(SessionPermissionMode.ASK_BEFORE_CHANGES.isAutomatic())
        assertTrue(SessionPermissionMode.AUTONOMOUS.isAutomatic())
        assertTrue(SessionPermissionMode.FULL_ACCESS.isAutomatic())
        assertFalse(SessionPermissionMode.AUTONOMOUS.isFullAccess())
        assertTrue(SessionPermissionMode.FULL_ACCESS.isFullAccess())
    }

    // ---- Tier -> gate mode mapping ----

@Test
    fun tierToGateMode() {
        assertEquals(PermissionMode.ASK, SessionPermissionMode.ASK_BEFORE_CHANGES.toGateMode())
        assertEquals(PermissionMode.GUARDED, SessionPermissionMode.AUTONOMOUS.toGateMode())
        assertEquals(PermissionMode.ALLOW_ALL, SessionPermissionMode.FULL_ACCESS.toGateMode())
    }

    // ---- Decision matrix at the gate ----

    private val gate = SecurityGateImpl()
    private val read = gate.classify("file_read", """{"path":"/var/minis/workspace/a.txt"}""")
    private val write = gate.classify("file_write", """{"path":"/var/minis/workspace/a.txt"}""")
    private val safeShell = gate.classify("shell_exec", """{"command":"ls /var/minis/workspace"}""")
    private val dangerousShell = gate.classify("shell_exec", """{"command":"iptables -F"}""")
    private val fatal = gate.classify("shell_exec", """{"command":"rm -rf /"}""")

@Test
    fun askBeforeChangesRunsReadsAndAsksForEveryChange() {
        val mode = SessionPermissionMode.ASK_BEFORE_CHANGES.toGateMode()
        assertTrue(gate.decide(read, mode) is Decision.Allow)
        assertTrue(gate.decide(write, mode) is Decision.NeedConfirm)
        assertTrue(gate.decide(dangerousShell, mode) is Decision.NeedConfirm)
        assertTrue("a fatal command is refused outright in the strict tier", gate.decide(fatal, mode) is Decision.Denied)
    }

@Test
    fun autonomousRunsWritesAndOnlyStopsForRisk() {
        val mode = SessionPermissionMode.AUTONOMOUS.toGateMode()
        assertTrue(gate.decide(read, mode) is Decision.Allow)
        assertTrue("自主模式 must not nag on ordinary writes", gate.decide(write, mode) is Decision.Allow)
        assertTrue(gate.decide(safeShell, mode) is Decision.Allow)
        val risk = gate.decide(dangerousShell, mode)
        assertTrue("a risky command still asks", risk is Decision.NeedConfirm)
        assertEquals("the gate emits an ASCII sentinel the UI localizes", GUARDED_RISK_REASON, (risk as Decision.NeedConfirm).reason)
    }

@Test
    fun fullAccessRunsEverythingButTheFatalSet() {
        val mode = SessionPermissionMode.FULL_ACCESS.toGateMode()
        assertTrue(gate.decide(read, mode) is Decision.Allow)
        assertTrue(gate.decide(write, mode) is Decision.Allow)
        assertTrue(gate.decide(safeShell, mode) is Decision.Allow)
        assertTrue(gate.decide(dangerousShell, mode) is Decision.Allow)
        val d = gate.decide(fatal, mode)
        assertTrue(d is Decision.NeedConfirm && d.mustPrompt)
    }

@Test
    fun cycleAscendsAutonomyAndWrapsToTheSafestTier() {
        assertEquals(SessionPermissionMode.AUTONOMOUS, SessionPermissionMode.ASK_BEFORE_CHANGES.next())
        assertEquals(SessionPermissionMode.FULL_ACCESS, SessionPermissionMode.AUTONOMOUS.next())
        assertEquals(SessionPermissionMode.ASK_BEFORE_CHANGES, SessionPermissionMode.FULL_ACCESS.next())
    }
}
