package com.openminis.app.goal

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide "open the Goal sheet" signal. Writers that have no business
 * holding a reference to the UI — the [com.openminis.app.ui.chat.PlanGoalCard]
 * header tap and the /goal slash entry — bump [requests]; the sheet collects
 * it and shows. A bare counter, no payload: the sheet always reads the
 * current goal fresh from the DB on open, so there is nothing to carry.
 */
object GoalSheetSignal {
    val requests = MutableStateFlow(0)

    fun open() {
        requests.value = requests.value + 1
    }
}
