package com.openminis.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/proc/self/cmdline` parsing — the fallback rung of
 * [ProcessChecks.currentProcessName] that must work on every API level the
 * app ships to, including the ones without
 * [android.app.Application.getProcessName].
 *
 * Pinned as a pure function because a wrong answer here is not a cosmetic
 * bug: the process guard in `MinisApp.onCreate` uses the same name to decide
 * whether to bind the sandbox's single-instance abstract socket, and getting
 * it wrong in a sub-process is exactly the crash this ladder exists to
 * prevent ([T-android-a2a-process-crash]).
 */
class ProcessChecksTest {

    /** zygote writes "<name>\0" — the first token is the whole name. */
    @Test
    fun mainProcessName() {
        assertEquals(
            "com.openminis.linux",
            parseProcessName("com.openminis.linux\u0000".toByteArray()),
        )
    }

    /** A `android:process=":a2a"` component gets "<package>:a2a". */
    @Test
    fun subProcessName() {
        assertEquals(
            "com.openminis.linux:a2a",
            parseProcessName("com.openminis.linux:a2a\u0000".toByteArray()),
        )
    }

    /** Trailing argv entries (rare in Android, legal on Linux) are ignored. */
    @Test
    fun onlyFirstArg() {
        assertEquals(
            "com.openminis.linux",
            parseProcessName("com.openminis.linux\u0000--flag\u0000value".toByteArray()),
        )
    }

    /** No NUL terminator: the whole blob is the name. */
    @Test
    fun noTerminator() {
        assertEquals("com.openminis.linux", parseProcessName("com.openminis.linux".toByteArray()))
    }

    /** Empty cmdline (kernel threads / unreadable) must not become "". */
    @Test
    fun emptyBlob() {
        assertNull(parseProcessName(ByteArray(0)))
    }

    /** A leading NUL is an empty name, not the package name. */
    @Test
    fun leadingNul() {
        assertNull(parseProcessName("\u0000com.openminis.linux".toByteArray()))
    }
}
