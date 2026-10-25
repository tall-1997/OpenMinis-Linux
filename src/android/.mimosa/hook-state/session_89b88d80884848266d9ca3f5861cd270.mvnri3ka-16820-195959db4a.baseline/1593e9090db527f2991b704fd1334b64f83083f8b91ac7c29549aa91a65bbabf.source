package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.theme.m3Icon

/** Input modalities that get a (muted) capability badge in the model list. */
internal val modalityIconKeys = setOf("image", "pdf", "audio", "video")
/** Output modalities that get a (tinted, generate-style) badge in the model list. */
internal val modalityOutputIconKeys = setOf("image", "audio", "video")

/**
 * [T-android-model-capability-output-tags] Capability badges for one model row.
 * Input modalities render in the muted onSurfaceVariant tint; output modalities
 * render in the primary tint with "generate"-style glyphs so a generator (e.g.
 * gpt-image-2 image_output) is visibly distinct from a model that merely accepts
 * that modality as input. Mirrors iOS ProviderInstanceDetailView.modalityIcons.
 *
 * Moved out of ProviderDetailScreen unchanged apart from visibility: that file is
 * pinned at its architecture ceiling, and this is a self-contained row primitive
 * the model list happens to own today.
 */
@Composable
internal fun ModalityIconsRow(
    inputModalities: List<String>,
    outputModalities: List<String>,
) {
    val inputTint = MaterialTheme.colorScheme.onSurfaceVariant
    val outputTint = MaterialTheme.colorScheme.primary
    val size = Modifier.size(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Input badges (muted).
        if ("image" in inputModalities) Icon(m3Icon(Icons.Default.Image), contentDescription = stringResource(R.string.modeldetail_image_input), tint = inputTint, modifier = size)
        if ("pdf" in inputModalities) Icon(m3Icon(Icons.AutoMirrored.Filled.InsertDriveFile), contentDescription = stringResource(R.string.modeldetail_pdf_input), tint = inputTint, modifier = size)
        if ("audio" in inputModalities) Icon(m3Icon(Icons.Default.Mic), contentDescription = stringResource(R.string.modeldetail_audio_input), tint = inputTint, modifier = size)
        if ("video" in inputModalities) Icon(m3Icon(Icons.Default.Videocam), contentDescription = stringResource(R.string.modeldetail_video_input), tint = inputTint, modifier = size)
        // Output badges (tinted, generate-style glyphs).
        if ("image" in outputModalities) Icon(m3Icon(Icons.Default.AddPhotoAlternate), contentDescription = stringResource(R.string.modeldetail_image_output), tint = outputTint, modifier = size)
        if ("audio" in outputModalities) Icon(m3Icon(Icons.Default.VolumeUp), contentDescription = stringResource(R.string.modeldetail_audio_output), tint = outputTint, modifier = size)
        if ("video" in outputModalities) Icon(m3Icon(Icons.Default.MovieCreation), contentDescription = stringResource(R.string.modeldetail_video_output), tint = outputTint, modifier = size)
    }
}
