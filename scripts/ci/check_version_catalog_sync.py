#!/usr/bin/env python3
"""CI 守卫：build-logic 的版本目录与根目录保持同步。

为什么需要单独一条：
`src/android/build-logic` 是 `includeBuild` 的 composite build，它有自己独立的
`gradle/libs.versions.toml` —— 根目录那份对它不可见。于是同一个 Kotlin 版本号在
两个文件里各写一遍，改一处漏一处是最常见的漂移。

漂移的后果不是编译错（两边都能编），而是 build-logic 里的 convention plugin
用 Kotlin A 版本生成 class，app 用 Kotlin B 版本编译 —— 表现为莫名其妙的
metadata 版本不匹配或 KotlinPluginVersion 冲突，报错信息完全指不到这里。

用法：
    python3 scripts/ci/check_version_catalog_sync.py
    python3 scripts/ci/check_version_catalog_sync.py --selftest
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

try:  # Python 3.11+
    import tomllib as _tomllib
except ModuleNotFoundError:  # 3.10 及更早：CI runner 上就是这么崩的
    _tomllib = None

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "src" / "android"
MAIN = ANDROID / "gradle" / "libs.versions.toml"
BUILD_LOGIC = ANDROID / "build-logic" / "gradle" / "libs.versions.toml"


# ── 最小 TOML 子集解析器（Python < 3.11 的 tomllib 替代）───────────────────
# 为什么需要：CI runner 上的 python3 是 3.10，没有 tomllib，守卫直接 NameError。
# 只实现版本目录实际用到的语法：表头、键值、字符串、整数、布尔、数组、
# 行内表（{ ... }）、注释。足够的判断依据是 —— 版本目录由 Gradle 校验，
# 语法畸形的 TOML 本来就进不了构建。

def _strip_comment(line: str) -> str:
    out, quote = [], None
    for ch in line:
        if quote:
            out.append(ch)
            if ch == quote:
                quote = None
        elif ch in ("\"", "'"):
            quote = ch
            out.append(ch)
        elif ch == "#":
            break
        else:
            out.append(ch)
    return "".join(out).strip()


def _split_top(text: str, sep: str = ",") -> list[str]:
    parts, buf, depth, quote = [], [], 0, None
    for ch in text:
        if quote:
            buf.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in ("\"", "'"):
            quote = ch
            buf.append(ch)
        elif ch in "[{":
            depth += 1
            buf.append(ch)
        elif ch in "]}":
            depth -= 1
            buf.append(ch)
        elif ch == sep and depth == 0:
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
    if buf:
        parts.append("".join(buf))
    return [p.strip() for p in parts if p.strip()]


def _value(token: str):
    token = token.strip()
    if not token:
        return ""
    if token.startswith('"""') and token.endswith('"""'):
        return token[3:-3]
    if token[0] in ("\"", "'") and token[-1] == token[0]:
        body = token[1:-1]
        if token[0] == '"':
            body = (body.replace('\\n', '\n').replace('\\t', '\t')
                        .replace('\\"', '"').replace('\\\\', '\\'))
        return body
    if token.startswith("["):
        inner = token[1:-1] if token.endswith("]") else token[1:]
        return [_value(x) for x in _split_top(inner)]
    if token.startswith("{"):
        inner = token[1:-1] if token.endswith("}") else token[1:]
        table = {}
        for item in _split_top(inner):
            k, _, v = item.partition("=")
            # TOML 允许 inline table 里用点分键：{ module = "x", version.ref = "y" }
            # 语义等价于 { module = "x", version = { ref = "y" } }
            parts = [p.strip().strip('"\'') for p in k.split(".")]
            node = table
            for part in parts[:-1]:
                nxt = node.get(part)
                if not isinstance(nxt, dict):
                    nxt = node[part] = {}
                node = nxt
            node[parts[-1]] = _value(v)
        return table
    low = token.lower()
    if low in ("true", "false"):
        return low == "true"
    try:
        return int(token, 0)
    except ValueError:
        pass
    try:
        return float(token)
    except ValueError:
        return token


def _parse_toml(text: str) -> dict:
    data: dict = {}
    cur = data
    for raw in text.splitlines():
        line = _strip_comment(raw)
        if not line:
            continue
        if line.startswith("[["):
            name = line.strip("[]").strip()
            node = data.setdefault(name, [])
            if not isinstance(node, list):
                node = data[name] = []
            node.append({})
            cur = node[-1]
            continue
        if line.startswith("["):
            name = line.strip("[]").strip()
            cur = data
            for part in name.split("."):
                part = part.strip().strip('"\'')
                nxt = cur.get(part)
                if not isinstance(nxt, dict):
                    nxt = cur[part] = {}
                cur = nxt
            continue
        key, sep, val = line.partition("=")
        if not sep:
            continue
        key = key.strip().strip('"\'')
        cur[key] = _value(val)
    return data


def _toml_loads(text: str) -> dict:
    return _parse_toml(text)


def _toml_load(path: Path) -> dict:
    return _parse_toml(path.read_text(encoding="utf-8"))


def parse_toml(path: Path) -> dict:
    """统一入口：有 tomllib 用它，没有就用内置子集解析器。"""
    if _tomllib is not None:
        with path.open("rb") as fh:
            return _tomllib.load(fh)
    return _toml_load(path)


def parse_toml_text(text: str) -> dict:
    if _tomllib is not None:
        return _tomllib.loads(text)
    return _toml_loads(text)


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)

# build-logic 必须与根目录同值的版本键。
# 只列真正会被两边同时解析进同一次编译的键 —— build-logic 的 convention plugin
# 与 app 模块共享 Kotlin 编译器版本，别的（Compose、Room）不共享。
SHARED_KEYS = ("kotlin",)


def load(path: Path) -> dict:
    return parse_toml(path)


def compare(main: dict, sub: dict, shared: tuple[str, ...] = SHARED_KEYS) -> list[str]:
    """返回不一致项。抽成纯函数是为了让 --selftest 不依赖真实文件。"""
    mv = main.get("versions", {})
    sv = sub.get("versions", {})
    problems: list[str] = []
    for key in shared:
        if key not in sv:
            problems.append(f"build-logic 的版本目录缺少 `{key}`（根目录为 {mv.get(key)!r}）")
            continue
        if key not in mv:
            problems.append(f"根目录的版本目录缺少 `{key}`（build-logic 为 {sv[key]!r}）")
            continue
        if mv[key] != sv[key]:
            problems.append(
                f"`{key}` 漂移：根目录 {mv[key]!r} ≠ build-logic {sv[key]!r}"
            )
    return problems


def check_catalog(catalog_path: Path) -> list[str]:
    """校验版本目录里没有 Gradle 保留词别名。

    为什么单列一条：`class` / `extensions` / `convention` 等词是 Gradle
    版本目录的保留名，一旦出现在别名里，**整个构建在配置阶段就失败**，
    报错是 "Alias '...' is a reserved name in Gradle which prevents
    generation of accessors"。它和依赖是否正确无关 —— 即使该别名从未被
    引用（死条目），照样炸。本轮实测踩过：`androidx-material3-window-size-class`
    让 CI 编译步骤在 5 分 47 秒时失败，而本地静态守卫因为不解析 toml
    语义完全看不见。
    """
    problems: list[str] = []
    try:
        data = parse_toml_text(catalog_path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        return [f"{rel(catalog_path)}: 无法解析 TOML — {exc}"]

    # Gradle 文档列出的保留名（小写比较，'-'/'_'/'.' 都视作分隔符）
    reserved = {"class", "extensions", "convention", "bundles", "versions", "plugins"}

    for section in ("libraries", "plugins", "bundles"):
        for alias in data.get(section, {}):
            for token in re.split(r"[-_.]", alias.lower()):
                if token in reserved:
                    problems.append(
                        f"{rel(catalog_path)}: [{section}] 别名 '{alias}' 含保留词 "
                        f"'{token}' → 改名（如 windowsize），否则整个构建在配置阶段失败"
                    )
                    break
    return problems


def check_accessors(android_root: Path) -> list[str]:
    """扫描 build 脚本里的 `libs.<alias>` 访问器。

    为什么要扫访问器而不只扫目录：别名含 `class` 时，访问器会展开成
    `libs.androidx.material3.window.size.class` —— `class` 是 Kotlin
    硬关键字，这一行**本身就是语法错误**，报错还是 "Name expected" /
    "Unresolved reference: window" 这种完全指不到根因的形式（同文件
    后面的报错全是解析器级联）。实测踩过两次：一次是目录里的别名，
    一次是访问器。两边都必须查。
    """
    problems: list[str] = []
    # 注意：`versions` / `plugins` / `bundles` 是访问器的**合法前缀段**
    # （libs.versions.compileSdk、libs.plugins.kotlin.android），只有它们
    # 出现在别名内部才算问题。所以 reserved 里不能含这三个词 ——
    # 保留词检查只针对真正的「别名可能出现的关键字」。
    keyword = {"class", "extensions", "convention"}
    accessor = re.compile(r"libs(?:\.([A-Za-z0-9_]+))+")

    for build in sorted(android_root.rglob("build.gradle.kts")):
        for lineno, raw in enumerate(build.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if raw.strip().startswith("//"):
                continue
            for m in accessor.finditer(raw):
                text = m.group(0)
                parts = [p for p in text.split(".")[1:] if p]
                # 去掉前缀段，剩下的才是别名
                while parts and parts[0] in ("versions", "plugins", "bundles"):
                    parts.pop(0)
                if any(p.lower() in keyword for p in parts):
                    problems.append(
                        f"{rel(build)}:{lineno}: 访问器 '{text}' 含 Kotlin 关键字 "
                        f"→ 该行是语法错误，改别名（如 windowsize）"
                    )
                    break
    return problems


def selftest() -> int:
    cases = [
        ("一致应通过", {"versions": {"kotlin": "2.1.0"}}, {"versions": {"kotlin": "2.1.0"}}, 0),
        ("漂移应抓出", {"versions": {"kotlin": "2.1.0"}}, {"versions": {"kotlin": "2.0.0"}}, 1),
        ("子缺失应抓出", {"versions": {"kotlin": "2.1.0"}}, {"versions": {}}, 1),
        ("根缺失应抓出", {"versions": {}}, {"versions": {"kotlin": "2.1.0"}}, 1),
    ]
    bad = 0
    for label, a, b, want in cases:
        got = len(compare(a, b))
        ok = got == want
        bad += 0 if ok else 1
        print(f"{'ok  ' if ok else 'FAIL'} [{label}] 期望 {want} 实际 {got}")
    # 保留词别名：这条是 CI 实测踩出来的（androidx-material3-window-size-class
    # 让整个构建在配置阶段失败，而当时所有本地守卫全绿）。
    catalog_cases = [
        ("保留词 class 应抓出", '[libraries]\nx-window-size-class = { module = "a:b" }\n', 1),
        ("保留词 extensions 应抓出", '[libraries]\nx-extensions = { module = "a:b" }\n', 1),
        ("改名后应通过", '[libraries]\nx-windowsize = { module = "a:b" }\n', 0),
        ("plugins 段也要查", '[plugins]\nx-class = { id = "a.b" }\n', 1),
        ("普通别名应通过", '[libraries]\nandroidx-core-ktx = { module = "a:b" }\n', 0),
    ]
    import tempfile

    with tempfile.TemporaryDirectory() as td:
        for label, body, want in catalog_cases:
            p = Path(td) / "libs.versions.toml"
            p.write_text(body, encoding="utf-8")
            got = len(check_catalog(p))
            ok = got == want
            bad += 0 if ok else 1
            print(f"{'ok  ' if ok else 'FAIL'} [{label}] 期望 {want} 实际 {got}")

        # 访问器形态：libs.androidx.material3.window.size.class 本身就是语法错误
        acc_cases = [
            ("访问器含 class 应抓出", "implementation(libs.a.b.class)\n", 1),
            ("正常访问器应通过", "implementation(libs.androidx.core.ktx)\n", 0),
            ("注释里的不算", "// implementation(libs.a.b.class)\n", 0),
            ("versions 前缀应通过", "compileSdk = libs.versions.compileSdk.get().toInt()\n", 0),
            ("plugins 前缀应通过", "alias(libs.plugins.kotlin.android) apply false\n", 0),
        ]
        for label, body, want in acc_cases:
            root = Path(td) / "fake-root"
            root.mkdir(exist_ok=True)
            (root / "build.gradle.kts").write_text(body, encoding="utf-8")
            got = len(check_accessors(root))
            ok = got == want
            bad += 0 if ok else 1
            print(f"{'ok  ' if ok else 'FAIL'} [{label}] 期望 {want} 实际 {got}")

    # 后备解析器自测：CI 的 python3 是 3.10，没有 tomllib，
    # 这条路径在 CI 上是唯一被执行的实现，必须自证语义正确。
    toml_cases = [
        (
            "点分键内联表",
            '[versions]\nagp = "8.7.3"\n[plugins]\nx = { id = "a.b", version.ref = "agp" }\n',
            {
                "versions": {"agp": "8.7.3"},
                "plugins": {"x": {"id": "a.b", "version": {"ref": "agp"}}},
            },
        ),
        (
            "注释与数组与布尔",
            '# 注释\n[a]\nb = ["x", "y"] # 行尾注释\nc = true\n',
            {"a": {"b": ["x", "y"], "c": True}},
        ),
        ("嵌套表头", '[libraries.foo]\nmodule = "a:b"\n', {"libraries": {"foo": {"module": "a:b"}}}),
        ("字符串里的井号", '[a]\nb = "x#y"\n', {"a": {"b": "x#y"}}),
        ("单引号字面量", "[a]\nb = 'x\\\\y'\n", {"a": {"b": "x\\\\y"}}),
        ("多行表数组", '[[x]]\nn = 1\n[[x]]\nn = 2\n', {"x": [{"n": 1}, {"n": 2}]}),
    ]
    for label, body, want in toml_cases:
        got = _toml_loads(body)
        ok = got == want
        bad += 0 if ok else 1
        print(f"{'ok  ' if ok else 'FAIL'} [{label}]" + ("" if ok else f" 得到 {got!r}"))

    total = len(cases) + len(catalog_cases) + len(acc_cases) + len(toml_cases)
    print(f"\n自测 {total - bad}/{total} 通过")
    return 0 if bad == 0 else 1


def main(argv: list[str]) -> int:
    if "--selftest" in argv:
        return selftest()
    if not MAIN.exists():
        print(f"version catalog check: 找不到 {MAIN.relative_to(ROOT)}")
        return 1
    if not BUILD_LOGIC.exists():
        print(f"version catalog check: 找不到 {BUILD_LOGIC.relative_to(ROOT)}")
        return 1

    problems = compare(load(MAIN), load(BUILD_LOGIC))
    problems += check_catalog(MAIN)
    problems += check_catalog(BUILD_LOGIC)
    problems += check_accessors(ANDROID)
    if problems:
        print(f"version catalog check: FAILED ({len(problems)} 处)")
        for p in problems:
            print(f"  - {p}")
        print("\n修法：版本漂移就改成同一个值；保留词别名就改名。")
        return 1
    print(f"version catalog check: OK（{'/'.join(SHARED_KEYS)} 一致，无保留词别名）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
