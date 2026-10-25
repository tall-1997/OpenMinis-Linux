package com.openminis.app.backup

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复以备份为准：同 id 的服务商、分组、模型参数用备份内容替换；
 * 同名分组并入本地分组；只存在于本机的内容保留；计数等于实际改动数。
 */
class BackupMergeTest {

    private fun instance(id: String, label: String) = ProviderInstance(
        id = id, label = label, providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
    )

    private fun entry(id: String, modelId: String, context: Int?) = ModelEntry(
        providerInstanceId = "p",
        baseModel = LLMModel(modelId, modelId, "OpenAI", contextWindow = context),
        uuid = id,
    )

    private fun group(id: String, name: String, members: List<String>) = ModelGroup(
        id = id, name = name, memberEntryIds = members.toMutableList(),
    )

    @Test
    fun `same id provider is replaced by the backup and local-only providers stay`() {
        val local = ProviderConfig(instances = mutableListOf(instance("a", "本地名"), instance("local-only", "仅本机")))
        val remote = ProviderConfig(instances = mutableListOf(instance("a", "备份名")))

        val (merged, changed) = BackupMerge.merge(local, remote)

        assertEquals(listOf("备份名", "仅本机"), merged.instances.map { it.label })
        assertEquals(1, changed)
    }

    @Test
    fun `same id model entry takes its parameters from the backup`() {
        val local = ProviderConfig(modelEntries = mutableListOf(entry("e1", "glm", 8_192)))
        val remote = ProviderConfig(modelEntries = mutableListOf(entry("e1", "glm", 1_000_000)))

        val (merged, changed) = BackupMerge.merge(local, remote)

        assertEquals(1_000_000, merged.modelEntries.single().baseModel.contextWindow)
        assertEquals(1, changed)
    }

    @Test
    fun `groups with the same name but different ids are merged into the local group`() {
        val local = ProviderConfig(modelGroups = mutableListOf(group("g-local", "对话", listOf("e1"))))
        val remote = ProviderConfig(modelGroups = mutableListOf(group("g-remote", "对话", listOf("e2"))))

        val (merged, changed) = BackupMerge.merge(local, remote)

        assertEquals(1, merged.modelGroups.size)
        assertEquals("g-local", merged.modelGroups.single().id)
        assertEquals(listOf("e1", "e2"), merged.modelGroups.single().memberEntryIds)
        assertEquals(1, changed)
    }

    @Test
    fun `local-only content is never deleted`() {
        val local = ProviderConfig(
            instances = mutableListOf(instance("keep", "留着")),
            modelEntries = mutableListOf(entry("keep-e", "local-model", null)),
            modelGroups = mutableListOf(group("keep-g", "本机分组", listOf("keep-e"))),
        )
        val remote = ProviderConfig()

        val (merged, changed) = BackupMerge.merge(local, remote)

        assertEquals(1, merged.instances.size)
        assertEquals(1, merged.modelEntries.size)
        assertEquals(1, merged.modelGroups.size)
        assertEquals(0, changed)
    }

    @Test
    fun `identical content counts as zero changes`() {
        val local = ProviderConfig(instances = mutableListOf(instance("a", "同")))
        val remote = ProviderConfig(instances = mutableListOf(instance("a", "同")))

        val (_, changed) = BackupMerge.merge(local, remote)

        assertEquals(0, changed)
        assertTrue(changed == 0)
    }
}
