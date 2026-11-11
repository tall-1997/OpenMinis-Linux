package com.openminis.app.backup

import com.openminis.app.agent.SoulStore
import java.io.File

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

    /**
     * 打包 [source] 整树到 [dest]（`data/memory` 目录），逐文件原样复制。
     *
     * 递归是 [GH#299] 的关键：头像在 `soul-icons/` 子目录里，只复制顶层文件
     * 会再次把它漏掉。
     */
    fun export(source: File, dest: File): BackupManifest.CategoryStat {
        dest.mkdirs()
        var entries = 0
        var bytes = 0L
        if (source.isDirectory) {
            for (file in source.walkTopDown().filter { it.isFile }) {
                val rel = file.relativeTo(source).path.replace(File.separatorChar, '/')
                val out = File(dest, rel).apply { parentFile?.mkdirs() }
                file.copyTo(out, overwrite = true)
                entries += 1
                bytes += file.length()
            }
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
        dest.mkdirs()
        val destRoot = dest.canonicalFile
        for (file in src.walkTopDown().filter { it.isFile }) {
            val rel = file.relativeTo(src).path.replace(File.separatorChar, '/')
            val out = File(dest, rel)
            // Same containment rule as the blob path: `data/memory` names come
            // from inside the package too.
            val parent = out.parentFile?.let { it.mkdirs(); it.canonicalFile }
            if (parent == null || !(parent.path == destRoot.path ||
                    parent.path.startsWith(destRoot.path + File.separator))
            ) {
                report.rejectedPaths += 1
                continue
            }
            file.copyTo(out, overwrite = true)
            report.filesWritten += 1
            report.bytesWritten += file.length()
        }
        return report
    }
}
