package com.openminis.app.security

/**
 * Adapted from XINCODE-Public PermissionMode (GPL-3.0-or-later).
 * https://github.com/kusesad-1122/XINCODE-Public
 */
enum class PermissionMode {
    ALLOW_ALL,
    ASK,
    DENY_ALL,
    READ_ONLY,
    PLAN,
    ;

    fun labelZh(): String = when (this) {
        ASK -> "询问"
        ALLOW_ALL -> "全部允许"
        READ_ONLY -> "只读"
        PLAN -> "计划"
        DENY_ALL -> "全部拒绝"
    }
}

/**
 * Session "允许本会话全部操作" and the global ALLOW_ALL switch must hit the
 * same gate. Rules are still evaluated first inside [SecurityGate.decide].
 */
fun effectivePermissionMode(stored: PermissionMode, sessionAllowAll: Boolean): PermissionMode =
    if (sessionAllowAll) PermissionMode.ALLOW_ALL else stored

/**
 * Session allow-all skips ordinary confirms. A fatal command still prompts,
 * the same as global ALLOW_ALL: `rm -rf /` asks, it does not run.
 */
fun sessionAllowAllSkipsPrompt(sessionAllowAll: Boolean, mustPrompt: Boolean): Boolean =
    sessionAllowAll && !mustPrompt
