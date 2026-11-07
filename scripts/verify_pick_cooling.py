#!/usr/bin/env python3
"""pick 冷却角色语义镜像（与 GroupRoutingState.pick/cooledActive 同构）。

覆盖第 6 项「角色语义修复」的验收点：
1. 有可用成员时冷却过滤保持原序（角色槽位不变）
2. 全员冷却时降级为恢复时间升序（quotaWindow primary / smart strong 指向最早恢复者）
3. smart light 全员冷却时同样取最早恢复者（可用性优先于预算槽位）
"""


def cooled_active(members, cooldowns, now):
    usable = [m for m in members if cooldowns.get(m, 0) <= now]
    if usable:
        return usable
    return sorted(members, key=lambda m: cooldowns.get(m, 0))


def pick(strategy, members, cooldowns, now, complex_req=False, quota_remaining=None):
    active = cooled_active(members, cooldowns, now)
    if strategy == 'quotaWindow':
        if quota_remaining > 0:
            return active[0], None, quota_remaining - 1
        hit = active[1] if len(active) > 1 else active[0]
        return hit, 'fallback', quota_remaining
    if strategy == 'smart':
        all_cooled = all(cooldowns.get(m, 0) > now for m in active)
        if complex_req or all_cooled:
            return active[0], 'primary', None
        return active[-1], 'budget', None
    if strategy == 'fallback':
        return active[0], None, None
    raise ValueError(strategy)


M = ['strong', 'budget']
cases = [
    # (策略, 成员, 冷却, now, 复杂?, 配额, 期望成员, 期望配额)
    # 1. 无人冷却：quotaWindow 有配额 → strong
    ('quotaWindow', M, {}, 100, False, 3, 'strong', 2),
    # 2. strong 冷却中（150>100）、budget 可用：有配额 → budget（冷却优先于角色槽位）
    ('quotaWindow', M, {'strong': 150}, 100, False, 3, 'budget', 2),
    # 3. 同上但配额空窗：active=[budget]，getOrNull(1)=None → 回落 budget
    ('quotaWindow', M, {'strong': 150}, 100, False, 0, 'budget', 0),
    # 4. 无人冷却 + 配额空窗 → budget 槽位
    ('quotaWindow', M, {}, 100, False, 0, 'budget', 0),
    # 5. 全员冷却 + 配额有剩余：降级恢复升序 → 最早恢复者 strong(120) < budget(200)
    ('quotaWindow', M, {'strong': 120, 'budget': 200}, 100, False, 3, 'strong', 2),
    # 6. 全员冷却 + 恢复时间倒挂（budget 先恢复）：降级后 primary 槽 = budget
    ('quotaWindow', M, {'strong': 300, 'budget': 150}, 100, False, 3, 'budget', 2),
    # 7. smart light 无人冷却 → budget
    ('smart', M, {}, 100, False, None, 'budget', None),
    # 8. smart complex 无人冷却 → strong
    ('smart', M, {}, 100, True, None, 'strong', None),
    # 9. smart light + strong 冷却中 → active=[budget] → budget
    ('smart', M, {'strong': 150}, 100, False, None, 'budget', None),
    # 10. smart light + 全员冷却（strong 先恢复）→ 最早恢复者 strong（旧语义会取 budget）
    ('smart', M, {'strong': 120, 'budget': 200}, 100, False, None, 'strong', None),
    # 11. smart complex + 全员冷却 → 最早恢复者（strong）
    ('smart', M, {'strong': 120, 'budget': 200}, 100, True, None, 'strong', None),
    # 12. fallback 全员冷却 → 最早恢复者（budget 150 < strong 300）
    ('fallback', M, {'strong': 300, 'budget': 150}, 100, False, None, 'budget', None),
]

ok = 0
for i, (s, mem, cd, now, cx, qr, want, want_q) in enumerate(cases, 1):
    got, _, got_q = pick(s, mem, cd, now, cx, qr)
    if got == want and got_q == want_q:
        ok += 1
    else:
        print(f'case {i} FAIL: got {got} (quota {got_q}), want {want} (quota {want_q})')
print(f'pick cooling-role mirror: {ok}/{len(cases)} passed')
