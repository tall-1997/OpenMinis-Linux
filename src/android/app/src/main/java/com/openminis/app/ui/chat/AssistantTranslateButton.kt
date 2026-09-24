package com.openminis.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.i18n.TranslationPrefs
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * Last-ink report for [TranslateCorner]. Only the highest token from the
 * current measure pass is kept, so a full-bleed block composed after a
 * paragraph wins and the button drops below it instead of covering the chrome.
 */
internal class TranslateInk {
    private var seq = 0
    var bestToken: Int = -1
    var blocked: Boolean = true
    var maxWidth: Int = 0
    var textHeight: Int = 0
    var lineRight: FloatArray = FloatArray(0)
    var lineTop: FloatArray = FloatArray(0)
    var lineBottom: FloatArray = FloatArray(0)

    fun alloc(): Int = seq++

    fun beginMeasure() {
        bestToken = -1
        blocked = true
        maxWidth = 0
        textHeight = 0
        lineRight = FloatArray(0)
        lineTop = FloatArray(0)
        lineBottom = FloatArray(0)
    }

    fun reportBlocked(token: Int) {
        if (token < bestToken) return
        bestToken = token
        blocked = true
        maxWidth = 0
        textHeight = 0
        lineRight = FloatArray(0)
        lineTop = FloatArray(0)
        lineBottom = FloatArray(0)
    }

    fun reportText(token: Int, maxWidth: Int, result: TextLayoutResult) {
        if (token < bestToken) return
        if (maxWidth <= 0 || maxWidth == Int.MAX_VALUE || result.lineCount <= 0) {
            reportBlocked(token)
            return
        }
        bestToken = token
        blocked = false
        this.maxWidth = maxWidth
        textHeight = result.size.height
        val n = result.lineCount
        val rights = FloatArray(n)
        val tops = FloatArray(n)
        val bottoms = FloatArray(n)
        for (i in 0 until n) {
            rights[i] = result.getLineRight(i)
            tops[i] = result.getLineTop(i)
            bottoms[i] = result.getLineBottom(i)
        }
        lineRight = rights
        lineTop = tops
        lineBottom = bottoms
    }

    /**
     * Top of the button in content coordinates. Sits in the trailing space of
     * the last lines when that rectangle misses every glyph; otherwise moves
     * down only to the first clear y. A blocked (full-bleed) tail goes fully
     * below the content.
     */
    fun buttonTop(contentHeight: Int, buttonWidth: Int, buttonHeight: Int, gapPx: Int): Int {
        if (blocked || lineRight.isEmpty() || maxWidth <= 0 || buttonWidth <= 0) return contentHeight
        val buttonLeft = maxWidth - buttonWidth
        if (buttonLeft <= gapPx) return contentHeight
        val textTop = (contentHeight - textHeight).coerceAtLeast(0)
        var top = (contentHeight - buttonHeight).coerceAtLeast(0)
        // The button is only allowed to overlap the last text block. Anything
        // above that block is unknown ink, so a button taller than the block
        // starts at the block's top instead of covering the previous block.
        if (top < textTop) top = textTop
        for (i in lineRight.indices) {
            if (lineRight[i] + gapPx <= buttonLeft) continue
            val lineTopC = textTop + lineTop[i]
            val lineBottomC = textTop + lineBottom[i]
            val bottom = top + buttonHeight
            if (bottom <= lineTopC || top >= lineBottomC) continue
            top = ceil(lineBottomC.toDouble()).toInt()
        }
        return top
    }
}

internal val LocalTranslateInk = compositionLocalOf<TranslateInk?> { null }

/** Full-bleed tail (code, table, image, rule). The button must not cover it. */
@Composable
internal fun ReportTranslateInkBlocked() {
    val ink = LocalTranslateInk.current
    val token = remember(ink) { ink?.alloc() ?: -1 }
    if (ink == null || token < 0) return
    Spacer(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            ink.reportBlocked(token)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
    )
}

/**
 * Full-width content, button at the physical bottom-right. Does not inset the
 * text. Overlaps only trailing whitespace; if that would cover ink, the button
 * moves down just far enough to clear it.
 */
@Composable
internal fun TranslateCorner(
    modifier: Modifier = Modifier,
    button: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val ink = remember { TranslateInk() }
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.roundToPx() }
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            CompositionLocalProvider(LocalTranslateInk provides ink) {
                Box(Modifier.fillMaxWidth()) { content() }
            }
            Box { button() }
        },
    ) { measurables, constraints ->
        ink.beginMeasure()
        val contentPlaceable = measurables[0].measure(constraints)
        val buttonPlaceable = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.maxWidth
        val contentHeight = contentPlaceable.height
        val buttonTop = if (buttonPlaceable.width == 0 || buttonPlaceable.height == 0) {
            contentHeight
        } else {
            ink.buttonTop(
                contentHeight = contentHeight,
                buttonWidth = buttonPlaceable.width,
                buttonHeight = buttonPlaceable.height,
                gapPx = gapPx,
            )
        }
        val height = maxOf(contentHeight, buttonTop + buttonPlaceable.height)
        layout(width, height) {
            contentPlaceable.place(0, 0)
            if (buttonPlaceable.width > 0 && buttonPlaceable.height > 0) {
                buttonPlaceable.place(width - buttonPlaceable.width, buttonTop)
            }
        }
    }
}

/**
 * One tap translates with the language and model already saved in Settings.
 * Does not ask for the language again.
 */
@Composable
fun AssistantTranslateButton(
    source: String,
    onTranslated: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (!TranslationPrefs.isEnabled(context)) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val missing = stringResource(R.string.translate_set_in_defaults)
    val noModel = stringResource(R.string.translate_no_model)

    Box(modifier = modifier.size(32.dp), contentAlignment = Alignment.Center) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            IconButton(
                onClick = {
                    if (busy || source.isBlank()) return@IconButton
                    val lang = TranslationPrefs.lang(context)
                    busy = true
                    scope.launch {
                        val result = translateBubble(context, source, lang)
                        busy = false
                        val failed = result.startsWith("Error:") || result == noModel || result == missing
                        if (failed) {
                            Toast.makeText(
                                context,
                                result.removePrefix("Error:").trim().ifBlank { missing },
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            onTranslated(result)
                        }
                    }
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Language,
                    contentDescription = stringResource(R.string.translate_action),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private suspend fun translateBubble(context: android.content.Context, text: String, lang: String): String {
    val app = context.applicationContext as? MinisApp
        ?: return "Error: " + context.getString(R.string.translate_no_model)
    val repo = app.providerRepository
    val entry = withContext(Dispatchers.IO) { resolveBubbleEntry(context) }
        ?: return "Error: " + context.getString(R.string.translate_set_in_defaults)
    val instance = repo.instance(entry.providerInstanceId)
        ?: return "Error: " + context.getString(R.string.translate_no_model)
    val key = repo.usableApiKey(instance) ?: return "Error: " + context.getString(R.string.translate_no_model)
    return try {
        val provider = ProviderFactory.create(instance, key, entry.model, context)
        val response = provider.sendMessage(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = text)),
            systemPrompt = "You are a translator. Return only the translation into $lang. No preface.",
            maxTokens = 4096,
            temperature = 0.2,
            thinkingLevel = ThinkingLevel.OFF,
        )
        response.text.trim().ifBlank { "Error: " + context.getString(R.string.translate_empty) }
    } catch (e: Exception) {
        "Error: " + (e.message ?: context.getString(R.string.translate_empty))
    }
}

private fun resolveBubbleEntry(context: android.content.Context): com.openminis.app.data.model.ModelEntry? {
    val repo = (context.applicationContext as? MinisApp)?.providerRepository ?: return null
    val config = repo.config.value
    fun usable(id: String): com.openminis.app.data.model.ModelEntry? {
        val entry = config.modelEntries.find { it.id == id && !it.isHidden } ?: return null
        val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
        return entry.takeIf { inst.isEnabled }
    }
    val wanted = TranslationPrefs.entryId(context)
    if (wanted != null) return usable(wanted)
    return config.modelEntries.firstNotNullOfOrNull { if (it.isHidden) null else usable(it.id) }
}
