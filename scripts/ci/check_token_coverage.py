#!/usr/bin/env python3
"""CI 守卫：设计令牌覆盖率棘轮 —— 未命名的字面量只许减少，不许增加。

## 为什么需要这条守卫

gen_tokens.py 把字面量聚合成令牌表，但它只统计、不逼迫采用。实测 347 处
sp 字面量里只有 123 处走了令牌，dp 更差。这个比例没人守就会一路下滑：新写
一屏 UI，最省事的做法永远是直接写 16.sp，而不是去表里找语义名 —— 令牌表
于是退化成只读装饰，和真实渲染脱钩。

所以做棘轮：按文件记下当前未命名数量作为预算，谁涨了就 fail。降下来之后
必须 --update-baseline 把预算调低；预算只能降，想调高要在 PR 里单独解释。

## 三类预算

  color  未命名的 Color(0x...)，应改用 MinisTheme.colors.* / RawColors.*
  sp     未走令牌的字号，应改用 MinisTypography.type.*
  dp     未走令牌的尺寸，应改用 Minis.dimens.*

按文件记账而不是全仓总数：总数会被一个文件的删除掩盖另一个文件的新增
（A 删 20、B 加 20，总数不变但 B 更烂了）。

## 源码内豁免：token-literal-ok

有些字面量不属于设计令牌的管辖范围 —— 最典型的是 Material 3 Typography
本身，它的每一档字号和行高都是外部规范的拷贝，值由规范决定而不是由本项目的
排版意图决定。硬接上令牌会让规范下一次调整时跟着漂移。

这类地方在**同一行**写 `token-literal-ok: <理由>` 即可豁免。理由必填：
空理由视为无效豁免并报错，因为"先豁免再说"是这类机制最常见的腐化方式。

## 注释不算使用点

16.sp 算，MinisType().body.sp 不算（不是字面量），// 原来是 16.sp 不算。
口径与 gen_tokens.py 的 strip_comments 一致，否则两边数字对不上，预算就
成了随机数。

用法：
    python3 scripts/ci/check_token_coverage.py
    python3 scripts/ci/check_token_coverage.py --update-baseline
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "src/android/app/src/main/java/com/openminis/app/ui"
BASELINE = Path(__file__).resolve().parent / "token-coverage-baseline.json"

RE_HEX = re.compile(r'Color\(0x([0-9A-Fa-f]{8})\)')
RE_DP = re.compile(r'(?<![\w.])(\d+(?:\.\d+)?)\.dp\b')
RE_SP = re.compile(r'(?<![\w.])(\d+(?:\.\d+)?)\.sp\b')


def strip_comments(src):
    """返回「只有代码」的文本：注释整段删除，字符串内容置空（保留引号占位）。

    为什么字符串要置空：`Text("16.sp")` 里的 16.sp 是给人看的文案，
    不是排版参数；把它计入预算会让文案改动触发排版告警。
    保留引号是因为 `"" .sp` 这种拼接仍需维持语法结构可读。
    """
    out, i, n, in_str = [], 0, len(src), False
    while i < n:
        c = src[i]
        if in_str:
            if c == '\\' and i + 1 < n:
                i += 2
                continue
            if c == '"':
                in_str = False
                out.append('"')
            elif c == '\n':
                out.append('\n')   # raw string 可跨行，行号必须对齐
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            # 关键：块注释里的换行必须补回来。否则去注释文本的行号与原文错位，
            # 所有"按行"的定位（豁免标记、诊断输出）都会指到错误的位置。
            out.append('\n' * src.count('\n', i, j))
            i = j
            continue
        if src.startswith('//', i) and (i == 0 or src[i - 1].isspace()):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        out.append(c)
        i += 1
    return ''.join(out)


EXEMPT = re.compile(r'token-literal-ok\s*:\s*(\S.*)?$')



def literal_values(line):
    """该行出现的全部字面量值（去重、保序），如 ['57','64']。

    豁免行要做「数值指纹」：把这一行实际写了哪些值记进 baseline。值一变指纹
    就对不上，必须人工确认并改 baseline —— 棘轮的本义是「只许往下」，而按行
    放行的豁口恰好把它变成「随便改都不报警」。
    """
    seen = []
    for m in list(RE_HEX.finditer(line)) + list(RE_SP.finditer(line)) + list(RE_DP.finditer(line)):
        if m.group(1) not in seen:
            seen.append(m.group(1))
    return seen


def count_file(path):
    """返回 (计数, 豁免行号, 空理由行号, {豁免行号: 数值指纹})。

    豁免必须在原始行上判断（strip_comments 会把注释删掉，而豁免标记就写在
    注释里），所以这里同时读原文和去注释文本。
    """
    raw = path.read_text(encoding='utf-8', errors='replace')
    clean = strip_comments(raw)
    counts = {'color': len(RE_HEX.findall(clean)),
              'sp': len(RE_SP.findall(clean)),
              'dp': len(RE_DP.findall(clean))}
    exempt, blank, prints = [], [], {}
    clean_lines = clean.split('\n')
    for i, line in enumerate(raw.split('\n'), 1):
        m = EXEMPT.search(line)
        if not m:
            continue
        reason = (m.group(1) or '').strip()
        if len(reason) < 6:
            blank.append(i)
            continue
        exempt.append(i)
        if i - 1 < len(clean_lines):
            c_cln = count_line(clean_lines[i - 1])
            for k in counts:
                counts[k] -= c_cln[k]
            vals = literal_values(clean_lines[i - 1])
            if vals:
                prints[str(i)] = ' '.join(vals)
    return counts, exempt, blank, prints


def count_line(line):
    return {'color': len(RE_HEX.findall(line)),
            'sp': len(RE_SP.findall(line)),
            'dp': len(RE_DP.findall(line))}


def survey():
    return survey_root(UI)


def survey_root(root):
    """返回 (per_file_counts, 问题列表, {文件: {行号: 数值指纹}})。root 可注入，便于自测。"""
    out, bad, exemps = {}, [], {}
    for p in sorted(Path(root).rglob('*.kt')):
        try:
            rel = p.relative_to(ROOT).as_posix()
        except ValueError:
            rel = str(p)
        c, _exempt, blank, prints = count_file(p)
        if blank:
            bad.extend('%s:%d 豁免理由为空或过短' % (rel, ln) for ln in blank)
        if prints:
            exemps[rel] = prints
        if any(c.values()):
            out[rel] = c
    return out, bad, exemps


def totals(per_file):
    return {k: sum(v[k] for v in per_file.values()) for k in ('color', 'sp', 'dp')}


def selftest():
    """反向用例：预算机制必须真的能抓到超额、能拒绝无效豁免。

    一条守卫最容易的腐化方式不是算错，而是永远返回 0 —— 阈值调宽、路径写错、
    目录为空，任何一种都让它变成摆设。这里对每个关键判断都构造一个必失败用例。
    """
    import tempfile
    fails = []
    with tempfile.TemporaryDirectory() as td:
        ui = Path(td) / 'ui'
        ui.mkdir()
        f = ui / 'A.kt'

        f.write_text('val a = 1.dp\nval b = 2.dp\n', encoding='utf-8')
        now, _bad, _pr = survey_root(ui)
        got = now.get(str(f), {'dp': 0})
        if got['dp'] != 2:
            fails.append('计数失效：期望 dp=2，实际 %s' % got['dp'])

        f.write_text('val a = 1.dp // token-literal-ok: 这是测试用的理由\nval b = 2.dp\n',
                     encoding='utf-8')
        now, _bad, _pr = survey_root(ui)
        if now[str(f)]['dp'] != 1:
            fails.append('豁免未生效：期望 dp=1，实际 %s' % now[str(f)]['dp'])

        f.write_text('val a = 1.dp // token-literal-ok:\n', encoding='utf-8')
        _now, bad, _pr = survey_root(ui)
        if not bad:
            fails.append('空理由豁免未被拒绝')

        f.write_text('val s = "16.dp"\n', encoding='utf-8')
        now, _bad, _pr = survey_root(ui)
        if now.get(str(f), {'dp': 0})['dp'] != 0:
            fails.append('字符串内容被计入 dp')

        # 块注释里的字面量不算，且行号要对齐
        f.write_text('/*\n16.dp\n*/\nval b = 3.dp\n', encoding='utf-8')
        now, _bad, _pr = survey_root(ui)
        if now.get(str(f), {'dp': 0})['dp'] != 1:
            fails.append('块注释里的 dp 被计入')

        # 数值指纹：豁免行必须报出它豁免了哪个值。这条对应真实事故 ——
        # 豁免按行放行时，把 8.sp 改成 13.sp 而注释不动，预算照旧通过，
        # 棘轮被直接绕开。指纹是唯一能拦住它的东西。
        f.write_text('val a = 8.sp // token-literal-ok: 环心极限尺寸\n', encoding='utf-8')
        _now, _bad, pr = survey_root(ui)
        if pr.get(str(f), {}).get('1') != '8':
            fails.append('豁免行数值指纹缺失或错误：%s' % pr.get(str(f)))

        f.write_text('val a = 13.sp // token-literal-ok: 环心极限尺寸\n', encoding='utf-8')
        _now, _bad, pr2 = survey_root(ui)
        if pr2.get(str(f), {}).get('1') == pr.get(str(f), {}).get('1'):
            fails.append('8.sp 改成 13.sp 后指纹没变，改值将无法被发现')

    if fails:
        print('token coverage selftest: FAIL')
        for x in fails:
            print('  - %s' % x)
        return 1
    print('token coverage selftest: PASS（计数/豁免/空理由/字符串/块注释/豁免指纹 6 项）')
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--selftest', action='store_true',
                    help='跑反向用例，验证守卫本身有效')
    ap.add_argument('--update-baseline', action='store_true')
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if not UI.exists():
        print('token coverage: SKIP (%s not found)' % UI)
        return 0

    now, bad_exempt, prints = survey()

    if bad_exempt:
        print('token coverage: 豁免标记无效（理由必填且不少于 6 字）：')
        for b in bad_exempt:
            print('  - %s' % b)
        return 1

    if args.update_baseline:
        old = {}
        if BASELINE.exists():
            old = json.loads(BASELINE.read_text(encoding='utf-8')).get('files', {})
        merged, regress = {}, []
        for f, c in now.items():
            prev = old.get(f)
            if prev is None:
                merged[f] = c
                continue
            merged[f] = {k: min(prev.get(k, c[k]), c[k]) for k in c}
            for k in c:
                if c[k] > prev.get(k, 0):
                    regress.append('%s: %s %s -> %s' % (f, k, prev.get(k, 0), c[k]))
        for f in old:
            if f not in now:
                merged[f] = {k: 0 for k in ('color', 'sp', 'dp')}
        BASELINE.write_text(json.dumps(
            {'note': '未命名字面量预算，只许下调。由 --update-baseline 生成。'
                     ' exempt 记录每个豁免行的数值指纹：值变了必须重新登记，'
                     '否则按行放行的豁免就成了绕过棘轮的洞。',
             'files': dict(sorted(merged.items())),
             'exempt': dict(sorted((f, dict(sorted(v.items(), key=lambda x: int(x[0]))))
                                   for f, v in prints.items()))},
            ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print('token coverage: baseline 写入 %s' % BASELINE.relative_to(ROOT))
        print('  合计预算 color/sp/dp = %s' % totals(merged))
        if regress:
            print('  其中 %d 项本次上涨但预算未放宽（沿用旧的高值，说明之前就超了）：'
                  % len(regress))
            for r in regress[:8]:
                print('    · %s' % r)
        return 0

    if not BASELINE.exists():
        print('token coverage: SKIP (无基线，先跑 --update-baseline)')
        return 0

    base = json.loads(BASELINE.read_text(encoding='utf-8')).get('files', {})
    base_ex = json.loads(BASELINE.read_text(encoding='utf-8')).get('exempt', {})
    fails = []

    # 豁免行的数值指纹必须与 baseline 一致：把 8.sp 改成 13.sp 而注释原封不动
    # 是最省事、也最隐蔽的绕过方式，指纹一变就必须有人来确认。
    for f, lines in prints.items():
        prev = base_ex.get(f)
        if prev is None:
            fails.append('%s: 新增豁免行 %s（首次需 --update-baseline 登记）'
                         % (f, ', '.join(str(x) for x in sorted(lines))))
            continue
        for ln, fp in lines.items():
            want = prev.get(str(ln))
            if want is None:
                fails.append('%s:%s 新增豁免（数值 %s），需 --update-baseline 登记'
                             % (f, ln, fp))
            elif want != fp:
                fails.append('%s:%s 豁免行数值 %s -> %s（豁免不跟随数值变化，'
                             '改值必须重新登记）' % (f, ln, want, fp))
        for ln in prev:
            if str(ln) not in {str(x) for x in lines}:
                fails.append('%s:%s baseline 里的豁免行已消失（豁免被挪走或删除）'
                             % (f, ln))
    for f, c in now.items():
        prev = base.get(f)
        if prev is None:
            extra = {k: v for k, v in c.items() if v > 0}
            if extra:
                fails.append('%s: 新文件带 %s 个未命名字面量（预算 0）' % (f, extra))
            continue
        for k, v in c.items():
            cap = prev.get(k, 0)
            if v > cap:
                fails.append('%s: %s %d > 预算 %d（+%d）' % (f, k, v, cap, v - cap))

    if bad_exempt:
        fails.extend(bad_exempt)

    if fails:
        print('token coverage: %d 处超预算' % len(fails))
        for x in fails[:40]:
            print('  - %s' % x)
        if len(fails) > 40:
            print('  ... 另有 %d 处' % (len(fails) - 40))
        print()
        print('预算只许下调。若确认已把字面量换成令牌，跑：')
        print('  python3 scripts/ci/check_token_coverage.py --update-baseline')
        print('若确实新增了字面量，改用对应令牌：')
        print('  颜色 → MinisTheme.colors.* / RawColors.*')
        print('  字号 → MinisTypography.type.*（MinisType）')
        print('  尺寸 → Minis.dimens.*（MinisDimens）')
        return 1

    t_now, t_base = totals(now), totals(base)
    better = {k: t_base[k] - t_now[k] for k in t_now if t_base[k] > t_now[k]}
    msg = ' / '.join('%s %d/%d' % (k, t_now[k], t_base[k]) for k in ('color', 'sp', 'dp'))
    print('token coverage: OK（未命名 color/sp/dp = %s，后者为预算）' % msg)
    if better:
        print('  可收紧：%s —— 跑 --update-baseline 下调预算' % better)
    return 0


if __name__ == '__main__':
    sys.exit(main())
