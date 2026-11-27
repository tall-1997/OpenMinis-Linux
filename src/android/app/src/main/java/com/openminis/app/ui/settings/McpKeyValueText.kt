package com.openminis.app.ui.settings

// [T-lemon-integration] Pure key/value helpers extracted from
// MCPIntegrationsScreen when the lemon catalog launcher pushed that file past
// its architecture ceiling. Bodies unchanged.
internal fun mapToLines(map: Map<String, String>?, joiner: String): String {
    if (map.isNullOrEmpty()) return ""
    return map.entries.joinToString("\n") { "${it.key}$joiner${it.value}" }
}

internal fun parseKeyValueLines(text: String, sep: Char): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val idx = trimmed.indexOf(sep)
        if (idx <= 0) continue
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        if (key.isNotEmpty()) out[key] = value
    }
    return out
}
