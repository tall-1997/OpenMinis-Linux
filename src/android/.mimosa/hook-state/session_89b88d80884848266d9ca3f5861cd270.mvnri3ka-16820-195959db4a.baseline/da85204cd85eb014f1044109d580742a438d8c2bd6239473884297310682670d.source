package com.openminis.app.data.body

import com.openminis.app.diagnostics.RouteFuse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Random

class BodyStoreTest {
    @Test
    fun put_round_trips_and_a_killed_temp_is_not_a_body() {
        val root = tempRoot()
        val store = BodyStore(root)
        val payload = Random(7).let { rng -> ByteArray(4096).also { rng.nextBytes(it) } }
        val put = store.put(payload)
        assertTrue(put.error, put.ok)
        assertEquals(payload.toList(), store.read(put.ref!!, payload.size)!!.toList())
        val kept = File(root, put.ref!!)
        val orphan = File(root, "deadbeef.tmp").apply { writeText("partial") }
        BodyStore(root).discardTemps(
            maxAgeMillis = 0,
            nowMillis = orphan.lastModified() + 1,
        )
        assertFalse(File(root, "deadbeef.tmp").exists())
        assertTrue(kept.isFile)
        assertNull(store.read("../secrets"))
        root.deleteRecursively()
    }

    @Test
    fun read_rejects_body_whose_content_hash_does_not_match_ref() {
        val root = tempRoot()
        val store = BodyStore(root)
        val put = store.put("trusted".toByteArray())
        val body = File(root, put.ref!!)
        val bytes = body.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        body.writeBytes(bytes)
        assertNull(store.read(put.ref!!, 1024))
        root.deleteRecursively()
    }

    @Test
    fun discardTemps_keeps_recent_temp_but_removes_old_orphan() {
        val root = tempRoot()
        val recent = File(root, "recent.tmp").apply { writeText("in flight") }
        val old = File(root, "old.tmp").apply { writeText("orphan") }
        val now = old.lastModified() + 2_000
        recent.setLastModified(now - 500)
        old.setLastModified(now - 2_000)
        BodyStore(root).discardTemps(maxAgeMillis = 1_000, nowMillis = now)
        assertTrue(recent.exists())
        assertFalse(old.exists())
        root.deleteRecursively()
    }

    @Test
    fun over_cap_is_refused_before_a_file_is_written() {
        val root = tempRoot()
        val store = BodyStore(root)
        val huge = ByteArray(ResourceLimits.MAX_DECLARED_UNCOMPRESSED + 1)
        val put = store.put(huge)
        assertFalse(put.ok)
        assertTrue(root.listFiles().isNullOrEmpty())
        root.deleteRecursively()
    }

    @Test
    fun a_100mb_cell_is_refused_without_a_file() {
        val root = tempRoot()
        val cell = ByteArray(100 * 1024 * 1024)
        val put = BodyStore(root).put(cell)
        assertFalse(put.ok)
        assertNull(put.ref)
        assertTrue(root.listFiles().orEmpty().none { it.isFile && !it.name.endsWith(".tmp") })
        root.deleteRecursively()
    }

    @Test
    fun near_cap_raw_body_round_trips_under_admission_budget() {
        val root = tempRoot()
        val payload = ByteArray(ResourceLimits.MAX_DECLARED_UNCOMPRESSED) { (it * 31).toByte() }
        val put = BodyStore(root).put(payload)
        assertTrue(put.ok)
        assertEquals(payload.size, BodyStore(root).read(put.ref!!, payload.size)!!.size)
        root.deleteRecursively()
    }

    @Test
    fun a_compression_bomb_is_refused_before_inflate() {
        val root = tempRoot()
        val declared = 1024 * 1024
        val stored = 16
        val file = File(root, "ab".repeat(32))
        file.writeBytes(
            byteArrayOf('O'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte()) +
                intBytes(declared) + intBytes(stored) + byteArrayOf(2) + ByteArray(stored),
        )
        assertNull(BodyStore(root).read(file.name, declared))
        root.deleteRecursively()
    }

    @Test
    fun disk_full_does_not_return_the_original_bytes() {
        val root = tempRoot()
        val secret = "do-not-keep-this-payload".toByteArray()
        val store = object : BodyStore(root) {
            override fun writeAtomic(tmp: File, bytes: ByteArray) {
                throw IOException("No space left on device")
            }
        }
        val put = store.put(secret)
        assertFalse(put.ok)
        assertNull(put.ref)
        assertFalse(put.error.orEmpty().contains("do-not-keep"))
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        root.deleteRecursively()
    }

    private fun tempRoot(): File = File.createTempFile("bodies", "").apply {
        delete()
        mkdirs()
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}

class PreviewBudgetTest {
    @Test
    fun four_hundred_rows_stop_on_total_bytes_not_row_count() {
        val row = ResourceLimits.PREVIEW_BYTES.toLong()
        val taken = PreviewBudget.fittingCount(row, 400, ResourceLimits.SESSION_PREVIEW_BUDGET.toLong())
        assertTrue(taken < 400)
        assertEquals(ResourceLimits.SESSION_PREVIEW_BUDGET.toLong() / row, taken)
    }

    @Test
    fun a_million_short_rows_do_not_all_fit() {
        val taken = PreviewBudget.fittingCount(64, 1_000_000, ResourceLimits.SESSION_PREVIEW_BUDGET.toLong())
        assertTrue(taken < 1_000_000)
        assertFalse(PreviewBudget.canTake(0, -1, ResourceLimits.SESSION_PREVIEW_BUDGET.toLong()))
    }
}

class RouteFuseTest {
    @Test
    fun a_restart_without_a_tombstone_does_not_auto_enter() {
        val dir = File.createTempFile("fuse", "").apply {
            delete()
            mkdirs()
        }
        RouteFuse.note(dir, "chat/killed", 1_000)
        assertTrue(RouteFuse.blocks(dir, "chat/killed"))
        RouteFuse.markHealthyAt(dir, "chat/killed", 1_000 + 1_000)
        assertTrue(RouteFuse.blocks(dir, "chat/killed"))
        RouteFuse.markHealthyAt(dir, "chat/killed", 1_000 + ResourceLimits.HEALTHY_TICK_MS)
        assertFalse(RouteFuse.blocks(dir, "chat/killed"))
        dir.deleteRecursively()
    }
}
