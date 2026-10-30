package com.openminis.app.a2a

import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.openminis.app.logging.AppLogger

/**
 * [T-android-a2a-incognito] Throwaway browser profiles for provider logins.
 *
 * Clearing cookies before each window was never enough: an account can be
 * restored from localStorage/IndexedDB/service-worker state even after its
 * cookies are gone, and "after the login succeeded, the session stays"
 * means adding the next account can still inherit the previous one in
 * every surface except the cookie jar. A WebView profile is a full
 * browser-profile boundary — cookies, DOM storage, IndexedDB, service
 * workers, HTTP cache — so the whole fingerprintable web state is fresh
 * per login and disappears when the round ends.
 *
 * Lifecycle mirrors a login round, not a window: the first `window.open`
 * of a round creates the profile, every login window of that round (the
 * shim's window plus any nested `window.open` it triggers) attaches to the
 * SAME profile — child windows must share the opener's session or the
 * provider's redirect flow breaks — and the profile is deleted once the
 * login-window board is empty ([endRound]). Deletion is best-effort and
 * must happen after the WebViews are destroyed; it is retried, and the
 * next round always starts a brand-new profile even if the previous
 * delete was refused.
 *
 * Graceful degradation: the API needs the `MULTI_PROFILE` WebView feature
 * (WebView 115+). When absent, [currentOrBegin] returns null and the caller
 * falls back to [A2aLoginCookies]' cookie sweep
 * ([A2aLoginCookies.clearProviderCookies] /
 * [A2aLoginCookies.clearProviderStorage]).
 *
 * What this is NOT: canvas/WebGL/font fingerprint randomization. No public
 * WebView API can vary those, and spoofing the UA alone would contradict
 * the engine's own client hints — a fresh profile (new cookie jar, new
 * storage, new cache) is the isolation a mobile shell can honestly provide.
 */
object A2aLoginProfiles {

    private const val TAG = "A2aLoginProfiles"

    private val lock = Any()
    private var active: Profile? = null
    private var activeName: String? = null

    /** True when the platform WebView can host multiple profiles. */
    fun isSupported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
            .getOrDefault(false)

    /**
     * The profile for the current login round, creating it on the first
     * call. Null means the platform cannot isolate — the caller must fall
     * back to the cookie sweep.
     */
    fun currentOrBegin(): Profile? {
        synchronized(lock) {
            active?.let { return it }
            if (!isSupported()) return null
            val name = "login-" + System.currentTimeMillis().toString(36) +
                "-" + (System.identityHashCode(lock) and 0xffff).toString(36)
            val profile = runCatching { ProfileStore.getInstance().getOrCreateProfile(name) }
                .onFailure { AppLogger.warning(TAG, "profile create failed: ${it.message}") }
                .getOrNull()
            active = profile
            if (profile != null) activeName = name
            return profile
        }
    }

    /**
     * Bind [webView] to [profile] before it loads anything. Returns false
     * when no profile was supplied or the platform refused the binding —
     * callers only use the result for logging; the login still works in the
     * default profile.
     */
    fun attach(webView: WebView, profile: Profile?): Boolean {
        if (profile == null) return false
        // The compat surface binds by profile NAME, not by Profile instance.
        return runCatching { WebViewCompat.setProfile(webView, profile.name); true }
            .onFailure { AppLogger.warning(TAG, "profile attach failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Finish the round: forget the profile and delete it (cookies, storage,
     * cache) off the main thread. Called when the last login window leaves
     * the board — the WebViews are destroyed one frame later, so deletion
     * waits and retries instead of racing them.
     */
    fun endRound() {
        // Profile has no delete(); ProfileStore deletes by name, so the round
        // remembers both.
        val profileName = synchronized(lock) {
            val current = activeName ?: return
            activeName = null
            active = null
            current
        }
        Thread {
            runCatching { Thread.sleep(DELETE_GRACE_MS) }
            repeat(DELETE_ATTEMPTS) { attempt ->
                val deleted = runCatching {
                    ProfileStore.getInstance().deleteProfile(profileName)
                }.getOrDefault(false)
                if (deleted) {
                    AppLogger.info(TAG, "login profile deleted")
                    return@Thread
                }
                runCatching { Thread.sleep(1_500L * (attempt + 1)) }
            }
            AppLogger.warning(TAG, "login profile could not be deleted; next round starts a new one")
        }.apply {
            isDaemon = true
            this.name = "a2a-profile-delete"
        }.start()
    }

    private const val DELETE_GRACE_MS = 1_200L
    private const val DELETE_ATTEMPTS = 2
}
