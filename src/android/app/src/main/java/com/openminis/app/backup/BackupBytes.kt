package com.openminis.app.backup

import java.util.Locale

/**
 * [T-android-human-bytes-one-place] Byte formatting for backup surfaces, in ONE
 * place.
 *
 * This function used to live in `BackupAndRestoreScreen.kt` (UI) while
 * `BackupViewModel` already reached for it from the same package. Moving the
 * run to [BackupRunController] — a non-UI class in the `backup` package — would
 * have forced a THIRD copy (upstream carries exactly that duplication), so the
 * implementation moves down here instead and the UI keeps a one-line alias.
 *
 * Deliberately NOT `String.format` with the default locale: "%.1f" under a
 * comma-decimal locale renders "32,0 MB", which is a different string than the
 * tests and the docs describe. Decimal units (kB/MB/GB, 1000-based) match what
 * storage vendors and Android's own Settings page report; binary units would
 * make the same package read as a smaller number than the file manager shows.
 */
internal fun humanBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 ->
        String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 ->
        String.format(Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 ->
        String.format(Locale.US, "%.0f kB", bytes / 1e3)
    else -> "$bytes B"
}
