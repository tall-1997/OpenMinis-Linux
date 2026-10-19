package com.openminis.app.ui.chat

import com.openminis.app.scheduled.ScheduledTaskMarker

/**
 * [T-android-scheduled-task-card] A fired scheduled task's envelope, rendered
 * as a card instead of a user bubble. Same wire shape as
 * [FlatChatItem.UserBubble] — the message IS the persisted user row, and its id
 * is the key — but the marker is parsed once at flatten time, so the render
 * site never re-parses the envelope on recomposition.
 *
 * Top-level rather than nested inside [FlatChatItem] so the row and the two
 * predicates that must agree with it live in one file; a sealed hierarchy
 * allows its subclasses in another file of the same package.
 */
internal class ScheduledTaskCardItem(
    val message: ChatMessage,
    val marker: ScheduledTaskMarker,
    private val keySuffix: String = "",
) : FlatChatItem() {
    override val key = FlatKeys.of(FlatKeys.KIND_SCHEDULED, message.id) + keySuffix
    override val contentType = "scheduled"
    override fun withKeySuffix(suffix: String): FlatChatItem = ScheduledTaskCardItem(message, marker, suffix)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScheduledTaskCardItem) return false
        return message == other.message
    }
    override fun hashCode(): Int = message.hashCode()
}

/**
 * The card for an envelope-tagged prompt, or null when the content is not one.
 *
 * A scheduled task's prompt arrives through the ordinary prompt path, so on
 * the wire it IS a user row; recognising the envelope is what lifts it into a
 * card instead of leaving the reader with text they never wrote. A message
 * that does not parse (another build's tag, a user typing the tag by hand)
 * returns null and falls through to an ordinary bubble: showing the raw text
 * beats showing nothing.
 */
internal fun scheduledRow(message: ChatMessage): ScheduledTaskCardItem? =
    ScheduledTaskMarker.parse(message.content)?.let { marker ->
        ScheduledTaskCardItem(message, marker)
    }

/**
 * [T-android-fab-up-target] True when this message renders as a real user
 * bubble — something the user actually typed and can navigate back to.
 *
 * `role == "user"` is NOT that test. A scheduled task's prompt is written with
 * the user role ([ScheduledTaskCardItem] renders it as its own card), so
 * anything walking "the user's turns" has to agree with what
 * [buildFlatChatItems] actually emitted, or it will aim at a turn that has no
 * `user:` row to land on — the up-button then sat on the wrong content and
 * looked dead.
 *
 * Kept beside the branch it mirrors so the two cannot drift apart. When
 * sub-agent callbacks land they render as their own card too and this
 * predicate must learn their shape as well.
 */
internal fun ChatMessage.rendersAsUserBubble(): Boolean =
    role == "user" && ScheduledTaskMarker.parse(content) == null

/**
 * A row that opens a user turn: the bubble the user typed, or the card a
 * scheduled task's envelope renders as. Spacing treats them alike — both are
 * turn boundaries — even though only the bubble is navigable/destructive.
 */
internal fun isUserTurnRow(item: FlatChatItem?): Boolean =
    item is FlatChatItem.UserBubble || item is ScheduledTaskCardItem
