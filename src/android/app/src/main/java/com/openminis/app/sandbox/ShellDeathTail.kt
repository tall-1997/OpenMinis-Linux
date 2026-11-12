package com.openminis.app.sandbox

/**
 * [T-android-shell-death-diagnosability] Captured shell output for premature-death
 * reporting, including chunks that arrive with no pending callback — readLoop used
 * to discard those outright. In release builds stderr is merged into stdout
 * (redirectErrorStream), so proot's dying words land exactly in that window.
 *
 * HEAD + rolling TAIL, not tail-only. The first crDroid field log proved tail-only
 * insufficient: proot prints its one-line cause FIRST ("proot error: …") and then
 * talloc_enable_leak_report() dumps a multi-KB allocation tree at exit — which
 * evicted the cause and left us staring at HandlerEntry leak rows. The head is
 * where the answer lives; the tail still shows how it ended.
 *
 * 从 [PersistentShell] 拆出（架构行数门禁），行为逐字节保持。
 */
internal class ShellDeathTail {

    companion object {
        const val HEAD_MAX = 1024
        const val TAIL_MAX = 2048
    }

    private val head = StringBuilder()
    private val tail = StringBuilder()
    private var total = 0

    /** Fresh capture per process incarnation — a respawned shell must not
     * report its predecessor's dying output as its own. */
    fun reset() {
        synchronized(tail) {
            head.setLength(0)
            tail.setLength(0)
            total = 0
        }
    }

    /** 启动结果日志里的早期输出预览（不超过 [max] 字符）。 */
    fun headPreview(max: Int): String = synchronized(tail) { head.toString().take(max) }

    fun append(text: String) {
        synchronized(tail) {
            total += text.length
            if (head.length < HEAD_MAX) {
                head.append(text.take(HEAD_MAX - head.length))
            }
            tail.append(text)
            val over = tail.length - TAIL_MAX
            if (over > 0) tail.delete(0, over)
        }
    }

    /**
     * Captured output (trimmed) for premature-death reporting: the head (where
     * proot's error line lives), plus the tail when output outgrew the head
     * window, with the elided middle marked.
     */
    fun capture(): String = synchronized(tail) {
        val headText = head.toString().trim()
        if (total <= HEAD_MAX) return headText // head holds everything
        // Tail buffer starts at byte (total - tail.length); the head covers
        // [0, HEAD_MAX). Drop any overlap from the tail so the two windows
        // concatenate without duplication.
        val overlap = HEAD_MAX - (total - tail.length)
        val tailText = tail.substring(overlap.coerceIn(0, tail.length)).trim()
        if (tailText.isEmpty()) return headText
        val elided = (-overlap).coerceAtLeast(0)
        val sep = if (elided > 0) "\n…[$elided bytes elided]…\n" else "\n"
        "$headText$sep$tailText"
    }
}
