 package com.openminis.app.data.repository
 
 import java.io.File
 
 /**
  * [T-memory-recall-sources] The daily files recall may read, with their text.
  *
  * WHY a separate file: `MemoryRecallEngine` is at its architecture ceiling,
  * and this decision is a rule rather than engine mechanics — the same
  * eligibility (`disabled` / TTL) and `supersedes` rules the prompt window
  * applies must gate recall too, or a memory the prompt deliberately withheld
  * re-enters through the recall path.
  *
  * Each file is read ONCE; the text serves both the metadata decision and the
  * candidate scan. The previous shape read the whole directory twice per
  * recall, on a path that runs for every new user message.
  */
 internal fun recallSources(files: List<File>, nowMs: Long): List<Pair<File, String>> {
    val read = files.mapNotNull { f ->
        readTextResilient(f, "MemoryRecall")?.let { f to it }
    }
    val superseded = MemoryMetadataParser.supersededIds(
        read.map { (f, text) -> f.name.removeSuffix(".md") to text },
    )
    return read.filter { (f, text) ->
        f.name.removeSuffix(".md") !in superseded &&
        MemoryMetadataParser.isEligible(text, nowMs, f.lastModified())
    }
 }
 
