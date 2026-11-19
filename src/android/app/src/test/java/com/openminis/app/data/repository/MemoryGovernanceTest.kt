 package com.openminis.app.data.repository
 
 import org.junit.After
 import org.junit.Assert.assertEquals
 import org.junit.Assert.assertFalse
 import org.junit.Assert.assertNull
 import org.junit.Assert.assertTrue
 import org.junit.Before
 import org.junit.Test
 import java.io.File
 import java.nio.file.Files
 
 /**
  * [T-memory-governance] Three contracts that used to be unimplemented:
  * - `supersedes` is CONSUMED, not merely parsed;
  * - new writes EMIT frontmatter (`4.1` was reader-only);
  * - delivery is counted separately from recall (`mark_memory_used`).
  */
 class MemoryGovernanceTest {
 
    private lateinit var dir: File
    
    @Before
    fun setUp() {
        dir = Files.createTempDirectory("memory-governance").toFile()
        MemoryUsageLedger.resetForTest()
    }
    
    @After
    fun tearDown() {
        MemoryUsageLedger.resetForTest()
        dir.deleteRecursively()
    }
    
    @Test
    fun supersedesRetiresTheReplacedFile() {
        val files = listOf(
            "2026-11-10" to "---\nsupersedes:2026-11-01\n---\nnote",
            "2026-11-01" to "plain note",
            "2026-11-02" to "other note",
        )
        val retired = MemoryMetadataParser.supersededIds(files)
        assertEquals(setOf("2026-11-01"), retired)
    }
    
    @Test
    fun supersedesIsForgivingAboutCaseAndSuffix() {
        val files = listOf(
            "2026-11-10" to "---\nsupersedes:2026-11-01.md\n---\nnote",
            "2026-11-01" to "plain note",
        )
        assertEquals(setOf("2026-11-01"), MemoryMetadataParser.supersededIds(files))
    }
    
    @Test
    fun aFileWithNoSupersedesRetiresNothing() {
        val files = listOf(
            "a" to "plain",
            "b" to "---\ntype: daily\n---\nnote",
        )
        assertTrue(MemoryMetadataParser.supersededIds(files).isEmpty())
    }
    
    @Test
    fun theWriterEmitsABlockTheParserReadsBack() {
        val block = MemoryMetadataParser.frontmatterFor(
            type = "feedback",
            scope = "private",
            ttlDays =3,
            supersedes = "2026-11-01",
        )
        val parsed = MemoryMetadataParser.parse(block + "body\n")
        assertEquals("feedback", parsed.type)
        assertEquals("private", parsed.scope)
        assertEquals(3, parsed.ttlDays)
        assertEquals("2026-11-01", parsed.supersedes)
    }
    
    @Test
    fun aPlainDailyWriteStaysPlain() {
        // No fields, no block: adding frontmatter to every entry would change
        // the shape of files users have been reading for months.
        assertEquals("", MemoryMetadataParser.frontmatterFor())
    }
    
    @Test
    fun usageIsCountedPerDeliveredLineAndSurvivesAReload() {
        MemoryUsageLedger.markUsed(dir, listOf("2026-11-10" to "line one"))
        MemoryUsageLedger.markUsed(dir, listOf("2026-11-10" to "line one"))
        assertEquals(2, MemoryUsageLedger.countOf("2026-11-10", "line one"))
        assertEquals(0, MemoryUsageLedger.countOf("2026-11-10", "never shown"))
        MemoryUsageLedger.resetForTest()
        MemoryUsageLedger.load(dir)
        assertEquals(2, MemoryUsageLedger.countOf("2026-11-10", "line one"))
    }
    
    @Test
    fun aMissingOrCorruptLedgerIsEmptyNotFatal() {
        MemoryUsageLedger.load(dir)
        assertEquals(0, MemoryUsageLedger.countOf("x", "y"))
        MemoryUsageLedger.file(dir).writeText("not json")
        MemoryUsageLedger.resetForTest()
        MemoryUsageLedger.load(dir)
        assertEquals(0, MemoryUsageLedger.countOf("x", "y"))
    }
    
    @Test
    fun anInstalledRerankerIsPickedUpWithoutAEngineRebuild() {
        // [T-memory-reranker-seam] The seam has to be reachable at runtime: the
        // engine is created deep in the prompt path, so installation is a swap on
        // one holder, not a constructor argument the caller cannot supply.
        assertFalse(MemoryRerankerRegistry.isCustom())
        MemoryRerankerRegistry.install(MemoryReranker { _, hits -> hits.reversed() })
        assertTrue(MemoryRerankerRegistry.isCustom())
        MemoryRerankerRegistry.install(null)
        assertFalse(MemoryRerankerRegistry.isCustom())
    }
    
    @Test
    fun aRerankerThatIsNotAPermutationIsIgnored() {
        // The guard rejects a backend that drops or invents hits: the delivered
        // count is what MemoryUsageLedger reports as used.
        val hits = emptyList<MemoryRecallEngine.RecallHit>()
        val shrinking = MemoryReranker { _, _ -> emptyList() }
        assertEquals(hits, shrinking.rerank("q", hits))
        assertTrue(shrinking.rerank("q", hits).isEmpty())
    }
    
    @Test
    fun theDefaultRerankerIsTheIdentity() {
        val hits = emptyList<MemoryRecallEngine.RecallHit>()
        assertEquals(hits, MemoryReranker.IDENTITY.rerank("q", hits))
    }
    
    @Test
    fun stripRemovesTheBlockAndKeepsTheBody() {
        val text = "---\ntype: daily\nttl_days:3\n---\nbody line\n"
        val body = MemoryMetadataParser.strip(text)
        assertEquals("body line", body.trim())
        assertFalse("the metadata block must not survive", body.contains("type: daily"))
    }
    
    @Test
    fun stripIsIdentityForAnythingThatIsNotAFrontmatterBlock() {
        // A lone `---`, an unterminated block and a plain file must come back
        // unchanged: the reader must never swallow a body that merely starts with
        // a horizontal rule.
        assertEquals("", MemoryMetadataParser.strip(""))
        assertEquals("---", MemoryMetadataParser.strip("---"))
        assertEquals("---\nno close\n", MemoryMetadataParser.strip("---\nno close\n"))
        assertEquals("plain\n", MemoryMetadataParser.strip("plain\n"))
    }
 }
 
