package com.openminis.app.data.model

/**
 * 删除模型的持久规则。
 *
 * 刷新模型列表时，非自定义条目按 baseModel.id 重建（见 ProviderRepository.replaceEntries），
 * 所以删掉一个 API 下发的模型后，下一次刷新会把它原样复活。墓碑记录「用户明确删除的模型 id」，
 * 刷新结果在入库前先剔除这些 id。自定义条目不走重建路径，按 uuid 删除即可，不需要墓碑。
 */
object ModelDeletion {

    /** 非自定义条目返回其 baseModel.id 作为墓碑；自定义条目返回 null。 */
    fun tombstoneModelId(isCustom: Boolean, baseModelId: String): String? =
        if (isCustom) null else baseModelId

    /** 从刷新得到的模型 id 列表里去掉被墓碑标记的。保持原顺序。 */
    fun applyTombstones(fetchedModelIds: List<String>, tombstonedIds: Collection<String>): List<String> =
        if (tombstonedIds.isEmpty()) fetchedModelIds
        else fetchedModelIds.filterNot { it in tombstonedIds }

    /** 记入墓碑。已存在的不重复记，保持原有顺序。 */
    fun withTombstone(tombstonedIds: List<String>, modelId: String): List<String> =
        if (modelId in tombstonedIds) tombstonedIds else tombstonedIds + modelId

    /** 用户手动重新添加同一模型时，撤掉对应墓碑。 */
    fun withoutTombstone(tombstonedIds: Collection<String>, modelId: String): List<String> =
        tombstonedIds.filterNot { it == modelId }

    /** 刷新时剔除被删除的模型。 */
    fun filterDeleted(
        config: ProviderConfig,
        instanceId: String,
        models: List<LLMModel>,
    ): List<LLMModel> {
        val tombstoned = config.deletedModelIds[instanceId].orEmpty()
        return if (tombstoned.isEmpty()) models
        else models.filter { tombstoneModelId(false, it.id) !in tombstoned }
    }

    /** 删除一个条目时记墓碑（非自定义条目）。返回是否记了。 */
    fun recordDeletion(config: ProviderConfig, entry: ModelEntry): Boolean {
        val modelId = tombstoneModelId(entry.isCustom, entry.baseModel.id) ?: return false
        val prior = config.deletedModelIds[entry.providerInstanceId].orEmpty()
        config.deletedModelIds[entry.providerInstanceId] = withTombstone(prior, modelId)
        return true
    }

    /** 重新添加一个条目时撤掉它的墓碑。 */
    fun clearDeletion(config: ProviderConfig, entry: ModelEntry) {
        config.deletedModelIds[entry.providerInstanceId]?.let { prior ->
            val left = withoutTombstone(prior, entry.baseModel.id)
            if (left.isEmpty()) config.deletedModelIds.remove(entry.providerInstanceId)
            else config.deletedModelIds[entry.providerInstanceId] = left
        }
    }
}
