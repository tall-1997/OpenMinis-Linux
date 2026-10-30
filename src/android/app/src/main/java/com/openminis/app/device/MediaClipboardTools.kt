package com.openminis.app.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.offload.MinisNotificationListenerService
import org.json.JSONObject

/**
 * Media + clipboard group of the device tool set. Media control dispatches
 * KEYCODE_MEDIA_* events through AudioManager (needs no permission); the
 * now-playing report is best-effort and only when our notification listener
 * is connected (MediaSessionManager requires that grant). Clipboard read is
 * foreground-only on API 29+ per the Android 10 clipboard policy.
 */
internal object MediaClipboardTools {

    private const val TOOL_TITLE_PARAM = "tool_title"
    private const val TOOL_TITLE_DESC = "A concise 5-10 word summary shown to the user."

    private fun titleParam() = AgentToolParam("string", TOOL_TITLE_DESC)

    fun definitions(): List<AgentToolDefinition> = listOf(
        MediaControlTool.definition(),
        ClipboardGetTool.definition(),
        ClipboardSetTool.definition(),
    )

    fun execute(name: String, argsJson: String, context: Context): String = when (name) {
        MediaControlTool.NAME -> MediaControlTool.execute(argsJson, context)
        ClipboardGetTool.NAME -> ClipboardGetTool.execute(argsJson, context)
        ClipboardSetTool.NAME -> ClipboardSetTool.execute(argsJson, context)
        else -> DeviceArgs.fail("unknown_tool", "media/clipboard group does not handle '$name'")
    }

    // ── media_control ──

    internal object MediaControlTool {
        const val NAME = "media_control"
        private val ACTIONS = listOf("play", "pause", "toggle", "next", "previous", "stop")

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Control whatever is currently playing (music/video/podcast) with media keys: " +
                "play, pause, toggle, next, previous or stop. Works on the active media session of any " +
                "app; the result reports what was playing when the notification-listener grant is " +
                "available. Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "action" to AgentToolParam("string", "Media key to dispatch.", enumValues = ACTIONS),
            ),
            required = listOf(TOOL_TITLE_PARAM, "action"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "action"),
        )

        private fun keycode(action: String): Int = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_STOP
        }

        private fun dispatch(context: Context, code: Int): Boolean {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            return true
        }

        @Suppress("DEPRECATION")
        private fun nowPlaying(context: Context): String? = runCatching {
            if (!MinisNotificationListenerService.isConnected()) return@runCatching null
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return@runCatching null
            val component = ComponentName(context, MinisNotificationListenerService::class.java)
            val sessions = runCatching { msm.getActiveSessions(component) }.getOrNull() ?: return@runCatching null
            val session = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull() ?: return@runCatching null
            val title = session.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "?"
            val artist = session.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            buildString {
                append(session.packageName).append(": ").append(title)
                if (artist.isNotEmpty()) append(" — ").append(artist)
                val state = session.playbackState?.state
                if (state != null) append(" (").append(
                    when (state) {
                        PlaybackState.STATE_PLAYING -> "playing"
                        PlaybackState.STATE_PAUSED -> "paused"
                        PlaybackState.STATE_STOPPED -> "stopped"
                        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "loading"
                        else -> "idle"
                    },
                ).append(')')
            }
        }.getOrNull()

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val action = obj.strArg("action") ?: return DeviceArgs.fail("invalid_args", "missing 'action'")
            if (action !in ACTIONS) {
                return DeviceArgs.fail("invalid_args", "action must be one of ${ACTIONS.joinToString("/")}")
            }
            if (!dispatch(context, keycode(action))) {
                return DeviceArgs.fail("unsupported", "no audio service on this device")
            }
            val playing = nowPlaying(context)
            return if (playing != null) "Sent $action (media key). Now: $playing."
            else "Sent $action (media key). No active media session is reporting; verify with the app."
        }
    }

    // ── clipboard_get ──

    internal object ClipboardGetTool {
        const val NAME = "clipboard_get"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Read the current clipboard text. Android 10+ only allows the app in the " +
                "foreground to read the clipboard, so this fails with a clear error when Minis is in the " +
                "background. Sensitive: reads user data.",
            parameters = mapOf(TOOL_TITLE_PARAM to titleParam()),
            required = listOf(TOOL_TITLE_PARAM),
            propertyOrdering = listOf(TOOL_TITLE_PARAM),
        )

        fun execute(argsJson: String, context: Context): String {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return DeviceArgs.fail("unsupported", "no clipboard service on this device")
            if (android.os.Build.VERSION.SDK_INT >= 29 && !isAppForeground(context)) {
                return DeviceArgs.fail(
                    "unavailable",
                    "Android only lets the foreground app read the clipboard. Open Minis, then retry, " +
                        "or ask the user to paste the content.",
                )
            }
            val text = runCatching {
                cm.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull()
            return when {
                text == null -> DeviceArgs.fail(
                    "unavailable",
                    "the clipboard could not be read (Android hides it from this app right now). " +
                        "Open Minis and retry.",
                )
                text.isEmpty() -> "(the clipboard holds empty text)"
                else -> text
            }
        }
    }

    // ── clipboard_set ──

    internal object ClipboardSetTool {
        const val NAME = "clipboard_set"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Put text on the system clipboard (plain text). Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "text" to AgentToolParam("string", "Text to place on the clipboard."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "text"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "text"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val text = obj.strArg("text") ?: return DeviceArgs.fail("invalid_args", "missing 'text'")
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return DeviceArgs.fail("unsupported", "no clipboard service on this device")
            return runCatching {
                cm.setPrimaryClip(ClipData.newPlainText("Minis", text))
                "Copied ${text.length} character(s) to the clipboard."
            }.getOrElse { DeviceArgs.fail("failed", "could not write the clipboard: ${it.message}") }
        }
    }
}
