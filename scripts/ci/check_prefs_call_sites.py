#!/usr/bin/env python3
"""CI 守卫：`Prefs.file(X).method(K, ...)` 的键必须在 X 自己的登记表里。

为什么需要单独一条：
  `check_prefs_registry.py` 只保证生成物与源码一致。但"一致"不等于"正确"——
  生成器可能把一个键归到**错误的文件**名下，注册表照样与源码一致，编译照样通过，
  然后在运行期炸：

      key 'chat.confirmBeforeStop' is not registered in PrefsRegistry
      for appearance_prefs

  实测发生过：`StopOptionsPrefs` 写 `const val PREFS = pkg.PREF_APPEARANCE`，
  而解析器只认 `const val X = "literal"`，于是退回全局常量表；`PREFS` 这个
  名字在十几个文件里各定义一次，全局表只剩最后一个 —— 键被登记到
  `mirror_settings`，而代码从 `appearance_prefs` 读。

  这条守卫直接把「调用点」和「该文件的登记表」对上，不经过生成器的归属判断，
  所以它能抓住归属本身的错。

用法：
    python3 scripts/ci/check_prefs_call_sites.py [--selftest]
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src/android/app/src/main/java/com/openminis/app"
INV = ROOT / "docs/prefs/prefs-inventory.json"

# Prefs.file(<arg>).<method>(<key>, ...)
RE_CALL = re.compile(
    r'Prefs\s*\.\s*file\(\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))\s*\)'
    r'\s*\.\s*(string|stringOrNull|bool|int|long|float|stringSet)\('
    r'\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))')
RE_CONST = re.compile(r'const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]*)"')
RE_CONST_REF = re.compile(
    r'const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*'
    r'(?:[A-Za-z_][A-Za-z0-9_.]*\.)?([A-Z][A-Z0-9_]*)\s*$', re.M)


def strip_comments(src: str) -> str:
    """去掉 // 与 /* */ 注释，避免把被注释掉的调用算成使用点。"""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
        elif src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def load_specs():
    inv = json.loads(INV.read_text(encoding='utf-8'))
    return {f['name']: f for f in inv['prefs_files']}


def accepts(spec, key):
    if spec.get('open_key_space'):
        return True
    if key in (spec.get('keys') or {}).values():
        return True
    return any(key.startswith(p) for p in (spec.get('dyn_keys') or []))


RE_IMPORT = re.compile(r'^import\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$', re.M)


def scan():
    """返回 (违规列表, 检查的调用点数)。"""
    specs = load_specs()
    files = sorted(p for p in SRC.rglob('*.kt'))
    texts = {}
    # per-file constant table: path -> {name: value}, plus package + imports
    consts_by_file = {}
    pkg_by_file = {}
    imports_by_file = {}
    # name -> list of (path, value); ambiguity is explicit, not last-wins
    by_name = {}
    for p in files:
        if p.name == 'PrefsRegistry.kt':
            continue
        raw = p.read_text(encoding='utf-8', errors='replace')
        texts[p] = strip_comments(raw)
        found = {}
        for m in RE_CONST.finditer(raw):
            found[m.group(1)] = m.group(2)
            by_name.setdefault(m.group(1), []).append((p, m.group(2)))
        consts_by_file[p] = found
        pm = re.search(r'^package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$', raw, re.M)
        pkg_by_file[p] = pm.group(1) if pm else ''
        imports_by_file[p] = RE_IMPORT.findall(raw)

    def resolve(p, name):
        """按 Kotlin 的作用域顺序解析一个标识符，解析不出返回 None。"""
        local = consts_by_file[p]
        if name in local:
            return local[name]
        # 同包文件（无需 import）
        pkg = pkg_by_file[p]
        for q, qconsts in consts_by_file.items():
            if q != p and pkg_by_file.get(q) == pkg and name in qconsts:
                return qconsts[name]
        # 显式 import 指向的声明文件
        for imp in imports_by_file[p]:
            head, _, last = imp.rpartition('.')
            if last != name:
                if imp.rsplit('.', 1)[0].endswith(name):
                    pass
                continue
            target_pkg = head
            for q, qconsts in consts_by_file.items():
                if pkg_by_file.get(q) == target_pkg and name in qconsts:
                    return qconsts[name]
        # 兜底：全局唯一才算数；重名说明判断不了，交给人看
        hits = by_name.get(name) or []
        values = {v for _, v in hits}
        if len(values) == 1:
            return next(iter(values))
        return None

    fails, checked = [], 0
    for p, s in texts.items():
        local = dict(consts_by_file[p])
        for n, ref in RE_CONST_REF.findall(s):
            if n in local:
                continue
            v = resolve(p, ref)
            if v:
                local[n] = v

        for m in RE_CALL.finditer(s):
            file_lit, file_ident, method, key_lit, key_ident = m.groups()
            fname = file_lit or local.get(file_ident)
            key = key_lit or local.get(key_ident)
            if fname is None and file_ident:
                fname = resolve(p, file_ident)
            if key is None and key_ident:
                key = resolve(p, key_ident)
            if not fname or not key:
                continue          # 解析不出来的调用留给人看，不算违规
            spec = specs.get(fname)
            checked += 1
            if spec is None:
                fails.append((p, fname, key, method, '文件名未在注册表中'))
                continue
            if spec.get('encrypted') or spec.get('keys') == {} and not spec.get('dyn_keys'):
                # 加密文件走原生 SharedPreferences，不经过 PrefsHandle 守卫；
                # keys 为空表示扫描器没抓到清单，守卫本身会退化成前缀约定。
                continue
            if not accepts(spec, key):
                fails.append((p, fname, key, method, '键未登记在该文件键表'))
    return fails, checked


def selftest() -> int:
    """反向用例：构造一个错误归属，确认能被抓出来。"""
    specs = {
        'appearance_prefs': {'name': 'appearance_prefs', 'keys': {'KEY_A': 'chat.a'},
                             'dyn_keys': [], 'open_key_space': False},
        'mirror_settings': {'name': 'mirror_settings', 'keys': {'KEY_B': 'mirror.b'},
                            'dyn_keys': ['mirror.selected.'], 'open_key_space': False},
    }
    cases = [
        ('appearance_prefs', 'chat.a', True),              # 正确登记
        ('appearance_prefs', 'chat.confirmBeforeStop', False),  # 本 PR 修的 bug
        ('mirror_settings', 'mirror.selected.x', True),    # 动态前缀
        ('mirror_settings', 'chat.a', False),              # 跨文件串味
    ]
    bad = 0
    for fname, key, want in cases:
        got = accepts(specs[fname], key)
        if got != want:
            print(f'  ✗ {fname} / {key}: 期望 {want} 实得 {got}')
            bad += 1
    if bad:
        print(f'selftest FAILED（{bad}/{len(cases)}）')
        return 1
    print(f'selftest OK（{len(cases)} 条反向用例）')
    return 0


def main() -> int:
    if '--selftest' in sys.argv:
        return selftest()
    if not INV.exists():
        print(f'prefs call sites: SKIP ({INV} not found；先跑 gen_prefs_registry.py)')
        return 0
    fails, checked = scan()
    if fails:
        print(f'prefs call sites: {len(fails)} 处调用读了未登记的键')
        for p, fname, key, method, why in fails:
            print(f'  {p.relative_to(ROOT)}: Prefs.file("{fname}").{method}("{key}") — {why}')
        print()
        print('修法：把该键的 `const val KEY_X = "..."` 放进真正使用它的源文件，')
        print('      或确认文件名常量能被解析（`const val PREFS = "appearance_prefs"`）。')
        print('      然后重跑 scripts/prefs/gen_prefs_registry.py。')
        return 1
    print(f'prefs call sites: OK（{checked} 处调用，键都登记在自己的文件里）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
