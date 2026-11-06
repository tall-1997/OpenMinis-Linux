package com.openminis.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * T-android-keystore-aead-fail: self-healing wrapper around
 * [EncryptedSharedPreferences.create].
 *
 * The default flow throws `AEADBadTagException` (wrapped as
 * `GeneralSecurityException`) on launch when the AndroidKeystore master
 * key can no longer decrypt the Tink keyset blob — observed on Samsung
 * One UI / Android 16 after backup-restore or biometric re-enroll. The
 * exception bubbles to the main thread and the app dies in a relaunch
 * loop because every cold start hits the same lazy init.
 *
 * [T-android-keystore-no-cascade] (port-upstream-1.16) The earlier version
 * deleted the XML with File.delete(), which leaves the process-wide
 * SharedPreferences cache holding the old keyset, so the rebuild re-read it
 * and always failed; callers that construct a store per call (backup page,
 * OAuth routing checks) re-ran the whole wipe on every call; and nothing ever
 * distinguished "this file's keyset is orphaned" from "the master key itself
 * is gone", so a healthy key could be blamed for one bad store.
 *
 * Strategy now (isolated per store: rebuild only the damaged segment):
 *  1. One open per file per process, under a lock ([cache]).
 *  2. Try the normal create, then once more (a Keystore hiccup must not cost
 *     data).
 *  3. Still failing: probe the master key with an encrypt/decrypt round trip.
 *     If it works, only THIS file's keyset is orphaned - drop just this file
 *     via deleteSharedPreferences (clears the framework cache too). Only a
 *     master key that cannot encrypt at all is recreated; every store under
 *     it is unreadable already, so that costs nothing extra. Either way the
 *     loss is bounded to the one store and logged loudly.
 *  4. Rebuild failing even then: an in-memory store, so the app keeps running
 *     without crashing and NOTHING reaches disk unencrypted - this tree
 *     deliberately refuses the plain-text downgrade upstream falls back to
 *     ([T-android-keystore-cascade]); the user re-enters the store's
 *     credentials after relaunch instead of having them sit in the clear.
 */
object EncryptedPrefsFactory {
    private const val TAG = "EncryptedPrefsFactory"

    private val lock = Any()
    private val cache = HashMap<String, SharedPreferences>()

    fun safeCreate(context: Context, fileName: String): SharedPreferences =
        cached(fileName) { open(AndroidOps(context.applicationContext), fileName) }

    /** One instance per file per process; the lock also serialises every create. */
    internal fun cached(fileName: String, create: () -> SharedPreferences): SharedPreferences =
        synchronized(lock) { cache.getOrPut(fileName, create) }

    /** Test hook: forget opened stores. */
    internal fun resetCacheForTest() = synchronized(lock) { cache.clear() }

    /** Everything [open] needs from the platform; a fake drives it in unit tests. */
    internal interface Ops {
        fun build(fileName: String): SharedPreferences
        /** True when the shared master key can still encrypt and decrypt. */
        fun masterKeyWorks(): Boolean
        /** Delete this one store, on disk and in the process-wide prefs cache. */
        fun discardStore(fileName: String)
        fun deleteMasterKey()
        /** The last-resort non-persisting store (see the class KDoc, step 4). */
        fun inMemory(): SharedPreferences
        fun log(priority: Int, msg: String, t: Throwable? = null)
    }

    internal fun open(ops: Ops, fileName: String): SharedPreferences {
        fun attempt(label: String, priority: Int = Log.WARN): SharedPreferences? = try {
            ops.build(fileName)
        } catch (t: Throwable) {
            ops.log(priority, "$label($fileName) failed: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }

        attempt("first create")?.let { return it }
        attempt("retry create")?.let { return it }

        if (ops.masterKeyWorks()) {
            ops.log(Log.WARN, "master key OK - discarding only $fileName (its keyset was wrapped by another key)")
            ops.discardStore(fileName)
        } else {
            ops.log(Log.ERROR, "master key unusable - recreating it; discarding $fileName")
            ops.deleteMasterKey()
            ops.discardStore(fileName)
        }

        attempt("rebuild", Log.ERROR)?.let { return it }

        ops.log(Log.ERROR, "Encrypted storage unavailable for $fileName - using a NON-PERSISTING in-memory store")
        return ops.inMemory()
    }

    private class AndroidOps(private val context: Context) : Ops {
        override fun build(fileName: String): SharedPreferences {
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        override fun masterKeyWorks(): Boolean = (1..2).any { roundTrip() }

        private fun roundTrip(): Boolean = runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            // No key at all: nothing to recreate, and nothing to blame on it.
            val key = ks.getKey(MasterKey.DEFAULT_MASTER_KEY_ALIAS, null) as? SecretKey
                ?: return@runCatching true
            val probe = "minis-keystore-probe".toByteArray()
            val enc = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val sealed = enc.doFinal(probe)
            val dec = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, enc.iv)) }
            dec.doFinal(sealed).contentEquals(probe)
        }.onFailure { log(Log.WARN, "master key probe failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)

        override fun discardStore(fileName: String) {
            runCatching { context.deleteSharedPreferences(fileName) }
                .onFailure { log(Log.WARN, "discard $fileName failed: ${it.message}") }
        }

        override fun deleteMasterKey() {
            runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            }.onFailure { log(Log.WARN, "delete master key failed: ${it.message}") }
        }

        override fun inMemory(): SharedPreferences = InMemorySharedPreferences()

        override fun log(priority: Int, msg: String, t: Throwable?) {
            if (t != null && priority >= Log.ERROR) Log.println(priority, TAG, msg + "\n" + Log.getStackTraceString(t))
            else Log.println(priority, TAG, msg)
        }
    }

    private fun build(context: Context, fileName: String): SharedPreferences {
        // The default alias stays: existing installs have their keysets wrapped
        // by it, and switching per file would orphan all stored credentials.
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Non-persisting [SharedPreferences] used when encrypted storage is
     * unavailable. Reads see what was written this process only; nothing reaches
     * the filesystem.
     */
    private class InMemorySharedPreferences : SharedPreferences {
        private val values = java.util.concurrent.ConcurrentHashMap<String, Any?>()
        private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = HashMap<String, Any?>(values)

        @Suppress("UNCHECKED_CAST")
        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.let { HashSet(it) } ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>(values)
            private var clearFirst = false

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) =
                apply { pending[key] = values?.let { HashSet(it) } }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending.remove(key) }
            override fun clear() = apply { pending.clear(); clearFirst = true }
            override fun commit(): Boolean {
                applyToStore()
                return true
            }
            override fun apply() { applyToStore() }
            private fun applyToStore() {
                if (!clearFirst) pending.keys.removeAll(values.keys - pending.keys)
                else { values.clear(); clearFirst = false }
                val changed = pending.keys.filter { values[it] != pending[it] }
                pending.forEach { (k, v) -> values[k] = v }
                synchronized(listeners) { listeners.toList() }.forEach { l ->
                    changed.forEach { l.onSharedPreferenceChanged(this@InMemorySharedPreferences, it) }
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) { if (listener != null) synchronized(listeners) { listeners.add(listener) } }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) { if (listener != null) synchronized(listeners) { listeners.remove(listener) } }
    }
}
