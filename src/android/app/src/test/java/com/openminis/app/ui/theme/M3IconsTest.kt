package com.openminis.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Contract tests for the bidirectional icon-variant normalizer [m3Icon].
 *
 * The regression these tests pin down: PR #126 wired SettingsRow through
 * m3Icon, but settings call sites pass Outlined vectors while the old map was
 * keyed on Filled — every lookup missed and the dynamic-color toggle had no
 * visible effect on settings icons. The normalizer now accepts EITHER variant
 * and returns the one the active visual system wants:
 *
 *   dynamic color ON  (M3 Expressive) -> Outlined
 *   dynamic color OFF (Apple)         -> Filled
 */
class M3IconsTest {

    /** (filled, outlined) pairs covering chat-style and settings-style icons. */
    private val pairs: List<Pair<ImageVector, ImageVector>> = listOf(
        Icons.Filled.Lock to Icons.Outlined.Lock,
        Icons.Filled.Build to Icons.Outlined.Build,
        Icons.Filled.Check to Icons.Outlined.Check,
        Icons.Filled.Info to Icons.Outlined.Info,
        Icons.Filled.Warning to Icons.Outlined.Warning,
        Icons.Filled.Share to Icons.Outlined.Share,
        Icons.Filled.Settings to Icons.Outlined.Settings,
        Icons.Filled.Terminal to Icons.Outlined.Terminal,
        Icons.Filled.Psychology to Icons.Outlined.Psychology,
        Icons.Filled.Palette to Icons.Outlined.Palette,
        Icons.Filled.FolderShared to Icons.Outlined.FolderShared,
        Icons.Filled.Inventory2 to Icons.Outlined.Inventory2,
    )

    /** Dynamic mode: Filled input maps to Outlined (chat-style call sites). */
    @Test
    fun dynamicMode_filledMapsToOutlined() {
        for ((filled, outlined) in pairs) {
            assertEquals(outlined, m3Icon(filled, dynamicActive = true))
        }
    }

    /** Dynamic mode: Outlined input passes through unchanged (settings-style). */
    @Test
    fun dynamicMode_outlinedPassesThrough() {
        for ((_, outlined) in pairs) {
            assertSame(outlined, m3Icon(outlined, dynamicActive = true))
        }
    }

    /**
     * Apple mode: Outlined input maps back to Filled — THE fix for the
     * "settings icons never change" regression.
     */
    @Test
    fun appleMode_outlinedMapsToFilled() {
        for ((filled, outlined) in pairs) {
            assertEquals(filled, m3Icon(outlined, dynamicActive = false))
        }
    }

    /** Apple mode: Filled input passes through unchanged. */
    @Test
    fun appleMode_filledPassesThrough() {
        for ((filled, _) in pairs) {
            assertSame(filled, m3Icon(filled, dynamicActive = false))
        }
    }

    /** AutoMirrored pairs normalize in both directions. */
    @Test
    fun autoMirrored_pairsNormalize() {
        assertEquals(
            Icons.AutoMirrored.Outlined.ArrowBack,
            m3Icon(Icons.AutoMirrored.Filled.ArrowBack, dynamicActive = true),
        )
        assertEquals(
            Icons.AutoMirrored.Filled.ArrowBack,
            m3Icon(Icons.AutoMirrored.Outlined.ArrowBack, dynamicActive = false),
        )
        assertEquals(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            m3Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, dynamicActive = true),
        )
        assertEquals(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            m3Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, dynamicActive = false),
        )
    }

    /** Unregistered icons pass through unchanged in both modes. */
    @Test
    fun unregisteredIcon_passesThrough() {
        val unregistered = ImageVector.Builder(
            name = "unregistered-test-vector",
            defaultWidth = 1.dp,
            defaultHeight = 1.dp,
            viewportWidth = 1f,
            viewportHeight = 1f,
        ).build()
        assertSame(unregistered, m3Icon(unregistered, dynamicActive = true))
        assertSame(unregistered, m3Icon(unregistered, dynamicActive = false))
    }

    /** The normalizer is idempotent: double wrapping must be a no-op. */
    @Test
    fun idempotent_doubleWrapIsNoOp() {
        for (dynamic in listOf(true, false)) {
            for ((filled, outlined) in pairs) {
                for (icon in listOf(filled, outlined)) {
                    val once = m3Icon(icon, dynamic)
                    assertSame(once, m3Icon(once, dynamic))
                }
            }
        }
    }

    /** Filled/Default are the same vectors — both aliases behave identically. */
    @Test
    fun defaultAlias_matchesFilled() {
        assertEquals(
            m3Icon(Icons.Filled.Lock, dynamicActive = true),
            m3Icon(Icons.Default.Lock, dynamicActive = true),
        )
        assertEquals(
            m3Icon(Icons.Filled.Lock, dynamicActive = false),
            m3Icon(Icons.Default.Lock, dynamicActive = false),
        )
    }

    /** The two directions are inverses: normalizing twice returns the input. */
    @Test
    fun roundTrip_bothModes() {
        for ((filled, outlined) in pairs) {
            assertSame(filled, m3Icon(m3Icon(filled, dynamicActive = true), dynamicActive = false))
            assertSame(outlined, m3Icon(m3Icon(outlined, dynamicActive = false), dynamicActive = true))
        }
    }

    // ─── [T-android-m3icon-full-coverage] source-scan guard ──────────────────

    /**
     * Scans every `ui/` source file for `m3Icon(Icons.…)` references and
     * asserts each referenced icon IS in the mapping table — the full-coverage
     * form of the "M3IconsTest 抽样把关" contract in M3Icons.kt's doc.
     *
     * Membership is probed through the public API, not by reading the private
     * table: in dynamic mode a registered Filled vector normalizes to its
     * Outlined pair (different object), an unregistered one passes through
     * identity — so `m3Icon(icon, dynamic) !== icon` is exactly "the table has
     * this key". The vectors themselves are resolved reflectively the same way
     * the compiler does: `Icons.Filled.Lock` is an extension property `Lock`
     * declared in `androidx.compose.material.icons.filled.LockKt`, taking the
     * `Icons.Filled` object as its single receiver.
     *
     * A failure lists the exact missing references so the fix is to add the
     * (Filled, Outlined) pair to iconPairs — not to change the call site.
     */
    @Test
    fun `every m3Icon reference in ui sources is registered in the mapping table`() {
        val root = java.io.File("src/main/java/com/openminis/app/ui")
        org.junit.Assert.assertTrue(
            "source root not found — this test locates sources relative to the " +
                "module dir (cwd=${java.io.File(".").absolutePath})",
            root.isDirectory,
        )

        val referenced = sortedSetOf<String>()
        val pattern = Regex(
            """m3Icon\(\s*Icons\.(AutoMirrored\.)?(Filled|Outlined|Default)\.([A-Za-z0-9_]+)""",
        )
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                // Strip comments: the correct pattern is documented by NAMING
                // it (see the m3Icon doc), and those mentions must not trip
                // the guard.
                val code = f.readText()
                    .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                    .lineSequence()
                    .map { it.substringBefore("//") }
                    .joinToString("\n")
                pattern.findAll(code).forEach { m ->
                    val automirrored = m.groupValues[1].isNotEmpty()
                    val variant = if (m.groupValues[2] == "Default") "Filled" else m.groupValues[2]
                    referenced += (if (automirrored) "AutoMirrored." else "") +
                        variant + "." + m.groupValues[3]
                }
            }
        org.junit.Assert.assertTrue(
            "scanner found no m3Icon(Icons…) references — regex or paths broke",
            referenced.isNotEmpty(),
        )

        val unmapped = referenced.filter { ref ->
            val icon = resolveIcon(ref)
            // Filled (and Default) inputs are mapped in DYNAMIC mode
            // (Filled→Outlined); Outlined inputs are mapped in APPLE mode
            // (Outlined→Filled). Identity on the probe = key missing.
            val isOutlined = ref.startsWith("Outlined.") || ref.startsWith("AutoMirrored.Outlined.")
            m3Icon(icon, dynamicActive = !isOutlined) === icon
        }
        org.junit.Assert.assertTrue(
            buildString {
                append("m3Icon call sites reference icons that are NOT in the ")
                append("M3Icons.kt iconPairs table — add the (Filled, Outlined) ")
                append("pair so the dynamic-color toggle restyles them:\n")
                unmapped.forEach { append("  ").append(it).append('\n') }
            },
            unmapped.isEmpty(),
        )
    }

    /**
     * Resolves a scanned reference like "Filled.Lock" /
     * "AutoMirrored.Outlined.KeyboardArrowRight" to its actual ImageVector by
     * the same mechanics the compiler uses for `Icons.<path>.<Name>`: a
     * top-level extension property `Name` on the nested `Icons.…` object,
     * compiled to a static `get<Name>` method in
     * `androidx.compose.material.icons[.automirrored].<variant>.<Name>Kt`.
     */
    private fun resolveIcon(ref: String): ImageVector {
        val automirrored = ref.startsWith("AutoMirrored.")
        val rest = ref.removePrefix("AutoMirrored.")
        val variant = rest.substringBefore('.').lowercase()   // filled | outlined
        val name = rest.substringAfter('.')

        // Walk the nested-object chain: Icons → (AutoMirrored)? → (Filled|Outlined).
        var className = "androidx.compose.material.icons.Icons"
        var receiver: Any? = null
        for (part in buildList {
            if (automirrored) add("AutoMirrored")
            add(variant.replaceFirstChar { it.uppercase() })
        }) {
            className += '$' + part
            receiver = Class.forName(className).getDeclaredField("INSTANCE").get(null)
        }

        val pkg = "androidx.compose.material.icons." +
            (if (automirrored) "automirrored." else "") + variant
        val holder = Class.forName("$pkg.${name}Kt")
        val getter = holder.declaredMethods.firstOrNull {
            it.name == "get$name" && it.parameterTypes.size == 1
        } ?: throw IllegalStateException(
            "could not resolve Icons.${ref} — expected $pkg.${name}Kt.get$name; " +
                "if this icon moved files, extend the resolver",
        )
        return getter.invoke(null, receiver) as ImageVector
    }
}
