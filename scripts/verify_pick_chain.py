#!/usr/bin/env python3
"""pickChain 链序镜像验证（与 GroupRoutingState.pickChain 同构）。
loadBalance 用例传 hashCode 后的 int；冷却断言注意语义：
cooldownUntil[id] <= nowMs 即冷却过期、成员可用。
"""


def pick_chain(strategy, members, weights, cursor_in, remaining, counts,
               session_hash, cooldowns, now, complex_req):
    usable = [m for m in members if cooldowns.get(m, 0) <= now]
    active = usable if usable else sorted(members, key=lambda m: cooldowns.get(m, 0))
    n = len(active)
    if strategy == 'fallback':
        return active, cursor_in, remaining
    if strategy == 'loadBalance':
        off = session_hash % n
        return active[off:] + active[:off], cursor_in, remaining
    if strategy == 'roundRobin':
        off = cursor_in % n
        return active[off:] + active[:off], cursor_in + 1, remaining
    if strategy == 'weighted':
        order = sorted(range(n), key=lambda i: (-max(weights.get(active[i], 1), 1), i))
        return [active[i] for i in order], cursor_in, remaining
    if strategy == 'leastUsed':
        return sorted(active, key=lambda m: counts.get(m, 0)), cursor_in, remaining
    if strategy == 'quotaWindow':
        if remaining > 0:
            return active, cursor_in, remaining - 1
        return active[1:] + active[:1], cursor_in, remaining
    if strategy == 'smart':
        all_cooled = all(cooldowns.get(m, 0) > now for m in active)
        return active if (complex_req or all_cooled) else list(reversed(active)), cursor_in, remaining
    raise ValueError(strategy)


M = ['a', 'b', 'c', 'd']
# (策略, 成员, 权重, 游标入, 配额入, 计数, hash, 冷却, now, 复杂请求, 期望链, 期望游标, 期望配额)
cases = [
    ('fallback',    M, {}, 0, 0, {}, 0, {}, 0, False, ['a', 'b', 'c', 'd'], 0, 0),
    ('loadBalance', M, {}, 0, 0, {}, 0, {}, 0, False, ['a', 'b', 'c', 'd'], 0, 0),
    ('loadBalance', M, {}, 0, 0, {}, 1, {}, 0, False, ['b', 'c', 'd', 'a'], 0, 0),
    ('roundRobin',  M, {}, 1, 0, {}, 0, {}, 0, False, ['b', 'c', 'd', 'a'], 2, 0),
    ('weighted',    M, {'a': 5, 'b': 1, 'c': 5}, 0, 0, {}, 0, {}, 0, False, ['a', 'c', 'b', 'd'], 0, 0),
    ('leastUsed',   M, {}, 0, 0, {'d': 0, 'c': 1, 'b': 2, 'a': 3}, 0, {}, 0, False, ['d', 'c', 'b', 'a'], 0, 0),
    # quotaWindow 有剩余：原序链、配额扣 1、游标不动
    ('quotaWindow', M, {}, 0, 3, {}, 0, {}, 0, False, ['a', 'b', 'c', 'd'], 0, 2),
    # quotaWindow 空窗：backup 打头环形
    ('quotaWindow', M, {}, 0, 0, {}, 0, {}, 0, False, ['b', 'c', 'd', 'a'], 0, 0),
    ('smart',       M, {}, 0, 0, {}, 0, {}, 0, False, ['d', 'c', 'b', 'a'], 0, 0),
    ('smart',       M, {}, 0, 0, {}, 0, {}, 0, True,  ['a', 'b', 'c', 'd'], 0, 0),
    # d 冷却中（99 > 50）：active 只剩 abc
    ('fallback',    M, {}, 0, 0, {}, 0, {'d': 99}, 50, False, ['a', 'b', 'c'], 0, 0),
    # 全员冷却（99 > 50）：降级回原序
    ('fallback',    M, {}, 0, 0, {}, 0, {'a': 99, 'b': 99, 'c': 99, 'd': 99}, 50, False, ['a', 'b', 'c', 'd'], 0, 0),
    # 单成员 + quotaWindow 空窗：环形退化为自身
    ('quotaWindow', ['a'], {}, 0, 0, {}, 0, {}, 0, False, ['a'], 0, 0),
]

ok = 0
for i, (s, mem, w, cur, rem, cnt, sh, cd, now, cx, want, want_cur, want_rem) in enumerate(cases, 1):
    got, got_cur, got_rem = pick_chain(s, mem, w, cur, rem, cnt, sh, cd, now, cx)
    if got == want and got_cur == want_cur and got_rem == want_rem:
        ok += 1
    else:
        print(f'case {i} FAIL: got {got} (cursor {got_cur}, remaining {got_rem}), '
              f'want {want} (cursor {want_cur}, remaining {want_rem})')
print(f'pickChain mirror: {ok}/{len(cases)} passed')
