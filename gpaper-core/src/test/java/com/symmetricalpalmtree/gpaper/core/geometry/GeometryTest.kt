package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    // ── distancePointToSegment ───────────────────────────────────────────────

    @Test
    fun `distance to degenerate segment is point distance`() {
        assertEquals(5f, Geometry.distancePointToSegment(3f, 4f, 0f, 0f, 0f, 0f), 1e-4f)
    }

    @Test
    fun `perpendicular foot inside segment`() {
        // Segment (0,0)-(10,0); point above the middle.
        assertEquals(4f, Geometry.distancePointToSegment(5f, 4f, 0f, 0f, 10f, 0f), 1e-4f)
    }

    @Test
    fun `foot clamped to endpoints`() {
        // Point beyond B: distance measured to B, not the infinite line.
        assertEquals(5f, Geometry.distancePointToSegment(13f, 4f, 0f, 0f, 10f, 0f), 1e-4f)
        // Point before A.
        assertEquals(5f, Geometry.distancePointToSegment(-3f, -4f, 0f, 0f, 10f, 0f), 1e-4f)
    }

    @Test
    fun `point on segment is distance zero`() {
        assertEquals(0f, Geometry.distancePointToSegment(5f, 5f, 0f, 0f, 10f, 10f), 1e-4f)
    }

    // ── pointInPolygon ───────────────────────────────────────────────────────

    private fun square() = listOf(
        StrokePoint(0f, 0f), StrokePoint(10f, 0f),
        StrokePoint(10f, 10f), StrokePoint(0f, 10f),
    )

    @Test
    fun `inside and outside a square`() {
        assertTrue(Geometry.pointInPolygon(5f, 5f, square()))
        assertFalse(Geometry.pointInPolygon(15f, 5f, square()))
        assertFalse(Geometry.pointInPolygon(5f, -1f, square()))
    }

    @Test
    fun `concave polygon notch is outside`() {
        // A "C" shape: square with a rectangular bite from the right side.
        val c = listOf(
            StrokePoint(0f, 0f), StrokePoint(10f, 0f), StrokePoint(10f, 3f),
            StrokePoint(4f, 3f), StrokePoint(4f, 7f), StrokePoint(10f, 7f),
            StrokePoint(10f, 10f), StrokePoint(0f, 10f),
        )
        assertTrue(Geometry.pointInPolygon(2f, 5f, c))   // in the spine
        assertFalse(Geometry.pointInPolygon(7f, 5f, c))  // in the notch
        assertTrue(Geometry.pointInPolygon(7f, 1f, c))   // upper arm
    }

    @Test
    fun `fewer than three points is never inside`() {
        assertFalse(Geometry.pointInPolygon(0f, 0f, emptyList()))
        assertFalse(Geometry.pointInPolygon(0f, 0f, listOf(StrokePoint(0f, 0f))))
        assertFalse(
            Geometry.pointInPolygon(0f, 0f, listOf(StrokePoint(-1f, 0f), StrokePoint(1f, 0f)))
        )
    }

    @Test
    fun `open outline is treated as closed`() {
        // Triangle listed without repeating the first point — closure is implicit.
        val tri = listOf(StrokePoint(0f, 0f), StrokePoint(10f, 0f), StrokePoint(5f, 9f))
        assertTrue(Geometry.pointInPolygon(5f, 3f, tri))
        assertFalse(Geometry.pointInPolygon(0f, 9f, tri))
    }

    // ── polylineIntersectsCircle ─────────────────────────────────────────────

    private fun line() = listOf(
        StrokePoint(0f, 0f), StrokePoint(10f, 0f), StrokePoint(10f, 10f),
    )

    @Test
    fun `circle crossing a segment hits`() {
        assertTrue(Geometry.polylineIntersectsCircle(line(), 5f, 2f, 3f))
    }

    @Test
    fun `circle exactly at radius distance hits (inclusive)`() {
        assertTrue(Geometry.polylineIntersectsCircle(line(), 5f, 3f, 3f))
    }

    @Test
    fun `circle beyond radius misses`() {
        assertFalse(Geometry.polylineIntersectsCircle(line(), 5f, 3.01f, 3f))
    }

    @Test
    fun `hit on later segment is found`() {
        assertTrue(Geometry.polylineIntersectsCircle(line(), 12f, 8f, 2.5f))
    }

    @Test
    fun `single point polyline uses point distance`() {
        val dot = listOf(StrokePoint(5f, 5f))
        assertTrue(Geometry.polylineIntersectsCircle(dot, 6f, 5f, 1f))
        assertFalse(Geometry.polylineIntersectsCircle(dot, 7f, 5f, 1f))
    }

    @Test
    fun `empty polyline never hits`() {
        assertFalse(Geometry.polylineIntersectsCircle(emptyList(), 0f, 0f, 100f))
    }

    // ── polylineIntersectsRect (0.1.4) ───────────────────────────────────────

    @Test
    fun `rect - point inside hits, point outside misses`() {
        val rect = Bounds(10f, 10f, 20f, 20f)
        assertTrue(Geometry.polylineIntersectsRect(listOf(StrokePoint(15f, 15f)), rect))
        assertTrue(Geometry.polylineIntersectsRect(listOf(StrokePoint(10f, 10f)), rect)) // edge counts
        assertFalse(Geometry.polylineIntersectsRect(listOf(StrokePoint(5f, 5f)), rect))
    }

    @Test
    fun `rect - segment crossing straight through hits with no endpoint inside`() {
        val rect = Bounds(10f, 10f, 20f, 20f)
        val through = listOf(StrokePoint(0f, 15f), StrokePoint(30f, 15f))
        assertTrue(Geometry.polylineIntersectsRect(through, rect))
    }

    @Test
    fun `rect - segment passing beside the rect misses`() {
        val rect = Bounds(10f, 10f, 20f, 20f)
        val beside = listOf(StrokePoint(0f, 25f), StrokePoint(30f, 25f))
        assertFalse(Geometry.polylineIntersectsRect(beside, rect))
    }

    @Test
    fun `rect - polyline fully inside hits via containment`() {
        val rect = Bounds(0f, 0f, 100f, 100f)
        val inside = listOf(StrokePoint(40f, 40f), StrokePoint(60f, 60f))
        assertTrue(Geometry.polylineIntersectsRect(inside, rect))
    }

    @Test
    fun `rect - empty polyline never hits`() {
        assertFalse(Geometry.polylineIntersectsRect(emptyList(), Bounds(0f, 0f, 10f, 10f)))
    }
}
