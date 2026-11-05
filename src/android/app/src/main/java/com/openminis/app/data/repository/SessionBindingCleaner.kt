package com.openminis.app.data.repository

import android.content.Context

/**
 * [Wave3-fix-9] Seam for clearing the copies of provider model-entry ids that
 * live OUTSIDE provider.db when those entries disappear: chat sessions pin the
 * id in `sessions.model_binding` (compact JSON) and scheduled tasks keep their
 * own `modelId` / `modelBinding` copy.
 *
 * [ProviderRepository] used to reach across the data layer boundary for both —
 * it cast the Application context to `MinisApp` to fish the chat DAO out of the
 * app container and constructed a throwaway
 * [com.openminis.app.scheduled.ScheduledTaskStore] per call. The lookup now
 * lives behind this interface so the provider store depends on an abstraction
 * instead of the app container. Behaviour is UNCHANGED by default:
 * [legacy] is a faithful port of the old body (AppContainer-first chat DAO
 * lookup with the getInstance fallback, IO dispatcher, lazily-built
 * ScheduledTaskStore), and [ProviderRepository] injects it unless the app
 * container passes a different implementation.
 */
interface SessionBindingCleaner {

    /**
     * Null the bindings that reference any id in [entryIds]. Idempotent, no-op
     * for an empty list; must not throw for missing rows.
     */
    suspend fun clearModelBindings(entryIds: List<String>)

    companion object {
        /** Legacy behaviour preserved verbatim — the default wiring. */
        fun legacy(context: Context): SessionBindingCleaner =
            LegacySessionBindingCleaner(context)
    }
}

/**
 * The pre-[SessionBindingCleaner] implementation, moved verbatim out of
 * [ProviderRepository.scrubExternalEntryRefs]. Kept `internal` — the app
 * container may replace it, nothing else should construct it.
 */
internal class LegacySessionBindingCleaner(
    private val context: Context,
) : SessionBindingCleaner {

    // [Wave3-fix-9] The old code built a fresh ScheduledTaskStore for every
    // call; one lazy instance per process serves the same writes.
    private val scheduledTaskStore by lazy {
        com.openminis.app.scheduled.ScheduledTaskStore(context)
    }

    override suspend fun clearModelBindings(entryIds: List<String>) {
        if (entryIds.isEmpty()) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // [T-singleton-convergence] AppContainer-first; getInstance =
            // pre-wiring fallback only — exactly the old lookup.
            val dao = (context as? com.openminis.app.MinisApp)?.appContainer?.database?.chatDao()
                ?: com.openminis.app.data.db.AppDatabase.getInstance(context).chatDao()
            // [Wave3-fix-14] One batched call instead of one full-table
            // `instr` UPDATE per id — see [ChatDao.clearBindingsReferencing].
            dao.clearBindingsReferencing(entryIds)
            scheduledTaskStore.dropEntryRefs(entryIds.toSet())
        }
    }
}
