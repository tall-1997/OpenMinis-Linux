package com.openminis.app.data.repository

import com.openminis.app.util.IsoTime
import java.io.File
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryGovernanceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val start = 1_798_200_000_000L
    private var now = start
    private val date = LocalDate.parse(IsoTime.formatLocalDate(start))
    private val today = "$date.md"

    @After fun resetReranker() = MemoryRerankerRegistry.resetForTest()

    @Test fun supersedesRetiresTheReplacedFile() {
        val files = mapOf(
            "2026-11-10" to MemoryMetadata(supersedes = "2026-11-01"),
            "2026-11-01" to MemoryMetadata(),
            "2026-11-02" to MemoryMetadata(),
        )
        assertEquals(setOf("2026-11-01"), MemoryMetadataParser.supersededIds(files))
    }

    @Test fun supersedesIsForgivingAboutCaseAndSuffix() {
        val files = mapOf("new.md" to MemoryMetadata(supersedes = "EARLIER.MD"), "earlier.md" to MemoryMetadata())
        assertEquals(setOf("earlier.md"), MemoryMetadataParser.supersededIds(files))
    }

    @Test fun aFileWithNoSupersedesRetiresNothing() {
        val files = mapOf("a" to MemoryMetadata(), "b" to MemoryMetadata(type = "daily"))
        assertTrue(MemoryMetadataParser.supersededIds(files).isEmpty())
    }

    @Test fun theWriterEmitsABlockTheParserReadsBack() {
        val dir = tmp.newFolder("new")
        val repo = MemoryRepository(dir) { now }
        assertTrue(repo.writeMemory("cobalt note", type = "feedback", scope = "private", ttlDays = 3).startsWith("Memory saved"))
        val parsed = MemoryMetadataParser.parse(File(dir, today).readText())
        assertEquals("feedback", parsed.type)
        assertEquals("private", parsed.scope)
        assertEquals(3, parsed.ttlDays)
        assertEquals("2026-11-01", MemoryMetadataParser.parse(MemoryMetadataParser.frontmatterFor(supersedes = "2026-11-01")).supersedes)
    }

    @Test fun oldPlainDiaryStaysPlainAndAHeaderIsNotPartOfEntryDedup() {
        assertEquals("", MemoryMetadataParser.frontmatterFor())
        val dir = tmp.newFolder("legacy")
        val file = File(dir, today)
        val old = "\uFEFF<!-- $date 00:00:00 -->\r\nold line\r\n\r\n"
        file.writeText(old)
        val repo = MemoryRepository(dir) { now }
        assertTrue(repo.writeMemory("new line", type = "feedback", ttlDays = 1).startsWith("Memory saved"))
        assertTrue(repo.writeMemory("old line").startsWith("Skipped"))
        assertTrue(file.readText().startsWith("\uFEFF<!--"))
        assertTrue(file.readText().endsWith(old.removePrefix("\uFEFF")))
        assertNull(MemoryMetadataParser.parse(file.readText()).ttlDays)
    }

    @Test fun fileAndEntryTtlsComposeAndExplicitRestoreOnlyResetsTheEntry() {
        val dir = tmp.newFolder("ttl")
        val repo = MemoryRepository(dir) { now }
        val file = File(dir, today)
        val header = "\uFEFF---\r\nttl_days: 2\r\nunknown: keep verbatim\r\n---\r\n"
        val legacy = "<!-- $date 00:00:00 -->\r\nkiwifruit manual\r\n\r\n"
        file.writeText(header + legacy)
        val revision = repo.currentDailyRevision()
        assertTrue(repo.writeHostMemory("kiwifruit managed", revision, MemoryEntryOrigin.MEMORY_WRITE,
            ttlDays = 1, autoArchive = true).startsWith("Memory saved"))
        assertTrue(repo.writeHostMemory("should not land", revision, MemoryEntryOrigin.MEMORY_WRITE,
            autoArchive = true).contains("MEMORY_CONFLICT"))
        assertTrue(file.setLastModified(start))
        val original = file.readText()
        assertTrue(original.startsWith(header))
        assertTrue(original.endsWith(legacy))
        assertEquals("kiwifruit managed", MemoryLifecycle.read(dir, today)!!.managed().single().body)
        val engine = MemoryRecallEngine({ dir }, clock = { now })
        val query = "kiwifruit"
        fun surfaces() = listOf(
            repo.loadRecentDailyFragments(query).fragments.joinToString("\n") { it.text },
            MemoryIndex.fragmentWithEntries(dir, null, emptySet(), now = now)?.text.orEmpty(),
            repo.getMemory(query, "daily"),
            engine.recall(query).joinToString("\n") { it.line },
        )
        assertTrue(surfaces().all { "kiwifruit managed" in it && "unknown:" !in it })
        now += MemoryLifecycle.DAY_MS
        assertTrue(surfaces().all { "kiwifruit managed" !in it && "kiwifruit manual" in it })
        val archived = repo.archivedEntries(query, 2).single()
        assertEquals(MemoryLifecycle.Status.EXPIRED, archived.status)
        assertTrue(repo.restoreArchivedMemory(archived.token))
        assertEquals(start, file.lastModified())
        assertTrue(surfaces().all { "kiwifruit managed" in it })
        now = start + 2 * MemoryLifecycle.DAY_MS + 1
        assertTrue(surfaces().all { "kiwifruit managed" !in it && "kiwifruit manual" !in it })
        assertTrue(repo.archivedEntries(query, 2).isEmpty())
        assertEquals(original, file.readText())
        assertTrue(file.setLastModified(now)) // A file edit can re-arm file TTL without rewriting entry TTL.
        assertTrue(surfaces().all { "kiwifruit managed" in it })
    }

    @Test fun scopeGovernancePrecedesWindowAndSameDateOverlay() {
        val session = tmp.newFolder("session")
        val shared = tmp.newFolder("shared")
        val repo = MemoryRepository(session) { now }
        File(session, today).writeText("quartz original\n")
        File(shared, today).writeText("quartz shared\n")
        val outsideWindow = "${date.minusDays(50)}.md"
        val correction = File(session, outsideWindow)
        correction.writeText("---\nsupersedes: $today\nttl_days: 1\n---\nreplaced\n")
        assertTrue(correction.setLastModified(start))
        File(shared, "shared-note.md").writeText("---\nsupersedes: $outsideWindow\n---\nsecond note\n")
        val engine = MemoryRecallEngine({ session }, { shared }, clock = { now })
        assertTrue(repo.loadRecentDailyFragments("quartz").fragments.isEmpty())
        assertFalse(repo.getMemory("quartz", "daily").contains("quartz original"))
        assertEquals("quartz shared", engine.recall("quartz").single().line)
        assertTrue(MemoryIndex.rows(session, shared, setOf(outsideWindow.removeSuffix(".md")), now = now).single().shared)
        now += MemoryLifecycle.DAY_MS + 1
        assertEquals("quartz shared", engine.recall("quartz").single().line)
        assertTrue(repo.loadRecentDailyFragments("quartz").fragments.isEmpty())
        assertTrue(correction.delete())
        assertEquals("quartz original", engine.recall("quartz").single().line)
        assertFalse(MemoryIndex.rows(session, shared, emptySet(), now = now).single().shared)
        assertTrue(repo.loadRecentDailyFragments("quartz").fragments.single().text.contains("quartz original"))
    }

    @Test fun aDisabledFileIsAbsentFromEveryOrdinarySurfaceWithoutCreatingAnArchiveEntry() {
        val dir = tmp.newFolder("disabled")
        val file = File(dir, today)
        file.writeText("---\ndisabled: true\n---\nzucchini private\n")
        val repo = MemoryRepository(dir) { now }
        val engine = MemoryRecallEngine({ dir }, clock = { now })
        assertTrue(repo.loadRecentDailyFragments("zucchini").fragments.isEmpty())
        assertTrue(MemoryIndex.rows(dir, null, emptySet(), now = now).isEmpty())
        assertTrue(engine.recall("zucchini").isEmpty())
        assertFalse(repo.getMemory("zucchini", "daily").contains("zucchini private"))
        assertTrue(repo.archivedEntries("zucchini", 10).isEmpty())
        assertTrue(repo.readFile(today).contains("zucchini private"))
    }

    @Test fun usageIsCountedPerDeliveredLineAndIsIsolatedAcrossChats() {
        val one = tmp.newFolder("usage-one")
        val two = tmp.newFolder("usage-two")
        val line = MemoryUsageLedger.lineKey(true, today, "line one")
        MemoryUsageLedger.recordVerified(one, "turn A", setOf(line), start)
        MemoryUsageLedger.recordVerified(two, "turn A", setOf(line), start)
        MemoryUsageLedger.recordVerified(one, "turn A", setOf(line), start + 1)
        MemoryUsageLedger.recordVerified(one, "turn B", setOf(line), start + 2)
        assertEquals(1 to 2, MemoryUsageLedger.verifiedSummaryFor(one))
        assertEquals(1 to 1, MemoryUsageLedger.verifiedSummaryFor(two))
        val persisted = MemoryUsageLedger.verifiedFile(one).readText()
        assertFalse(persisted.contains("line one"))
        assertFalse(persisted.contains("turn A"))
    }

    @Test fun aMissingOrCorruptLedgerIsEmptyAndLegacyCountsAreOnlyHistorical() {
        val dir = tmp.newFolder("usage-old")
        assertEquals(0 to 0, MemoryUsageLedger.verifiedSummaryFor(dir))
        MemoryUsageLedger.verifiedFile(dir).writeText("not json")
        assertEquals(0 to 0, MemoryUsageLedger.verifiedSummaryFor(dir))
        val legacy = File(dir, "memory-usage.json")
        val old = """{"2026-11-10:private line":7}"""
        legacy.writeText(old)
        assertEquals(1 to 7, MemoryUsageLedger.summaryFor(dir))
        assertEquals(0 to 0, MemoryUsageLedger.verifiedSummaryFor(dir))
        MemoryUsageLedger.recordVerified(dir, "new turn", setOf(MemoryUsageLedger.lineKey(false, today, "private line")), start)
        assertEquals(1 to 1, MemoryUsageLedger.verifiedSummaryFor(dir))
        assertEquals(old, legacy.readText())
    }

    @Test fun anInstalledRerankerIsPickedUpWithoutAnEngineRebuild() {
        val (engine, hits) = rankedHits()
        assertFalse(MemoryRerankerRegistry.isCustom())
        MemoryRerankerRegistry.install(MemoryReranker { _, input -> input.reversed() })
        assertTrue(MemoryRerankerRegistry.isCustom())
        assertEquals(hits.reversed(), engine.recall("quartz"))
        MemoryRerankerRegistry.install(null)
        assertFalse(MemoryRerankerRegistry.isCustom())
        assertEquals(hits, engine.recall("quartz"))
    }

    @Test fun aRerankerThatIsNotAPermutationOrThrowsIsIgnored() {
        val (engine, hits) = rankedHits()
        MemoryRerankerRegistry.install(MemoryReranker { _, input -> input.drop(1) })
        assertEquals(hits, engine.recall("quartz"))
        MemoryRerankerRegistry.install(MemoryReranker { _, _ -> error("unavailable") })
        assertEquals(hits, engine.recall("quartz"))
    }

    @Test fun theDefaultRerankerIsTheIdentity() {
        val (_, hits) = rankedHits()
        assertEquals(hits, MemoryReranker.IDENTITY.rerank("quartz", hits))
    }

    @Test fun stripRemovesTheBlockButKeepsTheBodyBytes() {
        val text = "\uFEFF---\r\ntype: daily\r\nttl_days:3\r\n---\r\nbody line\r\n"
        assertEquals("body line\r\n", MemoryMetadataParser.strip(text))
    }

    @Test fun stripIsIdentityForAnythingThatIsNotAFrontmatterBlock() {
        for (legacy in listOf("", "---", "---\nno close\n", "plain\n", "---\nprose with a colon: yes\n---\n")) {
            assertEquals(legacy, MemoryMetadataParser.strip(legacy))
        }
    }

    private fun rankedHits(): Pair<MemoryRecallEngine, List<MemoryRecallEngine.RecallHit>> {
        val dir = tmp.newFolder("reranker")
        File(dir, today).writeText("different quartz minerals\nsecond quartz sample\n")
        val engine = MemoryRecallEngine({ dir }, clock = { now })
        return engine to engine.recall("quartz").also { assertEquals(2, it.size) }
    }
}
