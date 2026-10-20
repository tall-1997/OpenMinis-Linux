package com.openminis.app.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-image-elision-sticky] A previously elided image must stay elided even when
 * the current request would fit it under the byte cap again (compaction lowered
 * the total). Re-including it rewrites a historical message, which invalidates
 * the provider prefix cache and changes what the model saw earlier.
 */
class ImageBudgetForcedDropTest {

    private fun image(byte: Byte) = ImageBudget.BudgetImage(
        data = byteArrayOf(byte, byte, byte, byte),
        linuxPath = null,
        mimeType = "image/jpeg",
    )

    @Test
    fun `forced image is dropped even when the budget would keep it`() {
        val old = image(1)
        val fresh = image(2)
        val plan = ImageBudget.planRequestBudget(
            images = listOf(old, fresh),
            maxBytes = 1_000,
            forceDrop = setOf(ImageBudget.ImagePartId.of(old.data)),
        )
        assertTrue(ImageBudget.ImagePartId.of(old.data) in plan.droppedIds)
        assertFalse(ImageBudget.ImagePartId.of(fresh.data) in plan.droppedIds)
        assertTrue(plan.mutated)
    }

    @Test
    fun `content-equal image bytes share a sticky id across rebuilds`() {
        // History reloads decode fresh arrays; content hashing keeps the id.
        assertTrue(
            ImageBudget.ImagePartId.of(byteArrayOf(9, 8, 7)) ==
                ImageBudget.ImagePartId.of(byteArrayOf(9, 8, 7)),
        )
    }
}
