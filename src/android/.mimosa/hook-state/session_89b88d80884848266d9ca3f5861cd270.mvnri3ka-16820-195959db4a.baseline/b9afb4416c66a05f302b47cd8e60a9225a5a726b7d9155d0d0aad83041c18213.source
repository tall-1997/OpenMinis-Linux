package com.openminis.app.offload

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.openminis.app.sandbox.SessionAccessPolicy

/** One privacy ceiling for all personal-data tools. Default is ask every session. */
object PrivacyPermissionPolicy {
    private const val PREFS = "offload_permissions"
    private const val KEY = "privacy_global_level"
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    val toolNames: Set<String> = setOf(
        "calendar", "location", "clipboard", "contacts",
        SessionAccessPolicy.GRANT, "photos",
    )

    fun get(context: Context): OffloadPermissionManager.PermissionLevel {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return raw?.let { runCatching { OffloadPermissionManager.PermissionLevel.valueOf(it) }.getOrNull() }
            ?: OffloadPermissionManager.PermissionLevel.ASK_ONCE
    }

    fun set(context: Context, level: OffloadPermissionManager.PermissionLevel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, level.name).apply()
        _version.value++
    }

    fun effective(
        toolName: String,
        global: OffloadPermissionManager.PermissionLevel,
        configured: OffloadPermissionManager.PermissionLevel,
    ): OffloadPermissionManager.PermissionLevel {
        if (toolName !in toolNames) return configured
        if (global == OffloadPermissionManager.PermissionLevel.NOT_ALLOWED) return global
        if (configured == OffloadPermissionManager.PermissionLevel.NOT_ALLOWED) return configured
        return global
    }
}
