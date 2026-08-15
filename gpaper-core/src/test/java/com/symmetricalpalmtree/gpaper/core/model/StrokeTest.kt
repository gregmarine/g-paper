package com.symmetricalpalmtree.gpaper.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeTest {

    private fun stroke() = Stroke(
        id = "s1",
        points = listOf(
            StrokePoint(1f, 2f, pressure = 0.5f, tilt = 0.3f, timeMillis = 100L),
            StrokePoint(4f, 6f, pressure = 0.7f, tilt = 0.4f, timeMillis = 116L),
        ),
        color = 0x7F123456,
        width = 5f,
        style = StrokeStyle.FOUNTAIN,
    )

    @Test
    fun `defaults are opaque black, default width, PEN style`() {
        val s = Stroke("s", listOf(StrokePoint(0f, 0f)))
        assertEquals(Stroke.BLACK, s.color)
        assertEquals(0xFF000000.toInt(), s.color)
        assertEquals(Stroke.DEFAULT_WIDTH, s.width, 0f)
        assertEquals(StrokeStyle.PEN, s.style)
    }

    @Test
    fun `point defaults are full pressure, no tilt, no time`() {
        val p = StrokePoint(1f, 2f)
        assertEquals(1f, p.pressure, 0f)
        assertEquals(0f, p.tilt, 0f)
        assertEquals(0L, p.timeMillis)
    }

    @Test
    fun `bounds computed from points at construction`() {
        assertEquals(Bounds(1f, 2f, 4f, 6f), stroke().bounds)
        assertEquals(Bounds.ZERO, Stroke("empty", emptyList()).bounds)
    }

    @Test
    fun `translated shifts points and bounds, preserves everything else`() {
        val s = stroke()
        val t = s.translated(10f, -1f)
        assertEquals("s1", t.id)
        assertEquals(s.color, t.color)
        assertEquals(s.width, t.width, 0f)
        assertEquals(StrokeStyle.FOUNTAIN, t.style)
        assertEquals(Bounds(11f, 1f, 14f, 5f), t.bounds)
        // Stylus data rides along untouched.
        assertEquals(0.5f, t.points[0].pressure, 0f)
        assertEquals(0.4f, t.points[1].tilt, 0f)
        assertEquals(116L, t.points[1].timeMillis)
        // Original unchanged (immutability).
        assertEquals(Bounds(1f, 2f, 4f, 6f), s.bounds)
    }

    @Test
    fun `translated with newId rekeys the copy`() {
        assertEquals("s2", stroke().translated(0f, 0f, newId = "s2").id)
    }

    @Test
    fun `copy with new points recomputes bounds`() {
        val moved = stroke().copy(points = listOf(StrokePoint(100f, 100f)))
        assertEquals(Bounds(100f, 100f, 100f, 100f), moved.bounds)
    }
}
