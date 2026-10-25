package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-android-skill-icon-circular] A colored circular badge with a white glyph
 * centered inside, for the skill detail ACTION rows (Update from URL / Update
 * from file / Rescan).
 *
 * Spec is copied VERBATIM from the main Settings page rows
 * (SettingsScreen's row icon: a 30dp circle filled with `iconColor`, holding a
 * 16dp white glyph, followed by a 14dp gap) — keep the two surfaces identical;
 * don't retune one without the other. Mirrors iOS `SettingsActionIcon`
 * (d431cfc7), which copied its own Settings spec the same way.
 *
 * Before this, the action rows used a bare 18dp tinted glyph with no container,
 * so they read as a different visual language from every other settings surface.
 */
@Composable
internal fun SettingsActionIcon(
    icon: ImageVector,
    iconColor: Color,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(color = iconColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = m3Icon(icon),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}
