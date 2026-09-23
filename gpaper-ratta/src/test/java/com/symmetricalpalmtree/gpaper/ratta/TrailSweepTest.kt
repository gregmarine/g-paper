package com.symmetricalpalmtree.gpaper.ratta

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.hypot

/**
 * The app-painted trail's pure half (Phase 42): each segment starts at the last point
 * laid, its dash phase is where the path already is, and the pieces add up to the path.
 */
class TrailSweepTest {

    private fun pt(x: Float, y: Float) = StrokePoint(x, y, 1f, 0f, 0L)

    private val path = listOf(
        pt(0f, 0f), pt(10f, 0f), pt(10f, 10f), pt(30f, 10f), pt(30f, 40f), pt(35f, 40f), pt(35f, 41f),
    )

    private fun lengthOf(points: List<StrokePoint>): Float {
        var l = 0f
        for (i in 1 until points.size) l += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        return l
    }

    @Test
    fun `a first point is a dot and nothing new is null`() {
        val s = TrailSweep(20f)
        val seg = s.advance(path.subList(0, 1))
        assertNotNull(seg)
        assertEquals(0, seg!!.from)
        assertEquals(1, seg.to)
        assertEquals(0f, seg.phase, 0f)
        assertNull(s.advance(path.subList(0, 1)))
    }

    @Test
    fun `segments share their join and cover the path once`() {
        val s = TrailSweep(20f)
        val cuts = listOf(1, 3, 4, 7)
        var lastTo = 0
        for (c in cuts) {
            val seg = s.advance(path.subList(0, c))!!
            assertEquals(if (lastTo == 0) 0 else lastTo - 1, seg.from)
            assertEquals(c, seg.to)
            lastTo = c
        }
        assertEquals(path.size, s.laid)
        assertEquals(lengthOf(path), s.length, 1e-4f)
    }

    @Test
    fun `the phase continues the pattern across joins as one path would`() {
        val period = 20f
        val whole = TrailSweep(period)
        whole.advance(path)
        val pieces = TrailSweep(period)
        var phases = ArrayList<Float>()
        for (c in listOf(2, 3, 5, 7)) phases.add(pieces.advance(path.subList(0, c))!!.phase)
        // Each piece's phase is the arc length laid before it, modulo the period — which is
        // exactly the offset the dash pattern of the whole path would have reached there.
        assertEquals(0f, phases[0], 0f)
        assertEquals(lengthOf(path.subList(0, 2)) % period, phases[1], 1e-4f)
        assertEquals(lengthOf(path.subList(0, 3)) % period, phases[2], 1e-4f)
        assertEquals(lengthOf(path.subList(0, 5)) % period, phases[3], 1e-4f)
        assertEquals(whole.length, pieces.length, 1e-4f)
    }

    @Test
    fun `a segment's bounds are its own points, join included`() {
        val s = TrailSweep(20f)
        s.advance(path.subList(0, 3))
        val seg = s.advance(path.subList(0, 5))!!
        assertEquals(10f, seg.minX, 0f)
        assertEquals(30f, seg.maxX, 0f)
        assertEquals(10f, seg.minY, 0f)
        assertEquals(40f, seg.maxY, 0f)
    }
}
