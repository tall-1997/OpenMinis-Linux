package com.openminis.app.ssh

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ssh-backend] TOFU host key store: decide/record/forget transitions,
 * ssh-keygen-compatible fingerprint format, and file persistence.
 */
class SshKnownHostsTest {

    private val blobA = "ssh-ed25519 AAAAC3Nz...hostA".toByteArray()
    private val blobB = "ssh-ed25519 AAAAC3Nz...hostB".toByteArray()

    @Test
    fun fingerprintIsSha256Base64Unpadded() {
        val fp = SshKnownHosts.fingerprint(blobA)
        assertTrue(fp.startsWith("SHA256:"))
        assertFalse("base64 must be unpadded like ssh-keygen", fp.contains("="))
        assertEquals(fp, SshKnownHosts.fingerprint(blobA.copyOf()))
        assertNotEquals(fp, SshKnownHosts.fingerprint(blobB))
    }

    @Test
    fun hostPortKeyFormat() {
        assertEquals("example.com:2222", SshKnownHosts.hostPort("example.com", 2222))
    }

    @Test
    fun unknownHostDecidesNew() {
        val kh = SshKnownHosts(null)
        assertTrue(kh.decide("h:22", "SHA256:x") is SshKnownHosts.Decision.New)
        assertNull(kh.fingerprintFor("h:22"))
    }

    @Test
    fun recordThenMatchThenMismatch() {
        val kh = SshKnownHosts(null)
        val fpA = SshKnownHosts.fingerprint(blobA)
        val fpB = SshKnownHosts.fingerprint(blobB)
        kh.record("h:22", fpA)
        assertTrue(kh.decide("h:22", fpA) is SshKnownHosts.Decision.Match)
        val m = kh.decide("h:22", fpB)
        assertTrue(m is SshKnownHosts.Decision.Mismatch)
        assertEquals(fpA, (m as SshKnownHosts.Decision.Mismatch).stored)
        assertEquals(mapOf("h:22" to fpA), kh.all())
    }

    @Test
    fun forgetReturnsToNew() {
        val kh = SshKnownHosts(null)
        kh.record("h:22", "SHA256:a")
        kh.forget("h:22")
        assertTrue(kh.decide("h:22", "SHA256:b") is SshKnownHosts.Decision.New)
        kh.forget("never-pinned:22") // no-op, must not throw
    }

    @Test
    fun persistsAcrossInstances() {
        val f = File.createTempFile("known_hosts", ".json")
        try {
            SshKnownHosts(f).record("h:2222", "SHA256:persisted")
            val reloaded = SshKnownHosts(f)
            assertTrue(reloaded.decide("h:2222", "SHA256:persisted") is SshKnownHosts.Decision.Match)
            assertTrue(reloaded.decide("h:2222", "SHA256:other") is SshKnownHosts.Decision.Mismatch)
        } finally {
            f.delete()
        }
    }

    @Test
    fun corruptFileStartsEmptyAndSelfHeals() {
        val f = File.createTempFile("known_hosts", ".json")
        try {
            f.writeText("{ not json !!")
            val kh = SshKnownHosts(f)
            assertTrue(kh.all().isEmpty())
            kh.record("h:22", "SHA256:x") // save() overwrites the corrupt file
            assertTrue(SshKnownHosts(f).decide("h:22", "SHA256:x") is SshKnownHosts.Decision.Match)
        } finally {
            f.delete()
        }
    }
}
