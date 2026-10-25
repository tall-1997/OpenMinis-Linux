package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 长按删除必须覆盖 API 下发的模型，且刷新后不复活。
 * 刷新按 baseModel.id 重建非自定义条目，所以删除要留下墓碑。
 */
class ModelDeletionTest {

    @Test
    fun `deleting a fetched model records its model id as a tombstone`() {
        val id = ModelDeletion.tombstoneModelId(isCustom = false, baseModelId = "glm-4.5")
        assertEquals("glm-4.5", id)
    }

    @Test
    fun `deleting a custom model records no tombstone`() {
        // 自定义条目按 uuid 删除，刷新路径本来就保留自定义条目，不需要墓碑。
        assertNull(ModelDeletion.tombstoneModelId(isCustom = true, baseModelId = "my-model"))
    }

    @Test
    fun `refresh drops models the user deleted`() {
        val fetched = listOf("glm-4.5", "glm-4.6", "qwen3")
        val kept = ModelDeletion.applyTombstones(fetched, setOf("glm-4.5"))
        assertEquals(listOf("glm-4.6", "qwen3"), kept)
    }

    @Test
    fun `refresh keeps everything when nothing was deleted`() {
        val fetched = listOf("a", "b")
        assertEquals(fetched, ModelDeletion.applyTombstones(fetched, emptySet()))
    }

    @Test
    fun `re-adding a model clears its tombstone`() {
        val left = ModelDeletion.withoutTombstone(listOf("glm-4.5", "qwen3"), "glm-4.5")
        assertEquals(listOf("qwen3"), left)
    }

    @Test
    fun `recording the same tombstone twice keeps one entry`() {
        val once = ModelDeletion.withTombstone(listOf("qwen3"), "glm-4.5")
        val twice = ModelDeletion.withTombstone(once, "glm-4.5")
        assertEquals(listOf("qwen3", "glm-4.5"), twice)
    }
}
