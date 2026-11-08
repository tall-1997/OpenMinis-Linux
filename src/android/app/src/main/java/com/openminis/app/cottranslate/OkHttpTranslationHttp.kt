package com.openminis.app.cottranslate

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [T-cot-translate] Production [TranslationHttp] over a plain OkHttp client.
 * The anonymous translate endpoints are public web services; no app credential
 * or provider routing is involved, so a lightweight per-process client (with
 * sane timeouts) is used instead of the provider pipeline. Proxy support rides
 * the system default ProxySelector — a user-level VPN/proxy applies naturally.
 */
object OkHttpTranslationHttp : TranslationHttp {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun post(url: String, body: String, contentType: String, timeoutMs: Int): String? {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(contentType.toMediaTypeOrNull()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }
}
