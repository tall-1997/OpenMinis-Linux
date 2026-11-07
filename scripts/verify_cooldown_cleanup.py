#!/usr/bin/env python3
"""冷却显式 entryId + 状态清理语义镜像（与 GroupRoutingState 第 7 项同构）。

验收点：
1. markRoutingCooldownFor(entryId, ...) 按显式 entryId 冷却，servingInstance
   并发被覆盖也不影响落点（旧的 servingEntry 反查竞态消除）
2. clearGroupState(groupId) 删 round-robin 游标 / 配额 / servingInstance 条目，
   不影响其他组
3. pruneEntryStates(active) 只保留仍存在于某组的 entryId 冷却/计数条目
4. 删组重建同 id：状态从零开始，不继承残留
"""

DEFAULT_COOLDOWN_MS = 120_000


class State:
    def __init__(self):
        self.cursors = {}
        self.quota = {}
        self.serving_instance = {}
        self.cooldown_until = {}
        self.serving_counts = {}

    def note_serving_instance(self, group_id, instance_id):
        self.serving_instance[group_id] = instance_id

    def mark_cooldown(self, entry_id, until_ms):
        self.cooldown_until[entry_id] = max(self.cooldown_until.get(entry_id, 0), until_ms)

    def mark_routing_cooldown_for(self, entry_id, group_id, error, now):
        retry_s = error.get('retry_after_seconds', 0)
        until = now + (retry_s * 1000 if retry_s > 0 else DEFAULT_COOLDOWN_MS)
        self.mark_cooldown(entry_id, until)

    def clear_group_state(self, group_id):
        self.cursors.pop(group_id, None)
        self.quota.pop(group_id, None)
        self.serving_instance.pop(group_id, None)

    def prune_entry_states(self, active):
        self.cooldown_until = {k: v for k, v in self.cooldown_until.items() if k in active}
        self.serving_counts = {k: v for k, v in self.serving_counts.items() if k in active}


ok = 0
fails = []


def check(name, cond):
    global ok
    if cond:
        ok += 1
    else:
        fails.append(name)


# --- 1. 显式 entryId 冷却：并发覆盖 servingInstance 不影响落点 ---
s = State()
s.note_serving_instance('g1', 'inst-old')
# 错误回来前，另一 turn 已把 g1 的 instance 覆盖成别的
s.note_serving_instance('g1', 'inst-other')
s.mark_routing_cooldown_for('entry-A', 'g1', {'retry_after_seconds': 30}, now=100_000)
check('explicit entryId cooldown lands on entry-A',
      s.cooldown_until == {'entry-A': 130_000})

# --- 2. retryAfter 缺失走默认窗口 ---
s2 = State()
s2.mark_routing_cooldown_for('entry-B', 'g1', {}, now=100_000)
check('default cooldown window',
      s2.cooldown_until == {'entry-B': 220_000})

# --- 3. 冷却取 max（更长窗口优先） ---
s3 = State()
s3.mark_routing_cooldown_for('entry-C', 'g1', {'retry_after_seconds': 10}, now=100_000)
s3.mark_routing_cooldown_for('entry-C', 'g1', {'retry_after_seconds': 60}, now=110_000)
check('cooldown takes max', s3.cooldown_until == {'entry-C': 170_000})

# --- 4. clearGroupState 只清目标组 ---
s4 = State()
s4.cursors['g1'] = 2
s4.cursors['g2'] = 1
s4.quota['g1'] = 3
s4.quota['g2'] = 5
s4.serving_instance['g1'] = 'i1'
s4.serving_instance['g2'] = 'i2'
s4.clear_group_state('g1')
check('clearGroupState drops g1 cursor/quota/instance',
      s4.cursors == {'g2': 1} and s4.quota == {'g2': 5} and s4.serving_instance == {'g2': 'i2'})

# --- 5. pruneEntryStates 只留活动 entry ---
s5 = State()
s5.cooldown_until = {'a': 999, 'b': 888, 'stale': 777}
s5.serving_counts = {'a': 2, 'stale': 5, 'b': 1}
s5.prune_entry_states({'a', 'b'})
check('prune keeps only active entries',
      s5.cooldown_until == {'a': 999, 'b': 888} and s5.serving_counts == {'a': 2, 'b': 1})

# --- 6. prune 空集清空全部 entry 状态 ---
s6 = State()
s6.cooldown_until = {'stale': 777}
s6.serving_counts = {'stale': 3}
s6.prune_entry_states(set())
check('prune with empty set clears all', not s6.cooldown_until and not s6.serving_counts)

# --- 7. 删组重建同 id：状态零继承 ---
s7 = State()
s7.cursors['g9'] = 4
s7.quota['g9'] = 0
s7.serving_instance['g9'] = 'dead-inst'
s7.clear_group_state('g9')
# 重建组：cursor/quota 从零开始（第一次 pick 不命中旧游标）
inherited = ('g9' in s7.cursors) or ('g9' in s7.quota) or ('g9' in s7.serving_instance)
check('rebuilt group inherits no state', not inherited)

print(f'cooldown/cleanup mirror: {ok}/{ok + len(fails)} passed')
if fails:
    for f in fails:
        print(f'FAIL: {f}')
    raise SystemExit(1)
