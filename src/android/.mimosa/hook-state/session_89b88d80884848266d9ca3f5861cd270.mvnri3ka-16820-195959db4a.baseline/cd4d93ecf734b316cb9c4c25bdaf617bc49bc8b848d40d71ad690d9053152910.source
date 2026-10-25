package com.openminis.app.accessibility

import java.util.concurrent.atomic.AtomicReference

/**
 * Observation contract for node actions (mechanism learned from Eta's
 * observation_id discipline, rewritten for this tree — no code copied).
 *
 * Every `ui dump` / `ui find` publishes an [Observation] carrying a fresh
 * `observationId`. Every node-mutating action (`tap node`, `scroll node`,
 * `input text --node`) must carry the observation_id it is acting on. A node
 * action against an expired observation is refused with STALE so the model
 * re-observes instead of tapping coordinates from a dead tree.
 *
 * The contract is deliberately a pure object: [validate] is JVM-unit-testable
 * without an AccessibilityService.
 */
object ObservationReferencePolicy {
    enum class Status { MATCH, NO_OBSERVATION, ID_REQUIRED, STALE }

    fun validate(currentId: String?, requestedId: String?): Status = when {
        currentId == null -> Status.NO_OBSERVATION
        requestedId.isNullOrBlank() -> Status.ID_REQUIRED
        requestedId != currentId -> Status.STALE
        else -> Status.MATCH
    }
}

/**
 * One published screen observation. [nodeIds] are the registry ids that were
 * allocated inside this observation; a node action re-checks membership so a
 * recycled id from an older observation cannot slip through after expiry.
 */
data class PublishedObservation(
    val id: String,
    val issuedAtMs: Long,
    val packageName: String?,
    val nodeIds: Set<String>,
)

/**
 * Stable per-node identity fingerprint. `viewId` alone is not unique inside
 * lists; text/description/class plus window+package give a matchable signature
 * that survives a registry-id recycle (Eta's node-identity lesson, rewritten).
 */
data class AccessibilityNodeIdentity(
    val windowId: Int,
    val packageName: String,
    val className: String,
    val viewId: String,
    val text: String,
    val description: String,
) {
    val strong: Boolean
        get() = text.isNotBlank() || description.isNotBlank() || viewId.isNotBlank()

    fun matches(refreshed: AccessibilityNodeIdentity): Boolean =
        windowId == refreshed.windowId &&
            packageName == refreshed.packageName &&
            className == refreshed.className &&
            (viewId.isBlank() || viewId == refreshed.viewId) &&
            text == refreshed.text &&
            description == refreshed.description
}

/**
 * Holds the most recently published observation. Thread-safe: publisher is the
 * offload handler's IO thread; readers are the same single-threaded handler.
 */
class ObservationStore {
    private val current = AtomicReference<PublishedObservation?>(null)

    fun publish(observation: PublishedObservation) = current.set(observation)

    fun current(): PublishedObservation? = current.get()

    /**
     * Validate an incoming node action. Returns null when the reference is
     * acceptable (observation matches and the node id belongs to it); otherwise
     * a Pair of error code and message for the CLI envelope.
     */
    fun checkNodeAction(observationId: String?, nodeId: String?): Pair<String, String>? {
        val obs = current()
        return when (ObservationReferencePolicy.validate(obs?.id, observationId)) {
            ObservationReferencePolicy.Status.NO_OBSERVATION ->
                "NO_OBSERVATION" to "Run `ui dump` (or `ui find`) first to publish an observation, then pass its observation_id."
            ObservationReferencePolicy.Status.ID_REQUIRED ->
                "OBSERVATION_ID_REQUIRED" to "Node actions must carry --observation <id> from the same `ui dump` that produced the node id."
            ObservationReferencePolicy.Status.STALE ->
                "STALE_OBSERVATION" to "observation_id=$observationId is stale (current ${obs?.id}); re-observe the screen."
            ObservationReferencePolicy.Status.MATCH ->
                if (nodeId != null && obs != null && nodeId !in obs.nodeIds) {
                    "NODE_NOT_IN_OBSERVATION" to "nodeId=$nodeId was not part of observation $observationId; re-observe."
                } else null
        }
    }
}
