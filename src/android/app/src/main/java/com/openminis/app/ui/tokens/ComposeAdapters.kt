package com.openminis.app.ui.tokens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose 适配层 —— 让 [MinisTokens] 保持纯 Kotlin（可脱离 Android 编译/测试），
 * 只有这 40 行依赖 Compose。
 *
 * 为什么分两层：令牌文件由 `refactor/tools/gen_tokens.py` 生成，必须在 JVM 上
 * 单独编译通过（`kotlinc MinisTokens.kt` 即可）。把 Compose 类型塞进生成物会让
 * 生成物依赖 androidx，失去可验证性。这一层是薄的、手写的、只有转换逻辑。
 */

/** 0xAARRGGBB 的 Long → Compose Color。Compose 的 Int 重载按 ARGB 解释，语义一致。 */
val Long.asColor: Color get() = Color(this.toInt())

// ─── 语义色入口 ─────────────────────────────────────────────────────────────

val LocalMinisColors = staticCompositionLocalOf { MinisColors.Light }

/** 当前主题的语义色。用法：`MinisTheme.colors.toolSuccess.asColor` */
object MinisTheme {
    val colors: MinisColors
        @Composable @ReadOnlyComposable get() = LocalMinisColors.current
}

// ─── 尺寸 / 字号 ────────────────────────────────────────────────────────────

val Float.dpToken: Dp get() = this.dp
val Float.spToken: TextUnit get() = this.sp

val LocalMinisDimens = staticCompositionLocalOf { MinisDimens() }

object Minis {
    val dimens: MinisDimens
        @Composable @ReadOnlyComposable get() = LocalMinisDimens.current
}

// ─── 行高 / 字号阶梯 ────────────────────────────────────────────────────────

val LocalMinisType = staticCompositionLocalOf { MinisType() }

object MinisTypography {
    val type: MinisType
        @Composable @ReadOnlyComposable get() = LocalMinisType.current

    /** 微档：角标数字、未读点、极小的计数。9sp。
     *
     *  9sp 是这套排版的下限。它不是「比 cap 更小的字」，用途也完全不同 ——
     *  cap 是能被读的小节标题，cap2 是**不打算被逐字读**的纯符号（角标、红点
     *  里的数字、hover 提示的箭头）。这类东西嵌在 4-8dp 的圆点里，字号再大
     *  就会溢出色块。混进 cap 会让有人调小节标题时把角标撑破。 */
    val cap2: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.cap2.sp

    /** 徽章 / 胶囊标签字号。10sp。
     *
     *  这一档的判据不是"有多小"，是**外面套着一个有边框或底色的容器**：
     *  浏览器 tab 计数、模型名字标签、头像首字母、在线状态胶囊。它们都在
     *  18-22dp 高的圆角块里，字号必须留出上下 padding 才不贴边。
     *
     *  归进 cap2(9sp) 会小得在胶囊里浮起来，归进 cap(11sp) 会在 18dp 高的
     *  块里顶到边框。10 是这两个约束挤出来的唯一值，不是随便取的中间档。 */
    val chip: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.chip.sp

    /** 正文档字号。13sp 不在 M3 规范档位里（bodyMedium 14 / bodySmall 12），
     *  所以每个用它的地方都得手写 `13.sp` —— 全仓 54 处。这是令牌该接管的
     *  典型场景：值本身没错，错的是散落 54 份副本，改一次正文尺寸要改 54 处。 */
    val body: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.body.sp

    /** 次级正文。14sp，与 M3 bodyMedium 同值但语义独立（我们的档位不跟 M3 走）。 */
    val bodyLg: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.bodyLg.sp

    /** 注脚 / 辅助信息。12sp。 */
    val foot: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.foot.sp

    /** 小节标题 / 列表主行。11sp，与 M3 labelSmall 同值。 */
    val cap: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.cap.sp

    /** 等宽代码档字号（代码块、日志、路径、命令回显）。13sp。
     *
     *  为什么不能和 [body] 共用一个槽：等宽字体的字形宽度固定，13sp 等宽行
     *  比 13sp 比例字体宽约 12%，视觉重量也不一样。把它们绑成一个名字，将来
     *  有人调正文字号会把代码块一起推歪 —— 那是 13 处代码视图的排版事故，
     *  而正文改动本身是对的。值相同不等于语义相同。 */
    val code: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.body.sp

    /** 等宽小档（行内代码、终端标签、密集日志行）。11sp。
     *
     *  等宽 11 / 12 / 13 / 14 是四个独立档位，不是同一个档位的四舍五入。
     *  实测它们的用途完全分开：11sp 是行内 code span 与终端快捷键标签，
     *  12sp 是日志面板与 diff 行，13sp 是代码块正文，14sp 是 KaTeX 公式
     *  与用量统计里的等宽数值。合并成一个 `code` 会把 11sp 的密集日志
     *  推到 13sp，行数一样但密度掉一档 —— 日志那种一屏几十行的场景里
     *  一屏就少显示四五行。 */
    val codeSm: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.cap.sp

    /** 等宽小档（日志面板、diff 行号与内容）。12sp。见 [codeSm] 的档位说明。 */
    val codeFoot: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.foot.sp

    /** 等宽大档（公式渲染、统计数值）。14sp。见 [codeSm] 的档位说明。 */
    val codeLg: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.bodyLg.sp

    /** 小标题 / 列表项主行。15sp。
     *
     *  15 是个别扭但要保住的值：它比 bodyLg(14) 大一档、比 titleLg(16) 小一档，
     *  专门用在「一屏里有多个并列小节」的列表，让每个小节的标题略微跳出来。
     *  归进 bodyLg 会把 8 处列表标题压平，归进 titleLg 会让它们抢正文的注意力。 */
    val title: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.title.sp

    /** 大标题档：对话框标题、分节大标题。16sp。
     *
     *  和 bodyLg(14) 只差 2sp，但这一档的意义在**行高**：titleLg 走
     *  lineHeight 曲线的 24sp 锚点，bodyLg 走 20sp。同样的 16sp 正文，
     *  行距一松就是标题感。所以这一档不能靠「复制 bodyLg 再加粗」替代。 */
    val titleLg: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.titleLg.sp

    /** 页内主标题。18sp。列表页 / 空状态 / 设置分组的大标题。 */
    val head: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.head.sp

    /** 输入框正文。16.5sp —— 比正文松半档，因为打字时光标需要呼吸空间。
     *  16.5 这个数不是随手取的：它刚好高于 16sp 的 `titleLg`，让输入区在
     *  视觉上比周围的说明文字更"在前"，又不至于跨进 18sp `head` 的标题感。 */
    val bodyInput: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.bodyInput.sp

    /** 展示档。20sp。Markdown h2、用量统计里的主数字。
     *
     *  20 之上不再是"功能文字"而是**展示**：出现频率低、占位大、不与 13sp
     *  正文同行共存。混进 head(18) 会让「调大标题」误伤所有小节标题。 */
    val display: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.display.sp

    /** 展示大档。24sp。Markdown h1、emoji 头像字形。
     *  到 24sp，字形细节（尤其 emoji 色块边缘）开始主导观感，再大就该用
     *  Image 而非 fontSize 了。 */
    val displayLg: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.displayLg.sp

    /** 全大写小节标题的字距。0.5sp —— 全大写无 x-height 变化、字形边缘齐平，
     *  密排时单词边界只能靠字间空隙区分。小写混排不需要这个补偿。 */
    val sectionLabelSpacing: TextUnit
        @Composable @ReadOnlyComposable get() = LocalMinisType.current.sectionLabelSpacing.sp

    // ─── 行距角色 ───────────────────────────────────────────────────────────
    // 以上是「字有多大」，以下是「行有多松」。两者必须分开：13sp 正文配 19、
    // 等宽 13sp 配 18、输入框 16.5sp 配 16.25 —— 同一个字号三个行高，
    // `MinisType.lineHeight(size)` 的连续曲线只能给一个答案。
    // 这些值不是曲线输出，是钉死的实测值，所以装箱成常量而非函数。

    /** 单行徽章行高（9-11sp + maxLines=1）。贴着字号，否则胶囊垂直居中会偏。 */
    val lineBadge: TextUnit get() = MinisLineHeight.badge.sp

    /** 多行说明 / 错误提示行高。12sp 正文与 M3 bodySmall 共用。 */
    val lineNote: TextUnit get() = MinisLineHeight.note.sp

    /** 11sp caption 两行排版的行高。 */
    val lineCaption: TextUnit get() = MinisLineHeight.caption.sp

    /** 13sp 正文默认行高。 */
    val lineBody: TextUnit get() = MinisLineHeight.body.sp

    /** 14sp 大正文行高。 */
    val lineBodyLg: TextUnit get() = MinisLineHeight.bodyLg.sp

    /** 等宽正文行高（12-13sp）。 */
    val lineCode: TextUnit get() = MinisLineHeight.code.sp

    /** 等宽小字行高（11sp）。 */
    val lineCodeSm: TextUnit get() = MinisLineHeight.codeSm.sp

    /** 紧凑等宽行高（13sp diff 行）。 */
    val lineCodeDiff: TextUnit get() = MinisLineHeight.codeDiff.sp

    /** 15sp 标题行高。 */
    val lineTitle: TextUnit get() = MinisLineHeight.title.sp

    /** 输入框基准行高（缩放前）。 */
    val lineComposer: TextUnit get() = MinisLineHeight.composer.sp

    /** 11sp cap 紧凑单行行高（顶栏按钮）。 */
    val lineCapCompact: TextUnit get() = MinisLineHeight.capCompact.sp

    /** 11sp cap 常规单行行高。 */
    val lineCap: TextUnit get() = MinisLineHeight.cap.sp

    /** 10sp chip 状态胶囊行高。 */
    val lineChip: TextUnit get() = MinisLineHeight.chip.sp
}
