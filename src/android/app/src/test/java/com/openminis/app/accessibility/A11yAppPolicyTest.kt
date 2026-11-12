package com.openminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#114] Pure-JVM contract for the per-app accessibility policy.
 *
 * Covers the three states the gate can be in before a UI read/tap:
 *  - mode OFF (backwards compatible: everything allowed),
 *  - ALLOWLIST / BLOCKLIST membership,
 *  - no foreground package (service not connected / query returned null) must
 *    BLOCK in restricted modes — the fail-closed rule that used to be a hole.
 */
class A11yAppPolicyTest {

    private val allowlist = setOf("com.allowed.app")
    private val blocklist = setOf("com.blocked.app")

    @Test
    fun `off mode allows every package including null`() {
        assertEquals(
            A11yAppPolicy.Verdict.Allowed,
            A11yAppPolicy.decide("com.anything", A11yAppPolicy.Mode.OFF, emptySet()),
        )
        assertEquals(
            A11yAppPolicy.Verdict.Allowed,
            A11yAppPolicy.decide(null, A11yAppPolicy.Mode.OFF, emptySet()),
        )
    }

    @Test
    fun `allowlist allows listed and blocks unlisted`() {
        assertTrue(
            A11yAppPolicy.decide(
                "com.allowed.app", A11yAppPolicy.Mode.ALLOWLIST, allowlist,
            ) is A11yAppPolicy.Verdict.Allowed,
        )
        assertTrue(
            A11yAppPolicy.decide(
                "com.other.app", A11yAppPolicy.Mode.ALLOWLIST, allowlist,
            ) is A11yAppPolicy.Verdict.Blocked,
        )
    }

    @Test
    fun `blocklist blocks listed and allows unlisted`() {
        assertTrue(
            A11yAppPolicy.decide(
                "com.blocked.app", A11yAppPolicy.Mode.BLOCKLIST, blocklist,
            ) is A11yAppPolicy.Verdict.Blocked,
        )
        assertTrue(
            A11yAppPolicy.decide(
                "com.other.app", A11yAppPolicy.Mode.BLOCKLIST, blocklist,
            ) is A11yAppPolicy.Verdict.Allowed,
        )
    }

    @Test
    fun `null package is fail closed in restricted modes`() {
        for (mode in listOf(A11yAppPolicy.Mode.ALLOWLIST, A11yAppPolicy.Mode.BLOCKLIST)) {
            val verdict = A11yAppPolicy.decide(null, mode, allowlist)
            assertTrue("$mode must block a null package", verdict is A11yAppPolicy.Verdict.Blocked)
            assertTrue(
                "reason must say the foreground app is unknown",
                (verdict as A11yAppPolicy.Verdict.Blocked).reason.contains("无法确认"),
            )
        }
    }

    @Test
    fun `no accessibility service is fail closed in restricted modes`() {
        for (mode in listOf(A11yAppPolicy.Mode.ALLOWLIST, A11yAppPolicy.Mode.BLOCKLIST)) {
            val verdict = A11yAppPolicy.decideForForeground(
                packageName = null,
                serviceAvailable = false,
                mode = mode,
                apps = emptySet(),
            )
            assertTrue(
                "$mode must block when the service is not connected",
                verdict is A11yAppPolicy.Verdict.Blocked,
            )
            assertTrue(
                "reason must name the missing AccessibilityService",
                (verdict as A11yAppPolicy.Verdict.Blocked).reason.contains("AccessibilityService"),
            )
        }
    }

    @Test
    fun `service connected but empty foreground package is blocked`() {
        val verdict = A11yAppPolicy.decideForForeground(
            packageName = "",
            serviceAvailable = true,
            mode = A11yAppPolicy.Mode.ALLOWLIST,
            apps = allowlist,
        )
        assertTrue(verdict is A11yAppPolicy.Verdict.Blocked)
    }

    @Test
    fun `wait and notify are gated but diagnostics are not`() {
        assertTrue("wait appear reads the screen", "wait" in A11yAppPolicy.POLICY_GATED_SUBS)
        assertTrue("notify observes the foreground app", "notify" in A11yAppPolicy.POLICY_GATED_SUBS)
        for (diagnostic in listOf("service", "--version")) {
            assertFalse(diagnostic, diagnostic in A11yAppPolicy.POLICY_GATED_SUBS)
        }
    }
}
