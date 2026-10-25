package com.openminis.app.tools

import android.util.Log
import com.openminis.app.data.db.CodeEdgeEntity
import com.openminis.app.data.db.CodeIndexDao
import com.openminis.app.data.db.CodeSymbolEntity
import java.io.File

/** Lightweight, no-JNI source indexer. Results are hints; grep_source remains the literal-search fallback. */
object CodeGraphIndexer {
    private const val TAG = "CodeGraphIndexer"
    private const val MAX_FILES = 3_000
    private const val MAX_FILE_BYTES = 1_048_576L
    private val skipDirs = setOf(".git", ".svn", ".hg", "node_modules", "build", "out", "dist", "target", ".gradle", ".idea", "vendor", "__pycache__", ".venv", "venv", "Pods", ".next", ".nuxt", "coverage")
    private val languages = mapOf("kt" to "kotlin", "java" to "java", "py" to "python", "ts" to "typescript", "tsx" to "typescript", "js" to "typescript", "jsx" to "typescript")
    private val ignoredCalls = setOf("if", "else", "when", "for", "while", "return", "try", "catch", "throw", "class", "fun", "new", "require", "check", "print", "println", "super", "this")

    suspend fun rebuild(root: String, dao: CodeIndexDao): Int {
        val dir = File(root).canonicalFile
        if (!dir.isDirectory) return 0
        dao.clearSymbols(dir.path)
        dao.clearEdges(dir.path)
        var files = 0
        var total = 0
        dir.walkTopDown().onEnter { it.name !in skipDirs && (!it.name.startsWith(".") || it == dir) }.forEach { file ->
            if (files >= MAX_FILES || !file.isFile || file.length() > MAX_FILE_BYTES) return@forEach
            val lang = languages[file.extension.lowercase()] ?: return@forEach
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            files++
            val (symbols, edges) = extract(file.canonicalPath, text, lang, dir.path)
            if (symbols.isNotEmpty()) dao.insertSymbols(symbols)
            if (edges.isNotEmpty()) dao.insertEdges(edges)
            total += symbols.size
        }
        Log.i(TAG, "indexed $files files, $total symbols in ${dir.path}")
        return total
    }

    private fun extract(path: String, content: String, lang: String, root: String): Pair<List<CodeSymbolEntity>, List<CodeEdgeEntity>> {
        val symbols = mutableListOf<CodeSymbolEntity>()
        val edges = mutableListOf<CodeEdgeEntity>()
        var current = "<file>"
        val seen = mutableSetOf<String>()
        val funRegex = when (lang) {
            "python" -> Regex("""^\s*def\s+(\w+)\s*\(""")
            "typescript" -> Regex("""(?:function\s+|(?:const|let|var)\s+)(\w+)\s*(?:=\s*(?:async\s*)?\([^)]*\)\s*=>|\()""")
            else -> Regex("""(?:fun\s+)(\w+)\s*\(""")
        }
        val classRegex = when (lang) {
            "python" -> Regex("""^\s*class\s+(\w+)""")
            else -> Regex("""\b(?:class|object|interface|enum\s+class)\s+(\w+)""")
        }
        content.lineSequence().forEachIndexed { index, line ->
            val lineNo = index + 1
            val found = funRegex.find(line)?.groupValues?.getOrNull(1)?.let { it to "function" }
                ?: classRegex.find(line)?.groupValues?.getOrNull(1)?.let { it to "class" }
            if (found != null) {
                current = found.first
                if (seen.add("${found.second}:${found.first}:$lineNo")) symbols += CodeSymbolEntity(
                    root = root, filePath = path, name = found.first, qualifiedName = path.removePrefix(root).trimStart('/').substringBeforeLast('.').replace('/', '.'),
                    kind = found.second, startLine = lineNo, endLine = lineNo, signature = line.trim().take(180)
                )
            }
            val trimmed = line.trim()
            if (!trimmed.startsWith("//") && !trimmed.startsWith("#") && !trimmed.startsWith("*") && !trimmed.startsWith("@")) {
                Regex("""\b([A-Za-z_]\w*)\s*\(""").findAll(line).map { it.groupValues[1] }
                    .filter { it !in ignoredCalls && it != current && it.length > 1 }
                    .distinct().forEach { target -> edges += CodeEdgeEntity(root = root, filePath = path, kind = "calls", fromName = current, toName = target, line = lineNo) }
            }
        }
        return symbols to edges
    }
}
