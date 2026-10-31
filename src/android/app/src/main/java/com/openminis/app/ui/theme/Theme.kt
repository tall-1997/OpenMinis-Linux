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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
// Original #0066CC reads as glaring on 1000nit+ displays. #5A8AD2 keeps
// the same hue/lightness with ~30% less saturation per WCAG best practices.
private val TealPrimary = Color(0xFF5A8AD2)
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
    surfaceContainerLowest = TealBackground,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF7F7FA),
    surfaceContainerHighest = Color(0xFFF7F7FA),
    outline = TealOutline,
    outlineVariant = TealOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = TealDarkPrimary,
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
    surfaceContainerLowest = TealDarkBackground,
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF2C2C2E),
    outline = TealDarkOutline,
    outlineVariant = TealDarkOutline,
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

// [T-two-styles] 动态取色模式下 Icon 走 Rounded 风格（M3 Material Symbols）。
// 非动态模式继续用 Default/Filled（Apple SF Symbols 风格）。默认为 false。
val LocalUseRoundedIcons = compositionLocalOf { false }

// True only while the wallpaper scheme is actually in effect (dynamic color ON
// and Android 12+). Consumers use it to swap hand-tuned iOS-parity accents for
// scheme roles without re-deriving the gate themselves.
val LocalMonetDynamic = compositionLocalOf { false }

// The accent bridge that consumes this gate (monetAccentTiles, monetIconTint,
// monetIconTileColor/ContentColor) lives in AccentTokens.kt.

// Apple-style shape system — rounded, friendly, iOS-like
// M3 Expressive 在动态取色时替换（见 MinisShapesM3）
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
    //   非动态 → Apple 风格（圆角大、Roboto 字重偏轻、自有 ChatPalette）
    //   动态   → M3 Expressive（官方 Shape tokens、Material You 全套色板+文字）
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

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes,
        typography = typography,
    ) {
        CompositionLocalProvider(
            LocalChatPalette provides chatPalette,
            LocalMonetDynamic provides dynamicActive,
            LocalUseRoundedIcons provides dynamicActive,
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
 *  - toastBg = primary at the same 0x2E alpha both static palettes use.
 *
 * Semantic slots (warning orange, syntax green, blockquote bar) and the
 * dark-mode elevation shadow stay on [fallback] — their hue IS the message.
 *
 * Pure so the rule is unit-testable alongside the static palettes.
 */
internal fun dynamicChatPalette(scheme: ColorScheme, fallback: ChatPalette): ChatPalette =
    fallback.copy(
        // Surfaces — the chat tints with the wallpaper like everything else.
        background = scheme.background,
        secondaryBg = scheme.surfaceContainerLow,
        inputBg = scheme.surfaceContainerLowest,
        inputIconBg = scheme.surfaceContainerHigh,
        inputIconBorder = scheme.outlineVariant.copy(alpha = 0.4f),
        inputBorder = scheme.outlineVariant.copy(alpha = 0.6f),
        toolBg = scheme.surfaceContainer,
        toolBorder = scheme.outlineVariant.copy(alpha = 0.5f),
        toolCapsuleBg = scheme.surfaceContainerLow,
        separator = scheme.outlineVariant,
        tableBorder = scheme.outlineVariant.copy(alpha = 0.5f),
        thumbnailBorder = scheme.outlineVariant.copy(alpha = 0.4f),
        sheetHeaderBg = scheme.surfaceContainerLow,
        sheetHeaderBorder = scheme.outlineVariant.copy(alpha = 0.5f),
        inlineCodeBg = scheme.surfaceContainerLow,
        // Text pairs come with the scheme — contrast already tuned.
        primaryText = scheme.onSurface,
        secondaryText = scheme.onSurfaceVariant,
        tertiaryText = scheme.outline,
        disabledText = scheme.onSurface.copy(alpha = 0.18f),
        sendButtonDisabled = scheme.onSurface.copy(alpha = 0.18f),
        // Accents (see the FAB pairing note above; userBubble text is
        // onSurface, which pairs with primaryContainer in both modes).
        userBubble = scheme.primaryContainer,
        sendButton = scheme.primary,
        link = scheme.primary,
        thinking = scheme.primary,
        toastBg = scheme.primary.copy(alpha = 0.18f),
        fabAccent = scheme.primaryContainer,
        fabOnAccent = scheme.onPrimaryContainer,
        // Kept on the fallback on purpose: codeBlockBg/Text and inlineCodeText
        // are terminal-green syntax identity, blockquoteBar/warning* are
        // semantic hues, inputShadow is the dark-mode elevation shadow, and
        // isDark marks the mode for consumers that branch on it.
    )

private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(fontSize = fontSize * factor)

// ─── M3 Typography (Material You 模式用) ───────────────────────────────────

private val M3Typography = Typography(
    displayLarge = TextStyle(fontSize = 57.sp, fontWeight = FontWeight.Normal, lineHeight = 64.sp),
    displayMedium = TextStyle(fontSize = 45.sp, fontWeight = FontWeight.Normal, lineHeight = 52.sp),
    displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Normal, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Normal, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Normal, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
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
