package com.openminis.app.speech

import android.content.Context

/**
 * [T-android-tts-scene-announcements] WHICH events are spoken aloud.
 *
 * [TextToSpeechManager] answers "how do I speak"; [VoiceOutputState] answers
 * "is reply read-aloud on". Neither answers "should THIS event make a sound" —
 * a finished tool batch, a failed run, a due reminder, and an agent turn that
 * ended while the user was away are four different user intents, and forcing
 * them through the global read-replies switch would make "read my replies
 * aloud" also mean "interrupt me whenever a cron job fires".
 *
 * So each scene carries its own toggle, persisted in a dedicated
 * `voice_announcement_prefs` file, deliberately separate from `voice_prefs`
 * (which [VoiceOutputState] owns) — announcement preferences are not reply
 * playback, and clearing one must not clear the other.
 *
 * The defaults encode intent, not uniformity:
 *  • [AnnouncementScene.REMINDER_DUE] defaults ON — a reminder the user
 *    explicitly scheduled is worthless if it stays silent.
 *  • The other three default OFF — an assistant that starts talking to you
 *    unprompted is worse than a quiet one, so those are opt-in.
 *
 * Android context dependency is confined to [VoiceAnnouncementPrefs.load] /
 * [setEnabled] / [isEnabled]; every string and decision below is pure, so the
 * JVM unit tests never touch the framework.
 */
enum class AnnouncementScene(val key: String, val defaultEnabled: Boolean) {
    /** A tool batch / long-running task finished. Opt-in. */
    TASK_COMPLETED("task_completed", false),

    /** Execution failed or the run reported an error. Opt-in. */
    ERROR_OCCURRED("error_occurred", false),

    /** A scheduled job / alarm reminder came due. On by default. */
    REMINDER_DUE("reminder_due", true),

    /** An agent turn ended while the user was NOT in the foreground. Opt-in. */
    IDLE_AGENT_DONE("idle_agent_done", false),
}

/**
 * Immutable snapshot of every scene's toggle. Carried explicitly into
 * [VoiceAnnouncementCoordinator.onScene] so the decision is a pure function of
 * its arguments — no singleton to stub, no SharedPreferences on the test path.
 *
 * [enabled] is a plain Map and may be partial; a scene with no entry falls back
 * to its [AnnouncementScene.defaultEnabled] in [VoiceAnnouncementPrefs.isEnabled].
 */
data class AnnouncementPrefs(
    val enabled: Map<AnnouncementScene, Boolean> = emptyMap(),
) {
    /** Convenience read with the same default rule as [VoiceAnnouncementPrefs.isEnabled]. */
    fun isEnabled(scene: AnnouncementScene): Boolean =
        enabled[scene] ?: scene.defaultEnabled
}

/**
 * Scene-announcement preference persistence, backed by SharedPreferences
 * (`voice_announcement_prefs`).
 *
 * Keys are stored as `scene.<[AnnouncementScene.key]>` = Boolean. An absent key
 * means "never chosen" and resolves to the scene's default — the same rule
 * [isEnabled] and [load] apply, so the map and the single lookup can't drift.
 */
object VoiceAnnouncementPrefs {

    const val PREFS_NAME = "voice_announcement_prefs"

    private const val KEY_PREFIX = "scene."

    /** Storage key for [scene] — one place, so load/set/isEnabled agree. */
    private fun keyOf(scene: AnnouncementScene): String = KEY_PREFIX + scene.key

    /**
     * Read the full snapshot. Missing entries use [AnnouncementScene.defaultEnabled].
     */
    fun load(context: Context): AnnouncementPrefs {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val map = AnnouncementScene.entries.associateWith { scene ->
            prefs.getBoolean(keyOf(scene), scene.defaultEnabled)
        }
        return AnnouncementPrefs(map)
    }

    /**
     * Persist one scene's toggle.
     *
     * [context] is only used for its SharedPreferences handle, so a JVM test can
     * call it with a Robolectric-free stub — but the tests deliberately exercise
     * only the pure functions and never reach here (there is no Robolectric on
     * the classpath).
     */
    fun setEnabled(context: Context, scene: AnnouncementScene, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(keyOf(scene), enabled)
            .apply()
    }

    /**
     * Single-scene lookup WITHOUT rebuilding the whole map — the hot path for
     * "should I speak this event" checks, which can fire per tool result and
     * must not allocate a four-entry map each time.
     */
    fun isEnabled(context: Context, scene: AnnouncementScene): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(keyOf(scene), scene.defaultEnabled)

    /**
     * The spoken sentence for [scene].
     *
     * Chinese, matching [ToolSpeech] and the iOS originals, so announcements and
     * tool narration don't switch language mid-run. [detail] is interpolated
     * verbatim when present and non-blank; blank is treated as absent (a
     * whitespace-only reminder detail would otherwise produce "提醒：   ").
     */
    fun announceText(scene: AnnouncementScene, detail: String? = null): String {
        val hasDetail = !detail.isNullOrBlank()
        return when (scene) {
            AnnouncementScene.TASK_COMPLETED -> "任务已完成"
            AnnouncementScene.ERROR_OCCURRED ->
                if (hasDetail) "执行出错：$detail" else "执行出错"
            AnnouncementScene.REMINDER_DUE ->
                if (hasDetail) "提醒：$detail" else "你有新的提醒"
            AnnouncementScene.IDLE_AGENT_DONE -> "任务完成，回来查看结果吧"
        }
    }

    /**
     * Pure gate: should [scene] be spoken given [prefs]?
     *
     * Kept here (not on the coordinator) so the decision can be unit-tested
     * with no TTS, no Context, and no coordinator instance.
     */
    fun shouldAnnounce(scene: AnnouncementScene, prefs: AnnouncementPrefs): Boolean =
        prefs.isEnabled(scene)
}
