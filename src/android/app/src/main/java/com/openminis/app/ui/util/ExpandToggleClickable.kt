package com.openminis.app.ui.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/** [T-port-fold-tools] Expand/collapse taps without a ripple (upstream parity). */
internal fun Modifier.expandToggleClickable(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        role = Role.Button,
        onClick = onClick,
    )
