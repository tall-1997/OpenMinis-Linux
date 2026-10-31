package com.openminis.app.data.db

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-message-projection-drift] Every full-row `messages` projection
 * must list the SAME 19 columns in the same order. The list lives in four
 * places: three Room `@Query` literals in ChatDao (annotations cannot
 * reference Kotlin constants) and [RawQuerySql.MESSAGE_PAGE_SELECT], which
 * builds its tail from `MESSAGE_TAIL_COLUMNS`. Adding a column to the entity
 * without touching all four used to be silent — Room would map the missing
 * one to null at runtime. This test makes the drift a failure instead.
 */
class MessageProjectionDriftTest {

    private val head = listOf("id", "session_id", "role", "parts_json")
    private val tail = RawQuerySql.MESSAGE_TAIL_COLUMNS.split(',').map { it.trim() }
    private val canonical = head + tail

    @Test
    fun `tail constant matches the canonical column order`() {
        assertEquals(
            listOf(
                "created_at", "token_usage", "sort_order", "reasoning_content",
                "stream_interrupt_count", "updated_at", "error_info", "model_id",
                "model_display_name", "provider_type", "provider_instance_id",
                "body_bytes", "body_ref", "body_sha", "preview",
            ),
            tail,
        )
    }

    @Test
    fun `every full-row projection in ChatDao lists the canonical columns`() {
        val daoSource = ProductionSources.read("data/db/ChatDao.kt")
        val projections = FULL_ROW_PROJECTION.findAll(daoSource).toList()
        assertTrue(
            "expected ≥3 full-row projections in ChatDao, found ${projections.size} — " +
                "did the extraction regex rot?",
            projections.size >= 3,
        )
        projections.forEachIndexed { i, match ->
            assertEquals(
                "projection #$i drifted from the canonical column list",
                canonical,
                columnsOf(match.value),
            )
        }
    }

    @Test
    fun `raw query page select covers the canonical columns`() {
        // The CASE spill contains commas, so split around the `END AS
        // parts_json` anchor instead of naively on ','.
        val sql = RawQuerySql.MESSAGE_PAGE_SELECT
        val headNames = sql.substringAfter("SELECT ").substringBefore(", CASE")
            .split(',').map { it.trim() }
        // parts_json here is the CASE spill alias, asserted via the anchor.
        assertEquals(listOf("id", "session_id", "role"), headNames)
        val tailNames = sql.substringAfter("END AS parts_json, ")
            .substringBefore(" FROM messages")
            .split(',').map { it.trim() }
        assertEquals(tail, tailNames)
    }

    /** Column names of one `SELECT id, session_id, role, parts_json, … FROM messages` text. */
    private fun columnsOf(sql: String): List<String> {
        val body = sql.substringAfter("parts_json").substringBefore("FROM messages")
        val names = head.take(3) + "parts_json" + Regex("[a-z_]+").findAll(body)
            .map { it.value }
            .filter { it !in SQL_NOISE }
            .toList()
        return names
    }

    private companion object {
        val SQL_NOISE = setOf("as", "select", "from")

        /** `SELECT id, … , preview FROM messages` spanning Kotlin string concat. */
        val FULL_ROW_PROJECTION = Regex(
            "SELECT id, session_id, role, parts_json,(?:[^;]*?)FROM messages",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}
