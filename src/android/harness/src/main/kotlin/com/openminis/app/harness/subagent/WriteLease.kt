package com.openminis.app.harness.subagent

import com.openminis.app.harness.HarnessTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Adapted from taixu SubagentLaneContracts 的写租约闸门 (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 子任务写路径**租约**：派发时声明的 write_paths 是该 lane 唯一可写范围，
 * 判定发生在派发层（工具执行前），而不是等工具落盘后再后悔。与只挡路径前缀的
 * ThreadLocal 白名单相比，租约给出的是**可解释的拒绝**——拒绝文案告诉模型为什么
 * 被拦、以及正确的替代动作（把内容作为结论返回由主智能体写入）。
 *
 * 规则（与上游逐条对齐）：
 *  - 非结构化写工具不在租约范围（shell 里的重定向由执行层自己的治理管）；
 *  - 未声明 write_paths = 只读任务，任何结构化写入都拒；
 *  - `*` 或归一化后空串 = 整工作区租约，全放行（不能当「未声明」降级成只读）；
 *  - 目标路径缺省交给 schema 层报错，租约不抢先拦；
 *  - `../` 消解后逃出工作区顶层 = 拒；
 *  - 其余按归一化前缀匹配：`target == scope || target.startsWith("$scope/")`。
 */
data class WriteLease(val scopes: List<String>) {
    // [T-p2-writelease-relative-scope] scope 与 target 同根解析（工作区根）：
    // 两侧基准不一致时，相对声明与相对 target 永远对不上绝对 scope。
    // 整工作区哨兵（`*` / 空串）经解析原样保留。
    val normalized: List<String> = scopes.map { resolveAgainstWorkspaceRoot(it) }

    /** 整工作区租约：`*` 或归一化后空串（`.` 的消解结果）。 */
    val wholeWorkspace: Boolean get() = normalized.any { it == "*" || it.isBlank() }

    companion object {
        /** 未声明 write_paths 的只读 lane。 */
        val NONE = WriteLease(emptyList())
    }
}

sealed interface LeaseVerdict {
    data object Allowed : LeaseVerdict
    data class Denied(val reason: String) : LeaseVerdict
}

object WriteLeaseGate {

    /** 结构化写工具 → 路径参数键；null = 不受租约约束。 */
    fun pathKey(tool: HarnessTool): String? = when (tool) {
        HarnessTool.WRITE, HarnessTool.EDIT -> "path"
        HarnessTool.DOWNLOAD -> "destination"
        else -> null
    }

    fun check(lease: WriteLease, tool: HarnessTool, rawToolName: String?, args: JsonObject): LeaseVerdict {
        // [T-p2-writelease-mcp-gap] mcp__* 检查必须在 pathKey 早退**之前**：MCP
        // 工具映射到 BASE（harnessToolFor 兜底），pathKey(BASE) = null 会直接
        // Allowed，mcp 分支永远不可达。
        if (rawToolName?.startsWith("mcp__") == true) {
            return when {
                lease.wholeWorkspace -> LeaseVerdict.Allowed
                lease.scopes.isEmpty() -> LeaseVerdict.Denied(
                    "本子任务按只读任务执行，MCP 工具的副作用面无法验证，禁止使用。" +
                        "请直接完成分析并把结果作为结论返回；如需 MCP 能力，让主智能体在本会话调用。",
                )
                looksLikeWrite(rawToolName) -> LeaseVerdict.Denied(
                    "MCP 工具 $rawToolName 疑似写操作，且其路径参数不受写租约约束，已拦截。" +
                        "请把需要写入的内容作为结论返回，由主智能体处理。",
                )
                else -> LeaseVerdict.Allowed
            }
        }
        val key = pathKey(tool) ?: return LeaseVerdict.Allowed
        if (lease.scopes.isEmpty()) {
            return LeaseVerdict.Denied(
                "本子任务未声明 write_paths，按只读任务执行，禁止写入工作区。" +
                    "请把需要落盘的完整内容作为结论正文返回，由主智能体写入，" +
                    "或让主智能体在重新派发时声明 write_paths。",
            )
        }
        if (lease.wholeWorkspace) return LeaseVerdict.Allowed
        val rawTarget = (args[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        // [T-p2-writelease-relative-scope] 相对 target 与 scope 同根解析：模型按
        // 拒绝文案改用相对路径时（workspace/reports/x.md），不再因两侧基准不同
        // 永远对不上。
        val target = resolveAgainstWorkspaceRoot(rawTarget)
        // 参数缺失或为空交由执行层按 schema 报错，这里不抢先拦截。
        if (target.isBlank()) return LeaseVerdict.Allowed
        if (target.startsWith("../")) {
            return LeaseVerdict.Denied(
                "路径 $rawTarget 经 ../ 消解后逃出工作区顶层，已拦截。" +
                    "请使用工作区内的路径（如 /var/minis/workspace/…，或相对工作区根的路径）。",
            )
        }
        val allowed = lease.normalized.any { scope -> target == scope || target.startsWith("$scope/") }
        return if (allowed) {
            LeaseVerdict.Allowed
        } else {
            LeaseVerdict.Denied(
                "路径 $target 超出本子任务的写租约范围（${lease.normalized.joinToString("、")}），" +
                    "已拦截以保持并行子任务之间的写隔离。请只在租约范围内写入，或把内容作为结论返回由主智能体处理。",
            )
        }
    }

    /** mcp__ 工具名的写形启发式：只用于受限租约下的保守拦截，不做放行依据。 */
    private fun looksLikeWrite(rawToolName: String): Boolean {
        val lower = rawToolName.lowercase()
        return WRITEISH_HINTS.any { lower.contains(it) }
    }

    private val WRITEISH_HINTS = listOf(
        "write", "save", "create", "insert", "update", "delete", "remove",
        "put", "upload", "replace", "append", "写", "写入", "保存", "创建", "删除",
    )

    /** 被拦截写入的可读描述，用于汇总与完成判定。 */
    fun targetLabel(rawToolName: String?, args: JsonObject): String {
        val target = (args["path"] as? JsonPrimitive)?.contentOrNull
            ?: (args["destination"] as? JsonPrimitive)?.contentOrNull
        return listOf(rawToolName.orEmpty(), target.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
    }
}

/**
 * 任务文字是否要求落盘。主智能体漏传 write_paths 时，只读提示会与任务目标直接
 * 冲突，必须在 Lane 提示词与父汇总里说清楚，而不是让子智能体在「要写」和「不许写」
 * 之间自行猜测。
 *
 * 判定刻意保守：误触发等于给纯分析任务塞误导性提示词。只有两种情况算写意图：
 * 命中无歧义落盘短语；或命中高频动词**且**文中同时出现文件名样式 token。
 * 「修改代码」「修改文件」在纯分析任务里过于高频，一律不算。
 */
fun declaresWriteIntent(taskPrompt: String): Boolean {
    if (EXPLICIT_WRITE_INTENT_MARKERS.any { taskPrompt.contains(it, ignoreCase = true) }) return true
    return AMBIGUOUS_WRITE_INTENT_MARKERS.any { taskPrompt.contains(it, ignoreCase = true) } &&
        FILE_NAME_HINT.containsMatchIn(taskPrompt)
}

private val EXPLICIT_WRITE_INTENT_MARKERS = listOf(
    "落盘", "写入文件", "写到文件", "写文件", "生成文件", "创建文件", "新建文件", "输出文件", "保存文件",
    "write the file", "write to file", "save to file",
)

private val AMBIGUOUS_WRITE_INTENT_MARKERS = listOf("写入", "写到", "写进", "保存到", "存到", "输出到", "save to")

/** `report.md`、`Main.kt` 这类「名字.扩展名」token，给高频动词加落盘目标的佐证。 */
private val FILE_NAME_HINT = Regex("""[\w./\\-]+\.[A-Za-z0-9]{1,8}(?![\w.])""")
