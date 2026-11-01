package com.openminis.app.a2a

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.openminis.app.logging.AppLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs the A2A gateway as a **host** app process: start ([ProcessBuilder] on
 * the APK's own binary), stop (graceful TERM -> KILL), and the loopback health
 * poll [A2aGateway] and the lane layer rely on.
 *
 * Moved from `plugins/builtin/NativeGatewayService.kt` when a2a left the
 * builtin plugin catalog for this package — the gateway is single now, so the
 * per-plugin status map and the descriptor parameter are gone, and the
 * suspend-and-callback plumbing collapsed into plain blocking calls (the
 * calling discipline lives with the caller — the UI launches on Dispatchers.IO
 * exactly like the old market card's toggle did). Every behavior is unchanged:
 *
 *  - **Binary split.** The gateway server itself is bionic (the APK's
 *    own liba2a.so, linked against the platform's libc, no PRoot); the
 *    provider plugin packs it spawns are upstream-built glibc binaries and
 *    run under the app-bundled glibc runtime via [A2aPluginShell]'s
 *    wrapper scripts — the gateway never sees the difference, the wrapper
 *    owns the loader exec.
 *  - **No pidfile.** [Process] owns the handle for the child we spawned,
 *    but the real ownership invariant is the port: if it answers, a
 *    gateway is already running and start is a no-op success. This also
 *    adopts a gateway left behind by a crashed app process.
 *  - **Stop reaches orphans.** A crash can orphan the gateway (the child
 *    outlives the dead app process), and an adopted gateway has no
 *    [Process] handle at all. So stop TERMs every same-uid process whose
 *    cmdline argv[0] is the gateway's binary — a /proc scan, not a
 *    pidfile — plus every plugin host (a process whose argv[0] is the
 *    bundled glibc loader, the [A2aPluginShell] wrapper's `exec` shape:
 *    `exec "$loader" …` replaces the shell so argv[0] IS the loader),
 *    waits out the grace window for the port to
 *    stop answering, then KILLs survivors. TERM-then-KILL either way: the
 *    upstream server traps SIGTERM for a clean shutdown; KILL only fires
 *    after the grace period so a wedged process cannot squat the port.
 */
object A2aGatewayProcess {
    private const val TAG = "A2aGatewayProcess"

    private const val TERM_GRACE_MS = 5_000L

    /** Health poll after spawn: the gateway needs a moment to bind. */
    private const val HEALTH_WAIT_MS = 20_000L

    /**
     * Name of the bionic binary in the APK's nativeLibraryDir. Android
     * package extraction only unpacks `lib*.so`, hence the extension.
     */
    internal const val BINARY = "liba2a.so"

    private var process: Process? = null

    private val lock = Any()

    /**
     * Home dir for the gateway's runtime state: `<filesDir>/builtin-plugins/a2a/`
     * carrying `ui/` (deployed panel bundle) and `data/` (gateway state:
     * SQLite, logs, generated keys). The path is kept from the builtin-plugin
     * era on purpose — an upgrade in place must keep the deployed panel and
     * the gateway state (the accounts a user added live in that SQLite db)
     * intact; only the owning code moved.
     */
    internal fun homeDir(filesDir: String): File =
        File(File(filesDir, "builtin-plugins"), A2aGateway.ID)

    internal fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY)

    /**
     * The command line the gateway process is launched with. Pure — the
     * test pins the exact argv shape: binary from nativeLibraryDir, env
     * from [launchEnv], no shell, no wrapper. The binary parses no CLI args
     * (even `--help` boots the server), so argv is exactly one element.
     */
    internal fun launchArgv(context: Context): List<String> = listOf(binary(context).path)

    /**
     * Host-process env for the gateway: the upstream binary's own
     * `AGENT2API_*` runtime contract, pointed at the private home.
     * `AGENT2API_PANEL_PORT` puts the web panel on its own loopback port
     * (headless 分端口形态 — the panel router alone, the gateway router
     * stays on [A2aGateway.PORT]). `ALLOW_NO_KEY` mirrors the guest
     * launcher's stance — loopback only, the app itself is the only
     * client. HOME/TMPDIR give the bionic process writable scratch inside
     * the private dir (bionic has no /tmp, and a null HOME breaks SQLite
     * temp files). TZ + TZDIR make the gateway AND the glibc plugin hosts
     * it spawns (which inherit this env) report the device time zone —
     * glibc reads zoneinfo from the deployed `<home>/glibc/zoneinfo`, and
     * without TZDIR a glibc process has no zoneinfo tree at all. Pure —
     * the test pins every key.
     */
    internal fun launchEnv(filesDir: String, timeZoneId: String): Map<String, String> {
        val home = homeDir(filesDir)
        return mapOf(
            "AGENT2API_HOST" to "127.0.0.1",
            "AGENT2API_PROXY_PORT" to A2aGateway.PORT.toString(),
            // The panel router answers on its own loopback port; the app's
            // WebView reads it and the gateway router stays on PORT.
            "AGENT2API_PANEL_PORT" to A2aGateway.PANEL_PORT.toString(),
            "AGENT2API_PROXY_HOME" to "${home.path}/data",
            "AGENT2API_UI_DIR" to "${home.path}/ui",
            "AGENT2API_ALLOW_NO_KEY" to "1",
            // [T-android-a2a-native] The APK's binary cannot replace itself and
            // the panel's update bridge is a Tauri desktop seam — kill the
            // self-update poll (its "downloading" state has no bridge and
            // would hang). The app ships the engine; updates ride app releases.
            "AGENT2API_SELF_UPDATE" to "0",
            // The panel's captcha is the local ALTCHA slider (beta.6 panel
            // ships it in the bundle — no CDN SDK), keep it on: loopback
            // panel logins go through the same gate as desktop.
            "AGENT2API_CAPTCHA_ENABLED" to "1",
            "HOME" to "${home.path}/data",
            "TMPDIR" to "${home.path}/data/tmp",
            // Time zone for the gateway and every plugin host it spawns.
            "TZ" to timeZoneId,
            "TZDIR" to "${home.path}/glibc/zoneinfo",
        )
    }

    /**
     * True when the gateway answers on its loopback health endpoint — the
     * port-ownership check [start] uses before spawning, and the live probe
     * behind [A2aGateway.isRunning] and [A2aGateway.panelUrl]. Blocking
     * (connect/read timeouts 1s); a dead port refuses instantly, so the
     * common call costs nothing.
     */
    fun isRunning(): Boolean = probeHealthNow()

    private fun probeHealthNow(): Boolean {
        return try {
            val connection = URL(healthUrl()).openConnection() as HttpURLConnection
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

    /** Loopback health URL — the gateway binds 127.0.0.1 only. Pure. */
    internal fun healthUrl(): String =
        "http://127.0.0.1:${A2aGateway.PORT}/health"

    /**
     * Start the gateway as a host process and wait for its health endpoint
     * to answer. Returns null on success, the failure reason otherwise.
     * Idempotent: when the port already answers, a previous process (ours
     * or one orphaned by a crash) is already serving — adopt it and report
     * success without spawning a duplicate. Blocking (health wait up to
     * [HEALTH_WAIT_MS]) — call off the main thread.
     */
    internal fun start(context: Context): String? =
        synchronized(lock) {
            try {
                if (probeHealthNow()) {
                    return null
                }
                val home = homeDir(context.filesDir.path)
                val log = File(home, "data/gateway.log")
                log.parentFile?.mkdirs()
                // Fresh DNS + wrapped plugin entries BEFORE the spawn: the
                // glibc plugin hosts read resolv.conf at first lookup and
                // the gateway spawns them during boot — a pack installed
                // by the panel since the last start must be wrapped now.
                A2aDns.refresh(context)
                A2aDns.ensureStatic(context)
                A2aPluginShell.ensure(home)
                val pb = ProcessBuilder(launchArgv(context)).apply {
                    redirectErrorStream(true)
                    redirectOutput(log)
                    environment().putAll(
                        launchEnv(
                            context.filesDir.path,
                            java.util.TimeZone.getDefault().id,
                        ),
                    )
                }
                val proc = pb.start()
                process = proc
                if (!waitForHealth()) {
                    // Do not leave a crash-looping zombie squatting the slot.
                    proc.destroy()
                    process = null
                    return "health timeout"
                }
                null
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "start: ${t.message}")
                t.message ?: "start failed"
            }
        }

    /**
     * Stop the gateway: TERM every process running the gateway's binary —
     * the tracked child plus any same-uid orphan a crash left behind —
     * wait out the grace window for the port to stop answering, then KILL
     * the survivors. Idempotent — a dead gateway is a success. Returns null
     * on success, the failure reason otherwise (the path never fails today;
     * the nullable keeps the shape symmetric with [start]). Blocking —
     * call off the main thread.
     */
    internal fun stop(context: Context): String? =
        synchronized(lock) {
            try {
                val proc = process
                if (proc != null) {
                    proc.destroy()
                    process = null
                }
                signalPids(orphanPids(context), OsConstants.SIGTERM)
                val deadline = System.currentTimeMillis() + TERM_GRACE_MS
                while (probeHealthNow() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200)
                }
                if (probeHealthNow()) {
                    signalPids(orphanPids(context), OsConstants.SIGKILL)
                    if (proc?.isAlive == true) proc.destroyForcibly()
                }
                null
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "stop: ${t.message}")
                t.message ?: "stop failed"
            }
        }

    /**
     * PIDs of processes (this uid only — /proc hides the rest) whose
     * cmdline argv[0] is exactly the gateway's binary path, plus the glibc
     * plugin hosts: a process whose argv[0] is the bundled glibc loader
     * (the [A2aPluginShell] wrapper's `exec` shape — `exec "$loader" …`
     * replaces the shell, so the loader lands at argv[0], never argv[1]).
     * This is the orphan
     * detector: the tracked child matches too, which is harmless — it gets
     * the same TERM twice. Pure scan, no signals sent.
     */
    internal fun orphanPids(context: Context): List<Int> {
        val bin = launchArgv(context).first()
        val loader = File(homeDir(context.filesDir.path), "glibc/${A2aPluginShell.LOADER}").path
        val mine = android.os.Process.myPid()
        return File("/proc").listFiles()
            ?.filter { it.name.toIntOrNull() != null }
            ?.mapNotNull { dir ->
                runCatching {
                    val pid = dir.name.toInt()
                    val argv = File(dir, "cmdline").readBytes()
                        .toString(Charsets.ISO_8859_1)
                        .split('\u0000')
                    if (pid != mine && matchesOwnedProcess(argv, bin, loader)) pid else null
                }.getOrNull()
            }
            ?: emptyList()
    }

    /**
     * True when [argv] belongs to a process the stop sweep should reach:
     * the gateway binary at argv[0], or a plugin host — the wrapper's
     * `exec "$loader" …` shape puts the bundled glibc loader at argv[0]
     * (the exec replaces the shell), so argv[1] — the pack-versioned
     * plugin binary path — is never the match key. Pure.
     */
    internal fun matchesOwnedProcess(argv: List<String>, gatewayBinary: String, loader: String): Boolean =
        argv.firstOrNull() == gatewayBinary || argv.firstOrNull() == loader

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
     * Loopback health poll: the gateway needs a moment to bind after the
     * process spawns.
     */
    private fun waitForHealth(): Boolean {
        val deadline = System.currentTimeMillis() + HEALTH_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (probeHealthNow()) return true
            Thread.sleep(500)
        }
        return false
    }
}
