package com.openminis.app.sandbox

/**
 * [diag] Bind-mount 诊断访问器（从 [PersistentShell] 拆出）。
 *
 * 诊断日志与复用判定只需要"这个长驻 shell 启动时冻结的挂载表"，不属于
 * shell 的生命周期逻辑；拆出使 PersistentShell 保持在架构行数上限内。
 */
internal fun PersistentShell.debugBindMount(linuxPath: String): String? =
    sessionBindMounts[linuxPath]

/** Copy of the binds this process was started with. */
internal fun PersistentShell.bindSnapshot(): Map<String, String> =
    sessionBindMounts.toMap()
