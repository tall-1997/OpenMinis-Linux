package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-path-resolver] The single host<->guest conversion point (#22): both
 * directions round-trip, edge paths stay jailed, and the session layout
 * factory agrees with [SessionWorkspace.resolveGuestPath].
 */
class PathResolverTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newFolder(name: String): File = tmp.newFolder(name)

    @Test
    fun `toHost and toGuest round-trip through the same bind`() {
        val hostRoot = newFolder("ws")
        File(hostRoot, "src/deep").mkdirs()
        val r = PathResolver.ofBinds(mapOf("/var/minis/workspace" to hostRoot.absolutePath))

        val host = r.toHost("/var/minis/workspace/src/deep/a.txt")
        assertEquals(File(hostRoot, "src/deep/a.txt").absolutePath, host!!.absolutePath)

        val guest = r.toGuest(File(hostRoot, "src/deep/a.txt").absolutePath)
        assertEquals("/var/minis/workspace/src/deep/a.txt", guest)
    }

    @Test
    fun `bind root itself maps both ways`() {
        val hostRoot = newFolder("ws")
        val r = PathResolver.ofBinds(mapOf("/var/minis/workspace" to hostRoot.absolutePath))
        assertEquals(hostRoot.absolutePath, r.toHost("/var/minis/workspace")!!.absolutePath)
        assertEquals("/var/minis/workspace", r.toGuest(hostRoot.absolutePath))
    }

    @Test
    fun `outside paths return null in both directions`() {
        val hostRoot = newFolder("ws")
        val other = newFolder("elsewhere")
        val r = PathResolver.ofBinds(mapOf("/var/minis/workspace" to hostRoot.absolutePath))

        assertNull(r.toHost("/var/minis/memory/x.md"))
        assertNull(r.toHost("/var/minis/"))
        assertNull(r.toHost("relative/path.txt"))
        assertNull(r.toGuest(File(other, "f.txt").absolutePath))
    }

    @Test
    fun `guest prefix boundary never half-matches a longer name`() {
        val hostRoot = newFolder("ws")
        val r = PathResolver.ofBinds(mapOf("/var/minis/workspace" to hostRoot.absolutePath))
        // "/var/minis/workspaceX" is NOT under the /var/minis/workspace bind.
        assertNull(r.toHost("/var/minis/workspaceX/f.txt"))
    }

    @Test
    fun `dotDot hop that stays inside resolves but escape is rejected`() {
        val hostRoot = newFolder("ws")
        File(hostRoot, "a").mkdirs()
        val r = PathResolver.ofBinds(mapOf("/var/minis/workspace" to hostRoot.absolutePath))

        // The returned File keeps the raw segments (the pre-existing
        // resolveHostPath behaviour); containment is what the guard judges,
        // so compare canonical paths.
        val inside = r.toHost("/var/minis/workspace/a/../b.txt")
        assertEquals(File(hostRoot, "b.txt").canonicalPath, inside!!.canonicalPath)

        assertNull(r.toHost("/var/minis/workspace/../../escape.txt"))
    }

    @Test
    fun `trailing slash and duplicate separators resolve to the root`() {
        val hostRoot = newFolder("ws")
        val r = PathResolver.ofBinds(mapOf("/var/minis/workspace" to hostRoot.absolutePath))
        assertEquals(hostRoot.absolutePath, r.toHost("/var/minis/workspace/")!!.absolutePath)
        assertEquals(
            File(hostRoot, "a/b.txt").absolutePath,
            r.toHost("/var/minis/workspace//a/b.txt")!!.absolutePath,
        )
    }

    @Test
    fun `longest guest prefix wins`() {
        val hostRoot = newFolder("ws")
        val subRoot = newFolder("ws-mount")
        val r = PathResolver.ofBinds(
            linkedMapOf(
                "/var/minis/workspace" to hostRoot.absolutePath,
                "/var/minis/workspace/tool-spill" to subRoot.absolutePath,
            ),
        )
        assertEquals(
            File(subRoot, "job.log").absolutePath,
            r.toHost("/var/minis/workspace/tool-spill/job.log")!!.absolutePath,
        )
        assertEquals(
            File(hostRoot, "other.txt").absolutePath,
            r.toHost("/var/minis/workspace/other.txt")!!.absolutePath,
        )
    }

    @Test
    fun `toGuest picks the innermost root for nested mounts`() {
        val sdcard = newFolder("sdcard")
        val nested = File(sdcard, "vault").apply { mkdirs() }
        val r = PathResolver.ofBinds(
            linkedMapOf(
                "/var/minis/mounts/sdcard" to sdcard.absolutePath,
                "/var/minis/mounts/vault" to nested.absolutePath,
            ),
        )
        assertEquals(
            "/var/minis/mounts/vault/note.md",
            r.toGuest(File(nested, "note.md").absolutePath),
        )
        assertEquals(
            "/var/minis/mounts/sdcard/pictures/img.jpg",
            r.toGuest(File(sdcard, "pictures/img.jpg").absolutePath),
        )
    }

    @Test
    fun `duplicate guest prefixes keep the first binding`() {
        val first = newFolder("first")
        val second = newFolder("second")
        // ofBinds collapses duplicate map keys, so first-wins is exercised via
        // an explicit Mapping list (the shape forSession assembles: layout
        // roots first, external binds dropped on collision).
        val r = PathResolver.ofMappings(
            listOf(
                PathResolver.Mapping("/var/minis/workspace", first),
                PathResolver.Mapping("/var/minis/workspace", second),
            ),
        )
        assertEquals(
            File(first, "a.txt").canonicalPath,
            r.toHost("/var/minis/workspace/a.txt")!!.canonicalPath,
        )
    }

    @Test
    fun `session layout resolves workspace memory and global binds`() {
        val filesDir = tmp.root
        val sid = "sess-a"
        SessionWorkspace.ensureDirs(filesDir, sid)

        val r = PathResolver.forSessionLayout(filesDir, sid)
        assertEquals(
            File(SessionWorkspace.hostDir(filesDir, sid, "workspace"), "x.py").absolutePath,
            r.toHost("/var/minis/workspace/x.py")!!.absolutePath,
        )
        assertEquals(
            File(SessionWorkspace.memoryDir(filesDir, sid), "d.md").absolutePath,
            r.toHost("/var/minis/memory/d.md")!!.absolutePath,
        )
        assertEquals(
            File(File(filesDir, SessionWorkspace.GLOBAL_DIR), "skills/s/SKILL.md").absolutePath,
            r.toHost("/var/minis/skills/s/SKILL.md")!!.absolutePath,
        )
        // Unknown subdirs stay unresolvable.
        assertNull(r.toHost("/var/minis/unknown/x"))
        assertNull(r.toHost("/opt/bin/sh"))
    }

    @Test
    fun `resolveGuestPath agrees with the resolver for the session layout`() {
        val filesDir = tmp.root
        val sid = "sess-b"
        SessionWorkspace.ensureDirs(filesDir, sid)
        val r = PathResolver.forSessionLayout(filesDir, sid)

        for (guest in listOf(
            "/var/minis/workspace/a.txt",
            "/var/minis/workspace",
            "/var/minis/memory/2026-01-01.md",
            "/var/minis/offloads/tools/t_1.txt",
            "/var/minis/shared/x",
            "/var/minis/",
            "/var/minis/unknown/y",
            "/var/minis/workspace/../../escape",
        )) {
            val expected = r.toHost(guest)?.absolutePath
            val actual = SessionWorkspace.resolveGuestPath(filesDir, sid, guest)?.absolutePath
            assertEquals("mismatch for $guest", expected, actual)
        }
    }

    @Test
    fun `forSession merges external binds without overriding layout`() {
        val filesDir = tmp.root
        val sid = "sess-c"
        SessionWorkspace.ensureDirs(filesDir, sid)
        val mountRoot = newFolder("vault")

        val r = PathResolver.forSession(
            filesDir,
            sid,
            listOf(
                "/var/minis/mounts/vault" to mountRoot.absolutePath,
                // Layout must win this collision.
                "/var/minis/workspace" to newFolder("bogus").absolutePath,
            ),
        )
        assertEquals(
            "/var/minis/mounts/vault/doc.md",
            r.toGuest(File(mountRoot, "doc.md").absolutePath),
        )
        assertEquals(
            File(SessionWorkspace.hostDir(filesDir, sid, "workspace"), "a.txt").absolutePath,
            r.toHost("/var/minis/workspace/a.txt")!!.absolutePath,
        )
    }

    @Test
    fun `guestPathUnder is root-scoped not global`() {
        val sdcard = newFolder("sdcard")
        val nested = File(sdcard, "vault").apply { mkdirs() }
        val target = File(nested, "note.md").absolutePath

        // The global resolver collapses onto the innermost prefix...
        val global = PathResolver.ofBinds(
            linkedMapOf(
                "/var/minis/mounts/sdcard" to sdcard.absolutePath,
                "/var/minis/mounts/vault" to nested.absolutePath,
            ),
        )
        assertEquals("/var/minis/mounts/vault/note.md", global.toGuest(target))

        // ...while each watch root keeps publishing under its own prefix.
        assertEquals(
            "/var/minis/mounts/vault/note.md",
            PathResolver.guestPathUnder(nested, "/var/minis/mounts/vault", target),
        )
        assertEquals(
            "/var/minis/mounts/sdcard/vault/note.md",
            PathResolver.guestPathUnder(sdcard, "/var/minis/mounts/sdcard", target),
        )
        assertNull(PathResolver.guestPathUnder(nested, "/var/minis/mounts/vault", "/not/under"))
    }

    @Test
    fun `plus extends a resolver without mutating it`() {
        val ws = newFolder("ws")
        val mount = newFolder("m")
        val base = PathResolver.ofBinds(mapOf("/var/minis/workspace" to ws.absolutePath))
        val extended = base.plus(
            listOf(PathResolver.Mapping("/var/minis/mounts/vault", mount)),
        )
        assertNull(base.toHost("/var/minis/mounts/vault/f.txt"))
        assertEquals(
            File(mount, "f.txt").absolutePath,
            extended.toHost("/var/minis/mounts/vault/f.txt")!!.absolutePath,
        )
    }
}
