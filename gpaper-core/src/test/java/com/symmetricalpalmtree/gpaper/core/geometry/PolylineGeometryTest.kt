package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineGeometryTest {

    // ── distanceSegmentToSegment ─────────────────────────────────────────────

    @Test
    fun `crossing segments have zero distance`() {
        val d = Geometry.distanceSegmentToSegment(0f, 0f, 10f, 10f, 0f, 10f, 10f, 0f)
        assertEquals(0f, d, 1e-6f)
    }

    @Test
    fun `touching endpoints count as intersecting`() {
        val d = Geometry.distanceSegmentToSegment(0f, 0f, 10f, 0f, 10f, 0f, 20f, 5f)
        assertEquals(0f, d, 1e-6f)
    }

    @Test
    fun `parallel segments measure the gap`() {
        val d = Geometry.distanceSegmentToSegment(0f, 0f, 10f, 0f, 0f, 5f, 10f, 5f)
        assertEquals(5f, d, 1e-4f)
    }

    @Test
    fun `collinear overlapping segments intersect`() {
        val d = Geometry.distanceSegmentToSegment(0f, 0f, 10f, 0f, 5f, 0f, 20f, 0f)
        assertEquals(0f, d, 1e-6f)
    }

    @Test
    fun `degenerate segments degrade to point distance`() {
        // Both segments are points: (0,0) and (3,4) — distance 5.
        val d = Geometry.distanceSegmentToSegment(0f, 0f, 0f, 0f, 3f, 4f, 3f, 4f)
        assertEquals(5f, d, 1e-4f)
    }

    // ── polylineWithinDistance ───────────────────────────────────────────────

    private fun pts(vararg xy: Float): List<StrokePoint> {
        val out = ArrayList<StrokePoint>()
        var i = 0
        while (i < xy.size) {
            out.add(StrokePoint(xy[i], xy[i + 1]))
            i += 2
        }
        return out
    }

    @Test
    fun `empty polylines never match`() {
        assertFalse(Geometry.polylineWithinDistance(emptyList(), pts(0f, 0f), 100f))
        assertFalse(Geometry.polylineWithinDistance(pts(0f, 0f), emptyList(), 100f))
    }

    @Test
    fun `crossing polylines match at any distance`() {
        assertTrue(
            Geometry.polylineWithinDistance(
                pts(0f, 0f, 10f, 10f),
                pts(0f, 10f, 10f, 0f),
                0f,
            )
        )
    }

    @Test
    fun `near polylines match within the distance only`() {
        val a = pts(0f, 0f, 10f, 0f)
        val b = pts(0f, 6f, 10f, 6f)
        assertTrue(Geometry.polylineWithinDistance(a, b, 6f))
        assertFalse(Geometry.polylineWithinDistance(a, b, 5.9f))
    }

    @Test
    fun `single points act as degenerate segments`() {
        assertTrue(Geometry.polylineWithinDistance(pts(0f, 0f), pts(3f, 4f), 5f))
        assertFalse(Geometry.polylineWithinDistance(pts(0f, 0f), pts(3f, 4f), 4.9f))
    }

    // ── sampleAlongPolyline ──────────────────────────────────────────────────

    @Test
    fun `empty input or non-positive spacing samples nothing`() {
        assertEquals(emptyList<StrokePoint>(), Geometry.sampleAlongPolyline(emptyList(), 10f))
        assertEquals(emptyList<StrokePoint>(), Geometry.sampleAlongPolyline(pts(0f, 0f), 0f))
    }

    @Test
    fun `single point samples itself`() {
        val out = Geometry.sampleAlongPolyline(pts(5f, 7f), 10f)
        assertEquals(1, out.size)
        assertEquals(5f, out[0].x, 1e-6f)
        assertEquals(7f, out[0].y, 1e-6f)
    }

    @Test
    fun `straight line samples at exact spacing`() {
        val out = Geometry.sampleAlongPolyline(pts(0f, 0f, 100f, 0f), 25f)
        // Start + 25/50/75/100.
        assertEquals(5, out.size)
        assertEquals(0f, out[0].x, 1e-4f)
        assertEquals(25f, out[1].x, 1e-4f)
        assertEquals(50f, out[2].x, 1e-4f)
        assertEquals(75f, out[3].x, 1e-4f)
        assertEquals(100f, out[4].x, 1e-4f)
    }

    @Test
    fun `spacing accumulates across segments`() {
        // Two 30px segments with 40px spacing: samples at arc lengths 0 and 40.
        val out = Geometry.sampleAlongPolyline(pts(0f, 0f, 30f, 0f, 30f, 30f), 40f)
        assertEquals(2, out.size)
        assertEquals(30f, out[1].x, 1e-4f)
        assertEquals(10f, out[1].y, 1e-4f)
    }

    @Test
    fun `pressure interpolates linearly`() {
        val a = StrokePoint(0f, 0f, pressure = 0f)
        val b = StrokePoint(100f, 0f, pressure = 1f)
        val out = Geometry.sampleAlongPolyline(listOf(a, b), 50f)
        assertEquals(3, out.size)
        assertEquals(0.5f, out[1].pressure, 1e-4f)
    }
}
