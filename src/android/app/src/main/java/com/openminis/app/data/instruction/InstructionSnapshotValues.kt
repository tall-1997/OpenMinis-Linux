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
 *  - The standing-rule fragments are recorded REGARDLESS of the session's
 *    memory toggle: rules are instructions, not memory, and inject with memory
 *    off too ([T-rules-uncoupled-from-memory-toggle]). Freezing them under a
 *    memory-off flag would describe instructions the model never saw — or,
 *    after the decoupling, fail to describe the ones it did see.
 */
object InstructionSnapshotValues {

    fun of(
        goalValues: Map<String, String>,
        appGlobalFragment: String?,
        sessionGlobalFragment: String?,
        projectRulesFragment: String? = null,
    ): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        values.putAll(goalValues)
        // Absent and blank both mean "not injected". Writing an empty string
        // instead would be read as a removal by InstructionState.sanitizeValues.
        appGlobalFragment?.takeIf { it.isNotBlank() }?.let {
            values[InstructionState.KEY_APP_GLOBAL] = it
        }
        // [T-memory-project-rules] Third layer: project-scoped rules.
        projectRulesFragment?.takeIf { it.isNotBlank() }?.let {
            values[InstructionState.KEY_PROJECT_RULES] = it
        }
        sessionGlobalFragment?.takeIf { it.isNotBlank() }?.let {
            values[InstructionState.KEY_SESSION_GLOBAL] = it
        }
        return values
    }
}
