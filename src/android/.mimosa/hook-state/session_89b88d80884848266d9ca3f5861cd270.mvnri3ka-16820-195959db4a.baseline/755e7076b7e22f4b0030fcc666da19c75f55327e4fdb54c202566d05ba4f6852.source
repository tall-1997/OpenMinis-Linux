package com.openminis.app.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxResourceGateTest {
    @Test
    fun apkBuildDetectsGradleAndAapt() {
        assertTrue(SandboxResourceGate.isApkBuild("./gradlew :app:assembleDebug"))
        assertTrue(SandboxResourceGate.isApkBuild("gradle assembleRelease"))
        assertTrue(SandboxResourceGate.isApkBuild("/opt/android-sdk/build-tools/35.0.2/aapt2"))
        assertFalse(SandboxResourceGate.isApkBuild("python3 -m pytest"))
    }

    @Test
    fun packageManagerDetectsAptAndSdkmanager() {
        assertTrue(SandboxResourceGate.isPackageManager("apt-get install -y golang-go"))
        assertTrue(SandboxResourceGate.isPackageManager("sdkmanager --list"))
        assertTrue(SandboxResourceGate.isPackageManager("minis-android-sdk-setup"))
        assertFalse(SandboxResourceGate.isPackageManager("ls /tmp"))
    }

    @Test
    fun aptWaitDoesNotCancelTheHolder() = runBlocking {
        val result = SandboxResourceGate.withCommandLock("minis-dev-setup-full", aptWaitMs = 40) {
            delay(150)
            "done"
        }
        assertEquals("done", result)
    }

    @Test
    fun aptWaitFailsWithoutRunningTheBlock() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val holder = async {
            SandboxResourceGate.withCommandLock("apt-get install curl", aptWaitMs = 2_000) {
                started.complete(Unit)
                hold.await()
                "held"
            }
        }
        started.await()
        val waiter = async {
            runCatching {
                SandboxResourceGate.withCommandLock("apt-get update", aptWaitMs = 80) { "ran" }
            }
        }
        val failed = withTimeout(2_000) { waiter.await() }
        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull() is RuntimeException)
        hold.complete(Unit)
        assertEquals("held", withTimeout(2_000) { holder.await() })
    }

    @Test
    fun nonAptDoesNotWaitOnAptMutex() = runBlocking {
        check(SandboxResourceGate.aptMutex.tryLock())
        try {
            val result = withTimeout(500) {
                SandboxResourceGate.withCommandLock("ls /tmp") { "ok" }
            }
            assertEquals("ok", result)
        } finally {
            SandboxResourceGate.aptMutex.unlock()
        }
    }

    @Test
    fun aptLineThatAlsoMentionsGradleStillTakesAptLock() = runBlocking {
        check(SandboxResourceGate.aptMutex.tryLock())
        try {
            val failed = withTimeout(2_000) {
                runCatching {
                    SandboxResourceGate.withCommandLock("apt-get install gradle", aptWaitMs = 80) { "ran" }
                }
            }
            assertTrue(failed.isFailure)
        } finally {
            SandboxResourceGate.aptMutex.unlock()
        }
    }
}
