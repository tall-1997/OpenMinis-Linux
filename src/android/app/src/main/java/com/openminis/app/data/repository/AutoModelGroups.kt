package com.openminis.app.data.repository

import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.RoutingStrategy

/**
 * [T-auto-model-groups] 供应商级「同源组」：每个启用、且有 ≥2 个可用模型的
 * 供应商，自动对应一个 fallback 组（id 前缀 `auto-`），成员就是该供应商的
 * 未隐藏模型。
 *
 * 为什么是派生数据而不是新表字段：
 *  - 组已经能跨供应商，隐式组只是「同源子集」，不需要新的持久化概念；
 *  - 用 id 前缀区分（选 `-` 而非 `:`，避免在导航/深链里被当成 scheme
 *    分隔符），路由与解析代码一行都不用改——`config.modelGroups` 里本来就
 *    能按 id 找到它；
 *  - 每次加载与保存前重建，模型增删、供应商改名、断开供应商都会自动跟随，
 *    不会留下悬空成员。
 *
 * 边界：
 *  - 管理列表（ModelGroupsScreen）过滤掉 `auto-` 组，用户看不到也删不掉；
 *  - 聊天里的组选择器照常列出，名字就是供应商标签，选它即「该供应商内按
 *    顺序失败切换」；
 *  - 用户想加权重/配额/策略时，把它当普通组用即可（当前 UI 暂未提供入口）。
 */
internal object AutoModelGroups {

    const val ID_PREFIX = "auto-"

    fun idFor(instanceId: String): String = ID_PREFIX + instanceId

    fun isAutoId(id: String?): Boolean = id != null && id.startsWith(ID_PREFIX)

    /** 期望的隐式组：启用供应商 + 未隐藏模型，且成员至少两个（单模型建组无意义）。 */
    fun desired(config: ProviderConfig): List<ModelGroup> =
        config.instances
            .filter { it.isEnabled }
            .mapNotNull { instance ->
                val members = config.modelEntries
                    .filter { it.providerInstanceId == instance.id && !it.isHidden }
                    .map { it.id }
                if (members.size < 2) return@mapNotNull null
                ModelGroup(
                    id = idFor(instance.id),
                    name = instance.label.ifBlank { instance.providerType.displayName },
                    memberEntryIds = members.toMutableList(),
                    strategy = RoutingStrategy.fallback,
                )
            }

    /**
     * 原地把 [config] 的隐式组同步成 [desired]；有改动返回 true。
     * 调用方保证 config 是工作副本（或冷启动刚发布、无并发读者）。
     */
    fun syncInPlace(config: ProviderConfig): Boolean {
        val desired = desired(config)
        val current = config.modelGroups.filter { isAutoId(it.id) }
        val unchanged = current.size == desired.size && current.all { existing ->
            desired.any { want ->
                want.id == existing.id &&
                    want.name == existing.name &&
                    want.memberEntryIds == existing.memberEntryIds
            }
        }
        if (unchanged) return false
        config.modelGroups.removeAll { isAutoId(it.id) }
        config.modelGroups.addAll(desired)
        return true
    }

    /** 管理界面用的用户组（排除隐式组）。 */
    fun userGroups(config: ProviderConfig): List<ModelGroup> =
        config.modelGroups.filterNot { isAutoId(it.id) }
}
