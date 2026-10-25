package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillSubscriptionStoreTest {
    @Test
    fun parseCatalogSkills() {
        val body = """
            {"name":"demo","skills":[
              {"url":"https://example.com/SKILL.md","sha256":"abc","name":"demo-skill"}
            ]}
        """.trimIndent()
        val catalog = SkillSubscriptionStore.parseCatalog(body)!!
        assertEquals("demo", catalog.name)
        assertEquals(1, catalog.skills.size)
        assertEquals("abc", catalog.skills[0].sha256)
    }

    @Test
    fun pinMatchesIsCaseInsensitive() {
        val hash = SkillSubscriptionStore.sha256Hex("hello")
        assertTrue(SkillSubscriptionStore.pinMatches(hash.uppercase(), hash))
        assertFalse(SkillSubscriptionStore.pinMatches("deadbeef", hash))
        assertTrue(SkillSubscriptionStore.pinMatches(null, hash))
    }

    @Test
    fun parseFeedsRoundTripShape() {
        val raw = """[{"id":"1","url":"https://x/SKILL.md","label":"x","sha256Pin":"aa","autoUpdate":true}]"""
        val feeds = SkillSubscriptionStore.parseFeeds(raw)
        assertEquals(1, feeds.size)
        assertEquals("https://x/SKILL.md", feeds[0].url)
        assertTrue(feeds[0].autoUpdate)
    }
}
