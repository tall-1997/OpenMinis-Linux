#!/usr/bin/env python3
"""UI 行为类棘轮（2026-11-23 新增，G2/G4）。

背景：token 系门禁只看字面量与令牌表，四类真实缺陷长期零覆盖：
  1. **Icon 直写**（G2）—— `Icon(Icons.X, ...)` 绕过 `M3Icons.m3Icon()`
     归一化：动态取色开关切换图标风格时，直写点不跟随，同屏出现
     Filled/Outlined 混排。2026-11-23 审计实测 240 处 / 83 文件。收口后
     用本门禁钉住，基线只降不升。
  2. **remember 读 pref 无 key**（G2）—— `remember { xxxEnabled(context) }`
     只在首个组合读一次，设置开关后不生效（Turn changes 卡片的真 bug，
     2026-11-23 实证）。这类写法一律报错，新代码必须走 observe 型
     remember 帮助函数。
  3. **SystemBarStyle.auto**（G2）—— auto 按系统 uiMode 选状态栏图标
     亮暗，不跟应用内主题；应用内浅色+系统深色 → 浅底白图标。
     MainActivity/QuickActivity 不得使用。
  4. **MinisColors 死槽**（G4）—— 57 槽里 22 槽零消费者、12 槽仅 1 个。
     令牌层与真实渲染两张皮的量化指标，基线 22 只降不升。

用法：
    python3 scripts/ci/check_ui_contracts.py [--update-baseline]
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "src/android/app/src/main/java/com/openminis/app"
BASELINE = Path(__file__).resolve().parent / "ui-contracts-baseline.json"

RE_ICON_CALL = re.compile(r'\bIcon\s*\(')
RE_M3 = re.compile(r'm3Icon\s*\(')
RE_ICONS_REF = re.compile(r'Icons\.(?:AutoMirrored\.)?(?:Filled|Outlined|Rounded|TwoTone|Sharp|Default)\.')
# [T-pref-reactive-read] Keyless `remember { xxxEnabled(context) }` only: a
# keyed `remember(k) { ... }` re-reads when k changes and is fine.
RE_REMEMBER_PREF = re.compile(r'remember\s*\{\s*(?:\w+\.)?(\w+Enabled)\(context\)')
EXEMPT_FILES = {'M3Icons.kt'}
STATUSBAR_FILES = ['MainActivity.kt', 'quick/QuickActivity.kt']

# MinisColors 的槽位（解析 MinisTokens.kt 生成文件；令牌生成物变化时用
# --update-baseline 重锚）。死槽 = 全仓（生成物/TokenBridge 除外）零引用。
TOKENS = APP / "ui/tokens/MinisTokens.kt"


def mask_comments(src: str) -> str:
    """Blank out // and /* */ comments, preserving line count.

    Code scans have to ignore comments: helpers here document the anti-pattern
    they replace, and a doc quote must not count as a violation.
    """
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('//', i):
            j = src.find('\n', i)
            j = n if j < 0 else j
            i = j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append('\n' * src.count('\n', i, j))
            i = j
            continue
        out.append(src[i])
        i += 1
    return ''.join(out)


def count_icon_bypass() -> int:
    """`Icon(` 的第一个参数直接传 Icons.* 且未经 m3Icon 的调用数。"""
    total = 0
    for p in APP.rglob('*.kt'):
        if p.name in EXEMPT_FILES:
            continue
        s = p.read_text(encoding='utf-8', errors='replace')
        for m in RE_ICON_CALL.finditer(s):
            i, depth, start = m.end(), 1, m.end()
            while i < len(s) and depth > 0:
                if s[i] == '(':
                    depth += 1
                elif s[i] == ')':
                    depth -= 1
                i += 1
            expr = s[start:i]
            # [T-icon-bypass-mixed-branch] `m3Icon(` anywhere in the argument is
            # NOT enough: `if (x) m3Icon(A) else Icons.B` still renders a bare
            # B in one branch. Count Icons references that are not immediately
            # inside an m3Icon(...) call.
            if has_wrapped_icons_ref(expr):
                total += 1
    return total


def has_wrapped_icons_ref(expr: str) -> bool:
    """True when some `Icons.*` in [expr] is NOT wrapped by m3Icon(...)."""
    covered = set()
    for m in RE_M3.finditer(expr):
        depth, j = 1, m.end()
        while j < len(expr) and depth > 0:
            if expr[j] == '(':
                depth += 1
            elif expr[j] == ')':
                depth -= 1
            j += 1
        covered.add((m.start(), j))
    for ref in RE_ICONS_REF.finditer(expr):
        if not any(a <= ref.start() < b for a, b in covered):
            return True
    return False


def count_remember_pref() -> list:
    hits = []
    for p in APP.rglob('*.kt'):
        if p.name in EXEMPT_FILES:
            continue
        raw = p.read_text(encoding='utf-8', errors='replace')
        # Scan code only: the helper's own KDoc quotes the anti-pattern, and a
        # comment must never register as a violation.
        s = mask_comments(raw)
        for m in RE_REMEMBER_PREF.finditer(s):
            hits.append(f'{p.relative_to(APP)}:{raw[:m.start()].count(chr(10)) + 1} {m.group(1)}')
    return hits


def check_statusbar() -> list:
    hits = []
    for f in STATUSBAR_FILES:
        p = APP / f
        if not p.exists():
            continue
        s = p.read_text(encoding='utf-8', errors='replace')
        for m in re.finditer(r'SystemBarStyle\.auto\s*\(', s):
            hits.append(f'{f}:{s[:m.start()].count(chr(10)) + 1}')
    return hits


def count_dead_minis_colors_slots() -> int:
    if not TOKENS.exists():
        return 0
    src = TOKENS.read_text(encoding='utf-8', errors='replace')
    i = src.find('class MinisColors')
    j = src.find('class MinisDimens')
    body = src[i:j if j > 0 else len(src)]
    slots = re.findall(r'val (\w+): Long', body)
    used = []
    for p in APP.rglob('*.kt'):
        if p.name in ('MinisTokens.kt', 'TokenBridge.kt'):
            continue
        used.append(p.read_text(encoding='utf-8', errors='replace'))
    used = '\n'.join(used)
    dead = [s for s in slots if not re.search(r'\b' + re.escape(s) + r'\b', used)]
    return len(dead)



def selftest() -> int:
    """Reverse cases: every rule must FAIL on a poisoned sample and PASS on a
    clean one. A ratchet that cannot fail is decoration."""
    fails = []

    def cases(label, regex, positives, negatives):
        for src in positives:
            if not regex.search(src):
                fails.append(f'{label}: 应命中但没命中  {src!r}')
        for src in negatives:
            if regex.search(src):
                fails.append(f'{label}: 不应命中却命中  {src!r}')

    # 1) keyless remember read of a prefs gate
    cases(
        'remember-pref',
        RE_REMEMBER_PREF,
        ['val x = remember { fooEnabled(context) }',
         'val y = remember{barEnabled(context)}'],
        ['val x = remember(k) { fooEnabled(context) }',   # keyed: re-reads
         'val x = remember { prefs.bool(key, false) }'],  # not the accessor shape
    )

    # 2) comment masking: the anti-pattern quoted in KDoc must not count
    doc = '// remember { fooEnabled(context) }\nval ok = 1\n'
    if RE_REMEMBER_PREF.search(mask_comments(doc)):
        fails.append('mask-comments: 注释里的示例被当成违规')
    if not RE_REMEMBER_PREF.search('val x = remember { fooEnabled(context) }'):
        fails.append('mask-comments: 掩码把真实代码也吃掉了')

    # 3) status bar auto detection
    for src in ['SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)']:
        if not re.search(r'SystemBarStyle\.auto\s*\(', src):
            fails.append(f'statusbar: 应命中 {src!r}')
    for src in ['SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)',
                'SystemBarStyle.dark(Color.TRANSPARENT)']:
        if re.search(r'SystemBarStyle\.auto\s*\(', src):
            fails.append(f'statusbar: 误命中 {src!r}')

    # 4) icon-bypass expression classification (mixed branches must count)
    for expr, expected in [
        ('Icons.Default.Close, contentDescription = null', True),
        ('m3Icon(Icons.Default.Close), contentDescription = null', False),
        ('if (x) Icons.Default.A else Icons.Default.B,', True),
        ('if (x) m3Icon(Icons.Default.A) else Icons.Default.B,', True),
        ('if (x) m3Icon(Icons.Default.A) else m3Icon(Icons.Default.B),', False),
    ]:
        got = has_wrapped_icons_ref(expr)
        if got != expected:
            fails.append(f'icon-bypass: {expr!r} 判定 {got}，应为 {expected}')

    if fails:
        print(f'ui contracts selftest: FAILED ({len(fails)} 项)')
        for f in fails:
            print('  -', f)
        return 2
    print('ui contracts selftest: PASS（remember-pref / 注释掩码 / 状态栏 / 图标归类 4 项）')
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--update-baseline', action='store_true')
    ap.add_argument('--selftest', action='store_true',
                    help='反向用例：验证本门禁的四条规则真的会失败')
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    current = {
        'icon_bypass': count_icon_bypass(),
        'remember_pref': len(count_remember_pref()),
        'statusbar_auto': len(check_statusbar()),
        'dead_minis_colors_slots': count_dead_minis_colors_slots(),
    }

    if args.update_baseline or not BASELINE.exists():
        BASELINE.write_text(json.dumps(current, indent=2) + '\n', encoding='utf-8')
        print(f'ui contracts: baseline updated -> {current}')
        return 0

    base = json.loads(BASELINE.read_text(encoding='utf-8'))
    fails = []

    # 棘轮：只降不升
    if current['icon_bypass'] > base.get('icon_bypass', 0):
        fails.append(f"Icon 直写绕过 m3Icon：{current['icon_bypass']} > 基线 "
                     f"{base['icon_bypass']}。新代码必须 Icon(m3Icon(Icons.X), ...)。"
                     f"基线下调用 --update-baseline。")
    if current['remember_pref'] > 0:
        hits = count_remember_pref()
        fails.append(f"remember{{}} 内直接读 pref 共 {current['remember_pref']} 处"
                     f"（无 key，设置切换不生效）：{'; '.join(hits[:5])}。"
                     f"改用 observe 型 remember 帮助函数（见 "
                     f"rememberShowTurnChangesCardEnabled）。")
    if current['statusbar_auto'] > 0:
        hits = check_statusbar()
        fails.append(f"MainActivity/QuickActivity 使用 SystemBarStyle.auto"
                     f"（跟系统不跟应用内主题）：{'; '.join(hits)}")
    if current['dead_minis_colors_slots'] > base.get('dead_minis_colors_slots', 0):
        fails.append(f"MinisColors 死槽 {current['dead_minis_colors_slots']} > 基线 "
                     f"{base.get('dead_minis_colors_slots', 0)}（令牌层与真实渲染"
                     f"继续脱钩）。给槽位接上消费者或删槽后 --update-baseline。")

    print(f"ui contracts: icon_bypass={current['icon_bypass']} "
          f"remember_pref={current['remember_pref']} "
          f"statusbar_auto={current['statusbar_auto']} "
          f"dead_slots={current['dead_minis_colors_slots']} "
          f"(baseline {base})")
    if fails:
        print(f"\n断言失败 {len(fails)} 项：")
        for f in fails:
            print(f"  ✗ {f}")
        return 1
    print("PASS: 图标归一化/remember-pref/状态栏/死槽 棘轮")
    return 0


if __name__ == '__main__':
    sys.exit(main())