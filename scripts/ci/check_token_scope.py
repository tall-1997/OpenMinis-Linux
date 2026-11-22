#!/usr/bin/env python3
"""CI 守卫：令牌层的 CompositionLocal 不能在不可组合的 lambda 里读。

## 为什么需要这条守卫

`MinisTypography.body` / `MinisTheme.colors` / `Minis.dimens` 都是
`staticCompositionLocalOf` 的读取器，标注了 `@Composable @ReadOnlyComposable`。
CompositionLocal 只能在组合期间访问 —— 在下面这些地方读到会抛
`IllegalStateException: ... is not a composable context`：

    remember(x) { MinisTypography.body }        // 缓存 body，第一次算就炸
    derivedStateOf { MinisTheme.colors.accent } // 同上
    LaunchedEffect(Unit) { Minis.dimens.spaceMd }
    produceState { ... }

## 为什么静态检查比编译更早发现问题

Kotlin 编译器**会**报错（@Composable 调用在非 Composable 上下文里是硬错），
所以这不算漏网之鱼。但编译器只在有人真的编译时说话，而这个仓库的行数棘轮
（check_architecture_policy）和令牌生成（check_ui_tokens）都在 CI 里先跑 ——
等到编译阶段报错，前面已经白跑了十分钟，而且错误指向的是 300 行外的一个
lambda 开括号，定位成本极高。

真正难复现的是另一半：`remember{}` 的 lambda 只在**缓存未命中**时执行。
写错了在热重载 / 增量编译下常常"看起来没事"，直到用户冷启动或配置变化
把缓存打穿，才在线上崩一次。静态扫描在这里比运行时反馈更可靠。

## 正确的写法

令牌在 remember 之外读，结果作为 key 传进去：

    val bodySp = MinisTypography.body            // 组合期间读，合法
    val style = remember(bodySp) { TextStyle(fontSize = bodySp) }

值本身变了（用户换字号档位），key 变，缓存重建 —— 语义与"在 lambda 里读"
完全等价，但没有生命周期问题。

用法：
  python3 scripts/ci/check_composition_locals.py [--root <ui 目录>]
  python3 scripts/ci/check_composition_locals.py --selftest
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

DEFAULT_ROOT = Path("src/android/app/src/main/java/com/openminis/app/ui")

# 不可组合的 lambda 开启者。
#
# 判据不是"这是不是一个 lambda"，而是**lambda 是否继承了可组合上下文**：
#
#   inline 函数（forEach / let / run / apply / also / takeIf / with / map）
#     → lambda 被内联展开，@Composable 上下文穿透，读令牌合法。
#       写成千上万个 forEach { Text(fontSize = MinisTypography.cap) } 都没事。
#
#   NON-inline 函数（derivedStateOf / LaunchedEffect / ...）
#     → lambda 是真正的函数对象，不带 @Composable 接收者，读令牌非法。
#
#   inline 但显式标注 @DisallowComposableCalls 的 lambda（remember /
#   rememberSaveable / rememberCoroutineScope）
#     → 内联了也故意掐断可组合上下文，因为缓存的算式不该在每次重组时重跑。
#
# 只列第三类和第二类。第一类明确排除 —— 误报比漏报更能让人关掉守卫。
NON_COMPOSABLE_LAMBDAS: tuple[str, ...] = (
    "remember",
    "rememberSaveable",
    "rememberCoroutineScope",
    "derivedStateOf",
    "LaunchedEffect",
    "DisposableEffect",
    "produceState",
    "snapshotFlow",
    "SideEffect",
    "runBlocking",
)

# 组合期才能读的令牌入口
TOKEN_READERS = re.compile(
    r"(MinisTypography\.\w+|MinisTheme\.colors|Minis\.dimens|LocalMinis\w+\.current)"
)


def mask_source(src: str) -> str:
    """把注释与字符串替换为空白（保留换行与长度），避免误报其中的括号。"""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        if src.startswith("//", i):
            while i < n and src[i] != "\n":
                out[i] = " "
                i += 1
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if src[k] != "\n":
                    out[k] = " "
            i = j
        elif src[i] == '"' or src.startswith('"""', i):
            quote = '"""' if src.startswith('"""', i) else '"'
            j = i + len(quote)
            while j < n:
                if src.startswith(quote, j):
                    j += len(quote)
                    break
                j += 2 if src[j] == "\\" else 1
            for k in range(i, min(j, n)):
                if src[k] != "\n":
                    out[k] = " "
            i = j
        elif src[i] == "'":
            # Kotlin 字符字面量：'{' 或 '}' 里的括号不是语法括号。
            # 漏掉这一支会让 LaunchedEffect 的闭合点被推到下一个 when 分支，
            # 于是分支里合法的组合期读取被误判成在 effect 内（FilePreviewScreen:745）。
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    j += 1
                    break
                if src[j] == "\n":   # 未闭合，别把整份文件吃掉
                    break
                j += 1
            for k in range(i, min(j, n)):
                if src[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def brace_pairs(masked: str) -> dict[int, int]:
    pairs: dict[int, int] = {}
    stack: list[int] = []
    for i, ch in enumerate(masked):
        if ch == "{":
            stack.append(i)
        elif ch == "}" and stack:
            pairs[stack.pop()] = i
    return pairs


def opener_name(masked: str, brace_pos: int) -> str:
    """返回这个 `{` 的开启者名字（`foo(...) {` → foo；`x -> {` → x）。"""
    j = brace_pos - 1
    while j > 0 and masked[j] in " \t\n":
        j -= 1
    if masked[j] == ")":
        depth = 0
        k = j
        while k > 0:
            if masked[k] == ")":
                depth += 1
            elif masked[k] == "(":
                depth -= 1
                if depth == 0:
                    break
            k -= 1
        e = k - 1
        while e > 0 and (masked[e].isalnum() or masked[e] in "._"):
            e -= 1
        return masked[e + 1 : k]
    e = j
    while e > 0 and (masked[e].isalnum() or masked[e] in "._"):
        e -= 1
    return masked[e + 1 : j + 1]


def check_file(path: Path, root: Path) -> list[str]:
    raw = path.read_text(encoding="utf-8", errors="replace")
    if not TOKEN_READERS.search(raw):
        return []
    masked = re.sub(r"^import .*$", "", mask_source(raw), flags=re.M)
    pairs = brace_pairs(masked)
    fails: list[str] = []
    for hit in TOKEN_READERS.finditer(masked):
        pos = hit.start()
        for b, e in pairs.items():
            if not (b < pos < e):
                continue
            name = opener_name(masked, b)
            if not any(name == d or name.endswith("." + d) for d in NON_COMPOSABLE_LAMBDAS):
                continue
            line = masked[:pos].count("\n") + 1
            rel = path.relative_to(root) if root in path.parents else path
            fails.append(
                f"{rel}:{line}: {hit.group(1)} read inside {name}{{}} — "
                f"CompositionLocal 只能在组合期间访问，先读出来当 remember key 传进去"
            )
    return fails


def selftest() -> int:
    import tempfile

    cases = {
        # (源码片段, 是否应报错)
        "remember": (
            """@Composable
fun A() {
    val s = remember { MinisTypography.body }
}
""",
            True,
        ),
        "outside-lambda": (
            """@Composable
fun A() {
    val s = MinisTypography.body
    val t = remember { TextStyle(fontSize = s) }
}
""",
            False,
        ),
        "derivedStateOf": (
            """@Composable
fun A() {
    val c by remember { derivedStateOf { MinisTheme.colors.accent } }
}
""",
            True,
        ),
        "nested-composable": (
            """@Composable
fun A() {
    Column {
        Text("x", fontSize = MinisTypography.code)
    }
}
""",
            False,
        ),
        "string-not-counted": (
            '''fun doc() {
    val s = "remember { MinisTypography.body }"
}
''',
            False,
        ),
        "launched-effect": (
            """@Composable
fun A() {
    LaunchedEffect(Unit) { println(Minis.dimens.spaceMd) }
}
""",
            True,
        ),
        "char-literal-brace": (
            # 字符字面量里的括号不能参与作用域配平。漏了这条，effect 的闭合点
            # 会被推到下一个 when 分支，分支里的合法读取全被误报。
            """@Composable
fun B(x: String) {
    LaunchedEffect(x) { load() }
    val s = when (x.first()) {
        '{' -> MinisTypography.lineCode
        '}' -> MinisTypography.lineCode
        else -> MinisTypography.lineCode
    }
    Text("", lineHeight = s)
}
""",
            False,
        ),
    }
    failed = 0
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        for name, (src, should_fail) in cases.items():
            f = root / f"{name}.kt"
            f.write_text(src, encoding="utf-8")
            got = check_file(f, root)
            ok = bool(got) == should_fail
            print(f"  {'PASS' if ok else 'FAIL'}  {name}: 期望{'报错' if should_fail else '通过'}")
            if not ok:
                failed += 1
                for g in got:
                    print(f"        {g}")
    print(f"\nselftest: {len(cases) - failed}/{len(cases)} passed")
    return 1 if failed else 0


def main() -> int:
    argv = sys.argv[1:]
    if "--selftest" in argv:
        return selftest()
    root = DEFAULT_ROOT
    if "--root" in argv:
        root = Path(argv[argv.index("--root") + 1])
    if not root.exists():
        print(f"composition locals: SKIP ({root} not found)")
        return 0
    files = sorted(root.rglob("*.kt"))
    fails: list[str] = []
    for f in files:
        fails.extend(check_file(f, root))
    if fails:
        print(f"composition locals: {len(fails)} violation(s)\n")
        for f in fails:
            print(f"  - {f}")
        return 1
    print(f"composition locals: OK ({len(files)} files scanned)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
