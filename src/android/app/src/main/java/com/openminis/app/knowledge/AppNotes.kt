package com.openminis.app.knowledge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local app experience library: short UI facts the agent recorded about apps on
 * this phone — an entry it found, a trap it hit, a route that worked. One JSON file in the
 * app's files dir, hand-rolled with org.json. Each note remembers the app version it was last
 * confirmed on and turns stale once a different version is installed; stale notes sort last in
 * injections and are flagged as possibly outdated. Privacy filter rejects anything that looks
 * like personal data (email addresses, long digit runs, SMS verification codes).
 */
class AppNotes internal constructor(
    private val file: File,
    /** Installed version code of a package, or null when it is not installed. */
    private val versionOf: (String) -> String?,
    private val clock: () -> Long,
) {

    enum class Kind { entry, trap, fact, route }

    data class Note(
        val id: String,
        val pkg: String,
        val appVersion: String,
        val text: String,
        val kind: Kind,
        val uses: Int,
        val lastConfirmedAt: Long,
    )

    sealed interface Outcome {
        data class Saved(val note: Note) : Outcome
        /** An (almost) identical note already existed; it was re-confirmed instead. */
        data class Duplicate(val note: Note) : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    private val lock = Any()
    private var nextId = 1
    private val state = MutableStateFlow(load())

    /** Every note, storage order; UI layers observe this to refresh. */
    val all: StateFlow<List<Note>> get() = state.asStateFlow()

    fun isStale(n: Note): Boolean =
        n.appVersion.isNotEmpty() && versionOf(n.pkg)?.let { it != n.appVersion } == true

    /** Notes for [pkgs]: fresh first, then by last confirmation and uses; stale notes last. */
    fun forPackages(pkgs: Collection<String>): List<Note> = synchronized(lock) {
        state.value
            .filter { it.pkg in pkgs }
            .sortedWith(
                compareBy<Note> { isStale(it) }
                    .thenByDescending { it.lastConfirmedAt }
                    .thenByDescending { it.uses },
            )
    }

    /**
     * Record [text] for [pkg]. Normalizes whitespace; rejects empty/overlong/personal-looking
     * text; re-confirms a duplicate (same pkg + normalized text) instead of storing twice;
     * enforces [MAX_PER_APP] per package, evicting the least useful notes.
     */
    fun add(pkg: String, text: String, kind: String): Outcome = synchronized(lock) {
        val clean = clean(text)
        problem(clean)?.let { return Outcome.Rejected(it) }
        val requested = kind.trim().lowercase()
        val k = if (requested in kinds) requested else Kind.fact.name
        val notes = state.value
        duplicateOf(clean, notes.filter { it.pkg == pkg })?.let { dup ->
            val confirmed = dup.copy(
                uses = dup.uses + 1,
                lastConfirmedAt = clock(),
                appVersion = versionOf(pkg) ?: dup.appVersion,
            )
            save(notes.map { if (it.id == dup.id) confirmed else it })
            return Outcome.Duplicate(confirmed)
        }
        val now = clock()
        val note = Note("n${nextId++}", pkg, versionOf(pkg).orEmpty(), clean, Kind.valueOf(k), 0, now)
        save(evict(notes + note, note.id))
        Outcome.Saved(note)
    }

    /** Delete every note (Settings "clear all"). */
    fun clearAll() = synchronized(lock) { save(emptyList()) }

    /** Delete one note by id; returns true when it existed. */
    fun remove(id: String): Boolean = synchronized(lock) {
        val target = state.value.firstOrNull { it.id == id } ?: return false
        save(state.value.filter { it.id != target.id })
        true
    }

    /**
     * Model-facing block for an app's notes (at most [max]), or null when there are none.
     * Stale notes come last and are flagged as possibly outdated.
     */
    fun notice(pkgs: Collection<String>, label: String?, max: Int = MAX_NOTICE): String? {
        val notes = forPackages(pkgs)
        if (notes.isEmpty()) return null
        val shown = notes.take(max)
        return buildString {
            append("Notes on ")
            append(label ?: "this app")
            append(" recorded on this phone by earlier runs (UI facts; verify on screen):\n")
            shown.forEach { append(line(it)).append('\n') }
            if (notes.size > shown.size) append("(+${notes.size - shown.size} more)\n")
        }
    }

    /** One model-readable line: `[id] (kind) text` with a stale marker when outdated. */
    fun line(n: Note): String =
        "[${n.id}] (${n.kind.name}) ${n.text}" +
            if (isStale(n)) " (recorded on app version ${n.appVersion} - may be outdated)" else ""

    private fun evict(notes: List<Note>, keepId: String): List<Note> {
        val pkg = notes.first { it.id == keepId }.pkg
        val mine = notes.filter { it.pkg == pkg }
        if (mine.size <= MAX_PER_APP) return notes
        val drop = mine.filter { it.id != keepId }
            .sortedWith(compareBy<Note> { it.uses }.thenBy { it.lastConfirmedAt })
            .take(mine.size - MAX_PER_APP)
            .map { it.id }
            .toSet()
        return notes.filter { it.id !in drop }
    }

    private fun load(): List<Note> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            nextId = root.optInt("nextId", 1)
            val arr = root.optJSONArray("notes") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text")
                if (text.isEmpty()) return@mapNotNull null
                Note(
                    id = o.optString("id"),
                    pkg = o.optString("pkg"),
                    appVersion = o.optString("appVersion"),
                    text = text,
                    kind = runCatching { Kind.valueOf(o.optString("kind", "fact")) }.getOrDefault(Kind.fact),
                    uses = o.optInt("uses", 0),
                    lastConfirmedAt = o.optLong("lastConfirmedAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(notes: List<Note>) {
        state.value = notes
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            val root = JSONObject()
            root.put("nextId", nextId)
            val arr = JSONArray()
            notes.forEach { n ->
                arr.put(
                    JSONObject()
                        .put("id", n.id)
                        .put("pkg", n.pkg)
                        .put("appVersion", n.appVersion)
                        .put("text", n.text)
                        .put("kind", n.kind.name)
                        .put("uses", n.uses)
                        .put("lastConfirmedAt", n.lastConfirmedAt),
                )
            }
            root.put("notes", arr)
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "rename failed" }
            }
        }
    }

    companion object {
        const val FILE = "app_notes.json"
        const val MAX_PER_APP = 12
        const val MAX_NOTICE = 12
        const val MAX_LEN = 200
        private val kinds = Kind.entries.map { it.name }.toSet()

        /** Pre-compiled: clean() runs on every add/dedupe comparison. */
        private val WHITESPACE_RUN = Regex("\\s+")

        /** Collapse whitespace; the normalized form is what dedupe compares. */
        fun clean(text: String): String = text.trim().replace(WHITESPACE_RUN, " ")

        /** Letters and digits only, lowercased, so punctuation/spacing variants compare equal. */
        fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val LONG_DIGITS = Regex("\\d{6,}")
        // "code"/CJK(verification-code)/CJK(SMS) followed by a short number: SMS code style.
        // CJK spellings are written as unicode escapes: Kotlin main sources must stay CJK-free.
        private val SMS_CODE = Regex(
            "(?i)(\\bcode\\b|\\u9a8c\\u8bc1\\u7801|\\u77ed\\u4fe1)\\D{0,8}\\d{3,8}",
        )

        /** Why [text] cannot be stored, or null when it is acceptable. */
        fun problem(text: String): String? = when {
            text.isEmpty() -> "The note is empty."
            text.length > MAX_LEN -> "The note is ${text.length} characters; keep it to one sentence of at most $MAX_LEN."
            EMAIL.containsMatchIn(text) || LONG_DIGITS.containsMatchIn(text) || SMS_CODE.containsMatchIn(text) ->
                "Not stored: the note looks like it contains personal data (an email address, phone " +
                    "number, verification code or other long number). Notes are UI facts only; " +
                    "describe the screen without names, numbers or account details."
            else -> null
        }

        /** An existing note with the same normalized text. */
        fun duplicateOf(text: String, notes: List<Note>): Note? {
            val n = normalize(text)
            if (n.isEmpty()) return null
            return notes.firstOrNull { normalize(it.text) == n }
        }

        /** Display label of a package; falls back to the raw package name. */
        fun labelOf(context: Context, pkg: String): String = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        /** Installed version code ("100100") of [pkg], or null when not installed. */
        fun installedVersion(pm: PackageManager, pkg: String): String? = runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString()
            else @Suppress("DEPRECATION") info.versionCode.toString()
        }.getOrNull()

        fun open(context: Context): AppNotes = AppNotes(
            File(context.applicationContext.filesDir, FILE),
            { pkg -> installedVersion(context.packageManager, pkg) },
            { System.currentTimeMillis() },
        )
    }
}

/** Process-wide singleton so tools and settings read the same store. */
object AppNotesStore {
    @Volatile
    private var instance: AppNotes? = null

    fun forContext(context: Context): AppNotes =
        instance ?: synchronized(this) {
            instance ?: AppNotes.open(context.applicationContext).also { instance = it }
        }

    /** Test support: drop the singleton so a fresh file backs the next instance. */
    fun resetForTests() {
        synchronized(this) { instance = null }
    }
}
