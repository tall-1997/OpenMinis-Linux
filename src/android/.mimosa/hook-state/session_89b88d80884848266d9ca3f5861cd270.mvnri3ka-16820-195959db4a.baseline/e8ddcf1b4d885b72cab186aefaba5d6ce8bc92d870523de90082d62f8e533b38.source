package com.openminis.app.service

import com.openminis.app.sandbox.SandboxWorkload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The resources owned by one assistant run in one chat session.
 *
 * Process ownership is explicit so stopping one chat cannot kill another
 * session's guest shell, host-su child, or the user's interactive terminal.
 */
class ActiveRun internal constructor(
    val registrySessionId: String,
    internal val generation: Long,
    ownerSessionIds: Set<String> = setOf(registrySessionId),
) {
    @Volatile
    var coroutine: Job? = null
        private set

    data class CurrentTool(val callId: String, val name: String, val args: String?)

    private val currentTool = java.util.concurrent.atomic.AtomicReference<CurrentTool?>(null)
    val currentToolCallId: String? get() = currentTool.get()?.callId
    val currentToolName: String? get() = currentTool.get()?.name
    val currentToolArgs: String? get() = currentTool.get()?.args

    @Volatile
    var assistantMessageId: String? = null
        private set

    private var lastPersistedAssistantText: String = ""
    private var currentAssistantText: String = ""
    private val progressLock = Any()

    fun attachAssistantMessage(messageId: String) {
        assistantMessageId = messageId
    }

    fun associateSession(sessionId: String) {
        ActiveRunRegistry.associate(this, sessionId)
    }

    fun updateAssistantText(text: String) = synchronized(progressLock) {
        if (!stopped.get()) currentAssistantText = text
    }

    fun markAssistantTurnPersisted(text: String) = synchronized(progressLock) {
        lastPersistedAssistantText = text
        currentAssistantText = text
    }

    /**
     * [T-android-stop-dup-row] True once ANY round of this run committed an
     * assistant row (each round persist marks [lastPersistedAssistantText]).
     * The stop-path partial-text fallback must NOT re-persist the canonical
     * message's cumulative content when rounds are already durable — that
     * wrote a second full-text assistant row and rendered the whole reply
     * twice after reload.
     */
    fun hasPersistedAssistantText(): Boolean = synchronized(progressLock) {
        lastPersistedAssistantText.isNotEmpty()
    }

    fun unpersistedAssistantText(): String = synchronized(progressLock) {
        val current = currentAssistantText
        val persisted = lastPersistedAssistantText
        if (current.startsWith(persisted)) current.drop(persisted.length) else current
    }

    private val ownersLock = Any()
    private val mutableOwnerSessionIds = ownerSessionIds.toMutableSet()
    val ownerSessionIds: Set<String> get() = synchronized(ownersLock) { mutableOwnerSessionIds.toSet() }

    internal fun addOwner(sessionId: String) {
        synchronized(ownersLock) { mutableOwnerSessionIds.add(sessionId) }
    }

    private val stopped = AtomicBoolean(false)
    private val persistenceOpen = AtomicBoolean(true)
    private val persistenceLock = Mutex()
    private val resourcesLock = Any()
    private val processes = ConcurrentHashMap<Process, () -> Unit>()
    private val stopActions = ConcurrentHashMap<() -> Unit, () -> Unit>()

    internal fun attach(job: Job?) {
        coroutine = job
    }

    fun setCurrentTool(callId: String?, name: String?, args: String? = null) {
        currentTool.set(if (callId == null || name == null) null else CurrentTool(callId, name, args))
    }

    fun currentToolSnapshot(): CurrentTool? = currentTool.get()

    fun registerProcess(process: Process) = registerProcess(process) {
        SandboxWorkload.release(process, kill = true, reason = "active-run-stop:$registrySessionId")
    }

    fun hasProcess(process: Process): Boolean = synchronized(resourcesLock) { processes.containsKey(process) }

    fun registerProcess(process: Process, cleanup: () -> Unit) {
        var accepted = false
        synchronized(resourcesLock) {
            if (!stopped.get()) {
                processes.put(process, cleanup)
                accepted = true
            }
        }
        if (!accepted) runCatching(cleanup)
    }

    fun unregisterProcess(process: Process) {
        synchronized(resourcesLock) { processes.remove(process) }
    }

    /** Register a stop hook and return a token that detaches it on normal completion. */
    fun registerStopAction(action: () -> Unit): () -> Unit {
        val registered = synchronized(resourcesLock) {
            if (stopped.get()) false else { stopActions[action] = action; true }
        }
        if (!registered) runCatching(action)
        return {
            synchronized(resourcesLock) { stopActions.remove(action) }
            Unit
        }
    }

    suspend fun <T> withPersistencePermit(block: suspend () -> T): T? = persistenceLock.withLock {
        if (!persistenceOpen.get()) null else block()
    }

    fun closePersistence() {
        synchronized(progressLock) { persistenceOpen.set(false) }
    }

    suspend fun awaitPersistenceDrained() {
        withContext(NonCancellable) { persistenceLock.withLock { } }
    }

    /** Cancel the run and kill only host children registered to this run. */
    fun stop() {
        val resources = synchronized(resourcesLock) {
            if (!stopped.compareAndSet(false, true)) return
            persistenceOpen.set(false)
            val hooks = stopActions.values.toList()
            val ownedProcesses = processes.values.toList()
            stopActions.clear()
            processes.clear()
            hooks to ownedProcesses
        }
        coroutine?.cancel(CancellationException("Stopped by user"))
        resources.first.forEach { action -> runCatching(action) }
        resources.second.forEach { cleanup -> runCatching(cleanup) }
    }

    val isStopped: Boolean get() = stopped.get()
}

/** Session-indexed registry; each run gets a generation to prevent stale finish callbacks. */
object ActiveRunRegistry {
    private val runs = ConcurrentHashMap<String, ActiveRun>()
    private val generations = java.util.concurrent.atomic.AtomicLong(0L)

    fun begin(sessionId: String, job: Job?): ActiveRun {
        return begin(setOf(sessionId), job)
    }

    fun begin(ownerSessionIds: Set<String>, job: Job?): ActiveRun {
        val owners = ownerSessionIds.filter(String::isNotBlank).toSet()
        require(owners.isNotEmpty()) { "ActiveRun requires a non-empty session owner" }
        val run = ActiveRun(owners.first(), generations.incrementAndGet(), owners)
        run.attach(job)
        owners.forEach { owner ->
            val previous = runs.put(owner, run)
            if (previous != null && previous !== run) previous.stop()
        }
        return run
    }

    fun current(sessionId: String): ActiveRun? = runs[sessionId]

    fun current(runJob: Job): ActiveRun? = ActiveRunContext.forJob(runJob)

    fun associate(run: ActiveRun, sessionId: String) {
        if (sessionId.isBlank()) return
        run.addOwner(sessionId)
        runs.compute(sessionId) { _, existing ->
            if (existing == null || existing === run || !existing.isStopped) {
                if (existing != null && existing !== run) existing.stop()
                run
            } else run
        }
    }

    fun registerProcess(sessionId: String?, process: Process) {
        sessionId?.takeIf { it.isNotBlank() }?.let(runs::get)?.registerProcess(process)
    }

    fun unregisterProcess(sessionId: String?, process: Process) {
        sessionId?.takeIf { it.isNotBlank() }?.let(runs::get)?.unregisterProcess(process)
    }

    fun stop(sessionId: String): ActiveRun? = runs[sessionId]?.also(ActiveRun::stop)

    fun stopExact(run: ActiveRun) {
        run.stop()
        run.ownerSessionIds.forEach { runs.remove(it, run) }
    }

    fun finish(run: ActiveRun) {
        run.ownerSessionIds.forEach { runs.remove(it, run) }
        if (!run.isStopped && run.ownerSessionIds.none { current(it) === run }) run.setCurrentTool(null, null)
    }

    fun finish(sessionId: String) {
        runs.remove(sessionId)?.let(::finish)
    }
}
