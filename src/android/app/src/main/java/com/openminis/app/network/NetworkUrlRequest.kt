// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.network

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [FEAT-261021-16] Settings > Network for call sites written on
 * HttpURLConnection (skill downloads, browser downloads, image viewer,
 * Gemini / Antigravity account calls, the shell's reachability probe).
 *
 * HttpURLConnection cannot carry the local entry's per-tag credentials:
 * Java answers an HTTPS proxy's 407 only through the process-wide
 * java.net.Authenticator, and a Proxy-Authorization header set by the caller
 * would also be sent to the origin server. So:
 *  - no route for [open]'s tag (the unconfigured app): the request is the
 *    same HttpURLConnection the call site used to make - same calls, same
 *    defaults (system proxy selector, no timeout unless the caller set one);
 *  - a route: the request goes through OkHttp via [NetworkHttp.client], with
 *    the same method, headers, body and timeouts, HttpURLConnection's
 *    User-Agent unless the caller set one, and the same error contract
 *    ([Response.body] throws for an HTTP error status).
 */
object NetworkUrlRequest {

    class Response internal constructor(
        val code: Int,
        /** Content-Length, or -1 when unknown. */
        val contentLength: Long,
        private val input: () -> InputStream,
        private val onClose: () -> Unit,
    ) : Closeable {
        /** The body, as HttpURLConnection.getInputStream gives it: an IOException for status >= 400. */
        fun body(): InputStream = input()

        fun text(): String = body().bufferedReader().readText()

        override fun close() = onClose()
    }

    /**
     * HttpURLConnection's own defaults: no connect / read timeout, and no
     * redirect between https and http.
     */
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followSslRedirects(false)
            .connectTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /** The routed OkHttp client for [tag], or null when HttpURLConnection is used unchanged. */
    internal fun routedClient(tag: String): OkHttpClient? {
        val ep = NetworkRuntime.endpoint(tag) ?: return null
        if (ep.httpPort == null) return null
        return NetworkHttp.client(tag, baseClient)
    }

    fun open(
        tag: String,
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        connectTimeoutMs: Int? = null,
        readTimeoutMs: Int? = null,
    ): Response {
        val routed = routedClient(tag) ?: return openDirect(url, method, headers, body, connectTimeoutMs, readTimeoutMs)
        val client = if (connectTimeoutMs == null && readTimeoutMs == null) routed else routed.newBuilder().apply {
            connectTimeoutMs?.let { connectTimeout(it.toLong(), TimeUnit.MILLISECONDS) }
            readTimeoutMs?.let { readTimeout(it.toLong(), TimeUnit.MILLISECONDS) }
        }.build()
        val builder = Request.Builder().url(url)
        for ((k, v) in headers) builder.header(k, v)
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            System.getProperty("http.agent")?.takeIf { it.isNotEmpty() }?.let { builder.header("User-Agent", it) }
        }
        val type = (headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: "application/x-www-form-urlencoded").toMediaTypeOrNull()
        val requestBody = body?.toRequestBody(type) ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(type) else null
        builder.method(method, requestBody)
        val resp = client.newCall(builder.build()).execute()
        val code = resp.code
        return Response(
            code,
            resp.body?.contentLength() ?: -1L,
            input = {
                if (code >= 400) throw IOException("HTTP $code")
                resp.body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
            },
            onClose = { resp.close() },
        )
    }

    /** Exactly what the call sites did before the Network module. */
    private fun openDirect(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        connectTimeoutMs: Int?,
        readTimeoutMs: Int?,
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (method != "GET") conn.requestMethod = method
        connectTimeoutMs?.let { conn.connectTimeout = it }
        readTimeoutMs?.let { conn.readTimeout = it }
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.write(body)
        }
        val code = conn.responseCode
        return Response(code, conn.contentLengthLong, input = { conn.inputStream }, onClose = { conn.disconnect() })
    }
}
