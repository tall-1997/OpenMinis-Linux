package com.openminis.app.vscreen

import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager

/**
 * [T-android-vscreen2] The "silent ledger" (静音账本): every app started on
 * an agent-owned virtual screen is muted through `appops set <pkg>
 * PLAY_AUDIO ignore` (via the Shizuku shell), with the previous mode recorded
 * so it can be put back EXACTLY when the app leaves the screen — display
 * destroyed, app handed to the user, or a leftover entry found during
 * reconcile (process death / screen eviction without cleanup).
 *
 * The ledger is persisted in the vscreen prefs (one "pkg\tmode\tuser\tdisplayId\tat"
 * line per entry under [LEDGER_KEY]) so a fresh process restores whatever a
 * dead one left behind. All appops output parsing / restore-mode mapping is
 * pure and JVM-testable; the instance itself is stateless over [prefs], so
 * two instances (tool path + notification receiver) never diverge.
 *
 * Restore semantics follow the PLAY_AUDIO contract: a recorded "default"
 * (never explicitly set) is written back as "allow", which is what default
 * means for PLAY_AUDIO; any explicit mode is restored verbatim.
 */
class AudioMuter(
    private val shell: (Array<String>, Long) -> ShizukuManager.ProcessResult,
    private val prefs: VscreenPrefs,
    /** True when [prefs] survives process death (SharedPreferences) — drives the manager's rebuild decision. */
    val persistent: Boolean = false,
) {

    data class Entry(
        val pkg: String,
        val originalMode: String,
        val userId: Int,
        val displayId: Int,
        val mutedAtMs: Long,
    )

    // ── public ops ───────────────────────────────────────────────────────

    /**
     * Mute [pkg] for [displayId] BEFORE the app is started on it. Returns a
     * model-facing note — success reads "audio muted via appops (…)", failure
     * reads "audio mute failed: …" (launch proceeds either way; the failure
     * is reported, never swallowed). [mainVisible] (null when task listing is
     * unavailable) refuses to mute a package the user has open on the main
     * display — PLAY_AUDIO is per app, so their copy would go silent too.
     */
    fun mute(displayId: Int, pkg: String, mainVisible: ((String) -> Boolean?)? = null): String {
        if (!PKG.matches(pkg)) return "audio mute skipped: '$pkg' is not a valid package name"
        if (mainVisible != null && mainVisible(pkg) == true) {
            return "not muting $pkg — the user has it open on their screen right now"
        }
        val entries = load()
        val existing = entries[pkg]
        if (existing != null) {
            return "audio already muted via appops (PLAY_AUDIO was: ${existing.originalMode}, muted from display ${existing.displayId})"
        }
        val user = currentUser()
        val got = shell(arrayOf("appops", "get", "--user", user.toString(), pkg, PLAY_AUDIO), 10_000L)
        if (got.exitCode != 0) {
            return "audio mute failed: appops get exited ${got.exitCode} (${got.combined.take(200).ifBlank { "no output" }})"
        }
        val original = parseMode(got.combined)
        val set = shell(arrayOf("appops", "set", "--user", user.toString(), pkg, PLAY_AUDIO, "ignore"), 10_000L)
        if (set.exitCode != 0) {
            return "audio mute failed: appops set ignore exited ${set.exitCode} (${set.combined.take(200).ifBlank { "no output" }})"
        }
        entries[pkg] = Entry(pkg, original, user, displayId, System.currentTimeMillis())
        save(entries)
        AppLogger.info(TAG, "muted $pkg via appops (was: $original, display $displayId)")
        return "audio muted via appops (PLAY_AUDIO was: $original)"
    }

    /**
     * Restore [pkg]'s original PLAY_AUDIO mode and drop the ledger entry.
     * Returns a note, or null when nothing was muted (silent no-op).
     */
    fun unmute(pkg: String): String? {
        val entries = load()
        val entry = entries[pkg] ?: return null
        val restored = applyMode(entry, restoreMode(entry.originalMode))
        if (!restored) {
            return "audio restore failed: appops set ${restoreMode(entry.originalMode)} exited non-zero — ledger entry kept for a later retry"
        }
        entries.remove(pkg)
        save(entries)
        AppLogger.info(TAG, "restored $pkg via appops (back to: ${entry.originalMode})")
        return "audio restored via appops (PLAY_AUDIO back to: ${entry.originalMode})"
    }

    /** Restore everything muted on behalf of [displayId]. Returns the restored package names. */
    fun restoreForDisplay(displayId: Int): List<String> =
        load().values.filter { it.displayId == displayId }.mapNotNull { entry ->
            unmute(entry.pkg)
            entry.pkg
        }

    /**
     * Leftover sweep, run on every tool dispatch and after each create:
     *  - entries whose muting display no longer exists (process death,
     *    eviction without cleanup) → restore;
     *  - entries whose app is now visible on the main display AND no longer
     *    on its muting display (handed over or escaped) → restore, so the
     *    user never holds a muted app.
     *
     * [mainVisible] answers "is a task of this package on the main display?"
     * (null = task listing unavailable — the second rule is then skipped).
     * Returns the model-facing notes of every restoration that ran.
     */
    fun reconcile(liveDisplayIds: Set<Int>, mainVisible: ((String) -> Boolean?)? = null): List<String> {
        val notes = mutableListOf<String>()
        val entries = load()
        for (entry in entries.values.toList()) {
            if (entry.displayId !in liveDisplayIds) {
                unmute(entry.pkg)?.let { notes.add("leftover: ${entry.pkg} — $it") }
                continue
            }
            if (mainVisible != null) {
                val visibleOnMain = mainVisible(entry.pkg)
                if (visibleOnMain == true) {
                    unmute(entry.pkg)?.let { notes.add("moved to user: ${entry.pkg} — $it") }
                }
            }
        }
        return notes
    }

    fun mutedPackages(): List<String> = load().keys.toList()

    // ── persistence ──────────────────────────────────────────────────────

    private fun load(): MutableMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        prefs.getString(LEDGER_KEY)?.lineSequence()?.forEach { line ->
            parseLine(line)?.let { out[it.pkg] = it }
        }
        return out
    }

    private fun save(entries: Map<String, Entry>) {
        val text = entries.values.joinToString("\n") { e ->
            "${e.pkg}\t${e.originalMode}\t${e.userId}\t${e.displayId}\t${e.mutedAtMs}"
        }
        if (text.isEmpty()) prefs.remove(LEDGER_KEY) else prefs.putString(LEDGER_KEY, text)
    }

    private fun applyMode(entry: Entry, mode: String): Boolean =
        shell(arrayOf("appops", "set", "--user", entry.userId.toString(), entry.pkg, PLAY_AUDIO, mode), 10_000L).exitCode == 0

    /** The Android user to run appops against, probed once per process (`am get-current-user`), falling back to 0. */
    private fun currentUser(): Int {
        cachedUser?.let { return it }
        val r = shell(arrayOf("am", "get-current-user"), 5_000L)
        val parsed = parseCurrentUserId(r.combined)
        cachedUser = parsed
        return parsed
    }

    // ── pure parsing (JVM-tested) ────────────────────────────────────────

    companion object {
        private const val TAG = "AudioMuter"
        const val PLAY_AUDIO = "PLAY_AUDIO"
        const val LEDGER_KEY = "mute_ledger"

        /** Guard against shell metacharacters riding inside a package name. */
        val PKG = Regex("[A-Za-z0-9_.]+")

        /** `appops get` output → mode ("PLAY_AUDIO: ignore; time=…" → ignore); not set → "default". */
        fun parseMode(output: String): String =
            Regex("PLAY_AUDIO: ([a-z_]+)").find(output)?.groupValues?.get(1) ?: "default"

        /** "default" (never set) is written back as allow, which is what default means for PLAY_AUDIO. */
        fun restoreMode(mode: String): String = if (mode == "default") "allow" else mode

        fun parseLine(line: String): Entry? {
            val p = line.split("\t")
            if (p.size < 5) return null
            val pkg = p[0]
            if (!PKG.matches(pkg)) return null
            return Entry(
                pkg = pkg,
                originalMode = p[1].ifBlank { "default" },
                userId = p[2].toIntOrNull() ?: 0,
                displayId = p[3].toIntOrNull() ?: return null,
                mutedAtMs = p[4].toLongOrNull() ?: 0L,
            )
        }

        /** `am get-current-user` prints the id (sometimes with noise) — first integer wins, fallback 0. */
        fun parseCurrentUserId(output: String): Int =
            Regex("\\d+").find(output)?.value?.toIntOrNull() ?: 0

        /** Per-process cache for the current Android user id. */
        @Volatile var cachedUser: Int? = null
    }
}
