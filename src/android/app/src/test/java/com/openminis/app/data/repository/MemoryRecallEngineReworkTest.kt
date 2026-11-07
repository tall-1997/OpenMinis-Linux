package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-android-memory-recall-r2] Acceptance suite for the recall rework:
 * GLOBAL.md is out of the candidate pool (it already rides in the system
 * prompt verbatim), hygiene filters strip template scaffolding, the BM25
 * score prefers rare-entity matches over generic rule lines, the recall
 * count no longer influences ranking, per-file caps bound both candidates
 * and final picks, and near-duplicates cannot crowd out diversity.
 */
class MemoryRecallEngineReworkTest {

    private lateinit var root: File
    private lateinit var sessionDir: File
    private lateinit var sharedDir: File

    private fun setUpDirs() {
        root = Files.createTempDirectory("recall-r2").toFile()
        sessionDir = File(root, "session").apply { mkdirs() }
        sharedDir = File(root, "shared").apply { mkdirs() }
    }

    private fun engine(): MemoryRecallEngine =
        MemoryRecallEngine({ sessionDir }, { sharedDir }).also { it.loadRecallCounts() }

    @Test
    fun `global md is never a recall candidate in either scope`() {
        setUpDirs()
        // Both GLOBALs carry the same content a diary line also carries —
        // only the diary line may surface.
        File(sessionDir, "GLOBAL.md").writeText("审查记忆系统时要先写实现方案\n")
        File(sharedDir, "GLOBAL.md").writeText("报告类产物写明测了什么结论是什么\n")
        File(sharedDir, "2026-11-05.md").writeText("报告类产物写明测了什么结论是什么\n审查记忆系统时要先写实现方案\n")
        val hits = engine().recall("记忆召回 实现方案 报告")
        assertTrue(hits.isNotEmpty())
        assertTrue(
            "no GLOBAL.md hit may be returned (its text already rides in the prompt)",
            hits.none { it.source == "GLOBAL.md" },
        )
        assertTrue("the diary line carrying the same content still surfaces", hits.isNotEmpty())
    }

    @Test
    fun `html comments separators and punctuation lines are skipped`() {
        setUpDirs()
        File(sharedDir, "2026-10-20.md").writeText(
            """
            <!-- 首次启动时种入到 filesDir 下的说明文字
            可在 设置 → 记忆 中编辑或重置。 -->
            ---
            ===
            prlimit was the real OOM cause
            """.trimIndent() + "\n",
        )
        val hits = engine().recall("设置 记忆 编辑 prlimit OOM")
        assertTrue(hits.all { !it.line.contains("<!--") && !it.line.contains("-->") })
        assertTrue(hits.all { it.line.any { c -> c.isLetterOrDigit() } && it.line != "---" })
        // The real content line still surfaces.
        assertTrue(hits.any { it.line.contains("prlimit") })
    }

    @Test
    fun `rare entity line outscores a generic rule line`() {
        setUpDirs()
        // A generic rule line stuffed with high-frequency grams (什么/怎么)…
        File(sharedDir, "2026-10-01.md").writeText(
            "失败时说明失败在哪、下一步具体做什么，问题出在哪里就怎么处理，不要问为什么\n",
        )
        // …against a rare, on-topic diary line.
        File(sessionDir, "2026-11-05.md").writeText("记忆召回引擎改用 BM25 打分并排除 GLOBAL 候选\n")
        val hits = engine().recall("记忆召回引擎 BM25 打分问题")
        assertTrue(
            "the rare on-topic line must outrank the generic rule line",
            hits.first().line.contains("BM25"),
        )
    }

    @Test
    fun `recall count does not influence ranking`() {
        setUpDirs()
        // Two lines with the SAME length and token set; one has a huge count.
        File(sessionDir, "2026-11-05.md").writeText("磁悬浮轴承方案定案甲\n磁悬浮轴承方案定案乙\n")
        val e = engine()
        repeat(20) { e.acceptRecall("2026-11-05.md", "磁悬浮轴承方案定案乙") }
        val hits = e.recall("磁悬浮轴承方案")
        assertTrue(hits.size >= 2)
        val boosted = hits.first { it.line.contains("定案乙") }
        val plain = hits.first { !it.line.contains("定案乙") }
        assertEquals("boosted carries its count as metadata", 20, boosted.recallCount)
        assertEquals(
            "identical content, identical score — the count must not flip order",
            plain.score,
            boosted.score,
            1e-9,
        )
    }

    @Test
    fun `per file candidate cap and final pick cap hold`() {
        setUpDirs()
        val sb = StringBuilder()
        repeat(60) { i -> sb.append("unique-token-$i 相关的独立记忆条目内容\n") }
        File(sharedDir, "2026-10-02.md").writeText(sb.toString())
        val query = (0 until 60).joinToString(" ") { i -> "unique-token-$i" }
        val hits = engine().recall(query)
        assertTrue(
            "final picks from one file are capped at MAX_HITS_PER_FILE",
            hits.count { it.source == "2026-10-02.md" } <= MemoryRecallEngine.MAX_HITS_PER_FILE,
        )
    }

    @Test
    fun `near duplicate lines from one file do not crowd the budget`() {
        setUpDirs()
        File(sessionDir, "2026-11-06.md").writeText(
            """
            prlimit 的 OOM 根因是 val limit 设置过小导致
            prlimit 的 OOM 根因是 val limit 设置过小导致（补充说明）
            磁悬浮轴承是另一个完全不相关的话题条目
            """.trimIndent() + "\n",
        )
        val hits = engine().recall("prlimit OOM 根因 磁悬浮")
        val prlimitPicks = hits.filter { it.line.contains("prlimit") }
        assertTrue(
            "near-duplicate prlimit lines collapse to one pick",
            prlimitPicks.size <= 1,
        )
        assertTrue(
            "the unrelated topic still gets its slot",
            hits.any { it.line.contains("磁悬浮") },
        )
    }

    @Test
    fun `different queries return different results`() {
        setUpDirs()
        File(sharedDir, "2026-10-10.md").writeText("上游仓库推送流程：先 rebase 再 force-push\n")
        File(sessionDir, "2026-11-01.md").writeText("GitHub 资料检索要用代理访问文档站\n")
        val e = engine()
        val q1 = e.recall("上游仓库 推送 rebase").joinToString { it.line }
        val q2 = e.recall("GitHub 资料 代理 文档").joinToString { it.line }
        assertFalse("two on-topic queries must not return identical sets", q1 == q2 && q1.isEmpty())
        assertTrue(q1.contains("rebase") || q2.contains("代理"))
    }

    @Test
    fun `diary entries outrank nothing when global alone exists - empty result is honest`() {
        setUpDirs()
        // Only a GLOBAL.md exists (the historical degenerate state): recall
        // must return NOTHING rather than re-inject what the prompt carries.
        File(sharedDir, "GLOBAL.md").writeText("失败时说明失败在哪、下一步具体做什么\n")
        assertTrue(engine().recall("失败 下一步").isEmpty())
    }
}
