package com.openminis.app.ui.settings

import android.content.Context
import com.openminis.app.R
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.service.AgentForegroundService
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-sandbox APK build. Runs on a process-wide scope so leaving Settings does
 * not cancel a multi-hour job the way [androidx.compose.runtime.rememberCoroutineScope]
 * does. UI re-attaches by collecting [state].
 */
object SelfBuildRunner {
    data class State(
        val running: Boolean = false,
        val message: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        val runningText = app.getString(R.string.check_update_self_build_running)
        _state.value = State(running = true, message = runningText)
        val gate = CompletableDeferred<Unit>()
        val launched = scope.launch {
            try {
                gate.await()
                val result = ExecutionCoordinator.execute(
                    "self-build",
                    "sh /usr/local/bin/minis-self-build",
                    timeout = 10_800_000L,
                )
                val tail = result.output
                    .trim()
                    .lines()
                    .takeLast(8)
                    .joinToString("\n")
                    .take(500)
                val shown = if (tail.isBlank()) "exit ${result.exitCode}" else tail
                _state.value = State(
                    running = false,
                    message = app.getString(R.string.check_update_self_build_result, shown),
                )
            } catch (t: kotlinx.coroutines.CancellationException) {
                _state.value = State(
                    running = false,
                    message = app.getString(R.string.check_update_self_build_result, "cancelled"),
                )
                throw t
            } catch (t: Throwable) {
                _state.value = State(
                    running = false,
                    message = app.getString(
                        R.string.check_update_self_build_result,
                        "${t.javaClass.simpleName}: ${t.message ?: "failed"}",
                    ),
                )
            } finally {
                SessionActivityTracker.setInactive("self-build")
                _state.value = _state.value.copy(running = false)
                running.set(false)
            }
        }
        job = launched
        // Register the overlay before the body can finish. On Dispatchers.IO a
        // fast failure used to call setInactive before setActive, which left
        // the overlay stuck on a job that had already ended.
        try {
            SessionActivityTracker.setActive("self-build") { launched.cancel() }
        } finally {
            // Always open the gate. If registration throws, awaiting it forever
            // leaves running=true and the next Start is a no-op.
            gate.complete(Unit)
        }
        AgentForegroundService.startService(app, 1, "self-build")
    }
}
