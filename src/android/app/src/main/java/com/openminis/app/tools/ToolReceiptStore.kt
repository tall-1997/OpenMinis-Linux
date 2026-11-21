package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal enum class ToolReceiptState(val wire: String) {
    NOT_SENT("not_sent"), UNKNOWN("unknown"), ACCEPTED("accepted"),
}

/** Only a real persisted assistant/result pair can reconcile a transcript receipt. */
internal class ToolReceiptPair(
    val key: ToolReceiptKey,
    callId: String,
    assistantRowId: String,
    resultRowId: String,
    val proof: String,
) {
    val callSha = receiptDigest("wire", callId)
    val pairSha = receiptDigest("transcript", assistantRowId, resultRowId)
    val resultRowSha = receiptDigest("result-row", resultRowId)
    val resultRef = resultRowId.takeIf(::validReceiptUuid)
}

internal sealed interface ToolReceiptDecision {
    data object Ready : ToolReceiptDecision
    class Claimed(val instanceSha: String, val token: String, val proof: String) : ToolReceiptDecision
    class AlreadySent(
        val state: ToolReceiptState,
        val resultRef: String?,
        val resultRecorded: Boolean = false,
    ) : ToolReceiptDecision
    data object UnprovenBatch : ToolReceiptDecision
    data object Full : ToolReceiptDecision
    data object Unavailable : ToolReceiptDecision
}

/**
 * One bounded file and lock per session. UNKNOWN and unpaired-ACCEPTED records are never
 * evicted: restoration can reuse the same session ID even after deletion, and that evidence
 * may still be the only proof of an in-flight or accepted delivery. Fully settled ACCEPTED
 * records (verified transcript pair or typed local outcome) recycle oldest-first once the
 * journal hits capacity. Session journals lazily read legacy v1 records until the first
 * verified v2 write.
 */
internal class ToolReceiptStore(
    private val directory: File,
    private val maxRecords: Int = MAX_RECORDS,
    private val replace: (File, ByteArray) -> Unit = ::replaceReceiptShard,
    private val syncDirectory: (File) -> Unit = {},
    /** Injectable clock for age-out settlement; tests advance it. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    init { require(maxRecords in 1..MAX_RECORDS) }

    private data class Record(
        val sessionSha: String,
        val parentSha: String,
        val logicalSha: String,
        val state: ToolReceiptState,
        val callSha: String? = null,
        val token: String? = null,
        val proofSha: String? = null,
        val pairSha: String? = null,
        val resultRef: String? = null,
        val resultRowSha: String? = null,
        val localResult: String? = null,
        val laneSha: String? = null,
        val ownerSha: String? = null,
        val operationSha: String? = null,
        val sequenceSha: String? = null,
        val step: Int = 0,
        val batch: Int = 0,
        /** Creation clock (ms). 0 = legacy pre-timestamp row — age beyond doubt. */
        val ts: Long = 0,
    ) {
        val resultRecorded: Boolean get() = pairSha != null || localResult != null
        val lane: String get() = laneSha ?: ToolReceiptScope.MAIN_LANE

        fun decision() = if (state == ToolReceiptState.NOT_SENT) ToolReceiptDecision.Ready
        else ToolReceiptDecision.AlreadySent(state, resultRef, resultRecorded)

        fun matchesProof(sha: String): Boolean = state != ToolReceiptState.NOT_SENT && sha ==
            (proofSha ?: token?.let { receiptDigest("proof", receiptProof(logicalSha, checkNotNull(callSha), it)) })
    }

    internal data class Pending(val unknown: Int, val acceptedWithoutResult: Int) {
        val hasUnpaired: Boolean get() = unknown > 0 || acceptedWithoutResult > 0
    }

    /** Age beyond doubt: a timestamped record past the ceiling, or a legacy
     *  pre-timestamp row (ts=0 — journals written before the clock existed).
     *  Combined with a generation boundary it satisfies the settled-out
     *  double condition (age + progress evidence). */
    private fun isStale(record: Record): Boolean =
        record.ts <= 0 || now() - record.ts >= STALE_MS

    private data class Check(val decision: ToolReceiptDecision, val batch: Int = 0)

    /** The same decision runs again under the claim lock after approval. */
    private fun check(key: ToolReceiptKey, records: Map<String, Record>): Check {
        val scope = key.scope
        val ownTurn = records.values.filter { it.parentSha == scope.parentSha }
        val legacy = records[key.legacySha]?.takeIf { it.laneSha == null && it.state != ToolReceiptState.NOT_SENT }
        if (legacy != null && !legacy.resultRecorded) return Check(legacy.decision())
        // A v1 record lacks effective operation metadata. Normalization must not
        // silently make an unresolved legacy delivery look like a different operation.
        if (key.operationNormalized) {
            val startsAtRoot = scope.laneSha == ToolReceiptScope.MAIN_LANE && scope.after == null
            ownTurn.firstOrNull {
                it.laneSha == null && it.state != ToolReceiptState.NOT_SENT && (!it.resultRecorded || startsAtRoot)
            }?.let { return Check(it.decision()) }
        }
        val sameOperation = ownTurn.filter {
            it.state != ToolReceiptState.NOT_SENT &&
                (it.operationSha == key.operationSha && it.ownerSha == scope.ownerSha || it == legacy)
        }
        sameOperation.firstOrNull { !it.resultRecorded }?.let { return Check(it.decision()) }

        val after = scope.after
        val batch = if (after == null) 0 else {
            val priorBatch = ownTurn.filter { row ->
                row.state == ToolReceiptState.ACCEPTED && row.lane == scope.laneSha && row.proofSha != null && when (after) {
                    is ToolReceiptPredecessor.Transcript -> !scope.localResults && row.pairSha != null &&
                        (row.resultRowSha ?: row.resultRef?.let { receiptDigest("result-row", it) }) == after.sha
                    is ToolReceiptPredecessor.Local -> scope.localResults && row.localResult != null && row.proofSha == after.sha
                }
            }.maxOfOrNull { it.batch } ?: return Check(ToolReceiptDecision.UnprovenBatch)
            if (priorBatch >= MAX_RECORDS) return Check(ToolReceiptDecision.Full)
            priorBatch + 1
        }
        // All siblings in one verified batch resolve to the same deterministic
        // instance generation. Older batch/step evidence cannot advance past it.
        val exact = records[key.instanceSha(batch)]
        if (exact != null && exact.state != ToolReceiptState.NOT_SENT) return Check(exact.decision())
        sameOperation.firstOrNull {
            (it.lane == scope.laneSha && it.batch >= batch) ||
                (it.sequenceSha == scope.sequenceSha && it.step > scope.stepOrdinal)
        }?.let { return Check(it.decision()) }
        return Check(ToolReceiptDecision.Ready, batch)
    }

    fun prepare(key: ToolReceiptKey): ToolReceiptDecision = transaction(
        key.scope.sessionSha, ToolReceiptDecision.Unavailable,
    ) { file, records ->
        val check = check(key, records)
        if (check.decision != ToolReceiptDecision.Ready) return@transaction check.decision
        val instance = key.instanceSha(check.batch)
        if (records[instance] != null) return@transaction ToolReceiptDecision.Ready
        if (!makeSpace(records)) return@transaction ToolReceiptDecision.Full
        records[instance] = newRecord(key, check.batch)
        write(file, records)
        ToolReceiptDecision.Ready
    }

    fun claim(key: ToolReceiptKey, callId: String): ToolReceiptDecision = transaction(
        key.scope.sessionSha, ToolReceiptDecision.Unavailable,
    ) { file, records ->
        val check = check(key, records)
        if (check.decision != ToolReceiptDecision.Ready) return@transaction check.decision
        val instance = key.instanceSha(check.batch)
        if (records[instance] == null && !makeSpace(records)) return@transaction ToolReceiptDecision.Full
        // Randomness protects the proof, not identity or the right to dispatch.
        val token = UUID.randomUUID().toString()
        val callSha = receiptDigest("wire", callId)
        records[instance] = newRecord(key, check.batch).copy(
            state = ToolReceiptState.UNKNOWN, callSha = callSha, token = token,
        )
        write(file, records)
        ToolReceiptDecision.Claimed(instance, token, receiptProof(instance, callSha, token))
    }

    fun settle(key: ToolReceiptKey, claim: ToolReceiptDecision.Claimed, state: ToolReceiptState): Boolean {
        require(state != ToolReceiptState.UNKNOWN)
        return transaction(key.scope.sessionSha, false) { file, records ->
            val prior = records[claim.instanceSha] ?: return@transaction false
            if (key.instanceSha(prior.batch) != claim.instanceSha) return@transaction false
            val sha = receiptDigest("proof", claim.proof)
            if (prior.state != ToolReceiptState.UNKNOWN || prior.token != claim.token || !prior.matchesProof(sha)) {
                return@transaction prior.state == ToolReceiptState.ACCEPTED && state == ToolReceiptState.ACCEPTED && prior.matchesProof(sha)
            }
            records[claim.instanceSha] = if (state == ToolReceiptState.NOT_SENT) newRecord(key, prior.batch)
            else prior.copy(state = state, token = null, proofSha = sha)
            write(file, records)
            true
        }
    }

    /** Compact typed evidence for a terminal return with no durable transcript carrier. */
    fun completeLocal(key: ToolReceiptKey, claim: ToolReceiptDecision.Claimed, success: Boolean): Boolean {
        if (!key.scope.localResults) return false
        return transaction(key.scope.sessionSha, false) { file, records ->
            val prior = records[claim.instanceSha] ?: return@transaction false
            if (key.instanceSha(prior.batch) != claim.instanceSha) return@transaction false
            val sha = receiptDigest("proof", claim.proof)
            if (!prior.matchesProof(sha)) return@transaction false
            val outcome = if (success) "success" else "failure"
            if (prior.localResult != null) return@transaction prior.localResult == outcome
            records[claim.instanceSha] = prior.copy(
                state = ToolReceiptState.ACCEPTED, token = null, proofSha = sha, localResult = outcome,
            )
            write(file, records)
            true
        }
    }

    /** Verify the original wire and claim proof. Newer instances need not have the root key. */
    fun reconcile(sessionSha: String, pairs: List<ToolReceiptPair>): Boolean {
        if (pairs.isEmpty()) return true
        return transaction(sessionSha, false) { file, records ->
            var changed = false
            for (pair in pairs) {
                val key = pair.key
                if (key.scope.sessionSha != sessionSha || !pair.proof.isReceiptHash()) continue
                val sha = receiptDigest("proof", pair.proof)
                val row = records.values.firstOrNull {
                    it.parentSha == key.scope.parentSha && it.lane == key.scope.laneSha &&
                        (it.operationSha == key.operationSha || it.laneSha == null && it.logicalSha == key.legacySha) &&
                        it.callSha == pair.callSha && !it.resultRecorded && it.matchesProof(sha)
                } ?: continue
                records[row.logicalSha] = row.copy(
                    state = ToolReceiptState.ACCEPTED, token = null, proofSha = sha,
                    pairSha = pair.pairSha, resultRef = pair.resultRef, resultRowSha = pair.resultRowSha,
                )
                changed = true
            }
            if (changed) write(file, records)
            true
        }
    }

    fun pending(sessionSha: String, parentSha: String? = null): Pending? {
        require(sessionSha.isReceiptHash())
        // Fresh sessions have no receipt files. A cold read must not create one.
        val absent = try {
            listOf("$sessionSha.json", "$sessionSha.lock", "${sessionSha.take(2)}.json")
                .all { Files.notExists(File(directory, it).toPath(), NOFOLLOW_LINKS) }
        } catch (_: SecurityException) {
            return null
        }
        if (absent) return Pending(0, 0)
        return transaction(sessionSha, null) { file, records ->
            // [T-receipt-aged-out] Orphans from EARLIER generations whose age is
            // beyond doubt (timestamp past the ceiling, or a legacy pre-ts row)
            // are settled out with an audit line. The generation boundary itself
            // is the progress evidence: the session only reached another user
            // turn after the earlier cycle completed. Records of the queried
            // generation are never aged out, however old they are.
            if (parentSha != null) {
                val stale = records.values.filter {
                    it.parentSha != parentSha && it.state == ToolReceiptState.UNKNOWN && isStale(it)
                }
                if (stale.isNotEmpty()) {
                    for (row in stale) records.remove(row.logicalSha)
                    write(file, records)
                    com.openminis.app.logging.AppLogger.warning(
                        TAG, "tool-receipt aged-out ${stale.size} stale orphan(s) from earlier generations",
                    )
                }
            }
            val local = records.values.filter { parentSha == null || it.parentSha == parentSha }
            Pending(local.count { it.state == ToolReceiptState.UNKNOWN },
                local.count { it.state == ToolReceiptState.ACCEPTED && !it.resultRecorded })
        }
    }

    private fun newRecord(key: ToolReceiptKey, batch: Int) = Record(
        key.scope.sessionSha, key.scope.parentSha, key.instanceSha(batch), ToolReceiptState.NOT_SENT,
        laneSha = key.scope.laneSha, ownerSha = key.scope.ownerSha, operationSha = key.operationSha,
        sequenceSha = key.scope.sequenceSha, step = key.scope.stepOrdinal, batch = batch,
        ts = now(),
    )

    private fun makeSpace(records: MutableMap<String, Record>): Boolean {
        if (records.size < maxRecords) return true
        // Oldest-first. NOT_SENT is resettable under lock; a fully settled
        // ACCEPTED record (verified transcript pair or typed local outcome) is
        // terminal history whose evidence is durable and can never be replayed.
        // UNKNOWN and ACCEPTED-without-result stay pinned permanently — that is
        // exactly the evidence a replay or reconciliation may still need.
        val recyclable = records.values.firstOrNull { it.state == ToolReceiptState.NOT_SENT }
            ?: records.values.firstOrNull { it.resultRecorded }
            // Last resort before wedging the session: an UNKNOWN whose age is
            // beyond doubt (stale — past the ceiling or a legacy pre-ts row)
            // gives way so new calls keep flowing. Freshly-claimed evidence
            // keeps its permanent pin.
            ?: records.values.firstOrNull { it.state == ToolReceiptState.UNKNOWN && isStale(it) }
            ?: return false
        records.remove(recyclable.logicalSha)
        return true
    }

    private fun <T> transaction(sessionSha: String, failure: T, run: (File, MutableMap<String, Record>) -> T): T {
        require(sessionSha.isReceiptHash())
        val lockFile = File(directory, "$sessionSha.lock")
        val lockKey = lockFile.absolutePath
        val local = localLocks.compute(lockKey) { _, present -> (present ?: LocalLock()).also { it.users++ } }!!
        return try {
            synchronized(local) {
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return failure
                RandomAccessFile(lockFile, "rw").use { handle ->
                    handle.channel.lock().use {
                        val file = File(directory, "$sessionSha.json")
                        val missing = Files.notExists(file.toPath(), NOFOLLOW_LINKS)
                        val initialized = handle.length() != 0L
                        if (initialized && (handle.length() != 1L || handle.read() != JOURNAL_INITIALIZED || missing)) {
                            return failure
                        }
                        val records = if (missing) {
                            read(File(directory, "${sessionSha.take(2)}.json"), sessionSha, legacy = true)
                        } else read(file, sessionSha, legacy = false)
                        val result = run(file, records)
                        if (!initialized && Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                            // Once a session has a journal, its loss is not a fresh session.
                            handle.writeByte(JOURNAL_INITIALIZED)
                            handle.fd.sync()
                            syncDirectory(directory)
                        }
                        result
                    }
                }
            }
        } catch (_: IOException) {
            failure
        } catch (_: SecurityException) {
            failure
        } catch (_: org.json.JSONException) {
            failure
        } catch (_: IllegalArgumentException) {
            failure
        } catch (_: StackOverflowError) {
            failure
        } finally {
            localLocks.computeIfPresent(lockKey) { _, current -> if (--current.users == 0) null else current }
        }
    }

    private fun read(file: File, sessionSha: String, legacy: Boolean): MutableMap<String, Record> {
        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) return linkedMapOf()
        val cap = if (legacy) LEGACY_MAX_BYTES else MAX_SESSION_BYTES
        require(file.isFile && file.length() in 1..cap.toLong())
        val bytes = file.readBytes()
        require(bytes.size in 1..cap)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("version") == if (legacy) 1 else 2)
        val rows = root.getJSONArray("records")
        require(rows.length() <= if (legacy) MAX_RECORDS else maxRecords)
        val records = LinkedHashMap<String, Record>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val sid = row.getString("session")
            if (legacy && sid != sessionSha) continue
            require(sid == sessionSha)
            fun str(name: String): String? = (row.opt(name) as? String)?.ifEmpty { null }
            val record = Record(
                sessionSha = sid,
                parentSha = row.getString("parent"),
                logicalSha = row.getString("key"),
                state = requireNotNull(ToolReceiptState.entries.firstOrNull { it.wire == row.getString("state") }),
                callSha = str("call"), token = str("claim"), proofSha = str("proof_sha"),
                pairSha = str("pair"), resultRef = str("result_ref"), resultRowSha = str("result_row_sha"),
                localResult = str("local_result"), laneSha = str("lane"), ownerSha = str("owner"),
                operationSha = str("operation"), sequenceSha = str("sequence"),
                ts = row.optLong("ts", 0),
                step = row.optInt("step", 0), batch = row.optInt("batch", 0),
            )
            require(record.parentSha.isReceiptHash() && record.logicalSha.isReceiptHash())
            require(listOfNotNull(record.callSha, record.proofSha, record.pairSha, record.resultRowSha,
                record.laneSha, record.ownerSha, record.operationSha, record.sequenceSha).all { it.isReceiptHash() })
            val scoped = record.laneSha != null
            require(scoped == (record.ownerSha != null) && scoped == (record.operationSha != null) &&
                scoped == (record.sequenceSha != null) && !(legacy && scoped))
            require(record.batch in 0..MAX_RECORDS && record.step >= 0)
            if (!scoped) require(record.batch == 0 && record.step == 0)
            require((record.state == ToolReceiptState.NOT_SENT) == (record.callSha == null))
            require((record.state == ToolReceiptState.UNKNOWN) == (record.token != null))
            require(record.token == null || validReceiptUuid(record.token))
            require(!(record.proofSha != null || record.resultRecorded) || record.state == ToolReceiptState.ACCEPTED)
            require(record.localResult == null || (record.proofSha != null && record.localResult in setOf("success", "failure")))
            require(record.resultRef == null || (record.pairSha != null && validReceiptUuid(record.resultRef)))
            require(record.resultRowSha == null || record.pairSha != null)
            require(records.put(record.logicalSha, record) == null)
        }
        require(records.size <= maxRecords)
        return records
    }

    private fun write(file: File, records: Map<String, Record>) {
        require(records.size <= maxRecords)
        val rows = JSONArray()
        for (record in records.values) {
            val row = JSONObject()
                .put("session", record.sessionSha).put("parent", record.parentSha).put("key", record.logicalSha)
                .put("state", record.state.wire).put("call", record.callSha).put("claim", record.token)
                .put("proof_sha", record.proofSha).put("pair", record.pairSha).put("result_ref", record.resultRef)
                .put("result_row_sha", record.resultRowSha).put("local_result", record.localResult)
                .put("lane", record.laneSha).put("owner", record.ownerSha).put("operation", record.operationSha)
                .put("sequence", record.sequenceSha).put("step", record.step).put("batch", record.batch)
                .put("ts", record.ts)
            // Fields are bounded ASCII. Include one byte per row for the array comma.
            require(row.toString().length + 1 <= MAX_RECORD_BYTES)
            rows.put(row)
        }
        val bytes = JSONObject().put("version", 2).put("records", rows).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_SESSION_BYTES)
        replace(file, bytes)
        syncDirectory(directory)
        directory.parentFile?.let(syncDirectory)
        if (file.length() != bytes.size.toLong() || !file.readBytes().contentEquals(bytes)) {
            throw IOException("Receipt replacement did not verify")
        }
    }

    private class LocalLock(var users: Int = 0)

    companion object {
        internal const val MAX_RECORDS = 1024

        /** [T-receipt-aged-out] Orphan age ceiling before settled-out (6h). */
        internal const val STALE_MS = 6 * 60 * 60 * 1000L
        private const val TAG = "ToolReceiptStore"
        private const val MAX_RECORD_BYTES = 1024
        internal const val MAX_SESSION_BYTES = 48 + MAX_RECORDS * MAX_RECORD_BYTES
        private const val LEGACY_MAX_BYTES = 512 * 1024
        private const val JOURNAL_INITIALIZED = 1
        private val localLocks = ConcurrentHashMap<String, LocalLock>()
    }
}

internal fun String.isReceiptHash(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun receiptProof(key: String, wire: String, token: String): String =
    receiptDigest("delivery-evidence", key, wire, token)

private fun validReceiptUuid(id: String): Boolean =
    id.length == 36 && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

private fun replaceReceiptShard(target: File, bytes: ByteArray) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    try {
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp.toPath())
    }
}
