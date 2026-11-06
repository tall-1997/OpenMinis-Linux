package com.openminis.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-folder-category-icon] Contract for the composed folder icon.
 *
 * A folder renders with the SAME category iconography as the category panel:
 * one session → that session's own category icon; two → a 2×1 pair; three to
 * four → 2×2; five to nine → 3×3. Anything past nine does NOT participate.
 */
class FolderIconLayoutTest {

    @Test
    fun `single member keeps the folder on its own category`() {
        val layout = folderIconLayout(1)
        assertEquals(1, layout.columns)
        assertEquals(1, layout.rows)
        assertEquals(listOf("code"), folderIconCategories(listOf("code")))
    }

    @Test
    fun `two members pair into a single row`() {
        val layout = folderIconLayout(2)
        assertEquals(2, layout.columns)
        assertEquals(1, layout.rows)
        assertEquals(2, layout.capacity)
    }

    @Test
    fun `three and four members fill a two by two grid`() {
        assertEquals(2, folderIconLayout(3).columns)
        assertEquals(2, folderIconLayout(3).rows)
        assertEquals(4, folderIconLayout(3).capacity)
        assertEquals(2, folderIconLayout(4).columns)
        assertEquals(2, folderIconLayout(4).rows)
        assertEquals(4, folderIconLayout(4).capacity)
    }

    @Test
    fun `five through nine members fill a three by three grid`() {
        for (count in 5..9) {
            val layout = folderIconLayout(count)
            assertEquals("count=$count", 3, layout.columns)
            assertEquals("count=$count", 3, layout.rows)
            assertEquals("count=$count", 9, layout.capacity)
        }
    }

    @Test
    fun `participation stops at nine and keeps recency order`() {
        val categories = (1..12).map { "cat$it" }
        assertEquals(9, folderIconCategories(categories).size)
        assertEquals(categories.take(9), folderIconCategories(categories))
    }

    @Test
    fun `null categories survive so the fallback glyph can stand in`() {
        assertEquals(listOf(null, "code"), folderIconCategories(listOf(null, "code")))
    }

    @Test
    fun `empty folder has no participants`() {
        assertTrue(folderIconCategories(emptyList()).isEmpty())
        assertEquals(1, folderIconLayout(0).capacity)
    }

    @Test
    fun `capacity never exceeds the nine slot grid`() {
        for (count in 0..40) {
            val layout = folderIconLayout(count)
            assertTrue("count=$count", layout.capacity <= 9)
            assertTrue("count=$count", layout.columns * layout.rows == layout.capacity)
        }
    }
}
