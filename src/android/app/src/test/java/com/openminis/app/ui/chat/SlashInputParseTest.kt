package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-slash-with-args] Token parsing for the slash intercept.
 *
 * The regression: `"/skill 用户自己的话"` (what the "/" picker produces when a
 * Skill is chosen over already-typed text) was matched against command titles
 * as ONE string, never matched, and was swallowed as "未知命令" — the user's
 * message was lost. Only the first token may be the command.
 */
class SlashInputParseTest {

    @Test
    fun `skill plus user text yields the skill token and a body`() {
        assertEquals("skill", SlashInputParse.commandToken("/skill 用户自己的话"))
        assertFalse(SlashInputParse.isBareCommand("/skill 用户自己的话"))
        assertEquals("用户自己的话", SlashInputParse.bodyAfterCommand("/skill 用户自己的话"))
    }

    @Test
    fun `full width slash is treated like ascii slash`() {
        assertEquals("技能", SlashInputParse.commandToken("／技能 帮我总结"))
        assertEquals("帮我总结", SlashInputParse.bodyAfterCommand("／技能 帮我总结"))
    }

    @Test
    fun `bare command has no body`() {
        assertEquals("clear", SlashInputParse.commandToken("/clear"))
        assertTrue(SlashInputParse.isBareCommand("/clear"))
        assertEquals("", SlashInputParse.bodyAfterCommand("/clear"))
    }

    @Test
    fun `leading whitespace and newlines do not hide the command`() {
        assertEquals("compact", SlashInputParse.commandToken("  /compact  "))
        assertEquals("下一行", SlashInputParse.bodyAfterCommand("/skill\n下一行"))
        assertEquals("compact", SlashInputParse.commandToken("/compact\n"))
    }

    @Test
    fun `plain text and bare slash have no command token`() {
        assertNull(SlashInputParse.commandToken("用户的话 /技能"))
        assertNull(SlashInputParse.commandToken("hello"))
        assertNull(SlashInputParse.commandToken("/"))
        assertNull(SlashInputParse.commandToken(""))
    }

    @Test
    fun `a command in the middle of the message is not a command token`() {
        // The send-path intercept only fires on a LEADING slash, so text that
        // merely mentions a slash command must reach the model untouched.
        assertNull(SlashInputParse.commandToken("我想用 /skill 做这个"))
    }
}
