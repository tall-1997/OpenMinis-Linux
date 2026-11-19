package com.openminis.app.data.repository

import com.openminis.app.data.db.CompactMarkerEntity

/**
 * UI-safe view of the active compact marker. The UI layer consumes this
 * projection instead of naming Room entities; the data layer performs the
 * mapping in one place.
 */
data class CompactMarkerView(
    val id: String,
    val version: Int,
    val summary: String?,
    val summaryChunks: String?,
    val assetsJson: String?,
    val lastCompactedMessageId: String?,
    val firstKeptMessageId: String?,
    val boundaryMessageId: String?,
)

fun CompactMarkerEntity.toView(): CompactMarkerView = CompactMarkerView(
    id = id,
    version = version,
    summary = summary,
    summaryChunks = summaryChunks,
    assetsJson = assetsJson,
    lastCompactedMessageId = lastCompactedMessageId,
    firstKeptMessageId = firstKeptMessageId,
    boundaryMessageId = boundaryMessageId,
)
