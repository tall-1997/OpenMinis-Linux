package com.openminis.app.sandbox

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Loopback HTTP/CONNECT proxy for sandbox traffic logging and one-click cut.
 * Does not use VpnService. Host LLM OkHttp does not honor http_proxy env.
 */
object SandboxHttpProxy {

    const val PORT = 17890
    private const val TAG = "SandboxHttpProxy"

    // [T-proxy-tunnel-leak] Idle read timeout for BOTH ends of a tunnel. The
    // client socket already had a 20s soTimeout but the remote socket did not,
    // so on an idle HTTPS tunnel one pump thread hit the client timeout and
    // exited while the other blocked forever on a silent remote — the latch
    // never reached zero and leaked one handler thread + two pump threads + two
    // sockets per idle connection. Symmetric soTimeout + a bounded await fixes it.
    private const val IDLE_TIMEOUT_MS = 20_000
    private val running = AtomicBoolean(false)
    private val denyAll = AtomicBoolean(false)
    private val recent = ArrayDeque<JSONObject>()
    private val logHits = AtomicInteger(0)
    private val logLock = Any()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var logFile: File? = null

    fun isRunning(): Boolean = running.get()

    fun envBlock(): Map<String, String>? {
        if (!running.get()) return null
        val uri = "http://127.0.0.1:$PORT"
        return mapOf(
            "http_proxy" to uri,
            "https_proxy" to uri,
            "HTTP_PROXY" to uri,
            "HTTPS_PROXY" to uri,
            "no_proxy" to "localhost,127.0.0.1,::1",
            "NO_PROXY" to "localhost,127.0.0.1,::1",
        )
    }

    fun recentJson(limit: Int = 20): JSONArray {
        val arr = JSONArray()
        synchronized(recent) {
            recent.toList().takeLast(limit).forEach { arr.put(it) }
        }
        return arr
    }

    @Synchronized
    fun start(rootfsDir: File?, deny: Boolean) {
        denyAll.set(deny)
        logFile = rootfsDir?.let { File(File(it, "run").also { d -> d.mkdirs() }, "minis-netlog.jsonl") }
        if (running.get()) return
        val ss = try {
            ServerSocket(PORT, 32, InetAddress.getByName("127.0.0.1"))
        } catch (t: Throwable) {
            Log.w(TAG, "proxy bind failed: ${t.message}")
            return
        }
        server = ss
        running.set(true)
        thread(name = "minis-http-proxy", isDaemon = true) {
            try {
                Log.i(TAG, "listening 127.0.0.1:$PORT deny=$deny")
                while (running.get()) {
                    val client = try {
                        ss.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    thread(name = "minis-proxy-conn", isDaemon = true) { handle(client) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "proxy failed: ${t.message}")
            } finally {
                if (server === ss) {
                    running.set(false)
                    runCatching { ss.close() }
                    server = null
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        denyAll.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = IDLE_TIMEOUT_MS
            val input = client.getInputStream()
            val header = readHeaders(input) ?: return
            val first = header.lineSequence().firstOrNull().orEmpty()
            val parts = first.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val target = parts.getOrNull(1).orEmpty()
            val hostPort = when {
                method.equals("CONNECT", true) -> target
                else -> {
                    val hostLine = header.lineSequence().firstOrNull { it.startsWith("Host:", true) }
                    hostLine?.substringAfter(':')?.trim()?.ifBlank { target } ?: target
                }
            }
            logHit(method, hostPort, denyAll.get())
            if (denyAll.get()) {
                client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            if (method.equals("CONNECT", true)) {
                // [T-proxy-ipv6-literal] The old `split(':')` read "[::1]:443"
                // as host "[" port "" and every IPv6 destination died with an
                // UnknownHostException before the tunnel ever opened.
                val parsed = parseHostPort(hostPort, defaultPort = 443)
                if (parsed == null) {
                    client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val (host, port) = parsed
                Socket(host, port).use { remote ->
                    // [T-proxy-tunnel-leak] Match the client's idle timeout so
                    // BOTH pump threads self-terminate on an idle tunnel and the
                    // latch reaches zero, instead of one blocking forever on a
                    // silent remote while the other already exited.
                    remote.soTimeout = IDLE_TIMEOUT_MS
                    client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    tunnel(client, remote)
                }
            } else {
                val parsed = parseHostPort(hostPort, defaultPort = 80)
                if (parsed == null) {
                    client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val (host, port) = parsed
                Socket(host, port).use { remote ->
                    remote.soTimeout = IDLE_TIMEOUT_MS
                    // [T-proxy-header-bytes] readHeaders decoded the request
                    // one byte at a time via `b.toChar()` (chars 0..255), so
                    // re-encoding with the default charset (UTF-8) turned every
                    // >=0x80 byte into two — a header value like a UTF-8
                    // filename arrived corrupted at the origin server.
                    // ISO-8859-1 maps chars 0..255 back to the exact bytes.
                    remote.getOutputStream().write(header.toByteArray(Charsets.ISO_8859_1))
                    tunnel(client, remote)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "conn: ${t.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readHeaders(input: java.io.InputStream): String? {
        val buf = StringBuilder()
        var prev = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.isEmpty()) null else buf.toString()
            buf.append(b.toChar())
            if (prev == '\r'.code && b == '\n'.code && buf.endsWith("\r\n\r\n")) return buf.toString()
            prev = b
            if (buf.length > 16_384) return buf.toString()
        }
    }

    /**
     * [T-proxy-ipv6-literal] Split an authority into (host, port). Accepts the
     * forms the two call sites hand over: CONNECT's authority target
     * ("example.com:443", "[::1]:443"), a `Host:` header value ("example.com",
     * "[2001:db8::1]:8080"), and — when no Host header exists — the raw
     * absolute-form request target ("http://[::1]:8080/x"), which is stripped
     * of scheme and path first.
     *
     * An IPv6 literal MUST be bracketed per RFC 3986 when it carries a port;
     * the brackets are stripped here because java.net.Socket resolves the bare
     * literal itself. A bare multi-colon authority (unbracketed IPv6, seen in
     * the wild) is treated as host-only with the default port instead of
     * splitting at every colon. Returns null when nothing sensible can be
     * parsed — the caller replies 400 instead of connecting to "[".
     */
    internal fun parseHostPort(raw: String, defaultPort: Int): Pair<String, Int>? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        val scheme = s.indexOf("://")
        if (scheme >= 0) {
            s = s.substring(scheme + 3)
            val slash = s.indexOf('/')
            if (slash >= 0) s = s.substring(0, slash)
        }
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null
            val host = s.substring(1, close)
            if (host.isEmpty()) return null
            val port = if (s.length > close + 1 && s[close + 1] == ':') {
                s.substring(close + 2).toIntOrNull() ?: return null
            } else {
                defaultPort
            }
            if (port !in 1..65535) return null
            return host to port
        }
        val first = s.indexOf(':')
        if (first < 0) return s to defaultPort
        if (s.indexOf(':', first + 1) >= 0) {
            // Bare unbracketed IPv6 literal — the whole authority is the host.
            return s to defaultPort
        }
        val host = s.substring(0, first)
        val port = s.substring(first + 1).toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }

    private fun tunnel(a: Socket, b: Socket) {
        val done = CountDownLatch(2)
        thread(isDaemon = true) {
            try {
                a.getInputStream().copyTo(b.getOutputStream())
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }
        thread(isDaemon = true) {
            try {
                b.getInputStream().copyTo(a.getOutputStream())
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }
        // [T-proxy-tunnel-leak] The pumps now have an idle soTimeout on BOTH
        // sockets, so a stalled tunnel trips them and the latch falls to zero.
        // This await is still bounded as a backstop: on expiry close BOTH
        // sockets so any still-blocked copyTo throws and its thread unwinds,
        // instead of an untimed await() leaking a handler thread + two pump
        // threads + two sockets per idle connection forever.
        val finished = done.await((IDLE_TIMEOUT_MS + 5_000L).toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { a.close() }
            runCatching { b.close() }
            done.await(2_000L, TimeUnit.MILLISECONDS)
        }
    }

    private fun logHit(method: String, host: String, denied: Boolean) {
        val obj = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("method", method)
            .put("host", host)
            .put("denied", denied)
        synchronized(recent) {
            if (recent.size >= 200) recent.removeFirst()
            recent.addLast(obj)
        }
        val f = logFile ?: return
        try {
            synchronized(logLock) {
                if (logHits.incrementAndGet() % 256 == 0 && f.length() > 5L * 1024 * 1024) {
                    val bak = File(f.parentFile, "minis-netlog.jsonl.1")
                    bak.delete()
                    if (!f.renameTo(bak)) {
                        f.writeText("")
                    }
                }
                f.appendText(obj.toString() + "\n")
            }
        } catch (_: Throwable) {
        }
    }
}
