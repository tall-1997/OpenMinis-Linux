package com.openminis.app.data.instruction

/**
 * [T-p1-instruction-snapshot] Replays the append-only instruction event stream
 * back into a values map.
 *
 * This is the read side of the snapshot: `InstructionStateStore` only ever
 * wrote events, so until now the stream could not answer "what were this
 * session's instructions at commit N". [rebuild] folds the deltas in `seq`
 * order onto the frozen `initialValuesJson` baseline, which is also the
 * deterministic inverse of the commit path (see [InstructionState.applyDelta]).
 *
 * Replay stops at an epoch boundary. Today exactly one initial row exists per
 * session — `advanceEpoch` is deliberately deferred — so a second initial row
 * means the stream was restarted, and continuing past it would fold deltas onto
 * the wrong baseline.
 */
object InstructionReplay {

    const val STOP_COMPLETE = "complete"
    const val STOP_EPOCH_RESTART = "epochRestart"

    data class ReplayResult(
        val values: Map<String, String>,
        /** Non-initial events folded onto the baseline. */
        val applied: Int,
        /** A second (or later) initial row was found; replay stopped there. */
        val epochRestarts: Int,
        val stopReason: String,
    )

    fun rebuild(
        initialValuesJson: String,
        updates: List<InstructionUpdateEntity>,
    ): ReplayResult {
        var values = InstructionState.parseValues(initialValuesJson)
        var applied = 0
        var epochRestarts = 0
        for (update in updates.sortedBy { it.seq }) {
            if (update.isInitial) {
                // The leading row describes the baseline we already started
                // from; only a LATER initial row is an epoch boundary.
                if (applied == 0 && epochRestarts == 0) continue
                epochRestarts += 1
                return ReplayResult(values, applied, epochRestarts, STOP_EPOCH_RESTART)
            }
            val deltaJson = update.deltaJson ?: continue
            values = InstructionState.applyDelta(values, InstructionState.parseDelta(deltaJson))
            applied += 1
        }
        return ReplayResult(values, applied, epochRestarts, STOP_COMPLETE)
    }
}
