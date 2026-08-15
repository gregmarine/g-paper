package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LassoHitTestTest {

    private fun stroke(id: String, vararg xy: Float): Stroke {
        val points = ArrayList<StrokePoint>()
        var i = 0
        while (i < xy.size) {
            points.add(StrokePoint(xy[i], xy[i + 1]))
            i += 2
        }
        return Stroke(id = id, points = points)
    }

    private fun polygon(vararg xy: Float): List<StrokePoint> {
        val points = ArrayList<StrokePoint>()
        var i = 0
        while (i < xy.size) {
            points.add(StrokePoint(xy[i], xy[i + 1]))
            i += 2
        }
        return points
    }

    /** A 100×100 square lasso from (0,0) to (100,100). */
    private val square = polygon(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)

    // ── hitStrokeIds ─────────────────────────────────────────────────────────

    @Test
    fun `degenerate polygon selects nothing`() {
        val s = stroke("a", 50f, 50f)
        assertEquals(emptyList<String>(), LassoHitTest.hitStrokeIds(listOf(s), emptyList()))
        assertEquals(emptyList<String>(), LassoHitTest.hitStrokeIds(listOf(s), polygon(0f, 0f, 100f, 100f)))
    }

    @Test
    fun `stroke fully inside is selected`() {
        val s = stroke("a", 20f, 20f, 60f, 60f)
        assertEquals(listOf("a"), LassoHitTest.hitStrokeIds(listOf(s), square))
    }

    @Test
    fun `stroke partially inside is selected — touch semantics`() {
        val s = stroke("a", 50f, 50f, 300f, 300f)
        assertEquals(listOf("a"), LassoHitTest.hitStrokeIds(listOf(s), square))
    }

    @Test
    fun `stroke fully outside is not selected`() {
        val s = stroke("a", 200f, 200f, 300f, 300f)
        assertEquals(emptyList<String>(), LassoHitTest.hitStrokeIds(listOf(s), square))
    }

    @Test
    fun `stroke crossing the lasso with no point inside is not selected`() {
        // Both samples outside; the segment slices through the square. Per-point
        // semantics (deliberate, matching the reference engines): not selected.
        val s = stroke("a", -50f, 50f, 150f, 50f)
        assertEquals(emptyList<String>(), LassoHitTest.hitStrokeIds(listOf(s), square))
    }

    @Test
    fun `concave outline excludes points in the notch`() {
        // U-shape: open notch from x=25..75 above y=50.
        val u = polygon(
            0f, 0f, 25f, 0f, 25f, 50f, 75f, 50f, 75f, 0f,
            100f, 0f, 100f, 100f, 0f, 100f,
        )
        val inNotch = stroke("notch", 50f, 25f)
        val inBody = stroke("body", 50f, 75f)
        assertEquals(listOf("body"), LassoHitTest.hitStrokeIds(listOf(inNotch, inBody), u))
    }

    @Test
    fun `order follows the stroke list and each id reported once`() {
        val a = stroke("a", 10f, 10f, 20f, 20f)
        val b = stroke("b", 30f, 30f, 40f, 40f)
        assertEquals(listOf("a", "b"), LassoHitTest.hitStrokeIds(listOf(a, b), square))
    }

    // ── polygonIntersectsBounds ──────────────────────────────────────────────

    @Test
    fun `rect fully inside polygon intersects`() {
        assertTrue(LassoHitTest.polygonIntersectsBounds(square, Bounds(40f, 40f, 60f, 60f)))
    }

    @Test
    fun `polygon fully inside rect intersects`() {
        assertTrue(LassoHitTest.polygonIntersectsBounds(square, Bounds(-100f, -100f, 200f, 200f)))
    }

    @Test
    fun `partial overlap intersects`() {
        assertTrue(LassoHitTest.polygonIntersectsBounds(square, Bounds(80f, 80f, 200f, 200f)))
    }

    @Test
    fun `disjoint rect does not intersect`() {
        assertFalse(LassoHitTest.polygonIntersectsBounds(square, Bounds(200f, 200f, 300f, 300f)))
    }

    @Test
    fun `edge crossing with no vertex containment intersects`() {
        // A long thin rect sliced by the square's right edge: the rect spans x=90..110
        // at y=-10..-5 — above the square, so shift it into range: y=45..55 crossing x=100.
        assertTrue(LassoHitTest.polygonIntersectsBounds(square, Bounds(90f, 45f, 110f, 55f)))
    }

    @Test
    fun `notch rect in concave polygon does not intersect`() {
        val u = polygon(
            0f, 0f, 25f, 0f, 25f, 50f, 75f, 50f, 75f, 0f,
            100f, 0f, 100f, 100f, 0f, 100f,
        )
        assertFalse(LassoHitTest.polygonIntersectsBounds(u, Bounds(35f, 10f, 65f, 35f)))
        assertTrue(LassoHitTest.polygonIntersectsBounds(u, Bounds(35f, 10f, 65f, 60f)))
    }

    @Test
    fun `degenerate polygon never intersects`() {
        assertFalse(LassoHitTest.polygonIntersectsBounds(emptyList(), Bounds(0f, 0f, 10f, 10f)))
        assertFalse(
            LassoHitTest.polygonIntersectsBounds(polygon(0f, 0f, 10f, 10f), Bounds(0f, 0f, 10f, 10f))
        )
    }
}
