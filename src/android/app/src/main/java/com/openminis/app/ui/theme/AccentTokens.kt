package com.openminis.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════════
// [T-android-color-contract] THE APP HAS THREE COLOR SYSTEMS. READ EXACTLY ONE.
//
//   1. MaterialTheme.colorScheme  (Theme.kt Light/DarkColorScheme + the
//      wallpaper-derived dynamic scheme)  →  general app chrome: bars, cards,
//      buttons, surfaces. Read via MaterialTheme.colorScheme.* only.
//
//   2. ChatPalette  (ChatColors.kt, LocalChatPalette)  →  chat surfaces and
//      anything that must follow the IN-APP theme override (theme_mode pref)
//      rather than the OS setting: bubbles, composer, tool cards, syntax
//      colors. Read via ChatColors / LocalChatPalette only.
//
//   3. AccentTokens  (this file)  →  hand-tuned iOS-parity decorative accents
//      (settings menu tiles, session category circles, folder glyphs). Read
//      via the NAMED tokens below (IosBlue, IosGreen, …) — never a raw
//      Color(0xFF…) literal — so the [monetAccentTiles] bridge can restyle
//      them when dynamic color is ON.
//
// CONTRACT FOR NEW CODE: do not hardcode new static colors outside these
// systems. A fresh Color(0xFF…) literal in a screen is a bug on three axes —
// it ignores dark mode, ignores the in-app theme override, and opts out of
// dynamic color. Pick the system by what the surface is (chrome → 1, chat /
// theme-following → 2, decorative accent → 3); if none fits, the default is
// MaterialTheme.colorScheme. Raw literals survive ONLY inside the three
// definition files themselves (Theme.kt, ChatColors.kt, AccentTokens.kt).
// ═══════════════════════════════════════════════════════════════════════════

// [T-android-dynamic-accent-icons] The single accent bridge between the
// hand-tuned iOS-parity chrome accents and the wallpaper scheme. Every icon
// decoration that used to hardcode an iOS system color — the settings menu's
// colored circle tiles, the session list's category circles and folder glyphs,
// the folder-picker chips — resolves through the table below instead of each
// screen re-deriving "am I in dynamic mode" itself.
//
//   Dynamic color OFF -> every function returns its static input unchanged
//                        (byte-identical to the pre-dynamic rendering; the
//                        scheme is never even read).
//   Dynamic color ON  -> a tile hue resolves to its MaterialTheme
//                        .colorScheme role; hues NOT in the table keep their
//                        static color because their hue IS the message.
//
// The wallpaper gate ([LocalMonetDynamic]) is provided by MinisTheme from the
// same `chat.dynamicColor` pref the AppearanceScreen toggle writes — this file
// is the reader, not a second truth source.

// [v3-P2-5] Named iOS system palette tokens — the single source the settings
// chrome's row icon colors / content colors reference (instead of each screen
// repeating a Color(0xFF...) literal) and from which the dynamic-accent table
// below is built. Declare BEFORE monetAccentTiles so the table initializer
// sees them.
val IosBlue = Color(0xFF007AFF)
val IosGreen = Color(0xFF34C759)
val IosOrange = Color(0xFFFF9500)
val IosIndigo = Color(0xFF5856D6)
val IosPurple = Color(0xFFAF52DE)
val IosTeal = Color(0xFF30B0C7)
val IosRed = Color(0xFFFF3B30)
val IosGray = Color(0xFF8E8E93)

// Accent hues that follow the wallpaper when dynamic color is on. The first
// block is the iOS system palette the settings chrome was tuned against; the
// rest is the session category palette (SessionListScreen.categoryStyle), so
// its circles, glyphs, spinners and picker chips restyle from the same table.
// Red (destructive) and systemGray (neutral/informational) deliberately stay
// static: their hue IS the message.
internal val monetAccentTiles = setOf(
    IosBlue,           // iOS systemBlue / session writing + education
    IosGreen,          // iOS systemGreen / session chat
    IosOrange,         // iOS systemOrange / session paused-badge family
    IosIndigo,         // iOS indigo / session analysis
    IosPurple,         // iOS purple
    IosTeal,           // iOS teal / session research
    Color(0xFF5AC8FA), // iOS light teal
    Color(0xFFF09A37), // session code + travel orange
    Color(0xFF3478F6), // session writing + education blue
    Color(0xFFFF2D55), // iOS systemPink / session creative + design
    Color(0xFF9B59B6), // session math purple
    Color(0xFF00BCD4), // session translation cyan
    Color(0xFF00C7BE), // session finance teal
    Color(0xFFFFCC00), // iOS systemYellow / session productivity
    Color(0xFF8B6914), // session support khaki
)

// Deliberately NOT in the table: 0xFFFF3B30 (iOS systemRed — session health,
// destructive confirmations), 0xFF8E8E93 (iOS systemGray — session "other",
// neutral chrome). They stay static in both modes.

/** Resolved colors for one accent tile, as [resolveMonetTile] produces them. */
internal data class MonetTileColors(
    /** Tint for a plain (chip-less) icon glyph. */
    val tint: Color,
    /** Background of a solid accent tile (colored circle / rounded square). */
    val tile: Color,
    /** Glyph color paired with [tile]. */
    val content: Color,
)

/**
 * Pure core of the accent bridge, so the mapping (table membership x gate x
 * scheme roles) is unit-testable without composition.
 *
 * Dynamic ON + a table hue -> the scheme's primary family: the glyph reads
 * `primary`, the tile `primaryContainer`, the glyph-on-tile
 * `onPrimaryContainer` — exactly the pairings Material You derives those slots
 * FOR, and the same chip contract the settings rows already used. Anything
 * else (gate off, or a hue outside [monetAccentTiles]) returns the static
 * input with the White glyph the tiles hardcoded before this slot existed;
 * the passed scheme is untouched on that path.
 */
internal fun resolveMonetTile(
    static: Color,
    dynamicActive: Boolean,
    scheme: ColorScheme,
): MonetTileColors =
    if (dynamicActive && static in monetAccentTiles) {
        MonetTileColors(
            tint = scheme.primary,
            tile = scheme.primaryContainer,
            content = scheme.onPrimaryContainer,
        )
    } else {
        MonetTileColors(tint = static, tile = static, content = Color.White)
    }

/** Tint for plain (chip-less) icons: wallpaper accent in dynamic mode. */
@Composable
fun monetIconTint(static: Color): Color =
    resolveMonetTile(static, LocalMonetDynamic.current, MaterialTheme.colorScheme).tint

/**
 * Background of a solid accent tile (the 30dp settings circle, the skill
 * action badge): `primaryContainer` in dynamic mode, the static color
 * otherwise. Pair the glyph with [monetIconTileContentColor] so the
 * on/off contrast stays tuned.
 */
@Composable
fun monetIconTileColor(static: Color): Color =
    resolveMonetTile(static, LocalMonetDynamic.current, MaterialTheme.colorScheme).tile

/**
 * Glyph color paired with [monetIconTileColor]: `onPrimaryContainer` in
 * dynamic mode (the hardcoded White would wash out on a dark-mode pastel),
 * White otherwise — matching every tile that existed before the bridge.
 */
@Composable
fun monetIconTileContentColor(static: Color): Color =
    resolveMonetTile(static, LocalMonetDynamic.current, MaterialTheme.colorScheme).content
