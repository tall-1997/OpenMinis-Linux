package com.openminis.app.harness.operation

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex

/**
 * [T-p1-6-finish-begin-race] lane 运行闸：串行化 accept 类入口的 check-then-act，
 * 并让"排队中的收尾"与入口互斥。从 OperationCoordinator 拆出（架构门 400 行帽），
 * 状态与语义原样搬移。
 *
 * 两件套：
 *  - [withLaneMutex]：accept 类入口的公共骨架。拿锁前与锁内都让位给排队中的
 *    收尾——锁内复查是必要的，因为 finish 在拿锁**之前**登记，beginRun 先拿锁时
 *    表里已能看到它的登记项（正是"beginRun 撞上即将收尾的 busy lane"的窗口）。
 *  - [withFinishMutex]：finish 的锁序入口。登记先于拿锁是关键：否则"beginRun
 *    先拿锁、finish 尚未启动"的窗口依旧存在——旧实现 beginRun 会把**即将被
 *    finishOperation 整行删除的旧 operationId** 返回给新运行，随后新运行的全部
 *    台账写入 requireOperation 抛错、被 OperationBridge.io 吞掉：新运行的程序
 *    计数器整轮丢失，unsettledIntents 崩溃恢复对它失效。
 */
internal class LaneRunGate {

    /**
     * 串行化 accept 类入口的 check-then-act：并发 accept 同一 lane 时，
     * "检查 currentOperationId == null → 写入"之间无保护会产生孤儿 RUNNING 操作。
     * 用 Mutex 而非 synchronized：临界区内含 suspend 的 repository 调用，不能阻塞线程。
     */
    private val acceptMutex = Mutex()

    /** 正在收尾的 lane → 完成信号（见类 KDoc）。internal 仅供同模块测试注入窗口。 */
    internal val pendingLaneFinishes =
        ConcurrentHashMap<Pair<String, String>, CompletableDeferred<Unit>>()

    suspend fun <T> withLaneMutex(sessionId: String, laneName: String, action: suspend () -> T): T {
        while (true) {
            acceptMutex.lock()
            val pending = pendingLaneFinishes[sessionId to laneName]
            if (pending != null) {
                acceptMutex.unlock()
                pending.await()
                // 自愈：消费掉已完成的登记（生产路径由 withFinishMutex 的 finally
                // 摘表；若摘表前有等待方醒来抢锁，或测试直接注入后只 complete，
                // 已完成项留在表里会让本循环原地自旋——remove(key, value) 只在
                // 映射仍指向同一 deferred 时生效，不会误删新登记的收尾）。
                pendingLaneFinishes.remove(sessionId to laneName, pending)
                continue
            }
            try {
                return action()
            } finally {
                acceptMutex.unlock()
            }
        }
    }

    /**
     * finish 的锁序入口：登记 lane 收尾信号 → 拿 acceptMutex → 执行 [action]
     * （调用方传 finishLocked）。完成后先 complete 再摘表——等待方醒来后重拿锁
     * 重查，lane 指针已清。
     */
    suspend fun <T> withFinishMutex(sessionId: String, laneName: String, action: suspend () -> T): T {
        val key = sessionId to laneName
        val done = pendingLaneFinishes.computeIfAbsent(key) { CompletableDeferred() }
        try {
            acceptMutex.lock()
            try {
                return action()
            } finally {
                acceptMutex.unlock()
            }
        } finally {
            done.complete(Unit)
            pendingLaneFinishes.remove(key, done)
        }
    }
}
