package com.openminis.app.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderKeyGateTest {

    @Test
    fun `same key serializes overlapping calls`() = runBlocking {
        val order = mutableListOf<String>()
        val key = ProviderKeyGate.key("https://relay.example/v1", "sk-test", "flash")
        val a = launch {
            ProviderKeyGate.withPermit(key) {
                order.add("a-start")
                delay(40)
                order.add("a-end")
            }
        }
        val b = launch {
            delay(10)
            ProviderKeyGate.withPermit(key) {
                order.add("b")
            }
        }
        a.join()
        b.join()
        assertEquals(listOf("a-start", "a-end", "b"), order)
    }

    @Test
    fun `blank key does not block`() = runBlocking {
        val order = mutableListOf<String>()
        val a = launch {
            ProviderKeyGate.withPermit("") {
                delay(30)
                order.add("a")
            }
        }
        val b = launch {
            delay(5)
            ProviderKeyGate.withPermit("") {
                order.add("b")
            }
        }
        a.join()
        b.join()
        assertEquals(listOf("b", "a"), order)
    }

    @Test
    fun `nested same key does not deadlock`() = runBlocking {
        val key = ProviderKeyGate.key("https://relay.example/v1", "sk-nested")
        val result = ProviderKeyGate.withPermit(key) {
            ProviderKeyGate.withPermit(key) { "ok" }
        }
        assertEquals("ok", result)
    }

    @Test
    fun `same host different path shares a gate`() {
        val a = ProviderKeyGate.key("https://relay.example/v1", "sk-shared", "flash")
        val b = ProviderKeyGate.key("https://relay.example/openai/v1", "sk-shared", "FLASH")
        assertEquals(a, b)
        assertEquals("relay.example", ProviderKeyGate.hostOf("https://relay.example/v1"))
    }

    @Test
    fun `different models on same credential do not serialize`() = runBlocking {
        val order = mutableListOf<String>()
        val flash = ProviderKeyGate.key("https://relay.example/v1", "sk", "flash")
        val v3 = ProviderKeyGate.key("https://relay.example/v1", "sk", "v3")
        val a = launch {
            ProviderKeyGate.withPermit(flash) {
                delay(40)
                order.add("flash")
            }
        }
        val b = launch {
            delay(5)
            ProviderKeyGate.withPermit(v3) {
                order.add("v3")
            }
        }
        a.join()
        b.join()
        assertEquals(listOf("v3", "flash"), order)
    }

    @Test
    fun `same model name on different keys is a different bucket`() {
        val a = ProviderKeyGate.key("https://relay.example/v1", "sk-alice", "flash")
        val b = ProviderKeyGate.key("https://relay.example/v1", "sk-bob", "flash")
        assertNotEquals(a, b)
        assertFalse(ProviderKeyGate.sameBucket(a, b))
        assertTrue(
            ProviderKeyGate.sameBucket(
                ProviderKeyGate.key("https://relay.example/v1", "sk-alice", "flash"),
                ProviderKeyGate.key("https://relay.example/openai/v1", "sk-alice", "FLASH"),
            ),
        )
    }

    @Test
    fun `same model on different keys does not serialize`() = runBlocking {
        val order = mutableListOf<String>()
        val alice = ProviderKeyGate.key("https://relay.example/v1", "sk-alice", "flash")
        val bob = ProviderKeyGate.key("https://relay.example/v1", "sk-bob", "flash")
        val a = launch {
            ProviderKeyGate.withPermit(alice) {
                delay(40)
                order.add("alice")
            }
        }
        val b = launch {
            delay(5)
            ProviderKeyGate.withPermit(bob) {
                order.add("bob")
            }
        }
        a.join()
        b.join()
        assertEquals(listOf("bob", "alice"), order)
    }
}
