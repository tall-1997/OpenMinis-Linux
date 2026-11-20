package com.openminis.app.data.repository

import com.openminis.app.data.authority.MemoryOrigin
import com.openminis.app.tools.MemoryTools
import com.openminis.app.util.IsoTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryLifecycleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val start = 1_798_200_000_000L
    private var now = start
    private val source get() = "${IsoTime.formatLocalDate(start)}.md"

    @Test fun ttlRetiresFromWindowIndexAndKeywordRecallWithoutChangingDiary() {
        val dir = tmp.newFolder("scope")
        val repo = MemoryRepository(dir) { now }
        write(repo, "kiwifruit TTL", "\"importance\":\"low\",\"ttl_days\":1")
        val log = File(dir, source)
        val original = log.readBytes()
        val engine = MemoryRecallEngine({ dir }, clock = { now })
        now = start + MemoryLifecycle.DAY_MS - 1
        assertTrue(repo.loadRecentDailyFragments("kiwifruit").fragments.single().text.contains("kiwifruit"))
        assertEquals("kiwifruit TTL", MemoryIndex.rows(dir, null, emptySet(), now = now).single().preview)
        assertTrue(engine.recall("kiwifruit").isNotEmpty())
        now++
        assertTrue(repo.loadRecentDailyFragments("kiwifruit").fragments.isEmpty())
        assertTrue(MemoryIndex.rows(dir, null, emptySet(), now = now).isEmpty())
        assertTrue("must not replay cached keyword hits", engine.recall("kiwifruit").isEmpty())
        assertFalse(repo.getMemory("kiwifruit", "all").contains("kiwifruit TTL"))
        assertTrue(repo.getMemory("kiwifruit", "archive").contains("kiwifruit TTL"))
        assertFalse(MemoryTools.executeMemoryGet("""{"scope":"archive"}""", repo, MemoryOrigin.AUTOMATIC_RECALL).success)
        val entry = repo.archivedEntries("kiwifruit", 10).single()
        assertEquals(MemoryLifecycle.Status.EXPIRED, entry.status)
        assertFalse(MemoryRepository(tmp.newFolder("other")) { now }.restoreArchivedMemory(entry.token))
        assertFalse(MemoryTools.executeMemoryGet("""{"scope":"archive","action":"restore","entry_id":"${entry.token}"}""", repo, MemoryOrigin.AUTOMATIC_RECALL).success)
        assertTrue(MemoryTools.executeMemoryGet("""{"scope":"archive","action":"restore","entry_id":"${entry.token}"}""", repo).success)
        assertTrue(engine.recall("kiwifruit").isNotEmpty())
        assertNull(MemoryLifecycle.read(dir, source)!!.managed().single().state.expiresAt)
        assertArrayEquals(original, log.readBytes())
    }

    @Test fun onlyLowImportanceHostEntriesArchiveAtSixtyIdleDays() {
        val dir = tmp.newFolder("scope")
        val repo = MemoryRepository(dir) { now }
        assertTrue(repo.writeMemory("legacy basil").startsWith("Memory saved"))
        write(repo, "pinned shallot", "\"importance\":\"low\",\"pinned\":true")
        write(repo, "normal garlic")
        write(repo, "temporary onion", "\"importance\":\"low\"")
        assertNotNull(MemorySediment.recordCompaction(repo, "temporary oregano", 2))
        repo.saveGlobalMd("standing coriander")
        File(dir, "SOUL.md").writeText("standing pepper")
        now = start + MemoryLifecycle.UNUSED_MS - 1
        assertTrue(repo.archivedEntries("", 10).isEmpty())
        now++
        assertEquals(2, repo.archivedEntries("", 10).size)
        val active = MemoryLifecycle.read(dir, source)!!.active(now).text
        assertTrue(active.contains("legacy basil") && active.contains("pinned shallot") && active.contains("normal garlic"))
        assertFalse(active.contains("temporary onion") || active.contains("temporary oregano"))
        assertTrue(repo.loadGlobalMd().contains("standing coriander"))
        assertEquals("standing pepper", File(dir, "SOUL.md").readText())
        assertTrue(MemoryIndex.rows(dir, null, emptySet(), now = now).single().preview.contains("normal garlic"))
        val archived = repo.archivedEntries("onion", 10).single()
        assertTrue(repo.restoreArchivedMemory(archived.token))
        now += MemoryLifecycle.UNUSED_MS - 1
        assertTrue(repo.archivedEntries("onion", 10).isEmpty())
        now++
        assertEquals(archived.token, repo.archivedEntries("onion", 10).single().token)
    }

    @Test fun realDeliveryDedupSurvivesReloadAndRestartsIdleClock() {
        val dir = tmp.newFolder("scope")
        val repo = MemoryRepository(dir) { now }
        write(repo, "remember mango", "\"importance\":\"low\"")
        val ref = MemoryLifecycle.read(dir, source)!!.managed().single().ref
        val deliveredAt = start + MemoryLifecycle.UNUSED_MS - 1
        MemoryLifecycle.recordDelivery(setOf(ref), "sid\u0000user-1", deliveredAt)
        MemoryLifecycle.recordDelivery(setOf(ref), "sid\u0000user-1", deliveredAt + 10)
        val state = MemoryLifecycle.read(dir, source)!!.managed().single().state
        assertEquals(1, state.usage)
        assertEquals(deliveredAt, state.lastUsedAt)
        assertEquals(MemoryLifecycle.Status.ACTIVE, state.status(deliveredAt + MemoryLifecycle.UNUSED_MS - 1))
        assertEquals(MemoryLifecycle.Status.ARCHIVED, state.status(deliveredAt + MemoryLifecycle.UNUSED_MS))
        MemoryLifecycle.recordDelivery(setOf(ref), "sid\u0000user-2", deliveredAt + 20)
        assertEquals(2, MemoryLifecycle.read(dir, source)!!.managed().single().state.usage)
    }

    @Test fun explicitPinDeduplicatesAndUserEditsRemoveLifecycleAuthority() {
        val dir = tmp.newFolder("scope")
        val repo = MemoryRepository(dir) { now }
        write(repo, "olive", "\"importance\":\"low\",\"ttl_days\":1")
        val file = File(dir, source)
        val before = file.readText()
        write(repo, "olive", "\"pinned\":true")
        assertEquals(before, file.readText())
        assertFalse(MemoryLifecycle.read(dir, source)!!.managed().single().state.autoArchive)
        write(repo, "guest edit", "\"importance\":\"low\"")
        assertTrue(repo.replaceEntryBody("olive", "hand revised") is MemoryRepository.EntryMutationResult.Success)
        assertTrue(repo.revokeEntry("guest edit") is MemoryRepository.EntryMutationResult.Success)
        assertTrue(MemoryLifecycle.read(dir, source)!!.managed().isEmpty())
        assertFalse(file.readText().contains("minis-memory:"))
        assertTrue(file.readText().contains("hand revised"))
    }

    @Test fun manuallyEditedOrDuplicateIdsCannotEnrollText() {
        val dir = tmp.newFolder("scope")
        val repo = MemoryRepository(dir) { now }
        write(repo, "low mango", "\"importance\":\"low\"")
        val file = File(dir, source)
        val original = file.readText()
        file.writeText(original + original.replace("low mango", "duplicate anchor"))
        now += MemoryLifecycle.UNUSED_MS
        assertTrue(repo.archivedEntries("", 10).isEmpty())
        file.writeText(original.replace("low mango", "hand revision"))
        assertTrue(repo.archivedEntries("", 10).isEmpty())
        assertTrue(MemoryLifecycle.read(dir, source)!!.active(now).text.contains("hand revision"))
    }

    @Test fun aReceiptSelectedBeforeTheBodyWasReplacedCannotRenewTheReplacement() {
        val dir = tmp.newFolder("digest-bound-receipt")
        val repo = MemoryRepository(dir) { now }
        write(repo, "first olive", "\"importance\":\"low\"")
        val selected = MemoryLifecycle.read(dir, source)!!.managed().single().ref
        MemoryLifecycle.locked(dir) { root ->
            val file = File(root, source)
            val raw = file.readText().replace("first olive", "other olive")
            val entry = raw.substring(MemoryDiaryFormat.blocks(raw).single().start)
            MemoryLifecycle.enroll(root, source, selected.id, entry, now, null, MemoryEntryOrigin.MEMORY_WRITE, autoArchive = true)
            file.writeText(raw)
        }
        MemoryLifecycle.recordDelivery(setOf(selected), "real user turn", now + 1, selectedAt = now)
        val replacement = MemoryLifecycle.read(dir, source)!!.managed().single()
        assertEquals("other olive", replacement.body)
        assertEquals(0, replacement.state.usage)
        assertNull(replacement.state.lastUsedAt)
    }

    @Test fun deleteFileRemovesTheLifecycleSidecarWithTheDiary() {
        val dir = tmp.newFolder("scope")
        val repo = MemoryRepository(dir) { now }
        write(repo, "delete plum", "\"importance\":\"low\"")
        val sidecar = checkNotNull(MemoryLifecycle.sidecar(dir.canonicalFile, source))
        assertTrue(sidecar.isFile)
        assertTrue(repo.deleteFile(source))
        assertFalse(File(dir, source).exists())
        assertFalse("the sidecar must not outlive the diary", sidecar.exists())
        // A recreated file with the same name starts without stale lifecycle state.
        write(repo, "fresh quince")
        val recreated = MemoryLifecycle.read(dir, source)!!.managed().single()
        assertTrue(recreated.body.contains("fresh quince"))
        assertEquals(0, recreated.state.usage)
    }

    @Test fun archiveScanNeverReadsDiariesBeyondTheLookbackWindow() {
        val dir = tmp.newFolder("scope")
        val repo = MemoryRepository(dir) { now }
        // Entries written through the REAL writer at shifted clock values, so
        // every copy carries a digest-bound lifecycle state — a hand-copied
        // sidecar would fail the digest check and read as manually edited.
        now = start - 5L * MemoryLifecycle.DAY_MS
        write(repo, "inside radish", "\"importance\":\"low\"")
        val inside = IsoTime.formatLocalDate(now)
        now = start - (MemoryRepository.MAX_LOOKBACK_DAYS + 10L) * MemoryLifecycle.DAY_MS
        write(repo, "outside radish", "\"importance\":\"low\"")
        val outside = IsoTime.formatLocalDate(now)
        now = start
        write(repo, "today radish", "\"importance\":\"low\"")
        val today = IsoTime.formatLocalDate(start)
        now = start + MemoryLifecycle.UNUSED_MS
        // [T-archive-window-admission] The recent window bounds BODY parsing,
        // not visibility: every lifecycle-gated entry (idle-archived here) is
        // reachable regardless of its date — the archive list exists for them.
        val hits = repo.archivedEntries("radish", 10)
        assertEquals(setOf(today, inside, outside), hits.map { it.token.substringBefore(':') }.toSet())
    }

    @Test fun strictTtlAndWritablePathsDoNotAffectStandingOrManualNotes() {
        val repo = MemoryRepository(tmp.newFolder("scope")) { now }
        for (bad in listOf("\"ttl_days\":0", "\"ttl_days\":\"1\"", "\"ttl_days\":1.2", "\"ttl_days\":3651", "\"pinned\":true,\"ttl_days\":1")) {
            assertFalse(MemoryTools.executeMemoryWrite("""{"content":"bad","importance":"low",$bad}""", repo).success)
        }
        assertFalse(MemoryTools.executeMemoryWrite("""{"content":"durable","ttl_days":1}""", repo).success)
        assertFalse(repo.restoreArchivedMemory("../2026-01-01:0123456789abcdef0123456789abcdef"))
        assertTrue(repo.getMemory("", "archive").startsWith("No expired"))
    }

    private fun write(repo: MemoryRepository, content: String, fields: String = "") {
        val args = "{\"content\":\"$content\"${if (fields.isEmpty()) "" else ",$fields"}}"
        val result = MemoryTools.executeMemoryWrite(args, repo)
        assertTrue(result.output, result.success)
    }
}
