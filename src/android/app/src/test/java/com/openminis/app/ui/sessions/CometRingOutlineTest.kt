package com.openminis.app.ui.sessions

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * [T-android-comet-ring-outline] The running comet rides the folder avatar's
 * own rounded-rect outline instead of a circular orbit.
 *
 * The bug this pins: [CometRing] drew its orbit with drawArc over the whole
 * canvas — a circle that ignored the avatar's rounded corners, so the comet
 * floated free of the folder silhouette exactly where the shape bends (the
 * avatar is a 44dp rounded square; a circle through its diagonals leaves the
 * outline by ~4.7dp). The fix parameterizes the avatar's rounded-rectangle
 * perimeter ([roundedRectOutlinePoint]); these tests pin that walk so the
 * renderer can never drift back to a circle that ignores the folder's
 * geometry.
 */
class CometRingOutlineTest {

    private val w = 100f
    private val h = 100f
    private val r = 20f

    private fun pt(t: Float) = roundedRectOutlinePoint(w, h, r, t)

    private fun assertNear(expected: Offset, actual: Offset, eps: Float = 1e-3f) {
        assertTrue(
            "expected $expected but was $actual (eps=$eps)",
            abs(expected.x - actual.x) <= eps && abs(expected.y - actual.y) <= eps,
        )
    }

    @Test
    fun `phase zero starts where the top edge meets the top-left corner`() {
        assertNear(Offset(r, 0f), pt(0f))
    }

    @Test
    fun `a quarter of the perimeter lands at the top of the right edge`() {
        // Top edge (60) + top-right arc (10pi) is exactly P/4 for a square.
        assertNear(Offset(100f, 20f), pt(0.25f))
    }

    @Test
    fun `half the perimeter lands at the right end of the bottom edge`() {
        assertNear(Offset(80f, 100f), pt(0.5f))
    }

    @Test
    fun `three quarters lands at the bottom of the left edge`() {
        assertNear(Offset(0f, 80f), pt(0.75f))
    }

    @Test
    fun `phase wraps modulo one`() {
        assertEquals(pt(0.75f), pt(-0.25f))
        assertEquals(pt(0f), pt(1f))
        assertEquals(pt(0.25f), pt(1.25f))
    }

    @Test
    fun `every sampled point stays on the outline`() {
        val corners = listOf(
            Offset(r, r), Offset(w - r, r), Offset(w - r, h - r), Offset(r, h - r),
        )
        val eps = 1e-3f
        for (i in 0 until 720) {
            val t = i / 720f
            val p = pt(t)
            val onEdge = (
                (abs(p.y) <= eps && p.x >= r - eps && p.x <= w - r + eps) ||
                    (abs(p.x - w) <= eps && p.y >= r - eps && p.y <= h - r + eps) ||
                    (abs(p.y - h) <= eps && p.x >= r - eps && p.x <= w - r + eps) ||
                    (abs(p.x) <= eps && p.y >= r - eps && p.y <= h - r + eps)
                )
            val onCorner = corners.any { c ->
                val d = sqrt((p.x - c.x) * (p.x - c.x) + (p.y - c.y) * (p.y - c.y))
                abs(d - r) <= eps
            }
            assertTrue("point $p at t=$t left the rounded-rect outline", onEdge || onCorner)
        }
    }

    @Test
    fun `progress is clockwise along the top edge`() {
        val a = pt(0.01f)
        val b = pt(0.02f)
        assertTrue("both on the top edge", a.y == 0f && b.y == 0f)
        assertTrue("x grows with t ($a -> $b)", b.x > a.x)
    }

    @Test
    fun `zero corner radius collapses to the sharp rectangle`() {
        // P = 400 for a sharp square; each quarter is exactly a corner.
        assertNear(Offset(0f, 0f), roundedRectOutlinePoint(w, h, 0f, 0f))
        assertNear(Offset(100f, 0f), roundedRectOutlinePoint(w, h, 0f, 0.25f))
        assertNear(Offset(100f, 100f), roundedRectOutlinePoint(w, h, 0f, 0.5f))
        assertNear(Offset(0f, 100f), roundedRectOutlinePoint(w, h, 0f, 0.75f))
    }

    @Test
    fun `corner radius clamps to half the shorter side`() {
        // r=200 on a 100x100 square clamps to 50: a stadium.
        assertNear(Offset(50f, 0f), roundedRectOutlinePoint(w, h, 200f, 0f))
        assertNear(Offset(100f, 50f), roundedRectOutlinePoint(w, h, 200f, 0.25f))
    }

    @Test
    fun `degenerate zero size returns the origin without throwing`() {
        assertNear(Offset(0f, 0f), roundedRectOutlinePoint(0f, 0f, 0f, 0.5f))
    }
}
