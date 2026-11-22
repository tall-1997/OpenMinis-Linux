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


# [T-prefs-handle-callsites] A PrefsHandle kept in a variable
# (`private var prefs: PrefsHandle? = null` … `prefs = Prefs.file(PREFS)`) is how
# most stores read and write. The original matcher only understood the inline
# `Prefs.file(...).read(...)` chain, so every call through a handle was invisible —
# including five unregistered keys that crashed the tool-limits page and the
# sandbox command lock.
HANDLE_METHODS = ('string', 'stringOrNull', 'bool', 'int', 'long', 'float', 'stringSet',
 'set', 'remove', 'contains', 'observe')
RE_HANDLE_CALL = re.compile(
 r'\b([A-Za-z_][A-Za-z0-9_]*)\s*(?:\?)?\.\s*('
 r'(?:' + '|'.join(HANDLE_METHODS) + r'))\s*\(\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))')
# [T-prefs-local-key] `val key = "lastModelsRefreshDate"` … `prefs.long(key, 0L)`.
# The literal never appears at the call site, so every earlier matcher missed
# it: the key stayed unregistered, every cold start threw, and the daily model
# refresh never ran (2026-11-22 field log). Resolve such locals to their literal.
# Modifiers are captured (not look-behind: Python requires fixed-width
# look-behind, so `const  val` with two spaces would slip through).
RE_LOCAL_KEY_RAW = re.compile(
 r'(?m)^[ \t]*((?:[a-z]+[ \t]+)*)(?:val|var)[ \t]+([A-Za-z_][A-Za-z0-9_]*)'
 r'[ \t]*(?::[ \t]*String[ \t]*)?=[ \t]*"([^"]+)"[ \t]*$')


def local_string_literals(src: str) -> dict:
    """[T-prefs-local-key] `val x = "literal"` locals, EXCLUDING const val.

    `const val` is the generator's job (it becomes a registry entry); a
    non-const local is invisible to the generator and is exactly the shape
    that shipped `lastModelsRefreshDate` unregistered.
    """
    out: dict[str, set[str]] = {}
    for mods, name, value in RE_LOCAL_KEY_RAW.findall(src):
        if 'const' in mods.split():
            continue
        # [T-prefs-local-key] Collect EVERY literal assigned to the name.
        # Last-wins let a later `val key = "<registered>"` hide an earlier
        # `val key = "<unregistered>"` in the same file.
        out.setdefault(name, set()).add(value)
    return out
# [T-prefs-local-key] Identifiers declared as a prefs handle (field, param or
# local). Name matching alone flagged `private val handle = SomeIndex()`.
RE_HANDLE_DECL = re.compile(
 r'\b([A-Za-z_][A-Za-z0-9_]*)\s*:(?:\s*com\.openminis\.app\.config\.)?\s*PrefsHandle\b')
RE_FILE_CALL = re.compile(
 r'Prefs\s*\.\s*file\(\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))\s*\)')
# [T-prefs-orphan-key] Receiver-scoped key check. Only an identifier that
# NAMES a prefs handle (prefs / handle / xxxPrefs / xxxHandle) counts, and the
# key may be a literal OR a `val key = "literal"` local. Scoping matters: a
# bare `.set("x")` would match every collection in the repo.
PREF_RECEIVER = re.compile(r'(?i)^(?:prefs|handle|.*prefs|.*handle)$')
RE_TYPED_ORPHAN = re.compile(
 r'\b([A-Za-z_][A-Za-z0-9_]*)\s*(?:\?)?\.\s*('
 r'(?:' + '|'.join(HANDLE_METHODS) + r'))\s*\(\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))')
RE_HANDLE_BIND = re.compile(
 r'\b([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=\n]+)?=\s*'
 r'(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)*Prefs\s*\.\s*file\('
 r'\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))\s*\)')
# [T-prefs-accessor-callsites] A file may expose an accessor function instead of
# a handle variable: `fun getAppearancePrefs(ctx): PrefsHandle = Prefs.file(...)`,
# then `getAppearancePrefs(context).bool(KEY_X, ...)` anywhere else. That shape
# shipped an unregistered key (chat.showTurnChangesCard) and crashed on device,
# because the receiver was a call, not a variable.
RE_FUN_NAME = re.compile(r'fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(')
RE_ACCESSOR = re.compile(
 r'fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*'
 r'(?::\s*(?:com\.openminis\.app\.config\.)?PrefsHandle\s*)?=\s*'
 r'(?:[A-Za-z_][A-Za-z0-9_.]*\s*\.\s*)?Prefs\s*\.\s*file\(\s*'
 r'(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))\s*\)')
RE_ACCESSOR_CALL = re.compile(
 r'\b([A-Za-z_][A-Za-z0-9_]*)\s*\([^()]*\)\s*\.\s*('
 r'(?:' + '|'.join(HANDLE_METHODS) + r'))\s*\(\s*'
 r'(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))')


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
        # [T-prefs-local-key] Single-assignment locals whose value is a literal
        # behave like consts for resolution purposes.
        for ln, lvs in local_string_literals(s).items():
            for lv in lvs:
                local.setdefault(ln, lv)
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
       # [T-prefs-handle-callsites] Second pass: calls made through a PrefsHandle
    # variable rather than the inline `Prefs.file(...).read(...)` chain. This is
    # how most stores read and write, and it used to be entirely invisible —
    # five unregistered keys reached production that way (tool-limits page and
    # sandbox command lock crashed at runtime).
    for p, s in texts.items():
        local = dict(consts_by_file[p])
        for n, ref in RE_CONST_REF.findall(s):
            if n in local:
                continue
            v = resolve(p, ref)
            if v:
                local[n] = v
        handles = {}
        for hn, hfile_lit, hfile_ident in RE_HANDLE_BIND.findall(s):
            hfile = hfile_lit or local.get(hfile_ident) or resolve(p, hfile_ident or '')
            if hfile:
                handles[hn] = hfile
        if not handles:
            continue
        for m in RE_HANDLE_CALL.finditer(s):
            hname, hmeth, hkey_lit, hkey_ident = m.groups()
            hfile = handles.get(hname)
            if not hfile:
                continue
            hkey = hkey_lit or local.get(hkey_ident) or resolve(p, hkey_ident or '')
            if not hkey:
                continue
            hspec = specs.get(hfile)
            checked +=1
            if hspec is None:
                fails.append((p, hfile, hkey, hmeth, 'handle指向的文件名未在注册表中'))
                continue
            if hspec.get('encrypted') or (hspec.get('keys') == {} and not hspec.get('dyn_keys')):
                continue
            if not accepts(hspec, hkey):
                fails.append((p, hfile, hkey, hmeth, 'handle调用用了未登记的键'))
    # [T-prefs-accessor-callsites] Accessor-function receivers, resolved
    # globally (the accessor and its caller are usually in different files).
    accessors = {}
    declared = {}
    for p, s in texts.items():
        local = dict(consts_by_file[p])
        for n, ref in RE_CONST_REF.findall(s):
            v = resolve(p, ref)
            if v and n not in local:
                local[n] = v
        declared[p] = set(RE_FUN_NAME.findall(s))
        for an, afile_lit, afile_ident in RE_ACCESSOR.findall(s):
            afile = afile_lit or local.get(afile_ident) or resolve(p, afile_ident or '')
            if afile:
                # [T-prefs-accessor-unique] Accessor names are NOT globally unique
                # (TranslationPrefs.prefs() and MCPOAuthStore.prefs() both exist).
                # Keep every candidate and accept a call if ANY of them registers
                # the key; a last-wins dict silently skipped whole call sites.
                accessors.setdefault(an, set()).add((afile, p))
    for p, s in texts.items():
        local = dict(consts_by_file[p])
        for n, ref in RE_CONST_REF.findall(s):
            v = resolve(p, ref)
            if v and n not in local:
                local[n] = v
        for m in RE_ACCESSOR_CALL.finditer(s):
            aname, ameth, akey_lit, akey_ident = m.groups()
            if aname not in accessors:
                continue
            pairs = sorted(accessors[aname])
            akey = akey_lit or local.get(akey_ident) or resolve(p, akey_ident or '')
            if not akey:
                continue
            checked +=1
            # Prefer the accessor declared in THIS file (common shorthand like
            # `prefs(...)` exists in several stores). If the name is still
            # ambiguous and no candidate accepts the key, treat it as
            # unverifiable instead of guessing — a wrong guess used to either
            # miss a real key or flag valid code.
            same_file = sorted({af for af, df in pairs if df == p})
            # The call site declares this name itself (e.g. a block-bodied
            # `private fun requirePrefs(ctx): PrefsHandle { ... }`): the global
            # accessor list is a different function, so guessing its target file
            # is wrong. Skip instead of flagging valid code.
            if not same_file and aname in declared.get(p, set()):
                continue
            if same_file:
                candidates = same_file
            else:
                distinct = sorted({af for af, _ in pairs})
                # A block-bodied accessor elsewhere (fun x(): PrefsHandle {
                # ... return prefs }) cannot be mapped to a file statically, so
                # a globally ambiguous name is unverifiable — skip it instead
                # of guessing another store's file (that guess produced false
                # positives on PendingUpdateStore/requirePrefs).
                if len(distinct) !=1:
                    continue
                candidates = distinct
            accepted = False
            for afile in candidates:
                spec = specs.get(afile)
                if spec is None:
                    accepted = True
                    break
                if spec.get('keys') == {} and not spec.get('dyn_keys'):
                    accepted = True
                    break
                if accepts(spec, akey):
                    accepted = True
                    break
            if not accepted and len(candidates) ==1:
                fails.append((p, candidates[0], akey, ameth, 'accessor 调用用了未登记的键'))
    # [T-prefs-local-key] THE shape that shipped `lastModelsRefreshDate`:
    #
    #     val key = "lastModelsRefreshDate"     <- literal lives here
    #     prefs.long(key, 0L)                    <- call site shows only `key`
    #
    # Every literal-key matcher above sees nothing at the call site, and the
    # handle is constructor-injected so no Prefs.file() exists in the file to
    # bind it either.
    #
    # Two rules this pass follows, both learned the hard way in review:
    #  1. A file whose handle IS resolvable is judged against THAT spec only
    #     (`accepts`) — a key registered in some other file must still fail,
    #     or cross-file misuse passes silently.
    #  2. A name assigned twice in one file resolves to EVERY literal, and the
    #     call fails if any of them is unregistered (last-wins hid real bugs).
    open_specs = [n for n, sp in specs.items() if sp.get('open_key_space')]
    all_keys = set()
    dyn_prefixes = []
    for sp in specs.values():
        all_keys.update((sp.get('keys') or {}).values())
        dyn_prefixes.extend(sp.get('dyn_keys') or [])
    for p, s in texts.items():
        local = local_string_literals(s)
        if not local:
            continue
        declared_handles = set(RE_HANDLE_DECL.findall(s)) | set(
            h for h, _lit, _ident in RE_HANDLE_BIND.findall(s))
        file_spec_names = [
            lit or resolve(p, ident or '')
            for lit, ident in RE_FILE_CALL.findall(s)
        ]
        known_handle_files = {n for n in file_spec_names if n}
        for m in RE_TYPED_ORPHAN.finditer(s):
            recv, meth, key_lit, key_ident = m.groups()
            if key_lit or not key_ident:
                continue          # direct literal: not this bug's shape
            if recv not in declared_handles:
                continue          # not a prefs handle we can see
            for key in local.get(key_ident, ()):
                checked +=1
                if key in all_keys:
                    # Registered SOMEWHERE. When this file's handle target is
                    # known we still demand that the right file owns it.
                    ownable = [n for n in known_handle_files if n in specs]
                    if ownable and not any(accepts(specs[n], key) for n in ownable):
                        if any(specs[n].get('open_key_space') for n in ownable):
                            continue
                        fails.append((
                            p, '/'.join(ownable), key, meth,
                            '局部变量键 `' + key_ident + '` 登记在**别的**文件里，'
                            '本文件读它会在运行期抛（键归属错了）',
                        ))
                    continue
                if any(key.startswith(pref) for pref in dyn_prefixes):
                    continue
                # Unregistered everywhere: legitimate only inside a file that
                # declares an open-key-space prefs, which accepts anything.
                if known_handle_files and all(n in open_specs for n in known_handle_files):
                    continue
                fails.append((
                    p, '<' + recv + '>', key, meth,
                    '局部变量键 `' + key_ident + '` 在任何 PrefsFileSpec 里都没登记；'
                    '生成器只认 const val，这种写法会绕过它（改成 const val 或补登记）',
                ))
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
        print(f'prefs call sites: FAIL ({INV} not found；fail-closed)')
        return 1
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
