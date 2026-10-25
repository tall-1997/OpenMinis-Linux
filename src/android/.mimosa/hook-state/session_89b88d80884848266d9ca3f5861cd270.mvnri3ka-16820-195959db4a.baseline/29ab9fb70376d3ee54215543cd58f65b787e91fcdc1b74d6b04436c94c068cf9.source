package com.openminis.app.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.view.accessibility.AccessibilityEvent

/**
 * User-initiated accessibility scene recorder. Writes a skill, never a
 * bundled malware preset (no red-packet / spam scripts).
 */
object A11yScriptRecorder {

    data class Step(
        val kind: Kind,
        val packageName: String?,
        val text: String?,
        val viewId: String?,
        val contentDescription: String?,
        val xy: String?,
        val atMs: Long,
    ) {
        enum class Kind { TAP, INPUT, WINDOW }
    }

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _steps = MutableStateFlow<List<Step>>(emptyList())
    val steps: StateFlow<List<Step>> = _steps.asStateFlow()

    private val listener: (MinisAccessibilityService.RecordedEvent) -> Unit = { event ->
        if (_recording.value) maybeAppend(event)
    }

    fun start(): Boolean {
        val svc = MinisAccessibilityService.getInstance() ?: return false
        _steps.value = emptyList()
        _recording.value = true
        svc.addEventListener(listener)
        return true
    }

    fun stop() {
        MinisAccessibilityService.getInstance()?.removeEventListener(listener)
        _recording.value = false
    }

    fun toSkillMarkdown(name: String, recorded: List<Step> = _steps.value): String {
        val title = name.trim().ifBlank { "recorded-scene" }
        val desc = "User-recorded accessibility scene. Replay with android-a11y-cli. Not a malware preset."
        return buildString {
            appendLine("---")
            appendLine("name: $title")
            appendLine("description: $desc")
            appendLine("---")
            appendLine()
            appendLine("# $title")
            appendLine()
            appendLine(desc)
            appendLine()
            appendLine("Recorded ${recorded.size} step(s). Review before replaying.")
            appendLine()
            appendLine("## Steps")
            appendLine()
            recorded.forEachIndexed { i, step ->
                appendLine("${i + 1}. ${describe(step)}")
                appendLine()
                appendLine("```bash")
                appendLine(cli(step))
                appendLine("```")
                appendLine()
            }
        }
    }

    internal fun fromEvent(event: MinisAccessibilityService.RecordedEvent): Step? {
        val type = event.type
        val kind = when {
            type == AccessibilityEvent.TYPE_VIEW_CLICKED.toString() ||
                type.contains("TYPE_VIEW_CLICKED") -> Step.Kind.TAP
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED.toString() ||
                type.contains("TYPE_VIEW_TEXT_CHANGED") -> Step.Kind.INPUT
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED.toString() ||
                type.contains("TYPE_WINDOW_STATE_CHANGED") -> Step.Kind.WINDOW
            else -> return null
        }
        val text = event.text?.trim()?.takeIf { it.isNotEmpty() }
        val viewId = event.viewId?.trim()?.takeIf { it.isNotEmpty() }
        if (kind == Step.Kind.TAP && text.isNullOrEmpty() && viewId.isNullOrEmpty() && event.xy.isNullOrEmpty()) {
            return null
        }
        return Step(
            kind = kind,
            packageName = event.packageName,
            text = text,
            viewId = viewId,
            contentDescription = event.contentDescription,
            xy = event.xy,
            atMs = event.timestamp,
        )
    }

    internal fun cli(step: Step): String = when (step.kind) {
        Step.Kind.TAP -> when {
            !step.viewId.isNullOrBlank() ->
                "android-a11y-cli tap id ${shellQuote(step.viewId)}"
            !step.text.isNullOrBlank() ->
                "android-a11y-cli tap text ${shellQuote(step.text)}"
            !step.xy.isNullOrBlank() -> {
                val parts = step.xy.split(',')
                val x = parts.getOrNull(0)?.trim() ?: "0"
                val y = parts.getOrNull(1)?.trim() ?: "0"
                "android-a11y-cli tap xy $x $y"
            }
            else -> "android-a11y-cli ui dump"
        }
        Step.Kind.INPUT ->
            "android-a11y-cli input text ${shellQuote(step.text.orEmpty())}"
        Step.Kind.WINDOW ->
            "android-a11y-cli wait activity"
    }

    internal fun shellQuote(raw: String): String {
        if (raw.isEmpty()) return "\"\""
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun describe(step: Step): String = when (step.kind) {
        Step.Kind.TAP -> buildString {
            append("Tap")
            step.text?.let { append(" \"$it\"") }
            step.viewId?.let { append(" id=$it") }
            step.packageName?.let { append(" in $it") }
        }
        Step.Kind.INPUT -> "Type \"${step.text.orEmpty()}\""
        Step.Kind.WINDOW -> "Window ${step.packageName ?: ""}".trim()
    }

    private fun maybeAppend(event: MinisAccessibilityService.RecordedEvent) {
        val step = fromEvent(event) ?: return
        val last = _steps.value.lastOrNull()
        if (last != null &&
            last.kind == step.kind &&
            last.viewId == step.viewId &&
            last.text == step.text &&
            last.packageName == step.packageName &&
            step.atMs - last.atMs < 250
        ) {
            return
        }
        _steps.value = _steps.value + step
    }
}
