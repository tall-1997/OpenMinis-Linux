package com.openminis.app.ui.chat

import com.openminis.app.goal.GoalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun ChatViewModel.executeGoalCommand(command: String) {
    val manager = GoalManager(chatRepository)
    val sid = withContext(Dispatchers.IO) { ensureSession() }
    val parts = command.split(Regex("\\s+"), limit = 2)
    val op = parts.firstOrNull()?.lowercase().orEmpty()
    val arg = parts.getOrNull(1).orEmpty()
    var shouldStartGoalRun = false
    val message = withContext(Dispatchers.IO) {
        runCatching {
            when (op) {
                "create" -> {
                    val budgetMatch = Regex("\\s+--budget\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE).find(arg)
                    val budget = budgetMatch?.groupValues?.get(1)?.toLongOrNull()
                    if (budgetMatch != null && budget == null) {
                        "Token budget is too large. Use a positive 64-bit integer."
                    } else {
                        val objective = if (budgetMatch != null) arg.removeRange(budgetMatch.range).trim() else arg.trim()
                        manager.createByUser(sid, objective, budget)
                        shouldStartGoalRun = true
                        manager.render(manager.get(sid))
                    }
                }
                "pause" -> manager.render(manager.setPausedByUser(sid, true))
                "budget" -> {
                    val budget = if (arg.equals("off", ignoreCase = true)) null else arg.toLongOrNull()
                    if (budget == null && !arg.equals("off", ignoreCase = true)) {
                        "Usage: /goal budget <positive tokens|off>"
                    } else {
                        val before = manager.get(sid)
                        val updated = manager.updateBudgetByUser(sid, budget)
                        shouldStartGoalRun = before?.status == com.openminis.app.data.db.GoalStatus.BUDGET_LIMITED &&
                            updated.status == com.openminis.app.data.db.GoalStatus.ACTIVE
                        manager.render(updated)
                    }
                }
                "resume" -> {
                    val resumed = manager.setPausedByUser(sid, false)
                    shouldStartGoalRun = resumed?.status == com.openminis.app.data.db.GoalStatus.ACTIVE
                    manager.render(resumed)
                }
                "show", "status" -> manager.render(manager.get(sid))
                "help" -> "Usage: /goal create <objective> [--budget tokens], /goal budget <tokens|off>, /goal pause, /goal resume, /goal show"
                else -> "Usage: /goal create <objective> [--budget tokens], /goal budget <tokens|off>, /goal pause, /goal resume, /goal show"
            }
        }.getOrElse { "Goal: ${it.message ?: "unable to update"}" }
    }
    withContext(Dispatchers.Main.immediate) {
        appendSystemInfo(message, "info")
        if (shouldStartGoalRun && !_isStreaming.value) {
            sendMessage("", skipContextCheck = true, internalGoalRun = true)
        }
    }
}
