package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-fab-up-target] The scroll-to-previous-turn button walks "the
 * user's turns". This pins what that means.
 *
 * `role == "user"` is NOT that test: a scheduled task's prompt is written with
 * the user role and renders as its own card instead of a `user:` bubble.
 * Counting them made the button aim at turns with no row to land on, so it
 * either stopped on the wrong content or fell through to RESTORE and looked
 * dead.
 *
 * These assertions are the contract between the walk and
 * [buildFlatChatItems]: anything that returns true here must be emitted as a
 * [FlatChatItem.UserBubble] there, and anything false must not be.
 *
 * Upstream's predicate also excludes sub-agent callbacks; this fork has no
 * callback cards yet, so there is no arm for them here. When they land, the
 * predicate and this test both gain one.
 */
class UserBubbleTargetTest {

    private fun msg(role: String, content: String) =
        ChatMessage(id = "m1", role = role, content = content)

    @Test
    fun `a typed message is a user bubble`() {
        assertTrue(msg("user", "帮我查一下今天的天气").rendersAsUserBubble())
    }

    /** Content that merely mentions the marker is still the user talking. */
    @Test
    fun `a message that only talks about the markers is still a bubble`() {
        assertTrue(msg("user", "the scheduled_task tag confused me").rendersAsUserBubble())
    }

    @Test
    fun `a scheduled task prompt is not a user bubble`() {
        assertFalse(
            msg("user", "<scheduled_task id=\"t1\">check the build</scheduled_task>")
                .rendersAsUserBubble(),
        )
    }

    /**
     * A marker with no `id` is NOT a scheduled card — [ScheduledTaskMarker.parse]
     * requires the id and returns null without it, so [buildFlatChatItems] falls
     * through and emits a plain bubble. The predicate has to agree, or the walk
     * would skip a row that really is on screen.
     */
    @Test
    fun `an id-less scheduled marker still renders as a bubble`() {
        assertTrue(msg("user", "<scheduled_task>bare</scheduled_task>").rendersAsUserBubble())
    }

    @Test
    fun `assistant and system messages are never user bubbles`() {
        assertFalse(msg("assistant", "here you go").rendersAsUserBubble())
        assertFalse(msg("system", "you are a helpful assistant").rendersAsUserBubble())
    }

    /**
     * The predicate and the card branch must test the SAME thing: a message
     * the walk calls a bubble but [buildFlatChatItems] turns into a card (or
     * vice versa) is the original bug in either direction. Both halves live in
     * ScheduledTaskRow.kt now; the flattening loop reaches the row builder
     * through [scheduledRow], which is what this pins.
     */
    @Test
    fun `the predicate mirrors the card branch`() {
        val rows = ProductionSources.read("ui/chat/ScheduledTaskRow.kt")
        val items = ProductionSources.read("ui/chat/ChatFlatItems.kt")
        assertTrue(rows.contains("ScheduledTaskMarker.parse(message.content)?.let { marker ->"))
        assertTrue(rows.contains("role == \"user\" && ScheduledTaskMarker.parse(content) == null"))
        assertTrue(items.contains("val scheduled = scheduledRow(message)"))
    }
}
