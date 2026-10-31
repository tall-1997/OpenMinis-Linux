package com.openminis.app.knowledge

/**
 * Per-session tap routes for the App experience library. The dispatcher feeds every
 * `ui_action` target label into [record] (main agent owns that wiring); [route] returns the
 * current tap path for the package the session is working in — at most [MAX_STEPS] steps
 * (oldest dropped), consecutive duplicates collapsed. A switch of package resets the route,
 * so the list always describes how the session reached its current screen in [pkg].
 *
 * Pure class: no Android imports, JVM-testable. Not thread-safe across instances but each
 * method is guarded, so concurrent callers share state safely.
 */
class RouteTracker {

    private class Session {
        var pkg: String? = null
        val steps = ArrayDeque<String>()
    }

    private val lock = Any()
    private val sessions = HashMap<String, Session>()

    /** Sessions may be `parentId/childId`; the whole conversation shares the head. */
    private fun bucket(sessionId: String): String = sessionId.substringBefore('/')

    private fun sessionOf(sessionId: String): Session =
        sessions.getOrPut(bucket(sessionId)) { Session() }

    /**
     * Record one `ui_action` target for [pkg]. Empty labels are ignored; a label identical to
     * the last recorded step is ignored (the tap toggled or re-tapped the same control);
     * recording under a different package clears the previous route first. Keeps only the
     * most recent [MAX_STEPS] steps.
     */
    fun record(sessionId: String, pkg: String, targetText: String) {
        val label = targetText.trim().replace(Regex("\\s+"), " ")
        if (label.isEmpty()) return
        synchronized(lock) {
            val s = sessionOf(sessionId)
            if (s.pkg != pkg) {
                s.pkg = pkg
                s.steps.clear()
            }
            if (s.steps.lastOrNull() == label) return
            s.steps.addLast(label)
            while (s.steps.size > MAX_STEPS) s.steps.removeFirst()
        }
    }

    /**
     * The recorded route for [sessionId] while it works in [pkg]: newest last, at most
     * [MAX_STEPS] steps. Empty when the session is unknown, moved to another package, or
     * nothing was recorded yet.
     */
    fun route(sessionId: String, pkg: String): List<String> = synchronized(lock) {
        val s = sessions[bucket(sessionId)] ?: return emptyList()
        if (s.pkg != pkg) emptyList() else s.steps.toList()
    }

    /** Drop the route (e.g. a new user turn began) but keep the session's package. */
    fun clear(sessionId: String) {
        synchronized(lock) { sessionOf(sessionId).steps.clear() }
    }

    /** Test support: forget every session. */
    fun resetForTests() {
        synchronized(lock) { sessions.clear() }
    }

    companion object {
        const val MAX_STEPS = 6
    }
}

/**
 * Process-wide tracker for the dispatcher wiring (sessions are keyed
 * internally, so one instance serves the whole app process).
 */
object RouteTrackers {
    val global: RouteTracker = RouteTracker()
}
