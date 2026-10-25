package com.openminis.app.tools

/**
 * [T-mindmap-team-import] Turn a mind map (Mermaid `mindmap` block or
 * indentation tree) into an org structure / team — mirroring shiyi-agent's
 * "group chat as org structure" idea: a drawn mind map becomes named roles
 * with duties, without hand-typing each CollabRoles card.
 *
 * ## Supported shapes
 *
 * 1. Mermaid:
 *    ```
 *    mindmap
 *      root((产品团队))
 *        PM[产品经理]
 *        Architect[架构师]
 *          sub1[数据组]
 *        Dev[工程师]
 *    ```
 * 2. Plain indented tree (tabs or 2/4 spaces):
 *    ```
 *    产品团队
 *      产品经理 — 定需求划优先级
 *      Dev: 实现与测试
 *    ```
 *
 * ## Mapping rules
 *
 * - The root node (depth 0) becomes the team/room name.
 * - Depth-1 nodes become members. A node whose subtree is non-empty becomes a
 *   sub-team (org unit) with its children as members — the shiyi org model.
 * - The `[label]` part (Mermaid brackets) or text after `—`/`: ` becomes the
 *   role's duty description.
 * - Deterministic fallback duty is synthesized when none is provided, so the
 *   import always yields a usable roster.
 *
 * The parser is a pure function; it never touches prefs or the roster itself.
 * Callers persist the result (e.g. as CollabRoles custom roles).
 */
object MindMapTeamImport {

    private data class Node(
        val name: String,
        val duty: String,
        var depth: Int,
        val children: MutableList<Node> = mutableListOf(),
    )

    data class ImportedTeam(
        val roomName: String,
        val blurb: String,
        val members: List<ImportedMember>,
    ) {
        data class ImportedMember(
            val name: String,
            val description: String,
            val unit: String = "",
            /** True when this member heads a sub-team (its children are members too). */
            val isSubTeamLead: Boolean = false,
        )
    }

    /**
     * Parse a mind map. Returns null when the text is not recognized as a map
     * (no root, or no depth-1 members).
     */
    fun parse(raw: String): ImportedTeam? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val lines = if (isMermaid(text)) parseMermaidLines(text) else text.lines()

        // Indentation →
        //   Mermaid: `  root((产品团队))` or `    PM[产品经理]`
        //   Plain:   leading spaces/tabs
        val root = buildForest(lines) ?: return null
        if (root.children.isEmpty()) return null

        val members = mutableListOf<ImportedTeam.ImportedMember>()
        for (child in root.children) {
            collectMembers(child, members)
        }
        return ImportedTeam(
            roomName = root.name,
            blurb = "由思维导图导入的组织结构（${members.size} 个成员）",
            members = members,
        )
    }

    private fun isMermaid(text: String): Boolean =
        text.lineSequence().any { it.trim().startsWith("mindmap") }

    private fun parseMermaidLines(text: String): List<String> {
        val lines = mutableListOf<String>()
        text.lines().forEach { raw ->
            if (raw.isBlank()) return@forEach
            val line = raw.trimEnd()
            if (line.trim() == "mindmap") return@forEach
            // strip trailing `;`; keep leading indentation (the tree parser
            // derives depth from it)
            lines.add(line.removeSuffix(";"))
        }
        return lines
    }

    /**
     * `id[duty]` / `id((duty))` / `id(duty)` → (name, duty).
     *
     * Mermaid's bracket forms are `id[label]` where the id is a bare token and
     * the label is the display text. For an org roster the LABEL is the useful
     * name (`PM[产品经理]` → member 产品经理, not PM), so the inside text wins;
     * an empty label falls back to the id. Plain `name — duty` / `name: duty`
     * splits the duty out of the name.
     */
    private fun splitMermaidLabel(raw: String): Pair<String, String> {
        val text = raw.trim()
        val openers = listOf("[", "(((", "((", "(", ")))", "))")
        for (opener in openers) {
            val idx = text.indexOf(opener)
            if (idx < 0) continue
            val id = text.substring(0, idx).trim()
            val closer = when {
                opener == "[" -> "]"
                opener.startsWith("((") && opener.length == 3 -> ")))"
                opener.startsWith("((") -> "))"
                else -> ")"
            }
            val closeIdx = text.indexOf(closer, idx + opener.length)
            val inner = if (closeIdx >= 0) text.substring(idx + opener.length, closeIdx).trim() else ""
            // Inside text is the display name; empty → fall back to the id.
            if (inner.isNotEmpty()) return inner to ""
            return id to ""
        }
        // fallback: name — duty | name : duty
        return splitPlain(raw)
    }

    private fun splitPlain(raw: String): Pair<String, String> {
        val text = raw.trim()
        for (sep in listOf(" — ", " – ", " - ", "：", ": ", "：")) {
            val idx = text.indexOf(sep)
            if (idx > 0) return (text.substring(0, idx).trim() to text.substring(idx + sep.length).trim())
        }
        return (text to "")
    }

    private fun buildForest(lines: List<String>): Node? {
        val trimmed = lines.map { it.trimEnd() }.filter { it.isNotBlank() }
        if (trimmed.isEmpty()) return null
        val parsed = trimmed.mapNotNull { line ->
            val indent = line.takeWhile { it == ' ' || it == '\t' }.replace("\t", "  ").length
            val body = line.trimStart()
            val (name, duty) = splitMermaidLabel(body)
            if (name.isEmpty()) null else Node(name, duty, indent / 2)
        }
        if (parsed.isEmpty()) return null

        // depth 0 = root. If no depth-0 node, synthesize one from the shallowest.
        val rootDepth = parsed.minOf { it.depth }
        if (rootDepth > 0) {
            return Node(parsed.first().name, parsed.first().duty, 0).also { root ->
                parsed.forEach { it.depth -= rootDepth }
                attach(parsed, root)
            }
        }

        val rootN = parsed.firstOrNull { it.depth == 0 } ?: return null
        val root = Node(rootN.name, rootN.duty, 0)
        attach(parsed.drop(1), root)
        return root
    }

    private fun attach(nodes: List<Node>, root: Node) {
        var current = root
        for (n in nodes) {
            if (n.depth <= 0) continue
            if (n.depth == current.depth + 1) {
                current.children.add(n)
                current = n
            } else if (n.depth > current.depth + 1) {
                // A deeper node with no parent at depth+1: attach under the last
                // node at the preceding depth (best effort).
                current.children.add(n)
                current = n
            } else {
                // back up to the ancestor at (n.depth - 1)
                val target = findAncestor(root, n.depth - 1) ?: root
                target.children.add(n)
                current = n
            }
        }
    }

    private fun findAncestor(root: Node, depth: Int): Node? {
        if (root.depth == depth) return root
        for (c in root.children) findAncestor(c, depth)?.let { return it }
        return null
    }

    private fun collectMembers(node: Node, out: MutableList<ImportedTeam.ImportedMember>) {
        collect(node, "", out)
    }

    /** [unit] = the nearest ancestor with children (the org unit this node reports to). */
    private fun collect(node: Node, unit: String, out: MutableList<ImportedTeam.ImportedMember>) {
        if (node.children.isEmpty()) {
            out.add(
                ImportedTeam.ImportedMember(
                    name = node.name,
                    description = node.duty.ifBlank { defaultDuty(node.name) },
                    unit = unit,
                ),
            )
        } else {
            // Sub-team lead + its children as members.
            out.add(
                ImportedTeam.ImportedMember(
                    name = node.name,
                    description = node.duty.ifBlank { "${if (node.children.size > 4) "组长" else "负责人"} · 带 ${node.children.size} 个下属单元" },
                    unit = unit,
                    isSubTeamLead = true,
                ),
            )
            node.children.forEach { collect(it, node.name, out) }
        }
    }

    /**
     * Deterministic fallback duty for a member with no description, so the
     * roster is always usable.
     */
    fun defaultDuty(name: String): String = when {
        name.contains("测试") || name.contains("QA") || name.contains("tester", true) ->
            "验证产出、找边界、把关质量"
        name.contains("产品") || name.contains("PM") || name.contains("运营") ->
            "定需求、划优先级、砍范围"
        name.contains("架构") || name.contains("architect", true) ->
            "系统设计、技术选型、非功能约束"
        name.contains("设计") || name.contains("design", true) ->
            "界面、交互、用户体验"
        name.contains("前端") || name.contains("前端工程师") ->
            "实现前端界面与交互"
        name.contains("后端") || name.contains("服务端") ->
            "实现后端逻辑与接口"
        name.contains("数据") || name.contains("data", true) ->
            "数据收集、分析与建模"
        name.contains("秘书") || name.contains("助理") || name.contains("记录") ->
            "主持、记录结论、追未完成项"
        name.contains("运维") || name.contains("部署") || name.contains("devops", true) ->
            "构建、部署、监控与故障恢复"
        else -> "在职责范围内执行、给出结论并主动同步风险"
    }
}