package com.openminis.app.network

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * [GH#258][GH#287] App-level trust store for user-supplied CAs.
 *
 * WHY THIS EXISTS. `network_security_config.xml` now trusts `src="user"`, which
 * covers a CA installed through Android's Settings → Security → Install a
 * certificate. That route is not always available or practical: Android 14+
 * makes user-certificate installation harder to reach, and a provider endpoint
 * may need a CA the user only has as a `.pem` file. This object accepts that
 * PEM and keeps it inside the app sandbox.
 *
 * HOW IT DIFFERS FROM THE SYSTEM STORE. The loaded certificates are ADDED to
 * the platform trust managers, never used to replace them: normal public HTTPS
 * keeps validating against the system roots. Hostname verification is still
 * OkHttp's default; trusting a CA does not disable it.
 *
 * WIRING. [init] runs from `MinisApp`; [applyAppTrustStore] is applied to every
 * provider-facing OkHttp builder (OpenAI / Anthropic / Gemini / voice / quota /
 * shared catalog client). Clients built before an import keep their old socket
 * factory — provider instances are constructed per request, so the next turn
 * picks the new trust material up; the UI surfaces that a retry is needed.
 *
 * This is the storage + runtime-injection half of the fix. The settings entry
 * point is `TrustedCaSection` (Settings → Network → Proxies → Trusted
 * certificates).
 */
object AppTrustStore {

    private const val TAG = "AppTrustStore"

    /** Directory under `filesDir` holding one PEM per imported CA. */
    const val DIR_NAME = "trusted-cas"

    /** A CA file bigger than this is not a plausible single PEM. */
    private const val MAX_PEM_BYTES = 512 * 1024

    data class ImportResult(val imported: Int, val duplicates: Int, val rejected: Int)

    private data class SslMaterial(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    @Volatile
    private var dirRef: File? = null

    @Volatile
    private var cached: SslMaterial? = null

    /** [GH#258] Bumped on every store change; lets callers rebuild clients. */
    @Volatile
    var generation: Int = 0
        private set

    /** Called once from `MinisApp.onCreate`; safe to call again (no data loss). */
    fun init(context: Context) {
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "cannot create ${dir.absolutePath}; user CA import disabled")
            return
        }
        dirRef = dir
        invalidate()
        Log.i(TAG, "init dir=${dir.absolutePath} certs=${list().size}")
    }

    /** The import directory, or null when [init] has not run. */
    fun directory(): File? = dirRef

    /** Imported certificate files, stable order. */
    fun list(): List<File> =
        dirRef?.listFiles { f -> f.isFile && f.length() > 0 }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun hasCertificates(): Boolean = list().isNotEmpty()

    /**
     * Import every X.509 certificate found in [pem] (a single certificate, a
     * concatenated bundle, or a PKCS#7 `.p7b`). Duplicates are detected by
     * SHA-256 fingerprint, so importing the same CA twice is a no-op.
     *
     * Never throws: malformed input is counted in [ImportResult.rejected].
     */
    fun importPem(pem: String): ImportResult {
        val dir = dirRef
        if (dir == null) {
            Log.w(TAG, "importPem called before init()")
            return ImportResult(imported = 0, duplicates = 0, rejected = 1)
        }
        val text = pem.trim()
        if (text.isEmpty() || text.length > MAX_PEM_BYTES || !text.contains("BEGIN CERTIFICATE")) {
            return ImportResult(imported = 0, duplicates = 0, rejected = 1)
        }
        val certs = try {
            CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                .filterIsInstance<X509Certificate>()
        } catch (e: Exception) {
            Log.w(TAG, "importPem: not X.509/PEM (${e.javaClass.simpleName}: ${e.message})")
            return ImportResult(imported = 0, duplicates = 0, rejected = 1)
        }
        if (certs.isEmpty()) return ImportResult(imported = 0, duplicates = 0, rejected = 1)

        var imported = 0
        var duplicates = 0
        var rejected = 0
        for (cert in certs) {
            try {
                val out = File(dir, "cert-${fingerprint(cert)}.pem")
                if (out.exists()) {
                    duplicates++
                    continue
                }
                out.writeText(pemOf(cert), Charsets.UTF_8)
                imported++
            } catch (t: Exception) {
                rejected++
                Log.w(TAG, "importPem: write failed (${t.javaClass.simpleName}: ${t.message})")
            }
        }
        if (imported > 0) invalidate()
        Log.i(TAG, "importPem: imported=$imported duplicates=$duplicates rejected=$rejected")
        return ImportResult(imported, duplicates, rejected)
    }

    /** Remove one imported file by name (path traversal rejected). */
    fun delete(fileName: String): Boolean {
        val dir = dirRef ?: return false
        if (fileName.isBlank() || fileName.contains('/') || fileName.contains('\\') ||
            fileName.contains("..")
        ) {
            return false
        }
        val file = File(dir, fileName)
        val deleted = file.isFile && file.delete()
        if (deleted) invalidate()
        return deleted
    }

    /** Remove every imported CA. Returns how many files were deleted. */
    fun clear(): Int {
        var deleted = 0
        for (file in list()) {
            if (file.delete()) deleted++
        }
        if (deleted > 0) invalidate()
        return deleted
    }

    /**
     * Add the user store to [builder] when at least one CA is imported.
     * No certificates → the builder is returned untouched.
     */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val material = material() ?: return builder
        return builder.sslSocketFactory(material.socketFactory, material.trustManager)
    }

    private fun material(): SslMaterial? {
        cached?.let { return it }
        if (dirRef == null) return null
        synchronized(this) {
            cached?.let { return it }
            val files = list()
            if (files.isEmpty()) return null
            val built = build(files) ?: return null
            cached = built
            return built
        }
    }

    private fun build(files: List<File>): SslMaterial? {
        val userStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        var added = 0
        for (file in files) {
            try {
                val cert = file.inputStream().use { stream ->
                    CertificateFactory.getInstance("X.509").generateCertificate(stream) as? X509Certificate
                } ?: continue
                userStore.setCertificateEntry(file.name, cert)
                added++
            } catch (e: Exception) {
                Log.w(TAG, "skip ${file.name}: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (added == 0) return null
        val userManager = trustManagerFor(userStore) ?: return null
        // Keep the platform managers first (normal public HTTPS must validate
        // exactly as before), then add the app store. AndroidCAStore is the
        // public view of both the system roots and the device's user-installed
        // CAs, so mixing it in keeps `src="user"` working even for a client
        // that has a custom socket factory because an app CA was imported.
        val delegates = ArrayList<X509TrustManager>(3)
        trustManagerFor(null)?.let { delegates.add(it) }
        androidCaTrustManager()?.let { delegates.add(it) }
        delegates.add(userManager)
        val combined = CompositeTrustManager(delegates)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(combined), SecureRandom())
        Log.i(TAG, "loaded $added user CA(s)")
        return SslMaterial(sslContext.socketFactory, combined)
    }

    /** Trust manager over `AndroidCAStore`, or null when it is unavailable. */
    private fun androidCaTrustManager(): X509TrustManager? = try {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        if (store.size() > 0) trustManagerFor(store) else null
    } catch (t: Exception) {
        Log.w(TAG, "AndroidCAStore unavailable: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun trustManagerFor(keyStore: KeyStore?): X509TrustManager? {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }

    private fun invalidate() {
        cached = null
        generation++
    }

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .take(8)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun pemOf(cert: X509Certificate): String {
        val encoded = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        return buildString {
            appendLine("-----BEGIN CERTIFICATE-----")
            var i = 0
            while (i < encoded.length) {
                appendLine(encoded.substring(i, minOf(i + 64, encoded.length)))
                i += 64
            }
            appendLine("-----END CERTIFICATE-----")
        }
    }

    /**
     * Consults each delegate in order; system roots stay authoritative while
     * the app store adds anchors instead of replacing them.
     */
    private class CompositeTrustManager(
        private val delegates: List<X509TrustManager>,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var last: CertificateException? = null
            for (delegate in delegates) {
                try {
                    delegate.checkClientTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    last = e
                }
            }
            throw last ?: CertificateException("No trust manager accepted the client certificate")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var last: CertificateException? = null
            for (delegate in delegates) {
                try {
                    delegate.checkServerTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    last = e
                }
            }
            throw last ?: CertificateException("No trust manager accepted the server certificate")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
    }
}

/**
 * Chainable form of [AppTrustStore.applyTo] for OkHttp builders:
 * `OkHttpClient.Builder()…applyAppTrustStore().build()`.
 */
fun OkHttpClient.Builder.applyAppTrustStore(): OkHttpClient.Builder = AppTrustStore.applyTo(this)
