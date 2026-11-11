package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [#31] Four-tier session permission: persistence compat read + tier → gate
 * mode mapping + the decision matrix each tier produces at the gate.
 */
class SessionPermissionModeTest {

    // ── Compat read (persisted `permission_mode` string → tier) ──────────

    @Test
    fun legacyTokensMapFaithfully() {
        // The two-tier "ASK" token meant "reads auto-allow, the rest asks" —
        // exactly today's SAFE_AUTO — so legacy sessions keep their behaviour.
        assertEquals(SessionPermissionMode.SAFE_AUTO, SessionPermissionMode.fromStored("ASK"))
        // The strict ask-everything tier has its own token.
        assertEquals(SessionPermissionMode.ASK, SessionPermissionMode.fromStored("ASK_ALL"))
        assertEquals(SessionPermissionMode.YOLO, SessionPermissionMode.fromStored("YOLO"))
        // Historical spelling the two-tier app actually wrote.
        assertEquals(SessionPermissionMode.YOLO, SessionPermissionMode.fromStored("YOYO"))
        // Case/whitespace tolerant.
        assertEquals(SessionPermissionMode.YOLO, SessionPermissionMode.fromStored(" yolo "))
        assertEquals(SessionPermissionMode.SAFE_AUTO, SessionPermissionMode.fromStored("SAFE_AUTO"))
        assertEquals(SessionPermissionMode.OFF, SessionPermissionMode.fromStored("OFF"))
    }

    @Test
    fun unknownValuesNeverWidenTheGate() {
        assertEquals(SessionPermissionMode.ASK, SessionPermissionMode.fromStored(null))
        assertEquals(SessionPermissionMode.ASK, SessionPermissionMode.fromStored(""))
        assertEquals(SessionPermissionMode.ASK, SessionPermissionMode.fromStored("   "))
        // Retired global tokens and garbage land on ASK.
        assertEquals(SessionPermissionMode.ASK, SessionPermissionMode.fromStored("ALLOW_ALL"))
        assertEquals(SessionPermissionMode.ASK, SessionPermissionMode.fromStored("READ_ONLY"))
        assertEquals(SessionPermissionMode.ASK, SessionPermissionMode.fromStored("PLAN"))
        assertEquals(SessionPermissionMode.ASK, SessionPermissionMode.fromStored("nonsense"))
        // DENY_ALL was the old spell of the OFF tier — keep reading it back.
        assertEquals(SessionPermissionMode.OFF, SessionPermissionMode.fromStored("DENY_ALL"))
    }

    @Test
    fun storedTokenRoundTrips() {
        for (tier in SessionPermissionMode.entries) {
            assertEquals(tier, SessionPermissionMode.fromStored(tier.toStoredToken()))
        }
    }

    // ── Tier → gate mode mapping ─────────────────────────────────────────

    @Test
    fun tierToGateMode() {
        assertEquals(PermissionMode.DENY_ALL, SessionPermissionMode.OFF.toGateMode())
        assertEquals(PermissionMode.ASK_ALL, SessionPermissionMode.ASK.toGateMode())
        assertEquals(PermissionMode.ASK, SessionPermissionMode.SAFE_AUTO.toGateMode())
        assertEquals(PermissionMode.ALLOW_ALL, SessionPermissionMode.YOLO.toGateMode())
    }

    // ── Four-tier decision matrix at the gate ────────────────────────────
    // Columns per tier: what a read tool / a write tool / a safe shell
    // command gets. OFF denies everything; ASK asks for everything;
    // SAFE_AUTO auto-runs the read-only/safe surface and asks for the rest;
    // YOLO auto-runs everything except fatal-confirm.

    private val gate = SecurityGateImpl()
    private val read = gate.classify("file_read", """{"path":"/var/minis/workspace/a.txt"}""")
    private val write = gate.classify("file_write", """{"path":"/var/minis/workspace/a.txt"}""")
    private val safeShell = gate.classify("shell_exec", """{"command":"ls /var/minis/workspace"}""")
    private val dangerousShell = gate.classify("shell_exec", """{"command":"rm -rf /var/minis/workspace/x"}""")

    @Test
    fun offDeniesEverything() {
        assertTrue(gate.decide(read, SessionPermissionMode.OFF.toGateMode()) is Decision.Denied)
        assertTrue(gate.decide(write, SessionPermissionMode.OFF.toGateMode()) is Decision.Denied)
        assertTrue(gate.decide(safeShell, SessionPermissionMode.OFF.toGateMode()) is Decision.Denied)
    }

    @Test
    fun askPromptsEveryCall() {
        // Reads included — the strict "每次询问" contract.
        assertTrue(gate.decide(read, SessionPermissionMode.ASK.toGateMode()) is Decision.NeedConfirm)
        assertTrue(gate.decide(write, SessionPermissionMode.ASK.toGateMode()) is Decision.NeedConfirm)
        // Even an argv-safe shell command asks.
        assertTrue(gate.decide(safeShell, SessionPermissionMode.ASK.toGateMode()) is Decision.NeedConfirm)
    }

    @Test
    fun safeAutoRunsReadsAndSafeShellAsksTheRest() {
        assertTrue(gate.decide(read, SessionPermissionMode.SAFE_AUTO.toGateMode()) is Decision.Allow)
        assertTrue(gate.decide(safeShell, SessionPermissionMode.SAFE_AUTO.toGateMode()) is Decision.Allow)
        assertTrue(gate.decide(write, SessionPermissionMode.SAFE_AUTO.toGateMode()) is Decision.NeedConfirm)
        assertTrue(gate.decide(dangerousShell, SessionPermissionMode.SAFE_AUTO.toGateMode()) is Decision.NeedConfirm)
    }

    @Test
    fun yoloAutoRunsWritesAndOnlyConfirmsFatal() {
        assertTrue(gate.decide(read, SessionPermissionMode.YOLO.toGateMode()) is Decision.Allow)
        assertTrue(gate.decide(write, SessionPermissionMode.YOLO.toGateMode()) is Decision.Allow)
        assertTrue(gate.decide(safeShell, SessionPermissionMode.YOLO.toGateMode()) is Decision.Allow)
        val fatal = gate.classify("shell_exec", """{"command":"rm -rf /"}""")
        val d = gate.decide(fatal, SessionPermissionMode.YOLO.toGateMode())
        assertTrue(d is Decision.NeedConfirm && (d as Decision.NeedConfirm).mustPrompt)
    }

    @Test
    fun askHardDeniesFatalLikeAsk() {
        val fatal = gate.classify("shell_exec", """{"command":"rm -rf /"}""")
        assertTrue(gate.decide(fatal, SessionPermissionMode.ASK.toGateMode()) is Decision.Denied)
    }

    @Test
    fun cycleOrderAscendsAutonomy() {
        assertEquals(SessionPermissionMode.SAFE_AUTO, SessionPermissionMode.ASK.next())
        assertEquals(SessionPermissionMode.YOLO, SessionPermissionMode.SAFE_AUTO.next())
        assertEquals(SessionPermissionMode.OFF, SessionPermissionMode.YOLO.next())
        assertEquals(SessionPermissionMode.ASK, SessionPermissionMode.OFF.next())
    }
}
