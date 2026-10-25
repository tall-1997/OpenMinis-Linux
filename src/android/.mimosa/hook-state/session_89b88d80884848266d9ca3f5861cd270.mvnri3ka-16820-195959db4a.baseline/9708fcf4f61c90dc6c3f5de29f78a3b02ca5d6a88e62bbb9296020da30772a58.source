package com.openminis.app.ssh

import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-ssh-backend] Metadata/secret split store: the metadata file must stay
 * free of credential material, secrets live in the (test: in-memory) box.
 */
class SshConfigStoreTest {

    private lateinit var metaFile: File
    private lateinit var box: InMemorySecretBox
    private lateinit var store: SshConfigStore

    @Before
    fun setUp() {
        metaFile = File.createTempFile("ssh-hosts", ".json")
        box = InMemorySecretBox()
        store = SshConfigStore(metaFile, box)
    }

    @After
    fun tearDown() {
        metaFile.delete()
    }

    private fun cfg(
        id: String = "web",
        name: String = "web",
        host: String = "10.0.0.5",
        port: Int = 22,
        user: String = "root",
        auth: SshAuthType = SshAuthType.PASSWORD,
    ) = SshHostConfig(id = id, name = name, host = host, port = port, username = user, authType = auth)

    @Test
    fun putListFindRemove() {
        store.put(cfg(), SshCredentials(password = "pw"))
        assertEquals(1, store.list().size)
        assertTrue(store.hasHosts())
        assertNotNull(store.find("web"))      // by id
        assertNotNull(store.find("WEB"))      // by name, case-insensitive
        assertNotNull(store.find("10.0.0.5")) // by hostname
        assertTrue(store.remove("web"))
        assertFalse(store.remove("web"))
        assertFalse(store.hasHosts())
    }

    @Test
    fun metadataFileNeverContainsSecrets() {
        store.put(cfg(), SshCredentials(password = "sup3r-s3cret-pw", passphrase = "pp-s3cret"))
        val raw = metaFile.readText()
        assertFalse(raw.contains("sup3r-s3cret-pw"))
        assertFalse(raw.contains("pp-s3cret"))
        assertTrue("metadata itself must persist", raw.contains("10.0.0.5"))
        assertEquals("sup3r-s3cret-pw", store.credentialsFor("web")?.password)
        assertEquals("pp-s3cret", store.credentialsFor("web")?.passphrase)
    }

    @Test
    fun keyMaterialRoundTrips() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nXYZ\n-----END OPENSSH PRIVATE KEY-----"
        store.put(cfg(auth = SshAuthType.PRIVATE_KEY), SshCredentials(privateKeyPem = pem, passphrase = "pp"))
        val c = store.credentialsFor("web")
        assertEquals(pem, c?.privateKeyPem)
        assertEquals("pp", c?.passphrase)
        assertFalse(metaFile.readText().contains("XYZ"))
    }

    @Test
    fun removeClearsSecrets() {
        store.put(cfg(), SshCredentials(password = "pw"))
        assertNotNull(store.credentialsFor("web"))
        store.remove("web")
        assertNull(store.credentialsFor("web"))
        assertTrue(box.map.isEmpty())
    }

    @Test
    fun putReplacesSameIdAndUpdatesSecret() {
        store.put(cfg(), SshCredentials(password = "pw1"))
        store.put(cfg(host = "10.0.0.6"), SshCredentials(password = "pw2"))
        assertEquals(1, store.list().size)
        assertEquals("10.0.0.6", store.find("web")?.host)
        assertEquals("pw2", store.credentialsFor("web")?.password)
        assertEquals("one secret slot per host", 1, box.map.keys.count { it.startsWith("web.") })
    }

    @Test
    fun credentialsForNullWhenNothingStored() {
        store.put(cfg(), null)
        assertNull(store.credentialsFor("web"))
        assertNull(store.credentialsFor("missing"))
    }

    @Test
    fun validationRejectsBadConfigs() {
        val e1 = assertThrows(SshFailure.Config::class.java) {
            store.put(cfg(name = "bad name!"), SshCredentials(password = "p"))
        }
        assertTrue(e1.message!!.contains("name"))
        assertThrows(SshFailure.Config::class.java) {
            store.put(cfg(port = 0), SshCredentials(password = "p"))
        }
        assertThrows(SshFailure.Config::class.java) {
            store.put(cfg(port = 65536), SshCredentials(password = "p"))
        }
        assertThrows(SshFailure.Config::class.java) {
            store.put(cfg(host = " "), SshCredentials(password = "p"))
        }
        assertThrows(SshFailure.Config::class.java) {
            store.put(cfg(user = ""), SshCredentials(password = "p"))
        }
        assertTrue("failed puts must not persist", store.list().isEmpty())
    }

    @Test
    fun newIdSlugsAndDedupes() {
        assertEquals("my-server", SshConfigStore.newId("My Server!", emptySet()))
        assertEquals("my-server-1", SshConfigStore.newId("My Server!", setOf("my-server")))
        assertEquals("my-server-2", SshConfigStore.newId("My Server!", setOf("my-server", "my-server-1")))
        assertEquals("host", SshConfigStore.newId("   ", emptySet()))
        assertEquals("host", SshConfigStore.newId("!!!", emptySet()))
    }

    @Test
    fun pinFingerprintSetsAndClears() {
        store.put(cfg(), SshCredentials(password = "p"))
        store.pinFingerprint("web", "SHA256:abc")
        assertEquals("SHA256:abc", store.find("web")?.hostKeyFingerprint)
        store.pinFingerprint("web", "")
        assertNull("blank pin clears (forgetHostKey path)", store.find("web")?.hostKeyFingerprint)
    }

    @Test
    fun reloadsFromDisk() {
        store.put(cfg(), SshCredentials(password = "pw"))
        store.pinFingerprint("web", "SHA256:abc")
        val again = SshConfigStore(metaFile, box)
        assertEquals("SHA256:abc", again.find("web")?.hostKeyFingerprint)
        assertEquals("pw", again.credentialsFor("web")?.password)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val c = cfg(auth = SshAuthType.PRIVATE_KEY).copy(hostKeyFingerprint = "SHA256:fp", createdAt = 123L)
        assertEquals(c, SshConfigStore.decode(SshConfigStore.encode(c)))
        assertNull(SshConfigStore.decode(null))
        assertNull(SshConfigStore.decode(JSONObject("{}")))
        assertNull(
            SshConfigStore.decode(
                JSONObject("""{"id":"","name":"","host":"","username":"","authType":"bogus"}"""),
            ),
        )
    }

    @Test
    fun describeNeverLeaksAndShowsPin() {
        store.put(cfg(), SshCredentials(password = "hunter2"))
        val d = store.find("web")!!.describe()
        assertFalse(d.contains("hunter2"))
        assertTrue(d.contains("root@10.0.0.5:22"))
        assertTrue(d.contains("auth=password"))
        store.pinFingerprint("web", "SHA256:abcdefghijklmnopqrstuvwx")
        assertTrue(store.find("web")!!.describe().contains("pinned="))
    }
}
