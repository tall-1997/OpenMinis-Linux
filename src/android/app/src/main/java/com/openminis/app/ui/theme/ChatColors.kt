package com.openminis.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// Semantic chat colors mirroring iOS ChatColors (AIChatView.swift).
// Resolved from LocalChatPalette, which is provided by MinisTheme.
//
// [T-xhy-skin] Global reskin (2026-11-22): both palettes now carry the
// 小黄鱼 (CiliDownloader) "01 暗色·炭粉" dark palette, extracted verbatim from
// that project's values-night/colors.xml (authoritative skin source). The
// light palette intentionally wears the same dark skin with isDark=true so
// every isDark behavioral branch (menu ink, math render, brand accents)
// picks dark-side contrast — the user asked for the dark skin to cover 100%
// of pages/buttons/text. Mapping highlights:
//   bg_dark        #0A0609 -> background / codeBlockBg(#160C13 shimmer-b)
//   surface_dark   #1A1116 -> secondaryBg / inputBg / toolBg / sheetHeaderBg
//   nav_bg         #120A0F -> inputIconBg / toolCapsuleBg
//   thumb_bg       #2A1620 -> inlineCodeBg
//   primary        #FFB3D1 -> sendButton / thinking
//   primary_dark   #FF7AD9 -> link / fabAccent / blockquoteBar
//   card_gold_top  #3A1F2B -> userBubble (gradient face color)
//   text_light     #FFEFF6 -> primaryText
//   text_dim       #D3BDC9 -> secondaryText
//   text_soft      #B9A3B0 -> tertiaryText
//   gold_line      #59FFB3D1 -> toolBorder / thumbnailBorder
//   gold_text      #FFD9E8 -> inlineCodeText
//   error #FF6B6B / ok #4CAF50 handled at the token level (ChatScreenSections).
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
    val warningBg: Color,
    val warningText: Color,
    val tableBorder: Color,
    val inputShadow: Color,
    val toastBg: Color,
    val thumbnailBorder: Color,
    val sheetHeaderBg: Color,
    val sheetHeaderBorder: Color,
    val fabAccent: Color,
    val fabOnAccent: Color,
)

// [T-xhy-skin] The 小黄鱼 dark skin applied globally. isDark=true so contrast
// branches (MinisMenu ink, KaTeX, brand accents) behave as dark everywhere.
val LightChatPalette = ChatPalette(
    isDark = true,
    background = Color(0xFF0A0609),
    secondaryBg = Color(0xFF1A1116),
    inputBg = Color(0xFF1A1116),
    inputIconBg = Color(0xFF120A0F),
    inputIconBorder = Color(0x4DFFB3D1),
    inputBorder = Color(0x26FFB3D1),
    primaryText = Color(0xFFFFEFF6),
    secondaryText = Color(0xFFD3BDC9),
    tertiaryText = Color(0xFFB9A3B0),
    disabledText = Color(0x59FFEFF6),
    userBubble = Color(0xFF3A1F2B),
    toolBg = Color(0xFF1A1116),
    toolBorder = Color(0x59FFB3D1),
    toolCapsuleBg = Color(0xFF120A0F),
    separator = Color(0x14FFFFFF),
    sendButton = Color(0xFFFFB3D1),
    sendButtonDisabled = Color(0x26FFB3D1),
    codeBlockBg = Color(0xFF160C13),
    codeBlockText = Color(0xFFFFB3D1),
    inlineCodeBg = Color(0xFF2A1620),
    inlineCodeText = Color(0xFFFFD9E8),
    link = Color(0xFFFF7AD9),
    blockquoteBar = Color(0x80FF7AD9),
    thinking = Color(0xFFFFB3D1),
    warningBg = Color(0x1AFFF3C4),
    warningText = Color(0xFFFFF3C4),
    tableBorder = Color(0x33FFB3D1),
    inputShadow = Color(0x80000000),
    toastBg = Color(0x2EFFB3D1),
    thumbnailBorder = Color(0x4DFFB3D1),
    sheetHeaderBg = Color(0xFF1A1116),
    sheetHeaderBorder = Color(0x33FFB3D1),
    fabAccent = Color(0xFFFF7AD9),
    fabOnAccent = Color(0xFF2A0F1A),
)

// [T-xhy-skin] Same skin as Light — the reskin is global by design.
val DarkChatPalette = ChatPalette(
    isDark = true,
    background = Color(0xFF0A0609),
    secondaryBg = Color(0xFF1A1116),
    inputBg = Color(0xFF1A1116),
    inputIconBg = Color(0xFF120A0F),
    inputIconBorder = Color(0x4DFFB3D1),
    inputBorder = Color(0x26FFB3D1),
    primaryText = Color(0xFFFFEFF6),
    secondaryText = Color(0xFFD3BDC9),
    tertiaryText = Color(0xFFB9A3B0),
    disabledText = Color(0x59FFEFF6),
    userBubble = Color(0xFF3A1F2B),
    toolBg = Color(0xFF1A1116),
    toolBorder = Color(0x59FFB3D1),
    toolCapsuleBg = Color(0xFF120A0F),
    separator = Color(0x14FFFFFF),
    sendButton = Color(0xFFFFB3D1),
    sendButtonDisabled = Color(0x26FFB3D1),
    codeBlockBg = Color(0xFF160C13),
    codeBlockText = Color(0xFFFFB3D1),
    inlineCodeBg = Color(0xFF2A1620),
    inlineCodeText = Color(0xFFFFD9E8),
    link = Color(0xFFFF7AD9),
    blockquoteBar = Color(0x80FF7AD9),
    thinking = Color(0xFFFFB3D1),
    warningBg = Color(0x1AFFF3C4),
    warningText = Color(0xFFFFF3C4),
    tableBorder = Color(0x33FFB3D1),
    inputShadow = Color(0x80000000),
    toastBg = Color(0x2EFFB3D1),
    thumbnailBorder = Color(0x4DFFB3D1),
    sheetHeaderBg = Color(0xFF1A1116),
    sheetHeaderBorder = Color(0x33FFB3D1),
    fabAccent = Color(0xFFFF7AD9),
    fabOnAccent = Color(0xFF2A0F1A),
)

val LocalChatPalette = compositionLocalOf { LightChatPalette }

// Short accessor: ChatColors.primaryText instead of LocalChatPalette.current.primaryText
val ChatColors: ChatPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalChatPalette.current
