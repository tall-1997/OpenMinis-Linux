package com.openminis.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.Color
import com.openminis.app.util.IsoTime

// [T-android-split-chat] Pure tool-label / duration / timestamp formatting
// helpers extracted verbatim from ChatScreen.kt. `internal` so the rest of the
// chat package (still in ChatScreen.kt) can call them across the file boundary.
// No logic change — code moved as-is.

internal fun formatStepTimestamp(epochMs: Long): String =
    IsoTime.formatHms(epochMs)

// Helper: tool accent color (iOS system palette). Family colors stay
// consistent: file tools cyan/blue, agent tools indigo, web tools blue,
// image tools purple, memory/goal pink, interactive prompts blue.
internal fun toolAccentColor(toolName: String): Color = when (toolName) {
    "shell_execute", "shell_exec", "agent_terminal" -> Color(0xFF34C759)
    "file_read" -> Color(0xFF32ADE6)
    "file_write" -> Color(0xFF007AFF)
    "file_edit", "multi_edit" -> Color(0xFFFF9500)
    "file_checkpoint" -> Color(0xFF8E8E93)
    "browser_use" -> Color(0xFF007AFF)
    "read_image", "ocr_image" -> Color(0xFFAF52DE)
    "memory_write", "memory_get" -> Color(0xFFFF2D55)
    "web_search" -> Color(0xFF32ADE6)    // iOS: .cyan for search
    "web_fetch" -> Color(0xFF007AFF)
    "grep", "grep_source" -> Color(0xFF64D2FF)
    "glob", "list_dir" -> Color(0xFF32ADE6)
    "code_graph" -> Color(0xFF5856D6)
    "search_sessions", "read_session" -> Color(0xFF64D2FF)
    "spawn_agent", "run_subagent", "dispatch_agents", "wolfpack_run", "check_agent" -> Color(0xFF5856D6)
    "agent_plan" -> Color(0xFF5856D6)
    "update_goal" -> Color(0xFFFF2D55)
    "evolution" -> Color(0xFFAF52DE)
    "ask_user_question" -> Color(0xFF007AFF)
    "ask_reasoning" -> Color(0xFFFF9500)
    "execute_code" -> Color(0xFF5856D6)
    "su_exec" -> Color(0xFFFF9500)
    "ssh_exec", "env_exec" -> Color(0xFF32ADE6)
    "ui_read", "ui_action" -> Color(0xFF64D2FF)
    "invoke_skill", "ui_skill", "skill_manage" -> Color(0xFF007AFF)
    "cronjob" -> Color(0xFFFF9500)
    "background_job" -> Color(0xFF64D2FF)
    "get_screen_time" -> Color(0xFF64D2FF)
    "present_file", "deliverable" -> Color(0xFF30D158)     // deliverable green
    else -> when {
        toolName.startsWith("mcp__") -> Color(0xFF5856D6)   // server-provided tools
        toolName.startsWith("online_") -> Color(0xFF007AFF)
        else -> Color(0xFF8E8E93)                            // unknown = neutral gray
    }
}

// Helper: tool icon (iOS: distinct SF Symbols per tool type).
// Every registered tool gets a semantic glyph; the FALLBACK is a wrench
// (generic "tool"), never a magnifier — an unknown tool is not a search,
// and defaulting to Search made unmapped tool cards indistinguishable
// from the composer's search affordance.
internal fun toolIconFor(toolName: String) = when (toolName) {
    "shell_execute", "shell_exec", "agent_terminal" -> Icons.Default.Terminal
    "file_read" -> Icons.Default.Description         // iOS: doc.text
    "file_write" -> Icons.AutoMirrored.Filled.NoteAdd   // iOS: doc.text.fill (filled variant)
    "file_edit", "multi_edit" -> Icons.Default.EditNote             // iOS: square.and.pencil
    "file_checkpoint" -> Icons.Default.History        // snapshot / restore point
    "browser_use" -> Icons.Default.Language            // iOS: globe
    "read_image" -> Icons.Default.Image                // iOS: photo
    "ocr_image" -> Icons.Default.DocumentScanner      // text out of an image
    "memory_write", "memory_get" -> Icons.Default.Psychology // iOS: brain.head.profile
    "web_search" -> Icons.Default.Search               // iOS: magnifyingglass
    "web_fetch" -> Icons.Default.Link                  // fetch one URL
    "grep", "grep_source" -> Icons.Default.ManageSearch  // search inside text/code
    "glob" -> Icons.Default.FolderOpen                 // file-pattern search
    "list_dir" -> Icons.Default.Folder
    "code_graph" -> Icons.Default.Hub                  // symbol graph
    "search_sessions", "read_session" -> Icons.Default.Search
    "spawn_agent", "run_subagent", "dispatch_agents", "wolfpack_run" -> Icons.Default.Groups
    "check_agent" -> Icons.Default.FactCheck           // verify lane results
    "agent_plan" -> Icons.Default.Dashboard            // plan board
    "update_goal" -> Icons.Default.TrackChanges        // goal state
    "evolution" -> Icons.Default.AutoAwesome           // self-improvement
    "ask_user_question" -> Icons.Default.HelpOutline   // interactive prompt
    "ask_reasoning" -> Icons.Default.Lightbulb
    "execute_code" -> Icons.Default.Code
    "su_exec" -> Icons.Default.AdminPanelSettings      // privileged host exec
    "ssh_exec", "env_exec" -> Icons.Default.Lan        // remote / env-scoped exec
    "ui_read" -> Icons.Default.Visibility              // read the screen
    "ui_action" -> Icons.Default.TouchApp              // drive the screen
    "invoke_skill", "ui_skill", "skill_manage" -> Icons.Default.Extension   // skill packs
    "cronjob" -> Icons.Default.Alarm
    "background_job" -> Icons.Default.Launch           // detached lane
    "get_screen_time" -> Icons.Default.Schedule
    "present_file", "deliverable" -> Icons.Default.IosShare
    else -> when {
        toolName.startsWith("mcp__") -> Icons.Default.Dns          // server-provided tool
        toolName.startsWith("online_") -> Icons.Default.Language
        else -> Icons.Default.Build                     // generic tool, NOT a magnifier
    }
}

// Helper: tool display name for "Minis is using X"
internal fun toolDisplayName(toolName: String): String = when (toolName) {
    "shell_execute" -> "terminal"
    "file_read" -> "file reader"
    "file_write" -> "file writer"
    "file_edit" -> "file editor"
    "browser_use" -> "browser"
    "read_image" -> "image viewer"
    "memory_write" -> "memory"
    "memory_get" -> "memory"
    "web_search" -> "search"
    "search_sessions" -> "session search"
    "read_session" -> "session reader"
    "spawn_agent", "run_subagent" -> "sub-agent"
    "cronjob" -> "cron"
    "present_file" -> "present_file"
    else -> if (toolName.startsWith("online_")) "online plugin" else toolName
}

/**
 * Full "Minis is …" label shown in the tool detail sheet's bottom bar.
 * Mirrors iOS ToolLiveSheet.toolTitle so the wording matches per tool.
 */
internal fun toolTitleLabel(toolName: String): String = when (toolName) {
    "shell_execute" -> "Minis is using Shell"
    "file_read" -> "Minis is reading File"
    "file_write" -> "Minis is using Editor"
    "file_edit" -> "Minis is editing File"
    "browser_use" -> "Minis is using Browser"
    "read_image" -> "Minis is reading Image"
    "memory_write", "memory_get" -> "Minis is using Memory"
    "web_search" -> "Minis is using Search"
    "search_sessions" -> "Minis is searching Sessions"
    "read_session" -> "Minis is reading a Session"
    "spawn_agent", "run_subagent" -> "Minis is coordinating a sub-agent"
    "cronjob" -> "Minis is scheduling a task"
    "present_file" -> "Minis is delivering a file"
    else -> "Minis is using ${toolDisplayName(toolName)}"
}

// Helper: format duration (iOS: < 1s → "0.1s", < 60s → "45s", >= 60s → "2m 10s")
internal fun formatToolDuration(ms: Long): String {
    val seconds = ms / 1000.0
    return when {
        seconds < 1 -> String.format("%.1fs", seconds)
        seconds < 60 -> String.format("%.0fs", seconds)
        else -> {
            val m = (seconds / 60).toInt()
            val s = (seconds % 60).toInt()
            "${m}m ${s}s"
        }
    }
}
