package com.openminis.app.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Contract tests for the glibc plugin wrapper. The gateway keeps
 * addressing each entry by its manifest name, so the wrapper must be
 * exactly three things: idempotent (a second run changes nothing), a
 * pure-name swap (script takes the entry name, the real binary moves to
 * `.gnu`), and cautious (a non-ELF entry — the gateway's own launcher or
 * a panel-installed pack shape — is left alone).
 */
class A2aPluginShellTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fakeElf(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
    }

    @Test fun `shell script pins the exec shape`() {
        assertEquals(
            "#!/system/bin/sh\n" +
                "exec \"/h/glibc/ld-linux-aarch64.so.1\" --library-path \"/h/glibc\" " +
                "--inhibit-cache \"/h/bin/entry.gnu\" \"\$@\"\n",
            A2aPluginShell.shellScript("/h/glibc/ld-linux-aarch64.so.1", "/h/glibc", "/h/bin/entry.gnu"),
        )
    }

    @Test fun `wrapEntry turns a raw glibc ELF into script plus gnu binary`() {
        val home = tmp.newFolder("home")
        File(home, "glibc").mkdirs()
        File(home, "glibc/${A2aPluginShell.LOADER}").writeText("loader")
        val entry = tmp.newFolder("pack").resolve("entry")
        fakeElf(entry)

        assertTrue(A2aPluginShell.wrapEntry(entry, home))
        assertTrue(entry.readText().startsWith("#!/system/bin/sh"))
        assertTrue(File(entry.path + A2aPluginShell.GNU_SUFFIX).isFile)
        // The `.gnu` file keeps the original ELF magic.
        assertTrue(A2aPluginShell.isElf(File(entry.path + A2aPluginShell.GNU_SUFFIX)))
    }

    @Test fun `wrapEntry is idempotent`() {
        val home = tmp.newFolder("home")
        File(home, "glibc").mkdirs()
        File(home, "glibc/${A2aPluginShell.LOADER}").writeText("loader")
        val entry = tmp.newFolder("pack").resolve("entry")
        fakeElf(entry)

        assertTrue(A2aPluginShell.wrapEntry(entry, home))
        val scriptAfterFirst = entry.readText()
        val gnuMtime = File(entry.path + A2aPluginShell.GNU_SUFFIX).lastModified()
        assertTrue(A2aPluginShell.wrapEntry(entry, home))
        assertEquals(scriptAfterFirst, entry.readText())
        assertEquals(gnuMtime, File(entry.path + A2aPluginShell.GNU_SUFFIX).lastModified())
    }

    @Test fun `wrapEntry leaves non-ELF entries alone`() {
        val home = tmp.newFolder("home")
        val entry = tmp.newFolder("pack").resolve("entry")
        entry.writeText("#!/system/bin/sh\n# the gateway's own launcher\n")
        assertTrue(A2aPluginShell.wrapEntry(entry, home))
        // Untouched: still the launcher text, no .gnu sibling was created.
        assertTrue(entry.readText().contains("own launcher"))
        assertFalse(File(entry.path + A2aPluginShell.GNU_SUFFIX).exists())
    }

    @Test fun `missing entry and unreadable file report false without throwing`() {
        val home = tmp.newFolder("home")
        assertFalse(A2aPluginShell.wrapEntry(null, home))
        assertFalse(A2aPluginShell.wrapEntry(tmp.newFolder("pack").resolve("absent"), home))
    }

    @Test fun `entryFor resolves the manifest triple map`() {
        val versionDir = tmp.newFolder("v")
        File(versionDir, "pack.json").writeText(
            """{"entry": {"x86_64-unknown-linux-gnu": "bin/x64", """ +
                """"aarch64-unknown-linux-gnu": "bin/a64"}}""",
        )
        assertEquals(
            File(versionDir, "bin/a64").path,
            A2aPluginShell.entryFor(versionDir)?.path,
        )
    }

    @Test fun `entryFor falls back to the loose aarch64 linux match`() {
        val versionDir = tmp.newFolder("v")
        File(versionDir, "pack.json").writeText(
            """{"entry": {"aarch64-unknown-linux-musl": "bin/musl", "x86_64": "bin/x64"}}""",
        )
        assertEquals(
            File(versionDir, "bin/musl").path,
            A2aPluginShell.entryFor(versionDir)?.path,
        )
    }

    @Test fun `entryFor is null for a missing manifest or no linux entry`() {
        assertNull(A2aPluginShell.entryFor(tmp.newFolder("v1")))
        val v2 = tmp.newFolder("v2")
        File(v2, "pack.json").writeText("""{"entry": {"x86_64-unknown-linux-gnu": "bin/x64"}}""")
        assertNull(A2aPluginShell.entryFor(v2))
        val v3 = tmp.newFolder("v3")
        File(v3, "pack.json").writeText("not json at all")
        assertNull(A2aPluginShell.entryFor(v3))
    }

    @Test fun `ensure wraps every version of every id and skips missing runtime`() {
        val home = tmp.newFolder("home")
        val entry = File(home, "data/plugins/agent2api-providers/3.3.0-beta.6/entry")
        fakeElf(entry)
        File(entry.parentFile!!, "pack.json").writeText(
            """{"entry": {"aarch64-unknown-linux-gnu": "entry"}}""",
        )

        // No glibc runtime yet: nothing is wrapped, nothing throws.
        A2aPluginShell.ensure(home)
        assertTrue(A2aPluginShell.isElf(entry))

        File(home, "glibc").mkdirs()
        File(home, "glibc/${A2aPluginShell.LOADER}").writeText("loader")
        A2aPluginShell.ensure(home)
        assertTrue(entry.readText().startsWith("#!/system/bin/sh"))
    }
}
