package com.openminis.app.data.repository

import com.openminis.app.logging.AppLogger
import com.openminis.app.util.Digest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** A diary ID is meaningful only within its host scope and source file. */
internal data class MemoryEntryRef(val directory: File, val source: String, val id: String)

/** Half-open UTF-16 range of actual entry content, not its heading or timestamp. */
internal data class MemoryDeliverySpan(val ref: MemoryEntryRef, val start: Int, val end: Int) {
    fun shifted(by: Int) = copy(start = start + by, end = end + by)
}

internal enum class MemoryEntryOrigin { MEMORY_WRITE, SEDIMENT }

/** Keep UTF-16 offsets valid when a budget lands inside a supplementary character. */
internal fun memoryTextPrefix(text: String, cap: Int): String {
    val end = cap.coerceIn(0, text.length)
    return text.take(if (end in 1 until text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end - 1 else end)
}

/** Legacy timestamp comments still delimit entries. Only host writes add the optional ID. */
internal object MemoryDiaryFormat {
    val dateFile = Regex("""\d{4}-\d{2}-\d{2}\.md""")
    val id = Regex("[a-f0-9]{32}")
    val marker = Regex("""(?m)(?:^|(?<=\A\uFEFF))(<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->)(?: <!-- minis-memory:([a-f0-9]{32}) -->)?[ \t]*(?=\r?$)""")

    data class Block(val start: Int, val bodyStart: Int, val end: Int, val legacyMarker: String, val id: String?)

    fun blocks(text: String): List<Block> {
        val marks = marker.findAll(text).toList()
        return marks.mapIndexed { index, match ->
            var bodyStart = match.range.last + 1
            if (text.getOrNull(bodyStart) == '\r') bodyStart++
            if (text.getOrNull(bodyStart) == '\n') bodyStart++
            Block(match.range.first, bodyStart, marks.getOrNull(index + 1)?.range?.first ?: text.length,
                match.groupValues[1], match.groups[2]?.value)
        }
    }
}

/**
 * A logical archive: Markdown is the only content store. One atomic metadata file per
 * diary holds both the active/archived state inputs and the successful-delivery receipts.
 * A row must match a unique ID AND the exact bytes of the original entry; changing its
 * body or writing a timestamp/ID by hand does not enroll people-written text.
 */
internal object MemoryLifecycle {
    const val DAY_MS = 86_400_000L
    const val UNUSED_MS = 60 * DAY_MS
    const val MAX_TTL_DAYS = 3650
    const val SIDECAR_DIR = ".memory-lifecycle"
    /** [T-lifecycle-receipt-window] Recent turn hashes kept per entry for delivery dedup. */
    private const val RECEIPT_WINDOW = 64
    private val locks = Array(64) { Any() }

    enum class Status { ACTIVE, EXPIRED, ARCHIVED }

    data class State(
        val createdAt: Long,
        val expiresAt: Long?,
        val origin: MemoryEntryOrigin,
        val length: Int,
        val digest: String,
        val restoredAt: Long? = null,
        val lastUsedAt: Long? = null,
        /**
         * [T-lifecycle-receipt-window] Successful-delivery turn hashes, bounded to
         * a recent window: the hashes only answer "did THIS turn already deliver"
         * (dedup within a replay), so keeping every hash forever grew the sidecar
         * O(deliveries) and made every delivery rewrite a larger file. The total
         * count lives in [deliveryCount] and stays exact.
         */
        val receipts: List<String> = emptyList(),
        /** False for normal or pinned host notes. Legacy/handwritten notes have no State at all. */
        val autoArchive: Boolean = true,
        val deliveryCount: Int = 0,
    ) {
        val usage: Int get() = deliveryCount

        fun status(now: Long): Status {
            val activity = maxOf(createdAt, restoredAt ?: createdAt, lastUsedAt ?: createdAt)
            return when {
                autoArchive && activity <= now && now - activity >= UNUSED_MS -> Status.ARCHIVED
                expiresAt != null && now >= expiresAt -> Status.EXPIRED
                else -> Status.ACTIVE
            }
        }
    }

    data class Managed(val ref: MemoryEntryRef, val state: State, val body: String)
    data class OwnedLine(val text: String, val entry: Managed? = null) {
        fun isSedimentCaption(): Boolean = entry?.state?.origin == MemoryEntryOrigin.SEDIMENT &&
            text.startsWith(MemorySediment.LABEL)
    }
    data class View(val text: String, private val bodyRanges: List<Pair<IntRange, Managed>> = emptyList()) {
        fun hasVisibleText(): Boolean = text.removePrefix("\uFEFF").isNotBlank()

        fun lines(): List<OwnedLine> {
            var offset = 0
            var ownerIndex = 0
            return text.lines().map { line ->
                while (ownerIndex < bodyRanges.size && bodyRanges[ownerIndex].first.last < offset) ownerIndex++
                val owner = bodyRanges.getOrNull(ownerIndex)?.takeIf { offset in it.first }?.second
                val lineEnd = offset + line.length
                offset = lineEnd + if (text.getOrNull(lineEnd) == '\r' && text.getOrNull(lineEnd + 1) == '\n') 2 else 1
                OwnedLine(line, owner)
            }
        }
    }

    data class Diary(val text: String, private val entries: List<Match> = emptyList()) {
        data class Match(val block: MemoryDiaryFormat.Block, val entry: Managed, val end: Int)

        fun managed(): List<Managed> = entries.map { it.entry }

        fun editEnds(contentWithoutBom: String): Map<String, Int> {
            if (text.removePrefix("\uFEFF") != contentWithoutBom) return emptyMap()
            val bom = if (text.startsWith('\uFEFF')) 1 else 0
            return entries.associate { it.entry.ref.id to (it.end - bom) }
        }

        fun active(now: Long): View {
            if (entries.isEmpty()) return View(text)
            val bodyRanges = mutableListOf<Pair<IntRange, Managed>>()
            val view = buildString {
                var from = 0
                for ((block, entry, end) in entries) {
                    append(text, from, block.start)
                    if (entry.state.status(now) == Status.ACTIVE) {
                        // Keep the legacy timestamp in the view, never expose the internal ID as prompt text.
                        appendLine(block.legacyMarker)
                        val bodyStart = length
                        append(entry.body)
                        bodyRanges += (bodyStart until length) to entry
                        append(text, block.bodyStart + entry.body.length, end)
                    }
                    from = end
                }
                append(text, from, text.length)
            }
            return View(view, bodyRanges)
        }
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    /** The guest mounts `memory/` read-write, not the session root. Both backup trees still copy the sidecar. */
    internal fun metadataDirectory(dir: File): File {
        val parent = dir.parentFile
        val inSession = dir.name == "memory" && parent?.parentFile?.name == "minis-sessions"
        return File(if (inSession) parent else dir, SIDECAR_DIR)
    }

    private val recallGeneration = java.util.concurrent.atomic.AtomicLong()
    fun recallGeneration(): Long = recallGeneration.get()
    fun invalidateRecall() { recallGeneration.incrementAndGet() }

    fun hasMetadata(dir: File): Boolean = runCatching { metadataDirectory(dir.canonicalFile).exists() }.getOrDefault(true)

    private fun lockFor(dir: File): Any = locks[(dir.path.hashCode() and Int.MAX_VALUE) % locks.size]

    fun <T> locked(directory: File, action: (File) -> T): T {
        if (Files.isSymbolicLink(directory.toPath()) ||
            (directory.name == "memory" && directory.parentFile?.let { Files.isSymbolicLink(it.toPath()) } == true)
        ) throw IOException("linked memory directory")
        val root = directory.canonicalFile
        return synchronized(lockFor(root)) { action(root) }
    }

    /** Reject traversal and link aliases; never read or replace another scope's source/sidecar. */
    fun child(dir: File, name: String): File? {
        if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == ':' }) return null
        val file = File(dir, name)
        return file.takeUnless { Files.isSymbolicLink(it.toPath()) || it.canonicalFile != it.absoluteFile }
    }

    internal fun sidecar(dir: File, source: String): File? {
        if (!MemoryDiaryFormat.dateFile.matches(source)) return null
        val base = metadataDirectory(dir)
        val safeBase = child(base.parentFile ?: return null, base.name) ?: return null
        return child(safeBase, "$source.json")
    }

    internal fun snapshotFiles(dir: File, source: String): MemoryRestoreJournal.PairFiles {
        MemoryRestoreJournal.original(dir, source)?.let { return it }
        val diary = child(dir, source) ?: throw IOException("Invalid diary path")
        val metadata = sidecar(dir, source) ?: throw IOException("Invalid lifecycle path")
        fun existing(file: File): File? = when {
            file.isFile -> file
            Files.exists(file.toPath(), NOFOLLOW_LINKS) -> throw IOException("Invalid diary member ${file.name}")
            else -> null
        }
        return MemoryRestoreJournal.PairFiles(existing(diary), existing(metadata))
    }

    /** Validate package bytes before replacing either target. Orphan metadata rows may be legitimate. */
    internal fun validateBackupPair(diary: File, metadata: File?, requireMatchingEntries: Boolean) {
        val text = Files.newBufferedReader(diary.toPath(), Charsets.UTF_8).use { it.readText() }
        val ids = MemoryDiaryFormat.blocks(text).mapNotNull { it.id }.toSet()
        if (metadata == null) {
            require(ids.isEmpty()) { "Diary has host entry IDs but no lifecycle metadata" }
            return
        }
        val states = readStates(metadata)
        require(states.keys.containsAll(ids)) { "Unrelated diary lifecycle metadata" }
        if (requireMatchingEntries) {
            val matched = match(checkNotNull(diary.parentFile), diary.name, text, states).managed()
            require(matched.mapTo(HashSet()) { it.ref.id } == ids) { "Diary and lifecycle metadata do not match" }
        }
    }

    fun read(directory: File, source: String): Diary? = try {
        locked(directory) { dir ->
            val pair = if (MemoryDiaryFormat.dateFile.matches(source)) snapshotFiles(dir, source)
                else MemoryRestoreJournal.PairFiles(child(dir, source)?.takeIf { it.isFile }, null)
            val text = pair.diary?.readText() ?: return@locked null
            val sidecar = pair.metadata ?: return@locked Diary(text)
            val states = try {
                readStates(sidecar)
            } catch (e: Exception) {
                // Invalid metadata never grants lifecycle authority over Markdown.
                android.util.Log.w("MemoryLifecycle", "Ignoring unreadable lifecycle metadata for $source: ${e.message}")
                return@locked Diary(text)
            }
            match(dir, source, text, states)
        }
    } catch (e: Exception) {
        android.util.Log.w("MemoryLifecycle", "Cannot read diary projection for $source: ${e.message}")
        null
    }

    private fun match(dir: File, source: String, text: String, states: Map<String, State>): Diary {
        if (states.isEmpty()) return Diary(text)
        val blocks = MemoryDiaryFormat.blocks(text)
        val counts = blocks.mapNotNull { it.id }.groupingBy { it }.eachCount()
        return Diary(text, blocks.mapNotNull { block ->
            val id = block.id ?: return@mapNotNull null
            val state = states[id] ?: return@mapNotNull null
            if (counts[id] != 1 || state.length !in 1..(block.end - block.start)) return@mapNotNull null
            val end = block.start + state.length
            if (end < block.bodyStart + 2 || !text.regionMatches(end - 2, "\n\n", 0, 2)) return@mapNotNull null
            if (Digest.sha256Hex(text.substring(block.start, end)) != state.digest) return@mapNotNull null
            val body = text.substring(block.bodyStart, end - 2)
            Diary.Match(block, Managed(MemoryEntryRef(dir, source, id), state, body), end)
        })
    }

    /**
     * [T-archive-window-admission] Cheap sidecar-only probe for the archive
     * scan: whether ANY row of this diary is not ACTIVE at [now] (idle-archived
     * or expired). Lifecycle-gated entries can outlive any fixed scan window
     * (idle archive at 60 days, TTL up to 3650), so the archive view admits an
     * old diary through this probe instead of parsing its body.
     */
    fun hasNonActiveRow(directory: File, source: String, now: Long): Boolean {
        if (!MemoryDiaryFormat.dateFile.matches(source)) return false
        val sidecar = sidecar(directory, source)?.takeIf { it.isFile } ?: return false
        return try {
            readStates(sidecar).values.any { it.status(now) != Status.ACTIVE }
        } catch (_: Exception) {
            false
        }
    }

    /** Must run under [locked]. Write metadata first: an interrupted write leaves only an inert orphan row. */
    fun enroll(        dir: File, source: String, id: String, bytesAsText: String, now: Long,
        ttlDays: Int?, origin: MemoryEntryOrigin, autoArchive: Boolean,
    ) {
        val sidecar = sidecar(dir, source) ?: throw IOException("invalid lifecycle path")
        val states = readStates(sidecar).toMutableMap()
        states[id] = State(
            now, ttlDays?.let { now + it * DAY_MS }, origin, bytesAsText.length, Digest.sha256Hex(bytesAsText),
            autoArchive = autoArchive,
        )
        writeStates(sidecar, states)
    }

    fun pin(directory: File, source: String, id: String, now: Long): Boolean =
        mutate(directory, source) { matched, states ->
            val entry = matched.firstOrNull { it.ref.id == id } ?: return@mutate false
            val state = entry.state
            if (!state.autoArchive && state.expiresAt == null) return@mutate false
            states[id] = state.copy(autoArchive = false, expiresAt = null, restoredAt = now)
            true
        }

    fun restore(directory: File, refSource: String, id: String, now: Long): Boolean =
        mutate(directory, refSource) { matched, states ->
            val entry = matched.firstOrNull { it.ref.id == id } ?: return@mutate false
            if (entry.state.status(now) == Status.ACTIVE) return@mutate false
            states[id] = entry.state.copy(expiresAt = null, restoredAt = now)
            true
        }.also { restored -> if (restored) invalidateRecall() }

    /** Called only after dispatch succeeds. Multiple surfaces/lines of one entry share one receipt. */
    fun recordDelivery(refs: Set<MemoryEntryRef>, turnKey: String, deliveredAt: Long, selectedAt: Long = deliveredAt) {
        if (turnKey.isBlank()) return
        val receipt = Digest.sha256Hex(turnKey)
        for ((location, entries) in refs.groupBy { it.directory to it.source }) {
            val (dir, source) = location
            try {
                val ids = entries.mapTo(HashSet()) { it.id }
                mutate(dir, source) { matched, states ->
                    var changed = false
                    for ((ref, state) in matched) {
                        if (ref.id !in ids || receipt in state.receipts || state.status(selectedAt) != Status.ACTIVE ||
                            selectedAt < maxOf(state.createdAt, state.restoredAt ?: state.createdAt)
                        ) continue
                        states[ref.id] = state.copy(
                            lastUsedAt = maxOf(state.lastUsedAt ?: state.createdAt, selectedAt, deliveredAt),
                            receipts = (state.receipts + receipt).takeLast(RECEIPT_WINDOW),
                            deliveryCount = state.deliveryCount + 1,
                        )
                        changed = true
                    }
                    changed
                }
            } catch (e: Exception) {
                AppLogger.warning("MemoryLifecycle", "Could not record delivery for $source: ${e.message}")
            }
        }
    }

    private fun mutate(directory: File, source: String, change: (List<Managed>, MutableMap<String, State>) -> Boolean): Boolean =
        locked(directory) { dir ->
            MemoryRestoreJournal.recover(dir, source)
            val sidecar = sidecar(dir, source)?.takeIf { it.isFile } ?: return@locked false
            val diary = child(dir, source)?.takeIf { it.isFile } ?: return@locked false
            val states = readStates(sidecar).toMutableMap()
            if (!change(match(dir, source, diary.readText(), states).managed(), states)) return@locked false
            writeStates(sidecar, states)
            true
        }

    private fun readStates(file: File): Map<String, State> {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return emptyMap()
        val json = JSONObject(file.readText())
        require(json.getInt("version") == 1) { "unsupported lifecycle version" }
        val rows = json.getJSONObject("entries")
        return buildMap {
            for (id in rows.keys()) {
                require(MemoryDiaryFormat.id.matches(id))
                val row = rows.getJSONObject(id)
                val rawReceipts = row.getJSONArray("receipts")
                put(id, State(
                    createdAt = row.getLong("createdAt"),
                    expiresAt = row.longOrNull("expiresAt"),
                    origin = MemoryEntryOrigin.valueOf(row.getString("origin")),
                    length = row.getInt("length"),
                    digest = row.getString("digest"),
                    restoredAt = row.longOrNull("restoredAt"),
                    lastUsedAt = row.longOrNull("lastUsedAt"),
                    receipts = (0 until rawReceipts.length()).map { rawReceipts.getString(it) }.takeLast(RECEIPT_WINDOW),
                    autoArchive = row.optBoolean("autoArchive", true), // v1 sidecars enrolled low entries only.
                    // Legacy sidecars kept the total only as the hash list size.
                    deliveryCount = row.optInt("deliveryCount", rawReceipts.length()),
                ))
            }
        }
    }

    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)

    private fun writeStates(file: File, states: Map<String, State>) {
        val rows = JSONObject()
        for ((id, state) in states) {
            rows.put(id, JSONObject().apply {
                put("createdAt", state.createdAt)
                put("expiresAt", state.expiresAt)
                put("origin", state.origin.name)
                put("autoArchive", state.autoArchive)
                put("length", state.length)
                put("digest", state.digest)
                put("restoredAt", state.restoredAt)
                put("lastUsedAt", state.lastUsedAt)
                put("receipts", JSONArray(state.receipts.takeLast(RECEIPT_WINDOW)))
                put("deliveryCount", state.deliveryCount)
            })
        }
        writeLifecycleAtomic(file, JSONObject().put("version", 1).put("entries", rows).toString())
    }
}

/** No copy-over fallback: on an unsupported fs or a failed rename the old file is untouched. */
internal fun writeLifecycleAtomic(file: File, text: String) {
    val parent = file.parentFile ?: throw IOException("Missing parent")
    if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) throw IOException("Cannot create ${parent.name}")
    val tmp = Files.createTempFile(parent.toPath(), ".memory-", ".minis-tmp")
    try {
        FileOutputStream(tmp.toFile()).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(tmp, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(tmp)
    }
}
