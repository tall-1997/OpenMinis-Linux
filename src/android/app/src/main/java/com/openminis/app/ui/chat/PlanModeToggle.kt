package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.tools.PlanModeStore
import com.openminis.app.ui.theme.m3Icon

/**
 * Composer pill that toggles plan mode for this session. Reads the flag
 * through [PlanModeStore.version] so every surface (pill, banners, guard
 * telemetry) recomposes from one source of truth; the toggle itself is the
 * only user-facing writer.
 */
@Composable
internal fun PlanModeToggle(
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val version by PlanModeStore.version.collectAsState()
    val enabled = remember(version, sessionId) {
        PlanModeStore.isEnabled(sessionId, context)
    }
    val pillShape = RoundedCornerShape(17.dp)
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .shadow(
                elevation = 4.dp,
                shape = pillShape,
                ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.06f),
                spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.12f),
            )
            .background(ChatColors.inputBg, pillShape)
            .border(
                if (enabled) 1.dp else 0.5.dp,
                if (enabled) accent.copy(alpha = 0.55f) else ChatColors.toolBorder,
                pillShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { PlanModeStore.setEnabled(sessionId, context, !enabled) }
            .heightIn(min = 34.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            m3Icon(Icons.Default.EditNote),
            contentDescription = null,
            tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (enabled) {
                androidx.compose.ui.res.stringResource(com.openminis.app.R.string.plan_mode_on)
            } else {
                androidx.compose.ui.res.stringResource(com.openminis.app.R.string.plan_mode_off)
            },
            fontSize = 13.sp,
            fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Medium,
            color = if (enabled) accent else ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

