package com.openminis.app.vscreen.tier

/**
 * [T-android-vscreen-tier] Wire protocol of the root-tier app_process host
 * (vdhost.Main), mirrored on the Java side. Pure functions only — parsing,
 * command assembly, freshness checks — so the whole contract runs on the JVM
 * in tests.
 *
 * Every file under /data/local/tmp is read and written through the su lane
 * (the host runs as root; the app uid has no direct access), so the paths
 * appear inside assembled commands, never as direct File I/O here.
 */
object VdHostProtocol {

    const val STATE_PATH = "/data/local/tmp/minis_vd.state"
    const val STOP_PATH = "/data/local/tmp/minis_vd.stop"
    const val HOST_JAR_PATH = "/data/local/tmp/minis_vd_host.jar"
    const val LOG_PATH = "/data/local/tmp/minis_vd.log"

    /** The host heartbeats every 30s; this is the stale threshold (4 missed beats). */
    const val HEARTBEAT_MAX_AGE_SEC = 120L

    /** Host process alive + a fresh heartbeat. */
    data class HostState(
        val displayId: Int,
        val pid: Int,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val flags: Int,
        val beatEpochSec: Long,
    )

    /**
     * Parses the state file the host rewrites on every heartbeat. Lines are
     * "key=value"; anything missing or unparsable makes the WHOLE state null
     * — a half-written file must never look like a live host.
     */
    fun parseState(text: String): HostState? {
        var displayId = -1
        var pid = -1
        var w = -1
        var h = -1
        var dpi = -1
        var flags = -1
        var beat = -1L
        for (line in text.lineSequence()) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "display" -> displayId = value.toIntOrNull() ?: return null
                "pid" -> pid = value.toIntOrNull() ?: return null
                "size" -> {
                    val m = SIZE_REGEX.matchEntire(value) ?: return null
                    w = m.groupValues[1].toInt()
                    h = m.groupValues[2].toInt()
                    dpi = m.groupValues[3].toInt()
                }
                "flags" -> flags = value.toIntOrNull() ?: return null
                "beat" -> beat = value.toLongOrNull() ?: return null
            }
        }
        if (displayId <= 0 || pid <= 0 || w <= 0 || h <= 0 || dpi <= 0 || flags < 0 || beat < 0) return null
        return HostState(displayId, pid, w, h, dpi, flags, beat)
    }

    fun isHeartbeatFresh(state: HostState, nowEpochSec: Long): Boolean =
        nowEpochSec - state.beatEpochSec <= HEARTBEAT_MAX_AGE_SEC

    /**
     * The bare host command (the caller wraps it in `su -c`): setsid detaches
     * it from the su session so the launcher call returns immediately while
     * the host keeps running; stdout/stderr land in the device-side log.
     */
    fun startCommand(): String =
        "setsid app_process -Djava.class.path=$HOST_JAR_PATH /system/bin " +
            "--nice-name=minis-vd-host vdhost.Main --serve >> $LOG_PATH 2>&1 &"

    /**
     * Deploys a freshly built host jar from the app's cache dir: copy with
     * shell-safe paths, fix the mode bit (a plain overwrite can drop the
     * exec/read bits the app_process read needs), and print the SHA-256 for
     * the caller to compare against the locally computed one.
     */
    fun deployCommand(appCacheJarPath: String): String =
        "cp $appCacheJarPath $HOST_JAR_PATH && chmod 644 $HOST_JAR_PATH && sha256sum $HOST_JAR_PATH"

    /** Reads the state file (or fails with an empty output the caller reports). */
    fun readStateCommand(): String = "cat $STATE_PATH"

    /** Asks the host to exit (its main loop polls this file every second). */
    fun requestStopCommand(): String = "touch $STOP_PATH"

    /** Cleans up after a dead host: stale state, stale sentinel, dead pid. */
    fun cleanupCommand(pid: Int): String =
        "rm -f $STATE_PATH $STOP_PATH" + if (pid > 0) " && kill -9 $pid" else ""

    /** Removes the stop sentinel so a (re)started host does not exit at once. */
    fun clearStopCommand(): String = "rm -f $STOP_PATH"

    private val SIZE_REGEX = Regex("(\\d+)x(\\d+)@(\\d+)")
}
