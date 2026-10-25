package com.openminis.app.evolution

import com.openminis.app.di.AppGraph

/**
 * Process-wide hooks so ChatViewModel / SessionActivityTracker do not take
 * an EvolutionEngine constructor dependency. The engine instance lives in
 * [AppGraph] (installed once from MinisApp.onCreate); this object stays the
 * call surface.
 */
object EvolutionHooks {
    fun onSessionFinished(sessionId: String, isError: Boolean) {
        AppGraph.evolutionEngineOrNull()?.onSessionFinished(sessionId, isError)
    }

    fun onToolFailure(sessionId: String, toolName: String, argsJson: String, error: String) {
        AppGraph.evolutionEngineOrNull()?.recordToolFailure(sessionId, toolName, argsJson, error)
    }

    fun maybeHarvestIdle() {
        AppGraph.evolutionEngineOrNull()?.maybeHarvestIdle()
    }
}
