#!/usr/bin/env python3
"""校验 ui/ 下对 RawColors.* 的引用都能在 gen/MinisTokens.kt 里找到定义。

为什么需要：令牌是生成物，引用是手改的。拼错一个常量名 kotlinc 会报
unresolved reference，但那是整项目编译时才发现；这个检查 0.1 秒给出全部错处。
"""
import os
import re
import sys

REF = re.compile(r'RawColors\.((?:Brand|BrandDark)\.)?([A-Za-z0-9_]+)')
DEF = re.compile(r'(?:object (Brand|BrandDark)|object RawColors)')
CONST = re.compile(r'const val ([A-Za-z0-9_]+): Long')
VAL = re.compile(r'val ([A-Za-z0-9_]+): List<Long>')


def collect_defs(tokens_path):
    s = open(tokens_path, encoding='utf-8').read()
    top, brand, brand_dark, lists = set(), set(), set(), set()
    scope = 'top'
    for line in s.split('\n'):
        st = line.strip()
        if st.startswith('object BrandDark'):
            scope = 'dark'
        elif st.startswith('object Brand'):
            scope = 'brand'
        elif st.startswith('object RawColors'):
            scope = 'top'
        elif st.startswith('}') and scope in ('brand', 'dark'):
            scope = 'top'
        m = CONST.search(st)
        if m:
            {'top': top, 'brand': brand, 'dark': brand_dark}[scope].add(m.group(1))
            continue
        m = VAL.search(st)
        if m:
            lists.add(m.group(1))
    return top, brand, brand_dark, lists


def main():
    ui = sys.argv[1]
    tokens = sys.argv[2] if len(sys.argv) > 2 else 'gen/MinisTokens.kt'
    top, brand, bd, lists = collect_defs(tokens)
    bad = []
    n = 0
    for dp, _, fs in os.walk(ui):
        for f in fs:
            if not f.endswith('.kt'):
                continue
            p = os.path.join(dp, f)
            s = open(p, encoding='utf-8', errors='replace').read()
            for m in REF.finditer(s):
                n += 1
                grp, name = m.group(1), m.group(2)
                pool = brand if grp == 'Brand.' else bd if grp == 'BrandDark.' else top | lists
                if name not in pool:
                    bad.append('%s: RawColors.%s%s' % (os.path.relpath(p, ui), grp or '', name))
    print('引用点 %d，定义 %d(top) + %d(Brand) + %d(BrandDark) + %d(list)'
          % (n, len(top), len(brand), len(bd), len(lists)))
    if bad:
        print('未定义引用 %d 处：' % len(bad))
        for b in bad[:40]:
            print('  ' + b)
        return 1
    print('PASS: 全部引用都有定义')
    return 0


if __name__ == '__main__':
    sys.exit(main())
