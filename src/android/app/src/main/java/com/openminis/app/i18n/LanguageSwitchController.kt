package com.openminis.app.i18n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [T-lang-switch-txn] 语言切换事务：双槽状态机 + 取消回滚 + 串行下载。
 *
 * **双槽**：已生效槽（prefs 当前值，[readEffective] 读）与进行中槽
 * （[SwitchState.Pending]）。**rollbackTo 只认已生效槽**——连切场景下回滚
 * 基准永远是「用户切之前实际生效的语言对」，不是上一个被顶掉的 pending
 * （否则连切 N 次后取消会落到中间态）。
 *
 * **提交时机**：下载成功才写 prefs（确认前不写）——确认弹窗取消 = 事务未
 * 开启，天然无需回滚；下载中取消 = [rollback] abort 在飞下载 + 清半成品。
 *
 * **锁分层**（关键）：[stateMutex] 只护状态迁移，**不跨下载**；下载在
 * [QUEUE] 全局串行队列里跑。否则 rollback 抢不到锁，「下载中取消」会阻塞
 * 到下载结束才生效。事务身份靠**代际令牌** [generation]：下载返回后比对
 * 令牌，被顶掉/被回滚的事务不得写 prefs 也不得改状态。
 *
 * **串行下载**（拍板）：[QUEUE] 全局单队列，多语言包请求排队逐个下。
 *
 * **回滚失败**：abort 后半成品清理抛异常 → [SwitchState.RollbackFailed]
 * （UI 落点态：设置页错误横幅，prefs 仍为 from 值，可重试）。
 *
 * 依赖全注入（无 Android 依赖，JVM 可测）。
 */
class LanguageSwitchController(
    private val readEffective: () -> LangPair,
    private val writeEffective: (LangPair) -> Unit,
    private val isPairReady: suspend (LangPair) -> Boolean,
    private val downloadPack: suspend (LangPair) -> Unit,
    private val abortDownload: (LangPair) -> Unit,
    private val clearPartial: suspend (LangPair) -> Unit,
) {

    data class LangPair(val src: String, val tgt: String)

    sealed class SwitchState {
        data object Idle : SwitchState()
        /** 进行中：from=已生效基准，to=目标，phase 区分确认前/下载中。 */
        data class Pending(val from: LangPair, val to: LangPair, val phase: Phase) : SwitchState() {
            enum class Phase { CONFIRMING, DOWNLOADING }
        }
        /** 回滚失败（abort 后半成品清理抛异常）：UI 错误落点态。 */
        data class RollbackFailed(val from: LangPair, val to: LangPair, val message: String) : SwitchState()
    }

    enum class SwitchResult { COMMITTED, FAILED, ROLLED_BACK }

    private val _state = MutableStateFlow<SwitchState>(SwitchState.Idle)
    val state: StateFlow<SwitchState> = _state

    private val stateMutex = Mutex()

    /** 代际令牌：每次新事务自增；被顶掉的事务凭旧令牌自动放弃写权限。 */
    @Volatile
    private var activeGen = -1L
    private var generation = 0L

    /** 全局串行下载队列（拍板：排队不并发）。 */
    companion object {
        private val QUEUE = Mutex()
        private object Superseded : RuntimeException("superseded by newer switch")
    }

    /**
     * 切换事务：就绪直接提交；需下载则入全局串行队列，成功才写 prefs。
     * 连切：新请求先回滚在飞事务（abort + 清半成品），再开新事务——回滚
     * 基准仍是已生效槽。
     */
    suspend fun requestSwitch(to: LangPair): SwitchResult {
        val from = readEffective()
        if (from == to) return SwitchResult.COMMITTED
        val gen = stateMutex.withLock {
            rollbackLocked()
            val g = ++generation
            activeGen = g
            _state.value = SwitchState.Pending(from, to, SwitchState.Pending.Phase.CONFIRMING)
            g
        }
        if (isPairReady(to)) return commit(gen, to)
        stateMutex.withLock {
            if (activeGen == gen) {
                _state.value = SwitchState.Pending(from, to, SwitchState.Pending.Phase.DOWNLOADING)
            }
        }
        val ok = try {
            QUEUE.withLock {
                if (activeGen != gen) throw Superseded
                downloadPack(to)
            }
            true
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce // 宿主吊销协程：照常传播，不吞取消
        } catch (_: Throwable) {
            false
        }
        return stateMutex.withLock {
            when {
                activeGen != gen -> SwitchResult.FAILED // 被顶掉/已回滚：不动状态不写 prefs
                ok && isPairReady(to) -> {
                    writeEffective(to)
                    activeGen = -1
                    _state.value = SwitchState.Idle
                    SwitchResult.COMMITTED
                }
                else -> {
                    activeGen = -1
                    _state.value = SwitchState.Idle
                    runCatching { clearPartial(to) } // 失败路径尽力清半成品
                    SwitchResult.FAILED
                }
            }
        }
    }

    /**
     * 取消回滚：丢弃 pending，abort 在飞下载，清半成品。prefs 未动（提交
     * 时机=下载成功后），无需写回。清理失败 → RollbackFailed 落点态。
     * 在飞事务凭代际令牌自动放弃提交，故本函数**不等下载结束**。
     */
    suspend fun rollback(): SwitchResult {
        val st = _state.value
        val to = (st as? SwitchState.Pending)?.to ?: (st as? SwitchState.RollbackFailed)?.to
            ?: return SwitchResult.ROLLED_BACK
        val from = (st as? SwitchState.Pending)?.from
            ?: (st as? SwitchState.RollbackFailed)?.from ?: readEffective()
        return stateMutex.withLock {
            activeGen = -1 // 先吊销令牌：在飞事务回来即作废
            abortDownload(to)
            runCatching { clearPartial(to) }.fold(
                onSuccess = { _state.value = SwitchState.Idle; SwitchResult.ROLLED_BACK },
                onFailure = {
                    _state.value = SwitchState.RollbackFailed(from, to, it.message ?: "rollback failed")
                    SwitchResult.FAILED
                },
            )
        }
    }

    /** 清 RollbackFailed 落点态（用户点「知道了」）。 */
    fun acknowledgeFailure() {
        if (_state.value is SwitchState.RollbackFailed) _state.value = SwitchState.Idle
    }

    /** 就绪快路径提交（无下载）；持锁比对令牌，被顶掉则放弃。 */
    private suspend fun commit(gen: Long, to: LangPair): SwitchResult = stateMutex.withLock {
        if (activeGen != gen) return@withLock SwitchResult.FAILED
        writeEffective(to)
        activeGen = -1
        _state.value = SwitchState.Idle
        SwitchResult.COMMITTED
    }

    /**
     * 回滚在飞事务（事务内部路径，调用方持 [stateMutex]）：吊销令牌 + abort +
     * 尽力清半成品（连切路径，旧事务清理失败不挡新事务；严格清理由
     * [rollback] 自己承担并落 RollbackFailed）。
     */
    private suspend fun rollbackLocked(): Boolean {
        activeGen = -1
        val pending = _state.value as? SwitchState.Pending ?: return false
        abortDownload(pending.to)
        runCatching { clearPartial(pending.to) }
        _state.value = SwitchState.Idle
        return true
    }
}
