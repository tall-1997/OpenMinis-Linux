// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.network

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [FEAT-261021-05, iOS e720112cf] A "[Net][Alert]" / "[Net][Recovered]" line
 * from the Go entry (deps/rclone-mobile/nethub/exitalert.go): a proxy or the
 * Tailscale node failed several connections in a row, or works again. Same
 * parser as iOS `NetworkAlertEvent`.
 */
data class NetworkAlertEvent(
    val subject: Subject,
    val recovered: Boolean = false,
    val failures: Int = 0,
    val stage: String = "",
    /** nethub failure class: timeout, dns, refused, unreachable, proxy-auth, proxy-rejected, tls, closed, other. */
    val failureClass: String = "",
    /** The underlying error, one line (no secrets: host:port and status only). */
    val detail: String = "",
) {
    sealed class Subject {
        data class Proxy(val id: String) : Subject()
        object Tailscale : Subject()
    }

    companion object {
        fun parse(line: String): NetworkAlertEvent? {
            val alert = line.indexOf("[Net][Alert] ")
            val recoveredAt = line.indexOf("[Net][Recovered] ")
            val (recovered, rest) = when {
                alert >= 0 -> false to line.substring(alert + "[Net][Alert] ".length)
                recoveredAt >= 0 -> true to line.substring(recoveredAt + "[Net][Recovered] ".length)
                else -> return null
            }
            val fields = HashMap<String, String>()
            var detail = ""
            var cursor = rest
            while (cursor.isNotEmpty()) {
                if (cursor.startsWith("err=")) {
                    detail = cursor.removePrefix("err=")
                    break
                }
                val token = cursor.substringBefore(' ')
                cursor = cursor.removePrefix(token).trimStart(' ')
                val eq = token.indexOf('=')
                if (eq > 0) fields[token.substring(0, eq)] = token.substring(eq + 1)
            }
            val subject = when (fields["exit"]) {
                "proxy" -> Subject.Proxy(fields["id"]?.takeIf { it.isNotEmpty() } ?: return null)
                "tailscale" -> Subject.Tailscale
                else -> return null
            }
            return NetworkAlertEvent(
                subject = subject,
                recovered = recovered,
                failures = fields["failures"]?.toIntOrNull() ?: 0,
                stage = fields["stage"].orEmpty(),
                failureClass = fields["class"].orEmpty(),
                detail = detail,
            )
        }
    }
}

/**
 * Shows a banner when a proxy or Tailscale keeps failing, so the user can
 * decide whether to stop using it; tapping it opens that setting. Port of
 * iOS `NetworkAlertCenter`.
 *
 * No banner while the device itself is offline (the network is the problem,
 * not the proxy), for a proxy or node the settings no longer use, or for a
 * subject the user dismissed in the last [SNOOZE_MS]. The latest failure per
 * subject stays in [failing] for the settings page until it recovers.
 */
object NetworkAlertCenter {

    private const val TAG = "Network"
    const val SNOOZE_MS = 30 * 60 * 1000L

    private val _banner = MutableStateFlow<NetworkAlertEvent?>(null)
    val banner: StateFlow<NetworkAlertEvent?> = _banner.asStateFlow()

    private val _failing = MutableStateFlow<Map<NetworkAlertEvent.Subject, NetworkAlertEvent>>(emptyMap())
    val failing: StateFlow<Map<NetworkAlertEvent.Subject, NetworkAlertEvent>> = _failing.asStateFlow()

    /** Set by NetworkHub.init: whether the device has a validated network. Tests replace it. */
    @Volatile
    var isOnline: () -> Boolean = { true }

    /** Tests replace it. */
    @Volatile
    var now: () -> Long = System::currentTimeMillis

    private val snoozedUntil = HashMap<NetworkAlertEvent.Subject, Long>()

    /** Called from the Go log callback (any thread). */
    fun ingest(line: String) {
        if (!line.contains("[Net][Alert]") && !line.contains("[Net][Recovered]")) return
        NetworkAlertEvent.parse(line)?.let { handle(it, NetworkHub.settings.value) }
    }

    @Synchronized
    internal fun handle(e: NetworkAlertEvent, s: NetworkSettings) {
        if (e.recovered) {
            _failing.update { it - e.subject }
            if (_banner.value?.subject == e.subject) _banner.value = null
            AppLogger.info(TAG, "[Net][Alert] ${describe(e.subject)} works again - banner cleared")
            return
        }
        if (!inUse(e.subject, s)) return
        _failing.update { it + (e.subject to e) }
        if (!isOnline()) {
            AppLogger.info(TAG, "[Net][Alert] ${describe(e.subject)} failed ${e.failures}x while the device is offline - no banner")
            return
        }
        if ((snoozedUntil[e.subject] ?: 0L) > now()) {
            AppLogger.info(TAG, "[Net][Alert] ${describe(e.subject)} failed ${e.failures}x - banner dismissed earlier, not shown again yet")
            return
        }
        AppLogger.warning(TAG, "[Net][Alert] ${describe(e.subject)} failed ${e.failures}x in a row " +
            "(stage=${e.stage} class=${e.failureClass}) - showing banner")
        _banner.value = e
    }

    /** The close button: hide it, and keep it hidden for this subject a while. */
    @Synchronized
    fun dismissBanner() {
        val b = _banner.value ?: return
        snoozedUntil[b.subject] = now() + SNOOZE_MS
        _banner.value = null
    }

    /** A tap on the banner: it goes away; the caller opens the setting. */
    @Synchronized
    fun takeBanner(): NetworkAlertEvent? = _banner.value.also { _banner.value = null }

    /** Settings changed: forget subjects they no longer use. */
    @Synchronized
    fun prune(s: NetworkSettings) {
        _failing.update { m -> m.filterKeys { inUse(it, s) } }
        _banner.value?.let { if (!inUse(it.subject, s)) _banner.value = null }
    }

    private fun inUse(subject: NetworkAlertEvent.Subject, s: NetworkSettings): Boolean = when (subject) {
        is NetworkAlertEvent.Subject.Proxy -> !s.forceDirect && s.proxies.any { it.id == subject.id }
        NetworkAlertEvent.Subject.Tailscale -> s.nodeWanted
    }

    private fun describe(subject: NetworkAlertEvent.Subject) = when (subject) {
        is NetworkAlertEvent.Subject.Proxy -> "proxy ${subject.id.take(8)}"
        NetworkAlertEvent.Subject.Tailscale -> "tailscale"
    }

    /** Tests only. */
    @Synchronized
    internal fun resetForTest() {
        _banner.value = null
        _failing.value = emptyMap()
        snoozedUntil.clear()
        isOnline = { true }
        now = System::currentTimeMillis
    }
}
