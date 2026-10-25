package com.openminis.app.data

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRouterTest {

    private fun entry(id: String, vararg inputs: String) = ModelEntry(
        providerInstanceId = "p",
        baseModel = LLMModel(
            id = id,
            displayName = id,
            provider = "test",
            inputModalities = inputs.toList(),
        ),
        uuid = id,
    )

    @Test
    fun `image turn prefers vision member`() {
        val text = entry("text", "text")
        val vision = entry("vision", "text", "image_input")
        val picked = CapabilityRouter.pickMembers(
            listOf(text, vision),
            setOf(ModelCapability.IMAGE_INPUT),
        )
        assertEquals(listOf("vision"), picked.map { it.id })
    }

    @Test
    fun `no capable member keeps original pool`() {
        val a = entry("a", "text")
        val b = entry("b", "text")
        val picked = CapabilityRouter.pickMembers(
            listOf(a, b),
            setOf(ModelCapability.IMAGE_INPUT),
        )
        assertEquals(listOf("a", "b"), picked.map { it.id })
    }

    @Test
    fun `empty need is a no-op`() {
        val a = entry("a", "text")
        assertEquals(listOf(a), CapabilityRouter.pickMembers(listOf(a), emptySet()))
    }

    @Test
    fun `neededForImages`() {
        assertEquals(emptySet<ModelCapability>(), CapabilityRouter.neededForImages(false))
        assertEquals(setOf(ModelCapability.IMAGE_INPUT), CapabilityRouter.neededForImages(true))
    }

    @Test
    fun `neededForTask infers image from text`() {
        val need = CapabilityRouter.neededForTask("请识图这张截图", hasImage = false)
        assertEquals(setOf(ModelCapability.IMAGE_INPUT), need)
    }

    @Test
    fun `decide exposes reason when pool has no vision`() {
        val a = entry("a", "text")
        val d = CapabilityRouter.decide(listOf(a), setOf(ModelCapability.IMAGE_INPUT), "a")
        assertEquals(listOf("a"), d.members.map { it.id })
        assertTrue(d.reason!!.contains("no group member"))
    }
}
