package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.SampledDecode
import com.openminis.app.tools.AtomicFileWrite
import java.io.File
import java.io.IOException

/**
 * [GH#299] SOUL.md inline-icon migration + body payload scrubbing.
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
 * ## 修法
 *
 *  - **icon → 文件**：解析出 data URI 后按 [SoulIcon.STORED_PIXELS] 重新编码
 *    成 PNG，写到 `filesDir/soul-icons/soul.png`，frontmatter 改成
 *    `file:soul-icons/soul.png`。读取路径（[SoulStore.load]）和写入路径
 *    （[SoulStore.save]）都会迁移；迁移失败保留原值并写日志，绝不丢图标。
 *  - **body → 占位符**：prompt 构建时把 `data:image/...;base64,...` 换成
 *    [INLINE_IMAGE_PLACEHOLDER]；保存前也剥掉，避免下一次读取又带着它。
 *
 * 迁移是幂等的：`file:` 值原样返回，不会重复重写 SOUL.md。
 */
internal object SoulIconMigration {

    private const val TAG = "SoulStore"

    /** `filesDir`-relative path of the single migrated Soul icon. */
    internal const val ICON_RELATIVE_PATH = "soul-icons/soul.png"

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

    /** `filesDir/soul-icons/soul.png`. */
    fun iconFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "soul-icons"), "soul.png")

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
     * Read path: migrate an inline data-URI icon to a file reference and
     * rewrite SOUL.md once. Returns [file] unchanged when there is nothing to
     * migrate or any step fails (the inline value keeps working).
     */
    fun migrateOnRead(context: Context, file: SoulFile): SoulFile {
        if (!SoulIcon.isInlineImageDataUri(file.metadata.icon)) return file
        val original = file.metadata.icon
        val ref = writeIconFile(context, original) ?: return file
        val migrated = file.copy(metadata = file.metadata.copy(icon = ref))
        return try {
            val target = SoulStore.fileLocation(context)
            val text = SoulMDParser.serialize(migrated)
            if (AtomicFileWrite.write(
                    target,
                    text,
                    temp = File(target.parentFile, "${target.name}.tmp"),
                ) == null
            ) {
                throw IOException("SOUL.md rewrite failed")
            }
            AppLogger.info(
                TAG,
                "[GH#299] migrated inline icon to $ref " +
                    "(${original.length} chars removed from SOUL.md)",
            )
            migrated
        } catch (t: Throwable) {
            AppLogger.warning(
                TAG,
                "[GH#299] icon migration could not rewrite SOUL.md; inline value kept: ${t.message}",
            )
            file
        }
    }

    /**
     * Write path: strip inline images from the body and migrate a data-URI
     * icon, exactly as [migrateOnRead] would — but without a separate SOUL.md
     * rewrite, because [SoulStore.save] is about to serialize this result
     * anyway.
     */
    fun sanitizeForSave(context: Context, file: SoulFile): SoulFile {
        val strippedBody = stripInlineImageDataUris(file.body)
        if (strippedBody != file.body) {
            AppLogger.warning(
                TAG,
                "[GH#299] stripped an inline image payload from the SOUL.md body before saving",
            )
        }
        val currentIcon = file.metadata.icon
        val nextIcon = if (SoulIcon.isInlineImageDataUri(currentIcon)) {
            writeIconFile(context, currentIcon) ?: currentIcon
        } else {
            currentIcon
        }
        return file.copy(
            metadata = file.metadata.copy(icon = nextIcon),
            body = strippedBody,
        )
    }

    /**
     * Decode → downscale to [SoulIcon.STORED_PIXELS] → PNG →
     * `filesDir/soul-icons/soul.png`, returning the frontmatter reference.
     * Every failure is logged and returns null so the caller keeps the
     * original value.
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
}
