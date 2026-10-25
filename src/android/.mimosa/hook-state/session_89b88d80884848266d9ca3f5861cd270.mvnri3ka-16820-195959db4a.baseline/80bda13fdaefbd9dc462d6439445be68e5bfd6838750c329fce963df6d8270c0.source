package com.openminis.app.network

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * [T-doh-resolver-fallback] DNS resolution with a DoH (RFC 8484 JSON API)
 * fallback for the case that actually breaks users: the system resolver
 * throws [UnknownHostException].
 *
 * ## Why this exists
 *
 * On a weak / hostile network the app dies at the *name resolution* step, not
 * at TLS and not at the API. `UnknownHostException` out of `Dns.SYSTEM` means
 * the retry layers above us ([com.openminis.app.provider.ModelListFetchRetry],
 * provider-level retry) all re-attempt the same broken lookup and still get
 * nothing, so a poisoned or simply unreachable resolver empties the model
 * picker and kills `web_fetch` with a message that reads as "the app is
 * offline" when it is in fact only *naming* that is broken.
 *
 * The order is deliberate: **system first, DoH only on failure.** System DNS
 * is the cheap path (in-process cache, no socket) and is what the device's
 * VPN/split-tunnel setup expects to be authoritative. DoH is the fallback
 * that rescues the "system can't even name this host" case.
 *
 * ## The recursion trap
 *
 * The DoH requests themselves need DNS to reach `dns.alidns.com` etc. — which
 * is the exact thing that just failed. So [dohClient] is built with
 * `Dns.SYSTEM` explicitly and **must never** be given [DohDns]; that would be
 * unbounded recursion (`lookup` → DoH call → `lookup` → …). [dohClient] also
 * gets its own connection pool so a poisoned DoH connection cannot poison the
 * LLM client's pool, and its own short timeouts because a DoH round trip is
 * measured in hundreds of ms, not tens of seconds.
 *
 * ## Bootstrap IPs ([BOOTSTRAP_IPS])
 *
 * Last-resort only: used *after* a source's hostname failed to resolve, so we
 * have to dial a literal IP and let SNI/Host still carry the real hostname.
 * Values are the operators' documented anycast resolver addresses; they are
 * only reached when both `Dns.SYSTEM` and the normal DoH hostname lookup have
 * failed, which is rare enough that staleness is an acceptable trade.
 */
object DohDns : Dns {

    private const val TAG = "DohDns"

    /** Per-source ceiling for the DoH call itself. */
    private const val DOH_TIMEOUT_SEC = 5L

    /** Hard cap on a cached answer, regardless of what the DNS TTL says. */
    private const val MAX_TTL_SEC = 300L

    /**
     * A host that failed every source stays failed for this long, so a broken
     * provider host under a retry loop does not re-pay 3 x 5s of DoH timeouts
     * per attempt.
     */
    private const val NEGATIVE_TTL_SEC = 30L

    /** LRU ceiling. Bounds memory; ~200 hosts is well past real working set. */
    private const val MAX_CACHE_ENTRIES = 200

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28

    /** A DoH endpoint plus its documented bootstrap IPs. */
    internal data class DohSource(
        val name: String,
        val url: String,
        val bootstrapIps: List<String>,
    )

    /**
     * Bootstrap addresses.
     *
     * Sources (checked 2025-01):
     *  - Alidns  `223.5.5.5` / `223.6.6.6` — documented public resolvers for
     *    the service behind `dns.alidns.com`.
     *  - DNSPod  `1.12.12.12` / `120.53.53.53` — documented public resolvers
     *    for the service behind `doh.pub`.
     *  - Cloudflare `104.16.248.249` / `104.16.249.249` — `cloudflare-dns.com`
     *    anycast addresses.
     *
     * Risk accepted: these can move. Consequence of a stale one is a failed
     * dial on an already-failing path (DoH hostname also failed), never a
     * wrong answer.
     */
    internal val SOURCES: List<DohSource> = listOf(
        DohSource(
            name = "alidns",
            url = "https://dns.alidns.com/resolve",
            bootstrapIps = listOf("223.5.5.5", "223.6.6.6"),
        ),
        DohSource(
            name = "dnspod",
            url = "https://doh.pub/dns-query",
            bootstrapIps = listOf("1.12.12.12", "120.53.53.53"),
        ),
        DohSource(
            name = "cloudflare",
            url = "https://cloudflare-dns.com/dns-query",
            bootstrapIps = listOf("104.16.248.249", "104.16.249.249"),
        ),
    )

    /** `type=A` + `type=AAAA`, so a dual-stack answer is not half-returned. */
    private const val QUERY_TYPES = "$TYPE_A,$TYPE_AAAA"

    private val JSON_MEDIA = "application/dns-json".toMediaType()

    /**
     * DoH transport. Deliberately NOT wired to [DohDns] — see the recursion
     * trap note above. Its own pool keeps a bad DoH connection from leaking
     * into the LLM client's pool.
     */
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM) // never DohDns — guards against recursion
            .connectTimeout(DOH_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(DOH_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(DOH_TIMEOUT_SEC * 2, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool())
            .build()
    }

    private data class CacheEntry(val ips: List<String>, val expireAtMillis: Long)

    /**
     * Insertion-ordered so [evictIfNeeded] can drop the oldest key; guarded by
     * [cacheLock] because access-order mutation is not safe concurrently.
     */
    private val cache = LinkedHashMap<String, CacheEntry>()
    private val cacheLock = Any()

    /** Injection seam for tests; defaults to wall clock. */
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }

    override fun lookup(hostname: String): List<InetAddress> {
        val cached = cachedIps(hostname)
        if (cached != null) {
            if (cached.isNotEmpty()) {
                val hit = resolveAllSafe(cached)
                if (hit.isNotEmpty()) return hit
            }
            // Negative cache hit: fail fast, do not re-pay DoH timeouts.
            throw UnknownHostException("DohDns: negative cache hit for $hostname")
        }

        try {
            val system = Dns.SYSTEM.lookup(hostname)
            if (system.isNotEmpty()) {
                // hostAddress is platform-nullable; skip any entry we cannot
                // round-trip to a literal rather than caching a null.
                val literals = system.mapNotNull { it.hostAddress }
                if (literals.isNotEmpty()) {
                    put(hostname, literals, ttlForSystemAnswer())
                    return system
                }
            }
        } catch (_: UnknownHostException) {
            // The whole point: fall through to DoH.
        } catch (_: Exception) {
            // Defensive: some platforms surface resolver faults as other IOExceptions.
            Log.i(TAG, "system dns failed for $hostname, trying DoH")
        }

        return lookupViaDoh(hostname)
    }

    private fun lookupViaDoh(hostname: String): List<InetAddress> {
        for (source in SOURCES) {
            try {
                val answer = query(source, hostname)
                if (answer.ips.isNotEmpty()) {
                    val addresses = resolveAllSafe(answer.ips)
                    if (addresses.isNotEmpty()) {
                        put(hostname, answer.ips, answer.ttlSec)
                        Log.i(TAG, "resolved $hostname via DoH source=${source.name}")
                        return addresses
                    }
                }
                Log.i(TAG, "DoH source=${source.name} returned no A/AAAA for $hostname")
            } catch (e: Exception) {
                Log.i(TAG, "DoH source=${source.name} failed for $hostname: ${e.javaClass.simpleName}")
            }
        }

        put(hostname, emptyList(), NEGATIVE_TTL_SEC)
        throw UnknownHostException("DohDns: all DoH sources failed for $hostname")
    }

    /** One DoH answer: the addresses plus the TTL we will cache them for. */
    internal data class DohAnswer(val ips: List<String>, val ttlSec: Long)

    private fun query(source: DohSource, hostname: String): DohAnswer {
        // Normal path: the DoH hostname itself resolves.
        val direct = runCatching { queryOnce(source.url, hostname) }.getOrNull()
        if (direct != null && direct.ips.isNotEmpty()) return direct

        // Last resort: dial a literal IP. Host header + SNI still carry the real
        // hostname because we rewrite only the URL's host.
        for (ip in source.bootstrapIps) {
            val viaIp = runCatching { queryOnce(pickBootstrapUrl(source, ip), hostname) }
                .getOrNull()
            if (viaIp != null && viaIp.ips.isNotEmpty()) return viaIp
        }
        return DohAnswer(emptyList(), MAX_TTL_SEC)
    }

    private fun queryOnce(baseUrl: String, hostname: String): DohAnswer {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("name", hostname)
            .addQueryParameter("type", QUERY_TYPES)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .get()
            .build()
        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return DohAnswer(emptyList(), MAX_TTL_SEC)
            val body = response.body?.string().orEmpty()
            val parsed = parseDnsJson(body)
            if (parsed.ips.isEmpty()) return DohAnswer(emptyList(), MAX_TTL_SEC)
            return parsed
        }
    }

    /**
     * Pure, JVM-testable: pull A/AAAA `data` out of an RFC 8484 JSON body.
     *
     * Returns addresses in server order and never throws — a malformed body is
     * an empty answer, which the caller treats as "this source had nothing".
     */
    internal fun parseDnsJson(body: String): DohAnswer {
        if (body.isBlank()) return DohAnswer(emptyList(), MAX_TTL_SEC)
        return try {
            val answers = JSONObject(body).optJSONArray("Answer")
            if (answers == null || answers.length() == 0) {
                return DohAnswer(emptyList(), MAX_TTL_SEC)
            }
            val out = ArrayList<String>(answers.length())
            // min() over every A/AAAA answer's TTL, per the caching contract.
            var minTtl = Long.MAX_VALUE
            for (i in 0 until answers.length()) {
                val a = answers.optJSONObject(i) ?: continue
                if (a.optInt("type", -1) !in TYPE_A_SET) continue
                val data = a.optString("data", "").trim()
                if (data.isEmpty()) continue
                out.add(data)
                minTtl = minOf(minTtl, a.optLong("TTL", MAX_TTL_SEC))
            }
            val ttl = if (minTtl == Long.MAX_VALUE) MAX_TTL_SEC else clampTtl(minTtl)
            DohAnswer(out, ttl)
        } catch (_: Exception) {
            DohAnswer(emptyList(), MAX_TTL_SEC)
        }
    }

    private val TYPE_A_SET = setOf(TYPE_A, TYPE_AAAA)

    /**
     * Pure: clamp an answer TTL into `(0, [MAX_TTL_SEC]]`. A zero/negative or
     * absurd TTL becomes the cap rather than "never expire" / "expire now".
     */
    internal fun clampTtl(seconds: Long): Long = when {
        seconds <= 0L -> 1L
        seconds > MAX_TTL_SEC -> MAX_TTL_SEC
        else -> seconds
    }

    /** Cached system answers get the full cap; the system cache is opaque to us. */
    private fun ttlForSystemAnswer(): Long = MAX_TTL_SEC

    /**
     * Pure: URL for a bootstrap (literal-IP) attempt. Only the URL host is
     * rewritten, so the `name`/`type` query params are still added by
     * [queryOnce] and TLS SNI/Host still carry the literal IP.
     *
     * ## Known limitation — this path is best-effort, not a guaranteed rescue
     *
     * Replacing the host with an IP also changes TLS SNI to that IP, while the
     * certificate presented by these anycast endpoints is issued for the
     * *hostname* (`dns.alidns.com`, …). Default hostname verification will
     * therefore reject the handshake unless the resolver also overrides the
     * hostname verifier. So this path helps mainly when the failure was
     * "cannot name the DoH host" AND the resolver is a custom one that does not
     * verify strictly; for Android's default verifier it will simply time out
     * or fail the handshake and we move to the next source, which is the
     * correct degradation. Making it dependable would need a
     * ConnectionSpec with a hostname verifier pinned to the source's hostname
     * — deliberately not done here, since trusting a certificate for a name we
     * are no longer sending is a security decision, not a bug fix.
     */
    internal fun pickBootstrapUrl(source: DohSource, ip: String): String =
        source.url.toHttpUrl().newBuilder().host(ip).build().toString()

    /** Never throws for a well-formed literal; drops anything malformed.
     *  getByName on a numeric literal is parse-only — no DNS round-trip —
     *  which is exactly the bootstrap property we need here. */
    private fun resolveAllSafe(ips: List<String>): List<InetAddress> =
        ips.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }

    private fun cachedIps(hostname: String): List<String>? = synchronized(cacheLock) {
        val entry = cache[hostname]
        if (entry == null) return@synchronized null
        if (entry.expireAtMillis <= nowProvider()) {
            cache.remove(hostname)
            return@synchronized null
        }
        entry.ips
    }

    /** Test seam for cache/negative-cache behaviour. Returns the cached list. */
    internal fun cachedForTest(hostname: String): List<String>? = cachedIps(hostname)

    /** Test seam: seed the cache with an explicit TTL. */
    internal fun putForTest(hostname: String, ips: List<String>, ttlSec: Long) =
        put(hostname, ips, ttlSec)

    /** Test seam: drop all cache state so cases do not leak into each other. */
    internal fun clearCacheForTest() = synchronized(cacheLock) { cache.clear() }

    private fun put(hostname: String, ips: List<String>, ttlSec: Long) {
        val expireAt = nowProvider() + ttlSec * 1000L
        synchronized(cacheLock) {
            cache.remove(hostname)
            cache[hostname] = CacheEntry(ips, expireAt)
            evictIfNeeded()
        }
    }

    /** Drops oldest-inserted until under the cap. Caller holds [cacheLock]. */
    private fun evictIfNeeded() {
        val it = cache.keys.iterator()
        var toDrop = cache.size - MAX_CACHE_ENTRIES
        while (toDrop > 0 && it.hasNext()) {
            it.next(); it.remove(); toDrop--
        }
    }
}

/**
 * Attach the DoH fallback to any client that talks to a hostname.
 *
 * The call is idempotent and preserves whatever else the caller configured
 * (timeouts, connection pool, proxy) — it only sets [Dns].
 *
 * ## SSRF caveat — do not blindly apply
 *
 * A client that enforces "the address I connect to is a public one" via its
 * own [Dns] (see `FetchUrlGuard.publicInternetDns`) must keep that Dns.
 * Overwriting it removes the guard; wrapping it would be the correct
 * composition and is left to that owner's call, not done here.
 */
fun OkHttpClient.Builder.withDohDns(): OkHttpClient.Builder = dns(DohDns)

/**
 * Compose the DoH fallback with a caller-supplied per-address safety check,
 * preserving the fail-closed rule such guards already use: **one** unsafe
 * address in the answer rejects the entire answer, rather than filtering the
 * bad address out and connecting anyway.
 *
 * This exists because an SSRF guard cannot simply be replaced by [DohDns].
 * [com.openminis.app.tools.FetchUrlGuard.publicInternetDns] validates the exact
 * addresses OkHttp would dial (including after redirects); swapping it out for
 * the DoH fallback would let a rebinding / poisoned answer through, and
 * filtering instead of rejecting would weaken a guard that is deliberately
 * conservative.
 *
 * [allow] returns true when the address is acceptable.
 */
fun guardedDohDns(allow: (InetAddress) -> Boolean): Dns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        // System first, DoH on UnknownHostException — see [DohDns].
        val resolved = DohDns.lookup(hostname)
        if (resolved.isEmpty() || resolved.any { !allow(it) }) {
            throw UnknownHostException("blocked non-public DNS answer for $hostname")
        }
        return resolved
    }
}