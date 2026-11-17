package com.openminis.app.cards

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import org.json.JSONObject

/**
 * [T-card-title-i18n] Card-title plumbing shared by the derivers and the
 * renderers, extracted from [CardDerivers]/[CardRenderers] so those files
 * stay under their architecture-policy line ceilings. Pure title/label
 * concerns only — no new behaviour:
 *  - [cardTitleText] resolves the display title for any card: the localized
 *    `stringResource(titleResId, titleResArg)` wins when the deriver set
 *    one, otherwise the English fallback title (tests / non-UI consumers).
 *  - [CardTitle] is the visual title row (moved verbatim from the
 *    renderers file).
 *  - [changeCard] + [buildUndoArgs] build the Change card whose label IS
 *    the card title (moved verbatim from the derivers file, plus the
 *    labelResId plumbing).
 */

@Composable
internal fun cardTitleText(fallback: String, resId: Int?, arg: String?): String = when {
    resId != null && arg != null -> stringResource(resId, arg)
    resId != null -> stringResource(resId)
    else -> fallback
}

@Composable
internal fun CardTitle(title: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.let {
            Spacer(Modifier.width(6.dp))
            Text(
                text = it,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.tertiaryText,
            )
        }
    }
}

/** Toggle label → its localized res id (label itself stays the match token). */
internal fun connectivityLabelRes(label: String): Int? = when (label) {
    "Wi-Fi" -> R.string.card_title_wifi
    "Bluetooth" -> R.string.card_title_bluetooth
    "Mobile data" -> R.string.card_title_mobile_data
    else -> null
}

internal fun toolNameOfLabel(label: String): String = when (label) {
    "Wi-Fi" -> "wifi_toggle"
    "Bluetooth" -> "bluetooth_toggle"
    else -> "mobile_data_toggle"
}

internal fun clip(s: String, max: Int): String =
    if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

/**
 * Build a [ToolCard.Change] from the tool-reported new state plus the
 * F-batch `was: <old>` line. Undoable only when the old value coerces
 * back into [valueKey]'s JSON type; [undoKey] overrides the argument
 * name (brightness adaptive mode).
 */
internal fun changeCard(
    label: String,
    newText: String,
    resultText: String,
    toolName: String,
    argsJson: String,
    valueKey: String?,
    coerceOld: (String) -> Any?,
    undoKey: String? = null,
    labelResId: Int? = null,
    titleResArg: String? = null,
): ToolCard.Change {
    val old = CardDeriver.parseWasLine(resultText)
    val coerced = old?.let { coerceOld(it) }
    val key = undoKey ?: valueKey
    val undoArgsJson = if (coerced != null && key != null) buildUndoArgs(argsJson, key, coerced) else null
    return ToolCard.Change(
        label = label,
        labelResId = labelResId,
        titleResArg = titleResArg,
        oldText = old?.let { clip(it, CardDeriver.MAX_FACT_VALUE_CHARS) },
        newText = newText,
        undoable = undoArgsJson != null,
        tool = toolName,
        undoArgsJson = undoArgsJson,
    )
}

/** Merge the original call args with the old value for the undo re-run. */
private fun buildUndoArgs(argsJson: String, key: String, oldValue: Any): String? {
    val obj = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
    return try {
        when (oldValue) {
            is Int -> obj.put(key, oldValue)
            is Boolean -> obj.put(key, oldValue)
            is String -> obj.put(key, oldValue)
            else -> return null
        }.toString()
    } catch (_: Exception) {
        null
    }
}
