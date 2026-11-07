package com.openminis.app.vscreen

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.HostSuFallback
import com.openminis.app.offload.ShizukuManager
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * [T-android-vscreen-host] App-side client for [DisplayHostMain].
 *
 * The host owns the display (root or shell uid); this side only speaks the
 * loopback protocol: capture, launch-on-display and teardown. All calls are
 * serialized on one socket, so a capture and a launch can never interleave
 * their replies.
 */
internal class HostDisplayClient private constructor(
    override val displayId: Int,
    private val socket: Socket,
    private val process: Process?,
) : VirtualScreenManager.DisplayHandle {

    private val input = DataInputStream(socket.getInputStream())
    private val out = socket.getOutputStream()

    override val captureJpeg: ByteArray? get() = shot()

    @Synchronized
    private fun shot(): ByteArray? = runCatching {
        out.write("SHOT\n".toByteArray())
        out.flush()
        val header = readLine() ?: return null
        if (!header.startsWith("JPEG ")) return null
        val length = header.removePrefix("JPEG ").trim().toInt()
        if (length <= 0) return null
        ByteArray(length).also { input.readFully(it) }
    }.getOrNull()

    @Synchronized
    fun launch(component: String): Boolean = runCatching {
        out.write("LAUNCH $component\n".toByteArray())
        out.flush()
        readLine() == "OK"
    }.getOrDefault(false)

    /** False once the host socket or its process is gone ([T-android-vscreen-host]). */
    val isAlive: Boolean
        get() = !socket.isClosed && process?.isAlive != false

    override val hostAlive: Boolean get() = isAlive

    override fun acquireLatestFrame(): android.media.Image? = null

    override fun release() {
        runCatching {
            out.write("STOP\n".toByteArray())
            out.flush()
        }
        runCatching { socket.close() }
        runCatching { process?.destroy() }
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }

    companion object {
        private const val TAG = "VdHostClient"

        /**
         * Start the host for [backend] (ROOT via `su`, SHIZUKU via
         * `Shizuku.newProcess`), wait for its loopback port, and return the
         * client. Null when this layer cannot start — the caller falls through
         * to the next one (see [VdBackends]).
         */
        fun start(context: Context, backend: VdBackend, spec: DisplaySpec): HostDisplayClient? {
            if (backend == VdBackend.NATIVE) return null
            val apk = context.packageCodePath ?: return null
            val port = freePort() ?: return null
            val command = buildString {
                append("CLASSPATH=").append(apk)
                append(" /system/bin/app_process /system/bin ")
                append(DisplayHostMain::class.java.name)
                append(" --port ").append(port)
                append(" --w ").append(spec.width)
                append(" --h ").append(spec.height)
                append(" --dpi ").append(spec.dpi)
            }
            val process = when (backend) {
                VdBackend.ROOT -> rootProcess(command)
                else -> if (shizukuDetached(command)) null else return null
            }
            if (backend == VdBackend.ROOT && process == null) return null

            // Drain stdout so a root host can never block on a full pipe.
            val owned = process
            if (owned != null) {
                Thread({
                    runCatching { owned.inputStream.use { it.readBytes() } }
                }, "vd-host-drain").apply { isDaemon = true }.start()
            }

            val socket = connect(port)
            if (socket == null) {
                runCatching { owned?.destroy() }
                return null
            }
            val id = runCatching {
                val line = socket.getInputStream().bufferedReader().readLine() ?: return null
                line.removePrefix("READY ").trim().toIntOrNull()
            }.getOrNull()
            if (id == null) {
                runCatching { socket.close() }
                runCatching { owned?.destroy() }
                return null
            }
            AppLogger.info(TAG, "host ready: backend=$backend display=$id port=$port")
            return HostDisplayClient(id, socket, process)
        }

        private fun rootProcess(command: String): Process? {
            val su = HostSuFallback.suBinary() ?: return null
            return runCatching {
                ProcessBuilder(su, "-c", command)
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull()
        }

        /**
         * Shizuku layer: `newProcess` is not public in the Shizuku client API,
         * so the host is started DETACHED (`nohup … &`) through the shell lane
         * and the call returns as soon as the shell forks. The host outlives
         * the command and talks over the loopback socket.
         */
        private fun shizukuDetached(command: String): Boolean {
            if (!ShizukuManager.isReady()) return false
            val detached = "nohup $command </dev/null >/dev/null 2>&1 &"
            val result = runCatching {
                ShizukuManager.runProcess(arrayOf("sh", "-c", detached), null, null, 5_000L)
            }.getOrNull()
            return result != null && result.exitCode == 0
        }

        private fun freePort(): Int? = runCatching {
            ServerSocket(0).use { it.localPort }
        }.getOrNull()

        /** Poll the loopback port until the host is listening (bounded). */
        private fun connect(port: Int): Socket? {
            val deadline = System.currentTimeMillis() + 12_000L
            while (System.currentTimeMillis() < deadline) {
                val socket = runCatching {
                    Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 500) }
                }.getOrNull()
                if (socket != null) return socket
                Thread.sleep(150)
            }
            return null
        }
    }
}
