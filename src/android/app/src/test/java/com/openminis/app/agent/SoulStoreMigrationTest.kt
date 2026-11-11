package com.openminis.app.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [GH#299] SOUL.md inline-icon migration + body-payload scrubbing.
 *
 * The reported bug was token waste: the frontmatter icon was never prompted,
 * but a hand-edited body (and the 85 KB icon line carried through every
 * read / sync / backup) still paid for the base64. These tests pin the three
 * behaviours the fix depends on:
 *
 *  1. read migration: `data:` icon → `file:soul-icons/soul.png` + SOUL.md rewrite,
 *  2. prompt/save scrubbing: `data:image/...;base64,...` → placeholder,
 *  3. failure safety: an undecodable payload keeps the original value.
 *
 * Robolectric (not a pure JVM test) because the migration decodes/re-encodes a
 * real Bitmap.
 */
@RunWith(RobolectricTestRunner::class)
class SoulStoreMigrationTest {

    private lateinit var context: Context

    /** 1×1 transparent PNG, the smallest real image payload. */
    private val onePixelPng =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

    private fun dataUri() = "data:image/png;base64,$onePixelPng"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Belt and braces: Robolectric normally gives each test a fresh
        // filesDir, but these assertions depend on "no migrated icon yet".
        SoulStore.fileLocation(context).parentFile?.deleteRecursively()
        File(context.filesDir, "soul-icons").deleteRecursively()
    }

    private fun writeRawSoul(icon: String, body: String) {
        val file = SoulStore.fileLocation(context)
        file.parentFile?.mkdirs()
        file.writeText(
            SoulMDParser.serialize(
                SoulFile(SoulMetadata.DEFAULT.copy(icon = icon), body),
            ),
        )
    }

    @Test
    fun `inline icon migrates to a file reference and rewrites SOUL md`() {
        val uri = dataUri()
        writeRawSoul(uri, "be helpful")

        val loaded = SoulStore.load(context)!!

        assertEquals("file:soul-icons/soul.png", loaded.metadata.icon)
        assertEquals("be helpful", loaded.body.trim())
        val iconFile = SoulIconMigration.iconFile(context)
        assertTrue("migrated PNG must exist", iconFile.isFile)
        assertTrue("migrated PNG must not be empty", iconFile.length() > 0)
        val onDisk = SoulStore.fileLocation(context).readText()
        assertFalse("base64 must be gone from SOUL.md", onDisk.contains("data:image"))
        assertTrue(onDisk.contains("icon: \"file:soul-icons/soul.png\""))
    }

    @Test
    fun `migration is idempotent`() {
        writeRawSoul(dataUri(), "body")
        SoulStore.load(context)
        val first = SoulStore.fileLocation(context).readText()

        SoulStore.load(context)

        assertEquals(first, SoulStore.fileLocation(context).readText())
    }

    @Test
    fun `save also migrates an inline icon`() {
        SoulStore.save(
            context,
            SoulFile(SoulMetadata.DEFAULT.copy(icon = dataUri()), "body"),
        )

        val onDisk = SoulStore.fileLocation(context).readText()
        assertFalse(onDisk.contains("data:image"))
        assertTrue(onDisk.contains("icon: \"file:soul-icons/soul.png\""))
        assertTrue(SoulIconMigration.iconFile(context).isFile)
    }

    @Test
    fun `undecodable inline icon keeps the original value`() {
        val broken = "data:image/png;base64,!!!!"
        writeRawSoul(broken, "body")

        val loaded = SoulStore.load(context)!!

        assertEquals(broken, loaded.metadata.icon)
        assertFalse(SoulIconMigration.iconFile(context).exists())
    }

    @Test
    fun `prompt body strips inline image payloads`() {
        val body = "before\n" +
            "![shot](data:image/png;base64,${"A".repeat(2000)})\n" +
            "after"

        val stripped = SoulIconMigration.stripInlineImageDataUris(body)

        assertFalse(stripped.contains("base64"))
        assertTrue(stripped.contains(SoulIconMigration.INLINE_IMAGE_PLACEHOLDER))
        assertTrue(stripped.contains("before"))
        assertTrue(stripped.contains("after"))
    }

    @Test
    fun `prompt building strips inline image payloads from the soul body`() {
        val body = "Voice: calm.\n" +
            "![shot](data:image/png;base64,${"B".repeat(3000)})\n" +
            "End."
        writeRawSoul("", body)

        val prompt = SystemPromptBuilder.identitySection(context)

        assertFalse("no base64 may reach the system prompt", prompt.contains("base64"))
        assertTrue(prompt.contains(SoulIconMigration.INLINE_IMAGE_PLACEHOLDER))
        assertTrue(prompt.contains("Voice: calm."))
        assertTrue(prompt.contains("End."))
    }

    @Test
    fun `save strips inline image payloads from the body`() {
        val body = "persona\nlogo: data:image/png;base64,${"Q".repeat(5000)}\nend"

        SoulStore.save(context, SoulFile(SoulMetadata.DEFAULT, body))

        val onDisk = SoulStore.fileLocation(context).readText()
        assertFalse(onDisk.contains("base64"))
        assertTrue(onDisk.contains(SoulIconMigration.INLINE_IMAGE_PLACEHOLDER))
        assertTrue(onDisk.contains("end"))
    }

    @Test
    fun `file reference decodes through SoulIcon`() {
        val dir = File(context.filesDir, "soul-icons").apply { mkdirs() }
        File(dir, "soul.png").writeBytes(
            android.util.Base64.decode(onePixelPng, android.util.Base64.DEFAULT),
        )

        assertNotNull(SoulIcon.decode("file:soul-icons/soul.png", context))
    }

    @Test
    fun `file reference cannot escape files dir`() {
        assertNull(SoulIcon.fileFor(context, "file:../databases/provider.db"))
        assertNull(SoulIcon.fileFor(context, "file:"))
        assertNotNull(SoulIcon.fileFor(context, "file:soul-icons/soul.png"))
    }
}
