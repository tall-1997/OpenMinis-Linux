package com.openminis.app.vscreen.tier

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * [T-android-vscreen3] Client for the root-tier host's loopback JPEG server
 * (vdhost.Main). One short-lived connection per screenshot: send the single
 * command [VdHostProtocol.SHOT_COMMAND], read the header line byte-by-byte
 * (no buffered reader — nothing may steal bytes from the binary body), then
 * read exactly the announced number of JPEG bytes.
 *
 * Any failure returns null so the caller falls through to the shell
 * screenshot lane: the socket lane is an optimization, the heartbeat in the
 * state file is the liveness oracle.
 */
internal object HostDisplayClient {

    fun shot(port: Int, timeoutMs: Int = 10_000): ByteArray? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            socket.soTimeout = timeoutMs
            val output: OutputStream = socket.getOutputStream()
            output.write(VdHostProtocol.SHOT_COMMAND.toByteArray(Charsets.US_ASCII))
            output.flush()
            val input: InputStream = socket.getInputStream()

            // Header line, byte-by-byte: buffered readers prefetch and would
            // eat the first bytes of the JPEG body.
            val header = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) header.append(b.toChar())
            }
            val len = VdHostProtocol.parseShotHeader(header.toString())
                ?: return@runCatching null

            val jpeg = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(jpeg, off, len - off)
                if (n < 0) return@runCatching null
                off += n
            }
            jpeg
        }
    }.getOrNull()
}
