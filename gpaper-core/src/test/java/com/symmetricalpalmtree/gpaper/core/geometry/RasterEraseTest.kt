package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pixel eraser must clear exactly the round-capped corridor of the sweep and nothing
 * outside it, the rect it announces must cover every pixel it clears (the host's undo
 * before-image is taken from that rect), and a fast flick split across batches must
 * leave no gap between them.
 */
class RasterEraseTest {

    private val page = 200 to 200
    private val radius = 10f

    private fun p(x: Float, y: Float) = StrokePoint(x, y)

    @Test
    fun `a single sample rubs a disc`() {
        val sweep = listOf(p(100f, 100f))
        assertTrue(RasterErase.covers(sweep, radius, 100f, 100f))
        assertTrue(RasterErase.covers(sweep, radius, 109f, 100f))
        assertTrue(RasterErase.covers(sweep, radius, 107f, 107f)) // ~9.9 away
        assertFalse(RasterErase.covers(sweep, radius, 111f, 100f))
        assertFalse(RasterErase.covers(sweep, radius, 108f, 108f)) // ~11.3 away
    }

    @Test
    fun `the corridor is the disc swept along the segment, with round ends`() {
        val sweep = listOf(p(50f, 100f), p(150f, 100f))
        // Along the body: within the radius across, not beyond it.
        assertTrue(RasterErase.covers(sweep, radius, 100f, 109f))
        assertFalse(RasterErase.covers(sweep, radius, 100f, 111f))
        // The ends are round: a point past the end is inside only within the radius of
        // the end sample, so the diagonal corner of a square cap is outside.
        assertTrue(RasterErase.covers(sweep, radius, 158f, 100f))
        assertFalse(RasterErase.covers(sweep, radius, 158f, 108f)) // ~11.3 from the end
        assertFalse(RasterErase.covers(sweep, radius, 161f, 100f))
    }

    @Test
    fun `nothing outside the corridor is touched`() {
        val sweep = listOf(p(50f, 50f), p(150f, 50f), p(150f, 150f))
        // The inside corner of the bend is not filled in: a pixel a little inside the
        // elbow but more than a radius from both segments stays.
        assertFalse(RasterErase.covers(sweep, radius, 130f, 70f))
        // A pixel exactly a radius from the inside corner of the join is in.
        assertTrue(RasterErase.covers(sweep, radius, 141f, 59f))
    }

    @Test
    fun `the batch rect covers every pixel the corridor clears`() {
        val sweep = listOf(p(30.5f, 40.2f), p(120.7f, 90.9f), p(80f, 160f))
        val rect = RasterErase.batchRect(sweep, radius, page.first, page.second)!!
        // Bounds pushed out by radius + margin, snapped outward.
        assertEquals(Bounds(18f, 28f, 133f, 172f), rect)
        // Walk the page: anything the corridor covers must be inside the rect.
        for (y in 0 until page.second) for (x in 0 until page.first) {
            val cx = x + 0.5f
            val cy = y + 0.5f
            if (RasterErase.covers(sweep, radius, cx, cy)) {
                assertTrue(
                    "pixel ($x,$y) is cleared but outside the batch rect",
                    cx >= rect.left && cx <= rect.right && cy >= rect.top && cy <= rect.bottom,
                )
            }
        }
    }

    @Test
    fun `chained batches leave no gap on a fast flick`() {
        // Two batches far apart, the way a flick arrives. Chained: the second batch is
        // handed the first batch's last sample in front.
        val first = listOf(p(20f, 100f), p(40f, 100f))
        val second = listOf(p(160f, 100f), p(180f, 100f))
        val chained = listOf(first.last()) + second
        // Every pixel along the line between the two batches is in one corridor or the other.
        for (x in 20..180) {
            val inFirst = RasterErase.covers(first, radius, x.toFloat(), 100f)
            val inSecond = RasterErase.covers(chained, radius, x.toFloat(), 100f)
            assertTrue("gap at x=$x", inFirst || inSecond)
        }
        // And without the chain there is a gap — the rule is load-bearing.
        assertFalse(RasterErase.covers(first, radius, 100f, 100f))
        assertFalse(RasterErase.covers(second, radius, 100f, 100f))
    }

    @Test
    fun `the batch rect stays on the page`() {
        val sweep = listOf(p(-5f, 3f), p(10f, 8f))
        val rect = RasterErase.batchRect(sweep, radius, page.first, page.second)!!
        assertEquals(Bounds(0f, 0f, 22f, 20f), rect)
    }

    @Test
    fun `a sweep wholly off the page announces nothing`() {
        val sweep = listOf(p(-40f, -40f), p(-30f, -30f))
        assertNull(RasterErase.batchRect(sweep, radius, page.first, page.second))
        assertNull(RasterErase.batchRect(emptyList(), radius, page.first, page.second))
    }
}
