package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * [T-tool-execution-ledger] Run-scoped single-execution contract for tool calls.
 *
 * ZCode's streaming contract: one call executes once and owns exactly one
 * result. The key is (id, name, args) because a provider may reuse a numbered
 * id for a different call in a later turn; args are hashed so a genuinely new
 * call with the same id is never served a stale result.
 *
 * The ledger is the ONE place that decides whether a call may execute. Every
 * execution lane (the serial runPlannedCall lane and the spawn fan-out) MUST go
 * through [executeOnce], which claims the key BEFORE invoking the side-effecting
 * producer. This makes it structurally impossible to run a tool and only
 * afterwards discover it was a replay — the exact defect that let the spawn
 * fan-out launch a second wave of sub-agents for a replayed call (it used to
 * awaitAll() executeRunSubAgent first, then consult ledger.reuse()).
 *
 * Two guarantees:
 *  - Replay: once a key is Completed, [executeOnce] returns the recorded
 *    result and never invokes the producer again.
 *  - In-flight duplicate: concurrent emissions of the same key join the one
 *    owner's [CompletableDeferred] instead of starting a second execution.
 *
 * On producer failure/cancellation the claim is released so a later retry can
 * execute; a failed call is never cached as a result.
 */
internal class ToolExecutionLedger {

    private data class Key(val id: String, val name: String, val argsSha: String)

    private sealed interface Entry {
        /** A completed call — its single canonical result. */
        data class Completed(val part: AgentContentPart.ToolResult) : Entry
        /** A call currently executing — concurrent duplicates await this. */
        class InFlight(val deferred: CompletableDeferred<AgentContentPart.ToolResult>) : Entry
    }

    private val entries = ConcurrentHashMap<Key, Entry>()

    /** Read-only: the recorded result for a key, or null when never completed. */
    fun reuse(id: String, name: String, argsSha: String): AgentContentPart.ToolResult? =
        (entries[Key(id, name, argsSha)] as? Entry.Completed)?.part

    /** Introspection for tests / diagnostics. */
    fun size(): Int = entries.size

    /**
     * The single execution boundary. Atomically decides the outcome for a key:
     *  - Completed -> return the recorded result, invoke [onReuse], skip
     *    [produce] entirely (no side effects).
     *  - InFlight  -> await the owner's deferred, invoke [onReuse], skip
     *    [produce] (the owner is already running it).
     *  - absent    -> claim it (first writer wins), run [produce] once, publish
     *    the result and complete every waiter.
     *
     * [produce] must contain the whole side-effecting execution AND the
     * finalization that turns it into an [AgentContentPart.ToolResult]. Callers
     * must NOT execute anything before calling this method.
     *
     * [onReuse] is a suspend callback so the caller can settle the replayed
     * call's UI card (a replay's tool_use block is created PENDING in phase 1
     * and never enters the producer — without this it would spin forever).
     * It runs for both the completed-key and in-flight-join paths.
     */
    suspend fun executeOnce(
        id: String,
        name: String,
        argsSha: String,
        onReuse: suspend (AgentContentPart.ToolResult) -> Unit = {},
        produce: suspend () -> AgentContentPart.ToolResult,
    ): AgentContentPart.ToolResult {
        val key = Key(id, name, argsSha)
        while (true) {
            when (val existing = entries[key]) {
                is Entry.Completed -> {
                    onReuse(existing.part)
                    return existing.part
                }
                is Entry.InFlight -> {
                    val part = existing.deferred.await()
                    onReuse(part)
                    return part
                }
                null -> {
                    val deferred = CompletableDeferred<AgentContentPart.ToolResult>()
                    val claim = Entry.InFlight(deferred)
                    if (entries.putIfAbsent(key, claim) == null) {
                        return try {
                            val part = produce()
                            entries[key] = Entry.Completed(part)
                            deferred.complete(part)
                            part
                        } catch (t: Throwable) {
                            // Release only our own claim; never cache a failure.
                            entries.remove(key, claim)
                            deferred.completeExceptionally(t)
                            throw t
                        }
                    }
                    // Lost the claim race; loop and observe the winner's entry.
                }
            }
        }
    }
}
