package com.openminis.app.vscreen

import android.content.Context

/**
 * [T-android-vscreen2] String key/value store seam for the vscreen package's
 * persistent state (mute ledger, handoff records, spillback notes).
 *
 * Production backs it with a package-private SharedPreferences file
 * ("vscreen"); JVM tests use [MemoryPrefs]. The whole vscreen state machine
 * layer therefore runs Robolectric-free.
 */
interface VscreenPrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun all(): Map<String, String>
}

/** SharedPreferences-backed store for the vscreen package. */
class SharedPrefsVscreenPrefs(context: Context) : VscreenPrefs {
    private val prefs = com.openminis.app.config.Prefs.file(PREFS_NAME)

    override fun getString(key: String): String? = prefs.stringOrNull(key)

    override fun putString(key: String, value: String) {
        prefs.set(key, value)
    }

    override fun remove(key: String) {
        prefs.remove(key)
    }

    override fun all(): Map<String, String> =
        prefs.snapshot().mapValues { it.value?.toString() ?: "" }

    companion object {
        const val PREFS_NAME = "vscreen"
    }
}

/** In-memory store for JVM tests. */
class MemoryPrefs : VscreenPrefs {
    private val map = LinkedHashMap<String, String>()

    @Synchronized
    override fun getString(key: String): String? = map[key]

    @Synchronized
    override fun putString(key: String, value: String) {
        map[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        map.remove(key)
    }

    @Synchronized
    override fun all(): Map<String, String> = map.toMap()
}
