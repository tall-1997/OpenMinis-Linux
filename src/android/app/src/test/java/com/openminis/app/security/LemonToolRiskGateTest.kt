package com.openminis.app.security

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-lemon-risk] 柠檬工具的风险等级必须真的进入安全门。
 *
 * ## 为什么单独一个测试文件
 *
 * `LemonTools` 把渲染好的 shell 命令交给 `McpToolShared.gateAndRun`，
 * 后者调 `SecurityGate.classify("lemon_xxx", {"command": "..."})`。这条路有两处
 * 会静默失效，而且**两处都不会让编译失败**：
 *
 *  1. `commandRisk()` 只对 `SHELL_TOOLS` 判风险，其余返回 NORMAL →
 *     GUARDED 分支（只拦 DANGEROUS）永远不会触发，43 个高危工具被放行。
 *  2. `extractCommand()` 对未知工具走 `cmd.toolArgs`（原始 JSON）→
 *     `classifyRisk` 分词后第一个 token 是 `{"command":"rm`，
 *     `argv.first() == "rm"` 不成立 → 仍然判 NORMAL。
 *
 * 只修 1 不修 2 等于没修。这两个用例把两条路都钉住。
 */
class LemonToolRiskGateTest {

    private val gate = SecurityGateImpl()

    /** 模拟 LemonTools 交给门的东西：name + 渲染后的命令体。 */
    private fun lemonCall(toolName: String, command: String): GateCommand =
        gate.classify("lemon_$toolName", JSONObject().put("command", command).toString())

    // ── 高危命令：GUARDED 必须拦住 ────────────────────────────────────────

    @Test
    fun guardedStopsDestructiveLemonCommand() {
        val cmd = lemonCall("fs_rm", "rm -rf /data/data/com.example")
        val d = gate.decide(cmd, PermissionMode.GUARDED)
        assertTrue(
            "GUARDED 必须拦住 rm -rf /data/data/*，实际 ${d::class.simpleName}",
            d is Decision.NeedConfirm,
        )
    }

    @Test
    fun guardedStopsSelinuxDisable() {
        val cmd = lemonCall("selinux_bool_set", "setenforce 0")
        val d = gate.decide(cmd, PermissionMode.GUARDED)
        assertTrue(
            "GUARDED 必须拦住 setenforce 0，实际 ${d::class.simpleName}",
            d is Decision.NeedConfirm,
        )
    }

    // ── 只读命令：GUARDED 自动放行 ────────────────────────────────────────

    @Test
    fun guardedAllowsReadOnlyLemonCommand() {
        val cmd = lemonCall("kernel_version", "uname -a")
        val d = gate.decide(cmd, PermissionMode.GUARDED)
        assertTrue(
            "只读柠檬工具在自主模式下应自动放行，实际 ${d::class.simpleName}",
            d is Decision.Allow,
        )
    }

    // ── 命令体必须被解出来（否则上面三条都是假象）────────────────────────

    @Test
    fun commandBodyIsExtractedFromLemonArgs() {
        // 这条直接验证 extractCommand 的效果：同样的 shell 命令，
        // 通过 lemon_ 前缀传入时判出的风险等级必须与 shell_exec 一致。
        val viaShell = gate.classifyRisk("rm -rf /data/data/com.example")
        val viaLemon = lemonCall("fs_rm", "rm -rf /data/data/com.example")
        val d = gate.decide(viaLemon, PermissionMode.GUARDED)
        assertTrue(
            "lemon 传入的命令体应被解析出 DANGEROUS（shell 侧判级=$viaShell）",
            d is Decision.NeedConfirm,
        )
    }

    // ── 三档对同一高危命令的差异 ─────────────────────────────────────────

    @Test
    fun fullAccessStillAllowsDangerousLemonCommand() {
        val cmd = lemonCall("fs_rm", "rm -rf /data/data/com.example")
        val d = gate.decide(cmd, PermissionMode.ALLOW_ALL)
        assertTrue(
            "完全访问下应放行（审计仍记录），实际 ${d::class.simpleName}",
            d is Decision.Allow,
        )
    }

    @Test
    fun askConfirmsLemonCalls() {
        val cmd = lemonCall("kernel_version", "uname -a")
        val d = gate.decide(cmd, PermissionMode.ASK)
        assertTrue(
            "变更前确认下柠檬调用需确认（lemon_* 不在只读白名单）",
            d is Decision.NeedConfirm,
        )
    }
}
