package com.openminis.app.service

import android.content.Context
import android.os.SystemClock
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-oauth-wait-fgs] The set of OAuth sign-ins currently waiting for their
 * localhost redirect. Pure bookkeeping, no Android: [OAuthWaitKeepAlive] wraps
 * it with the service and the log.
 *
 * One entry per flow rather than a bare counter, so a flow ending twice (its
 * own stop() after a callback, then the caller's cleanup stop()) releases once,
 * and one flow ending never releases another's protection. Every entry carries
 * a deadline: a flow that never reaches stop() (the user closed the tab and
 * stayed on the screen, or the browser launch threw after the server started)
 * must not keep the service alive for ever.
 */
internal class OAuthWaitFlows(private val maxHoldMs: Long) {

    private data class Flow(val reason: String, val deadline: Long)

    private val flows = LinkedHashMap<String, Flow>()

    val count: Int get() = flows.size

    /** Register [id]; returns the new count. Re-adding an id refreshes it. */
    fun add(id: String, reason: String, now: Long): Int {
        flows[id] = Flow(reason, now + maxHoldMs)
        return flows.size
    }

    /** True when [id] was active (so this call is the one that ended it). */
    fun remove(id: String): Boolean = flows.remove(id) != null

    /** Drop every flow past its deadline; returns the reasons of those dropped. */
    fun prune(now: Long): List<String> {
        val expired = flows.filterValues { it.deadline <= now }
        expired.keys.forEach { flows.remove(it) }
        return expired.values.map { it.reason }
    }

    /** Earliest deadline, or null with nothing active. */
    fun nextDeadline(): Long? = flows.values.minOfOrNull { it.deadline }

    fun clear(): Int {
        val n = flows.size
        flows.clear()
        return n
    }
}

/**
 * [T-oauth-wait-fgs] Keeps Minis Ultra from being frozen while a sign-in waits for its
 * localhost redirect.
 *
 * Field log (HUAWEI tablet, Android 12): the Custom Tab opened at 15:19:36.160,
 * the whole process stopped a second later, and the redirect was served at
 * 15:20:16.278 - in the same millisecond the process thawed. With the browser
 * on top, Minis Ultra is a cached background process and the cached-apps freezer
 * stops it; the kernel still accepts the browser's connection to
 * localhost:1455, but the thread that would answer it is frozen, so the page
 * spins (~17 s for OpenAI in the user's video, who then gave up).
 *
 * [OAuthCallbackServer] calls [begin] when it starts listening - right before
 * the browser opens, from the user's tap, so the app is in the foreground - and
 * [end] on every way it stops. While at least one flow is active,
 * [OAuthWaitService] (a `dataSync` foreground service) holds the process at a
 * perceptible priority, out of the freezer's reach. Every provider (Claude
 * 54545, OpenAI 1455, xAI 56121, OpenRouter 3000) and MCP OAuth share the
 * server, so all get it without their own wiring.
 */
object OAuthWaitKeepAlive {

    private const val TAG = "OAuthWait"

    /**
     * Longest one flow keeps the service up: the same 5 minutes the token
     * exchange waits for the user to come back ([com.openminis.app.auth.OAuthForegroundGate]).
     * A sign-in takes 1-5 minutes, far inside the dataSync 6 h budget.
     */
    internal const val MAX_HOLD_MS = 5 * 60_000L

    private val lock = Any()
    private val flows = OAuthWaitFlows(MAX_HOLD_MS)
    private val _active = MutableStateFlow(0)

    /** Number of active flows; the service stops itself when it reaches 0. */
    val active: StateFlow<Int> = _active.asStateFlow()

    @Volatile private var appContext: Context? = null
    private var seq = 0

    /** Called once from MinisApp.onCreate; before it, [begin] only counts. */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * A flow started waiting. Returns its id for [end]. Never throws: a
     * service that cannot start must not stop the sign-in.
     */
    fun begin(reason: String): String {
        val (id, n) = synchronized(lock) {
            val id = "w${++seq}"
            id to flows.add(id, "$reason#$id", now()).also { _active.value = it }
        }
        AppLogger.info(TAG, "[OAuthWait] start reason=$reason id=$id active=$n")
        val ctx = appContext
        if (ctx == null) {
            AppLogger.warning(TAG, "[OAuthWait] not installed - no keep-alive for id=$id")
        } else {
            OAuthWaitService.start(ctx)
        }
        return id
    }

    /** The flow [id] ended ([reason]: callback / error-callback / stopped / ...). Idempotent. */
    fun end(id: String, reason: String) {
        val n = synchronized(lock) {
            if (!flows.remove(id)) return
            flows.count.also { _active.value = it }
        }
        AppLogger.info(TAG, "[OAuthWait] stop reason=$reason id=$id active=$n")
    }

    /** Release flows past their deadline; returns the active count after. */
    internal fun prune(): Int {
        val (expired, n) = synchronized(lock) {
            val e = flows.prune(now())
            e to flows.count.also { _active.value = it }
        }
        for (r in expired) {
            AppLogger.warning(TAG, "[OAuthWait] stop reason=deadline(${MAX_HOLD_MS / 1000}s) flow=$r active=$n")
        }
        return n
    }

    internal fun nextDeadline(): Long? = synchronized(lock) { flows.nextDeadline() }

    /** Forget every flow (the task was removed: the Custom Tab went with it). */
    internal fun clear(reason: String) {
        val dropped = synchronized(lock) { flows.clear().also { _active.value = 0 } }
        if (dropped > 0) AppLogger.info(TAG, "[OAuthWait] stop reason=$reason dropped=$dropped active=0")
    }

    /** Monotonic and counting deep sleep, so a deadline survives the screen being off. */
    internal fun now(): Long = SystemClock.elapsedRealtime()
}
