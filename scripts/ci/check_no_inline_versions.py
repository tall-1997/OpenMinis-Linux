#!/usr/bin/env python3
"""CI 守卫：build.gradle.kts 的 dependencies 块里不许出现内联版本号。

为什么需要这条守卫
------------------
版本目录（gradle/libs.versions.toml）只有在没人绕过它的时候才是「唯一来源」。
Gradle 不会因为你写 `implementation("g:a:1.2.3")` 而报错，于是同一个坐标会在
三个模块里漂出三个版本 —— 本项目历史上 coroutines 在 app 与 provider:api
各写一遍、kotlinx-serialization 在 app 与 core:model 各写一遍，就是这条路。

判据
----
在 dependencies { ... } 块内（按花括号配平切出），扫描字符串字面量，
匹配 `group:artifact:version` 且 version 段以数字开头 → 违规。

豁免
----
* `project(":core:model")` —— 模块依赖，没有版本概念。
* `platform(...)` 内部的 BOM 坐标本身需要版本（BOM 就是版本清单）。
* `group = "" , name = "rclone", ext = "aar"` 这类本地 libs/*.aar 写法。
* 行尾带 `// version-catalog-ok: <理由>` 的行。

用法
----
    python3 scripts/ci/check_no_inline_versions.py
    python3 scripts/ci/check_no_inline_versions.py --selftest
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD_FILES = sorted(
    p for p in ROOT.glob("src/android/**/build.gradle.kts")
) + sorted(p for p in ROOT.glob("src/android/build.gradle.kts"))

# group:artifact:version —— version 以数字开头（排除 :$var 与 :{...})
COORD = re.compile(r'"([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([0-9][^"]*)"')

ALLOW_MARK = "version-catalog-ok"

# `id("com.android.application") version "8.7.3"`
PLUGIN_VER = re.compile(r'id\(\s*"([A-Za-z0-9_.\-]+)"\s*\)\s*version\s*"([^"]+)"')


def _block_span(src: str, opener: str) -> list[tuple[int, int]]:
    """返回每个 `<opener> { ... }` 块的行号区间（1-based, 闭区间）。"""
    spans: list[tuple[int, int]] = []
    for m in re.finditer(opener, src):
        start = m.end() - 1          # 指向 '{'
        depth = 0
        i = start
        n = len(src)
        while i < n:
            c = src[i]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        first_line = src[: m.start()].count("\n") + 1
        last_line = src[:i].count("\n") + 1
        spans.append((first_line, last_line))
    return spans


def find_plugins_blocks(src: str) -> list[tuple[int, int]]:
    """返回每个 plugins { ... } 块的行号区间。"""
    return _block_span(src, r"\bplugins\s*\{")


def find_dependencies_blocks(src: str) -> list[tuple[int, int]]:
    """返回每个 dependencies { ... } 块的行号区间。"""
    return _block_span(src, r"\bdependencies\s*\{")


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def check_file(path: Path) -> list[str]:
    src = path.read_text(encoding="utf-8", errors="replace")
    lines = src.splitlines()
    problems: list[str] = []

    # plugins 块：`id("com.x.y") version "1.2.3"` 形式必须用 alias。
    # 这里最容易漏 —— 守卫只看 dependencies 块时，根 build.gradle.kts
    # 的 7 个插件版本 (AGP / Kotlin / KSP) 全部完好无损地留在外面。
    for first, last in find_plugins_blocks(src):
        for lineno in range(first, last + 1):
            raw = lines[lineno - 1]
            if ALLOW_MARK in raw or raw.strip().startswith("//"):
                continue
            m = PLUGIN_VER.search(raw)
            if m:
                problems.append(
                    f"{rel(path)}:{lineno}: 插件的内联版本 "
                    f'\'{m.group(1)}\' version "{m.group(2)}" → 改用 alias(libs.plugins.<alias>)'
                )

    spans = find_dependencies_blocks(src)
    if not spans:
        return problems
    for first, last in spans:
        for lineno in range(first, last + 1):
            raw = lines[lineno - 1]
            stripped = raw.strip()
            if ALLOW_MARK in raw:
                continue
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            if "project(" in raw:
                continue
            # BOM 本身必须带版本（它就是版本清单），platform()/enforcedPlatform() 豁免。
            if "platform(" in raw:
                continue
            for m in COORD.finditer(raw):
                group, artifact, version = m.groups()
                # 本地 AAR：implementation(group = "", name = "rclone", ext = "aar")
                if not group:
                    continue
                problems.append(
                    f"{rel(path)}:{lineno}: 内联版本 "
                    f'"{group}:{artifact}:{version}" → 改用 libs.<alias>'
                )
    return problems


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()

    problems: list[str] = []
    for p in BUILD_FILES:
        problems.extend(check_file(p))

    if problems:
        print(f"inline version check: FAILED ({len(problems)} 处)")
        for x in problems:
            print(f"  - {x}")
        print()
        print("修法：把坐标加进 gradle/libs.versions.toml 的 [libraries]，")
        print("然后在 build.gradle.kts 里写 implementation(libs.<alias>)。")
        return 1

    print(f"inline version check: OK ({len(BUILD_FILES)} 个 build 文件无内联版本)")
    return 0


def selftest() -> int:
    import tempfile

    cases = [
        (
            "内联版本应被抓出",
            'dependencies {\n    implementation("androidx.core:core-ktx:1.15.0")\n}\n',
            1,
        ),
        (
            "libs 别名应通过",
            "dependencies {\n    implementation(libs.androidx.core.ktx)\n}\n",
            0,
        ),
        (
            "project 依赖应通过",
            'dependencies {\n    implementation(project(":core:model"))\n}\n',
            0,
        ),
        (
            "platform BOM 带版本应通过（BOM 就是版本清单）",
            'dependencies {\n    implementation(platform("androidx.compose:compose-bom:2025.09.00"))\n}\n',
            0,
        ),
        (
            "无版本坐标应通过（BOM 管理）",
            'dependencies {\n    implementation("androidx.compose.material3:material3")\n}\n',
            0,
        ),
        (
            "显式豁免注释应通过",
            'dependencies {\n    implementation("g:a:1.0") // version-catalog-ok: BOM\n}\n',
            0,
        ),
        (
            "注释行应通过",
            'dependencies {\n    // implementation("g:a:1.0")\n}\n',
            0,
        ),
        (
            "插件块的内联版本应被抓出",
            'plugins {\n    id("com.android.application") version "8.7.3" apply false\n}\n',
            1,
        ),
        (
            "插件块用 alias 应通过",
            "plugins {\n    alias(libs.plugins.android.application) apply false\n}\n",
            0,
        ),
        (
            "插件块注释应通过",
            'plugins {\n    // id("com.android.application") version "8.7.3"\n}\n',
            0,
        ),
        (
            "dependencies 块外的版本不算",
            'val x = "g:a:1.0"\ndependencies {\n    implementation(libs.a)\n}\n',
            0,
        ),
    ]
    bad = 0
    for name, src, want in cases:
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "build.gradle.kts"
            p.write_text(src, encoding="utf-8")
            got = len(check_file(p))
        flag = "ok  " if got == want else "FAIL"
        if got != want:
            bad += 1
        print(f"{flag} [{name}] 期望 {want} 实际 {got}")
    print()
    print(f"自测 {len(cases) - bad}/{len(cases)} 通过")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
