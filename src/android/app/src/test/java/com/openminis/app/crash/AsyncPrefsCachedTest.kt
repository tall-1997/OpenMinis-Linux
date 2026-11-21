package com.openminis.app.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-asyncprefs-cached] [AsyncPrefs.create] hands out a fresh instance per
 * call, each with its own loader thread and its own pre-load default window.
 * A hot path that called it per read (SessionActivityTracker's presence
 * keep-alive check) multiplied threads AND briefly read a persisted OFF as
 * ON on every single call, because every fresh instance answered with the
 * caller default until its own load landed. [AsyncPrefs.cached] is the fix:
 * one instance per file name, reused by every caller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AsyncPrefsCachedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun cachedReturnsTheSameInstancePerFile() {
        val first = AsyncPrefs.cached(context, "asyncprefs_cached_identity_a")
        val second = AsyncPrefs.cached(context, "asyncprefs_cached_identity_a")
        assertSame(first, second)
        val other = AsyncPrefs.cached(context, "asyncprefs_cached_identity_b")
        assertNotSame(first, other)
    }

    @Test
    fun createKeepsItsFreshInstancePerCallSemantics() {
        val a = AsyncPrefs.create(context, "asyncprefs_create_fresh_a")
        val b = AsyncPrefs.create(context, "asyncprefs_create_fresh_a")
        assertNotSame(a, b)
    }

    /**
     * The defect the cache exists for: once the cached instance's load lands,
     * every later read returns the PERSISTED value instead of re-entering the
     * default window a fresh-per-call instance re-opened each time.
     */
    @Test
    fun cachedInstanceConvergesOnThePersistedValue() {
        val name = "asyncprefs_cached_converge"
        // Persist OFF directly, before the cached instance is ever built.
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
            .edit().putBoolean("presence", false).commit()
        val prefs = AsyncPrefs.cached(context, name)
        assertTrue(prefs.awaitLoaded())
        assertFalse("the persisted OFF must win over the caller default", prefs.getBoolean("presence", true))
        assertSame(prefs, AsyncPrefs.cached(context, name))
        assertFalse(AsyncPrefs.cached(context, name).getBoolean("presence", true))
    }
}
