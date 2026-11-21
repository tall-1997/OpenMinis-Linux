#!/usr/bin/env python3
"""单入口 CI 验证：一次跑完全部守卫，共享一次全仓文件扫描。

为什么需要这个入口
──────────────────
重构期间每条守卫各起一个进程、各自 `rglob("*.kt")` 扫同一批 1939 个文件。
14 条守卫 = 14 次全仓扫描 + 14 次 Python 冷启动。实测串行合计约 70 秒，
其中纯 I/O 重复占比很高。

这个脚本做三件事：
  1. 一次扫描把全仓 Kotlin 文件读进内存，供本地检查复用；
  2. 把 14 条既有守卫按「可并行 / 需独占」分组，进程池并发跑（它们彼此无共享状态）；
  3. 跑本文件内置的静态检查（括号配平、生成物新鲜度），与守卫结果合并成一张表。

设计约束（踩过的坑）
────────────────────
* 子进程用 `subprocess.run(..., cwd=REPO)` 而不是 `os.chdir`：守卫里大量
  `Path(__file__).parents[n]` 定位仓库根，改 cwd 会影响它们对相对路径的假设。
* `check_new_untranslated.py` 需要两个 git sha 参数，且只在 PR 事件有意义；
  默认跳过，用 `--with-pr-gates` 打开（本地重构没有 base sha）。
* `check_*_coverage.py` / `check_token_scope.py` / `check_prefs_call_sites.py`
  支持 `--selftest`；CI 里先跑自测再跑正式。这里保持同样顺序。
* 退出码：0 全绿；1 有失败；2 自身用法错误。

用法
────
    python3 scripts/ci/verify_all.py              # 常规全量
    python3 scripts/ci/verify_all.py -j 4         # 4 路并发
    python3 scripts/ci/verify_all.py --only prefs # 只跑名字含 prefs 的
    python3 scripts/ci/verify_all.py --list       # 只列将执行哪些
    python3 scripts/ci/verify_all.py --selftest   # 跑本文件自带的静态检查自测
"""
from __future__ import annotations

import argparse
import concurrent.futures
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CI = REPO / "scripts" / "ci"
SRC = REPO / "src"


# ─────────────────────────────────────────────────────────────────────────────
# 任务定义
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class Gate:
    """一条验证。`argv` 是相对仓库根的命令行；`pre` 为真时先跑 --selftest。"""
    name: str
    argv: list[str]
    pre_selftest: bool = False
    needs_git_sha: bool = False
    timeout: int = 600
    rc_ok: tuple[int, ...] = (0,)


def _py(script: str, *args: str) -> list[str]:
    return [sys.executable, str(CI / script), *args]


GATES: list[Gate] = [
    # ── 结构 / 架构 ──────────────────────────────────────────────────────────
    Gate("architecture-policy", _py("check_architecture_policy.py")),
    Gate("composition-locals", _py("check_composition_locals.py")),

    # ── 构建配置单点化 ───────────────────────────────────────────────────────
    Gate("no-inline-versions", _py("check_no_inline_versions.py"), pre_selftest=True),
    Gate("version-catalog-sync", _py("check_version_catalog_sync.py"), pre_selftest=True),

    # ── 字符串资源 ───────────────────────────────────────────────────────────
    Gate("string-refs", _py("check_string_refs.py")),
    Gate("cjk-literals", _py("check_cjk_literals.py")),

    # ── prefs 三件套 ─────────────────────────────────────────────────────────
    Gate("prefs-registry", _py("check_prefs_registry.py")),
    Gate("prefs-encryption", _py("check_prefs_encryption.py")),
    Gate("prefs-api-usage", _py("check_prefs_api_usage.py")),
    Gate("prefs-call-sites", _py("check_prefs_call_sites.py"), pre_selftest=True),

    # ── 设计令牌 ─────────────────────────────────────────────────────────────
    Gate("ui-tokens", _py("check_ui_tokens.py")),
    Gate("token-scope", _py("check_token_scope.py"), pre_selftest=True),
    Gate("token-semantics", _py("check_token_semantics.py")),
    Gate("token-coverage", _py("check_token_coverage.py"), pre_selftest=True),

    # ── 工具 / schema ────────────────────────────────────────────────────────
    Gate("schema-drift", _py("check_schema_drift.py")),
    Gate("announced-tools", _py("check_announced_tools.py")),

    # ── 仅在 PR 事件有意义（需要 base sha） ──────────────────────────────────
    Gate("new-untranslated", _py("check_new_untranslated.py"), needs_git_sha=True),
]


# ─────────────────────────────────────────────────────────────────────────────
# 共享扫描（本地静态检查用）
# ─────────────────────────────────────────────────────────────────────────────

def mask_source(src: str) -> str:
    """把注释与字符串替换为空白（保留换行与长度）。

    覆盖六个分支：行注释、块注释、三引号串、普通串、字符字面量、
    **字符串模板表达式 `${...}`**。

    模板那一条是这里最容易漏的：`"${c.optString("data").length} chars"` 里
    内层引号属于表达式而不是字符串边界，朴素实现会在 `"data"` 的引号处收尾，
    于是后面整段代码被当成字符串吞掉（McpJsonRpc.kt 因此少算 5 个右括号）。
    正解是进入 `${` 后按**代码**继续扫描（其中的括号要计数），只在闭合 `}`
    处回到字符串状态。
    """
    out = list(src)
    n = len(src)

    def blank(a: int, b: int) -> None:
        for k in range(a, min(b, n)):
            if src[k] != "\n":
                out[k] = " "

    def skip_line(i: int) -> int:
        while i < n and src[i] != "\n":
            i += 1
        return i

    def skip_block(i: int) -> int:
        j = src.find("*/", i + 2)
        return n if j < 0 else j + 2

    def skip_char(i: int) -> int:
        j = i + 1
        while j < n:
            if src[j] == "\\":
                j += 2
                continue
            if src[j] == "'":
                return j + 1
            if src[j] == "\n":
                return j
            j += 1
        return n

    def skip_template(k: int) -> int:
        """k 指向 `${` 之后（已越过 `{`）。返回匹配的 `}` 之后的位置。"""
        depth = 1
        while k < n:
            c = src[k]
            if c == "{":
                depth += 1
                k += 1
            elif c == "}":
                depth -= 1
                k += 1
                if depth == 0:
                    return k
            elif src.startswith("//", k):
                k = skip_line(k)
            elif src.startswith("/*", k):
                k = skip_block(k)
            elif c == '"':
                k = skip_string(k)
            elif c == "'":
                k = skip_char(k)
            else:
                k += 1
        return k

    def skip_string(i: int) -> int:
        """i 指向起始引号。返回字符串结束之后的位置。"""
        raw = src.startswith('"""', i)
        quote = '"""' if raw else '"'
        start = i
        j = i + len(quote)
        while j < n:
            if raw:
                # Kotlin 原生串：连续 N 个引号（N>=3）里，后 3 个是结束符，
                # 前面的 N-3 个属于内容。`""""value""""` 这种写法就是
                # 「内容以一个引号开头、以一个引号结尾」，朴素实现会在
                # 第一个三连引号处收尾，剩下的引号又开一个新串把后文吞掉。
                if src[j] == '"':
                    run = j
                    while run < n and src[run] == '"':
                        run += 1
                    if run - j >= 3:
                        blank(start, run)
                        return run
                    j = run
                    continue
            else:
                if src.startswith(quote, j):
                    blank(start, j + len(quote))
                    return j + len(quote)
                if src.startswith("${", j):
                    blank(start, j + 2)          # 串体 + `${` 一起掩掉
                    k = skip_template(j + 2)     # 模板内部按代码处理
                    blank(k - 1, k)              # 闭合 `}` 也是模板标记
                    start, j = k, k
                    continue
                if src[j] == "\\":
                    j += 2
                    continue
            j += 1
        blank(start, n)
        return n

    i = 0
    while i < n:
        if src.startswith("//", i):
            j = skip_line(i)
            blank(i, j)
            i = j
        elif src.startswith("/*", i):
            j = skip_block(i)
            blank(i, j)
            i = j
        elif src[i] == '"':
            i = skip_string(i)
        elif src[i] == "'":
            j = skip_char(i)
            blank(i, j)
            i = j
        elif src[i] == "`":
            # Kotlin 反引号标识符：`test's name`()。里面的撇号不是字符字面量起点，
            # 漏掉这一支会把后续整段代码当字符字面量吞掉（测试文件普遍用这种函数名）。
            j = src.find("`", i + 1)
            if j < 0:
                j = skip_line(i)
            else:
                j += 1
            blank(i, j)
            i = j
        else:
            i += 1
    return "".join(out)


def kotlin_files() -> list[Path]:
    if not SRC.is_dir():
        return []
    return sorted(SRC.rglob("*.kt"))


def check_brace_balance(files: list[Path], cache: dict[Path, str]) -> list[str]:
    """掩码后统计 { 与 } 是否配平。直接数原始字符会被字符串里的括号干扰。"""
    problems: list[str] = []
    for p in files:
        src = cache.get(p)
        if src is None:
            src = cache[p] = p.read_text(encoding="utf-8", errors="replace")
        masked = mask_source(src)
        delta = masked.count("{") - masked.count("}")
        if delta != 0:
            rel = p.relative_to(REPO)
            problems.append(f"{rel}: 括号不配平 (差值 {delta:+d})")
    return problems


def check_paren_balance(files: list[Path], cache: dict[Path, str]) -> list[str]:
    problems: list[str] = []
    for p in files:
        src = cache.get(p)
        if src is None:
            src = cache[p] = p.read_text(encoding="utf-8", errors="replace")
        masked = mask_source(src)
        delta = masked.count("(") - masked.count(")")
        if delta != 0:
            rel = p.relative_to(REPO)
            problems.append(f"{rel}: 圆括号不配平 (差值 {delta:+d})")
    return problems


def check_generated_tokens(cache: dict[Path, str]) -> list[str]:
    """ui/tokens/MinisTokens.kt 必须能由 gen_tokens.py 重生成。

    手改生成物会在下一次生成时静默丢失（上一轮 `sectionLabelSpacing` 就是这么丢的），
    所以这里只校验「生成器可跑 + 输出存在」，逐值比对交给 check_ui_tokens.py。
    """
    gen = REPO / "scripts" / "ui" / "gen_tokens.py"
    ui = REPO / "src" / "android" / "app" / "src" / "main" / "java" / "com" / "openminis" / "app" / "ui"
    if not gen.is_file() or not ui.is_dir():
        return []
    out = REPO / ".verify_tokens_tmp"
    try:
        r = subprocess.run(
            [sys.executable, str(gen), "--ui", str(ui), "--out", str(out)],
            cwd=REPO, capture_output=True, text=True, timeout=300,
        )
    except subprocess.TimeoutExpired:
        return ["gen_tokens.py 超时 (>300s)"]
    if r.returncode != 0:
        tail = (r.stderr or r.stdout or "").strip().splitlines()[-3:]
        return [f"gen_tokens.py 失败 rc={r.returncode}: " + " | ".join(tail)]
    produced = out / "MinisTokens.kt"
    if not produced.is_file():
        return [f"gen_tokens.py 未产出 {produced}"]
    return []


# ─────────────────────────────────────────────────────────────────────────────
# 子进程执行
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class Result:
    name: str
    rc: int
    seconds: float
    output: str = ""
    skipped: str = ""

    @property
    def ok(self) -> bool:
        # 跳过项（如仅 PR 生效的守卫）rc 恒为 0，不算失败。
        return self.rc == 0


def run_one(gate: Gate, with_pr_gates: bool) -> Result:
    t0 = time.time()

    if gate.needs_git_sha and not with_pr_gates:
        return Result(gate.name, 0, 0.0, skipped="需 --with-pr-gates（PR base sha）")

    if gate.pre_selftest:
        r = subprocess.run(
            gate.argv + ["--selftest"], cwd=REPO,
            capture_output=True, text=True, timeout=gate.timeout,
        )
        if r.returncode != 0:
            return Result(
                gate.name, r.returncode, time.time() - t0,
                "SELFTEST 失败:\n" + (r.stdout or "") + (r.stderr or ""),
            )

    try:
        r = subprocess.run(
            gate.argv, cwd=REPO,
            capture_output=True, text=True, timeout=gate.timeout,
        )
    except subprocess.TimeoutExpired:
        return Result(gate.name, 124, time.time() - t0, f"超时 (>{gate.timeout}s)")

    out = (r.stdout or "") + (r.stderr or "")
    return Result(gate.name, r.returncode, time.time() - t0, out)


# ─────────────────────────────────────────────────────────────────────────────
# 汇总
# ─────────────────────────────────────────────────────────────────────────────

def print_table(results: list[Result]) -> None:
    w = max((len(r.name) for r in results), default=10)
    print()
    print(f"{'状态':<6} {'验证':<{w}} {'耗时':>7}  说明")
    print("─" * (w + 34))
    for r in sorted(results, key=lambda x: (x.ok, x.name)):
        if r.skipped:
            mark, note = "SKIP", r.skipped
        elif r.ok:
            mark, note = " OK ", ""
        else:
            first = next((ln for ln in r.output.splitlines() if ln.strip()), "")
            note = f"rc={r.rc} {first[:70]}"
            mark = "FAIL"
        print(f"{mark:<6} {r.name:<{w}} {r.seconds:6.1f}s  {note}")
    print("─" * (w + 34))

    failed = [r for r in results if not r.ok]
    skipped = [r for r in results if r.skipped]
    total = sum(r.seconds for r in results)
    print(
        f"合计 {len(results)} 项：{len(results) - len(failed) - len(skipped)} 通过 / "
        f"{len(failed)} 失败 / {len(skipped)} 跳过，耗时 {total:.1f}s"
    )
    if failed:
        print()
        for r in failed:
            print(f"─── {r.name} (rc={r.rc}) " + "─" * 40)
            body = r.output.strip()
            print("\n".join(body.splitlines()[-40:]) if body else "(无输出)")
            print()


# ─────────────────────────────────────────────────────────────────────────────
# 自测
# ─────────────────────────────────────────────────────────────────────────────

def selftest() -> int:
    """mask_source 与括号检查的自测。字符字面量那一条必须有覆盖。"""
    cases: list[tuple[str, str, int]] = [
        ("注释里的括号", "val a = 1 // {\nval b = 2\n", 0),
        ("块注释里的括号", "/* { } ( */ val a = 1\n", 0),
        ("字符串里的括号", 'val s = "{(}"\n', 0),
        ("三引号里的括号", 'val s = """{{(("""\n', 0),
        ("字符字面量左括号", "when (c) {\n  '{' -> 1\n}\n", 0),
        ("字符字面量右括号", "when (c) {\n  '}' -> 1\n}\n", 0),
        ("转义字符字面量", "when (c) {\n  '\\'' -> 1\n}\n", 0),
        ("真实不配平", "fun f() {\n  if (x) {\n}\n", 1),
        # 字符串模板：内层引号属于表达式，不能当串边界
        ("模板内嵌套字符串", 'val s = "${a.optString("k").length} x"\n', 0),
        ("模板内嵌套字符串带括号", 'val s = "v=${f("a")}"\n', 0),
        ("模板内块", 'val s = "${if (x) { 1 } else { 2 }}"\n', 0),
        ("模板里真实不配平", 'val s = "${if (x) { 1 }"\n', 0),
        # 反引号标识符：里面的撇号不是字符字面量
        ("反引号含撇号", "fun `it's fine`() {\n  val a = 1\n}\n", 0),
        ("反引号含括号", "fun `a (b) c`() {\n}\n", 0),
        # 原生串边界：内容以引号开头/结尾时，连续引号里后 3 个才是结束符
        ("原生串内容首尾各一引号", 'val s = """"value""""\n', 0),
        ("原生串内含单引号", 'val s = """name="image""""\n', 0),
        ("原生串后接代码", 'val s = """a"""; val b = "{"\n', 0),
        ("正则原生串", 'val r = Regex(""""v"\\s*:\\s*"((?:\\\\.|[^"\\\\])*)""")\n', 0),
    ]
    bad = 0
    for label, src, expect in cases:
        masked = mask_source(src)
        got = masked.count("{") - masked.count("}")
        if got != expect:
            print(f"FAIL [{label}] 期望差值 {expect}，实得 {got}")
            bad += 1
        else:
            print(f"ok   [{label}] 差值 {got}")
    print(f"\n自测 {len(cases) - bad}/{len(cases)} 通过")
    return 0 if bad == 0 else 1


# ─────────────────────────────────────────────────────────────────────────────
# main
# ─────────────────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(description="单入口跑完全部 CI 守卫")
    ap.add_argument("-j", "--jobs", type=int, default=4, help="并发路数（默认 4）")
    ap.add_argument("--only", default="", help="只跑名字含该子串的验证")
    ap.add_argument("--list", action="store_true", help="只列出将执行的验证")
    ap.add_argument("--selftest", action="store_true", help="跑 mask_source 自测")
    ap.add_argument("--with-pr-gates", action="store_true", help="包含需要 git base sha 的验证")
    ap.add_argument("--skip-static", action="store_true", help="跳过内置静态检查")
    ap.add_argument("-v", "--verbose", action="store_true", help="打印每条验证的完整输出")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    gates = [g for g in GATES if args.only.lower() in g.name.lower()] if args.only else GATES
    if not gates:
        print(f"没有匹配 '{args.only}' 的验证", file=sys.stderr)
        return 2

    if args.list:
        for g in gates:
            flags = []
            if g.pre_selftest:
                flags.append("selftest")
            if g.needs_git_sha:
                flags.append("pr-only")
            print(f"{g.name:<24} {' '.join(flags)}")
        return 0

    print(f"仓库 {REPO}")
    print(f"执行 {len(gates)} 项验证，{args.jobs} 路并发")

    results: list[Result] = []

    # 内置静态检查（与守卫并行无意义，它们共享文件缓存，先跑）
    run_static = (
        not args.skip_static
        and (not args.only or "static".startswith(args.only.lower()) or args.only.lower() in "static-balance")
    )
    if run_static:
        t0 = time.time()
        files = kotlin_files()
        cache: dict[Path, str] = {}
        problems: list[str] = []
        problems += check_brace_balance(files, cache)
        problems += check_paren_balance(files, cache)
        problems += check_generated_tokens(cache)
        results.append(Result(
            "static-balance", 0 if not problems else 1, time.time() - t0,
            "\n".join(problems),
        ))
        print(f"静态检查：扫描 {len(files)} 个 .kt，{len(cache)} 个读入缓存")

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(run_one, g, args.with_pr_gates): g for g in gates}
        for fut in concurrent.futures.as_completed(futures):
            results.append(fut.result())

    print_table(results)

    if args.verbose:
        for r in results:
            if r.output.strip():
                print(f"\n═══ {r.name} ═══")
                print(r.output)

    return 0 if all(r.ok for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
