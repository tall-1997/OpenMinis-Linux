package com.openminis.app.data.model

/**
 * 删除 provider 实例的持久规则 —— 与 [ModelDeletion]（模型级墓碑）并列，不混用。
 *
 * 为什么需要它：内置免费档实例在每次启动时按需播种。设备上的「实例不见了」有两种
 * 完全不同的成因 —— 用户主动删的，或者某次版本升级替用户删的（例如免费档被整条
 * 下线的那次改动）。两者留下的字节一模一样，只看 config 无法区分，于是「删了又
 * 回来」和「再也不回来」只能二选一，必然有一半用户被做错。
 *
 * 有了实例级墓碑，主动删除会留痕，升级替用户做的删除**不**留痕 —— 后者是我们的
 * 决定，不是用户意愿，所以下次启动可以无感恢复，旧会话与槽位引用一并复活。
 *
 * 恢复渠道（[FreeLaneBootstrap.restore]）负责清掉这里的记录。
 */
object InstanceRemoval {

    /** 记入墓碑。已存在的不重复记，保持原有顺序。返回是否改动了 config。 */
    fun record(config: ProviderConfig, instanceId: String): Boolean =
        if (config.removedInstanceIds.contains(instanceId)) {
            false
        } else {
            config.removedInstanceIds.add(instanceId)
            true
        }

    /** 这个实例是不是用户亲手删掉的。 */
    fun wasRemovedByUser(config: ProviderConfig, instanceId: String): Boolean =
        config.removedInstanceIds.contains(instanceId)

    /** 撤掉墓碑（显式恢复渠道调用）。返回是否改动了 config。 */
    fun clear(config: ProviderConfig, instanceId: String): Boolean =
        config.removedInstanceIds.remove(instanceId)
}
