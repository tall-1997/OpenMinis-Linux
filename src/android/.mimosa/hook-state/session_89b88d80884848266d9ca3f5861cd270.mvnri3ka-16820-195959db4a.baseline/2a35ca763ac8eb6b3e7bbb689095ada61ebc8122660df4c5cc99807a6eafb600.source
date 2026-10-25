package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefixRulePolicyTest {

    private fun verdict(rules: List<PermissionRule>, command: String, tool: String = "shell_execute") =
        PrefixRulePolicy.evaluate(rules, tool, command)

    @Test
    fun `no rules or no match returns null`() {
        assertNull(verdict(emptyList(), "ls /data"))
        assertNull(
            verdict(
                listOf(PermissionRule(action = "forbidden", toolFilter = "*", pattern = "pm uninstall")),
                "pm list packages",
            ),
        )
    }

    @Test
    fun `forbidden matches exact argv prefix`() {
        val rules = listOf(PermissionRule(action = "forbidden", pattern = "rm -rf"))
        assertEquals(
            PrefixRulePolicy.Verdict.FORBIDDEN,
            verdict(rules, "rm -rf /sdcard/DCIM")?.first,
        )
    }

    @Test
    fun `prefix match is token exact not substring`() {
        val rules = listOf(PermissionRule(action = "forbidden", pattern = "rm -rf"))
        assertNull(verdict(rules, "rm -r /tmp/x"))
        assertNull(verdict(rules, "rm /tmp/x"))
        assertNull(verdict(rules, "rmdir /tmp/x"))
    }

    @Test
    fun `program token ignores path prefix`() {
        val rules = listOf(PermissionRule(action = "prompt", pattern = "pm uninstall"))
        assertEquals(
            PrefixRulePolicy.Verdict.PROMPT,
            verdict(rules, "/system/bin/pm uninstall --user 0 com.example")?.first,
        )
    }

    @Test
    fun `multiple matches take the strictest`() {
        val rules = listOf(
            PermissionRule(action = "prompt", pattern = "dd"),
            PermissionRule(action = "forbidden", toolFilter = "shell_execute", pattern = "dd if=/dev/zero"),
        )
        assertEquals(
            PrefixRulePolicy.Verdict.FORBIDDEN,
            verdict(rules, "dd if=/dev/zero of=/dev/block/mmcblk0")?.first,
        )
        assertEquals(
            PrefixRulePolicy.Verdict.PROMPT,
            verdict(rules, "dd if=/dev/null of=/tmp/x")?.first,
        )
    }

    @Test
    fun `compound command matches any risk unit`() {
        val rules = listOf(PermissionRule(action = "forbidden", pattern = "mkfs.ext4"))
        assertEquals(
            PrefixRulePolicy.Verdict.FORBIDDEN,
            verdict(rules, "sync && mkfs.ext4 /dev/block/mmcblk1")?.first,
        )
    }

    @Test
    fun `su -c payload is classified on its own`() {
        val rules = listOf(PermissionRule(action = "forbidden", pattern = "pm uninstall"))
        assertEquals(
            PrefixRulePolicy.Verdict.FORBIDDEN,
            verdict(rules, "su -c 'pm uninstall --user 0 com.example'")?.first,
        )
    }

    @Test
    fun `allow and deny actions are ignored by this layer`() {
        val rules = listOf(
            PermissionRule(action = "allow", pattern = "rm -rf"),
            PermissionRule(action = "deny", pattern = "mkfs.ext4"),
        )
        assertNull(verdict(rules, "rm -rf /tmp/x"))
    }

    @Test
    fun `toolFilter restricts the rule`() {
        val rules = listOf(
            PermissionRule(action = "forbidden", toolFilter = "su_exec", pattern = "pm uninstall"),
        )
        assertNull(verdict(rules, "pm uninstall com.example", tool = "shell_execute"))
        assertEquals(
            PrefixRulePolicy.Verdict.FORBIDDEN,
            verdict(rules, "pm uninstall com.example", tool = "su_exec")?.first,
        )
    }

    @Test
    fun `blank or malformed patterns never match`() {
        val rules = listOf(
            PermissionRule(action = "forbidden", pattern = ""),
            PermissionRule(action = "forbidden", pattern = "   "),
        )
        assertNull(verdict(rules, "ls /data"))
    }

    @Test
    fun `wiring in decide - forbidden denies hard before YOYO`() {
        val gate = SecurityGateImpl()
        gate.setPermissionRules(
            listOf(PermissionRule(action = "forbidden", pattern = "pm uninstall")),
        )
        val cmd = gate.classify("shell_execute", """{"command":"pm uninstall --user 0 com.example"}""")
        val d = gate.decide(cmd, PermissionMode.ALLOW_ALL)
        assertTrue(d is Decision.Denied)
        assertTrue((d as Decision.Denied).hard)
    }

    @Test
    fun `wiring in decide - prompt forces confirm under YOYO`() {
        val gate = SecurityGateImpl()
        gate.setPermissionRules(
            listOf(PermissionRule(action = "prompt", pattern = "dd")),
        )
        val cmd = gate.classify("shell_execute", """{"command":"dd if=/dev/zero of=/sdcard/f"}""")
        val d = gate.decide(cmd, PermissionMode.ALLOW_ALL)
        assertTrue(d is Decision.NeedConfirm)
        assertTrue((d as Decision.NeedConfirm).mustPrompt)
    }

    @Test
    fun `wiring in decide - prompt rule forces confirm under YOYO even for safe command`() {
        val gate = SecurityGateImpl()
        gate.setPermissionRules(
            listOf(PermissionRule(action = "prompt", pattern = "apt install")),
        )
        // `apt install` is not fatal, not a hard refusal, and would auto-run
        // under YOYO as a plain FS command — the prompt rule must stop it.
        val cmd = gate.classify("shell_execute", """{"command":"apt install htop"}""")
        val d = gate.decide(cmd, PermissionMode.ALLOW_ALL)
        assertTrue(d is Decision.NeedConfirm)
        assertTrue((d as Decision.NeedConfirm).mustPrompt)
    }

    @Test
    fun `layer never widens - no rules keeps existing behaviour`() {
        val gate = SecurityGateImpl()
        val cmd = gate.classify("shell_execute", """{"command":"ls /var"}""")
        val d = gate.decide(cmd, PermissionMode.ALLOW_ALL)
        assertFalse(d is Decision.NeedConfirm)
        assertTrue(d is Decision.Allow)
    }

    @Test
    fun `wiring in decide - su hard refusal fires even with a matching prompt rule`() {
        val gate = SecurityGateImpl()
        gate.setPermissionRules(
            listOf(PermissionRule(action = "prompt", pattern = "ls")),
        )
        val cmd = gate.classify("su_exec", """{"command":"ls /data/data/com.openminis.linux"}""")
        val d = gate.decide(cmd, PermissionMode.ALLOW_ALL)
        assertTrue(d is Decision.Denied)
        assertTrue((d as Decision.Denied).hard)
    }}
