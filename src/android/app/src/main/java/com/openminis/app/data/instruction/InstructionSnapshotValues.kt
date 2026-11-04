package com.openminis.app.data.instruction

/**
 * [T-p1-instruction-snapshot] Builds the instruction-value map a single request
 * is actually built from.
 *
 * Pure and total, so the "what belongs in the snapshot" decision is testable
 * without a database or a ViewModel. It takes the already-derived goal values
 * rather than the goal entity on purpose: the instruction layer owns the
 * value shape, and the UI layer that calls this must not reach into Room
 * entities to build it.
 *
 * Two rules matter:
 *
 *  - The two GLOBAL fragments come from *different* files. App-wide rules live
 *    in `minis-global/memory/GLOBAL.md`; session-scoped rules live in the chat's
 *    own workspace. Reading both from the session repository records one file
 *    under two keys and drops app-wide edits from the event stream entirely —
 *    the defect this type was extracted to fix.
 *  - With memory turned off for the session the prompt carries no GLOBAL
 *    fragments at all, so none may be recorded. Freezing them would describe
 *    instructions the model never saw.
 */
object InstructionSnapshotValues {

    fun of(
        goalValues: Map<String, String>,
        appGlobalFragment: String?,
        sessionGlobalFragment: String?,
        memoryEnabled: Boolean,
    ): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        values.putAll(goalValues)
        if (!memoryEnabled) return values
        // Absent and blank both mean "not injected". Writing an empty string
        // instead would be read as a removal by InstructionState.sanitizeValues.
        appGlobalFragment?.takeIf { it.isNotBlank() }?.let {
            values[InstructionState.KEY_APP_GLOBAL] = it
        }
        sessionGlobalFragment?.takeIf { it.isNotBlank() }?.let {
            values[InstructionState.KEY_SESSION_GLOBAL] = it
        }
        return values
    }
}
