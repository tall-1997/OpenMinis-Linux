package com.openminis.app.ui.chat

import android.content.Context
import java.io.File

/**
 * [T-prior-read] Durable staging for share-inbound attachments, extracted
 * from ChatViewModel.addAttachmentFromStagedShare (architecture policy
 * ratchet). ChatScreen wipes the share-extension directory right after the
 * drain returns (`SharedShareStore.cleanSharedFiles`), so a
 * `Uri.fromFile(<staged file>)` would dangle by the time the user actually
 * sends — the byte-read in prepareUserAttachments then fails to open the
 * stream and the image never makes it into the LLM payload, leaving the
 * model staring at "what is this?" with no picture. Copying the staged
 * bytes into our own private dir (cache/share_inbound) makes the
 * attachment outlive the share-extension cleanup.
 */
internal fun stageShareInboundCopy(context: Context, file: File): Result<File> {
    val durableDir = File(context.cacheDir, "share_inbound").apply { mkdirs() }
    val durable = File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")
    return runCatching {
        file.inputStream().use { input ->
            durable.outputStream().use { output -> input.copyTo(output) }
        }
        durable
    }
}
