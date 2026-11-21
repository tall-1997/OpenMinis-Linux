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
     * Classifies [revision] against [day]'s file.
     *
     * [dayFileExists] is required: "no log today" and "log was rewritten" both
     * fail a hash comparison but mean opposite things, and only the file's
     * existence tells them apart.
     */
    fun rejection(
        revision: String,
        day: String,
        fileName: String,
        dayFileExists: Boolean,
    ): Rejection? {
        val parsed = parse(revision)
        if (parsed.day != null && parsed.day != day) {
            return Rejection.DayRolledOver(parsed.day, day, dayFileExists)
        }
        if (!dayFileExists) return Rejection.NoLogToday(fileName)
        return null
    }
}
