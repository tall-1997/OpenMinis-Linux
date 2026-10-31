package com.openminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationContractTest {
    @Test
    fun `validate returns NO_OBSERVATION when nothing published`() {
        assertEquals(
            ObservationReferencePolicy.Status.NO_OBSERVATION,
            ObservationReferencePolicy.validate(null, "o1"),
        )
    }

    @Test
    fun `validate requires an id when one is published`() {
        assertEquals(
            ObservationReferencePolicy.Status.ID_REQUIRED,
            ObservationReferencePolicy.validate("o1", ""),
        )
        assertEquals(
            ObservationReferencePolicy.Status.ID_REQUIRED,
            ObservationReferencePolicy.validate("o1", null),
        )
    }

    @Test
    fun `validate rejects stale ids`() {
        assertEquals(
            ObservationReferencePolicy.Status.STALE,
            ObservationReferencePolicy.validate("o2", "o1"),
        )
    }

    @Test
    fun `validate accepts matching id`() {
        assertEquals(
            ObservationReferencePolicy.Status.MATCH,
            ObservationReferencePolicy.validate("o1", "o1"),
        )
    }

    @Test
    fun `node action without any observation is refused`() {
        val store = ObservationStore()
        val err = store.checkNodeAction("o1", "a3f2")
        assertEquals("NO_OBSERVATION", err?.first)
    }

    @Test
    fun `node action without observation id is refused`() {
        val store = ObservationStore()
        store.publish(PublishedObservation("o1", 0L, "pkg", setOf("a3f2")))
        val err = store.checkNodeAction(null, "a3f2")
        assertEquals("OBSERVATION_ID_REQUIRED", err?.first)
    }

    @Test
    fun `node action with stale observation is refused`() {
        val store = ObservationStore()
        store.publish(PublishedObservation("o1", 0L, "pkg", setOf("a3f2")))
        store.publish(PublishedObservation("o2", 1L, "pkg", setOf("b4c3")))
        val err = store.checkNodeAction("o1", "a3f2")
        assertEquals("STALE_OBSERVATION", err?.first)
    }

    @Test
    fun `node id outside its observation is refused`() {
        val store = ObservationStore()
        store.publish(PublishedObservation("o1", 0L, "pkg", setOf("a3f2")))
        assertEquals("NODE_NOT_IN_OBSERVATION", store.checkNodeAction("o1", "ffff")?.first)
        assertNull(store.checkNodeAction("o1", "a3f2"))
    }
}
