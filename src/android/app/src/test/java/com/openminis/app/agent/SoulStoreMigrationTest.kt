package com.openminis.app.agent

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.BackupMemoryTree
import com.openminis.app.backup.BackupUndoPaths
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
import org.robolectric.annotation.Config

/**
 * [GH#299] SOUL.md 内联图标迁移 + body 保真 + 备份闭环。
 *
 * 第二轮对抗复审在补修里发现两个回归，这里按最终语义钉死：
 *
 *  1. **备份闭环**：头像必须落在 `minis-global/memory/soul-icons/soul.png`
 *     （MEMORY 备份类别根内），"迁移 → 备份 → 清空 → 恢复 → decode 非空"
 *     全程不丢；
 *  2. **body 保真**：`save` 后磁盘上的正文仍是用户原文，base64 图片不被替换；
 *     只有 prompt 构建的内存副本出现 `[inline image stripped]`；
 *  3. **load 零写盘**：读取不再整文件重写 SOUL.md（消除与并发 save 的
 *     lost-update 窗口）；
 *  4. 失败安全：不可解码的 payload 保留原值。
 *
 * Robolectric（不是纯 JVM 测试）因为迁移会真实 decode / re-encode Bitmap。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class) // [T-robolectric-bare-app] MinisApp.onCreate pulls RootfsManager; these tests only need a Context.
class SoulStoreMigrationTest {

    private lateinit var context: Context

    /** 1×1 透明 PNG，最小的真实图片 payload。 */
    private val onePixelPng =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

    private fun dataUri() = "data:image/png;base64,$onePixelPng"

    /** 迁移后 frontmatter 里的引用（新位置：memory 备份根内）。 */
    private fun migratedRef() = SoulIcon.fileRefFor(SoulIconMigration.ICON_RELATIVE_PATH)

    private fun iconFile() = SoulIconMigration.iconFile(context)

    private fun memoryRoot() = BackupMemoryTree.deviceRoot(context.filesDir)

    private fun soulFile() = SoulStore.fileLocation(context)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Belt and braces: Robolectric normally gives each test a fresh
        // filesDir, but these assertions depend on "no migrated icon yet".
        // memory 根同时承载 SOUL.md、persona 文件和迁移后的头像。
        memoryRoot().deleteRecursively()
        File(context.filesDir, "soul-icons").deleteRecursively()
    }

    private fun writeRawSoul(icon: String, body: String) {
        val file = soulFile()
        file.parentFile?.mkdirs()
        file.writeText(
            SoulMDParser.serialize(
                SoulFile(SoulMetadata.DEFAULT.copy(icon = icon), body),
            ),
        )
    }

    // ── 1. 迁移目标在备份类别内 ─────────────────────────────────────────

    @Test
    fun `save migrates inline icon into the memory backup root`() {
        SoulStore.save(
            context,
            SoulFile(SoulMetadata.DEFAULT.copy(icon = dataUri()), "body"),
        )

        val onDisk = soulFile().readText()
        assertFalse("base64 must be gone from SOUL.md", onDisk.contains("data:image"))
        assertTrue(onDisk.contains("icon: \"${migratedRef()}\""))
        val icon = iconFile()
        assertTrue("migrated PNG must exist", icon.isFile)
        assertTrue("migrated PNG must not be empty", icon.length() > 0)
        assertTrue(
            "migrated PNG must sit inside the MEMORY backup category root",
            SoulIcon.isContained(icon, memoryRoot()),
        )
        assertNotNull(SoulIcon.decode(migratedRef(), context))
    }

    @Test
    fun `legacy soul-icons reference is migrated into the backup root on save`() {
        // 第二轮补修的落盘形态：文件在 filesDir/soul-icons，引用是 file:soul-icons/soul.png。
        val legacyDir = File(context.filesDir, "soul-icons").apply { mkdirs() }
        val legacyFile = File(legacyDir, "soul.png")
        legacyFile.writeBytes(Base64.decode(onePixelPng, Base64.DEFAULT))
        val legacyRef = SoulIcon.fileRefFor(SoulIconMigration.LEGACY_ICON_RELATIVE_PATH)
        assertNotNull(
            "compat: the old out-of-backup ref must keep rendering until it is migrated",
            SoulIcon.decode(legacyRef, context),
        )

        SoulStore.save(
            context,
            SoulFile(SoulMetadata.DEFAULT.copy(icon = legacyRef), "body"),
        )

        val loaded = SoulStore.load(context)!!
        assertEquals(migratedRef(), loaded.metadata.icon)
        assertTrue(iconFile().isFile)
        assertFalse("old out-of-backup copy must be gone", legacyFile.exists())
        assertNotNull(SoulIcon.decode(loaded.metadata.icon, context))
    }

    // ── 2. body 保真（E2）──────────────────────────────────────────────

    @Test
    fun `save keeps the inline image bytes in the SOUL md body`() {
        val body = "persona\nlogo: ${dataUri()}\nend"

        SoulStore.save(context, SoulFile(SoulMetadata.DEFAULT.copy(icon = dataUri()), body))

        val onDisk = soulFile().readText()
        assertTrue(
            "save must not delete the user's inline image payload from disk",
            onDisk.contains("data:image/png;base64,$onePixelPng"),
        )
        assertFalse(
            "placeholder is prompt-only; it must never be persisted",
            onDisk.contains(SoulIconMigration.INLINE_IMAGE_PLACEHOLDER),
        )
        assertEquals(body, SoulStore.load(context)!!.body.trim())
    }

    @Test
    fun `save does not scrub a large inline image payload from the body`() {
        // [GH#299] 反向断言：旧行为（保存时把 body 内 base64 永久替换成占位符）
        // 已被移除；大 payload 也必须原样保留。
        val payload = "Q".repeat(5000)
        val body = "persona\nlogo: data:image/png;base64,$payload\nend"

        SoulStore.save(context, SoulFile(SoulMetadata.DEFAULT.copy(icon = dataUri()), body))

        val onDisk = soulFile().readText()
        assertTrue(
            "user body must be preserved verbatim on disk",
            onDisk.contains("data:image/png;base64,$payload"),
        )
        assertFalse(onDisk.contains(SoulIconMigration.INLINE_IMAGE_PLACEHOLDER))
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
    fun `prompt building strips inline image payloads but leaves the file intact`() {
        val body = "Voice: calm.\n" +
            "![shot](data:image/png;base64,${"B".repeat(3000)})\n" +
            "End."
        writeRawSoul("", body)
        val before = soulFile().readText()

        val prompt = SystemPromptBuilder.identitySection(context)

        assertFalse("no base64 may reach the system prompt", prompt.contains("base64"))
        assertTrue(prompt.contains(SoulIconMigration.INLINE_IMAGE_PLACEHOLDER))
        assertTrue(prompt.contains("Voice: calm."))
        assertTrue(prompt.contains("End."))
        assertEquals(
            "prompt building must work on a copy, never rewrite SOUL.md",
            before,
            soulFile().readText(),
        )
        assertTrue("the user's base64 must still be on disk", soulFile().readText().contains("base64"))
    }

    // ── 3. load 不写盘（E3）─────────────────────────────────────────────

    @Test
    fun `load is read-only and does not migrate on read`() {
        val uri = dataUri()
        writeRawSoul(uri, "be helpful")
        val before = soulFile().readText()

        val loaded = SoulStore.load(context)!!

        assertEquals("load must return the raw inline value", uri, loaded.metadata.icon)
        assertEquals("load must not rewrite SOUL.md", before, soulFile().readText())
        assertFalse("load must not create a migrated icon file", iconFile().exists())
    }

    @Test
    fun `save is idempotent for an already migrated icon`() {
        val first = SoulFile(SoulMetadata.DEFAULT.copy(icon = dataUri()), "body")
        SoulStore.save(context, first)
        val afterFirst = soulFile().readText()

        val loaded = SoulStore.load(context)!!
        SoulStore.save(context, loaded)

        assertEquals(afterFirst, soulFile().readText())
        assertEquals(migratedRef(), SoulStore.load(context)!!.metadata.icon)
        assertTrue(iconFile().isFile)
    }

    @Test
    fun `undecodable inline icon keeps the original value`() {
        val broken = "data:image/png;base64,!!!!"

        SoulStore.save(context, SoulFile(SoulMetadata.DEFAULT.copy(icon = broken), "body"))

        val loaded = SoulStore.load(context)!!
        assertEquals(broken, loaded.metadata.icon)
        assertFalse(iconFile().exists())
        // The original frontmatter value is still on disk, not silently dropped.
        assertTrue(soulFile().readText().contains("icon: \"$broken\""))
    }

    // ── 4. 备份闭环（E1 + E4）──────────────────────────────────────────

    @Test
    fun `migrated icon survives backup then wipe then restore`() {
        val body = "keep me\n![x](${dataUri()})\nend"
        SoulStore.save(context, SoulFile(SoulMetadata.DEFAULT.copy(icon = dataUri()), body))

        val saved = SoulStore.load(context)!!
        val ref = saved.metadata.icon
        assertEquals(migratedRef(), ref)
        assertNotNull("precondition: avatar decodes before backup", SoulIcon.decode(ref, context))

        // 备份：BackupExporter.exportMemory 的真实实现（共享 BackupMemoryTree）。
        val staging = File(context.cacheDir, "soul-backup-closure").apply {
            deleteRecursively()
            mkdirs()
        }
        val exported = BackupMemoryTree.export(memoryRoot(), File(staging, "memory"))
        assertTrue("SOUL.md + icon must both be packaged", exported.entries >= 2)
        val iconInPackage = File(
            File(staging, "memory"),
            iconFile().relativeTo(memoryRoot()).invariantSeparatorsPath,
        )
        assertTrue("migrated PNG must be inside the package memory tree", iconInPackage.isFile)
        assertTrue("SOUL.md must be inside the package memory tree", File(staging, "memory/SOUL.md").isFile)

        // 清空：模拟换机 / 卸载重装后的空 filesDir。
        memoryRoot().deleteRecursively()
        assertNull("regression guard: missing icon decodes to null", SoulIcon.decode(ref, context))
        assertNull(SoulStore.load(context))

        // 恢复：BackupImporter.importMemory 的真实实现（共享 BackupMemoryTree）。
        val report = BackupMemoryTree.restore(File(staging, "memory"), memoryRoot())
        assertEquals(0, report.rejectedPaths)
        assertEquals(exported.entries, report.filesWritten)

        val restored = SoulStore.load(context)!!
        assertEquals(ref, restored.metadata.icon)
        assertEquals(body, restored.body.trim())
        assertNotNull(
            "backup -> wipe -> restore must keep the avatar decodable",
            SoulIcon.decode(restored.metadata.icon, context),
        )
    }

    @Test
    fun `memory backup root matches the restore undo root`() {
        assertTrue(
            "migrated icon must stay under SoulStore.MEMORY_SUBDIR",
            SoulIconMigration.ICON_RELATIVE_PATH.startsWith("${SoulStore.MEMORY_SUBDIR}/"),
        )
        assertEquals(
            "MEMORY export/restore and undo must cover the same root",
            listOf(SoulStore.MEMORY_SUBDIR),
            BackupUndoPaths.pathsFor(setOf(BackupCategory.MEMORY)),
        )
    }

    // ── 解码 / containment ─────────────────────────────────────────────

    @Test
    fun `file reference decodes through SoulIcon`() {
        iconFile().parentFile?.mkdirs()
        iconFile().writeBytes(Base64.decode(onePixelPng, Base64.DEFAULT))

        assertNotNull(SoulIcon.decode(migratedRef(), context))
    }

    @Test
    fun `file reference cannot escape files dir`() {
        assertNull(SoulIcon.fileFor(context, "file:../databases/provider.db"))
        assertNull(SoulIcon.fileFor(context, "file:"))
        assertNotNull(SoulIcon.fileFor(context, SoulIcon.fileRefFor(SoulIconMigration.ICON_RELATIVE_PATH)))
    }
}
