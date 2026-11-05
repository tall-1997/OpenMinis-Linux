package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-dispatch-idempotence] Same briefs = same wave.
 *
 * A dispatch was a plain side effect, so a retried call or a model re-emitting
 * `spawn_agent` produced a second wave doing identical work. The fingerprint is
 * what makes the second call recognisable as the same one.
 */
class SubAgentBatchRegistryFingerprintTest {

    private val sid = "chat-dedupe"

    @Before
    fun reset() {
        clearAll()
    }

    @After
    fun tearDown() {
        clearAll()
    }

    private fun clearAll() {
        SubAgentBatchRegistry.list(sid, null).forEach { batch ->
            batch.lanes.filter { !it.finished }.forEach { lane ->
                SubAgentBatchRegistry.markFinished(
                    sid, null, batch.id, lane.index,
                    success = true, output = "cleanup", error = null, attempts = 1,
                )
            }
        }
    }

    private fun args(vararg tasks: Pair<String, String>): String {
        val json = tasks.joinToString(",") { (kind, prompt) ->
            """{"kind":"$kind","prompt":"$prompt"}"""
        }
        return """{"tasks":[$json]}"""
    }

    @Test
    fun identicalBriefsShareAFingerprintRegardlessOfOrder() {
        val a = SubAgentBatchRegistry.fingerprintOf(
            args("explore" to "read the parser", "worker" to "fix the test"),
        )
        val b = SubAgentBatchRegistry.fingerprintOf(
            args("worker" to "fix the test", "explore" to "read the parser"),
        )
        assertEquals(a, b)
        assertTrue(a.isNotBlank())
    }

    @Test
    fun aDifferentBriefIsADifferentWave() {
        val a = SubAgentBatchRegistry.fingerprintOf(args("explore" to "read the parser"))
        val b = SubAgentBatchRegistry.fingerprintOf(args("explore" to "read the lexer"))
        assertNotEquals(a, b)
    }

    @Test
    fun theKindIsPartOfTheIdentity() {
        val a = SubAgentBatchRegistry.fingerprintOf(args("explore" to "read it"))
        val b = SubAgentBatchRegistry.fingerprintOf(args("worker" to "read it"))
        assertNotEquals(a, b)
    }

    @Test
    fun anEmptyDispatchHasNoIdentity() {
        assertEquals("", SubAgentBatchRegistry.fingerprintOf("""{"tasks":[]}"""))
        assertEquals("", SubAgentBatchRegistry.fingerprintOf("""{"tool_title":"t"}"""))
        assertEquals("", SubAgentBatchRegistry.fingerprintOf("not json"))
    }

    @Test
    fun aSingleTaskSpawnIsAlsoFingerprinted() {
        val one = SubAgentBatchRegistry.fingerprintOf("""{"kind":"worker","prompt":"do it"}""")
        assertTrue(one.isNotBlank())
        val same = SubAgentBatchRegistry.fingerprintOf("""{"tasks":[{"kind":"worker","prompt":"do it"}]}""")
        assertEquals(one, same)
    }

    @Test
    fun aLiveBatchIsFoundAgainAndAFinishedOneIsNot() {
        val spawn = args("explore" to "read the parser")
        val fp = SubAgentBatchRegistry.fingerprintOf(spawn)
        val batch = SubAgentBatchRegistry.begin(
            sid, null, toolId = "t1", assistantId = "a1", background = true,
            seeds = listOf(
                SubAgentBatchRegistry.LaneSeed(
                    index = 0,
                    kind = "explore",
                    title = "recon",
                    prompt = "read the parser",
                ),
            ),
        )
        assertEquals(fp, SubAgentBatchRegistry.fingerprintOf(batch))

        val found = SubAgentBatchRegistry.findLiveDuplicate(sid, null, fp)
        assertNotNull(found)
        assertEquals(batch.id, found!!.id)

        // Finished waves are not duplicates: re-running after completion is legal.
        SubAgentBatchRegistry.markFinished(
            sid, null, batch.id, 0, success = true, output = "done", error = null, attempts = 1,
        )
        assertNull(SubAgentBatchRegistry.findLiveDuplicate(sid, null, fp))
    }

    @Test
    fun aBatchOutsideTheWindowIsNotADuplicate() {
        val spawn = args("worker" to "run the migration")
        val fp = SubAgentBatchRegistry.fingerprintOf(spawn)
        val batch = SubAgentBatchRegistry.begin(
            sid, null, toolId = "t2", assistantId = "a2", background = true,
            seeds = listOf(
                SubAgentBatchRegistry.LaneSeed(index = 0, kind = "worker", title = "w", prompt = "run the migration"),
            ),
        )
        val later = batch.createdAt + SubAgentBatchRegistry.DUPLICATE_WINDOW_MS + 1
        assertNull(SubAgentBatchRegistry.findLiveDuplicate(sid, null, fp, nowMs = later))
        assertNotNull(
            SubAgentBatchRegistry.findLiveDuplicate(
                sid, null, fp,
                nowMs = batch.createdAt + SubAgentBatchRegistry.DUPLICATE_WINDOW_MS - 1,
            ),
        )
    }

    @Test
    fun blankFingerprintsNeverMatch() {
        assertNull(SubAgentBatchRegistry.findLiveDuplicate(sid, null, ""))
    }
}
