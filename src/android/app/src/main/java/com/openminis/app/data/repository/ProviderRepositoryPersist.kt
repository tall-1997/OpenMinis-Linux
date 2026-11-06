package com.openminis.app.data.repository

import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.ProviderConfig

/**
 * Atomically persist [config] to (DB) + (legacy JSON mirror), with
 * the meta.json_sync_hash kept in lockstep with the mirror we just
 * wrote. Returns the canonicalized config (entry uuids rewritten to
 * the composite "{instanceId}/{modelId}" shape via the snapshot
 * round-trip). Callers should treat the return value as the new
 * authoritative state — using the in-memory pre-call object would
 * leak the legacy uuid form into [_config.value].
 *
 * [Wave3-fix-11] The DB half uses [ProviderConfigDao.replaceDiff] (writes only
 * rows that actually changed) instead of the 5-table clear+replaceAll, which
 * rewrote the entire provider config on every slot pick / pin toggle / endpoint
 * stamp — all serialized behind the single persistence worker. The JSON mirror
 * is still written on EVERY persist and still `commit()`ed, on purpose: it is
 * the downgrade-compatibility copy AND its hash sits in the DB meta row, so a
 * throttled or deferred mirror write would leave DB(hash=new) + disk(JSON=old),
 * forcing the next cold start through the hash-mismatch repair path — costing
 * a full DB round-trip to save one prefs commit — and dropping mirror
 * fidelity for a downgraded build that reads only the JSON. The commit is
 * 5–30 ms on the worker thread; the expensive half was the DB rewrite, and
 * that is what replaceDiff removes.
 */
// ── [T-android-save-off-main-thread] Persistence pipeline (queue, worker
// scope, generation counter) lives in [ProviderConfigStore]; the facade below
// keeps the internal surface the save-path extensions rely on. Moved here from
// ProviderRepository when its architecture line ceiling left no room — pure
// one-line forwards, no state, so extension form is lossless. ──
internal val ProviderRepository.persistRequests
    get() = configStore.persistRequests
internal val ProviderRepository.writeScope
    get() = configStore.writeScope
internal val ProviderRepository.saveGeneration
    get() = configStore.saveGeneration

/** Starts the persistence worker on the first save. Idempotent. */
internal fun ProviderRepository.ensurePersistWorker() = configStore.ensurePersistWorker()

/**
 * Suspends until every save queued before this call has reached the store.
 * Backup/restore and any other "durability before returning" path should
 * await it — the fire-and-forget `saveConfig` no longer implies the write
 * landed.
 */
internal suspend fun ProviderRepository.awaitPersisted() = configStore.awaitPersisted()

internal suspend fun ProviderRepository.persistToDbAndMirror(config: ProviderConfig): ProviderConfig {
    // First serialize without the hash so the meta row reflects the
    // exact string we put into prefs (the hash sees the mirror that
    // older builds will read, not a hash-of-itself).
    val mirrorStr = json.encodeToString(ProviderConfig.serializer(), config)
    val mirrorHash = hashJsonMirror(mirrorStr)
    val snapshot = config.toSnapshot(json, jsonSyncHash = mirrorHash)
    providerDao.replaceDiff(
        instances = snapshot.instances,
        entries = snapshot.entries,
        groups = snapshot.groups,
        loopIds = snapshot.loopIds,
        meta = snapshot.meta,
    )
    // commit() not apply(): the json_sync_hash we just stored to DB is
    // a hash of THIS mirror string. If apply() queues the disk write
    // and the process dies before it flushes, the next launch sees
    // DB(hash=new) + JSON-on-disk(content=old) → the hash-mismatch
    // path interprets it as "old build wrote during downgrade" and
    // re-imports the stale JSON, blowing away the write that the DB
    // already persisted synchronously. commit() blocks the writer
    // (~5–30ms) but guarantees DB and JSON land together.
    //
    // [T-android-save-off-main-thread] "the writer" is now the repository's
    // persistence worker, never a Compose click handler: saveConfig hands the
    // snapshot to that single coroutine, so this synchronous commit no longer
    // stalls the main thread while keeping its durability guarantee.
    //
    // commit() returns false (no exception) on disk-full / permission
    // failure / corrupted prefs XML. We log so the situation is
    // observable; the DB already holds the new state authoritatively,
    // and a subsequent successful save will resync the mirror + hash.
    // If the mirror write fails, the DB remains authoritative. The next
    // cold start detects the mismatch and repairs the compatibility mirror
    // from the DB snapshot instead of importing stale JSON.
    val mirrorWritten = prefs.edit().putString("config", mirrorStr).commit()
    // [T-android-provider-memo] Any persisted config change can reshape
    // instances/entries (baseURL, UA, azure flags, useResponsesAPI…). The
    // memo key already carries the full instance hashCode + credential
    // fingerprint, so a reshaped instance produces a fresh entry naturally;
    // [T-provider-factory-race] we only prune entries of DELETED instances —
    // a blanket clear here ran after every image generation
    // (setImageEndpointResolved persists too) and rebuilt every OkHttpClient.
    com.openminis.app.provider.ProviderFactory.pruneToLive(
        config.instances.map { it.id }.toSet()
    )
    if (!mirrorWritten) {
        android.util.Log.w(
            "ProviderRepo",
            "[ProviderStore] mirror commit() returned false — DB updated " +
                "but JSON write rejected (disk full? prefs corruption?); " +
                "DB remains authoritative",
        )
    }
    // Return canonicalized form so the caller's _config.value reflects
    // entry uuids in composite-key shape from this write forward.
    return snapshot.toProviderConfig(json)
}
