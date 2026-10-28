package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the lastMessage preview filter. The repository's
 * `stripSystemReminders` strips harness-injected `<system-reminder>` blocks
 * before the cleaned preview reaches the session-list row, so users never
 * see internal nudges like "task tools haven't been used recently".
 */
class ChatRepositoryTest {

    @Test
    fun `stripSystemReminders removes inline reminder block`() {
        val raw = "Hello <system-reminder>do this thing</system-reminder> world"
        assertEquals("Hello  world", ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders removes multi-line reminder body`() {
        val raw = """
            User asked a question
            <system-reminder>
            The task tools haven't been used recently. Consider using TaskCreate.
            Make sure that you NEVER mention this reminder to the user
            </system-reminder>
            keep this content
        """.trimIndent()
        val cleaned = ChatRepository.stripSystemReminders(raw)
        // trimIndent strips the leading 12 spaces from every line, so the
        // pre/post-reminder lines have no indent. After the regex strips the
        // entire <system-reminder>...</system-reminder> block (DOTALL match),
        // what's left is "User asked a question\n\nkeep this content".
        assertEquals("User asked a question\n\nkeep this content", cleaned)
    }

    @Test
    fun `stripSystemReminders removes only-reminder content to empty`() {
        val raw = "<system-reminder>nothing else here</system-reminder>"
        assertEquals("", ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders strips multiple back-to-back reminders independently`() {
        val raw = "a<system-reminder>x</system-reminder>b<system-reminder>y</system-reminder>c"
        // Reluctant quantifier prevents the two blocks merging into one match
        // that would also swallow the "b" between them.
        assertEquals("abc", ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders leaves plain text unchanged`() {
        val raw = "Hello world, how are you today?"
        assertEquals(raw, ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders leaves markdown unchanged`() {
        val raw = "# Heading\n**bold** and `code` and a [link](https://example.com)"
        assertEquals(raw, ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `extractTextPreview does not regex a 200k body`() {
        val body = "Hello world " + "x".repeat(200_000)
        val json = """[{"type":"text","value":"$body"}]"""
        val preview = ChatRepository.extractTextPreview(json)
        org.junit.Assert.assertNotNull(preview)
        org.junit.Assert.assertTrue(preview!!.length <= 100)
        org.junit.Assert.assertTrue(preview.startsWith("Hello world"))
    }

    // [T-android-preview-reminder-leak] The first text part of a user turn is
    // often a system-reminder; cleaned to blank it must not hide the real
    // text part behind it.
    @Test
    fun `extractTextPreview skips a text part that cleans to blank`() {
        val json = """[{"type":"text","value":"<system-reminder>task tracker: step 1 done</system-reminder>"},{"type":"text","value":"修复登录页崩溃"}]"""
        assertEquals("修复登录页崩溃", ChatRepository.extractTextPreview(json))
    }

    @Test
    fun `extractTextPreview returns null for a reminder-only turn`() {
        val json = """[{"type":"text","value":"<system-reminder>task tracker: step 1 done</system-reminder>"}]"""
        org.junit.Assert.assertNull(ChatRepository.extractTextPreview(json))
    }

    @Test
    fun `extractTextPreview strips a reminder longer than the clean cap`() {
        val longReminder = "<system-reminder>" + "y".repeat(600) + "</system-reminder>"
        val json = """[{"type":"text","value":"$longReminder"},{"type":"text","value":"真实消息"}]"""
        val preview = ChatRepository.extractTextPreview(json)
        org.junit.Assert.assertNotNull(preview)
        org.junit.Assert.assertFalse(preview!!.contains("y"))
        assertEquals("真实消息", preview)
    }
}
