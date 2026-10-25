package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ImageBudget

/**
 * Apply the request-level image-byte budget to a fully-resolved
 * message list before handing it to a provider. Images that don't
 * fit under [ImageBudget.MAX_REQUEST_BYTES] (oldest first) are
 * replaced in-place with a text placeholder that, when the original
 * bytes were offloaded to disk, points the model back to the linux
 * path so it can re-fetch via `read_image` if needed. Images that
 * never had a linuxPath are spilled to
 * `attachments/spillover/<sha1>.<ext>` lazily so the placeholder
 * still carries an addressable reference.
 *
 * Returns the budgeted message list. When nothing was elided this
 * is the same instance as [messages].
 *
 * Emits a one-shot [requestBudgetEvent] for the UI Snackbar so the
 * user knows older images were compacted into placeholders.
 */
internal fun ChatViewModel.applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage> {
    // Collect every image in chronological order so the planner can
    // walk in reverse and protect the most recent images.
    data class ImageRef(val msgIdx: Int, val partIdx: Int, val image: ImageBudget.BudgetImage)
    val images = mutableListOf<ImageRef>()
    messages.forEachIndexed { mi, msg ->
        msg.contentParts.forEachIndexed { pi, part ->
            when (part) {
                is AgentContentPart.ImageData -> {
                    images.add(
                        ImageRef(
                            mi, pi,
                            ImageBudget.BudgetImage(part.data, part.linuxPath, part.mimeType),
                        )
                    )
                }
                is AgentContentPart.ToolResult -> {
                    val img = part.imageData
                    if (img != null) {
                        images.add(
                            ImageRef(
                                mi, pi,
                                ImageBudget.BudgetImage(
                                    img,
                                    part.imageLinuxPath,
                                    part.imageMimeType ?: "image/jpeg",
                                ),
                            )
                        )
                    }
                }
                else -> Unit
            }
        }
    }
    if (images.isEmpty()) return messages

    val plan = ImageBudget.planRequestBudget(images.map { it.image })
    if (!plan.mutated) return messages

    // For dropped images without a linuxPath, lazily spill to disk so
    // the placeholder still gives the model an addressable reference.
    val attachmentsRoot = activeSessionId?.let { sid ->
        com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sid, "attachments")
    }
    val resolvedPaths = HashMap<ImageBudget.ImagePartId, String?>()
    for (ref in images) {
        val id = ImageBudget.ImagePartId.of(ref.image.data)
        if (id !in plan.droppedIds) continue
        val existing = ref.image.linuxPath
        if (existing != null) {
            resolvedPaths[id] = existing
        } else if (attachmentsRoot != null) {
            resolvedPaths[id] = ImageBudget.ensureSpillover(
                attachmentsRoot, ref.image.data, ref.image.mimeType,
            )
        } else {
            resolvedPaths[id] = null
        }
    }

    // Build a new message list with dropped image parts replaced by
    // text placeholders. Same-message multiple drops collapse cleanly
    // because we never touch parts whose ids weren't in droppedIds.
    val byMsg = images.groupBy { it.msgIdx }
    val mutated = messages.toMutableList()
    for ((mi, refs) in byMsg) {
        val msg = mutated[mi]
        val newParts = msg.contentParts.toMutableList()
        for (ref in refs) {
            val id = ImageBudget.ImagePartId.of(ref.image.data)
            if (id !in plan.droppedIds) continue
            val path = resolvedPaths[id]
            val placeholder = AgentContentPart.Text(ImageBudget.elidedImagePlaceholder(path))
            val originalPart = newParts[ref.partIdx]
            newParts[ref.partIdx] = when (originalPart) {
                is AgentContentPart.ImageData -> placeholder
                is AgentContentPart.ToolResult -> originalPart.copy(
                    // Strip the bytes but keep the structural ToolResult
                    // role; append the elision marker into content so
                    // the model sees it next to the rest of the tool
                    // output. linux path remains in the part for any
                    // subsequent diagnostic round-trip.
                    imageData = null,
                    imageMimeType = null,
                    content = originalPart.content +
                        (if (originalPart.content.isEmpty()) "" else "\n") +
                        ImageBudget.elidedImagePlaceholder(path),
                )
                else -> originalPart
            }
        }
        mutated[mi] = msg.copy(contentParts = newParts)
    }

    _requestBudgetEvent.tryEmit(plan)
    AppLogger.info(
        ChatViewModel.TAG,
        "applyRequestImageBudget: dropped=${plan.droppedCount}/${plan.totalCount} keptBytes=${plan.keptBytes}B elidedBytes=${plan.elidedBytes}B",
    )
    return mutated
}
