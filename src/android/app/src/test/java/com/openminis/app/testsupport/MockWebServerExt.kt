package com.openminis.app.testsupport

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * [T-test-takerequest-bounded] Bounded variant of [MockWebServer.takeRequest].
 *
 * WHY THIS EXISTS: the no-arg overload parks the calling thread FOREVER. Every
 * provider test drives the real client through `runCatching { provider.send…() }`
 * before reading the recorded request, so when the client fails to connect
 * (the documented Windows loopback TIME_WAIT storm) — or a regression stops the
 * request from firing at all — the swallowed exception leaves `takeRequest()`
 * parked indefinitely and ONE flaky connect freezes the entire suite. Observed:
 * `testReleaseUnitTest` sat 3h47m with `ThinkingRulesRegressionTest.capture()`
 * hung in the unbounded wait, zero CPU, no new result XMLs. A bounded wait turns
 * that freeze into a fast, self-explaining failure naming the actual mechanism.
 */
fun MockWebServer.takeRequestOrThrow(timeoutSeconds: Long = 15): RecordedRequest =
    takeRequest(timeoutSeconds, TimeUnit.SECONDS)
        ?: error(
            "MockWebServer recorded no request within ${timeoutSeconds}s — the client " +
                "failed before sending (exception swallowed by runCatching?) or the code " +
                "under test never issued the expected call",
        )
