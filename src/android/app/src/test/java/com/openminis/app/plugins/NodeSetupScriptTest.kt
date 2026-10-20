package com.openminis.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-devsuite-node24-tarball] Contract for the guest installer script shipped
 * as an asset. The recipe (DevSuiteCatalogTest) says WHAT is installed; this
 * pins HOW, because the failure modes only show up inside the guest:
 *
 *  - an unpinned or unverified payload is code execution as root;
 *  - a missing `--strip-components` produces the wrong /opt layout;
 *  - linking anywhere but /usr/local/bin leaves the distro node 18 in front;
 *  - a mirror URL that only exists when the digest is known.
 */
class NodeSetupScriptTest {

    private val script = File("src/main/assets/default_mount/usr/local/bin/minis-setup-node")
    private val offlineLib = File("src/main/assets/default_mount/usr/local/lib/minis/offline.sh")

    private fun text() = script.readText()

    @Test
    fun `script exists and is a posix shell script`() {
        assertTrue("missing ${script.path}", script.isFile)
        val body = text()
        assertTrue(body.startsWith("#!/bin/sh"))
        assertTrue(body.contains("set -eu"))
    }

    @Test
    fun `payload is pinned by sha256 and verified through the shared helper`() {
        val body = text()
        val pinned = Regex("""NODE_SHA256="([0-9a-f]{64})"""").find(body)
        assertTrue("no 64-hex SHA256 pin in minis-setup-node", pinned != null)
        // offline.sh fails closed: a mismatch discards the payload.
        assertTrue(body.contains(". /usr/local/lib/minis/offline.sh"))
        assertTrue(body.contains("resume_fetch"))
        assertTrue(
            "the caller-resolved digest must be declared, or mirrors are skipped",
            body.contains("_MINIS_EXPECTED=\"sha256 \$NODE_SHA256\""),
        )
        assertFalse("no curl | sh pipeline", body.contains("| sh"))
    }

    @Test
    fun `catalogue digest and the script pin are the same literal`() {
        val body = text()
        val pinned = Regex("""NODE_SHA256="([0-9a-f]{64})"""").find(body)!!.groupValues[1]
        // NODE_VERSION is a bare literal; TARBALL interpolates it.
        val version = Regex("""NODE_VERSION=([0-9.]+)""").find(body)!!.groupValues[1]
        val tarball = "node-v$version-linux-arm64.tar.xz"
        val lib = offlineLib.readText()
        assertTrue(
            "offline.sh catalogue must carry the same digest as minis-setup-node",
            lib.contains(pinned),
        )
        // The catalogue is keyed by file name; a rename must not silently
        // orphan the digest (offline_pick would then reject the payload).
        assertTrue(
            "offline.sh catalogue must key $tarball",
            lib.contains("$tarball)"),
        )
    }

    @Test
    fun `installs into opt and shadows the distro node via usr local bin`() {
        val body = text()
        assertTrue(body.contains("ROOT=/opt/nodejs"))
        assertTrue(body.contains("LINK_DIR=/usr/local/bin"))
        assertTrue("tar must strip the versioned top directory", body.contains("--strip-components=1"))
        assertTrue(body.contains("ln -sfn"))
        // The fallback contract: never remove the distro package.
        assertFalse(body.contains("apt-get remove"))
        assertFalse(body.contains("apt-get purge"))
    }

    @Test
    fun `documents the per-command heap override`() {
        // 192MB is the app-injected baseline (GuestLimits); the script must
        // tell the user how to raise it for one heavy build instead of silently
        // pretending Node 24 unlocked Vite/Next.
        assertTrue(text().contains("max-old-space-size"))
    }
}
