package com.openminis.app.sandbox

import java.io.File

/**
 * [T-offload-prelude-source-once] Offload reply-parsing shell functions, sourced
 * into the persistent bash exactly once per handler-set change.
 *
 * This used to run `file.readText()` on the rootfs and re-source ~25 functions on
 * EVERY command. The shell is long-lived and reused, so the functions persist
 * once loaded — the only thing that invalidates them is a change in the
 * registered handler set (app update / new handler). [PersistentShell] keeps only
 * the signature cache; script building and the disk write live here.
 *
 * [sourcedOffloadSignature] resets on respawn (a new bash has no functions), so
 * the next command after a restart re-sources. The offload-exit reporting the
 * prelude exists for (the `__MINIS_OFFLOAD_RC` / handler_timeout mapping) keeps
 * working unchanged: the wrapper functions are in the shell exactly as before,
 * just installed once instead of per command.
 */
internal object OffloadShellPrelude {

    /** 生成 `minis_offload_wrap` + 每个 handler 的包装函数（纯函数）。 */
    fun buildScript(names: List<String>): String = buildString {
        appendLine("minis_offload_wrap() {")
        appendLine("  local name=\"\$1\"")
        appendLine("  shift")
        appendLine("  local out rc first")
        appendLine("  out=\$(command \"\$name\" \"\$@\" 2>&1)")
        appendLine("  rc=\$?")
        appendLine("  first=\$(printf '%s\\n' \"\$out\" | head -n 1)")
        appendLine("  case \"\$first\" in")
        appendLine("    __MINIS_OFFLOAD_RC=*__)")
        appendLine("      rc=\${first#__MINIS_OFFLOAD_RC=}")
        appendLine("      rc=\${rc%__}")
        appendLine("      out=\$(printf '%s\\n' \"\$out\" | tail -n +2)")
        appendLine("      ;;")
        appendLine("  esac")
        appendLine("  printf '%s\\n' \"\$out\"")
        appendLine("  case \"\$out\" in")
        appendLine("    *handler_timeout*) return 124 ;;")
        appendLine("  esac")
        appendLine("  return \$rc")
        appendLine("}")
        for (name in names) {
            append(name).append("() { minis_offload_wrap ").append(name).appendLine(" \"\$@\"; }")
        }
    }

    /** 合法的 handler 名（防注入）。 */
    fun sanitizedHandlerNames(registered: Set<String>): List<String> =
        registered
            .filter { it.matches(Regex("[A-Za-z0-9._+-]+")) }
            .sorted()

    /**
     * 返回 (要 source 的命令行, 新签名)；没有 handler 或签名未变时返回 null。
     * 脚本内容变化才落盘（避免每命令触碰磁盘）。
     */
    fun ensure(rootfsDir: File, lastSignature: String?): Pair<String, String?>? {
        val names = sanitizedHandlerNames(NativeOffloadServer.registeredHandlers)
        if (names.isEmpty()) return null
        val signature = names.joinToString(",")
        if (signature == lastSignature) return null
        val body = buildScript(names)
        val file = File(rootfsDir, "etc/minis-offload-exit.sh")
        runCatching {
            // Only touch the disk when the script content actually changed.
            if (!file.isFile || file.readText() != body) {
                file.parentFile?.mkdirs()
                file.writeText(body)
            }
        }.onFailure { return null }
        return ". /etc/minis-offload-exit.sh 2>/dev/null || true\n" to signature
    }
}
