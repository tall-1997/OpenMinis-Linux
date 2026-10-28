package com.openminis.app.plugins.builtin

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [T-builtin-plugins] Runs the installed builtin-plugin gateways as managed
 * guest services: start (nohup + pidfile), stop (pidfile kill with a pkill
 * sweep), and health polling on the loopback port.
 *
 * The commands are pure builders so the tests can pin their exact shape; the
 * executor is a thin wrapper over [com.openminis.app.sandbox.ExecutionCoordinator]
 * with a short timeout — the start command returns as soon as the pidfile is
 * written (nohup detaches the gateway), and health confirmation is a separate
 * HTTP poll from the host, not the guest.
 *
 * One service per descriptor, one mutex per service: starting two gateways
 * concurrently is fine (different ports), racing the SAME gateway's start
 * and stop is not.
 */
object GatewayServiceManager {
    private const val TAG = "GatewayServiceManager"

    private const val SESSION_ID = "builtin-gateway-service"
    private const val CMD_TIMEOUT_MS = 30_000L

    enum class ServiceState { STOPPED, STARTING, RUNNING, STOPPING }

    data class ServiceStatus(
        val pluginId: String,
        val state: ServiceState = ServiceState.STOPPED,
        /** First health-probe line that confirmed RUNNING, for the card. */
        val detail: String = "",
        val updatedAtMs: Long = 0L,
    )

    private val _statuses = MutableStateFlow<Map<String, ServiceStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, ServiceStatus>> = _statuses.asStateFlow()

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    /** Fire-and-forget toggle the UI calls; survives leaving the screen. */
    fun toggle(d: BuiltinPluginCatalog.Descriptor, target: Boolean) {
        scope.launch {
            if (target) start(d) else stop(d)
        }
    }

    private val locks = mutableMapOf<String, Mutex>()

    private fun lockFor(pluginId: String): Mutex = synchronized(locks) {
        locks.getOrPut(pluginId) { Mutex() }
    }

    private fun setStatus(pluginId: String, state: ServiceState, detail: String = "") {
        _statuses.value = _statuses.value + (pluginId to ServiceStatus(
            pluginId = pluginId,
            state = state,
            detail = detail,
            updatedAtMs = System.currentTimeMillis(),
        ))
    }

    fun statusOf(pluginId: String): ServiceStatus =
        _statuses.value[pluginId] ?: ServiceStatus(pluginId)

    /**
     * Guest command that backgrounds the launcher and writes its pid. nohup
     * + redirect + `&` is the only detachment shape that survives the
     * coordinator's session teardown; the pidfile under data/ is what stop
     * reads back. The mkdir guards the redirect: data/ exists after a
     * successful install, but a wiped install root (or a first start racing
     * the install) would make the shell fail the redirection before nohup
     * even runs. Pure — the test pins the exact string.
     */
    internal fun startCommand(d: BuiltinPluginCatalog.Descriptor): String {
        val root = BuiltinPluginCatalog.installRoot(d.id)
        val launcher = "${BuiltinPluginCatalog.toolDir(d.id)}/bin/${d.launcher}"
        return "mkdir -p $root/data; " +
            "nohup $launcher > $root/data/gateway.log 2>&1 & echo $! > $root/data/gateway.pid"
    }

    /**
     * Guest command that stops the service: kill the pidfile pid, then a
     * pkill sweep scoped to the launcher directory so a stale pidfile (or
     * a launcher that re-exec'd) cannot leave the port squatted. The sweep
     * pattern uses the bracket trick (`[t]ool` for `tool`): the command
     * string rides in the wrapper shell's argv, and a pattern that matches
     * that text kills the wrapper (and its process group) before `true`
     * runs — the stop then surfaces as SIGTERM instead of exit 0. The
     * bracketed token must be one ABSENT from the rest of the command:
     * `tool/bin` never appears in the pidfile path, so `[t]ool/bin/` can
     * only match real launcher processes. `true` tails it: stop must be
     * idempotent — a dead gateway is a success, not an error the card has
     * to surface. Pure.
     */
    internal fun stopCommand(d: BuiltinPluginCatalog.Descriptor): String {
        val root = BuiltinPluginCatalog.installRoot(d.id)
        return "kill \"$(cat $root/data/gateway.pid 2>/dev/null)\" 2>/dev/null; " +
            "pkill -f \"$root/[t]ool/bin/\" 2>/dev/null; true"
    }

    /** Start the gateway and wait for its health endpoint to answer. */
    suspend fun start(d: BuiltinPluginCatalog.Descriptor): Boolean = lockFor(d.id).withLock {
        setStatus(d.id, ServiceState.STARTING)
        try {
            val result = com.openminis.app.sandbox.ExecutionCoordinator.execute(
                sessionId = SESSION_ID,
                command = startCommand(d),
                timeout = CMD_TIMEOUT_MS,
            )
            if (result.exitCode != 0) {
                AppLogger.warning(TAG, "start ${d.id}: exit ${result.exitCode}")
                setStatus(d.id, ServiceState.STOPPED, "start exit ${result.exitCode}")
                return false
            }
            val healthy = waitForHealth(d)
            setStatus(
                d.id,
                if (healthy) ServiceState.RUNNING else ServiceState.STOPPED,
                if (healthy) BuiltinPluginCatalog.healthUrl(d) else "health timeout",
            )
            healthy
        } finally {
            // STARTING must never linger: every path above already set a
            // terminal state; this only guards a thrown exception.
            if (_statuses.value[d.id]?.state == ServiceState.STARTING) {
                setStatus(d.id, ServiceState.STOPPED, "start aborted")
            }
        }
    }

    /** Stop the gateway; idempotent — a dead gateway is a success. */
    suspend fun stop(d: BuiltinPluginCatalog.Descriptor): Boolean = lockFor(d.id).withLock {
        setStatus(d.id, ServiceState.STOPPING)
        try {
            val result = com.openminis.app.sandbox.ExecutionCoordinator.execute(
                sessionId = SESSION_ID,
                command = stopCommand(d),
                timeout = CMD_TIMEOUT_MS,
            )
            val ok = result.exitCode == 0
            setStatus(d.id, ServiceState.STOPPED, if (ok) "" else "stop exit ${result.exitCode}")
            ok
        } finally {
            if (_statuses.value[d.id]?.state == ServiceState.STOPPING) {
                setStatus(d.id, ServiceState.STOPPED, "stop aborted")
            }
        }
    }

    /**
     * Host-side health poll on the loopback port. Retries for a few seconds
     * because the gateway needs a moment to bind after nohup returns.
     */
    private suspend fun waitForHealth(d: BuiltinPluginCatalog.Descriptor): Boolean =
        withContext(Dispatchers.IO) {
            val url = BuiltinPluginCatalog.healthUrl(d)
            val deadline = System.currentTimeMillis() + 20_000L
            while (System.currentTimeMillis() < deadline) {
                try {
                    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 2_000
                    connection.readTimeout = 2_000
                    connection.requestMethod = "GET"
                    val code = connection.responseCode
                    connection.disconnect()
                    if (code in 200..299) return@withContext true
                } catch (_: Exception) {
                    // Not up yet — keep polling until the deadline.
                }
                kotlinx.coroutines.delay(500)
            }
            false
        }
}
