package com.openminis.app.offload

import com.openminis.app.sandbox.offload.SuCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings-facing snapshot of the host Magisk/KernelSU `su` binary.
 *
 * Does **not** run `su -c id` — that would pop a Magisk grant dialog just for
 * opening Permissions. Binary presence is enough for the card; an explicit
 * "Test su" on the detail screen is what probes elevation.
 */
object HostSuManager {
    enum class State { NOT_FOUND, FOUND }

    data class Snapshot(
        val state: State,
        val path: String? = null,
    )

    private val _snapshot = MutableStateFlow(Snapshot(State.NOT_FOUND))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun refresh() {
        val path = SuCommand.findSuBinary()
        _snapshot.value = if (path != null) Snapshot(State.FOUND, path) else Snapshot(State.NOT_FOUND)
    }

    fun isFound(): Boolean = _snapshot.value.state == State.FOUND
}
