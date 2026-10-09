package com.openminis.app.harness

/**
 * `:harness` 内日志接缝（对齐执行计划「android.util.Log → HarnessLog」一条）。
 *
 * taixu 的 AppLogger 绑 Context + 公共目录落盘；harness 是纯 JVM 库拿不到这些，
 * 默认实现只走 stderr（观察侧数据，丢了不影响正确性）。宿主若要落盘/上报，
 * 注入自己的 sink 即可——运行链路从不因日志失败。
 */
object HarnessLog {

    /** 可替换的输出端；测试可捕获断言。 */
    var sink: (level: String, message: String, throwable: Throwable?) -> Unit =
        { level, message, throwable ->
            System.err.println("[harness/$level] $message" + (throwable?.let { ": ${it.message}" } ?: ""))
        }

    fun w(message: String, throwable: Throwable? = null) = sink("W", message, throwable)

    fun e(message: String, throwable: Throwable? = null) = sink("E", message, throwable)
}
