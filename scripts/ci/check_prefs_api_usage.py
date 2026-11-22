#!/usr/bin/env python3
"""核对 PrefsHandle / SharedPreferences 的接收者类型与调用方法是否匹配。

PrefsHandle  API: string/int/bool/set/remove/write/observe/snapshot/rawKeys/all/clear
SharedPreferences API: getString/getInt/getBoolean/getFloat/getLong/edit/register.../unregister...

为什么要这条守卫：`PrefsHandle` 是 SharedPreferences 的替代品，两套方法名几乎
不重叠（int vs getInt、bool vs getBoolean）。迁移时把调用点改成新方法名、却漏改
接收者的声明类型（或反过来），编译器报的是「unresolved reference: int」，指向调用
点，看不出真正的原因是声明还是调用 —— 一个文件一个文件翻很费时。

判据：以「变量名 -> 声明类型」的映射为准，逐个调用点比对。类型来源三处：
  1. 显式声明 `val x: SharedPreferences = ...` / `val x: PrefsHandle = ...`
  2. 参数 `x: SharedPreferences`
  3. 已知工厂（类型推断）：`Prefs.file(...)` → PrefsHandle、
     `getAppearancePrefs(...)` → PrefsHandle、`EncryptedPrefsFactory.safeCreate(...)` → SharedPreferences

注释与字符串字面量先掩码，否则注释里举的 `prefs.edit().commit()` 会误报。

用法：python3 scripts/ci/check_prefs_api_usage.py [源码根目录]
"""
import os
import re
import sys

PH_METHODS = {'string', 'int', 'bool', 'set', 'write', 'observe', 'remove',
              'snapshot', 'rawKeys', 'all', 'clear'}
SP_METHODS = {'getString', 'getInt', 'getBoolean', 'getFloat', 'getLong',
              'getStringSet', 'edit', 'registerOnSharedPreferenceChangeListener',
              'unregisterOnSharedPreferenceChangeListener'}

DECL = re.compile(
    r'(?:private\s+|internal\s+|public\s+)?(?:val|var)\s+(\w+)\s*:\s*([\w.<>]+)\s*='
)
INFER = re.compile(
    r'(?:private\s+|internal\s+|public\s+)?(?:val|var)\s+(\w+)\s*=\s*'
    r'(?:com\.openminis\.app\.config\.Prefs\.file|Prefs\.file|getAppearancePrefs'
    r'|com\.openminis\.app\.ui\.settings\.getAppearancePrefs'
    r'|EncryptedPrefsFactory\.safeCreate|com\.openminis\.app\.util\.EncryptedPrefsFactory\.safeCreate)'
)
CALL = re.compile(r'\b(\w+)\s*\.\s*(\w+)\s*\(')
PARAM = re.compile(r'(\w+)\s*:\s*(SharedPreferences|com\.openminis\.app\.config\.PrefsHandle|PrefsHandle)\b')


def mask_source(src: str) -> str:
    """把注释和字符串字面量替换成等长空格，保留行号。"""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = ' '
            i = j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if src[k] != '\n':
                    out[k] = ' '
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i, j):
                if src[k] != '\n':
                    out[k] = ' '
            i = j
        elif c == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                if src[j] == '\n':
                    break
                j += 1
            for k in range(i, min(j, n)):
                out[k] = ' '
            i = j
        elif c == "'":
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == "'":
                    j += 1
                    break
                if src[j] == '\n':
                    break
                j += 1
            for k in range(i, min(j, n)):
                out[k] = ' '
            i = j
        else:
            i += 1
    return ''.join(out)


def classify(t: str) -> str:
    t = (t or '').strip()
    if 'SharedPreferences' in t and 'OnShared' not in t:
        return 'SP'
    if 'PrefsHandle' in t:
        return 'PH'
    return '?'


def scan(root: str):
    problems = []
    scanned = 0
    for dirpath, _, files in os.walk(root):
        for fn in files:
            if not fn.endswith('.kt'):
                continue
            path = os.path.join(dirpath, fn)
            raw = open(path, encoding='utf-8', errors='replace').read()
            scanned += 1
            src = mask_source(raw)
            types = {}
            for m in DECL.finditer(src):
                k = classify(m.group(2))
                if k != '?':
                    types.setdefault(m.group(1), k)
            for m in INFER.finditer(src):
                types.setdefault(m.group(1), 'SP' if 'safeCreate' in m.group(0) else 'PH')
            for m in PARAM.finditer(src):
                types.setdefault(m.group(1), classify(m.group(2)))
            for m in CALL.finditer(src):
                name, method = m.group(1), m.group(2)
                kind = types.get(name)
                if kind is None:
                    continue
                line = src[:m.start()].count('\n') + 1
                if kind == 'PH' and method in SP_METHODS:
                    problems.append((path, line, name, method, 'PrefsHandle 上用了 SharedPreferences 方法'))
                elif kind == 'SP' and method in PH_METHODS:
                    problems.append((path, line, name, method, 'SharedPreferences 上用了 PrefsHandle 方法'))
    return problems, scanned


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    problems, scanned = scan(root)
    print(f'scanned {scanned} files')
    if not problems:
        print('OK: 接收者类型与调用方法一致')
        return 0
    for p in problems:
        print(f'{os.path.relpath(p[0], root)}:{p[1]}: {p[2]}.{p[3]}() —— {p[4]}')
    print(f'\n{len(problems)} mismatch(es)')
    return 1


if __name__ == '__main__':
    sys.exit(main())
