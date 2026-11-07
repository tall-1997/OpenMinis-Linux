package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-memory-recall-persist] Recall counts survive process restarts via the
 * `.recall-counts.json` sidecar, and scoreDetail explains the composition.
 *
 * [T-android-memory-recall-r2] GLOBAL.md is no longer a recall candidate
 * (its full text rides in the system prompt every turn), so the fixtures
 * below use a dated log file. The recall-count bump persists as metadata
 * but no longer contributes to the ranking score.
 */
class MemoryRecallEnginePersistTest {

    private fun tempDir(): File = Files.createTempDirectory("recall").toFile()

    private fun engineWith(dir: File): MemoryRecallEngine {
        File(dir, "2026-10-03.md").writeText("用户偏好：安静模式\n中午的咖啡店在楼下\n")
        return MemoryRecallEngine.fromDir(dir)!!
    }

    @Test
    fun acceptRecallIncrementsAndPersistsAcrossInstances() {
        val dir = tempDir()
        val e1 = engineWith(dir)
        e1.acceptRecall("2026-10-03.md", "用户偏好：安静模式")
        e1.acceptRecall("2026-10-03.md", "用户偏好：安静模式")

        val sidecar = File(dir, ".recall-counts.json")
        assertTrue("sidecar must exist after acceptRecall", sidecar.isFile)
        assertTrue("sidecar must carry the bumped key", sidecar.readText().contains("安静模式"))

        // A brand-new engine (process restart) loads the persisted counts.
        val e2 = MemoryRecallEngine.fromDir(dir)!!
        val snapshot = e2.recallCountsSnapshot()
        val key = snapshot.keys.first { it.contains("安静模式") }
        assertEquals(2, snapshot[key])
    }

    @Test
    fun recallCountIsCarriedAsMetadataAndDetailExplainsTheScore() {
        val dir = tempDir()
        val e1 = engineWith(dir)
        repeat(12) { e1.acceptRecall("2026-10-03.md", "用户偏好：安静模式") }

        val e2 = MemoryRecallEngine.fromDir(dir)!!
        val hits = e2.recall("安静模式")
        assertTrue("query must match the line", hits.isNotEmpty())
        val hit = hits.first()
        assertEquals(12, hit.recallCount)
        assertTrue(
            "scoreDetail must explain the BM25 + recency composition",
            hit.scoreDetail.contains("bm25=") && hit.scoreDetail.contains("recency="),
        )
    }

    @Test
    fun loadRecallCountsIgnoresUnreadableSidecar() {
        val dir = tempDir()
        val corrupt = File(dir, ".recall-counts.json")
        corrupt.writeText("{ not json")
        val engine = engineWith(dir)
        // Must not throw; starts with an empty count map.
        assertTrue(engine.recallCountsSnapshot().isEmpty())
    }
}
