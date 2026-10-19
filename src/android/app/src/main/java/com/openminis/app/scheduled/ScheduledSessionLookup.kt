package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.MinisApp
import kotlinx.coroutines.runBlocking

/**
 * [T-android-scheduled-default-follow-up] Read-only session probes shared by
 * the two creation surfaces that run inside a chat (the `minis-scheduled` CLI
 * handler and the agent-facing `cronjob` tool). Deciding whether a follow-up
 * target exists and *naming* that chat in the delivery sentence both need the
 * same two answers, and both surfaces must give the same ones.
 *
 * Both probes degrade to "unknown" while the repository is not ready (early
 * boot, unit tests): an unknown session counts as existing and its title as
 * unknown. Refusing a create on a guess would turn a transient startup state
 * into a user-visible error, and a missing title only costs a name in the
 * sentence — never the fire itself.
 *
 * Called from offload-server / tool threads, hence `runBlocking`: the same
 * pattern the CLI handler already used to reach the chat repository.
 */
class ScheduledSessionLookup(context: Context) {

    private val app = context.applicationContext as? MinisApp

    private val repo get() = app?.chatRepositoryOrNull

    fun exists(id: String): Boolean {
        val r = repo ?: return true
        return runBlocking { r.getSession(id) } != null
    }

    fun title(id: String): String? {
        val r = repo ?: return null
        return runBlocking { r.getSession(id) }?.title?.takeIf { it.isNotBlank() }
    }
}
