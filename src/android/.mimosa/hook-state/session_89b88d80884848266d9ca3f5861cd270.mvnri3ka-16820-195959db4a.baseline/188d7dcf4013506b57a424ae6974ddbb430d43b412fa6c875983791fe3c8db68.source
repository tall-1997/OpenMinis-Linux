package com.openminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InstallProgressContractTest {
    private fun asset(path: String) = File("src/main/assets/default_mount", path).readText()

    @Test
    fun aptWrapperDoesNotRecurseAndPrintsNewlines() {
        val wrap = asset("usr/local/bin/minis-apt-wrap")
        val aptGet = asset("usr/local/bin/apt-get")
        assertTrue(aptGet.contains("/usr/local/bin/minis-apt-wrap /usr/bin/apt-get"))
        assertTrue(wrap.contains("/usr/bin/apt-get") || wrap.contains("\"\$real\""))
        assertFalse(wrap.contains("exec apt-get"))
        assertTrue(wrap.contains("apt 已开始"))
        assertTrue(wrap.contains("MINIS_APT_RAW"))
        assertTrue(asset("usr/local/bin/apt").contains("/usr/bin/apt"))
    }

    @Test
    fun aptDoesNotWaitTwoMinutesOnAStalledMirror() {
        val conf = asset("etc/apt/apt.conf.d/99minis-proot")
        assertTrue(conf.contains("Acquire::http::Timeout \"30\""))
        assertTrue(conf.contains("--force-unsafe-io"))
        assertTrue(asset("etc/dpkg/dpkg.cfg.d/99minis-unsafe-io").contains("force-unsafe-io"))
        val mirror = asset("usr/local/bin/minis-mirror")
        assertTrue(mirror.contains("UPDATE_TIMEOUT=45"))
        assertFalse(mirror.contains("UPDATE_TIMEOUT=120"))
    }

    @Test
    fun sdkDownloadAbortsAStalledMirrorAndPrintsBytes() {
        val setup = asset("usr/local/bin/minis-android-sdk-setup")
        assertTrue(setup.contains("--speed-time 30"))
        assertTrue(setup.contains("--speed-limit 100"))
        assertTrue(setup.contains("已下载"))
        assertTrue(setup.contains("--checkpoint=20000"))
        assertFalse(setup.contains("curl -fL --retry 2 --retry-delay 1 --connect-timeout 20"))
    }

    @Test
    fun aptLockWaitIsVisible() {
        val lock = asset("usr/local/lib/minis/apt-lock.sh")
        val waitLine = lock.lines().first { it.contains("另一个 apt 还在跑") }
        assertFalse(waitLine.contains(">&2"))
    }
}
