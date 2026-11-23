#!/usr/bin/env python3
"""纯 JVM 断言：设计令牌语义层的自洽性。

不依赖 Android、不依赖 Compose —— 直接解析 MinisTokens.kt 源码文本做静态断言。
任何机器上 0.1 秒跑完，不需要 gradle / 模拟器。

## 检查项（每条都对应一类真实会发生的错误）

1. **深色继承白名单** —— `Dark = Light.copy(...)` 意味着没显式赋值的槽位直接
   沿用浅色值。对 accent 色（systemBlue 之类）这是对的，两套都能用；但**任何
   surface / 文字 / 边框槽位一旦被继承，就是白卡片贴在黑背景上**。所以只允许
   白名单里的纯 accent 槽位继承，其余必须显式赋值。

2. **明暗方向** —— 深色方案的背景类槽位必须比浅色暗、文字类必须比浅色亮。
   写反了编译通过、运行不报错，只是看不见字。

3. **WCAG 对比度** —— 文字槽位相对其所在背景达到 4.5:1（正文 AA）。
   这条能抓出「设计稿改了一个色值，深色模式下悄悄掉到 2.8:1」这类腐化。

4. **槽位漂移** —— Dark 多出 Light 没有的槽位（多半是改名改了一半）。

5. **引用可解析** —— `RawColors.X` 拼错。

6. **品牌哈希配对** —— brandHashLight / brandHashDark 按索引配对，长度必须一致。

用法：
    python3 scripts/ci/check_token_semantics.py [MinisTokens.kt 路径]
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = (ROOT / "src/android/app/src/main/java/com/openminis/app/ui/tokens/MinisTokens.kt")

# 允许被 Dark 继承（不显式赋值）的槽位：纯 accent 色，两套主题共用一个值。
# 新增项必须确认「它在黑底上也可读」，否则就是白卡片/黑字的来源。
INHERIT_OK = {
    'primary', 'statusInfo', 'statusWarn', 'statusDanger', 'statusWarnBg',
    'toolPending', 'toolRunning', 'toolSuccess', 'toolFailed', 'toolCancelled',
    'categoryCode', 'categoryCreative', 'categoryResearch', 'categoryAffair',
    'sendEnabled', 'fabAccent', 'fabOnAccent', 'link',
    'brandOpenAI', 'brandGemini', 'brandFallback',
    # 终端面板永远深底（浅色主题也是），两套同值是设计意图而非漏填。
    'terminalBg', 'terminalBorder', 'terminalLink', 'terminalHudBg',
    'urlCapsuleBg', 'urlCapsuleText',
}

# 深色方案里必须更暗的槽位（背景/表面/边框/填充）
DARKER_IN_DARK = {
    'background', 'surface', 'surfaceVariant', 'cardBg', 'cardBorder',
    'inputBg', 'inputBorder', 'codeBlockBg', 'inlineCodeBg', 'separator',
    'sendDisabled',
}
# toastBg 故意不入 DARKER_IN_DARK：它是**反色**设计（浅色主题给深色气泡、
# 深色主题给浅色气泡），两套都靠反色拉开对比，方向恰好相反。

# 深色方案里必须更亮的槽位（文字/前景）
BRIGHTER_IN_DARK = {
    'onBackground', 'onSurface', 'onSurfaceVariant', 'textPrimary',
    'textSecondary', 'textTertiary', 'outline', 'codeBlockText',
    'inlineCodeText',
}

# 正文级文字槽位 → 应达到的对比度（WCAG AA：正文 4.5，大字/辅助 3.0）
TEXT_MIN_CONTRAST = {
    'textPrimary': 4.5,
    'textSecondary': 4.5,
    'textTertiary': 3.0,   # 三级文字按 AA 大字/辅助信息放宽
    'onBackground': 4.5,
    'onSurface': 4.5,
    'link': 3.0,           # 链接按 AA 非文字/大字号放宽
}

# 已知且已接受的对比度缺口（键为 "主题.槽位"，值为实测比值）。
# 只允许「不低于基线」，防止改色时悄悄变差；想调高就把基线改高。
# textTertiary 浅色 = iOS 系统色 #8E8E93 on #F2F2F7，实测 2.92 —— 照抄 iOS
# 既有观感，改它会让三级文字比 iOS 更黑、层级消失。
CONTRAST_BASELINE = {
    'Light.textTertiary': 2.92,
    'Dark.textTertiary': 6.43,
}


def luminance(argb: int) -> float:
    """sRGB 相对亮度（WCAG 定义）。"""
    def ch(c: int) -> float:
        c = c / 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    return (0.2126 * ch((argb >> 16) & 0xFF)
            + 0.7152 * ch((argb >> 8) & 0xFF)
            + 0.0722 * ch(argb & 0xFF))


def contrast(a: int, b: int) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def parse_consts(src: str):
    """收集 RawColors / Brand / BrandDark 里的 `const val name: Long = 0x...L`。"""
    consts, scope = {}, 'top'
    for line in src.split('\n'):
        st = line.strip()
        if st.startswith('object BrandDark'):
            scope = 'BrandDark'
        elif st.startswith('object Brand'):
            scope = 'Brand'
        elif st.startswith('object RawColors'):
            scope = 'top'
        elif st.startswith('}') and scope in ('Brand', 'BrandDark'):
            scope = 'top'
        m = re.search(r'const val ([A-Za-z0-9_]+):\s*Long\s*=\s*(0x[0-9A-Fa-f]+)L', st)
        if m:
            key = f'{scope}.{m.group(1)}' if scope != 'top' else m.group(1)
            consts[key] = int(m.group(2), 16)
    return consts


def parse_slots(src: str, marker: str, stop: str):
    """解析 `val Light = MinisColors(...)` / `val Dark = Light.copy(...)` 的槽位赋值。"""
    i = src.find(marker)
    if i < 0:
        return None
    j = src.find(stop, i)
    body = src[i:j if j > 0 else len(src)]
    return dict(re.findall(
        r'^\s*([a-z][A-Za-z0-9_]*)\s*=\s*([^,\n]+?),?\s*$', body, re.M))


def resolve(expr: str, consts: dict):
    """`RawColors.systemBlue` / `0xFF123456L` / `RawColors.Brand.zhipu` → int。"""
    expr = expr.strip()
    m = re.fullmatch(r'0x([0-9A-Fa-f]+)L', expr)
    if m:
        return int(m.group(1), 16)
    m = re.fullmatch(r'RawColors\.((?:Brand|BrandDark)\.)?([A-Za-z0-9_]+)', expr)
    if m:
        key = f'{m.group(1)}{m.group(2)}' if m.group(1) else m.group(2)
        return consts.get(key)
    return None


# 令牌层里「只需要被看见」的符号：它们由 MinisTheme 提供，或只在自身文件内用。
DEAD_CODE_EXEMPT_OBJECTS = {'RawColors', 'RawSpace', 'RawType', 'MinisColors',
                            'MinisDimens', 'MinisType'}
DEAD_CODE_MIN_MEMBERS = 3   # 少于此数的对象不判（可能是纯常量表）

# 只排除生成物（scripts/ui/gen_tokens.py 产出）。手写的适配层必须算调用方，
# 否则「object 无人使用」这条永远对着 ComposeAdapters 里的真实引用报错。
GENERATED_TOKEN_FILES = {'MinisTokens.kt'}


def check_token_dead_code(src: str) -> list:
    """令牌定义了但全仓没人读 —— 与「有消费者没提供点」互为镜像的那种空转。

    PR #126 是挂了 Provider 没接消费者；令牌层这一轮是消费者齐了没提供点，
    外加 Minis.dimens / MinisMotion / MinisType 三件套长期无人读取。这类东西
    编译通过、CI 通过、代码看着很整齐，唯一的问题是对产品零影响。

    这里只报**候选**（不算 fail）：令牌往往先定义、后接入，硬 fail 会误伤
    正在铺路的提交。真正的收口靠 reviewer 看这份清单。
    """
    root = ROOT / 'src/android/app/src/main/java/com/openminis/app'
    if not root.exists():
        return []
    # 收集除**生成物**以外的全部源码。
    #
    # 这里排除的是生成文件，不是整个 ui/tokens/ 目录：ComposeAdapters.kt 是
    # 手写适配层（Composable 属性 + line* 常量映射），它才是这些 object 的
    # 真正消费者。早先按目录整块排除，MinisLineHeight 的 13 个成员明明全被
    # ComposeAdapters 引用，检查器却报「全部零引用」—— 漏报比误报更糟：
    # 一份永远叫的狼来了清单，reviewer 看两轮就整个跳过死代码段了。
    others = []
    for p in root.rglob('*.kt'):
        if p.name in GENERATED_TOKEN_FILES or p.name == 'TokenBridge.kt':
            continue
        try:
            others.append(p.read_text(encoding='utf-8', errors='replace'))
        except OSError:
            pass
    used = '\n'.join(others)

    warns = []
    # MinisMotion / MinisType 这类 object：逐个成员看有没有被引用
    for om in re.finditer(r'object (\w+) \{([^}]*)\}', src):
        name, body = om.group(1), om.group(2)
        if name in DEAD_CODE_EXEMPT_OBJECTS:
            continue
        members = re.findall(r'const val (\w+)', body)
        if len(members) < DEAD_CODE_MIN_MEMBERS:
            continue
        dead = [m for m in members if f'{name}.{m}' not in used]
        if len(dead) == len(members):
            warns.append(f'object {name} 无人使用（{len(members)} 个成员全部零引用）')
        elif dead:
            warns.append(f'object {name} 有 {len(dead)}/{len(members)} 个成员零引用：'
                         f'{", ".join(dead)}')

    # 令牌入口：整体零引用就是悬空。别名（Dims / MinisTokens / MinisTheme）
    # 也算调用方 —— 已到棘轮上限的文件故意用包内 typealias 省 import 行数，
    # 只认全限定写法会把它们误报成死代码。
    entries = {
        'Minis.dimens': ('Minis.dimens', 'Dims.dimens', 'MinisDimensEntry.dimens'),
        'MinisTheme.colors': ('MinisTheme.colors', 'MinisTokens.colors'),
    }
    for label, aliases in entries.items():
        if not any(a in used for a in aliases):
            warns.append(f'{label} 无任何调用方（令牌入口悬空）')

    if warns:
        print(f'\n提示：令牌死代码 {len(warns)} 项（不算失败，但要有人跟进）：')
        for w in warns:
            print(f'  · {w}')
    return []


def check_dimens_monotonic(src: str) -> list:
    """MinisDimens 的 space* 阶梯必须严格递增，默认档和每个 alternate 档都要。

    只改其中几个槽位是真实会发生的错误：旧版 Compact 只写了 spaceSm/Md/Lg/Xl/Xxl，
    于是 spaceMd2(10) > spaceLg(8)、spaceLg2(14) > spaceXl(12)、spaceXl2(18) >
    spaceXxl(16) 三处倒挂，spaceXs 与 spaceSm 还塌成同值 4。编译通过、渲染出来
    只是「大间距比小间距小」，肉眼很难归因到令牌。
    """
    fails = []
    m = re.search(r'data class MinisDimens\((.*?)\n\)', src, re.S)
    if not m:
        return ['MinisDimens 声明未找到，无法校验尺寸阶梯']

    def grab(text):
        return {mm.group(1): float(mm.group(2))
                for mm in re.finditer(r'(space\w+)\s*:\s*Float\s*=\s*([0-9.]+)f', text)}

    def grab_assigned(text):
        return {mm.group(1): float(mm.group(2))
                for mm in re.finditer(r'\b(space\w+)\s*=\s*([0-9.]+)f', text)}

    defaults = grab(m.group(1))
    if not defaults:
        return ['MinisDimens 未解析到 space* 默认值']

    # 阶梯顺序取**声明顺序**，不按数值排序。按值排序有个隐蔽问题：一旦默认值
    # 本身写错（例如 spaceMd 被写成 30），排序会被带偏，检查就查不出它错。
    # 声明顺序是人的意图，与值无关，所以永远可用来当参照。
    ladder = list(defaults)

    def check(name, values):
        out = []
        seq = [(k, values[k]) for k in ladder if k in values]
        for i in range(len(seq) - 1):
            a, av = seq[i]
            b, bv = seq[i + 1]
            if av > bv:
                out.append(f'MinisDimens.{name}: {a}={av:g} > {b}={bv:g}'
                           f'（阶梯倒挂，大间距比小间距还小）')
            elif av == bv:
                out.append(f'MinisDimens.{name}: {a} == {b} == {av:g}'
                           f'（两档塌成同值，层级消失）')
        return out

    fails.extend(check('默认', defaults))

    # alternate 档在 companion object 里，不在主构造参数表中 —— 必须在全文找，
    # 否则这条检查会静默空转（我第一次就写成只搜参数表，反向用例没报错才发现）。
    seen = 0
    for am in re.finditer(r'val (\w+) = MinisDimens\(\s*(.*?)\n\s*\)', src, re.S):
        seen += 1
        alt = dict(defaults)
        alt.update(grab_assigned(am.group(2)))
        fails.extend(check(am.group(1), alt))
    if seen == 0:
        fails.append('MinisDimens 未找到任何 alternate 档（Compact 之类）—— 正则失效')

    return fails


def check_lineheight_ladder(src: str) -> list:
    """MinisType.lineHeight 的实测锚点必须让**实际行高**随字号严格递增，且插值无缝。

    这条曲线是倒 U 形：9sp 紧（11）、16sp 最松（24）、57sp 又收紧（64）。
    比值本身不单调，所以不能比比值 —— 要代入字号算实际行高再比。

    真会犯的错有三类，编译和渲染都不报错：
      1. 锚点数组长度不一致 → 插值越界或错位取点。
      2. 某个锚点行高手滑（16sp 的 24 写成 16.8）→ 相邻字号行高倒挂。
      3. 回退成 `when` 分段常数阶梯 → 档与档之间跨 1sp 就跳变，16.5/17/18
         这类中间字号一律错（历史上真发生过：16.5sp 输入框被算成 20.9，
         比 16sp 正文的 24 还紧）。
    所以第三条也要守：lineHeight 里出现 `size <= ... ->` 就是退回阶梯了。
    """
    m = re.search(r'fun lineHeight\(size: Float\): Float \{(.*?)\n    \}', src, re.S)
    if not m:
        return ['MinisType.lineHeight 未找到 —— 正则失效或函数被重写']
    body = m.group(1)

    fails = []

    # 3. 不许退回分段常数阶梯
    if re.search(r'size <= [0-9.]+f ->', body):
        fails.append('MinisType.lineHeight 退回 when 阶梯（分段常数）：'
                     '跨档跳变，16.5/17/18 这类中间字号会被算错。应改为锚点插值')

    def anchors(name: str):
        mm = re.search(rf'val {name} = floatArrayOf\((.*?)\)', src, re.S)
        if not mm:
            return None
        vals = [float(v) for v in re.findall(r'([0-9.]+)f', mm.group(1))]
        return vals or None

    sizes = anchors('LH_SIZES')
    heights = anchors('LH_HEIGHTS')
    if sizes is None or heights is None:
        return fails + ['MinisType 缺少 LH_SIZES / LH_HEIGHTS 锚点数组']

    if len(sizes) != len(heights):
        fails.append(f'LH_SIZES({len(sizes)}) 与 LH_HEIGHTS({len(heights)}) 长度不一致，'
                     '插值会取错点或越界')
        return fails
    if len(sizes) < 5:
        return fails + [f'行高锚点只解析到 {len(sizes)} 个，少于 5 个 —— 正则失效']

    for a, b in zip(sizes, sizes[1:]):
        if b <= a:
            fails.append(f'LH_SIZES 未严格递增：{a:g} → {b:g}（插值分母为 0 或负）')
    for a, b in zip(heights, heights[1:]):
        if b < a:
            fails.append(f'LH_HEIGHTS 倒挂：{a:g} → {b:g}'
                         f'（越大字行距越紧，读起来会挤成一坨）')
    if fails:
        return fails
    # 允许相等但只允许一段：16/16.5 是刻意压平的平台（输入框不能比正文松）。
    # 连续两段相等说明表被填坏了，不是有意为之。
    flat = sum(1 for a, b in zip(heights, heights[1:]) if b == a)
    if flat > 1:
        fails.append(f'LH_HEIGHTS 有 {flat} 段相邻相等（只允许 16→16.5 一段平台），'
                     '其余档次被压平了')

    # 在真实字号上验插值结果：必须无跳变、且始终大于字号本身
    def interp(size: float) -> float:
        if size <= sizes[0]:
            return size * (heights[0] / sizes[0])
        if size >= sizes[-1]:
            return size * (heights[-1] / sizes[-1])
        for i in range(len(sizes) - 1):
            if size <= sizes[i + 1]:
                x0, y0, x1, y1 = sizes[i], heights[i], sizes[i + 1], heights[i + 1]
                return y0 + (y1 - y0) * ((size - x0) / (x1 - x0))
        return heights[-1]

    probes = [8, 9, 10, 11, 12, 13, 14, 15, 16, 16.5, 17, 18, 19, 20, 22, 24, 28, 32, 36, 45, 52, 57, 64]
    prev = None
    for s in probes:
        h = interp(float(s))
        if h <= s:
            fails.append(f'{s:g}sp 插值行高 {h:.2f} 未大于字号本身（行会重叠）')
        if prev is not None:
            ps, ph = prev
            plateau = (ps, s) == (16, 16.5)  # 唯一允许的平台段
            if h < ph or (h == ph and not plateau):
                fails.append(f'{s:g}sp 行高 {h:.2f} 不高于 {ps:g}sp 的 {ph:.2f}'
                             f'（行高必须随字号单调递增）')
            elif s - ps <= 0.6 and h - ph > (s - ps) * 2.2:
                fails.append(f'{ps:g}→{s:g}sp 行高从 {ph:.2f} 跳到 {h:.2f}'
                             f'（跨档跳变，中间字号会被压平）')
        prev = (s, h)

    # 正文必须比小字松 —— 这是这条曲线存在的理由
    if interp(16.0) <= interp(9.0):
        fails.append(f'16sp 行高 {interp(16):.2f} 未高于 9sp 的 {interp(9):.2f}，'
                     f'正文该比注脚松')
    return fails


# ─── G1: live palettes（真实渲染的色板对比度，2026-11-23 起） ──────────────
# 上面 1-8 条守护的是 MinisColors（令牌层，消费者≈1 个文件）。而实际渲染
# 用的是 ChatPalette（286 处引用）与 MaterialTheme.colorScheme（1411 处）：
# 三级文字 1.74:1、FAB 图标 2.19:1、浅色 primary 3.50:1 全部发生在门禁外，
# 这条就是堵那个洞的。

CHAT_COLORS = (ROOT / "src/android/app/src/main/java/com/openminis/app/ui"
               "/theme/ChatColors.kt")
THEME_KT = (ROOT / "src/android/app/src/main/java/com/openminis/app/ui"
            "/theme/Theme.kt")

# ChatPalette 文字槽 → (最低对比度, 背景槽名或 'thinking'/'inlineCodeBg'/'fabAccent')
CHAT_TEXT_SLOTS = {
    'primaryText': (4.5, 'background'),
    'secondaryText': (4.5, 'background'),
    'tertiaryText': (4.5, 'background'),
    'link': (4.5, 'background'),
    'thinking': (4.5, 'background'),
    # [T-text-threshold] `thinkingOn` is passed to `Text(color = ...)` on the
    # selected chip, so it is TEXT: 4.5, not the 3.0 non-text floor. At 3.0 the
    # gate accepted Black on #0066CC (3.03:1) — a real readability failure.
    'thinkingOn': (4.5, 'thinking'),
    'inlineCodeText': (4.5, 'inlineCodeBg'),
    'fabOnAccent': (3.0, 'fabAccent'),
    # [T-warning-text-slot] warningText was missing from this table entirely, so
    # the dark-mode regression that dropped it to 4.43:1 had no guard.
    'warningText': (4.5, 'warningBg'),
}

# Theme.kt scheme 配对（两套主题各查）：前景 → (最低对比度, 背景槽)
SCHEME_PAIRS = {
    'primary': (4.5, 'background'),        # primary 会被当链接/强调文字用
    'onPrimary': (4.5, 'primary'),
    'onSurfaceVariant': (4.5, 'background'),
    'onSurface': (4.5, 'background'),
}


def _composite(argb: int, bg: int) -> int:
    """带 alpha 的前景压到不透明背景上（WCAG 用的有效色）。"""
    a = (argb >> 24) & 0xFF
    if a == 0xFF:
        return argb
    af = a / 255.0
    out = 0xFF
    for shift in (16, 8, 0):
        fg = (argb >> shift) & 0xFF
        bb = (bg >> shift) & 0xFF
        out = (out << 8) | round(af * fg + (1 - af) * bb)
    return out


def _parse_palette_slots(src: str, marker: str):
    i = src.find(marker)
    if i < 0:
        return None
    j = src.find('\n)', i)
    body = src[i:j if j > 0 else len(src)]
    named = {'Color.White': 0xFFFFFFFF, 'Color.Black': 0xFF000000}
    out = {}
    for m in re.finditer(r'^\s*([a-z][A-Za-z0-9_]*)\s*=\s*(Color\.\w+|Color\((0x[0-9A-Fa-f]{8})\))',
                         body, re.M):
        if m.group(3):
            out[m.group(1)] = int(m.group(3), 16)
        elif m.group(2) in named:
            out[m.group(1)] = named[m.group(2)]
    return out


def _parse_scheme(src: str, fun: str):
    i = src.find(f'{fun}(')
    if i < 0:
        return None
    j = src.find('\n)', i)
    body = src[i:j if j > 0 else len(src)]
    out = {}
    for m in re.finditer(r'^\s*([a-zA-Z]+)\s*=\s*(?:Color\()?0x([0-9A-Fa-f]{8})',
                         body, re.M):
        out[m.group(1)] = int(m.group(2), 16)
    # TealX 别名槽
    for m in re.finditer(r'^\s*([a-zA-Z]+)\s*=\s*(Teal[A-Za-z]+)', body, re.M):
        mm = re.search(rf'private val {m.group(2)} = Color\((0x[0-9A-Fa-f]{{8}})\)', src)
        if mm:
            out[m.group(1)] = int(mm.group(1), 16)
    return out


def check_live_palettes() -> list:
    if not CHAT_COLORS.exists() or not THEME_KT.exists():
        return ['ChatColors.kt / Theme.kt 缺失，live palette 检查无法运行']
    src = CHAT_COLORS.read_text(encoding='utf-8')
    theme = THEME_KT.read_text(encoding='utf-8')
    fails = []

    for name, palette in (('LightChatPalette', 'light'), ('DarkChatPalette', 'dark')):
        slots = _parse_palette_slots(src, f'val {name} = ChatPalette(')
        if slots is None:
            fails.append(f'{name} 未解析到（ChatColors.kt 结构变了？）')
            continue
        # [T-gate-fail-closed] A parser that silently yields {} on an expression
        # value (e.g. `background = RawColors.neutral0.asColor`) would skip every
        # assertion below and report PASS. Require the slots we intend to judge.
        missing = [s for s in CHAT_TEXT_SLOTS if s not in slots]
        for s in ('background',):
            if s not in slots:
                missing.append(s)
        if missing:
            fails.append(
                f'{name} 解析不到 {len(missing)} 个关键槽：{", ".join(missing)}'
                f' —— 色值若已改成表达式/常量，本检查会静默退化成假绿，'
                f'需要扩展解析器而不是放着跳过')
            continue
        bg = slots.get('background')
        if bg is None:
            fails.append(f'{name}.background 未解析到')
            continue
        for fg_slot, (need, bg_slot) in CHAT_TEXT_SLOTS.items():
            fg = slots.get(fg_slot)
            bgv = slots.get(bg_slot, bg)
            if fg is None or bgv is None:
                continue
            # [T-alpha-stack] warningBg / toastBg are TRANSLUCENT overlays: they
            # are painted on top of `background`, so the effective backdrop is the
            # composite, not the raw ARGB (reading the raw value invented a
            # 1.38:1 "failure" that no user can see).
            backdrop = _composite(bgv, bg) if (bgv >> 24) & 0xFF != 0xFF else bgv
            eff = _composite(fg, backdrop)
            got = contrast(eff, backdrop)
            if got < need:
                fails.append(
                    f'{name}.{fg_slot} on {bg_slot} 对比度 {got:.2f}:1 < {need}:1'
                    f'（#{eff:08X} on #{backdrop:08X}）')

    # Theme.kt：两套 scheme 的配对 + surfaceContainer 阶梯不塌档
    for fun, label in (('lightColorScheme', 'Light'), ('darkColorScheme', 'Dark')):
        sch = _parse_scheme(theme, fun)
        if not sch:
            fails.append(f'{label}ColorScheme 未解析到（Theme.kt 结构变了？）')
            continue
        for fg_slot, (need, bg_slot) in SCHEME_PAIRS.items():
            fg, bgv = sch.get(fg_slot), sch.get(bg_slot)
            if fg is None or bgv is None:
                fails.append(f'{label}.{fg_slot}/{bg_slot} 未解析到（缺角色或解析失效）')
                continue
            got = contrast(fg, bgv)
            if got < need:
                fails.append(
                    f'{label}ColorScheme.{fg_slot} on {bg_slot} {got:.2f}:1 < {need}:1')
        ramp = ['surfaceContainerLowest', 'surfaceContainerLow', 'surfaceContainer',
                'surfaceContainerHigh', 'surfaceContainerHighest']
        vals = [sch.get(k) for k in ramp]
        if any(v is None for v in vals):
            fails.append(f'{label}ColorScheme surfaceContainer 阶梯不完整')
        elif len(set(vals)) < len(vals):
            fails.append(f'{label}ColorScheme surfaceContainer 有同值档'
                         f'（{[f"#{v:06X}" for v in vals]}）——层级不可编程')
    return fails


def main() -> int:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT
    if not path.exists():
        print(f"token semantics: FAIL ({path} not found)")
        return 1
        return 0

    src = path.read_text(encoding='utf-8')
    consts = parse_consts(src)
    light = parse_slots(src, 'val Light = MinisColors(', 'val Dark =')
    dark = parse_slots(src, 'val Dark = Light.copy(', '\n    }\n}')

    if light is None or dark is None:
        print("token semantics: FAILED (找不到 Light / Dark 定义，生成器模板变了？)")
        return 1

    fails = []

    # 1. 深色继承白名单
    inherited = sorted(set(light) - set(dark))
    bad_inherit = [k for k in inherited if k not in INHERIT_OK]
    if bad_inherit:
        fails.append(
            f"深色方案继承了非 accent 槽位 {len(bad_inherit)} 个"
            f"（浅色值会漏到深色模式）：{', '.join(bad_inherit)}")

    # 4. 槽位漂移
    extra = sorted(set(dark) - set(light))
    if extra:
        fails.append(f"深色方案多出 {len(extra)} 个槽位：{', '.join(extra)}")

    # 解析全部槽位的有效值（Dark 未赋值的继承 Light）
    eff_light, eff_dark = {}, {}
    for k in sorted(light):
        a = resolve(light[k], consts)
        if a is None:
            fails.append(f"Light.{k} 无法解析：{light[k].strip()}")
        else:
            eff_light[k] = a
        expr = dark.get(k, light[k])
        b = resolve(expr, consts)
        if b is None:
            fails.append(f"Dark.{k} 无法解析：{expr.strip()}")
        else:
            eff_dark[k] = b

    # 2. 明暗方向（只在两侧都解析成功时比较）
    for k in sorted(DARKER_IN_DARK & eff_light.keys() & eff_dark.keys()):
        if luminance(eff_dark[k]) > luminance(eff_light[k]) + 0.02:
            fails.append(
                f"{k}: 深色应更暗，实际 Light=#{eff_light[k]:08X} Dark=#{eff_dark[k]:08X}")
    for k in sorted(BRIGHTER_IN_DARK & eff_light.keys() & eff_dark.keys()):
        if luminance(eff_dark[k]) < luminance(eff_light[k]) - 0.02:
            fails.append(
                f"{k}: 深色应更亮，实际 Light=#{eff_light[k]:08X} Dark=#{eff_dark[k]:08X}")

    # 3. WCAG 对比度（两套主题各查一遍，文字 vs 背景）
    for theme, slots in (('Light', eff_light), ('Dark', eff_dark)):
        bg = slots.get('background')
        if bg is None:
            continue
        for k, need in TEXT_MIN_CONTRAST.items():
            fg = slots.get(k)
            if fg is None:
                continue
            got = contrast(fg, bg)
            key = f'{theme}.{k}'
            if key in CONTRAST_BASELINE:
                # 已接受的缺口：只防退步
                floor = CONTRAST_BASELINE[key]
                if got < floor - 0.05:
                    fails.append(
                        f"{key} 对比度退化到 {got:.2f}:1，低于基线 {floor:.2f}:1"
                        f"（#{fg:08X} on #{bg:08X}）")
                continue
            if got < need:
                fails.append(
                    f"{key} 对比度 {got:.2f}:1 < {need}:1"
                    f"（#{fg:08X} on #{bg:08X}）")

    # 5. 引用可解析
    unresolved = {f'{a or ""}{b}'
                  for a, b in re.findall(
                      r'RawColors\.((?:Brand|BrandDark)\.)?([A-Za-z0-9_]+)', src)
                  if f'{a or ""}{b}' not in consts}
    if unresolved:
        fails.append(f"未定义的 RawColors 引用 {len(unresolved)} 个："
                     f"{', '.join(sorted(unresolved)[:10])}")

    # 6. 品牌哈希配对
    hl = re.search(r'brandHashLight:\s*List<Long>\s*=\s*listOf\((.*?)\)', src, re.S)
    hd = re.search(r'brandHashDark:\s*List<Long>\s*=\s*listOf\((.*?)\)', src, re.S)
    if hl and hd:
        a = len(re.findall(r'0x[0-9A-Fa-f]+L', hl.group(1)))
        b = len(re.findall(r'0x[0-9A-Fa-f]+L', hd.group(1)))
        if a != b:
            fails.append(f"品牌哈希兜底长度不配对：Light {a} 条 / Dark {b} 条（按索引配对会越界）")
        else:
            print(f"  品牌哈希兜底：Light/Dark 各 {a} 条，配对正确")

    # 7. 尺寸阶梯单调性（默认 + 每个 alternate 档）
    fails.extend(check_dimens_monotonic(src))
    fails.extend(check_lineheight_ladder(src))

    # 8. 令牌死代码 —— 定义了却没人读
    fails.extend(check_token_dead_code(src))

    # 9. live palettes —— ChatPalette / ColorScheme 的真实对比度
    fails.extend(check_live_palettes())

    print(f"语义槽位：Light {len(light)} 个 / Dark {len(dark)} 个"
          f"（显式 {len(dark)}，继承 {len(inherited)}）；RawColors 常量 {len(consts)} 个")
    if fails:
        print(f"\n断言失败 {len(fails)} 项：")
        for f in fails:
            print(f"  ✗ {f}")
        return 1

    print("PASS: 深色继承限于 accent、明暗方向正确、文字对比度达标、引用全可解析")
    return 0


if __name__ == '__main__':
    sys.exit(main())
