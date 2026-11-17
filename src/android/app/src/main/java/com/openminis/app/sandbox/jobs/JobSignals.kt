package com.openminis.app.sandbox.jobs

/**
 * [T-job-signal-posix-literals] POSIX signal numbers as plain literals —
 * NOT android.system.OsConstants: the Robolectric mockable android.jar
 * (unit-test classpath) strips the ConstantValue of static final fields, so
 * every OsConstants reference resolves to 0 under tests while the real
 * values (15/10/...) only held in release builds. Signal numbers are POSIX,
 * not Android-specific, so literals are correct everywhere.
 */
object JobSignals {

    val NUMBERS = mapOf(
        "HUP" to 1,
        "INT" to 2,
        "QUIT" to 3,
        "ILL" to 4,
        "TRAP" to 5,
        "ABRT" to 6,
        "FPE" to 8,
        "KILL" to 9,
        "USR1" to 10,
        "SEGV" to 11,
        "USR2" to 12,
        "PIPE" to 13,
        "ALRM" to 14,
        "TERM" to 15,
        "CHLD" to 17,
        "CONT" to 18,
        "STOP" to 19,
        "TSTP" to 20,
        "TTIN" to 21,
        "TTOU" to 22,
        "URG" to 23,
        "XCPU" to 24,
        "XFSZ" to 25,
        "VTALRM" to 26,
        "PROF" to 27,
        "WINCH" to 28,
        "IO" to 29,
        "SYS" to 31,
    )

    /**
     * Guest signal name (or number) → host signal number. Numeric strings
     * pass through as numbers (bounded to the valid signal range); names
     * must exist in [NUMBERS] — an unknown name is NOT guessed.
     */
    fun number(sig: String): Int? = when {
        sig.matches(Regex("\\d+")) -> sig.toIntOrNull()?.takeIf { it in 1..64 }
        else -> NUMBERS[sig]
    }
}
