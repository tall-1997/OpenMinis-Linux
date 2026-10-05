package com.openminis.app.crash

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-android-mainthread-prefs-hang] Lazy, off-main-thread SharedPreferences
 * loader.
 *
 * The first `getSharedPreferences()` call for a given name on a fresh process
 * is a *synchronous* disk read on the calling thread. When that call happens
 * on the main thread (a ViewModel constructor inside a Compose frame, a
 * per-tick UI heartbeat), a wedged filesystem stalls the whole UI for as long
 * as the read takes — the stall-2026-10-05 logs show 119s inside
 * ViewModelProvider.create and a separate 62-minute episode.
 *
 * `AsyncPrefs` inverts the shape: the first `getSharedPreferences()` runs on
 * a daemon thread; before it resolves, `get*` returns the caller-supplied
 * default. Writes go through the underlying prefs as soon as they are
 * available and are immediately mirrored into an in-memory override map, so
 * reads-after-writes within the same process always see the written value.
 *
 * Used today by HangDetector.markHealthyTick, which ChatScreen calls on the
 * main thread at a healthy cadence — the one HangDetector prefs touch that
 * cannot tolerate a storage stall.
 */
class AsyncPrefs private constructor(
    private val context: Context,
    private val name: String,
) {

    private val loaded = AtomicBoolean(false)
    @Volatile
    private var prefs: SharedPreferences? = null
    private val loadComplete = java.util.concurrent.CountDownLatch(1)
    // In-memory overrides written before the backing prefs finished loading,
    // so a read immediately after a write returns the written value.
    private val overrides = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Start the background load. Idempotent. */
    fun warm() {
        if (!loaded.compareAndSet(false, true)) return
        Thread(
            {
                try {
                    prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                } catch (t: Throwable) {
                    Log.w(TAG, "prefs load failed for $name: ${t.message}")
                } finally {
                    loadComplete.countDown()
                }
            },
            "AsyncPrefs-$name",
        ).apply { isDaemon = true }.start()
    }

    /**
     * Block until the initial load finished (or timed out). For callers on
     * background threads that need the PERSISTED value rather than the
     * default. Returns true when the backing prefs are usable.
     */
    fun awaitLoaded(timeoutMs: Long = 10_000L): Boolean {
        return try {
            loadComplete.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) && prefs != null
        } catch (_: InterruptedException) {
            false
        }
    }

    fun getInt(key: String, defValue: Int): Int {
        overrides[key]?.let { return it as Int }
        return prefs?.getInt(key, defValue) ?: defValue
    }

    fun getLong(key: String, defValue: Long): Long {
        overrides[key]?.let { return it as Long }
        return prefs?.getLong(key, defValue) ?: defValue
    }

    fun putInt(key: String, value: Int) {
        overrides[key] = value
        prefs?.edit()?.putInt(key, value)?.apply()
    }

    fun putLong(key: String, value: Long) {
        overrides[key] = value
        prefs?.edit()?.putLong(key, value)?.apply()
    }

    companion object {
        private const val TAG = "AsyncPrefs"

        /** Create + warm. The load itself is asynchronous. */
        fun create(context: Context, name: String): AsyncPrefs =
            AsyncPrefs(context.applicationContext, name).also { it.warm() }
    }
}
