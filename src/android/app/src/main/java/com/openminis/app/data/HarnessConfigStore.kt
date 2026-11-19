package com.openminis.app.data

import com.openminis.app.agent.HarnessConfig
import org.json.JSONObject
import java.io.File

/**
 * [T-harness-config-store] Per-session override persistence for [HarnessConfig].
 *
 * WHY this exists: `HarnessConfig.install` shipped with no caller, so the
 * "per-session override" acceptance item was unreachable — the only way to move
 * a threshold was to edit the defaults and publish a release. A 32K-window model
 * and a 200K-window model need different tails, and which model a chat uses is a
 * per-session fact, not an app-wide one.
 *
 * Storage mirrors the other per-session sidecars (`NoticeLedgerStore`,
 * `WorkingMemoryStore`): one small JSON file under `files/harness-config/`.
 * Absent or corrupt → the shipped defaults, never an exception into the loop; a
 * config file must not be able to break a session.
 *
 * The store is deliberately dumb: it persists values and hands them to
 * [HarnessConfig.install]. Validation stays in one place ([HarnessConfig.validated]).
 */
internal object HarnessConfigStore {

    internal const val DIR = "harness-config"

    private fun safeSession(sessionId: String): String =
        sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "_" }

    internal fun file(filesDir: File, sessionId: String): File =
        File(File(filesDir, DIR), "${safeSession(sessionId)}.json")

    /** Serialize every field, so a future field addition is a visible diff. */
    internal fun toJson(config: HarnessConfig): String = JSONObject()
        .put("v", 1)
        .put("postAnchorBudgetTokens", config.postAnchorBudgetTokens)
        .put("postAnchorWindowFraction", config.postAnchorWindowFraction)
        .put("noticeReserveTokens", config.noticeReserveTokens)
        .put("noticeBaseTokens", config.noticeBaseTokens)
        .put("absoluteAgeTokens", config.absoluteAgeTokens)
        .put("agingCharsPerToken", config.agingCharsPerToken)
        .put("digestScanMessages", config.digestScanMessages)
        .put("digestMaxLines", config.digestMaxLines)
        .put("digestMaxChars", config.digestMaxChars)
        .put("ledgerMaxEntries", config.ledgerMaxEntries)
        .toString()

    /**
     * Parse a stored override, or null when the file is missing/corrupt.
     *
     * Every field falls back to the shipped default individually, so a file
     * written by an older build (fewer keys) still loads instead of resetting
     * the user's whole override.
     */
    internal fun fromJson(text: String): HarnessConfig? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val defaults = HarnessConfig.DEFAULT
        return HarnessConfig(
            postAnchorBudgetTokens = obj.optInt(
                "postAnchorBudgetTokens", defaults.postAnchorBudgetTokens,
            ),
            postAnchorWindowFraction = obj.optDouble(
                "postAnchorWindowFraction", defaults.postAnchorWindowFraction,
            ),
            noticeReserveTokens = obj.optInt("noticeReserveTokens", defaults.noticeReserveTokens),
            noticeBaseTokens = obj.optInt("noticeBaseTokens", defaults.noticeBaseTokens),
            absoluteAgeTokens = obj.optInt("absoluteAgeTokens", defaults.absoluteAgeTokens),
            agingCharsPerToken = obj.optLong("agingCharsPerToken", defaults.agingCharsPerToken),
            digestScanMessages = obj.optInt("digestScanMessages", defaults.digestScanMessages),
            digestMaxLines = obj.optInt("digestMaxLines", defaults.digestMaxLines),
            digestMaxChars = obj.optInt("digestMaxChars", defaults.digestMaxChars),
            ledgerMaxEntries = obj.optInt("ledgerMaxEntries", defaults.ledgerMaxEntries),
        )
    }

    /** Persist a validated override for one session. Returns false when invalid. */
    fun save(filesDir: File?, sessionId: String, config: HarnessConfig): Boolean {
        if (filesDir == null || sessionId.isBlank()) return false
        val validated = runCatching { config.validated() }.getOrNull() ?: return false
        return runCatching {
            val target = file(filesDir, sessionId)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(toJson(validated), Charsets.UTF_8)
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrDefault(false)
    }

    /** Read the stored override, or null when none exists / it is unusable. */
    fun load(filesDir: File?, sessionId: String): HarnessConfig? {
        if (filesDir == null || sessionId.isBlank()) return null
        val target = file(filesDir, sessionId)
        if (!target.isFile) return null
        val text = runCatching { target.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return fromJson(text)
    }

    /**
     * [T-harness-config-store] Install this session's override (or reset to the
     * shipped defaults when it has none). Called from the session-switch seam so
     * switching chats also switches the numeric policy.
     *
     * Best-effort by contract: a bad file leaves the previous value in place
     * rather than failing the switch.
     */
    fun install(filesDir: File?, sessionId: String) {
        val loaded = load(filesDir, sessionId)
        if (loaded == null) {
            HarnessConfig.resetForTest()
            return
        }
        runCatching { HarnessConfig.install(loaded) }
    }

    /** Test seam: drop the stored file. */
    internal fun reset(filesDir: File?, sessionId: String) {
        if (filesDir == null) return
        runCatching { file(filesDir, sessionId).delete() }
    }
}
