package com.openminis.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// Semantic chat colors mirroring iOS ChatColors (AIChatView.swift).
// Resolved from LocalChatPalette, which is provided by MinisTheme.
//
// iOS reference:
//   systemBackground        -> background
//   secondarySystemBackground -> secondaryBg
//   tertiarySystemFill      -> userBubble
//   tertiarySystemGroupedBackground -> toolBg
//   label                   -> primaryText
//   secondaryLabel          -> secondaryText
//   tertiaryLabel           -> tertiaryText
//   quaternaryLabel         -> sendButtonDisabled
//   separator               -> border
//   systemGray6             -> inlineCodeBg / toolCapsuleBg
@Immutable
data class ChatPalette(
    val isDark: Boolean,
    val background: Color,
    val secondaryBg: Color,
    val inputBg: Color,
    val inputIconBg: Color,
    val inputIconBorder: Color,
    val inputBorder: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val disabledText: Color,
    val userBubble: Color,
    val toolBg: Color,
    val toolBorder: Color,
    val toolCapsuleBg: Color,
    val separator: Color,
    val sendButton: Color,
    val sendButtonDisabled: Color,
    val codeBlockBg: Color,
    val codeBlockText: Color,
    val inlineCodeBg: Color,
    val inlineCodeText: Color,
    val link: Color,
    val blockquoteBar: Color,
    val thinking: Color,
    /** Foreground paired with [thinking] fills (selected chip). Static keeps the
     *  White the chip hardcoded; dynamic derives onPrimary so White never sits on
     *  a dark-mode tone-80 primary (1.70:1 measured 2026-11-23). */
    val thinkingOn: Color,
    val warningBg: Color,
    val warningText: Color,
    val tableBorder: Color,
    val inputShadow: Color,
    val thumbnailBorder: Color,
    val sheetHeaderBg: Color,
    val sheetHeaderBorder: Color,
    val fabAccent: Color,
    val fabOnAccent: Color,
)

val LightChatPalette = ChatPalette(
    isDark = false,
    background = Color.White,
    secondaryBg = Color(0xFFF2F2F7),
    inputBg = Color.White,
    inputIconBg = Color(0xFFF2F2F7),
    inputIconBorder = Color.Transparent,
    inputBorder = Color(0x4D3C3C43),
    primaryText = Color(0xFF000000),
    // [T-chat-text-contrast] alpha-blended iOS label grays measured 3.44 / 1.74:1
    // on white (2026-11-23 audit) — below WCAG AA 4.5 for text. Solid iOS-hue
    // grays that pass while keeping the label hierarchy (secondary darker than
    // tertiary; both well under primary black).
    secondaryText = Color(0xFF636367),
    tertiaryText = Color(0xFF76767A),
    disabledText = Color(0x2E3C3C43),
    userBubble = Color(0x1E787880),
    toolBg = Color(0xFFF2F2F7),
    toolBorder = Color(0x14000000),
    toolCapsuleBg = Color(0xFFF2F2F7),
    separator = Color(0x4D3C3C43),
    sendButton = Color(0xFF000000),
    sendButtonDisabled = Color(0x2E3C3C43),
    codeBlockBg = Color(0xFF000000),
    codeBlockText = Color(0xFF34C759),
    inlineCodeBg = Color(0xFFF2F2F7),
    // [T-inline-code-light-contrast] #FF9500 on #F2F2F7 measured 1.97:1 —
    // unreadable. Same hue family, darkened to pass 4.5 (4.66 measured).
    inlineCodeText = Color(0xFFB25000),
    // [T-action-blue-text] #007AFF measured 4.02:1 as text on white. #0066CC
    // keeps the Action Blue hue and passes 4.5 (5.57).
    link = Color(0xFF0066CC),
    blockquoteBar = Color(0x80FF9500),
    thinking = Color(0xFF0066CC),
    // [T-thinking-on] See the slot KDoc; static keeps White byte-identical.
    thinkingOn = Color.White,
    warningBg = Color(0x14FF9500),
    // [T-warning-text-contrast] α0.45 over the warn-tinted card measured
    // 3.32:1 — below AA. Solid neutral ink keeps the muted look and passes
    // (measured 8.05:1 over warningBg composited on the page).
    warningText = Color(0xFF3C3C43),
    tableBorder = Color(0x1F000000),
    inputShadow = Color.Transparent,
    thumbnailBorder = Color(0x33808080),
    sheetHeaderBg = Color(0xFFFFFFFF),
    sheetHeaderBorder = Color(0x1A000000),
    fabAccent = Color(0xFFB7AF96),
    // [T-fab-icon-contrast] White on the beige #B7AF96 measured 2.19:1 (below
    // the 3.0 non-text bar). Ink on the same beige = 7.77.
    fabOnAccent = Color(0xFF1C1C1E),
)

// T153: Android-specific dark palette tweaks. iOS borrows the system
// palette (#1C1C1E / #2C2C2E etc.) which reads as "layered dark grey"
// on a 1000+ nit display, but on a typical Android phone (Pixel 6 ≈
// 500 nits, mid-range OEMs even less) those layers crush together
// into a single near-black wash and the user can't tell tool capsules
// from background or input from message list. Lift the non-background
// layers ~6-10% so the contrast survives the brightness gap; the pure
// `background` itself stays #000 because every other color is keyed
// to "darker than this".
val DarkChatPalette = ChatPalette(
    isDark = true,
    background = Color(0xFF000000),
    secondaryBg = Color(0xFF26262A),
    inputBg = Color(0xFF2C2C30),
    inputIconBg = Color(0xFF1C1C1E),
    inputIconBorder = Color(0xFF595959),
    inputBorder = Color(0x40545458),
    primaryText = Color(0xFFFFFFFF),
    secondaryText = Color(0x99EBEBF5),
    // [T-chat-text-contrast-dark] 30% alpha measured 2.27:1 on #000; 55% = 5.42.
    tertiaryText = Color(0x8DEBEBF5),
    disabledText = Color(0x2EEBEBF5),
    // [T-android-user-bubble-dark-contrast] The old 0x247676D7 was a 14%-alpha
    // translucent blue-grey that washed out to near-invisible on the #000 chat
    // background on real (≈500-nit) displays — exactly the "crush to a near-black
    // wash" failure the palette header warns about. Use an OPAQUE cool slate-blue
    // so the user's own messages read as a distinct accent; white primaryText
    // stays legible on it.
    userBubble = Color(0xFF2F3A5C),
    toolBg = Color(0xFF3A3A3F),
    toolBorder = Color(0x40545458),
    toolCapsuleBg = Color(0xFF28282C),
    separator = Color(0x99545458),
    sendButton = Color(0xFFFFFFFF),
    sendButtonDisabled = Color(0x2EEBEBF5),
    codeBlockBg = Color(0xFF262626),
    codeBlockText = Color(0xFF8CF38C),
    // [T-inline-code-dark-bg-android] Lifted into the T153 ramp — the old
    // #1C1C1E chip was invisible on the #000 chat background (the comment
    // block above explains why non-background layers need the ~6-10% lift;
    // this one was missed). #34343A sits between inputBg (#2C2C30) and
    // toolBg (#3A3A3F), clearly above codeBlockBg (#262626) so small inline
    // chips read against both the wash and fenced blocks.
    inlineCodeBg = Color(0xFF34343A),
    inlineCodeText = Color(0xFFFF9F0A),
    link = Color(0xFF0A84FF),
    blockquoteBar = Color(0x80FF9F0A),
    thinking = Color(0xFF0A84FF),
    // [T-thinking-on] White on #0A84FF measures 3.65:1 (below AA for text).
    // Ink on the same blue measures 5.76:1.
    thinkingOn = Color(0xFF1C1C1E),
    warningBg = Color(0x14FF9F0A),
    // [T-warning-text-dark] 45% alpha measured 4.43:1 — one point short of AA.
    warningText = Color(0x76FFFFFF),
    tableBorder = Color(0xFF38383A),
    inputShadow = Color(0x80000000),
    thumbnailBorder = Color(0x20545458),
    sheetHeaderBg = Color(0xFF2C2C2E),
    sheetHeaderBorder = Color(0x33FFFFFF),
    fabAccent = Color(0xFF504C42),
    fabOnAccent = Color.White,
)

val LocalChatPalette = compositionLocalOf { LightChatPalette }

// Short accessor: ChatColors.primaryText instead of LocalChatPalette.current.primaryText
val ChatColors: ChatPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalChatPalette.current
