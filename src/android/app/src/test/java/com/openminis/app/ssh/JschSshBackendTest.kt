package com.openminis.app.ssh

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-ssh-backend] Host-resolution and cleanup paths of the JSch backend —
 * the parts that need no network. Connect/exec paths are integration-only.
 */
class JschSshBackendTest {

    private lateinit var metaFile: File
    private lateinit var box: InMemorySecretBox
    private lateinit var knownHosts: SshKnownHosts
    private lateinit var backend: JschSshBackend

    @Before
    fun setUp() {
        metaFile = File.createTempFile("ssh-hosts", ".json")
        box = InMemorySecretBox()
        knownHosts = SshKnownHosts(null)
        backend = JschSshBackend(SshConfigStore(metaFile, box), knownHosts)
    }

    private fun addHost(id: String = "web") {
        backend.store.put(
            SshHostConfig(
                id = id, name = id, host = "10.0.0.5", port = 22,
                username = "root", authType = SshAuthType.PASSWORD,
            ),
            SshCredentials(password = "pw"),
        )
    }

    @Test
    fun removeHostResolvesClearsPinAndSecrets() {
        addHost()
        knownHosts.record("10.0.0.5:22", "SHA256:pinned")
        assertTrue(backend.removeHost("WEB")) // case-insensitive name resolution
        assertFalse(backend.store.hasHosts())
        assertNull(backend.store.credentialsFor("web"))
        assertNull("pin must be evicted so a re-add re-pins cleanly", knownHosts.fingerprintFor("10.0.0.5:22"))
    }

    @Test
    fun removeHostUnknownIsFalse() {
        assertFalse(backend.removeHost("ghost"))
    }

    @Test
    fun forgetHostKeyClearsOnlyThePin() {
        addHost()
        knownHosts.record("10.0.0.5:22", "SHA256:pinned")
        backend.forgetHostKey("web")
        assertNull(knownHosts.fingerprintFor("10.0.0.5:22"))
        assertTrue("host config survives", backend.store.hasHosts())
    }

    @Test
    fun forgetHostKeyUnknownThrows() {
        val e = assertThrows(SshFailure.UnknownHost::class.java) { backend.forgetHostKey("ghost") }
        assertTrue(e.message!!.contains("ghost"))
    }

    @Test
    fun execWithoutCredentialsFailsAsConfig() {
        // Metadata present but the secret slot is empty (e.g. secrets wiped):
        // must surface as Config, never attempt an anonymous connect.
        backend.store.put(
            SshHostConfig(
                id = "nosecret", name = "nosecret", host = "10.0.0.9", port = 22,
                username = "root", authType = SshAuthType.PASSWORD,
            ),
            null,
        )
        val e = assertThrows(SshFailure.Config::class.java) {
            backend.exec("nosecret", "uptime", 5_000, null)
        }
        assertTrue(e.message!!.contains("No stored credentials"))
    }
}
