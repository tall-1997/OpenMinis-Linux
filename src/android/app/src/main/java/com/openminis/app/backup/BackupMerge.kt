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
            // [GH#366] A package produced by an older/buggy merge (or hand-
            // edited) can carry the same id twice; writing both duplicates the
            // provider row and every model under it.
            if (!placedInstances.add(ri.id)) continue
            val existing = local.instances.firstOrNull { it.id == ri.id }
            instances.add(ri)
            if (existing == null || !sameInstance(existing, ri)) changed++
        }
        local.instances.filter { it.id !in placedInstances }.forEach { instances.add(it) }

        val entries = mutableListOf<ModelEntry>()
        val placedEntries = mutableSetOf<String>()
        for (re in remote.modelEntries) {
            if (!placedEntries.add(re.id)) continue
            val existing = local.modelEntries.firstOrNull { it.id == re.id }
            entries.add(re)
            if (existing == null || existing != re) changed++
        }
        local.modelEntries.filter { it.id !in placedEntries }.forEach { entries.add(it) }

        val groups = mutableListOf<ModelGroup>()
        val placedGroups = mutableSetOf<String>()

        // [GH#366] Merge [incoming]'s members into the group already at
        // [index] instead of appending a second copy. The by-name branch below
        // can place a local group before a later remote group with the SAME id
        // arrives; the old code then added that remote group unconditionally,
        // so one restore produced two groups sharing an id / a name.
        fun mergeMembersInto(index: Int, incoming: ModelGroup) {
            val base = groups[index]
            val union = (base.memberEntryIds + incoming.memberEntryIds).distinct()
            // Same id = the backup's settings win (that is what restore means);
            // only the member set is a union.
            groups[index] = incoming.copy(memberEntryIds = union.toMutableList())
            if (union != base.memberEntryIds) changed++
        }

        for (rg in remote.modelGroups) {
            // 1) Same id: restore the backup's group content.
            val byId = groups.firstOrNull { it.id == rg.id }
                ?: local.modelGroups.firstOrNull { it.id == rg.id }
            if (byId != null) {
                val idx = groups.indexOfFirst { it.id == byId.id }
                if (idx >= 0) {
                    mergeMembersInto(idx, rg)
                } else {
                    groups.add(rg)
                    if (byId != rg) changed++
                }
                placedGroups.add(byId.id)
                continue
            }
            // 2) 同名但不同 id：并入已有分组（保留本机 id），不新建重复分组。
            // 同时检查本轮已经放入的分组，避免远端两个同名分组各自新建一份。
            val byName = groups.firstOrNull { it.name == rg.name }
                ?: local.modelGroups.firstOrNull {
                    it.id !in placedGroups && it.name == rg.name
                }
            if (byName != null) {
                val idx = groups.indexOfFirst { it.id == byName.id }
                if (idx >= 0) {
                    val base = groups[idx]
                    val union = (base.memberEntryIds + rg.memberEntryIds).distinct()
                    groups[idx] = base.copy(memberEntryIds = union.toMutableList())
                    if (union != base.memberEntryIds) changed++
                } else {
                    val union = (byName.memberEntryIds + rg.memberEntryIds).distinct()
                    groups.add(byName.copy(memberEntryIds = union.toMutableList()))
                    if (union != byName.memberEntryIds) changed++
                }
                placedGroups.add(byName.id)
                continue
            }
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
