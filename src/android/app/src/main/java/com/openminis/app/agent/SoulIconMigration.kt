package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.SampledDecode
import com.openminis.app.tools.AtomicFileWrite
import java.io.File

/**
 * [GH#299] SOUL.md 内联图标的落盘迁移 + prompt 侧 body 清洗。
 *
 * ## 为什么
 *
 * 自定义头像最初直接以 `data:image/png;base64,…` 写在 SOUL.md 的 `icon:`
 * frontmatter 里。它不进 prompt（frontmatter 白名单），但有两个真实代价：
 *
 *  1. **文件体积**：手改 / 同步进来的 SOUL.md 可能带 85 KB base64，整个文件
 *     被记忆备份、配置页、diff 反复搬运；`SoulIcon.decode` 的 UI 上限只治了
 *     渲染，没治存储。
 *  2. **body 内联图片**：用户把截图 base64 粘进 personality 正文时，正文是
 *     原样注入 system prompt 的（[SystemPromptBuilder]），每一轮都烧 token。
 *
 * ## 修法（第二轮对抗复审后的最终形态）
 *
 *  - **icon → 文件，且必须落在备份类别内**：解析出 data URI 后按
 *    [SoulIcon.STORED_PIXELS] 重新编码成 PNG，写到
 *    `filesDir/minis-global/memory/soul-icons/soul.png`，frontmatter 改成
 *    `file:minis-global/memory/soul-icons/soul.png`（见 [ICON_RELATIVE_PATH]）。
 *
 *    选方案 A（放进 memory 备份根）而不是方案 B（给 `filesDir/soul-icons` 新增
 *    备份类别）的原因：`BackupExporter`/`BackupImporter` 的 MEMORY 类别本来就是
 *    `minis-global/memory` 整树 `walkTopDown()` 打包/还原（见
 *    `BackupMemoryTree`），头像和 SOUL.md 同根即自动同生共死；不需要新增备份
 *    类别、不改 `.minisbak` 格式，也自动进入 `BackupUndoPaths` 的恢复撤销快照。
 *    第二轮补修把图标写到 `filesDir/soul-icons/`，而备份只打包 memory 树，于是
 *    恢复后 `SoulIcon.decode` 找不到文件、头像回退 sparkle —— 这正是要根因修掉
 *    的回归。[LEGACY_ICON_RELATIVE_PATH] 仅用于把已经落在旧位置的图标搬进来。
 *
 *  - **只在保存时迁移 frontmatter**：[SoulStore.save] 调用 [prepareForSave]；
 *    [SoulStore.load] 是纯读，不写 SOUL.md。旧实现的"读后整文件重写"与并发
 *    `save` 竞争时会用旧快照覆盖用户刚写入的内容（lost-update），删除写路径
 *    是根因修法。
 *
 *  - **body 原样保留**：磁盘上的 SOUL.md 永远保留用户原文（包括 base64 图片）；
 *    `data:image/...;base64,...` 只在 prompt 构建的**内存副本**里替换成
 *    [INLINE_IMAGE_PLACEHOLDER]（[SoulStore.save] 不再剥 body，见
 *    [SystemPromptBuilder.identitySection] 的两处 [stripInlineImageDataUris]）。
 *
 * 迁移是幂等的：`file:` 值（新路径）原样返回，不会重复重写 SOUL.md。
 */
internal object SoulIconMigration {

    private const val TAG = "SoulStore"

    /**
     * `filesDir`-relative path of the single migrated Soul icon.
     *
     * [GH#299] 必须位于 [SoulStore.MEMORY_SUBDIR] 之下：备份/恢复的 MEMORY
     * 类别按这个根整树递归打包，图标放在这里才能和 SOUL.md 一起进出备份包。
     */
    internal const val ICON_RELATIVE_PATH = "minis-global/memory/soul-icons/soul.png"

    /**
     * [GH#299] 第二轮补修写入的旧位置（`filesDir/soul-icons/soul.png`）。
     *
     * 它不在任何备份类别内，备份/恢复后头像会丢。保留这个常量只为一件事：
     * 已经在这条分支上落过盘的 `file:soul-icons/soul.png` 引用，在下一次
     * [SoulStore.save] 时把文件搬进 [ICON_RELATIVE_PATH] 并改写引用。读取路径
     * 仍然能解码旧位置（[SoulIcon.fileFor] 相对 filesDir），所以迁移失败也不会
     * 让头像立刻消失。
     */
    internal const val LEGACY_ICON_RELATIVE_PATH = "soul-icons/soul.png"

    /** Injected into a personality body in place of an inline base64 image. */
    internal const val INLINE_IMAGE_PLACEHOLDER = "[inline image stripped]"

    /**
     * Refuse absurd hand-edited payloads before base64-decoding them. 4 MB
     * still covers the reported 85 KB case by a wide margin.
     */
    private const val MAX_MIGRATABLE_CHARS = 4 * 1024 * 1024

    /**
     * Deliberately line-local: base64 in a Markdown body is written on one
     * line, and allowing whitespace would let the match swallow the following
     * paragraph (English words are valid base64 characters).
     */
    private val INLINE_DATA_URI = Regex(
        "data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=]+",
        RegexOption.IGNORE_CASE,
    )

    /**
     * `filesDir/minis-global/memory/soul-icons/soul.png`.
     *
     * [GH#299] 用 [ICON_RELATIVE_PATH] 直接拼 filesDir（而不是再手写一段目录名），
     * 备份类别根与常量之间只有这一处定义。
     */
    fun iconFile(context: Context): File =
        File(context.applicationContext.filesDir, ICON_RELATIVE_PATH)

    /**
     * Replace every inline image data URI in [body] with
     * [INLINE_IMAGE_PLACEHOLDER]. Pure and cheap when the body has no marker
     * (the common case: one substring scan, no regex).
     */
    fun stripInlineImageDataUris(body: String): String {
        if (!body.contains("data:image/", ignoreCase = true)) return body
        return INLINE_DATA_URI.replace(body, INLINE_IMAGE_PLACEHOLDER)
    }

    /**
     * 保存路径：只迁移 frontmatter 里的 icon，**完全不动 body**。
     *
     * [GH#299] 第二轮补修在这里把 body 内 base64 永久替换成
     * [INLINE_IMAGE_PLACEHOLDER]，用户原文被无声改写；现在磁盘保留原文，
     * 清洗只发生在 prompt 构建的内存副本上（[SystemPromptBuilder]）。
     *
     * 迁移失败返回原值并写日志，绝不丢图标；body 不参与本函数。
     */
    fun prepareForSave(context: Context, file: SoulFile): SoulFile {
        val current = file.metadata.icon
        val next = when {
            // 内联 data URI → PNG 文件（新位置，备份类别内）。
            SoulIcon.isInlineImageDataUri(current) -> writeIconFile(context, current)

            // 第二轮补修的旧 file: 位置 → 搬进备份类别根。
            current == SoulIcon.fileRefFor(LEGACY_ICON_RELATIVE_PATH) ->
                migrateLegacyIconFile(context, current)

            else -> null
        } ?: current
        if (next == current) return file
        AppLogger.info(
            TAG,
            "[GH#299] migrated SOUL icon reference to $next (file is now inside the memory backup root)",
        )
        return file.copy(metadata = file.metadata.copy(icon = next))
    }

    /**
     * [GH#299] 启动时一次性迁移 interim 构建留下的旧引用。
     *
     * 只在用户之后恰好保存 SOUL.md 时才迁移是不够的：从 interim 构建升级、
     * 之后不再保存 SOUL.md 的用户，旧 `file:soul-icons/soul.png` 永远进不了
     * 备份根，备份/恢复仍会丢头像。幂等：无变化直接返回；迁移失败保留旧
     * 引用，下次启动再试（不阻塞启动，调用点已在 IO 协程）。
     */
    fun migrateLegacyRefOnStartup(context: Context) {
        val current = SoulStore.load(context) ?: return
        val prepared = prepareForSave(context, current)
        if (prepared == current) return
        SoulStore.save(context, prepared)
        AppLogger.info(
            TAG,
            "[GH#299] startup migration moved the SOUL icon into the memory backup root",
        )
    }

    /**
     * Decode → downscale to [SoulIcon.STORED_PIXELS] → PNG →
     * `filesDir/minis-global/memory/soul-icons/soul.png`, returning the
     * frontmatter reference. Every failure is logged and returns null so the
     * caller keeps the original value.
     */
    private fun writeIconFile(context: Context, inline: String): String? {
        if (inline.length > MAX_MIGRATABLE_CHARS) {
            AppLogger.warning(
                TAG,
                "[GH#299] inline icon is ${inline.length} chars, too large to migrate; keeping it as-is",
            )
            return null
        }
        val bytes = SoulIcon.decodeInlineBytes(inline) ?: run {
            AppLogger.warning(TAG, "[GH#299] inline icon base64 could not be decoded; keeping it as-is")
            return null
        }
        val bitmap = runCatching { SampledDecode.decodeBytes(bytes, 512) }.getOrNull() ?: run {
            AppLogger.warning(TAG, "[GH#299] inline icon is not a decodable image; keeping it as-is")
            return null
        }
        val png = SoulIcon.encodePng(bitmap) ?: run {
            AppLogger.warning(TAG, "[GH#299] inline icon could not be re-encoded; keeping it as-is")
            return null
        }
        if (png.size > SoulIcon.MAX_ICON_FILE_BYTES) {
            AppLogger.warning(
                TAG,
                "[GH#299] re-encoded icon is ${png.size} bytes, above the file cap; keeping it as-is",
            )
            return null
        }

        val target = iconFile(context)
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            target.parentFile?.mkdirs()
            temp.writeBytes(png)
            // moveIntoPlace has no short-write verification (it only renames);
            // check the exact byte count so a full disk cannot install a
            // truncated PNG that then fails to render forever.
            if (temp.length() != png.size.toLong()) {
                temp.delete()
                AppLogger.warning(TAG, "[GH#299] migrated icon short write; keeping the inline value")
                return null
            }
            // AtomicFileWrite.moveIntoPlace reuses the same per-path lock and
            // rename/copy-over fallback SOUL.md writes use, without needing a
            // text payload.
            if (!AtomicFileWrite.moveIntoPlace(temp, target)) {
                AppLogger.warning(TAG, "[GH#299] could not move migrated icon into ${target.name}")
                return null
            }
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            AppLogger.warning(TAG, "[GH#299] could not write migrated icon: ${t.message}")
            return null
        }
        return SoulIcon.fileRefFor(ICON_RELATIVE_PATH)
    }

    /**
     * [GH#299] 把第二轮补修写到 `filesDir/soul-icons/soul.png` 的旧图标搬进
     * 备份类别根。只有精确的旧引用（[LEGACY_ICON_RELATIVE_PATH]）才迁移，用户
     * 手改的其它 `file:` 值不碰。
     *
     * 顺序保证"先落新、后删旧"：新文件短写/搬移失败时旧文件保留，引用维持原值，
     * `SoulIcon.decode` 继续能读到旧位置；删除旧文件失败也只是留一份副本。
     */
    private fun migrateLegacyIconFile(context: Context, legacyRef: String): String? {
        val legacy = SoulIcon.fileFor(context, legacyRef) ?: return null
        if (!legacy.isFile) {
            AppLogger.warning(
                TAG,
                "[GH#299] legacy icon file is missing (${legacy.absolutePath}); keeping the reference as-is",
            )
            return null
        }
        if (legacy.length() > SoulIcon.MAX_ICON_FILE_BYTES) {
            AppLogger.warning(
                TAG,
                "[GH#299] legacy icon is ${legacy.length()} bytes, above the file cap; keeping it as-is",
            )
            return null
        }

        val target = iconFile(context)
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            target.parentFile?.mkdirs()
            legacy.copyTo(temp, overwrite = true)
            if (temp.length() != legacy.length()) {
                temp.delete()
                AppLogger.warning(TAG, "[GH#299] legacy icon copy short write; keeping the old reference")
                return null
            }
            if (!AtomicFileWrite.moveIntoPlace(temp, target)) {
                AppLogger.warning(TAG, "[GH#299] could not move legacy icon into ${target.name}")
                return null
            }
            // New bytes are in place; dropping the old copy is best-effort only.
            if (!runCatching { legacy.delete() }.getOrDefault(false)) {
                AppLogger.info(
                    TAG,
                    "[GH#299] legacy icon left in place after migration: ${legacy.absolutePath}",
                )
            }
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            AppLogger.warning(TAG, "[GH#299] could not migrate legacy icon: ${t.message}")
            return null
        }
        return SoulIcon.fileRefFor(ICON_RELATIVE_PATH)
    }
}
