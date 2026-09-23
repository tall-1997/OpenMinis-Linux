package com.openminis.app.security

import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default SecurityGate.
 *
 * Adapted from XINCODE-Public SecurityGateImpl (GPL-3.0-or-later)
 * https://github.com/kusesad-1122/XINCODE-Public
 *
 * Audit is an in-memory sha256 hash chain (no Room). Tests can tamper entries.
 */
class SecurityGateImpl : SecurityGate {

    @Volatile
    private var _permissionMode = PermissionMode.ASK

    override fun setPermissionMode(mode: PermissionMode) { _permissionMode = mode }
    override fun getPermissionMode(): PermissionMode = _permissionMode

    @Volatile
    private var permissionRules: List<PermissionRule> = emptyList()

    override fun setPermissionRules(rules: List<PermissionRule>) {
        permissionRules = rules
    }

    @Volatile
    private var authorityProfile: PermissionProfile? = null

    override fun setAuthorityProfile(profile: PermissionProfile?) {
        authorityProfile = profile
    }

    /**
     * Caller chat session for the decision in flight.
     *
     * ThreadLocal rather than a field: the offload IPC worker and the chat loop
     * can classify concurrently, and a stale value would deny the caller's own
     * tree (or worse, allow someone else's). [withCallerSession] is the only
     * writer, so the value cannot outlive the call it belongs to.
     */
    private val callerSession = ThreadLocal<String?>()

    /**
     * Whether the guest `/sdcard` bind mount exists right now.
     *
     * Injected by the app because the gate has no Context, and
     * `PRootKernel.shellSharedStorageBinds` is the single source of truth for
     * that plan. Left null the /sdcard rule is skipped rather than guessed.
     */
    @Volatile
    var sdcardMounted: (() -> Boolean)? = null

    /** Run [block] with [sessionId] visible to the path policies inside it. */
    fun <T> withCallerSession(sessionId: String?, block: () -> T): T {
        val previous = callerSession.get()
        callerSession.set(sessionId)
        try {
            return block()
        } finally {
            callerSession.set(previous)
        }
    }

    private val auditTrail = CopyOnWriteArrayList<AuditEntry>()

    companion object {
        val READ_ONLY_TOOLS = setOf(
            "file_read", "list_dir", "grep", "grep_source", "glob",
            "web_search", "web_fetch", "search_sessions", "read_session",
            "memory_get", "recall_memory", "read_image", "describe_image",
            "code_graph", "browser_use",
        )
        val WRITE_TOOLS = setOf(
            "file_write", "file_edit", "multi_edit", "su_exec",
        )
        val SHELL_TOOLS = setOf("shell_exec", "shell_execute", "su_exec", "env_exec")
        val COORDINATOR_TOOLS = setOf(
            "spawn_agent", "run_subagent", "dispatch_agents", "wolfpack_run",
            "agent_plan", "cronjob", "ask_user_question", "invoke_skill",
            "skill_manage", "memory_write", "memory_get", "save_memory",
            "recall_memory", "ask_reasoning",
        )
        val SAFE_COMMANDS = setOf(
            "ls", "cat", "pwd", "whoami", "id", "echo", "grep", "rg", "egrep", "fgrep",
            "head", "tail", "find", "stat", "file", "wc", "date", "uname", "df", "du",
            "ps", "env", "printenv", "which", "type", "basename", "dirname", "realpath",
            "readlink", "sort", "uniq", "cut", "tr", "diff", "cmp", "md5sum",
            "sha1sum", "sha256sum", "getprop", "true", "test",
        )
        val SAFE_GIT_SUB = setOf(
            "status", "log", "diff", "show", "branch", "rev-parse",
            "remote", "ls-files", "blame",
        )
    }

    override fun classify(toolName: String, toolArgs: String): GateCommand {
        val name = ToolAliases.canonical(toolName)
        return when (name) {
            "su_exec" -> classifyShellCommand(name, toolArgs).copy(capability = Capability.SYSTEM)
            "shell_exec", "shell_execute", "env_exec" -> classifyShellCommand(
                if (name == "shell_execute") "shell_exec" else name,
                toolArgs,
            ).let { if (name == "env_exec") it.copy(capability = Capability.TERMINAL) else it }
            "file_read" -> GateCommand(name, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "只读文件操作，可逆")
            "file_write" -> GateCommand(name, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "文件写入可回滚")
            "file_edit", "multi_edit" -> GateCommand(name, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "文件局部编辑可回滚")
            "list_dir", "grep", "grep_source", "glob" ->
                GateCommand(name, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "只读文件/目录操作，可逆")
            "web_search", "web_fetch" -> GateCommand(name, toolArgs, Capability.NET, Reversibility.REVERSIBLE, "只读网络")
            "execute_code", "code_exec" ->
                GateCommand(name, toolArgs, Capability.SYSTEM, Reversibility.REVERSIBLE, "沙箱代码执行")
            "spawn_agent", "run_subagent", "dispatch_agents", "wolfpack_run" ->
                GateCommand(name, toolArgs, Capability.SYSTEM, Reversibility.REVERSIBLE, "派发子代理")
            "agent_plan", "cronjob", "invoke_skill", "skill_manage", "ask_reasoning",
            "memory_write", "memory_get", "ask_user_question",
            -> GateCommand(name, toolArgs, Capability.SYSTEM, Reversibility.REVERSIBLE, "会话协调工具")
            else -> GateCommand(name, toolArgs, Capability.UNKNOWN, Reversibility.IRREVERSIBLE, "未知工具类型，默认不可逆")
        }
    }

    override fun classifyRisk(command: String): RiskLevel {
        var worst = RiskLevel.NORMAL
        val units = riskUnits(command).ifEmpty { listOf(command) }
        for (unit in units) {
            when (classifySegment(unit)) {
                RiskLevel.FATAL_BANNED -> return RiskLevel.FATAL_BANNED
                RiskLevel.DANGEROUS -> worst = RiskLevel.DANGEROUS
                else -> {}
            }
        }
        if (worst == RiskLevel.NORMAL && containsShellExpansionSyntax(command)) {
            return RiskLevel.DANGEROUS
        }
        return worst
    }

    private fun classifySegment(raw: String): RiskLevel {
        val unwrapped = raw.replace(Regex("\\$\\{IFS\\}"), " ")
        val argv = tokenizeCommand(unwrapped)
        val joined = argv.joinToString(" ")
        val systemPaths = listOf(
            "/system", "/system_ext", "/vendor", "/product", "/odm", "/boot", "/recovery",
        ) + APP_DATA_ROOTS
        val destructiveOps = listOf("rm ", "rm\t", "dd ", "wipe", "format", "shred")
        if (systemPaths.any { p -> argv.any { it == p || it.startsWith("$p/") } || joined.contains(p) } &&
            destructiveOps.any { joined.contains(it) || raw.contains(it) }
        ) {
            return RiskLevel.FATAL_BANNED
        }
        if (listOf("parted", "fdisk", "mkfs", "/dev/block/", "fastboot", "flash_image").any {
                argv.contains(it) || joined.contains(it)
            }
        ) {
            return RiskLevel.FATAL_BANNED
        }
        val hasRm = argv.firstOrNull() == "rm" || argv.contains("rm")
        val hasRf = argv.any { it == "-rf" || it == "-fr" || (it.startsWith("-") && it.contains("r") && it.contains("f")) }
        if (hasRm && hasRf && argv.any { it == "/" || it == "/*" }) {
            return RiskLevel.FATAL_BANNED
        }
        if (hasRm && hasRf && argv.any { it == "/data" || it == "/data/" } &&
            argv.none { it.startsWith("/data/data/") || it.startsWith("/data/local/") }
        ) {
            return RiskLevel.FATAL_BANNED
        }
        if (hasRm && hasRf && argv.any { it.startsWith("/data/data/") }) {
            return RiskLevel.DANGEROUS
        }
        if (joined.contains("build.prop") || argv.any { it.contains("build.prop") }) {
            return RiskLevel.DANGEROUS
        }
        if (argv.firstOrNull() == "iptables" && argv.contains("-F")) {
            return RiskLevel.DANGEROUS
        }
        if (argv.contains("pm") && argv.contains("uninstall") && joined.contains("--user 0")) {
            return RiskLevel.DANGEROUS
        }
        if (joined.contains("/etc/hosts")) {
            return RiskLevel.DANGEROUS
        }
        if (joined.contains("setenforce 0") || (argv.contains("setenforce") && argv.contains("0"))) {
            return RiskLevel.DANGEROUS
        }
        if (containsShellExpansionSyntax(raw)) {
            return RiskLevel.DANGEROUS
        }
        return RiskLevel.NORMAL
    }

    override fun decide(cmd: GateCommand, mode: PermissionMode): Decision {
        // [Fix-P0] Reordered to match user expectation: ALLOW_ALL should bypass
        // everything except rules + fatal-confirm. Old order put authority fence
        // + fatal-block *before* ALLOW_ALL, breaking user's "allow all" intent.

        // [1] Permission rules: explicit allow/deny bypass everything else.
        val rule = evaluateRules(cmd)
        if (rule == "deny") return Decision.Denied("规则拒绝: ${cmd.toolName}")
        if (rule == "allow") return Decision.Allow("规则放行: ${cmd.toolName}")

        // [2] Always pass: read-only tools (unless mode==DENY_ALL).
        if (cmd.toolName in READ_ONLY_TOOLS && mode != PermissionMode.DENY_ALL) {
            return Decision.Allow("只读工具自动放行")
        }

        // [3] Always pass: coordinator tools (unless mode blocks them).
        if (cmd.toolName in COORDINATOR_TOOLS && mode != PermissionMode.DENY_ALL &&
            mode != PermissionMode.READ_ONLY && mode != PermissionMode.PLAN
        ) {
            return Decision.Allow("协调工具自动放行")
        }

        // Host `su` shares the app UID, so mode 700 does not hide other chats
        // or databases/minis.db. Guest paths are a separate matter: /data is the
        // sandbox's own tree, and /sdcard only exists once shared storage has
        // been bound. Both need the caller, which this method cannot see, so
        // [withCallerSession] supplies it — a null caller keeps the old
        // deny-anything-private behaviour instead of opening up.
        if (cmd.toolName in SHELL_TOOLS) {
            val command = extractCommand(cmd)
            SuPathPolicy.denial(command, callerSession.get())
                ?.let { return Decision.Denied(it, hard = true) }
            // "Mounted" when nothing injected a provider: refusing commands that
            // name a mount we were never told about would break the shell for a
            // state this object cannot observe.
            GuestMountPolicy.rejection(command, sdcardMounted?.invoke() ?: true)
                ?.let { return Decision.Denied(it, hard = true) }
        }

        // [4] Mode-level block: DENY_ALL, READ_ONLY, PLAN.
        if (mode == PermissionMode.DENY_ALL) {
            return Decision.Denied("当前为拒绝全部模式")
        }
        if (mode == PermissionMode.READ_ONLY || mode == PermissionMode.PLAN) {
            if (cmd.toolName in WRITE_TOOLS || cmd.toolName in SHELL_TOOLS) {
                return Decision.Denied("只读/计划模式禁止写与执行")
            }
        }

        // [5] FATAL risk check, but ALLOW_ALL downgrades to confirm.
        val command = extractCommand(cmd)
        val risk = if (cmd.toolName in SHELL_TOOLS || cmd.toolName == "shell_exec") {
            classifyRisk(command)
        } else {
            RiskLevel.NORMAL
        }
        if (risk == RiskLevel.FATAL_BANNED) {
            if (mode == PermissionMode.ALLOW_ALL) {
                // [Fix-P0-option-2] User selected: FATAL→confirm in ALLOW_ALL.
                return Decision.NeedConfirm(
                    describeFatalViolation(command),
                    "⚠️ 危险命令（可能损坏系统）\n\n${preview(cmd)}"
                )
            } else {
                return Decision.Denied(describeFatalViolation(command))
            }
        }

        // [6] ALLOW_ALL: if we reached here, no rule/fatal blocked it → allow.
        // Authority fence is checked *after* ALLOW_ALL to let users override it.
        if (mode == PermissionMode.ALLOW_ALL) {
            return Decision.Allow("允许全部模式：直接放行")
        }

        // [7] Authority fence: only enforced in ASK mode (workspace jail).
        val authority = authorityProfile
        if (authority != null) {
            val path = extractPath(cmd)
            if (path != null) {
                val write = cmd.toolName in WRITE_TOOLS || cmd.toolName in SHELL_TOOLS
                if (!authority.allows(path, write)) {
                    return Decision.Denied("权威围栏拒绝路径: $path")
                }
            }
            if (cmd.capability == Capability.NET && authority.net == NetPolicy.RESTRICTED &&
                cmd.toolName in setOf("web_fetch", "web_search", "download_file")
            ) {
                return Decision.Denied("权威围栏禁止出网")
            }
        }

        // [8] ASK: safe commands auto-allow, others confirm.
        if (cmd.toolName in SHELL_TOOLS && isSafeReadOnlyCommand(command)) {
            return Decision.Allow("只读安全命令自动放行")
        }
        return Decision.NeedConfirm(cmd.why, preview(cmd))
    }

    override fun preview(cmd: GateCommand): String {
        val command = extractCommand(cmd)
        val sb = StringBuilder()
        sb.append("工具: ${cmd.toolName}\n")
        sb.append("能力: ${cmd.capability.label}\n")
        sb.append("可逆: ${cmd.reversibility}\n")
        sb.append("说明: ${cmd.why}\n")
        if (command.isNotBlank()) sb.append("目标: $command\n")
        if (cmd.toolName == "env_exec") {
            sb.append("注意: env_exec 跑在 Ubuntu 客户机里，bind 了 /sdcard /storage /data。\n")
        }
        return sb.toString()
    }

    override fun audit(cmd: GateCommand, decision: Decision, result: String?) {
        val ds = when (decision) {
            is Decision.Allow -> "Allow: ${decision.reason}"
            is Decision.NeedConfirm -> "NeedConfirm: ${decision.reason}"
            is Decision.Denied -> "Denied: ${decision.reason}"
        }
        synchronized(auditTrail) {
            val prev = auditTrail.lastOrNull()?.hash ?: ""
            val ts = System.currentTimeMillis()
            val hash = computeAuditHash(prev, ts, cmd.toolName, cmd.toolArgs, ds, result)
            auditTrail.add(
                AuditEntry(
                    timestamp = ts,
                    toolName = cmd.toolName,
                    toolArgs = cmd.toolArgs,
                    capability = cmd.capability,
                    reversibility = cmd.reversibility,
                    decision = ds,
                    result = result,
                    prevHash = prev,
                    hash = hash,
                ),
            )
        }
    }

    override fun getAuditTrail(): List<AuditEntry> = synchronized(auditTrail) { auditTrail.toList() }

    override fun verifyAuditChain(): AuditChainVerification {
        synchronized(auditTrail) {
            var prev = ""
            for ((i, e) in auditTrail.withIndex()) {
                if (e.prevHash != prev) return AuditChainVerification(ok = false, brokenAt = i)
                val expected = computeAuditHash(e.prevHash, e.timestamp, e.toolName, e.toolArgs, e.decision, e.result)
                if (expected != e.hash) return AuditChainVerification(ok = false, brokenAt = i)
                prev = e.hash
            }
            return AuditChainVerification(ok = true)
        }
    }

    /** Test helper: mutate payload without recomputing hash. */
    fun tamperDecision(index: Int, newDecision: String) {
        synchronized(auditTrail) {
            val e = auditTrail[index]
            auditTrail[index] = e.copy(decision = newDecision)
        }
    }

    private fun computeAuditHash(
        prevHash: String,
        timestamp: Long,
        toolName: String,
        toolArgs: String,
        decision: String,
        result: String?,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = buildString {
            append(prevHash); append('|')
            append(timestamp); append('|')
            append(toolName); append('|')
            append(toolArgs); append('|')
            append(decision); append('|')
            append(result ?: "")
        }
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun evaluateRules(cmd: GateCommand): String? {
        if (permissionRules.isEmpty()) return null
        val target = extractCommand(cmd)
        var allowHit = false
        for (r in permissionRules) {
            if (!toolFilterMatches(r.toolFilter, cmd.toolName)) continue
            if (!patternMatches(r.pattern, target)) continue
            when (r.action.lowercase()) {
                "deny" -> return "deny"
                "allow" -> allowHit = true
            }
        }
        return if (allowHit) "allow" else null
    }

    private fun toolFilterMatches(filter: String, toolName: String): Boolean = when {
        filter == "*" || filter.isBlank() -> true
        filter.endsWith("*") -> toolName.startsWith(filter.dropLast(1))
        else -> filter == toolName || ToolAliases.canonical(toolName) == filter
    }

    private fun patternMatches(pattern: String, target: String): Boolean {
        if (pattern.isBlank()) return true
        val regex = buildString {
            append("^")
            for (c in pattern) when (c) {
                '*' -> append(".*")
                '?' -> append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                    append('\\'); append(c)
                }
                else -> append(c)
            }
            append("$")
        }
        return try {
            Regex(regex).containsMatchIn(target) || Regex(regex).matches(target) ||
                target.contains(pattern.trim('*'))
        } catch (_: Exception) {
            target.contains(pattern)
        }
    }

    private fun extractCommand(cmd: GateCommand): String = when (cmd.toolName) {
        "shell_exec", "shell_execute", "su_exec", "env_exec" -> {
            try { JSONObject(cmd.toolArgs).optString("command", cmd.toolArgs) } catch (_: Exception) { cmd.toolArgs }
        }
        "file_read", "file_write", "file_edit", "multi_edit", "list_dir", "glob", "grep", "grep_source" -> {
            try { JSONObject(cmd.toolArgs).optString("path", cmd.toolArgs) } catch (_: Exception) { cmd.toolArgs }
        }
        else -> cmd.toolArgs
    }

    private fun extractPath(cmd: GateCommand): String? {
        val p = try { JSONObject(cmd.toolArgs).optString("path", "") } catch (_: Exception) { "" }
        return p.takeIf { it.isNotBlank() }
    }

    private fun describeFatalViolation(command: String): String {
        val fatal = riskUnits(command).firstOrNull { classifySegment(it) == RiskLevel.FATAL_BANNED } ?: command
        val norm = fatal.replace(Regex("\\s+"), " ")
        return when {
            APP_DATA_ROOTS.any { norm.contains(it) } -> "禁止写入应用私有数据 ($command)"
            listOf("/system", "/system_ext", "/vendor", "/product", "/odm", "/boot", "/recovery").any { norm.contains(it) } ->
                "禁止写入系统分区 ($command)"
            listOf("parted", "fdisk", "mkfs", "/dev/block/", "fastboot", "flash_image").any { norm.contains(it) } ->
                "禁止操作分区表/块设备"
            norm.contains("rm -rf /") -> "禁止删除根目录"
            norm.contains("rm -rf /data") && !norm.contains("/data/data/") && !norm.contains("/data/local/") ->
                "禁止删除 /data 整体"
            else -> "致命违规操作"
        }
    }

    private fun classifyShellCommand(toolName: String, toolArgs: String): GateCommand {
        val command = try { JSONObject(toolArgs).optString("command", "") } catch (_: Exception) { "" }
        val risk = classifyRisk(command)
        val rev = when (risk) {
            RiskLevel.FATAL_BANNED, RiskLevel.DANGEROUS -> Reversibility.IRREVERSIBLE
            RiskLevel.NORMAL -> Reversibility.REVERSIBLE
        }
        return GateCommand(toolName, toolArgs, inferCapability(command), rev, "风险等级: $risk, 命令: '$command'")
    }

    private fun inferCapability(command: String): Capability = when {
        command.contains("kill") || command.contains("renice") -> Capability.PROCESS
        command.contains("iptables") || command.contains("ifconfig") -> Capability.NET
        command.contains("mount") || command.contains("insmod") -> Capability.KERNEL
        command.contains("pm ") || command.contains("am ") -> Capability.APP
        command.contains("settings") || command.contains("setprop") -> Capability.SYSTEM
        command.contains("gradle") || command.contains("sdkmanager") || command.contains("apt-get") || command.contains("apt ") -> Capability.BUILD
        command.contains("chroot") || command.contains("env -i") -> Capability.TERMINAL
        else -> Capability.FS
    }

    private fun isSafeReadOnlyCommand(command: String): Boolean {
        if (command.isBlank()) return false
        if (containsShellExpansionSyntax(command)) return false
        val argv = tokenizeCommand(command)
        if (argv.isEmpty()) return false
        val bin = argv[0].substringAfterLast('/')
        if (bin == "git") {
            val sub = argv.getOrNull(1) ?: return false
            return sub in SAFE_GIT_SUB
        }
        return bin in SAFE_COMMANDS
    }
}
