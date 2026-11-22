package com.openminis.app.config

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prefs-registry-smoke] The smoke test the crash logs asked for:
 * open EVERY registered prefs file, then read/write EVERY registered key
 * through the real [PrefsHandle] guard.
 *
 * Why this exists: #419/#420 shipped six crashes where a key production code
 * reads was missing from the generated registry. Unit tests never touched
 * those stores, so the whole suite stayed green. This test makes the registry
 * self-consistent (every registered key is accepted by the guard for the file
 * that declares it) and would catch a generator that files a key under the
 * wrong file, a stale generated file, or a spec whose keys cannot round-trip.
 */
class PrefsRegistrySmokeTest {

    private val strictWas = Prefs.strictKeyGuard

    @After
    fun restore() {
        Prefs.strictKeyGuard = strictWas
        Prefs.uninstall()
    }

    @Test
    fun everyRegisteredFileOpensAndEveryRegisteredKeyRoundTrips() {
        Prefs.strictKeyGuard = true
        Prefs.install(InMemorySource())
        val failures = mutableListOf<String>()
        var files = 0
        var keys = 0
        for (spec in PrefsRegistry.files) {
            val handle = try {
                Prefs.file(spec.name)
            } catch (t: Throwable) {
                failures += "${spec.name}: cannot open the file: ${t.message}"
                continue
            }
            files +=1
            for ((constName, key) in spec.keys) {
                keys +=1
                try {
                    val probe = "smoke-$key"
                    handle.set(key, probe)
                    val readBack = handle.stringOrNull(key)
                    if (readBack != probe) {
                        failures += "${spec.name}/$key (const=$constName): round-trip mismatch"
                    }
                } catch (t: Throwable) {
                    failures += "${spec.name}/$key (const=$constName): ${t.javaClass.simpleName}: ${t.message}"
                }
            }
            for (prefix in spec.dynamicPrefixes) {
                try {
                    handle.contains(prefix + "smoke")
                } catch (t: Throwable) {
                    failures += "${spec.name}: dynamic prefix '$prefix' rejected: ${t.message}"
                }
            }
        }
        assertTrue("no prefs file in the registry", files >0)
        assertTrue("no key in the registry", keys >0)
        assertTrue(
            "PrefsRegistry is inconsistent:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
