package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [T-once-notices] Delivery bookkeeping must survive a process restart. */
class NoticeLedgerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `recall keys and goal sha survive a reload`() {
        NoticeLedgerStore.save(
            tmp.root,
            "session-a",
            NoticeLedgerStore.State(
                recallKeys = setOf("2026-11-12:a line", "global:b line"),
                goalSha = "sha-1",
            ),
        )
        val loaded = NoticeLedgerStore.load(tmp.root, "session-a")
        assertEquals(setOf("2026-11-12:a line", "global:b line"), loaded.recallKeys)
        assertEquals("sha-1", loaded.goalSha)
    }

    @Test
    fun `sessions do not share state`() {
        NoticeLedgerStore.save(tmp.root, "s1", NoticeLedgerStore.State(setOf("k"), "g"))
        val other = NoticeLedgerStore.load(tmp.root, "s2")
        assertEquals(emptySet<String>(), other.recallKeys)
        assertNull(other.goalSha)
    }

    @Test
    fun `a corrupt file degrades to an empty state`() {
        val dir = java.io.File(tmp.root, "notices").apply { mkdirs() }
        java.io.File(dir, "broken.json").writeText("{not json")
        val loaded = NoticeLedgerStore.load(tmp.root, "broken")
        assertTrue(loaded.recallKeys.isEmpty())
        assertNull(loaded.goalSha)
    }
}
