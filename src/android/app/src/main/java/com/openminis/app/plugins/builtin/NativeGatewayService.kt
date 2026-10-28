package com.openminis.app.plugins.builtin

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * [T-builtin-plugins] Runs a **native** builtin-plugin gateway as a host
 * app process: start ([ProcessBuilder] on the APK's own binary), stop
 * (graceful TERM → KILL), and the same loopback health poll
 * [GatewayServiceManager] uses.
 *
 * Same public shape as [GatewayServiceManager] — it reuses that object's
 * [GatewayServiceManager.ServiceState] and [GatewayServiceManager.ServiceStatus]
 * types, statuses flow, fire-and-forget toggle, per-plugin mutex — so the
 * market card and the lane layer treat both identically. The differences
 * are all under the hood:
 *
 *  - **No sandbox session.** The binary is bionic, linked against the
 *    platform's own libc, and runs as the app's uid directly. No PRoot
 *    translation layer between the gateway and the kernel.
 *  - **No pidfile.** [Process] owns the handle for the child we spawned,
 *    but the real ownership invariant is the port: if it answers, a
 *    gateway is already running and start is a no-op success. This also
 *    adopts a gateway left behind by a crashed app process.
 *  - **Stop reaches orphans.** A crash can orphan the gateway (the child
 *    outlives the dead app process), and an adopted gateway has no
 *    [Process] handle at all. So stop TERMs every same-uid process whose
 *    cmdline argv[0] is this plugin's binary — a /proc scan, not a
 *    pidfile — waits out the grace window for the port to stop answering,
 *    then KILLs survivors. TERM-then-KILL either way: the upstream server
 *    traps SIGTERM for a clean shutdown; KILL only fires after the grace
 *    period so a wedged process cannot squat the port.
 */
object NativeGatewayService {
    private const val TAG = "NativeGatewayService"

    private const val TERM_GRACE_MS = 5_000L

    private val _statuses = MutableStateFlow<Map<String, GatewayServiceManager.ServiceStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, GatewayServiceManager.ServiceStatus>> = _statuses.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var process: Process? = null

    private val lock = Mutex()

    /** Fire-and-forget toggle the UI calls; survives leaving the screen. */
    fun toggle(context: Context, d: BuiltinPluginCatalog.Descriptor, target: Boolean) {
        scope.launch {
            if (target) start(context, d) else stop(context, d)
        }
    }

    private fun setStatus(
        pluginId: String,
        state: GatewayServiceManager.ServiceState,
        detail: String = "",
    ) {
        _statuses.value = _statuses.value + (pluginId to GatewayServiceManager.ServiceStatus(
            pluginId = pluginId,
            state = state,
            detail = detail,
            updatedAtMs = System.currentTimeMillis(),
        ))
    }

    fun statusOf(pluginId: String): GatewayServiceManager.ServiceStatus =
        _statuses.value[pluginId] ?: GatewayServiceManager.ServiceStatus(pluginId)

    /**
     * The command line the gateway process is launched with. Pure — the
     * test pins the exact argv shape: binary from nativeLibraryDir, env
     * from [BuiltinPluginCatalog.nativeEnv], no shell, no wrapper. The
     * binary parses no CLI args (even `--help` boots the server), so argv
     * is exactly one element.
     */
    internal fun launchArgv(context: Context, d: BuiltinPluginCatalog.Descriptor): List<String> =
        listOf(File(context.applicationInfo.nativeLibraryDir, requireNotNull(d.nativeBinary)).path)

    /**
     * Start the gateway as a host process and wait for its health endpoint.
     * Idempotent: when the port already answers, a previous process (ours
     * or one orphaned by a crash) is already serving — adopt it and report
     * RUNNING without spawning a duplicate.
     */
    suspend fun start(context: Context, d: BuiltinPluginCatalog.Descriptor): Boolean =
        lock.withLock {
            setStatus(d.id, GatewayServiceManager.ServiceState.STARTING)
            try {
                if (probeHealth(d)) {
                    setStatus(d.id, GatewayServiceManager.ServiceState.RUNNING, BuiltinPluginCatalog.healthUrl(d))
                    return true
                }
                val home = File(BuiltinPluginCatalog.nativeHomeDir(context.filesDir.path, d.id))
                val log = File(home, "data/gateway.log")
                log.parentFile?.mkdirs()
                val pb = ProcessBuilder(launchArgv(context, d)).apply {
                    redirectErrorStream(true)
                    redirectOutput(log)
                    environment().putAll(BuiltinPluginCatalog.nativeEnv(d, context.filesDir.path))
                }
                val proc = pb.start()
                process = proc
                val healthy = waitForHealth(d)
                if (!healthy) {
                    // Do not leave a crash-looping zombie squatting the slot.
                    proc.destroy()
                    process = null
                    setStatus(d.id, GatewayServiceManager.ServiceState.STOPPED, "health timeout")
                    return false
                }
                setStatus(d.id, GatewayServiceManager.ServiceState.RUNNING, BuiltinPluginCatalog.healthUrl(d))
                true
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "start ${d.id}: ${t.message}")
                setStatus(d.id, GatewayServiceManager.ServiceState.STOPPED, t.message ?: "start failed")
                false
            } finally {
                if (_statuses.value[d.id]?.state == GatewayServiceManager.ServiceState.STARTING) {
                    setStatus(d.id, GatewayServiceManager.ServiceState.STOPPED, "start aborted")
                }
            }
        }

    /**
     * Stop the gateway: TERM every process running this plugin's binary —
     * the tracked child plus any same-uid orphan a crash left behind —
     * wait out the grace window for the port to stop answering, then KILL
     * the survivors. Idempotent — a dead gateway is a success.
     */
    suspend fun stop(context: Context, d: BuiltinPluginCatalog.Descriptor): Boolean =
        lock.withLock {
            setStatus(d.id, GatewayServiceManager.ServiceState.STOPPING)
            try {
                val proc = process
                if (proc != null) {
                    proc.destroy()
                    process = null
                }
                signalPids(orphanPids(context, d), OsConstants.SIGTERM)
                val deadline = System.currentTimeMillis() + TERM_GRACE_MS
                while (probeHealth(d) && System.currentTimeMillis() < deadline) delay(200)
                if (probeHealth(d)) {
                    signalPids(orphanPids(context, d), OsConstants.SIGKILL)
                    if (proc?.isAlive == true) proc.destroyForcibly()
                }
                setStatus(d.id, GatewayServiceManager.ServiceState.STOPPED)
                true
            } finally {
                if (_statuses.value[d.id]?.state == GatewayServiceManager.ServiceState.STOPPING) {
                    setStatus(d.id, GatewayServiceManager.ServiceState.STOPPED, "stop aborted")
                }
            }
        }

    /**
     * PIDs of processes (this uid only — /proc hides the rest) whose
     * cmdline argv[0] is exactly this plugin's binary path. This is the
     * orphan detector: the tracked child matches too, which is harmless —
     * it gets the same TERM twice. Pure scan, no signals sent.
     */
    internal fun orphanPids(context: Context, d: BuiltinPluginCatalog.Descriptor): List<Int> {
        val bin = launchArgv(context, d).first()
        val mine = android.os.Process.myPid()
        return File("/proc").listFiles()
            ?.filter { it.name.toIntOrNull() != null }
            ?.mapNotNull { dir ->
                runCatching {
                    val pid = dir.name.toInt()
                    val argv = File(dir, "cmdline").readBytes()
                        .toString(Charsets.ISO_8859_1)
                        .split('\u0000')
                    if (pid != mine && argv.firstOrNull() == bin) pid else null
                }.getOrNull()
            }
            ?: emptyList()
    }

    private fun signalPids(pids: List<Int>, signal: Int) {
        for (pid in pids) {
            try {
                Os.kill(pid, signal)
            } catch (_: ErrnoException) {
                // Already gone — the race between scan and signal is fine.
            }
        }
    }

    /**
     * Single loopback probe on the health endpoint — the ownership check
     * [start] uses before spawning. 200..299 on the plugin's port means
     * some process is already serving it.
     */
    private suspend fun probeHealth(d: BuiltinPluginCatalog.Descriptor): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(BuiltinPluginCatalog.healthUrl(d)).openConnection() as HttpURLConnection
                connection.connectTimeout = 1_000
                connection.readTimeout = 1_000
                connection.requestMethod = "GET"
                val code = connection.responseCode
                connection.disconnect()
                code in 200..299
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Loopback health poll with the same cadence [GatewayServiceManager]
     * uses: the gateway needs a moment to bind after the process spawns.
     */
    private suspend fun waitForHealth(d: BuiltinPluginCatalog.Descriptor): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 20_000L
            while (System.currentTimeMillis() < deadline) {
                if (probeHealth(d)) return@withContext true
                delay(500)
            }
            false
        }
}
