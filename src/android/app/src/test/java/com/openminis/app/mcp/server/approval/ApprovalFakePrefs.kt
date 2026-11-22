package com.openminis.app.mcp.server.approval

/**
 * 测试用 prefs 句柄。
 *
 * [T-prefs-handle] McpApprovalStore 的构造参数从 SharedPreferences 换成了
 * PrefsHandle（类型化句柄），所以测试不再伪造整个 SharedPreferences 接口 ——
 * 直接给一个内存后端，读写语义与生产实现一致，少写几十行无用方法。
 */
internal fun approvalFakePrefs(name: String = "mcp_approvals_test"): com.openminis.app.config.PrefsHandle =
    com.openminis.app.config.PrefsHandle(
        com.openminis.app.config.PrefsFileSpec(
            name = name,
            module = "mcp",
            scope = com.openminis.app.config.PrefsScope.PLAIN,
            openKeySpace = true,
            note = "test fixture",
        ),
        com.openminis.app.config.InMemoryPrefsBackend(name),
    )
