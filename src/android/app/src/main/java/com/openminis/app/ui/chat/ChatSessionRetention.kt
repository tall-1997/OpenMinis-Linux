package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.SessionWorkspace
import java.io.File

/**
 * [GH#325] Retention policy for sessions emptied by an EXPLICIT user action.
 *
 * 中文：`/clear`、`/compact` 是显式动作，会话行与 workspace 必须保留，
 * 重启后不能被“空会话自动清理”再次删掉；用会话目录下的标记文件区分
 * “从未使用过的空会话”和“用户主动清空的会话”。
 *
 * `cleanupIfEmptyOnExit` (ChatViewModel) is a real need: toggling a
 * session-scoped setting on a brand-new chat materialises a DB row with no
 * messages, and without a cleanup the list grows "empty" rows. But the same
 * "0 persisted messages" test also matched a chat the user had deliberately
 * emptied with `/clear` (and a fully compacted one), so leaving the screen
 * after `/clear` deleted the SESSION ROW and its workspace, and a restart
 * showed the chat gone — the exact report in GH#325.
 *
 * The fix distinguishes the two cases with a marker on disk, not with a
 * message count (which cannot tell "never used" from "explicitly cleared"):
 *
 *  - `/clear` and `/compact` write [MARKER_FILE] under the session directory;
 *  - `cleanupIfEmptyOnExit` skips any session carrying it;
 *  - an explicit delete removes the whole session directory, marker included,
 *    so no cleanup is ever needed for a deleted session.
 *
 * The marker is intentionally small and only checked on the exit-cleanup path.
 */
internal object ChatSessionRetention {

    const val MARKER_FILE = ".retain-session"

    fun markExplicitlyEmptied(filesDir: File, sessionId: String, reason: String) {
        if (!SessionWorkspace.isSafeId(sessionId)) return
        runCatching {
            val dir = SessionWorkspace.base(filesDir, sessionId)
            if (!dir.exists()) dir.mkdirs()
            File(dir, MARKER_FILE).writeText(
                "retain=true\nreason=$reason\nts=${System.currentTimeMillis()}\n",
            )
            // Best-effort logging: this marker is also written from JVM tests
            // where AppLogger has no application context.
            runCatching {
                AppLogger.info(
                    "ChatSessionRetention",
                    "session $sessionId marked retained after explicit $reason",
                )
            }
        }.onFailure {
            runCatching {
                AppLogger.warning(
                    "ChatSessionRetention",
                    "failed to mark session $sessionId retained: ${it.message}",
                )
            }
        }
    }

    /** True when the user explicitly emptied this session — never auto-delete it. */
    fun isRetained(filesDir: File, sessionId: String): Boolean {
        if (!SessionWorkspace.isSafeId(sessionId)) return false
        return runCatching {
            File(SessionWorkspace.base(filesDir, sessionId), MARKER_FILE).isFile
        }.getOrDefault(false)
    }
}
