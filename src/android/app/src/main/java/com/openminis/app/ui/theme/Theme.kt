package com.openminis.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.tokens.LocalMinisColors
import com.openminis.app.ui.tokens.LocalMinisDimens
import com.openminis.app.ui.tokens.LocalMinisType
import com.openminis.app.ui.tokens.MinisColors
import com.openminis.app.ui.tokens.MinisDimens
import com.openminis.app.ui.tokens.MinisType

// Accent: iOS blue, desaturated. [T-android-accent-blue-parity]
//
// Was a teal (#2E8B8B / #4DD9D9) that predated iOS settling on blue. The hue
// now comes from iOS Assets.xcassets/AccentColor.colorset (sRGB components
// r0.212 g0.525 b0.933 -> #3686EE light, r0.329 g0.565 b0.894 -> #5490E4 dark),
// but iOS's saturation (84% / 73%) read as glaring on Android's darker
// surfaces, so SATURATION is dialled back ~30% with hue and lightness kept:
//   light  #3686EE  S84% L57%  ->  #528AD2  S59% L57%
//   dark   #5490E4  S73% L61%  ->  #6A94CE  S51% L61%
//
// Lightness is deliberately NOT raised, which is the other way to "lighten".
// It would have softened dark mode further but pushed light-mode contrast on
// white from 3.62 to 2.62 — below WCAG AA's 4.5 for text. Desaturating keeps
// dark mode at 5.93 (passing) and leaves light mode where it was.
//
// Names keep the `Teal` prefix only to avoid churning 90+ call sites; the
// value is the contract, not the name.
// [T-apple-blue] Apple Blue primary, desaturated for Android readability.
// [T-accent-text-contrast] #5A8AD2 measured 3.50:1 as button text (white on
// primary) and 3.14:1 as text on #F2F2F7 — both below WCAG AA 4.5. #2E6BC0
// keeps the blue hue and passes everywhere (white-on-it 5.28, as-text 4.73).
private val TealPrimary = Color(0xFF2E6BC0)
private val TealOnPrimary = Color(0xFFFFFFFF)
private val TealPrimaryContainer = Color(0xFFD6E4FF)
private val TealOnPrimaryContainer = Color(0xFF001A3B)
private val TealSecondary = Color(0xFF545F70)
private val TealOnSecondary = Color(0xFFFFFFFF)
private val TealSecondaryContainer = Color(0xFFD8E3F8)
private val TealOnSecondaryContainer = Color(0xFF111C2B)
private val TealTertiary = Color(0xFF6D5677)
private val TealOnTertiary = Color(0xFFFFFFFF)
private val TealTertiaryContainer = Color(0xFFF6D8FF)
private val TealOnTertiaryContainer = Color(0xFF271431)
// Apple-style grouped surface: page = systemGray6 (#F2F2F7), card = white.
private val TealBackground = Color(0xFFF2F2F7)
private val TealOnBackground = Color(0xFF1C1C1E)
private val TealSurface = Color(0xFFF2F2F7)
private val TealOnSurface = Color(0xFF1C1C1E)
private val TealSurfaceVariant = Color(0xFFE2E2E6)
private val TealOnSurfaceVariant = Color(0xFF444446)
private val TealOutline = Color(0xFF747477)
private val TealDarkPrimary = Color(0xFFABC7FF)
private val TealDarkOnPrimary = Color(0xFF002D5F)
private val TealDarkPrimaryContainer = Color(0xFF004378)
private val TealDarkOnPrimaryContainer = Color(0xFFD6E4FF)
private val TealDarkSecondary = Color(0xFFBCC7DB)
private val TealDarkOnSecondary = Color(0xFF263141)
private val TealDarkSecondaryContainer = Color(0xFF3C4859)
private val TealDarkOnSecondaryContainer = Color(0xFFD8E3F8)
private val TealDarkBackground = Color(0xFF000000)
private val TealDarkOnBackground = Color(0xFFE2E2E6)
private val TealDarkSurface = Color(0xFF000000)
private val TealDarkOnSurface = Color(0xFFE2E2E6)
private val TealDarkSurfaceVariant = Color(0xFF444446)
private val TealDarkOnSurfaceVariant = Color(0xFFC4C4C8)
private val TealDarkOutline = Color(0xFF8E8E92)

// Surface tokens are defined inline in LightColorScheme/DarkColorScheme above.

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    surfaceTint = TealPrimary,
    inversePrimary = TealDarkPrimary,
    error = Color(0xFFD70015),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2B2B2E),
    inverseOnSurface = Color(0xFFF2F2F7),
    surfaceDim = Color(0xFFD9D9DE),
    surfaceBright = Color(0xFFFDFDFE),
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    tertiary = TealTertiary,
    onTertiary = TealOnTertiary,
    tertiaryContainer = TealTertiaryContainer,
    onTertiaryContainer = TealOnTertiaryContainer,
    background = TealBackground,
    onBackground = TealOnBackground,
    surface = TealSurface,
    onSurface = TealOnSurface,
    surfaceVariant = TealSurfaceVariant,
    onSurfaceVariant = TealOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFDFDFE),
    surfaceContainer = Color(0xFFFAFAFC),
    // [T-surface-container-ramp] cLow/c and cHigh/cHighest were duplicate pairs
    // (cHigh→cHighest 1.00:1 — invisible to its 24 consumers). Five distinct
    // steps, keeping the Apple grouped direction (lowest = white cards on the
    // gray page, M3 direction reversed by design).
    surfaceContainerHigh = Color(0xFFF6F6F9),
    surfaceContainerHighest = Color(0xFFF1F1F5),
    outline = TealOutline,
    // [T-outline-split] outlineVariant was aliased to outline: the decorative
    // hairline tier and the structural border tier were one color, forcing 87
    // call sites to fold their own alpha. Split per M3 (light variant ≈ tone 80).
    outlineVariant = Color(0xFFC9C9CE),
)

private val DarkColorScheme = darkColorScheme(
    primary = TealDarkPrimary,
    surfaceTint = TealDarkPrimary,
    inversePrimary = TealPrimary,
    // [T-dark-tertiary-family] The dark scheme skipped tertiary entirely, so it
    // fell back to the library pink #EFB8C8 — a different hue from the light
    // scheme's #6D5677. Derived from the same iOS-purple family.
    tertiary = Color(0xFFD9BCE4),
    onTertiary = Color(0xFF352A44),
    tertiaryContainer = Color(0xFF523C60),
    onTertiaryContainer = Color(0xFFF2E6FA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE2E2E6),
    inverseOnSurface = Color(0xFF1C1C1E),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A3A3C),
    onPrimary = TealDarkOnPrimary,
    primaryContainer = TealDarkPrimaryContainer,
    onPrimaryContainer = TealDarkOnPrimaryContainer,
    secondary = TealDarkSecondary,
    onSecondary = TealDarkOnSecondary,
    secondaryContainer = TealDarkSecondaryContainer,
    onSecondaryContainer = TealDarkOnSecondaryContainer,
    background = TealDarkBackground,
    onBackground = TealDarkOnBackground,
    surface = TealDarkSurface,
    onSurface = TealDarkOnSurface,
    surfaceVariant = TealDarkSurfaceVariant,
    onSurfaceVariant = TealDarkOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF0A0A0C),
    surfaceContainerLow = Color(0xFF161618),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF262628),
    surfaceContainerHighest = Color(0xFF323235),
    outline = TealDarkOutline,
    outlineVariant = Color(0xFF4A4A4F),
)

// App-wide FAB accent color (warm beige, matching iOS New Chat button).
// Reads from ChatPalette so it follows the in-app theme override (theme_mode pref),
// not android.isSystemInDarkTheme(), which only tracks the system setting.
@Composable
fun minisFabColor(): Color = LocalChatPalette.current.fabAccent

// Icon tint paired with [minisFabColor]. Reads from the palette so the dynamic
// path can pair onPrimary with the wallpaper-derived fabAccent; the static
// path keeps the White the icon hardcoded before this slot existed.
@Composable
fun minisFabContentColor(): Color = LocalChatPalette.current.fabOnAccent

// [T-two-styles] 动态取色模式下 Icon 走 Outlined 风格（material-icons 的
// Outlined 族，经 M3Icons.kt 的 m3Icon() 做 Filled↔Outlined 映射）。
// 非动态模式用 Filled/Default。注意：这是 Material Icons 的填充风格切换，
// 不是 Material Symbols 的 Rounded 字形族（后者从未被引入）。默认为 false。
val LocalUseRoundedIcons = compositionLocalOf { false }

// True only while the wallpaper scheme is actually in effect (dynamic color ON
// and Android 12+). Consumers use it to swap hand-tuned iOS-parity accents for
// scheme roles without re-deriving the gate themselves.
val LocalMonetDynamic = compositionLocalOf { false }

// The accent bridge that consumes this gate (monetAccentTiles, monetIconTint,
// monetIconTileColor/ContentColor) lives in AccentTokens.kt.

// Apple-style shape system — rounded, friendly, iOS-like
// 动态取色时替换为 M3 baseline 5 档（M3ExpressiveShapes）。M3E 的 8 档
// Shapes / MotionScheme / MaterialExpressiveTheme 需 material3 1.5.0
// （AGP 9.1.2 + compileSdk 37），版本门控见 gradle/libs.versions.toml 头注。
private val AppleShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// M3 Expressive shape tokens — dynamic mode 用这套。
// Token 对应: extraSmall=4dp, small=8dp, medium=12dp, large=16dp, extraLarge=28dp
private val M3ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // [T-android-monet-dynamic-color] The framework only carries the system_*
    // color resources on Android 12+, so the gate is a correctness
    // requirement, not a nicety: below S those resources do not resolve.
    // minSdk is 26, hence the explicit gate. In dynamic mode the wallpaper
    // scheme supplies EVERY slot — accents AND surfaces; Material You derives
    // surface/onSurface pairs with contrast built in, so the hand-measured
    // WCAG notes on the static palettes do not apply to this branch.
    val dynamicActive = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = if (dynamicActive) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) DarkColorScheme else LightColorScheme
    // 两套独立的视觉系统：
    //   非动态 → Apple 风格（自有 ChatPalette）
    //   动态   → M3 baseline（Material You 全套色板 + 官方 Shape/Type tokens）。
    //   注：M3 baseline 而非 M3 Expressive —— material3 1.3.2 没有
    //   MaterialExpressiveTheme / MotionScheme / 8 档 Shapes（1.5.0 才有）
    val shapes = if (dynamicActive) M3ExpressiveShapes else AppleShapes
    val typography = if (dynamicActive) {
        M3Typography  // M3 官方 Type Scale（Display/Headline/Title/Body/Label）
    } else {
        scaledTypography(fontScale)
    }
    val fallbackChatPalette = if (darkTheme) DarkChatPalette else LightChatPalette
    val chatPalette =
        if (dynamicActive) {
            dynamicChatPalette(colorScheme, fallbackChatPalette)
        } else {
            fallbackChatPalette
        }
    // [T-tokens-wire] 令牌层必须在这里真正提供，否则 LocalMinisColors 一直取
    // staticCompositionLocalOf 的默认值（MinisColors.Light），深色模式读令牌
    // 永远拿到浅色值 —— 与 PR #126「只挂 Provider 没接消费者」同型的假修复。
    val minisColors = if (darkTheme) MinisColors.Dark else MinisColors.Light
    // 紧凑档跟屏幕宽度走，不跟取色走 —— Compact 的语义是「平板分栏 / 小屏横屏
    // 整体缩一档间距」，拿 dynamicActive 当开关会让开启动态取色的手机无故变密。
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val minisDimens = if (screenWidthDp >= 600) MinisDimens.Compact else MinisDimens()
    // 行高阶梯单一来源：MinisType.lineHeight(size) 由全仓 57 组多行
    // fontSize/lineHeight 配对反推。M3Typography 里 11sp 与 12sp 同用 16sp 行高
    // 是 M3 规范下限所致，令牌表里 12sp = 17sp。
    val minisType = MinisType()
    // [T-motion-scale] System animator duration scale: 0 when the user turned
    // animations off, 1 normally, >1 under a developer slowdown. Read once per
    // theme composition (it changes only on a config change, which recomposes
    // the theme anyway).
    val motionScale = runCatching {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrDefault(1f)

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes,
        typography = typography,
    ) {
        CompositionLocalProvider(
            LocalChatPalette provides chatPalette,
            LocalMonetDynamic provides dynamicActive,
            LocalUseRoundedIcons provides dynamicActive,
            LocalMinisColors provides minisColors,
            LocalMinisDimens provides minisDimens,
            LocalMinisType provides minisType,
            // [T-motion-scale] Feed the system animator duration scale into the
            // decor derivations so "remove animations" (scale 0) actually stops
            // them — previously nothing in the app read it at all.
            LocalMotionDurationScale provides motionScale,
        ) {
            // [T-decor-shared-clock] 所有装饰动画共用一个 30fps 相位来源。
            // 消费者必须显式读 LocalDecorPhase / decor* 派生器才真正共享时钟
            // ——PR #126 只挂了 Provider 没接消费者，共享时钟空转、独立
            // transition 照跑，是假修复；本提交把 8 处动画全部迁到派生器上。
            // [T-decor-consumer-gate] 派生器现在还承担消费者计数：无消费者时
            // Provider 的帧循环整体取消，静止页面不再为装饰动画付帧成本。
            DecorClockProvider(content)
        }
    }
}

/**
 * [T-android-monet-dynamic-color] The chat palette follows the wallpaper too.
 *
 * The chat palette is where Minis is most itself — 34 slots of hand-tuned iOS
 * system colors — and it does not read [MaterialTheme.colorScheme], so a
 * scheme-only change left the biggest visible surface (chat) untouched, which
 * is why toggling dynamic color changed almost nothing a user could see.
 *
 * Surface, text and accent slots all derive from the wallpaper scheme; each
 * takes a slot the framework already contrast-tunes rather than a
 * hand-blended guess:
 *  - userBubble = primaryContainer: Monet designs primaryContainer to pair
 *    with dark-on-light / light-on-dark text, and the bubble text is
 *    onSurface (black/white) — the pairing holds in both modes without any
 *    alpha arithmetic (the T-android-user-bubble-dark-contrast note in
 *    ChatColors.kt is exactly the failure mode translucent guesses produce).
 *  - sendButton / link / thinking / fabAccent = primary: tone-40 on white and
 *    tone-80 on black are the pairings Material You derives primary FOR.
 *  - fabOnAccent = onPrimary: the FAB icon tint, paired by construction. The
 *    static palettes keep White, matching the icon's previous hardcoded tint,
 *    so the OFF path is byte-identical.
 *
 * Semantic slots (warning orange, syntax green, blockquote bar) and the
 * dark-mode elevation shadow stay on [fallback] — their hue IS the message.
 * Thinking-chip foreground follows onPrimary (White on a dark-mode tone-80
 * primary measured 1.70:1).
 * Dark surfaces lift one step (input=containerHighest, tools=containerHigh)
 * so the chat layers stay separated on ~500-nit screens — the T153 lesson
 * from the static dark palette, which the first dynamic mapping reversed.
 *
 * Pure so the rule is unit-testable alongside the static palettes.
 */
internal fun dynamicChatPalette(scheme: ColorScheme, fallback: ChatPalette): ChatPalette =
    fallback.copy(
        // Surfaces — the chat tints with the wallpaper like everything else.
        background = scheme.background,
        // [T-dynamic-dark-layering] Dark mode lifts each chat layer one step
        // (input=highest, tools=high, chrome=container): the literal tone ramp
        // put the input at tone 4 — DARKER than the tone-6 background (1.04:1).
        // Light keeps the Apple grouped look (white input on gray page).
        secondaryBg = if (fallback.isDark) scheme.surfaceContainer else scheme.surfaceContainerLow,
        inputBg = if (fallback.isDark) scheme.surfaceContainerHighest else scheme.surfaceContainerLowest,
        inputIconBg = scheme.surfaceContainerHigh,
        inputIconBorder = scheme.outlineVariant.copy(alpha = 0.4f),
        inputBorder = scheme.outlineVariant.copy(alpha = 0.6f),
        toolBg = if (fallback.isDark) scheme.surfaceContainerHigh else scheme.surfaceContainer,
        toolBorder = scheme.outlineVariant.copy(alpha = 0.5f),
        toolCapsuleBg = if (fallback.isDark) scheme.surfaceContainer else scheme.surfaceContainerLow,
        separator = scheme.outlineVariant,
        tableBorder = scheme.outlineVariant.copy(alpha = 0.5f),
        thumbnailBorder = scheme.outlineVariant.copy(alpha = 0.4f),
        sheetHeaderBg = scheme.surfaceContainerLow,
        sheetHeaderBorder = scheme.outlineVariant.copy(alpha = 0.5f),
        inlineCodeBg = scheme.surfaceContainerLow,
        // [T-inline-code-light-contrast] The orange fallback measured 1.97:1 on
        // the light chip; dark keeps the bright terminal orange (6.02:1 there).
        inlineCodeText = if (fallback.isDark) fallback.inlineCodeText else Color(0xFFB25000),
        // Text pairs come with the scheme — contrast already tuned.
        primaryText = scheme.onSurface,
        secondaryText = scheme.onSurfaceVariant,
        // [T-dynamic-tertiary-text] `tertiaryText` was mapped to `scheme.outline`,
        // which M3 defines as the BORDERS/dividers role: on a real Material You
        // light scheme (surface tone 98, outline tone 50) that measures 4.27:1 —
        // below AA for text, and the live-palette gate cannot see it because it
        // only parses the static palettes. Derive it from onSurfaceVariant
        // (tone 30 light / 80 dark: 9.1 / 10.9) with a reduction that keeps the
        // three-tier hierarchy below secondaryText.
        tertiaryText = scheme.onSurfaceVariant.copy(alpha = 0.78f),
        disabledText = scheme.onSurface.copy(alpha = 0.18f),
        sendButtonDisabled = scheme.onSurface.copy(alpha = 0.18f),
        // Accents (see the FAB pairing note above; userBubble text is
        // onSurface, which pairs with primaryContainer in both modes).
        userBubble = scheme.primaryContainer,
        sendButton = scheme.primary,
        link = scheme.primary,
        thinking = scheme.primary,
        thinkingOn = scheme.onPrimary,
        fabAccent = scheme.primaryContainer,
        fabOnAccent = scheme.onPrimaryContainer,
        // Kept on the fallback on purpose: codeBlockBg/Text is terminal-green
        // syntax identity, blockquoteBar/warning* are
        // semantic hues, inputShadow is the dark-mode elevation shadow, and
        // isDark marks the mode for consumers that branch on it.
    )

private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(fontSize = fontSize * factor)

// ─── M3 Typography (Material You 模式用) ───────────────────────────────────

private val M3Typography = Typography(
    displayLarge = TextStyle(fontSize = 57.sp, fontWeight = FontWeight.Normal, lineHeight = 64.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    displayMedium = TextStyle(fontSize = 45.sp, fontWeight = FontWeight.Normal, lineHeight = 52.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Normal, lineHeight = 44.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Normal, lineHeight = 40.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Normal, lineHeight = 36.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal, lineHeight = 32.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = MinisType().lineHeightTight(11f).sp),  // token-literal-ok: Material3 规范拷贝，值由规范决定而非本项目排版意图
)

private fun scaledTypography(factor: Float): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.scale(factor),
        displayMedium = base.displayMedium.scale(factor),
        displaySmall = base.displaySmall.scale(factor),
        headlineLarge = base.headlineLarge.scale(factor),
        headlineMedium = base.headlineMedium.scale(factor),
        headlineSmall = base.headlineSmall.scale(factor),
        titleLarge = base.titleLarge.scale(factor),
        titleMedium = base.titleMedium.scale(factor),
        titleSmall = base.titleSmall.scale(factor),
        bodyLarge = base.bodyLarge.scale(factor),
        bodyMedium = base.bodyMedium.scale(factor),
        bodySmall = base.bodySmall.scale(factor),
        labelLarge = base.labelLarge.scale(factor),
        labelMedium = base.labelMedium.scale(factor),
        labelSmall = base.labelSmall.scale(factor),
    )
}
