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
 * [T-asyncprefs-replay] Writes that land BEFORE the load completes are queued
 * in the override map and replayed into SharedPreferences once it resolves —
 * they used to exist only in memory and vanish on process death. If the load
 * itself failed, the next write schedules one async retry.
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
    // so a read immediately after a write returns the written value. Replayed
    // to disk once the load resolves ([T-asyncprefs-replay]).
    private val overrides = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val retryInFlight = AtomicBoolean(false)

    /** Start the background load. Idempotent. */
    fun warm() {
        if (!loaded.compareAndSet(false, true)) return
        Thread(
            {
                try {
                    prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    prefs?.let { replayOverrides(it) }
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
        val p = prefs
        if (p != null) p.edit().putInt(key, value).apply() else retryLoad()
    }

    fun putLong(key: String, value: Long) {
        overrides[key] = value
        val p = prefs
        if (p != null) p.edit().putLong(key, value).apply() else retryLoad()
    }

    /**
     * [T-asyncprefs-replay] Flush everything queued in [overrides] to disk.
     * Idempotent; concurrent putX calls write the same values either way.
     */
    private fun replayOverrides(p: SharedPreferences) {
        if (overrides.isEmpty()) return
        val ed = p.edit()
        for ((k, v) in overrides) when (v) {
            is Int -> ed.putInt(k, v)
            is Long -> ed.putLong(k, v)
        }
        ed.apply()
    }

    /**
     * [T-asyncprefs-replay] The initial load can fail (storage wedged at
     * boot). Without a retry, writes would live only in [overrides] forever.
     * One retry at a time, off the caller's thread; it replays everything
     * queued so far. Skipped while the initial load is still running — that
     * path replays on its own.
     */
    private fun retryLoad() {
        if (loadComplete.count > 0) return
        if (!retryInFlight.compareAndSet(false, true)) return
        Thread(
            {
                try {
                    val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    prefs = p
                    replayOverrides(p)
                } catch (t: Throwable) {
                    Log.w(TAG, "prefs load retry failed for $name: ${t.message}")
                } finally {
                    retryInFlight.set(false)
                }
            },
            "AsyncPrefs-$name-retry",
        ).apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "AsyncPrefs"

        /** Create + warm. The load itself is asynchronous. */
        fun create(context: Context, name: String): AsyncPrefs =
            AsyncPrefs(context.applicationContext, name).also { it.warm() }
    }
}
