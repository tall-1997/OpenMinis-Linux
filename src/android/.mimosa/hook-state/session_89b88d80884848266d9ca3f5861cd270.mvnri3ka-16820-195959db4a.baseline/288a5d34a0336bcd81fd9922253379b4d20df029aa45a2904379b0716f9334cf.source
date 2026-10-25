package com.openminis.app.notification

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * Preset + dynamic sandbox commands for notification actions.
 *
 * The broadcast extra carries only an action key / token — never a free-form
 * command — so a third-party app cannot smuggle a payload even if it somehow
 * targeted the exported=false receiver.
 */
object SandboxNotifyActions {
    const val ACTION = "com.openminis.linux.action.SANDBOX_NOTIFY"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_ACTION = "action"

    const val RETRY = "retry"
    const val BACKUP = "backup"
    const val CLEANUP = "cleanup"

    const val LAST_CMD_GUEST_PATH = "/var/minis/workspace/.minis-last-cmd.sh"

    private const val DYN_PREFS = "sandbox_notify_dyn"
    private val META = Regex("[;|&`\$()<>\\n\\r]")
    private val TOKENS = Regex("""[A-Za-z0-9._/+\-]+(?: [A-Za-z0-9._/+\-]+)*""")

    fun commandFor(action: String, context: Context? = null): String? = when (action) {
        RETRY -> """
            # minis-notify-action retry
            if [ -f $LAST_CMD_GUEST_PATH ]; then
              sh $LAST_CMD_GUEST_PATH
            else
              echo 'nothing to retry'
            fi
        """.trimIndent()
        BACKUP -> """
            # minis-notify-action backup
            mkdir -p /var/minis/workspace/.minis-backup
            tar -czf "/var/minis/workspace/.minis-backup/workspace-\$(date +%Y%m%d-%H%M%S).tgz" \
              --exclude .minis-backup -C /var/minis/workspace . 2>/dev/null
            echo backup-ok
        """.trimIndent()
        CLEANUP -> """
            # minis-notify-action cleanup
            rm -rf /tmp/* /var/tmp/* 2>/dev/null || true
            echo cleaned
        """.trimIndent()
        else -> if (action.startsWith("dyn:") && context != null) loadDynamic(context, action) else null
    }

    fun shouldRecordLastCommand(command: String): Boolean {
        if (command.contains("minis-notify-action")) return false
        if (command.contains(".minis-last-cmd.sh")) return false
        return command.isNotBlank()
    }

    fun isSafeGuestCommand(raw: String): Boolean = normalizeGuestCommand(raw) != null

    fun normalizeGuestCommand(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 240) return null
        if (META.containsMatchIn(t)) return null
        if (!TOKENS.matches(t)) return null
        val parts = t.split(Regex("""\s+"""))
        val path = parts[0]
        val abs = if (path.startsWith("/")) path else "/var/minis/workspace/$path"
        if (!abs.startsWith("/var/minis/") && !abs.startsWith("/usr/local/bin/minis-")) return null
        return (listOf(abs) + parts.drop(1)).joinToString(" ")
    }

    fun registerDynamic(context: Context, sessionId: String, command: String): String? {
        val cmd = normalizeGuestCommand(command) ?: return null
        val token = "dyn:" + UUID.randomUUID().toString().replace("-", "").take(16)
        val payload = JSONObject().put("session", sessionId).put("cmd", cmd).toString()
        context.applicationContext
            .getSharedPreferences(DYN_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(token, payload)
            .apply()
        return token
    }

    private fun loadDynamic(context: Context, token: String): String? {
        val raw = context.applicationContext
            .getSharedPreferences(DYN_PREFS, Context.MODE_PRIVATE)
            .getString(token, null) ?: return null
        return runCatching { JSONObject(raw).optString("cmd").takeIf { it.isNotBlank() } }.getOrNull()
    }
}
