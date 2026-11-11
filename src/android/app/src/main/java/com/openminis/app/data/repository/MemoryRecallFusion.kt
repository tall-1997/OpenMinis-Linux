package com.openminis.app.data.repository

import com.openminis.app.data.memory.MemoryEmbeddingSupport
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * [T-memory-embedding-recall] Pure keyword+semantic merge (#27), extracted
 * verbatim from [MemoryRecallEngine] so the engine file stays under the
 * new-file cap. FTS hits keep their order and scores; semantic hits dedupe
 * against them (exact recall key plus the pass-3 head-prefix guard) and fill
 * only the slots left free under [merge]'s `limit` and `perFileCap`. Displayed
 * semantic scores are clamped below half the weakest FTS score so the sort
 * weight stays strictly below every keyword hit, exactly like the slot rule
 * implies. Pure JVM — no Android, no Room — which is what makes the fusion
 * unit-testable.
 */
internal object SemanticRecallFusion {

    fun merge(
        fts: List<MemoryRecallEngine.RecallHit>,
        semantic: List<MemoryEmbeddingSupport.SemanticHit>,
        limit: Int,
        perFileCap: Int,
        archiveCutoff: LocalDate?,
        dayKeyOf: (String) -> String,
    ): List<MemoryRecallEngine.RecallHit> {
        if (semantic.isEmpty() || limit <= 0) return fts
        // When keyword search found nothing, semantic hits are the only
        // signal and keep their cosine score; otherwise every semantic
        // score is clamped below half the weakest keyword score.
        val scoreFloor = fts.minOfOrNull { it.score }?.let { it * 0.5 } ?: Double.MAX_VALUE
        val perFile = HashMap<String, Int>()
        val keys = HashSet<String>()
        val heads = ArrayList<Pair<String, String>>() // (source, head) of accepted hits
        for (hit in fts) {
            keys.add("${dayKeyOf(hit.source)}:${hit.line.trim()}")
            heads.add(hit.source to hit.line.take(30))
            perFile.merge(hit.source, 1, Int::plus)
        }
        val out = ArrayList<MemoryRecallEngine.RecallHit>(fts)
        for (s in semantic) {
            if (out.size >= limit) break
            val key = "${dayKeyOf(s.source)}:${s.line.trim()}"
            if (key in keys) continue
            val head = s.line.take(30)
            if (heads.any { (src, h) -> src == s.source && (h.startsWith(head) || head.startsWith(h)) }) continue
            val used = perFile.merge(s.source, 1, Int::plus) ?: 1
            if (used > perFileCap) continue
            val entryDay = runCatching { LocalDate.parse(dayKeyOf(s.source), DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
            out += MemoryRecallEngine.RecallHit(
                source = s.source,
                line = s.line,
                day = dayKeyOf(s.source),
                score = minOf(s.score.toDouble(), scoreFloor),
                recallCount = 0,
                // Same aging flag as the keyword path: an old semantic hit
                // is surfaced faded, not hidden. Undated lines are never
                // archived.
                archived = entryDay != null && archiveCutoff != null && entryDay.isBefore(archiveCutoff),
                scoreDetail = "semantic=%.2f".format(s.score),
            )
            keys.add(key)
            heads.add(s.source to head)
        }
        return out
    }
}
