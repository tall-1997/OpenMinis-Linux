package com.openminis.app.sandbox

/**
 * 一次命令执行的回调状态（从 [PersistentShell] 拆出）。
 *
 * [GH#358] [generation] 记录创建它的通道代次：超时/reset/stop 后旧 reader
 * 线程仍在排空死进程的输出，这些字节绝不能进入新命令的 framer（它们携带旧
 * marker 与命令输出）；代次不匹配即视为"已弃用通道"，由分发处丢弃。
 */
internal class ShellCommandCallback(
    val marker: String,
    val generation: Long,
    val output: BoundedOutputBuffer = BoundedOutputBuffer(),
    val lineCallback: ((String) -> Unit)?,
    var onComplete: ((String, Int) -> Unit)? = null,
) {
    val framer = MarkerFramer(marker)
    val heartbeat = SilentOutputHeartbeat()

    fun appendOutput(text: String) {
        if (text.isNotEmpty()) heartbeat.onOutput()
        output.append(text)
    }
}
