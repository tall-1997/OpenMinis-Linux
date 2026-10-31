package com.openminis.app.vscreen

import com.openminis.app.offload.ShizukuManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-vscreen2] JVM tests for the silent ledger: the appops command
 * sequence (get original mode BEFORE set ignore), the restore sequence
 * (default→allow mapping), idempotence, the "user has it open on main"
 * refusal, leftover reconcile (dead display / app on main), and prefs
 * persistence across instances (what survives a process death).
 */
class AudioMuterTest {

    private class FakeShell {
        val calls = mutableListOf<Array<String>>()
        var respond: (Array<String>) -> ShizukuManager.ProcessResult = { ShizukuManager.ProcessResult(0, "", "") }
        fun run(argv: Array<String>, timeoutMs: Long): ShizukuManager.ProcessResult {
            calls.add(argv)
            return respond(argv)
        }

        fun joined() = calls.map { it.joinToString(" ") }
    }

    private fun muter(shell: FakeShell, prefs: VscreenPrefs = MemoryPrefs()) =
        AudioMuter({ argv, _ -> shell.run(argv, 5_000L) }, prefs)

    @Test
    fun `mute reads the original mode first then sets ignore`() {
        val shell = FakeShell()
        shell.respond = { argv ->
            if (argv.first() == "appops" && argv[1] == "get") {
                ShizukuManager.ProcessResult(0, "PLAY_AUDIO: allow; time=+5s ago", "")
            } else {
                ShizukuManager.ProcessResult(0, "", "")
            }
        }
        val note = muter(shell).mute(2, "com.example.app")
        assertTrue(note.contains("audio muted via appops"))
        assertTrue(note.contains("allow"))
        // The current-user probe (am get-current-user) rides ahead once; the
        // ledger's own sequence is get → set.
        assertEquals(
            listOf(
                "appops get --user 0 com.example.app PLAY_AUDIO",
                "appops set --user 0 com.example.app PLAY_AUDIO ignore",
            ),
            shell.joined().filter { it.startsWith("appops") },
        )
    }

    @Test
    fun `unmute restores the original mode and default maps to allow`() {
        val shell = FakeShell()
        val prefs = MemoryPrefs()
        val m = muter(shell, prefs)
        m.mute(2, "com.example.app") // original: default
        shell.calls.clear()
        val note = m.unmute("com.example.app")
        assertTrue(note!!.contains("back to: default"))
        assertEquals(
            listOf("appops set --user 0 com.example.app PLAY_AUDIO allow"),
            shell.joined(),
        )
        assertTrue(muter(shell, prefs).mutedPackages().isEmpty())
    }

    @Test
    fun `an explicit original mode is restored verbatim`() {
        val shell = FakeShell()
        shell.respond = { argv ->
            if (argv.first() == "appops" && argv[1] == "get") {
                ShizukuManager.ProcessResult(0, "PLAY_AUDIO: ignore; time=...", "")
            } else {
                ShizukuManager.ProcessResult(0, "", "")
            }
        }
        val m = muter(shell)
        m.mute(3, "com.a") // was already "ignore" before us
        assertEquals("allow", AudioMuter.restoreMode("default"))
        assertEquals("ignore", AudioMuter.restoreMode("ignore"))
        // Restore writes the ORIGINAL explicit mode back, not "allow".
        m.unmute("com.a")
        assertTrue(shell.joined().last().endsWith("com.a PLAY_AUDIO ignore"))
    }

    @Test
    fun `muting an already-muted package is idempotent and keeps the original mode`() {
        val shell = FakeShell()
        val prefs = MemoryPrefs()
        val m = muter(shell, prefs)
        shell.respond = {
            ShizukuManager.ProcessResult(0, "PLAY_AUDIO: allow; time=...", "")
        }
        m.mute(2, "com.a")
        val before = AudioMuter.parseLine(prefs.getString(AudioMuter.LEDGER_KEY)!!)!!.originalMode
        val note = m.mute(2, "com.a")
        assertTrue(note.contains("already muted via appops"))
        assertEquals("allow", before)
        // No extra appops set ran.
        assertEquals(2, shell.calls.size)
    }

    @Test
    fun `mute refuses a package the user has open on the main display`() {
        val shell = FakeShell()
        val note = muter(shell).mute(2, "com.a", mainVisible = { true })
        assertTrue(note.contains("not muting"))
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `a failed appops get is reported and nothing is recorded`() {
        val shell = FakeShell()
        shell.respond = { ShizukuManager.ProcessResult(1, "", "op not found") }
        val prefs = MemoryPrefs()
        val note = muter(shell, prefs).mute(2, "com.a")
        assertTrue(note.contains("audio mute failed"))
        assertNull(prefs.getString(AudioMuter.LEDGER_KEY))
    }

    @Test
    fun `restoreForDisplay only restores that display's entries`() {
        val shell = FakeShell()
        val prefs = MemoryPrefs()
        val m = muter(shell, prefs)
        m.mute(2, "com.a")
        m.mute(3, "com.b")
        val restored = m.restoreForDisplay(2)
        assertEquals(listOf("com.a"), restored)
        assertEquals(listOf("com.b"), muter(shell, prefs).mutedPackages())
    }

    @Test
    fun `reconcile restores leftovers of dead displays`() {
        val shell = FakeShell()
        val prefs = MemoryPrefs()
        muter(shell, prefs).mute(2, "com.a") // display 2 will be gone
        shell.calls.clear()
        val notes = muter(shell, prefs).reconcile(liveDisplayIds = emptySet(), mainVisible = { null })
        assertTrue(notes.any { it.contains("leftover") && it.contains("back to: default") })
        assertTrue(muter(shell, prefs).mutedPackages().isEmpty())
        assertTrue(shell.joined().single().endsWith("com.a PLAY_AUDIO allow"))
    }

    @Test
    fun `reconcile restores an app that reached the main display`() {
        val shell = FakeShell()
        val prefs = MemoryPrefs()
        muter(shell, prefs).mute(2, "com.a")
        shell.calls.clear()
        val notes = muter(shell, prefs).reconcile(liveDisplayIds = setOf(2), mainVisible = { it == "com.a" })
        assertTrue(notes.any { it.contains("moved to user") })
        assertTrue(muter(shell, prefs).mutedPackages().isEmpty())
    }

    @Test
    fun `reconcile keeps entries whose display is alive and app not on main`() {
        val shell = FakeShell()
        val prefs = MemoryPrefs()
        muter(shell, prefs).mute(2, "com.a")
        shell.calls.clear()
        val notes = muter(shell, prefs).reconcile(liveDisplayIds = setOf(2), mainVisible = { false })
        assertTrue(notes.isEmpty())
        assertEquals(listOf("com.a"), muter(shell, prefs).mutedPackages())
    }

    @Test
    fun `the ledger persists across instances like a process restart`() {
        val shell = FakeShell()
        val prefs = MemoryPrefs()
        muter(shell, prefs).mute(2, "com.a")
        val fresh = muter(shell, prefs) // "restarted" process, same prefs
        assertEquals(listOf("com.a"), fresh.mutedPackages())
        // And it restores what the dead process left behind.
        val notes = fresh.reconcile(liveDisplayIds = emptySet())
        assertTrue(notes.isNotEmpty())
    }

    @Test
    fun `shell metacharacters cannot ride inside a package name`() {
        val shell = FakeShell()
        val note = muter(shell).mute(2, "com.a; rm -rf /")
        assertTrue(note.contains("not a valid package name"))
        assertTrue(shell.calls.isEmpty())
        assertNull(AudioMuter.parseLine("com.a; rm\tallow\t0\t2\t1"))
    }

    @Test
    fun `parseMode reads the appops output shapes`() {
        assertEquals("ignore", AudioMuter.parseMode("PLAY_AUDIO: ignore; time=+3s ago"))
        assertEquals("allow", AudioMuter.parseMode("PLAY_AUDIO: allow"))
        assertEquals("default", AudioMuter.parseMode("No operations."))
        assertEquals("default", AudioMuter.parseMode(""))
    }

    @Test
    fun `parseCurrentUserId takes the first integer and falls back to zero`() {
        assertEquals(0, AudioMuter.parseCurrentUserId("0"))
        assertEquals(11, AudioMuter.parseCurrentUserId("user 11\nextra"))
        assertEquals(0, AudioMuter.parseCurrentUserId(""))
    }

    @Test
    fun `parseLine round-trips entries and rejects junk`() {
        val e = AudioMuter.Entry("com.a", "default", 0, 2, 123L)
        val line = "${e.pkg}\t${e.originalMode}\t${e.userId}\t${e.displayId}\t${e.mutedAtMs}"
        assertEquals(e, AudioMuter.parseLine(line))
        assertNull(AudioMuter.parseLine("too\tfew\tcols"))
        assertNull(AudioMuter.parseLine("com.a\tdefault\tx\tnotanint\t1"))
    }

    @Test
    fun `a failed restore keeps the ledger entry for a later retry`() {
        val shell = FakeShell()
        val prefs = MemoryPrefs()
        val m = muter(shell, prefs)
        m.mute(2, "com.a")
        shell.calls.clear()
        shell.respond = { ShizukuManager.ProcessResult(1, "", "refused") }
        val note = m.unmute("com.a")
        assertTrue(note!!.contains("audio restore failed"))
        assertEquals(listOf("com.a"), muter(shell, prefs).mutedPackages())
        assertFalse(shell.joined().isEmpty())
    }
}
