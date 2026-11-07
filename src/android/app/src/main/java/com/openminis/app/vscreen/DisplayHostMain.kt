package com.openminis.app.vscreen

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * [T-android-vscreen-host] The privileged display host.
 *
 * Runs OUTSIDE the app process 鈥?launched as `app_process` by SU/KSU (root
 * uid) or Shizuku (shell uid 2000) 鈥?and therefore OWNS the [VirtualDisplay]:
 * a display dies with the process that created it, so a one-shot shell call
 * can never keep one alive. This mirrors the two reference projects:
 *  - `MerlinShieh/vdisplay-android` (`vd.jar`): `ActivityThread.systemMain()`
 *    gives the host a system Context, the display is created with
 *    PUBLIC | OWN_CONTENT_ONLY so shell-side `am start --display`,
 *    `input -d` and `screencap -d` are all allowed;
 *  - `AcidGr/agent-mobile-use` (`DaemonMain`): a long-lived privileged owner
 *    that keeps the display and its frames alive across tool calls.
 *
 * Wire protocol (loopback only, line oriented, one client):
 *   server 鈫?`READY <displayId>\n`                     on connect
 *   client 鈫?`PING\n`      server 鈫?`PONG <displayId>\n`
 *   client 鈫?`SHOT\n`      server 鈫?`JPEG <len>\n` + <len bytes>
 *   client 鈫?`LAUNCH <pkg>/<activity>\n` 鈫?`OK\n` / `ERR <msg>\n`
 *   client 鈫?`STOP\n`      server 鈫?`BYE\n`, then exit
 *
 * Args: `--port <p> --w <w> --h <h> --dpi <d>`. Nothing else is required:
 * the host is self-contained, so no jar has to be pushed and the user does
 * not configure anything.
 */
object DisplayHostMain {
    private const val TAG = "VdHost"

    @JvmStatic
    fun main(args: Array<String>) {
        val port = arg(args, "--port") ?: return say("ERR missing --port")
        val width = arg(args, "--w") ?: 1080
        val height = arg(args, "--h") ?: 1920
        val dpi = arg(args, "--dpi") ?: 320

        Looper.prepareMainLooper()
        val context = systemContext() ?: return say("ERR no system context")
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return say("ERR no DisplayManager")

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        // Drain frames so the producer never starves; SHOT acquires its own.
        val thread = HandlerThread("vd-frames").apply { start() }
        reader.setOnImageAvailableListener({ r ->
            runCatching { r.acquireLatestImage()?.close() }
        }, Handler(thread.looper))

        // PUBLIC: shell-side `am start --display` is allowed for a display the
        // shell/root host owns. OWN_CONTENT_ONLY: frames stay private to us.
        val vd = runCatching {
            dm.createVirtualDisplay(
                "minis-vd-host", width, height, dpi, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                null, null,
            )
        }.getOrNull() ?: return say("ERR createVirtualDisplay refused")

        val displayId = vd.display.displayId
        say("READY $displayId $port")

        runCatching { serve(port, displayId, reader, width, height, vd, context) }
        runCatching { vd.release() }
        runCatching { reader.close() }
        say("BYE")
    }

    private fun serve(
        port: Int,
        displayId: Int,
        reader: ImageReader,
        width: Int,
        height: Int,
        vd: VirtualDisplay,
        context: Context,
    ) {
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 0
            while (true) {
                val socket = server.accept()
                socket.use { handleClient(it, displayId, reader, width, height, vd, context) }
            }
        }
    }

    private fun handleClient(
        socket: Socket,
        displayId: Int,
        reader: ImageReader,
        width: Int,
        height: Int,
        vd: VirtualDisplay,
        context: Context,
    ) {
        val input = DataInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        out.write("READY $displayId\n".toByteArray())
        out.flush()
        while (true) {
            val line = readLine(input) ?: return
            when {
                line == "PING" -> {
                    out.write("PONG $displayId\n".toByteArray())
                    out.flush()
                }
                line == "SHOT" -> {
                    val jpeg = captureJpeg(reader, width, height)
                    if (jpeg == null) {
                        out.write("ERR no frame\n".toByteArray())
                    } else {
                        out.write("JPEG ${jpeg.size}\n".toByteArray())
                        out.write(jpeg)
                    }
                    out.flush()
                }
                line.startsWith("LAUNCH ") -> {
                    val target = line.removePrefix("LAUNCH ").trim()
                    val ok = launch(context, target, displayId)
                    out.write((if (ok) "OK\n" else "ERR launch\n").toByteArray())
                    out.flush()
                }
                line == "STOP" -> {
                    out.write("BYE\n".toByteArray())
                    out.flush()
                    return
                }
                else -> {
                    out.write("ERR unknown\n".toByteArray())
                    out.flush()
                }
            }
        }
    }

    /** Latest frame as JPEG, waiting briefly for the first one after a launch. */
    private fun captureJpeg(reader: ImageReader, width: Int, height: Int): ByteArray? {
        repeat(10) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image != null) {
                image.use { return it.toJpeg(width, height) }
            }
            Thread.sleep(80)
        }
        return null
    }

    private fun Image.toJpeg(width: Int, height: Int): ByteArray? = runCatching {
        val plane = planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val paddedWidth = width + rowPadding / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
        val bytes = java.io.ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
        if (cropped !== padded) cropped.recycle()
        padded.recycle()
        bytes.toByteArray()
    }.getOrNull()

    /** Start an app ON this display from the host (owner privileges). */
    private fun launch(context: Context, target: String, displayId: Int): Boolean = runCatching {
        val pkg = target.substringBefore('/')
        val cls = target.substringAfter('/', "")
        if (pkg.isEmpty() || cls.isEmpty()) return false
        val intent = Intent(Intent.ACTION_MAIN).setClassName(pkg, cls)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        context.startActivity(intent, options)
        true
    }.getOrDefault(false)

    /** The system context an `app_process` host runs with (reference approach). */
    private fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        val sys = at.getMethod("getSystemContext").invoke(thread) as? Context
        val shell = runCatching {
            sys?.createPackageContext(
                "com.android.shell",
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
        }.getOrNull()
        shell ?: sys
    }.getOrNull()

    private fun arg(args: Array<String>, name: String): Int? {
        val i = args.indexOf(name)
        return if (i >= 0) args.getOrNull(i + 1)?.toIntOrNull() else null
    }

    private fun readLine(input: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }

    private fun say(line: String) {
        println(line)
        System.out.flush()
    }
}
