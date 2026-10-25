package com.openminis.app.backup

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance

/**
 * 备份恢复的合并规则。以备份内容为准，但不删除只存在于本机的内容。
 *
 * - 服务商、模型条目：同 id 用备份的整份内容替换（设置、参数因此恢复）。
 * - 分组：同 id 用备份内容替换；备份里与某个本机分组同名但 id 不同的，并入该
 *   本机分组（成员取并集，保留本机 id），避免同名分组越恢复越多。
 * - 本机独有的服务商、条目、分组原样保留，追加在备份内容之后。
 *
 * 返回实际改动的数量：内容确实变化的替换计 1，新增计 1，完全相同计 0。
 * 恢复页面显示的数量必须来自这里，而不是「之后减之前」——那种算法把
 * 被替换的内容算成 0。
 */
object BackupMerge {

    data class Result(val config: ProviderConfig, val changedCount: Int)

    fun merge(local: ProviderConfig, remote: ProviderConfig): Result {
        var changed = 0

        val instances = mutableListOf<ProviderInstance>()
        val placedInstances = mutableSetOf<String>()
        for (ri in remote.instances) {
            val existing = local.instances.firstOrNull { it.id == ri.id }
            instances.add(ri)
            placedInstances.add(ri.id)
            if (existing == null || !sameInstance(existing, ri)) changed++
        }
        local.instances.filter { it.id !in placedInstances }.forEach { instances.add(it) }

        val entries = mutableListOf<ModelEntry>()
        val placedEntries = mutableSetOf<String>()
        for (re in remote.modelEntries) {
            val existing = local.modelEntries.firstOrNull { it.id == re.id }
            entries.add(re)
            placedEntries.add(re.id)
            if (existing == null || existing != re) changed++
        }
        local.modelEntries.filter { it.id !in placedEntries }.forEach { entries.add(it) }

        val groups = mutableListOf<ModelGroup>()
        val placedGroups = mutableSetOf<String>()
        val consumedRemoteGroups = mutableSetOf<String>()
        for (rg in remote.modelGroups) {
            val byId = local.modelGroups.firstOrNull { it.id == rg.id }
            if (byId != null) {
                groups.add(rg)
                placedGroups.add(rg.id)
                consumedRemoteGroups.add(rg.id)
                if (byId != rg) changed++
                continue
            }
            // 同名但不同 id：并入本机分组，不新建一个重复分组。
            val byName = local.modelGroups.firstOrNull {
                it.id !in placedGroups && it.name == rg.name
            }
            if (byName != null) {
                val union = (byName.memberEntryIds + rg.memberEntryIds).distinct()
                val merged = byName.copy(memberEntryIds = union.toMutableList())
                groups.add(merged)
                placedGroups.add(byName.id)
                consumedRemoteGroups.add(rg.id)
                if (union != byName.memberEntryIds) changed++
            }
        }
        for (rg in remote.modelGroups) {
            if (rg.id in consumedRemoteGroups) continue
            groups.add(rg)
            changed++
        }
        local.modelGroups.filter { it.id !in placedGroups }.forEach { groups.add(it) }

        val merged = local.copy(
            instances = instances,
            modelEntries = entries,
            modelGroups = groups,
        )
        return Result(merged, changed)
    }

    /** 服务商比较忽略创建时间：时间不同不代表设置被改过。 */
    private fun sameInstance(a: ProviderInstance, b: ProviderInstance): Boolean =
        a.copy(createdAt = 0L) == b.copy(createdAt = 0L)
}
