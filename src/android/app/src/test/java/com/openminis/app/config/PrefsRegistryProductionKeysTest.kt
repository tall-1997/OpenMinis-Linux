package com.openminis.app.config

import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-prefs-unregistered-keys] Pins the keys production code reads through a
 * `PrefsHandle` against the generated registry.
 *
 * Why this exists: #410 moved every store onto `Prefs.file(name)`, which throws
 * `IllegalArgumentException("key '…' is not registered")` for any key the
 * generator never saw. Three stores shipped keys it could not see and crashed
 * at runtime — the tool-limits page and the sandbox command lock (five literal
 * keys), voice read-aloud (`readReplies`, shadowed by a same-named constant),
 * the background settings bridge, and the MCP shell policy. Unit tests never
 * touched those paths, so the whole suite stayed green.
 *
 * `ui_prefs/fab_swapped` (DualFabRow in SessionListScreen) was the next one —
 * a real crash log from a Redmi 25102RKBEC on 2026-11-22. The registry now
 * carries it and the handle reads it without throwing.
 */
class PrefsRegistryProductionKeysTest {

    private val keysByFile = mapOf(
        "minis_tool_limits" to listOf(
            "idleShellCount",
            "idleShellMinutes",
            "commandConcurrency",
            "heavyConcurrency",
            "queueTimeoutSec",
            "autoConcurrency",
            "toolCallConcurrency",
        ),
        "voice_prefs" to listOf("readReplies"),
        "background_settings" to listOf(
            "enhanced_background_execution",
            "background_notifications_enabled",
        ),
        "mcp_server" to listOf("shell_policy"),
        "ui_prefs" to listOf("fab_swapped"),
        // [T-daily-refresh-key] Read through a constructor-injected PrefsHandle
        // (ModelCatalogCache), which the generator cannot attribute; unregistered
        // it threw at every cold start and the daily model refresh never ran.
        "provider_config" to listOf("lastModelsRefreshDate"),
        // [T-prefs-accessor-callsites] Read through getAppearancePrefs(context),
        // which the generator cannot attribute — both crashed on device before.
        "appearance_prefs" to listOf("chat.showTurnChangesCard", "chat.showFullThinking"),
    )

    @Test
    fun everyKeyProductionReadsThroughAHandleIsRegistered() {
        // [T-prefs-guard-never-crashes] Pin strict mode: the guard only
        // throws when strict, and this flag is a mutable global other tests
        // flip to false. Without this pin an earlier lenient test turned
        // every assertion below into a no-op.
        Prefs.strictKeyGuard = true
        Prefs.install(InMemorySource())
        for ((file, keys) in keysByFile) {
            val handle = Prefs.file(file)
            for (key in keys) {
                try {
                    handle.contains(key)
                } catch (e: IllegalArgumentException) {
                    fail("$file/$key is not in PrefsRegistry: ${e.message}")
                }
            }
        }
    }
}
