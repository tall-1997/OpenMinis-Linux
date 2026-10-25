package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelGroupBucketsTest {

    @Test
    fun `same model on different keys is not a duplicate`() {
        val a = ModelGroupBuckets.bucket("https://relay.example/v1", "sk-alice", "flash")
        val b = ModelGroupBuckets.bucket("https://relay.example/v1", "sk-bob", "flash")
        assertFalse(a == b)
        assertTrue(ModelGroupBuckets.duplicateBuckets(listOf(a, b)).isEmpty())
    }

    @Test
    fun `same key and model is a duplicate`() {
        val a = ModelGroupBuckets.bucket("https://relay.example/v1", "sk-alice", "flash")
        val b = ModelGroupBuckets.bucket("https://relay.example/openai/v1", "sk-alice", "FLASH")
        assertEquals(a, b)
        assertEquals(setOf(a), ModelGroupBuckets.duplicateBuckets(listOf(a, b)))
    }

    @Test
    fun `caption hides the raw key`() {
        val bucket = ModelGroupBuckets.bucket("https://relay.example/v1", "sk-secret-value", "glm-5")
        val cap = ModelGroupBuckets.caption(bucket)
        assertFalse(cap.contains("sk-secret-value"))
        assertTrue(cap.contains("glm-5"))
    }
}
