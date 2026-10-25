package com.openminis.app.security

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prefixrule-wrapper-unwrap] Regression net for the three prefix-rule
 * bypasses: argv[0] wrappers, command substitution, and quote-agnostic
 * pattern tokenization.
 */
class PrefixRuleBypassTest {

    private val rules = listOf(
        PermissionRule(pattern = "rm -rf", action = "forbidden", toolFilter = "*"),
    )

    private fun forbidden(command: String): String? =
        PrefixRulePolicy.evaluate(rules, "shell_execute", command)?.second

    @Test
    fun `plain rm -rf matches`() {
        assertNotNull(forbidden("rm -rf /tmp/x"))
    }

    @Test
    fun `env wrapper does not hide the program`() {
        assertNotNull(forbidden("env X=1 rm -rf /"))
        assertNotNull(forbidden("env -u HOME rm -rf /"))
    }

    @Test
    fun `nice timeout setsid nohup stdbuf wrappers do not hide the program`() {
        assertNotNull(forbidden("nice -n 19 rm -rf /"))
        assertNotNull(forbidden("nice rm -rf /"))
        assertNotNull(forbidden("timeout 5 rm -rf /"))
        assertNotNull(forbidden("timeout -k 2 5 rm -rf /"))
        assertNotNull(forbidden("setsid rm -rf /"))
        assertNotNull(forbidden("nohup rm -rf /"))
        assertNotNull(forbidden("stdbuf -o 0 rm -rf /"))
    }

    @Test
    fun `xargs and sudo wrappers do not hide the program`() {
        assertNotNull(forbidden("xargs rm -rf /"))
        assertNotNull(forbidden("xargs -0 rm -rf /"))
        assertNotNull(forbidden("sudo rm -rf /"))
    }

    @Test
    fun `busybox applet does not hide the program`() {
        assertNotNull(forbidden("busybox rm -rf /"))
    }

    @Test
    fun `find -exec payload is classified on its own`() {
        assertNotNull(forbidden("find . -exec rm -rf {} \\;"))
        assertNotNull(forbidden("find /data -name x -execdir rm -rf {} ;"))
    }

    @Test
    fun `shell shell sees through the wrapper too`() {
        assertNotNull(forbidden("bash -c 'env X=1 rm -rf /'"))
    }

    @Test
    fun `command substitution surfaces the inner command as a segment`() {
        // [T-prefixrule-substitution] echo is in SAFE_COMMANDS; the old
        // segmentation made `echo $(rm -rf /x)` a single `echo` unit that no
        // rule could ever match.
        val units = riskUnits("echo $(rm -rf /tmp/x)")
        assertTrue("expected a unit starting with rm, got: $units", units.any { it.startsWith("rm ") })
        val backtick = riskUnits("echo `rm -rf /tmp/x`")
        assertTrue("expected a unit starting with rm, got: $backtick", backtick.any { it.startsWith("rm ") })
        assertNotNull(forbidden("echo $(rm -rf /tmp/x)"))
    }

    /**
     * [T-prefixrule-quoted-substitution] The QUOTED spelling is the idiomatic
     * one — you quote a substitution precisely to preserve its whitespace — and
     * it was the half the first substitution fix missed. bash performs command
     * substitution inside DOUBLE quotes, so `echo "$(cmd)"` really runs cmd,
     * but the splitter's quote branch appended everything verbatim and the whole
     * command stayed a single `echo` unit that no rule could match.
     *
     * Verified blind before the fix (tokenizer ported and run over 14 forms):
     * the unquoted, `env`/`xargs`/`find -exec` and `bash -c` forms all matched,
     * while all four double-quoted forms below did not.
     */
    @Test
    fun `substitution inside double quotes is still seen`() {
        val forms = listOf(
            "echo \"$(rm -rf /tmp/x)\"",           // quoted substitution
            "X=\"$(rm -rf /tmp/x)\"",              // assignment rhs, quoted
            "echo \"`rm -rf /tmp/x`\"",            // quoted backtick
            "echo \"$(env X=1 rm -rf /tmp/x)\"",   // wrapper inside a quoted substitution
        )
        for (cmd in forms) {
            val units = riskUnits(cmd)
            assertTrue(
                "expected a unit starting with rm for <$cmd>, got: $units",
                units.any { it.startsWith("rm ") },
            )
            assertNotNull("rule missed <$cmd>", forbidden(cmd))
        }
    }

    /**
     * The other half of the same property: where bash does NOT expand, the
     * splitter must NOT see a command either. Without these, "fix the quoted
     * case" degenerates into "split on every `$(`", which would flag ordinary
     * quoted prose. Single quotes make everything literal, and a backslash
     * escapes `$` inside double quotes.
     */
    @Test
    fun `substitution that bash would not expand must not match`() {
        assertNull(forbidden("echo '$(rm -rf /tmp/x)'"))       // single quotes: literal
        assertNull(forbidden("echo \"\\$(rm -rf /tmp/x)\""))   // escaped opener: literal
    }

    @Test
    fun `pattern tokenization is quote-aware`() {
        // [T-prefixrule-pattern-tokenize] The old naive split kept the literal
        // quote in the pattern token, so the rule could never match.
        val quotedRule = listOf(
            PermissionRule(pattern = "git commit -m \"x\"", action = "forbidden", toolFilter = "*"),
        )
        assertNotNull(
            PrefixRulePolicy.evaluate(quotedRule, "shell_execute", "git commit -m \"x\""),
        )
        // A different message must still NOT match (token-exact semantics kept).
        assertNull(
            PrefixRulePolicy.evaluate(quotedRule, "shell_execute", "git commit -m \"y\""),
        )
    }

    @Test
    fun `innocent commands still pass through unmatched`() {
        assertNull(forbidden("ls -la /tmp"))
        assertNull(forbidden("rm /tmp/x"))           // rm without -rf: different prefix
        assertNull(forbidden("cat rm -rf notes.txt")) // first token is cat
    }
}
