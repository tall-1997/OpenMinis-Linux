package com.openminis.app.a2a

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable state of the A2A gateway. Field order is the contract the UI
 * was built against — positional construction elsewhere depends on it.
 */
data class A2aStatus(
    val installed: Boolean = false,
    val running: Boolean = false,
    val port: Int = A2aGateway.PORT,
    val panelUrl: String? = null,
    val lastError: String? = null,
)

/**
 * [T-a2a-gateway] Manager singleton for the A2A gateway — a2a as a
 * first-class feature, not a builtin plugin. It used to be a catalog
 * descriptor (`plugins/builtin/`) routed through NativeGatewayInstaller /
 * NativeGatewayService and the market card; it left that system for this
 * package and the engine moved with it ([A2aInstaller],
 * [A2aGatewayProcess]). The lane layer is untouched:
 * [com.openminis.app.provider.Agent2ApiFreeLane] still pins the same port
 * and the same loopback semantics.
 *
 * The three actions ([ensureInstalled], [start], [stop]) are **blocking,
 * synchronous, idempotent** — the caller owns the dispatching (the UI
 * launches them on Dispatchers.IO, exactly the discipline the old market
 * card's fire-and-forget toggle followed), and their outcomes surface on
 * [status] as well as their return values. One lock serializes install +
 * start + stop: the same gateway can never be deployed twice or started
 * and stopped concurrently, so the UI needs no gating of its own.
 *
 * [isRunning] / [panelUrl] are the live probes: the port is the ownership
 * invariant, so "running" always means "the port answers right now", not
 * "we remember spawning it".
 *
 * The lifecycle is decoupled from the plugin stores: a2a does not read the
 * plugin registry or the builtin-plugin state — its own enable/disable
 * persistence lives in the `a2a_gateway` prefs ([isEnabled], written by
 * [start] / [stop]).
 */
object A2aGateway {
    /** Stable id — keys the app-private home dir; never renamed. */
    const val ID = "a2a"

    /**
     * Loopback port the gateway binds. The lane
     * ([com.openminis.app.provider.Agent2ApiFreeLane]) and the binary's env
     * contract pin the same value — the port IS the ownership on a host
     * that carries every sandbox service, so it never moves.
     */
    const val PORT = 18901

    /** Panel path on the port — the web panel serves at the root. */
    private const val PANEL_PATH = "/"

    /**
     * Own enable/disable persistence. SharedPreferences, not Room and not
     * the plugin stores (`plugin_registry`, `builtin_plugin_state`): a flat
     * boolean avoids a schema migration and keeps the lifecycle out of the
     * plugin system. [start] writes true, [stop] writes false — the flip is
     * the user's intent, recorded regardless of the outcome.
     */
    private const val PREFS = "a2a_gateway"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INCOGNITO_LOGIN = "incognito_login"

    private val _status = MutableStateFlow(A2aStatus())
    val status: StateFlow<A2aStatus> = _status.asStateFlow()

    private val lock = Any()

    /** The loopback port the gateway serves. */
    fun port(): Int = PORT

    /**
     * The loopback panel URL while the gateway answers
     * (`http://127.0.0.1:<port>` at the panel path), null otherwise. Live
     * probe — a dead gateway never yields a URL. Blocking like every probe;
     * prefer reading [status] on the main thread.
     */
    fun panelUrl(): String? = if (A2aGatewayProcess.isRunning()) panelUrlNow() else null

    private fun panelUrlNow(): String = "http://127.0.0.1:$PORT$PANEL_PATH"

    /**
     * True when the home layout is complete — the deployed panel bundle plus
     * the native binary in the APK's nativeLibraryDir (see
     * [A2aInstaller.isDeployed]). Pure disk reads.
     */
    fun isInstalled(context: Context): Boolean = A2aInstaller.isDeployed(context)

    /**
     * Own enable/disable state — the persisted flag the lifecycle writes and
     * a future auto-start reads. Part of this object's public face so the
     * UI (and anything after it) stays out of the prefs file itself.
     */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    internal fun writeEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /**
     * Incognito login (default ON) — [T-android-a2a-incognito]: every provider
     * login window starts from a clean cookie jar, so adding a second account
     * of the same provider never gets silently pre-authenticated as the
     * first. Read once when the panel activity launches and carried in the
     * intent (SharedPreferences has no cross-process invalidation, so the
     * :a2a process must not re-read it per window).
     */
    fun isIncognitoLogin(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INCOGNITO_LOGIN, true)

    fun setIncognitoLogin(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCOGNITO_LOGIN, value).apply()
    }

    /**
     * The engine's stdout log (login state machine, security, session). Read
     * by the in-app log viewer in both processes; app-private, same uid.
     * [T-android-a2a-login-capture]
     */
    fun gatewayLogFile(context: Context): java.io.File =
        java.io.File(A2aGatewayProcess.homeDir(context.filesDir.path), "data/gateway.log")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Deploy the panel bundle + the state dirs when the home layout is
     * incomplete; a no-op once deployed. Blocking (an asset unzip) — call
     * off the main thread. Never touches the running state: adopting an
     * orphaned gateway is [start]'s port probe. On completion
     * [A2aStatus.installed] reflects the outcome and [A2aStatus.lastError]
     * carries the failure reason.
     */
    fun ensureInstalled(context: Context) {
        synchronized(lock) {
            ensureInstalledLocked(context)
        }
    }

    /**
     * Idempotently start the gateway as a host process: deploy when needed
     * (the binary's env contract points HOME/PROXY_HOME into the private
     * home — it must exist before the gateway binds), then the
     * port-ownership probe adopts an already-serving gateway (ours or one
     * orphaned by a crash) instead of spawning a duplicate. Blocking (health
     * wait up to 20s) — call off the main thread. [A2aStatus.running] flips
     * with the outcome and [A2aStatus.lastError] carries the failure reason.
     */
    fun start(context: Context) {
        synchronized(lock) {
            writeEnabled(context, true)
            ensureInstalledLocked(context)
            val error = A2aGatewayProcess.start(context)
            _status.value = if (error == null) {
                _status.value.copy(
                    running = true,
                    panelUrl = panelUrlNow(),
                    lastError = null,
                )
            } else {
                _status.value.copy(
                    running = false,
                    panelUrl = null,
                    lastError = error,
                )
            }
        }
    }

    /**
     * Stop the gateway (TERM -> KILL, orphans included — see
     * [A2aGatewayProcess.stop]). Idempotent — a dead gateway is a success.
     * Blocking (grace window up to 5s) — call off the main thread.
     */
    fun stop(context: Context) {
        synchronized(lock) {
            writeEnabled(context, false)
            val error = A2aGatewayProcess.stop(context)
            _status.value = _status.value.copy(
                running = false,
                panelUrl = null,
                lastError = error,
            )
        }
    }

    /**
     * Re-derive [A2aStatus] from the world (disk install state + loopback
     * port probe). The singleton's StateFlow starts at defaults on a cold
     * app process, so without this the manage screen shows "not installed"
     * for a deployed gateway until the user touches a control. Blocking
     * (one port probe) — call off the main thread.
     */
    fun refresh(context: Context) {
        synchronized(lock) {
            val running = A2aGatewayProcess.isRunning()
            _status.value = _status.value.copy(
                installed = A2aInstaller.isDeployed(context),
                running = running,
                panelUrl = if (running) panelUrlNow() else null,
            )
        }
    }

    /**
     * Cold-boot restore for gateway-owned scheduled tasks (sign-ins etc.):
     * the executor lives in the gateway process, so tasks only run while it
     * does. When the user left the switch on, bring the gateway back
     * without waiting for them to visit the screen — refresh + adopt-or-
     * start, silently (a failure here just records lastError). Blocking —
     * call off the main thread. Returns true when a gateway is serving.
     */
    fun maybeAutoStart(context: Context): Boolean {
        if (!isEnabled(context) || !isInstalled(context)) {
            refresh(context)
            return false
        }
        if (A2aGatewayProcess.isRunning()) {
            refresh(context)
            return true
        }
        start(context)
        return A2aGatewayProcess.isRunning()
    }

    private fun ensureInstalledLocked(context: Context) {
        if (A2aInstaller.isDeployed(context)) {
            _status.value = _status.value.copy(installed = true)
            return
        }
        val error = A2aInstaller.install(context)
        _status.value = _status.value.copy(
            installed = error == null,
            lastError = error,
        )
    }
}
