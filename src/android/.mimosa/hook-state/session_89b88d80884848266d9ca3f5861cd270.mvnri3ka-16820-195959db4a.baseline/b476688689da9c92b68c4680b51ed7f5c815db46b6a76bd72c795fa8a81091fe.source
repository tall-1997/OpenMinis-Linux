package com.openminis.app.security

/**
 * Persistent allow/deny rule. Deny wins over allow.
 *
 * Actions: `allow` | `deny` (user rules, deny wins) plus `prompt` |
 * `forbidden` — the tighten-only shell prefix layer (Codex execpolicy
 * semantics, see [PrefixRulePolicy]). `prompt`/`forbidden` rules match
 * shell commands by exact argv prefix and can only tighten a decision,
 * never widen one.
 *
 * Adapted from XINCODE-Public PermissionRuleEntity (GPL-3.0-or-later).
 */
data class PermissionRule(
    val action: String,
    val toolFilter: String = "*",
    val pattern: String = "",
)
