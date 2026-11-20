package com.openminis.app.backup

import com.openminis.app.agent.SoulStore
import com.openminis.app.data.repository.MemoryDiaryFormat
import com.openminis.app.data.repository.MemoryLifecycle
import com.openminis.app.logging.AppLogger
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

/**
 * [GH#299] MEMORY 类别的包内布局：备份包里是 `data/memory` 整树，设备上是
 * `<filesDir>/minis-global/memory` 整树。
 *
 * ## 为什么单独抽出来
 *
 * 第二轮补修把 SOUL.md 的头像迁移到 `filesDir/soul-icons/soul.png`，而 MEMORY
 * 类别只打包 `minis-global/memory`，于是"备份 → 清空 → 恢复"之后
 * `SoulIcon.decode` 找不到文件、头像回退 sparkle。这里把 memory 树的打包/还原
 * 收敛成**一份**实现，exporter、importer 和闭环测试共用，避免三处各自拼路径
 * 再次漂移：
 *
 *  - 根目录用 [SoulStore.MEMORY_SUBDIR]，SOUL.md 和它的头像必然在同一个备份根；
 *  - 打包/还原都递归（`walkTopDown`），头像所在子目录
 *    `soul-icons/soul.png` 因此自动随包进出，不需要新增备份类别、也不改
 *    `.minisbak` 格式（iOS 兼容）。
 *
 * 这不改变包内布局：仍然是 `data/memory/<相对路径>`，旧包照常可恢复。
 */
internal object BackupMemoryTree {

    /** 设备侧 memory 根（`filesDir/minis-global/memory`）。 */
    fun deviceRoot(filesDir: File): File = File(filesDir, SoulStore.MEMORY_SUBDIR)

    private const val PAIRS = ".diary-pairs.json"
    private fun metadata(root: File, source: String): File = File(root, "${MemoryLifecycle.SIDECAR_DIR}/$source.json")

    fun isMemoryTemp(relative: String): Boolean =
        relative.substringAfterLast('/').let { it.startsWith(".memory-") && it.endsWith(".minis-tmp") }

    /**
     * 打包 [source] 整树到 [dest]（`data/memory` 目录），逐文件原样复制。
     *
     * 递归是 [GH#299] 的关键：头像在 `soul-icons/` 子目录里，只复制顶层文件
     * 会再次把它漏掉。
     */
    fun export(
        source: File,
        dest: File,
        maxDiaryBytes: Long? = null,
        onSkip: (String, Long) -> Unit = { _, _ -> },
    ): BackupManifest.CategoryStat {
        dest.mkdirs()
        var entries = 0
        var bytes = 0L
        val pairs = JSONObject()
        val markdownTimes = JSONObject()
        if (source.isDirectory) MemoryLifecycle.locked(source) { root ->
            for (file in treeFiles(root)) {
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (rel == PAIRS || isMemoryTemp(rel) || BackupDiaryPairs.isInternalRestorePath(rel)) continue
                val member = BackupDiaryPairs.globalMember(rel)
                if (member == null) {
                    val out = File(dest, rel)
                    if (BackupDiaryPairs.isMemoryMarkdown(rel)) {
                        BackupDiaryPairs.copyWithTime(file, out)
                        markdownTimes.put(rel, out.lastModified())
                    } else file.copyTo(out, overwrite = true)
                    entries++
                    bytes += file.length()
                } else if (member.member == BackupDiaryPairs.Member.DIARY) {
                    val sidecar = metadata(dest, member.source)
                    val body = File(dest, rel)
                    val included = try {
                        val pair = MemoryLifecycle.snapshotFiles(root, member.source)
                        clearPairOutputs(body, sidecar)
                        if (pair.diary == null) continue
                        val sizes = listOfNotNull(pair.diary, pair.metadata).map { it.length() }
                        if (maxDiaryBytes != null && sizes.any { it > maxDiaryBytes }) {
                            onSkip("data/memory/$rel", sizes[0])
                            sizes.getOrNull(1)?.let { onSkip("data/memory/${sidecar.relativeTo(dest).invariantSeparatorsPath}", it) }
                            continue
                        }
                        BackupDiaryPairs.withSnapshot(pair, member.source, dest) { staged ->
                            val diary = checkNotNull(staged.diary)
                            val metadata = staged.metadata
                            if (maxDiaryBytes != null && listOfNotNull(diary, metadata).any { it.length() > maxDiaryBytes }) {
                                throw java.io.IOException("Diary pair changed size")
                            }
                            MemoryLifecycle.validateBackupPair(diary, metadata, requireMatchingEntries = false)
                            BackupDiaryPairs.copyWithTime(diary, body)
                            metadata?.let { BackupDiaryPairs.copyWithTime(it, sidecar) }
                            pairs.put(member.source, JSONObject().apply {
                                put("diary", BackupDiaryPairs.fingerprint(diary))
                                put("metadata", metadata?.let(BackupDiaryPairs::fingerprint) ?: BackupDiaryPairs.NO_METADATA)
                                put("diary_mtime_ms", diary.lastModified())
                                metadata?.let { put("metadata_mtime_ms", it.lastModified()) }
                            })
                            listOfNotNull(body, sidecar.takeIf { metadata != null })
                        }
                    } catch (e: Exception) {
                        // If an incomplete staged pair cannot be removed, abort packaging it.
                        clearPairOutputs(body, sidecar)
                        onSkip("data/memory/$rel", file.length())
                        val sourceMeta = metadata(root, member.source)
                        if (BackupDiaryPairs.exists(sourceMeta)) {
                            onSkip("data/memory/${sourceMeta.relativeTo(root).invariantSeparatorsPath}", sourceMeta.length())
                        }
                        AppLogger.warning("Backup", "Cannot include diary $rel: ${e.message}")
                        continue
                    }
                    entries += included.size
                    bytes += included.sumOf { it.length() }
                }
            }
        }
        if (source.isDirectory) {
            val inventory = JSONObject().put("version", 1).put("pairs", pairs).put("markdown_mtime_ms", markdownTimes)
            File(dest, PAIRS).writeText(inventory.toString())
        }
        return BackupManifest.CategoryStat(entries, bytes, encrypted = false)
    }

    /**
     * 把 [src]（包内 `data/memory`）整树还原到 [dest]（设备 memory 根）。
     *
     * 保留原有的 canonical-path containment 校验：`data/memory` 里的名字来自
     * 包内，`../` 或符号链接不能把写入带出 memory 根。
     */
    fun restore(src: File, dest: File): BackupImporter.CategoryReport {
        val report = BackupImporter.CategoryReport(BackupCategory.MEMORY.key)
        if (!src.isDirectory) return report
        val inventory = File(src, PAIRS)
        val descriptor = if (!BackupDiaryPairs.exists(inventory)) null else try {
            require(inventory.isFile && inventory.canonicalFile == inventory.absoluteFile)
            JSONObject(inventory.readText()).also {
                require(it.getInt("version") == 1)
                it.getJSONObject("pairs")
            }
        } catch (_: Exception) {
            report.rejectedPaths++
            JSONObject() // An invalid inventory is not a legacy package.
        }
        val bound = descriptor?.optJSONObject("pairs") ?: descriptor?.let { JSONObject() }
        val markdownTimes = descriptor?.optJSONObject("markdown_mtime_ms")
        val diarySources = linkedSetOf<String>()
        bound?.keys()?.forEach { diarySources += it }
        MemoryLifecycle.locked(dest) { destRoot ->
            for (file in treeFiles(src)) {
                val rel = file.relativeTo(src).invariantSeparatorsPath
                if (rel == PAIRS || isMemoryTemp(rel) || BackupDiaryPairs.isInternalRestorePath(rel)) continue
                val member = BackupDiaryPairs.globalMember(rel)
                if (member != null) {
                    diarySources += member.source
                    continue
                }
                val out = File(destRoot, rel)
                if (out.canonicalFile != out.absoluteFile || !out.path.startsWith(destRoot.path + File.separator)) {
                    report.rejectedPaths++
                    continue
                }
                if (BackupDiaryPairs.isMemoryMarkdown(rel)) {
                    try {
                        BackupDiaryPairs.restoreMarkdown(file, out, markdownTimes?.mtime(rel))
                    } catch (e: Exception) {
                        report.rejectedPaths++
                        AppLogger.warning("Restore", "Kept previous memory file $rel: ${e.javaClass.simpleName}")
                        continue
                    }
                } else file.copyTo(out, overwrite = true)
                report.filesWritten++
                report.bytesWritten += file.length()
            }
        }
        for (source in diarySources) {
            try {
                require(MemoryDiaryFormat.dateFile.matches(source))
                val body = File(src, source)
                require(body.isFile && body.canonicalFile == body.absoluteFile) { "Missing diary" }
                val sidecar = metadata(src, source).takeIf(BackupDiaryPairs::exists)
                require(sidecar == null || sidecar.isFile && sidecar.canonicalFile == sidecar.absoluteFile)
                val binding = bound?.getJSONObject(source)
                BackupDiaryPairs.publish(dest, source, body, sidecar, legacy = bound == null,
                    diaryMtimeMs = binding?.mtime("diary_mtime_ms"), metadataMtimeMs = binding?.mtime("metadata_mtime_ms"),
                ) { staged ->
                    if (binding == null) return@publish
                    BackupDiaryPairs.verify(checkNotNull(staged.diary), binding.getString("diary"))
                    val hash = binding.getString("metadata")
                    if (staged.metadata == null) require(hash == BackupDiaryPairs.NO_METADATA)
                    else BackupDiaryPairs.verify(staged.metadata, hash)
                }
                report.filesWritten += if (sidecar == null) 1 else 2
                report.bytesWritten += body.length() + (sidecar?.length() ?: 0L)
            } catch (e: Exception) {
                report.rejectedPaths++
                AppLogger.warning("Restore", "Kept previous diary $source: ${e.message}")
            }
        }
        return report
    }

    private fun JSONObject.mtime(key: String): Long? = if (isNull(key)) null else getLong(key)

    private fun treeFiles(root: File): Sequence<File> = root.walkTopDown().onEnter { directory ->
        !Files.isSymbolicLink(directory.toPath()) &&
            (directory == root || !BackupDiaryPairs.isInternalRestorePath(directory.relativeTo(root).invariantSeparatorsPath))
    }.filter { Files.isRegularFile(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }

    private fun clearPairOutputs(body: File, sidecar: File) {
        Files.deleteIfExists(body.toPath())
        Files.deleteIfExists(sidecar.toPath())
    }
}
