package com.openminis.app.evolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LearnedPrefsStoreTest {

    @Test
    fun addAndInjectCaps() {
        val dir = Files.createTempDirectory("learned-prefs").toFile()
        val store = LearnedPrefsStore(dir.resolve("LEARNED.md"))
        assertNull(store.promptFragment())
        assertTrue(store.addBullet("Prefer bash over zsh"))
        assertFalse(store.addBullet("- Prefer bash over zsh"))
        assertTrue(store.addBullet("Never write SOUL.md"))
        val items = store.readItems()
        assertEquals(2, items.size)
        val frag = store.promptFragment()
        assertTrue(frag!!.contains("Prefer bash over zsh"))
        assertTrue(frag.contains("LEARNED.md"))
    }

    @Test
    fun restoreRegionRollsBack() {
        val dir = Files.createTempDirectory("learned-prefs").toFile()
        val store = LearnedPrefsStore(dir.resolve("LEARNED.md"))
        store.addBullet("rule one")
        val snap = store.snapshotRegion()
        store.addBullet("rule two")
        assertEquals(2, store.readItems().size)
        store.restoreRegion(snap)
        assertEquals(listOf("- rule one"), store.readItems())
    }

    @Test
    fun fragmentDropsOldestToFitByteCap() {
        val dir = Files.createTempDirectory("learned-prefs").toFile()
        val store = LearnedPrefsStore(dir.resolve("LEARNED.md"))
        repeat(12) { i ->
            store.addBullet("Rule number $i " + "x".repeat(120))
        }
        val frag = store.promptFragment()!!
        assertTrue(frag.toByteArray(Charsets.UTF_8).size <= LearnedPrefsStore.MAX_FRAGMENT_BYTES)
        assertTrue(store.readItems().size == 12)
    }

    @Test
    fun parseSceneAndCoreTags() {
        val item = LearnedPrefsStore.parseItem("- [core] [backend] Prefer apt")
        assertTrue(item.core)
        assertEquals(SceneTag.BACKEND, item.scene)
        assertEquals("Prefer apt", item.body)
        val plain = LearnedPrefsStore.parseItem("- Always be brief")
        assertFalse(plain.core)
        assertEquals(SceneTag.GENERAL, plain.scene)
        assertEquals("Always be brief", plain.body)
    }

    @Test
    fun fragmentFiltersSceneAndKeepsGeneral() {
        val dir = Files.createTempDirectory("learned-prefs-scene").toFile()
        val store = LearnedPrefsStore(dir.resolve("LEARNED.md"))
        assertTrue(store.addBullet("Always be brief"))
        assertTrue(store.addBullet("Use apt-get", SceneTag.BACKEND))
        assertTrue(store.addBullet("Confirm deletes", SceneTag.WORKFLOW, core = true))
        val backend = store.promptFragment(SceneTag.BACKEND)!!
        assertTrue(backend.contains("Always be brief"))
        assertTrue(backend.contains("Use apt-get"))
        assertFalse(backend.contains("Confirm deletes"))
        val workflow = store.promptFragment(SceneTag.WORKFLOW)!!
        assertTrue(workflow.contains("Always be brief"))
        assertTrue(workflow.contains("Confirm deletes"))
        assertFalse(workflow.contains("Use apt-get"))
        val preview = store.previewFragment()!!
        assertTrue(preview.contains("Use apt-get"))
        assertTrue(preview.contains("Confirm deletes"))
        assertTrue(preview.contains("[backend]") || preview.contains("Use apt-get"))
    }

    @Test
    fun fragmentKeepsCoreWhenOverCap() {
        val dir = Files.createTempDirectory("learned-prefs-core").toFile()
        val store = LearnedPrefsStore(dir.resolve("LEARNED.md"))
        repeat(12) { i ->
            store.addBullet("Filler $i " + "y".repeat(80))
        }
        assertTrue(store.addBullet("Keep this core rule", SceneTag.GENERAL, core = true))
        val frag = store.promptFragment()!!
        assertTrue(frag.contains("Keep this core rule"))
        assertTrue(frag.toByteArray(Charsets.UTF_8).size <= LearnedPrefsStore.MAX_FRAGMENT_BYTES)
    }
}
