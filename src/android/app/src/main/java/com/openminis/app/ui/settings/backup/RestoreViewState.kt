package com.openminis.app.ui.settings.backup

import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.BackupImporter
import com.openminis.app.backup.BackupManifest
import java.io.File

// Restore-side view state: what the screen needs to render, with no behavior
// attached. Moved out of BackupViewModel, whose architecture-policy baseline
// pins it at a ceiling it may shrink under but never grow past, so new work
// there has to displace something. Same package, same names and fields — no
// behaviour moved.

/**
 * [T-android-restore-perf] Fraction of the current restore, for the determinate
 * ring on the action button. Null when no total is known yet — the ring then
 * draws an empty arc rather than a moving one, and the text label carries the
 * live state instead.
 *
 * Clamped: `done` can outrun `total` when the manifest's count and the JSONL's
 * record count disagree, and an arc over 100% wraps around to look nearly
 * empty exactly when the run is finishing.
 */
fun restoreFraction(progress: BackupImporter.Progress?): Float? {
    val total = progress?.total ?: return null
    if (total <= 0) return null
    return (progress.done.toFloat() / total).coerceIn(0f, 1f)
}

/** A picked package, extracted and its manifest read, awaiting confirm. */
data class PendingRestore(
    val extractedRoot: File,
    val manifest: BackupManifest,
    val availableCategories: Set<BackupCategory>,
)

/**
 * Live download figures, so the sheet can show speed and time left.
 *
 * No percentage claim: [bytesDone] over [totalBytes] is honest only once the
 * server has sent a Content-Length, and [fraction] answers 0 until it has.
 */
data class TransferInfo(
    val name: String,
    val bytesDone: Long,
    val totalBytes: Long,
    val bytesPerSecond: Double,
    val secondsRemaining: Long?,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes) else 0f
}

/**
 * [T-android-open-progress] What the open is ACTUALLY doing, as opposed to
 * which label a timer has reached. [files]/[bytes] count entries already
 * written, [current] is the one in flight; null when nothing is open.
 *
 * Deliberately no percentage: a forward `ZipInputStream` scan does not know
 * the entry count until it hits the end, and a bar that crawls to 90% and
 * then sits there is a worse lie than an honest running total.
 */
data class OpenProgress(val files: Int, val bytes: Long, val current: String)
