#!/usr/bin/env python3
"""CI 守卫：每个 `compositionLocalOf` 声明都必须至少有一个 `provides` 点。

为什么需要 —— PR #126 的教训：`LocalDecorPhase` 声明了、Provider 也挂了，
但没有任何消费者读它，共享时钟空转，独立 transition 照跑。那是"假修复"。
这次设计令牌层踩的是同一形态的另一个方向：

    `LocalMinisColors` 声明了，`MinisTheme.colors` 也在读它，
    但全仓没有一处 `LocalMinisColors provides ...`。

后果是 `staticCompositionLocalOf { MinisColors.Light }` 的默认值永远生效：
深色模式下读令牌恒得浅色值，编译通过、运行不报错、只是颜色不对。
这种错误靠肉眼读 diff 抓不住，靠真机点一遍也未必看出来（浅色主题下完全正常）。

所以这里做机械校验：声明 vs 提供点，任一侧为零即失败。

用法：
    python3 scripts/ci/check_composition_locals.py

退出码 0 = 全部有提供点；1 = 有孤立的 Local。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src"

# val LocalFoo = compositionLocalOf { ... }   /  staticCompositionLocalOf<T> { ... }
DECL = re.compile(
    r'\b(?:internal\s+|private\s+|public\s+)?val\s+(Local[A-Za-z0-9_]*)\s*(?::[^=]+)?=\s*'
    r'(?:static)?[Cc]ompositionLocalOf'
)
# (可选全限定包名前缀) LocalFoo provides
#   前缀存在 → 框架/依赖库自带（如 androidx.compose.ui.platform.LocalTextToolbar）
#   无前缀   → 本仓声明
PROVIDES = re.compile(
    r'((?:[a-z_][A-Za-z0-9_]*\.)+)?\b(Local[A-Za-z0-9_]*)\s+provides\b'
)
# import androidx.compose.material3.LocalContentColor 之类的框架自带 Local
IMPORT_LOCAL = re.compile(r'^\s*import\s+[\w.]*\.(Local[A-Za-z0-9_]*)\s*$', re.M)
# 读值：LocalFoo.current（不匹配 `provides` 那一行本身）
CONSUME = re.compile(r'\b(Local[A-Za-z0-9_]*)\.current\b')

# 允许豁免的 Local：只在测试/预览里提供，或由宿主 App 注入。
# 新增豁免必须写明理由 —— 空集合是健康状态。
EXEMPT = {}


def main() -> int:
    if not SRC.exists():
        print("composition locals check: SKIP (src/ not found)")
        return 0

    decls = {}   # name -> 声明所在文件
    provides = {}  # name -> 提供点数量
    imported = set()  # 通过 import 引入的框架自带 Local
    consumers = {}  # name -> 读它的文件集合

    for p in SRC.rglob("*.kt"):
        try:
            s = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        # 不能只看「声明 / provides」就跳过 —— 消费者文件往往两样都没有，
        # 只有 LocalXxx.current。早期版本就是在这里 continue，
        # 导致 consumers 恒为 0（每个 Local 都被误报成死代码）。
        if (
            "ompositionLocalOf" not in s
            and " provides " not in s
            and ".current" not in s
        ):
            continue
        rel = p.relative_to(ROOT).as_posix()
        for m in DECL.finditer(s):
            decls.setdefault(m.group(1), rel)
        for m in PROVIDES.finditer(s):
            qualified, name = m.group(1), m.group(2)
            if qualified:
                # 全限定名 → 一定不是本仓声明（本仓不会用包名限定自己）
                imported.add(name)
                continue
            provides[name] = provides.get(name, 0) + 1
        for m in IMPORT_LOCAL.finditer(s):
            imported.add(m.group(1))
        # 消费者：Local.foo / Local.foo.current（读值）
        for m in CONSUME.finditer(s):
            consumers.setdefault(m.group(1), set()).add(rel)

    orphans = sorted(n for n in decls if provides.get(n, 0) == 0 and n not in EXEMPT)
    # 反向：提供了但本仓没声明 —— 若来自 import 则是框架自带，合法；否则是拼错
    ghosts = sorted(n for n in provides
                    if n not in decls and n not in imported and n not in EXEMPT)

    print(f"CompositionLocal 声明 {len(decls)} 个，提供点合计 {sum(provides.values())} 处")
    if not orphans and not ghosts:
        print("PASS: 每个 Local 都有提供点，每个 provides 都能解析到声明")
        # 只有死代码警告（不影响退出码）—— PR #126 的教训反方向：
        # 提供点齐全但没人读，同样是白做。这条不 fail，因为可能是为将来
        # 的消费者预留；但要让人看见它没人用。
        dead = sorted(n for n in decls if not consumers.get(n))
        if dead:
            print(f"\n提示：{len(dead)} 个 Local 有提供点但当前无人读取（死代码）：")
            for n in dead:
                print(f"  {n}  —— provides {provides.get(n, 0)} 处，consumers 0")
        return 0

    if orphans:
        print(f"\n孤立声明 {len(orphans)} 个（永远取默认值，是静默失效）：")
        for n in orphans:
            print(f"  {n}  —— 声明于 {decls[n]}")
        print("\n修法：在组合根（通常是 MinisTheme / MainActivity）补 "
              "`CompositionLocalProvider(<Local> provides <value>)`。")

    if ghosts:
        print(f"\n无主 provides {len(ghosts)} 处（名字对不上，provides 静默无效）：")
        for n in ghosts:
            print(f"  {n}  —— {provides[n]} 处，但没有同名声明")

    return 1


if __name__ == "__main__":
    sys.exit(main())
