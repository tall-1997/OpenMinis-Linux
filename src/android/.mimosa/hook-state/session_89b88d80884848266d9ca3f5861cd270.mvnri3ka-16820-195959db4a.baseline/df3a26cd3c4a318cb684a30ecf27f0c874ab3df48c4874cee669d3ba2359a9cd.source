package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.db.CodeIndexDao
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import java.io.File

/** Query a lightweight persisted code-symbol/call graph. */
object CodeGraphTool {
    const val NAME = "code_graph"

    fun definition() = AgentToolDefinition(
        name = NAME,
        description = "查代码结构：符号定义、调用者、被调用符号和文件符号。首次查询自动建立轻量索引；字面搜索仍用 grep_source。",
        parameters = mapOf(
            "action" to AgentToolParam(type = "string", description = "define | callers | callees | file | status", enumValues = listOf("define", "callers", "callees", "file", "status")),
            "name" to AgentToolParam(type = "string", description = "define/callers/callees 的符号名"),
            "path" to AgentToolParam(type = "string", description = "file 的文件路径"),
        ),
        required = listOf("action"),
        propertyOrdering = listOf("action", "name", "path"),
    )

    suspend fun execute(
        args: Map<String, String>,
        sessionId: String,
        context: Context,
        dao: CodeIndexDao,
        resolvePath: (String) -> String?,
    ): String {
        val root = detectRoot(resolvePath) ?: return "无法确定工作区根目录"
        if (dao.symbolCount(root) == 0) CodeGraphIndexer.rebuild(root, dao)
        return when (args["action"]) {
            "status" -> status(dao, root)
            "define" -> define(dao, root, args["name"])
            "callers" -> callers(dao, root, args["name"])
            "callees" -> callees(dao, root, args["name"])
            "file" -> file(dao, args["path"], resolvePath)
            else -> "action 必须是 define / callers / callees / file / status 之一"
        }
    }

    private fun detectRoot(resolvePath: (String) -> String?): String? {
        val candidates = listOf("/var/minis/workspace", "/var/minis/shared", ".")
        return candidates.asSequence().mapNotNull(resolvePath).map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }?.path
    }

    private suspend fun status(dao: CodeIndexDao, root: String): String =
        "已索引 ${dao.fileCount(root)} 个文件，${dao.symbolCount(root)} 个符号。工作区: $root"

    private suspend fun define(dao: CodeIndexDao, root: String, raw: String?): String {
        val name = raw?.trim().orEmpty(); if (name.isEmpty()) return "action=define 需要 name"
        val hits = dao.findByName(root, name)
        if (hits.isEmpty()) return "索引里没有「$name」；可再用 grep_source。"
        return buildString { appendLine("找到 ${hits.size} 处定义:"); hits.forEach { s -> appendLine("- ${s.kind} ${s.name}  ${s.filePath}:${s.startLine}\n  ${s.signature}") } }
    }

    private suspend fun callers(dao: CodeIndexDao, root: String, raw: String?): String {
        val name = raw?.trim().orEmpty(); if (name.isEmpty()) return "action=callers 需要 name"
        val hits = dao.callersOf(root, name)
        return if (hits.isEmpty()) "索引里没有引用「$name」的地方。" else buildString { appendLine("${hits.size} 处引用了「$name」:"); hits.forEach { appendLine("- ${it.fromName} → ${it.toName}  ${it.filePath}:${it.line}") } }
    }

    private suspend fun callees(dao: CodeIndexDao, root: String, raw: String?): String {
        val name = raw?.trim().orEmpty(); if (name.isEmpty()) return "action=callees 需要 name"
        val hits = dao.calleesOf(root, name)
        return if (hits.isEmpty()) "索引里没有「$name」调用别的符号的记录。" else buildString { appendLine("「$name」调用了:"); hits.forEach { appendLine("- ${it.toName}  ${it.filePath}:${it.line}") } }
    }

    private suspend fun file(dao: CodeIndexDao, raw: String?, resolvePath: (String) -> String?): String {
        val path = raw?.trim().orEmpty(); if (path.isEmpty()) return "action=file 需要 path"
        val resolved = resolvePath(path) ?: path
        val hits = dao.symbolsOf(File(resolved).canonicalPath)
        return if (hits.isEmpty()) "这个文件没有索引记录: $path" else buildString { appendLine("$path 里的符号 (${hits.size}):"); hits.forEach { appendLine("- ${it.startLine}: ${it.kind} ${it.name}  ${it.signature}") } }
    }
}
