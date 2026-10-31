package com.openminis.app.tools

import android.content.Context

/**
 * [T-android-capability-sets] Contract for predicate-dispatched tool
 * registries (device / automation / app-knowledge): the dispatcher probes
 * [handles] ahead of the name-subject `when` and runs the first match.
 *
 * Each implementation maps its native result contract onto
 * [ToolExecutionResult] here — DeviceTools' "Error:"-prefix text and
 * AutomationRuleTools' `{"ok":bool}` envelope both become real
 * success/failure, so the shared guard tail, evolution hook and output
 * policy treat every capability tool identically.
 */
interface CapabilityToolSet {
    fun handles(name: String): Boolean
    fun execute(name: String, argsJson: String, sessionId: String?, context: Context): ToolExecutionResult
}
