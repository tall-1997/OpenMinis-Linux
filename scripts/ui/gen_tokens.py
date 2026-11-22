#!/usr/bin/env python3
"""
从源码实测数据生成设计令牌的数据层（纯 JVM，无 Compose 依赖）。

为什么把令牌做成"纯数据 + 适配器"两层：
  Compose 的 Color/Dp 是 UI 类型，一旦令牌定义里出现它们，令牌层就无法在
  纯 JVM 上单测。所以这里只产出 ARGB Long 与 dp 的 Float，Compose 侧由
  ui/tokens/ComposeAdapters.kt 用扩展属性转成 Color/Dp。

关键设计：命名常量**无条件全量输出**，不按"当前是否还有源文件在用"裁剪。
  早期版本按出现次数裁剪，结果第一批文件迁移完成后该色值从源码里消失，
  令牌跟着消失，已迁移的引用立刻 unresolved —— 生成器把使用者自己删掉了。
  频率只用来排序和写注释，不用来决定是否存在。

用法：
  python3 tools/gen_tokens.py --ui <.../app/ui> --out gen
产出：
  gen/MinisTokens.kt        令牌对象（RawColors / RawSpace / MinisColors / MinisDimens / MinisType）
  gen/token_data.json       扫描事实（供文档与校验器复用）
"""
import argparse
import json
import os
import re
from collections import Counter

RE_HEX = re.compile(r'Color\(0x([0-9A-Fa-f]{8})\)')
RE_DP = re.compile(r'(?<![\w.])(\d+(?:\.\d+)?)\.dp\b')
RE_SP = re.compile(r'(?<![\w.])(\d+(?:\.\d+)?)\.sp\b')
# 注释里的字面量不算使用点：`// 原来是 16.sp` 不改渲染，计入会让棘轮的压力
# 来自空气。统计口径必须和一线的痛感一致，否则预算会被注释慢慢吃掉。
RE_COMMENT = re.compile(r'(?://|/\*).*$')

# iOS HIG 系统色。这张表是**契约**，不是统计结果：名字一旦发布就不能删。
IOS_NAMES = {
    'FF34C759': 'systemGreen',
    'FFFF9500': 'systemOrange',
    'FF007AFF': 'systemBlue',
    'FFFF3B30': 'systemRed',
    'FFFF2D55': 'systemPink',
    'FF5856D6': 'systemPurple',
    'FF8E8E93': 'systemGray',
    'FF32ADE6': 'systemTeal',
    'FF64D2FF': 'systemCyan',
    'FFFFCC00': 'systemYellow',
    'FFAF52DE': 'systemPurpleAlt',
    'FF30D158': 'systemGreenAlt',
    'FF30B0C7': 'systemTealAlt',
    'FFB8B096': 'sparkleWarm',
    'FF99998C': 'sparkleStone',
}
NEUTRAL_NAMES = {
    'FFFFFFFF': 'neutral0',
    'FFF2F2F7': 'neutral50',
    'FFE2E2E6': 'neutral100',
    'FFC4C4C8': 'neutral200',
    'FF8E8E92': 'neutral400',
    'FF747477': 'neutral500',
    'FF444446': 'neutral600',
    'FF1C1C1E': 'neutral800',
    'FF16161A': 'neutral900',
    'FF000000': 'neutral1000',
}
# dp 命名：按实测频率赋名，保证高频值拿到短名字
SPACE_ORDER = ['2', '4', '6', '8', '10', '12', '14', '16',
               '18', '20', '22', '24', '28', '32', '40', '48', '56']
SPACE_NAMES = {
    '2': 'xxs', '4': 'xs', '6': 'sm', '8': 'md', '10': 'md2', '12': 'lg',
    '14': 'lg2', '16': 'xl', '18': 'xl2', '20': 'xxl', '22': 'xxl2',
    '24': 'xxl3', '28': 'xxl4', '32': 'huge', '40': 'huge2', '48': 'giant',
    '56': 'giant2',
}
STROKE = {'0.5': 'hairline', '1': 'thin', '2': 'thick'}
SP_NAMES = {'9': 'cap2', '11': 'cap', '12': 'foot', '13': 'body',
            '14': 'bodyLg', '15': 'title', '16': 'titleLg', '18': 'head'}

# 圆角令牌。原版只有 Sm/Md/Lg 三档（8/12/16），但全仓 RoundedCornerShape
# 实测 211 处里有 114 处（55%）落在三档之外 —— 10dp 用了 44 次却没有令牌。
# 令牌若不能表达真实设计，它就不是令牌而是装饰；这里按实际频次补齐。
RADIUS_ORDER = ['2', '4', '6', '8', '10', '12', '14', '16', '18', '20', '24', '28', '32']
RADIUS_NAMES = {
    '2': 'radiusXs', '4': 'radiusXs2', '6': 'radiusSm', '8': 'radiusSm2',
    '10': 'radiusMd', '12': 'radiusMd2', '14': 'radiusLg', '16': 'radiusLg2',
    '18': 'radiusXl', '20': 'radiusXl2', '24': 'radiusXxl', '28': 'radiusHuge',
    '32': 'radiusHuge2',
}

HERE = os.path.dirname(os.path.abspath(__file__))


def load_brand():
    """品牌色表：20 家厂商主色 + 18 条深色覆盖 + 各 6 条哈希兜底。"""
    p = os.path.join(HERE, 'brand_palette.json')
    if not os.path.exists(p):
        raise SystemExit('缺少 tools/brand_palette.json（由 BrandAccent.kt 抽取）')
    return json.load(open(p, encoding='utf-8'))


def strip_comments(src):
    """返回「只有代码」的文本：注释整段删除，字符串内容置空（保留引号占位）。

    为什么字符串也要置空：`Text("16.sp")` 里的 16.sp 是给人看的文案，不是排版
    参数。计入预算会让改一句文案触发排版告警，预算就失去可信度。
    `//` 只在前面是空白或行首时才算注释 —— 真实代码里没有 `x//y` 这种写法，
    所以 `"https://..."` 不会被误切（而且它在字符串里，本来就被置空了）。
    """
    out = []
    i, n = 0, len(src)
    in_str = False
    while i < n:
        c = src[i]
        if in_str:
            if c == '\\' and i + 1 < n:
                i += 2; continue
            if c == '"':
                in_str = False
                out.append('"')
            i += 1
            continue
        if c == '"':
            in_str = True; out.append(c); i += 1; continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            # 关键：块注释里的换行必须补回来。否则去注释文本的行号与原文错位，
            # 所有"按行"的定位（豁免标记、诊断输出）都会指到错误的位置。
            out.append('\n' * src.count('\n', i, j))
            i = j
            continue
        if src.startswith('//', i) and (i == 0 or src[i - 1].isspace()):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        out.append(c); i += 1
    return ''.join(out)


EXEMPT = re.compile(r'token-literal-ok\s*:\s*(\S.*)?$')


def scan(ui_root):
    """聚合字面量。带 token-literal-ok 的行按豁免处理，不计入。

    豁免语义必须和 check_token_coverage.py 完全一致，否则生成器的统计头
    与棘轮预算对不上，看的人会以为其中一个坏了。
    """
    colors, dps, sps, files = Counter(), Counter(), Counter(), 0
    for dirpath, _, names in os.walk(ui_root):
        for n in names:
            if not n.endswith('.kt'):
                continue
            files += 1
            raw = open(os.path.join(dirpath, n), encoding='utf-8', errors='replace').read()
            s = strip_comments(raw)
            if 'token-literal-ok' in raw:
                # 按行扣减：豁免行在去注释文本里的字面量全部作废
                keep = []
                for o_line, c_line in zip(raw.split('\n'), s.split('\n')):
                    keep.append(c_line if not EXEMPT.search(o_line) else '')
                s = '\n'.join(keep)
            for m in RE_HEX.finditer(s):
                colors[m.group(1).upper()] += 1
            for m in RE_DP.finditer(s):
                dps[m.group(1)] += 1
            for m in RE_SP.finditer(s):
                sps[m.group(1)] += 1
    return files, colors, dps, sps


def render(files, colors, dps, sps, brand):
    raw = []
    for hexv, name in IOS_NAMES.items():
        n = colors.get(hexv, 0)
        raw.append('    const val %s: Long = 0x%sL%s' % (name, hexv, '  // x %d' % n if n else ''))
    for hexv, name in NEUTRAL_NAMES.items():
        n = colors.get(hexv, 0)
        raw.append('    const val %s: Long = 0x%sL%s' % (name, hexv, '  // x %d' % n if n else ''))

    def slug(s):
        return re.sub(r'[^a-z0-9_]', '_', s)

    brand_light = '\n'.join('        const val %s: Long = 0x%sL' % (slug(s), h.upper())
                            for s, h in brand['light'])
    brand_dark = '\n'.join('        const val %s: Long = 0x%sL' % (slug(s), h.upper())
                           for s, h in brand['dark'])
    lh = '\n'.join('        0x%sL,' % h.upper() for h in brand['lh'])
    dh = '\n'.join('        0x%sL,' % h.upper() for h in brand['dh'])

    space = ['    const val %s: Float = %sf%s' % (SPACE_NAMES[v], v, '  // x %d' % dps[v] if v in dps else '')
             for v in SPACE_ORDER]
    space += ['    const val %s: Float = %sf%s' % (STROKE[v], v, '  // x %d' % dps[v] if v in dps else '')
              for v in STROKE]
    space.append('    const val touchTargetMin: Float = 48f  // 无障碍硬要求，不是审美')

    named_total = sum(c for h, c in colors.items() if h in IOS_NAMES or h in NEUTRAL_NAMES)
    total = sum(colors.values())
    pct = round(named_total * 100 / total) if total else 0

    head = '''package com.openminis.app.ui.tokens

/**
 * 自动生成 —— 不要手改。生成器: scripts/ui/gen_tokens.py
 *
 * 扫描事实：%d 个 ui 下的 .kt 文件 -> Color(0x...): %d 处 / %d 个不同色值；
 * dp 字面量 %d 处 / %d 个不同值；sp %d 处。
 * （已排除注释、字符串，以及标了 token-literal-ok 的行。）
 * 本文件命名 %d 个色值，当前覆盖 %d/%d = %d%% 的颜色使用点。
 * 剩下的未命名字面量由 scripts/ci/check_token_coverage.py 的按文件预算
 * 逐步压制 —— 那个脚本才是棘轮，本文件只负责统计。
 *
 * 注意：常量全量输出，不因"源码里暂时没有使用者"而删除 —— 迁移完一个色值后
 * 源码里就不再有它，如果按频率裁剪，令牌会把自己删空。
 */

/** 物理色。命名规则=来源，不改语义。语义槽在 [MinisColors]。 */
object RawColors {
%s

    // ─── 品牌色：厂商自身主色，不随动态取色变化 ─────────────────────────
    object Brand {
%s
    }

    object BrandDark {
%s
    }

    val brandHashLight: List<Long> = listOf(
%s
    )
    val brandHashDark: List<Long> = listOf(
%s
    )
}

/** 间距 / 圆角 / 线宽原语（2dp 栅格）。未命名的值（如 3/5/7）由棘轮压制。 */
object RawSpace {
%s
}

''' % (files, total, len(colors), sum(dps.values()), len(dps), sum(sps.values()),
       len(IOS_NAMES) + len(NEUTRAL_NAMES), named_total, total, pct,
       '\n'.join(raw), brand_light, brand_dark, lh, dh,
       '\n'.join(space))

    return head + SEMANTIC

SEMANTIC = '''
/**
 * 语义色。取值来自 RawColors，但每个字段回答的是"用在哪"，而不是"是什么颜色"。
 * 换肤只改 Raw*，语义槽不动。
 */
data class MinisColors(
    val primary: Long,
    val onPrimary: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    val separator: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val textTertiary: Long,
    val textDisabled: Long,
    val cardBg: Long,
    val cardBorder: Long,
    val inputBg: Long,
    val inputBorder: Long,
    val codeBlockBg: Long,
    val codeBlockText: Long,
    val inlineCodeBg: Long,
    val inlineCodeText: Long,
    val toolPending: Long,
    val toolRunning: Long,
    val toolSuccess: Long,
    val toolFailed: Long,
    val toolCancelled: Long,
    val statusInfo: Long,
    val statusWarn: Long,
    val statusDanger: Long,
    val statusWarnBg: Long,
    val categoryCode: Long,
    val categoryCreative: Long,
    val categoryResearch: Long,
    val categoryAffair: Long,
    val sendEnabled: Long,
    val sendDisabled: Long,
    val fabAccent: Long,
    val fabOnAccent: Long,
    val link: Long,
    val toastBg: Long,
    // ─── 编辑器卡片（文件编辑工具的 diff 视图）──────────────────────────
    // 独立于聊天气泡的灰阶：body 要比 sheet 容器沉一档，header 再比 body 亮一档，
    // 三段灰阶的层次关系是 iOS fileEditorContent 的观感契约，不是随手取的灰。
    val editorCardBg: Long,
    val editorCardHeaderBg: Long,
    val editorCardBorder: Long,
    val diffAddedBg: Long,
    val diffAddedText: Long,
    val diffRemovedBg: Long,
    val diffRemovedText: Long,
    // ─── 终端面板 ──────────────────────────────────────────────────────
    // 终端永远深底（连浅色主题也是），所以这三个槽两套主题同值。
    val terminalBg: Long,
    val terminalBorder: Long,
    val terminalLink: Long,
    val terminalHudBg: Long,
    // iOS CopyableURLCapsule: 固定白灰胶囊（white:0.85 填充 / white:0.35 文字），
    // 贴在终端深底上，不随主题翻转 —— 与 iOS 逐像素对齐。
    val urlCapsuleBg: Long,
    val urlCapsuleText: Long,
    val brandOpenAI: Long,
    val brandGemini: Long,
    val brandFallback: Long,
) {
    companion object {
        /** 浅色方案。语义值直接引用 Raw*，改视觉只改 Raw*。 */
        val Light = MinisColors(
            primary = RawColors.systemBlue,
            onPrimary = RawColors.neutral0,
            background = RawColors.neutral50,
            onBackground = RawColors.neutral800,
            surface = RawColors.neutral0,
            onSurface = RawColors.neutral800,
            surfaceVariant = RawColors.neutral100,
            onSurfaceVariant = RawColors.neutral600,
            outline = RawColors.neutral500,
            separator = RawColors.neutral100,
            textPrimary = RawColors.neutral900,
            textSecondary = RawColors.neutral600,
            textTertiary = RawColors.neutral400,
            textDisabled = RawColors.neutral200,
            cardBg = RawColors.neutral0,
            cardBorder = RawColors.neutral100,
            inputBg = RawColors.neutral50,
            inputBorder = RawColors.neutral100,
            codeBlockBg = RawColors.neutral50,
            codeBlockText = RawColors.neutral900,
            inlineCodeBg = RawColors.neutral100,
            inlineCodeText = RawColors.neutral800,
            toolPending = RawColors.systemGray,
            toolRunning = RawColors.systemBlue,
            toolSuccess = RawColors.systemGreen,
            toolFailed = RawColors.systemRed,
            toolCancelled = RawColors.systemOrange,
            statusInfo = RawColors.systemBlue,
            statusWarn = RawColors.systemOrange,
            statusDanger = RawColors.systemRed,
            statusWarnBg = 0xFFFFF4E5L,
            categoryCode = RawColors.systemOrange,
            categoryCreative = RawColors.systemPink,
            categoryResearch = RawColors.systemTeal,
            categoryAffair = RawColors.systemPurple,
            sendEnabled = RawColors.systemBlue,
            sendDisabled = RawColors.neutral200,
            fabAccent = RawColors.systemBlue,
            fabOnAccent = RawColors.neutral0,
            link = RawColors.systemBlue,
            toastBg = 0xE61C1C1EL,
            editorCardBg = 0xFFF0F0F0L,
            editorCardHeaderBg = 0xFFEBEBEBL,
            editorCardBorder = 0xFFD1D1D1L,
            diffAddedBg = 0xFFE5FFE5L,
            // 原 iOS 值 #1A991A 在 #E5FFE5 上只有 3.52:1，低于 AA 正文 4.5。
            // 压暗到 #15801A 得 4.78:1 —— 色相不变，只是不再靠"绿得发亮"取胜。
            diffAddedText = 0xFF15801AL,
            diffRemovedBg = 0xFFFFE5E5L,
            diffRemovedText = 0xFFCC1A1AL,
            terminalBg = 0xFF000000L,
            terminalBorder = 0xFF404040L,
            terminalLink = 0xFF7CC4FFL,
            terminalHudBg = 0xFF141414L,
            urlCapsuleBg = 0xFFD9D9D9L,
            urlCapsuleText = 0xFF595959L,
            brandOpenAI = RawColors.neutral900,
            brandGemini = RawColors.systemBlue,
            brandFallback = RawColors.systemGray,
        )

        /** 深色方案。与 Light 逐槽对应 —— 新增槽位漏填会编译报错，而不是运行期黑屏。 */
        val Dark = Light.copy(
            primary = 0xFFABC7FFL,
            onPrimary = 0xFF002D5FL,
            background = RawColors.neutral1000,
            onBackground = RawColors.neutral100,
            surface = RawColors.neutral1000,
            onSurface = RawColors.neutral100,
            surfaceVariant = RawColors.neutral600,
            onSurfaceVariant = RawColors.neutral200,
            outline = RawColors.neutral400,
            separator = RawColors.neutral600,
            textPrimary = RawColors.neutral100,
            textSecondary = RawColors.neutral200,
            textTertiary = RawColors.neutral400,
            textDisabled = RawColors.neutral600,
            cardBg = RawColors.neutral900,
            cardBorder = RawColors.neutral600,
            inputBg = RawColors.neutral900,
            inputBorder = RawColors.neutral600,
            codeBlockBg = RawColors.neutral900,
            codeBlockText = RawColors.neutral100,
            inlineCodeBg = RawColors.neutral600,
            inlineCodeText = RawColors.neutral100,
            statusWarnBg = 0xFF3A2A10L,
            sendDisabled = RawColors.neutral600,
            toastBg = 0xE6E2E2E6L,
            editorCardBg = 0xFF1A1A1AL,
            editorCardHeaderBg = 0xFF212121L,
            editorCardBorder = 0xFF404040L,
            diffAddedBg = 0xFF144D14L,
            diffAddedText = 0xFF66FF66L,
            diffRemovedBg = 0xFF4D1414L,
            diffRemovedText = 0xFFFF6666L,
            brandOpenAI = RawColors.neutral100,
            brandFallback = RawColors.neutral400,
        )
    }
}

/**
 * 语义尺寸。凡是"间距/圆角/线宽/触控"一律走这里。
 * 默认值即当前视觉；需要换密度时提供 alternate，而不是各处改 dp。
 */
data class MinisDimens(
    val spaceXxs: Float = 2f, val spaceXs: Float = 4f, val spaceSm: Float = 6f,
    val spaceMd: Float = 8f, val spaceMd2: Float = 10f, val spaceLg: Float = 12f,
    val spaceLg2: Float = 14f, val spaceXl: Float = 16f, val spaceXl2: Float = 18f,
    val spaceXxl: Float = 20f, val spaceXxl2: Float = 22f, val spaceXxl3: Float = 24f,
    val spaceXxl4: Float = 28f, val spaceHuge: Float = 32f, val spaceHuge2: Float = 40f,
    val spaceGiant: Float = 48f, val spaceGiant2: Float = 56f,
    val strokeHairline: Float = 0.5f, val strokeThin: Float = 1f, val strokeThick: Float = 2f,
    val touchTargetMin: Float = 48f,
    // 命名保持原三档语义不变：radiusSm=8 / radiusMd=12 / radiusLg=16。
    // 新增档位插在它们前后，不改旧值 —— 旧值若被改成 6，任何存量调用都会
    // 悄悄变窄，而"更小更精致"这种视觉变化没人会当成 bug 报上来。
    val radiusXs: Float = 2f, val radiusXs2: Float = 4f,
    val radiusSm3: Float = 6f, val radiusSm: Float = 8f,
    val radiusMd3: Float = 10f, val radiusMd: Float = 12f,
    val radiusLg3: Float = 14f, val radiusLg: Float = 16f,
    val radiusXl: Float = 18f, val radiusXl2: Float = 20f,
    val radiusXxl: Float = 24f, val radiusHuge: Float = 28f,
    val radiusHuge2: Float = 32f,
    val radiusPill: Float = 999f,
) {
    companion object {
        /**
         * 紧凑档：平板分栏 / 小屏横屏时整体缩一档间距。
         *
         * 必须覆盖整条 space 阶梯。只改其中几个槽会破坏单调性 —— 旧版只写了
         * spaceSm/Md/Lg/Xl/Xxl，于是 spaceMd2(10) > spaceLg(8)、
         * spaceLg2(14) > spaceXl(12)、spaceXl2(18) > spaceXxl(16) 三处倒挂，
         * 且 spaceXs 与 spaceSm 塌成同值 4。视觉上表现为「大间距比小间距还小」，
         * 排版忽紧忽松。check_token_semantics 现在会拦住这种半套改写。
         */
        val Compact = MinisDimens(
            spaceXxs = 2f, spaceXs = 3f, spaceSm = 4f,
            spaceMd = 6f, spaceMd2 = 8f, spaceLg = 10f,
            spaceLg2 = 12f, spaceXl = 14f, spaceXl2 = 16f,
            spaceXxl = 18f, spaceXxl2 = 20f, spaceXxl3 = 22f,
            spaceXxl4 = 24f, spaceHuge = 28f, spaceHuge2 = 34f,
            spaceGiant = 40f, spaceGiant2 = 48f,
            strokeHairline = 0.5f, strokeThin = 1f, strokeThick = 2f,
            touchTargetMin = 48f,
            radiusXs = 2f, radiusXs2 = 3f, radiusSm3 = 4f, radiusSm = 6f,
            radiusMd3 = 8f, radiusMd = 10f, radiusLg3 = 12f, radiusLg = 14f,
            radiusXl = 16f, radiusXl2 = 18f, radiusXxl = 20f,
            radiusHuge = 24f, radiusHuge2 = 28f, radiusPill = 999f,
        )
    }
}

/** 字号 + 行高。字号是离散档位，行高是连续插值 —— 两者性质不同，别混用。 */
data class MinisType(
    val cap2: Float = 9f, val chip: Float = 10f, val cap: Float = 11f,
    val foot: Float = 12f, val body: Float = 13f, val bodyLg: Float = 14f,
    val title: Float = 15f,
    val titleLg: Float = 16f, val head: Float = 18f,
    /** 展示档：Markdown h2、统计大数字。20sp。
     *
     *  令牌表此前止步于 18(head)，因为 18 是「最大的正文可用字号」——
     *  界面里的功能文字到此为止。20 之上是**展示**：Markdown 标题、用量
     *  统计里的主数字。它们的共同点是出现频率低、占位大、不需要和 13sp
     *  正文在同一行共存。混进 head 会让「调大标题」误伤所有小节标题。 */
    val display: Float = 20f,
    /** 展示大档：Markdown h1、emoji 头像字形。24sp。
     *
     *  24 是正文的 1.85 倍。到这个尺度，字形细节（尤其是 emoji 的色块边缘）
     *  开始主导观感，再大就变成插画而非文字，需要换 Image 而非 fontSize。 */
    val displayLg: Float = 24f,
    /** 输入框 / 用户气泡 / 代码编辑器。这三处都是多行可编辑区，16.5 是字宽与
     *  可读性的折中：再小输入时看不住，再大又把气泡撑宽、一行塞不下几个字。 */
    val bodyInput: Float = 16.5f,
) {
    /**
     * 全大写小节标题的字距（sp）。0.5。
     *
     * 只用于 `uppercaseForDisplay() + labelSmall + Medium` 这一种组合 ——
     * 全仓 7 处 `letterSpacing = 0.5.sp` 全是它（MemoryDetailScreens /
     * SessionMcpsSheet / SessionSkillsSheet / ProviderQuotaSection×2 /
     * DetailRows / SettingsComponents）。
     *
     * 为什么不是巧合：全大写没有 x-height 变化，字形边缘齐平，密排时单词
     * 边界只能靠字间空隙区分。小写混排不需要这个补偿。
     */
    val sectionLabelSpacing: Float = 0.5f

    /**
     * 行高 = 字号代入实测曲线。**不是** 字号 × 单一比例 —— 真实设计不这样。
     *
     * 全仓实测 59 组多行 fontSize/lineHeight 配对（已排除单行标签，那些把
     * lineHeight 压到等于 fontSize 是为消除行间空隙，属于刻意做法），按字号
     * 取行高中位数得到锚点（字号 → 行高）：
     *
     *    9 → 11    12 → 17    16 → 24   28 → 36
     *   10 → 12    13 → 18   16.5 → 24   32 → 40
     *   11 → 14.5  14 → 20    22 → 28   36 → 44
     *   15 → 22                          45 → 52
     *                                    57 → 64
     *
     * 16 与 16.5 都是 24：16.5 是输入框/气泡，它不能比正文更松，否则输入时
     * 光标与文字的相对位置看起来是飘的。这一段是平台，不是断档。
     *
     * 比值呈倒 U 形（1.20 → 1.50 → 1.12）：小字要紧凑、正文要松、显示字号
     * 要收紧。这是三条不同曲线，不是一条按比例缩放的线。
     *
     * 前两版都是错的，错法不同：
     *   1. `size * 1.35f` 单点 —— 把曲线压成一个点。9sp 拉松到 12.2（实际 11），
     *      16sp 压紧到 21.6（实际 24），正文最挤、注脚最松，正好反了。
     *   2. `when` 阶梯 —— 16 之后直接 `size <= 22f -> 1.27f`，于是
     *      16.5/17/18/19/20 全拿到 1.27。16.5sp 输入框（实测 24）被算成 20.9，
     *      比 16sp 正文的 24 还紧；17-20 的 20 处调用点一并被压平。
     *      阶梯本质是分段常数，跨档必然跳变，档与档之间一律错。
     *
     * 现在按锚点线性插值：任何字号都落在相邻两个实测值之间，不存在跨档跳变，
     * 也不再需要「未知字号」兜底分支。超出实测范围按端点比值外推（9→11 和
     * 57→64 的比值），调用方拿到的是有限正值。
     *
     * 插值取相邻锚点连直线，比真实曲线略保守：拐点附近宁可松一点也不挤。
     * 挤压是看得见的排版 bug，松一点只浪费几像素。
     */
    fun lineHeight(size: Float): Float {
        val first = LH_SIZES[0]
        val last = LH_SIZES[LH_SIZES.size - 1]
        return when {
            size <= first -> size * (LH_HEIGHTS[0] / first)
            size >= last -> size * (LH_HEIGHTS[LH_HEIGHTS.size - 1] / last)
            else -> {
                var i = 0
                while (i < LH_SIZES.size - 2 && size > LH_SIZES[i + 1]) i++
                val x0 = LH_SIZES[i]
                val y0 = LH_HEIGHTS[i]
                val x1 = LH_SIZES[i + 1]
                val y1 = LH_HEIGHTS[i + 1]
                y0 + (y1 - y0) * ((size - x0) / (x1 - x0))
            }
        }
    }

    /**
     * 单行标签行高系数。maxLines=1 + noFontPad 的文本用它，**不要**用 [lineHeight]。
     *
     * 为什么单独给：单行文本的高度直接决定栏高、胶囊高度、列表行高。这类文本
     * 处处被刻意压紧（实测系数 1.14–1.23，中位 1.188），目的是让 6dp/8dp 的
     * padding 上下均匀。用多行曲线给它们会让 16sp 标题的栏高从 19 撑到 24，
     * 顶栏立刻高出一截 —— 这不是排版宽松，是布局错位。
     *
     * 实测覆盖（系数 1.18 与真实值的偏差）：
     *   11sp → 13（-0.02）   14sp → 16（+0.52）
     *   12sp → 14（+0.16）   15sp → 18（-0.30）
     *   13sp → 16（-0.66）   16sp → 19（-0.12）
     * 全部落在 ±0.7sp 内，比任何一条统一曲线都准。
     */
    fun lineHeightTight(size: Float): Float = size * 1.18f

    /**
     * 等宽文本行高系数（日志、代码块、路径显示）。
     *
     * 等宽字体的字形高度与比例字体不同，同字号需要更大行距才不粘连；这类文本
     * 是给人逐行扫的，行距偏大反而好读。实测 11sp→16（1.455）、12sp→16（1.333），
     * 取 1.35 居中：11sp 得 14.85（比实测紧 1.15，日志要一屏塞更多行），
     * 12sp 得 16.2（几乎命中 16）。
     */
    fun lineHeightMono(size: Float): Float = size * 1.35f

    companion object {
        val Default = MinisType()

        /** 实测行高锚点，必须与 [LH_HEIGHTS] 等长、严格递增。守卫会校验。 */
        val LH_SIZES = floatArrayOf(
            9f, 10f, 11f, 12f, 13f, 14f, 15f, 16f, 16.5f, 22f, 24f, 28f, 32f, 36f, 45f, 57f,
        )
        val LH_HEIGHTS = floatArrayOf(
            11f, 12f, 14.5f, 17f, 18f, 20f, 22f, 24f, 24f, 28f, 32f, 36f, 40f, 44f, 52f, 64f,
        )
    }
}

/** 具名行距角色。值是对齐到整个 sp 的实测量，不是曲线输出。
 *
 *  [MinisType.lineHeight] 是「字号 -> 行高」的连续插值，只能给一个答案。
 *  但同一字号在不同用途下需要的行距并不相同：13sp 正文配 19，等宽日志配
 *  18，输入框配 16.25（后者是 composerFontScale 缩放前的基准，必须钉死，
 *  否则缩放时长行距会被曲线二次放大）。这类地方只能按角色取值。
 *
 *  命名按**用途**不按数值：`badge` 不是「11sp 的行高」，是「单行徽章的行高」。
 *  同一数值服务多个角色时归入最大的一族，其余在注释里点名，避免出现
 *  `lh11`/`lh16` 这种把字面量换个写法的假令牌。 */
object MinisLineHeight {
    /** 单行徽章：fontSize 9-11sp 且 maxLines=1。行高必须贴着字号，
     *  否则 18dp 高的胶囊里文字会被行距顶出垂直居中。 */
    const val badge = 11f

    /** 多行说明与错误提示：12sp 正文、Material bodySmall。 */
    const val note = 16f

    /** 多行小字排版：11sp caption 两行、12sp 工具结果摘要。 */
    const val caption = 17f

    /** 13sp 正文默认行距。中文正文的舒适行距下限。 */
    const val body = 19f

    /** 14sp 大正文：sub-agent 栏、列表副标题。 */
    const val bodyLg = 20f

    /** 等宽正文 12-13sp：工具输出、日志、代码预览、memory 内容。
     *  codeFoot(12) 与 code(13) 共用：等宽字形 x-height 一致，行距取
     *  同一绝对值才让相邻区块的基线对齐。 */
    const val code = 18f

    /** 等宽小字 11sp：git 面板、logcat。比 [code] 紧一档。 */
    const val codeSm = 15f

    /** 紧凑等宽行 13sp：diff 的 +/- 行。每行只有 2dp 垂直 padding，
     *  行距必须比常规 [code] 紧 1sp，否则相邻增删行会互相粘连，
     *  一眼看不出是一行改成了另一行。 */
    const val codeDiff = 17f

    /** 15sp 标题：sub-agent 结果头。 */
    const val title = 22f

    /** 输入框基准行距。乘 composerFontScale 之前的值，16.5sp 字号配它
     *  得 0.98 的紧行距，与 4 行可见高度的设计一致。 */
    const val composer = 16.25f

    /** 11sp cap 的紧凑单行：顶栏按钮文字。 */
    const val capCompact = 13f

    /** 11sp cap 的常规单行。 */
    const val cap = 14f

    /** 10sp chip：状态胶囊。 */
    const val chip = 12f
}

/** 动效令牌。fast=120ms 用于 press，base=200ms 用于展开，slow=320ms 用于页面切换。 */
object MinisMotion {
    const val FAST = 120
    const val BASE = 200
    const val SLOW = 320
}
'''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ui', required=True)
    ap.add_argument('--out', default='gen')
    a = ap.parse_args()

    files, colors, dps, sps = scan(a.ui)
    brand = load_brand()
    os.makedirs(a.out, exist_ok=True)
    open(os.path.join(a.out, 'MinisTokens.kt'), 'w', encoding='utf-8').write(
        render(files, colors, dps, sps, brand))

    named = sum(c for h, c in colors.items() if h in IOS_NAMES or h in NEUTRAL_NAMES)
    total = sum(colors.values())
    json.dump({
        'files': files,
        'colors': dict(colors.most_common()),
        'dp': dict(dps.most_common()),
        'sp': dict(sps.most_common()),
        'named_total': named,
        'named_pct': round(named * 100 / total) if total else 0,
        'brand_light': len(brand['light']),
        'brand_dark': len(brand['dark']),
    }, open(os.path.join(a.out, 'token_data.json'), 'w', encoding='utf-8'),
        ensure_ascii=False, indent=1)

    pct = round(named * 100 / total) if total else 0
    print('ui 文件      : %d' % files)
    print('颜色         : %d 处 / %d 个不同值  -> 命名覆盖 %d 处 (%d%%)'
          % (total, len(colors), named, pct))
    print('品牌色       : 浅色 %d / 深色 %d' % (len(brand['light']), len(brand['dark'])))
    print('dp           : %d 处 / %3d 个不同值' % (sum(dps.values()), len(dps)))
    print('sp           : %d 处 / %d 个不同值' % (sum(sps.values()), len(sps)))
    print('输出 -> %s/MinisTokens.kt + token_data.json' % a.out)


if __name__ == '__main__':
    main()
