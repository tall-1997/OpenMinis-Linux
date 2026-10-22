package com.openminis.app.ui.chat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-model-switch-midrun-stall] 用户在 agent loop 运行中切换了模型。
 *
 * 单独成文件：ChatViewModelAgentLoopExt 已到架构门禁的行数 ceiling。
 *
 * 现象：任务跑到一半用户从模型 A 切到 B，接着卡住不动；之后一直在重试 A。
 *
 * 根因：loop 在启动时把 [ChatViewModel.currentProvider] 快照进局部变量
 * （`var currentProvider = provider`），失败重试与组内回退都读这个局部变量。
 * `selectEntry()` 改的是 VM 上的字段，跑着的 loop 看不见。于是旧模型——往往
 * 正是失败的那个——被反复重试到预算耗尽，用户以为"切了没反应"。
 *
 * `ModelSwitchGate` 的 epoch 本来只保护"回退结果不回写用户选择"，不改变重试
 * 目标。这里让它在重试决策点直接放弃本轮：用户已经明确不想再用旧模型，继续
 * 重试没有意义，把控制权交回，下一次发送自然使用新模型。
 */

/**
 * 用户在本轮运行中切换了模型吗？
 *
 * epoch 在用户主动选模型时递增，loop 启动时快照一次。两者不等 = 用户中途换过。
 */
internal fun ChatViewModel.shouldAbandonForModelSwitch(snapshottedEpoch: Long): Boolean =
    !ModelSwitchGate.mayWriteBack(snapshottedEpoch, snapshotModelSelectionEpoch())

/**
 * 若用户中途切换了模型，收拾状态并返回 true（调用方应结束本轮）。
 */
internal suspend fun ChatViewModel.abandonIfModelSwitched(snapshottedEpoch: Long): Boolean {
    if (!shouldAbandonForModelSwitch(snapshottedEpoch)) return false
    abandonTurnForModelSwitch(snapshottedEpoch)
    return true
}

/**
 * 结束本轮并把状态收拾干净：清掉重试倒计时，提示用户重新发送。
 */
internal suspend fun ChatViewModel.abandonTurnForModelSwitch(snapshottedEpoch: Long) {
    val current = snapshotModelSelectionEpoch()
    Log.i(
        ChatViewModel.TAG,
        "[ModelSwitch] 用户中途切换模型（epoch $snapshottedEpoch -> $current），" +
            "放弃旧模型重试，结束本轮",
    )
    withContext(Dispatchers.Main) {
        _autoRetryAttempt.value = 0
        _autoRetryCountdown.value = 0
        setTransientInlineError("已切换模型 — 请重新发送以使用新模型")
    }
}
