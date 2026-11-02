package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.provider.BuiltinFreeLanes

/**
 * Which config rows belong to a built-in lane this build no longer ships.
 *
 * A retired lane leaves two things behind on an install that used it: the
 * provider row seeded under its id and the models the row listed. The
 * descriptor is gone, so nothing refreshes the row any more — a loopback
 * endpoint no process binds — and nothing else in the app knows the id was
 * ever ours. [BuiltinFreeLanes.RETIRED_IDS] is that memory, and this is the
 * only reader: the boot purge asks it which instances to hand to
 * [ProviderRepository.removeInstance], which owns the full cleanup (models,
 * groups, agent-loop membership, tombstones, credentials, OAuth, voice shadow,
 * last-used pointer, thinking rules).
 *
 * Pure by construction — no Context, no repository, no I/O — so the decision is
 * a JVM test rather than a device behaviour. Keeping it separate from the
 * removal is what makes "add a retired id, get a clean upgrade" a one-line
 * change for the next lane we retire, instead of a second cleanup routine.
 */
internal object BuiltinLaneRetirement {

    /**
     * Instance ids present in [config] that a retired lane owns, in config
     * order. Two retirement channels:
     *
     * - [BuiltinFreeLanes.RETIRED_IDS]: lanes whose whole build-shipment is
     *   gone (a2a) — remembered by id because no descriptor survives.
     * - Built-in-prefixed rows absent from [activeInstanceIds]: manifest lanes
     *   the current manifest no longer lists. The active set is the lane list
     *   that last seeded successfully (or its persisted snapshot when the
     *   latest manifest download failed), so an unreadable manifest can never
     *   retire a lane, while a valid one that drops an entry retires exactly
     *   that entry on the next boot.
     *
     * User-built providers are never candidates: they do not carry the
     * `builtin-` prefix and are never in either channel.
     */
    fun instancesToPurge(
        config: ProviderConfig,
        activeInstanceIds: Set<String> = BuiltinFreeLanes.ALL_INSTANCE_IDS,
    ): List<String> = config.instances.map { it.id }.filter { id ->
        when {
            id in BuiltinFreeLanes.RETIRED_IDS -> true
            id.startsWith("builtin-") && id !in activeInstanceIds -> true
            else -> false
        }
    }
}
