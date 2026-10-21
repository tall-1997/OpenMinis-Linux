package com.openminis.app.ui.markdown

import java.io.File

/**
 * Markdown 预览里链接的归类。
 *
 * 网页链接（http/https）与 minis:// 链接原样交给应用内浏览器；
 * 相对路径按当前文件所在目录解析成绝对路径，再核对落在允许的根目录内，
 * 防止 `../` 穿越读到根以外的文件。页内锚点不是可打开的目标。
 */
object MarkdownLinkResolver {

    sealed class Target {
        data class Web(val url: String) : Target()
        data class File(val absolutePath: String) : Target()
    }

    fun resolve(href: String, baseDir: String, allowedRoot: String): Target? {
        val trimmed = href.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val head = trimmed.substringBefore(':')
        // 冒号前是已知 scheme 才当链接。白名单避开了正则转义问题，
        // 也拒绝 javascript: 之类的未知 scheme。
        if (':' in trimmed && head.all { it.isLetter() || it in "+-." }) {
            return if (head in WEB_SCHEMES) Target.Web(trimmed) else null
        }
        val base = File(baseDir)
        val resolved = File(base, trimmed).normalize().path
        val root = File(allowedRoot).normalize().path
        val withinRoot = resolved == root || resolved.startsWith(root + separator)
        return if (withinRoot) Target.File(resolved) else null
    }

    private val WEB_SCHEMES = setOf("http", "https", "minis")
    private val separator = java.io.File.separator
}
