package com.openminis.app.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-update-download-fgs] The 2.0.50 crash: `getString(R.string.update_notif_progress, pct)`
 * against a string declaring TWO positional specifiers (`%1$s / %2$s`) threw
 * MissingFormatArgumentException from inside Service.onStartCommand and killed
 * the process the moment the user tapped "download" in Settings → About.
 *
 * Resources.getString(id, vararg) forwards straight to String.format, so a
 * call passing fewer args than the string declares is a guaranteed crash in
 * every locale that carries the string. This test scans the real source tree
 * (same pattern as ResourceBoundaryTest) so the whole class of bug fails the
 * build instead of the user's phone:
 *
 *  1. every `getString(R.string.x, …)` call passes at least as many args as
 *     the string's highest positional index (or its count of bare %s/%d);
 *  2. every locale agrees with `values/` on how many specifiers a key has —
 *     a translation that drops one crashes only the users of that locale.
 */
class StringFormatArgSafetyTest {

    private val specRegex = Regex("%(?:\\d+\\$)?[sSdDfF]")
    private val positionalRegex = Regex("%(\\d+)\\$")
    private val stringTagRegex = Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val callRegex = Regex("""getString\(\s*R\.string\.(\w+)\s*,""")

    private fun specCount(body: String): Int? {
        val specs = specRegex.findAll(body).map { it.value }.toList()
        if (specs.isEmpty()) return null
        val positional = specs.mapNotNull { positionalRegex.matchEntire(it)?.groupValues?.get(1)?.toInt() }
        return if (positional.isNotEmpty()) positional.max() else specs.size
    }

    private fun loadStrings(): Map<String, Map<String, Int>> {
        val table = mutableMapOf<String, MutableMap<String, Int>>()
        for (dir in File("src/main/res").listFiles { f -> f.name.startsWith("values") }!!.sortedBy { it.name }) {
            val strings = File(dir, "strings.xml")
            if (!strings.exists()) continue
            for (m in stringTagRegex.findAll(strings.readText())) {
                if (m.groupValues[2].contains("formatted=\"false\"")) continue
                val n = specCount(m.groupValues[3]) ?: continue
                table.getOrPut(m.groupValues[1]) { mutableMapOf() }[dir.name] = n
            }
        }
        return table
    }

    private fun topLevelArgCount(argsText: String): Int {
        var depth = 0
        var commas = 0
        var inString = false
        var i = 0
        while (i < argsText.length) {
            val c = argsText[i]
            if (c == '"' && (i == 0 || argsText[i - 1] != '\\')) inString = !inString
            if (!inString) {
                when (c) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    ',' -> if (depth == 0) commas++
                }
            }
            i++
        }
        return commas + 1
    }

    @Test
    fun every_locale_agrees_with_the_base_string_on_format_specifier_count() {
        val table = loadStrings()
        val offenders = table.entries
            .filter { (_, locales) -> locales.values.distinct().size > 1 }
            .map { (key, locales) -> "$key: $locales" }
        assertTrue(
            "locales disagree on format specifier counts:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun every_parameterized_getString_call_passes_enough_arguments() {
        val table = loadStrings()
        val offenders = mutableListOf<String>()
        var scanned = 0
        for (kt in File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val text = kt.readText()
            for (m in callRegex.findAll(text)) {
                val key = m.groupValues[1]
                val needed = table[key]?.values?.maxOrNull() ?: continue
                // find the closing paren of this call at depth 0
                var depth = 0
                var i = m.range.last + 1 // at the comma after R.string.key
                while (i < text.length) {
                    val c = text[i]
                    if (c == '(') depth++
                    if (c == ')') {
                        if (depth == 0) break
                        depth--
                    }
                    i++
                }
                val argc = topLevelArgCount(text.substring(m.range.last + 1, i))
                scanned++
                if (argc < needed) {
                    val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                    offenders += "${kt.relativeTo(File("src/main/java"))}:$line  $key passes $argc arg(s), string needs $needed"
                }
            }
        }
        assertTrue(
            "getString calls with fewer args than the string's format specifiers (crashes at runtime):\n" +
                offenders.joinToString("\n") + "\n(scanned $scanned parameterized calls)",
            offenders.isEmpty(),
        )
    }
}
