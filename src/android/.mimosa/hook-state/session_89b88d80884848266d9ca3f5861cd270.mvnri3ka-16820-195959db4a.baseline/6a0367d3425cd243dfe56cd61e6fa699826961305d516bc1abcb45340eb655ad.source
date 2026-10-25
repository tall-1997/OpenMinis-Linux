package com.openminis.app.security

import com.openminis.app.sandbox.SessionWorkspace

/**
 * Host `su` shares the app UID, so mode 700 does not hide other chats or
 * `databases/minis.db`. Deny those paths before the host binary runs.
 * `android-su -c 'ls /data'` stays allowed: it does not name this app's tree.
 */
object SuPathPolicy {
    // The name must end here. `minis-sessions-cli` is the supported tool, not a
    // directory read; the old pattern treated that hyphen as "end of token".
    private val sessionTree = Regex("""(?:^|[^\w.-])minis-sessions(?![\w.-])(?:/([^/\s'"`]+))?""")
    private val projectTree = Regex("""(?:^|[^\w.-])minis-workspaces(?![\w.-])(?:/([^/\s'"`]+))?""")
    // Longer names first so `minis-su` is not missed by the bare `su` token.
    private val hostSu = Regex("""(?:^|[^\w.-])(?:sudo|minis-su(?:-cli)?|android-su|su)(?![\w.-])""")

    fun denial(command: String, callerSessionId: String?, callerFolderId: String? = null): String? {
        val norm = command.replace('\\', '/')
        if (norm.contains("databases/minis.db") ||
            (norm.contains("minis.db") && norm.contains("/databases/"))
        ) {
            return "拒绝读取应用数据库 databases/minis.db。会话内容请用 search_sessions / read_session，跨会话需要用户授权。"
        }
        if (APP_DATA_ROOTS.any { norm.contains(it) } && hostSu.containsMatchIn(norm)) {
            return "拒绝通过宿主 su 读取应用私有目录。本会话文件在 /var/minis；其他会话需要 session_read 授权。"
        }
        val caller = callerSessionId?.takeIf { it.isNotBlank() }?.let { SessionWorkspace.ownerSessionId(it) }
        for (match in sessionTree.findAll(norm)) {
            val id = match.groupValues.getOrNull(1).orEmpty().trimEnd('/')
            if (id.isEmpty() || caller == null || id != caller) {
                val shown = if (id.isEmpty()) "minis-sessions" else "minis-sessions/$id"
                return "拒绝读取其他会话目录 $shown。"
            }
        }
        for (match in projectTree.findAll(norm)) {
            val id = match.groupValues.getOrNull(1).orEmpty().trimEnd('/')
            if (id.isEmpty() || callerFolderId.isNullOrBlank() || id != callerFolderId) {
                val shown = if (id.isEmpty()) "minis-workspaces" else "minis-workspaces/$id"
                return "拒绝读取其他项目目录 $shown。"
            }
        }
        return null
    }
}
