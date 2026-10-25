package com.openminis.app.security

/**
 * Tighten-only prefix rule layer for shell tools (Codex execpolicy
 * semantics, rewritten in Kotlin for this tree).
 *
 * Rules are [PermissionRule]s whose action is `prompt` or `forbidden` and
 * whose pattern is a whitespace-separated argv prefix (`rm -rf`,
 * `git push`, `pm uninstall`). Matching is token-exact — no substring, no
 * glob — so `rm -rf` cannot catch `rm -r` and `pm uninstall` cannot catch
 * `pm list`. The program token compares without its path prefix, so
 * `/system/bin/pm uninstall` matches a `pm uninstall` rule.
 *
 * Semantics carried over from Codex `execpolicy` (decision.rs `Ord`,
 * policy.rs `Evaluation::from_matches`):
 *  - several rules matching one command take the STRICTEST verdict
 *    (forbidden > prompt);
 *  - a compound command matches when any risk unit (segment or `su -c`
 *    payload, via [riskUnits]) matches;
 *  - no match returns null and the existing five-mode machinery decides;
 *  - the layer can only tighten: there is deliberately no allow verdict
 *    here, because widening is what the permission modes are for.
 *
 * Wiring (SecurityGateImpl.decide): the layer runs AFTER every hard refusal
 * (SuPathPolicy / GuestMountPolicy / GuestWorkloadPolicy.hostRefusal) so a
 * rule can never mask one, and BEFORE any confirmable path
 * (requiresFreshConfirm, YOYO auto-run, low-risk auto-run) so a forbidden
 * rule is not laundered through an approval dialog and a prompt rule is not
 * silently executed in YOYO. `forbidden` denies with hard=true so session
 * allow-all cannot bypass it; `prompt` confirms with mustPrompt=true so the
 * dialog always shows.
 */
object PrefixRulePolicy {

    /** Order matters: strictness is ordinal order (Codex Allow<Prompt<Forbidden minus allow). */
    enum class Verdict { PROMPT, FORBIDDEN }

    /** @return the strictest (verdict, matched pattern), or null when nothing matched. */
    fun evaluate(
        rules: List<PermissionRule>,
        toolName: String,
        command: String,
    ): Pair<Verdict, String>? {
        var worst: Pair<Verdict, String>? = null
        for (r in rules) {
            val verdict = when (r.action.lowercase()) {
                "forbidden" -> Verdict.FORBIDDEN
                "prompt" -> Verdict.PROMPT
                else -> continue
            }
            if (!toolFilterMatches(r.toolFilter, toolName)) continue
            // [T-prefixrule-pattern-tokenize] Tokenize the pattern with the
            // SAME quote-aware tokenizer used for the command. The old naive
            // whitespace split kept literal quotes (`-m "x"` → token `"x"`),
            // which can never equal the command's unquoted `x` — the rule
            // silently never matched while the user believed it did.
            val patTokens = tokenizeCommand(r.pattern)
            if (patTokens.isEmpty()) continue
            val hit = riskUnits(command).any { unit -> matchesPrefix(patTokens, tokenizeCommand(unit)) }
            if (!hit) continue
            val candidate = verdict to r.pattern
            if (worst == null || verdict.ordinal > worst.first.ordinal) worst = candidate
        }
        return worst
    }

    /** Token-exact argv prefix match; program token compares path-stripped. */
    private fun matchesPrefix(pat: List<String>, argv: List<String>): Boolean {
        if (argv.size < pat.size) return false
        if (argv[0].substringAfterLast('/') != pat[0].substringAfterLast('/')) return false
        for (i in 1 until pat.size) {
            if (pat[i] != argv[i]) return false
        }
        return true
    }

    /** Same semantics as the user-rule filter in SecurityGateImpl. */
    internal fun toolFilterMatches(filter: String, toolName: String): Boolean = when {
        filter == "*" || filter.isBlank() -> true
        filter.endsWith("*") -> toolName.startsWith(filter.dropLast(1))
        else -> filter == toolName || ToolAliases.canonical(toolName) == filter
    }
}
