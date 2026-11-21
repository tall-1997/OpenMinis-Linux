package com.openminis.app.data.repository

import com.openminis.app.util.Digest

/**
 * [T-memory-revision-day-scoped] A memory revision is only meaningful for the
 * day file it was minted from.
 *
 * Before this, `writeMemory` compared a bare 16-char hash against whatever the
 * day file held. Past midnight that produced a lie: the caller (an agent that
 * had just written yesterday's log) handed back yesterday's hash, the file for
 * today did not exist yet, `existing` was `""`, its SHA-256 was
 * `e3b0c44298fc1c14` — and the write was refused with "日志文件已被并发更新".
 * The reader then hunts for a concurrent writer that never existed, when the
 * real event is "the log rolled over".
 *
 * The fix makes a revision self-describing (`YYYY-MM-DD:hash16`) and compares
 * the day BEFORE the hash, so the two failures are distinguishable. This module
 * is pure logic: it answers with a [Rejection] variant, and the repository owns
 * the wording (user-facing copy lives with the rest of the memory strings).
 */
internal object MemoryRevision {

    private const val HASH_CHARS = 16
    private const val SEPARATOR = ':'

    // [T-memory-revision-degenerate-inputs] A defensive predicate must never be
    // driven by a parsed value the parser did not fully shape: `startsWith("")`
    // is always true, so `revision="2026-11-21:"` (empty hash), any 1..15-char
    // prefix, or a multi-colon form walked straight past the concurrency check
    // (reproduced on JVM before the fix). Both segments are shape-checked here,
    // and the guard compares hashes for FULL equality, never as a prefix.
    private val DAY_SHAPE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val HASH_SHAPE = Regex("""[0-9a-f]{16}""")

    /** `2026-11-20:ca9639251e3d29e3` — the canonical token for [day] + [content]. */
    fun token(day: String, content: String): String =
        "$day$SEPARATOR" + Digest.sha256Hex(content).take(HASH_CHARS)

    /** A parsed revision: the day it was minted for, plus its hash prefix. */
    internal data class Parsed(val day: String?, val hash: String)

    /**
     * Why [revision] cannot be used, or null when the caller should go on to
     * compare hashes. A revision minted before the day prefix existed comes
     * back with `day == null`: legacy callers keep working, they just do not
     * get the cross-midnight diagnosis.
     */
    internal sealed interface Rejection {
        /** The revision does not fit `YYYY-MM-DD:hash16` (nor a legacy hash16). */
        data class Malformed(val revision: String) : Rejection

        /** The revision belongs to a different day; the log rolled over. */
        data class DayRolledOver(
            val revisionDay: String,
            val today: String,
            /** Whether today's file already has content (append vs create). */
            val dayFileHasContent: Boolean,
        ) : Rejection

        /** No file for today yet, so there is nothing to validate against. */
        data class NoLogToday(val fileName: String) : Rejection
    }

    /** Splits `day:hash`; a legacy bare hash parses with `day == null`. */
    fun parse(revision: String): Parsed {
        val at = revision.indexOf(SEPARATOR)
        return if (at > 0) {
            Parsed(revision.substring(0, at), revision.substring(at + 1))
        } else {
            Parsed(null, revision)
        }
    }

    /**
     * Shape gate for the tool parameter ([F3]): true only for a token or a
     * legacy 16-hex hash. Cheaper than [rejection] and usable before any file
     * IO, so a garbled parameter fails at the argument layer.
     */
    fun wellFormed(revision: String): Boolean {
        val parsed = parse(revision)
        if (parsed.day != null && !DAY_SHAPE.matches(parsed.day)) return false
        return HASH_SHAPE.matches(parsed.hash)
    }

    /**
     * Full-equality comparison of a FULL sha256 hex (as the guard's [hashOf]
     * produces, 64 chars) against a minted token hash (16 chars). The guard
     * must never re-derive this as `fullDigestHex.startsWith(expected)` — that
     * form is always true for an empty expected hash and weakened for any
     * short one (the F1 hole, JVM-reproduced). Both inputs are shape-gated
     * before this runs.
     */
    fun hashMatches(fullDigestHex: String, expectedHash: String): Boolean =
        fullDigestHex.take(HASH_CHARS) == expectedHash

    /**
     * Classifies [revision] against [day]'s file.
     *
     * [dayFileExists] is required: "no log today" and "log was rewritten" both
     * fail a hash comparison but mean opposite things, and only the file's
     * existence tells them apart.
     *
     * Diagnosis order: a non-date day segment is [Rejection.Malformed] (no
     * rollover can be claimed for it); a legal day that is not today is
     * [Rejection.DayRolledOver] (the actionable diagnosis, whatever the hash
     * looks like); only a legal day equal to today goes on to demand a
     * well-shaped hash.
     */
    fun rejection(
        revision: String,
        day: String,
        fileName: String,
        dayFileExists: Boolean,
    ): Rejection? {
        val parsed = parse(revision)
        if (parsed.day != null) {
            if (!DAY_SHAPE.matches(parsed.day)) return Rejection.Malformed(revision)
            if (parsed.day != day) return Rejection.DayRolledOver(parsed.day, day, dayFileExists)
        }
        if (!HASH_SHAPE.matches(parsed.hash)) return Rejection.Malformed(revision)
        if (!dayFileExists) return Rejection.NoLogToday(fileName)
        return null
    }
}
