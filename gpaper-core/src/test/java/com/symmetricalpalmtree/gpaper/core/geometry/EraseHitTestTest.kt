package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EraseHitTestTest {

    private fun stroke(id: String, vararg xy: Float): Stroke {
        val points = ArrayList<StrokePoint>()
        var i = 0
        while (i < xy.size) {
            points.add(StrokePoint(xy[i], xy[i + 1]))
            i += 2
        }
        return Stroke(id = id, points = points)
    }

    private fun sweep(vararg xy: Float): List<StrokePoint> {
        val points = ArrayList<StrokePoint>()
        var i = 0
        while (i < xy.size) {
            points.add(StrokePoint(xy[i], xy[i + 1]))
            i += 2
        }
        return points
    }

    @Test
    fun `empty inputs hit nothing`() {
        assertEquals(emptyList<String>(), EraseHitTest.hitStrokeIds(emptyList(), sweep(0f, 0f), 10f))
        assertEquals(
            emptyList<String>(),
            EraseHitTest.hitStrokeIds(listOf(stroke("a", 0f, 0f, 10f, 0f)), emptyList(), 10f),
        )
    }

    @Test
    fun `direct pass over a stroke hits it`() {
        val s = stroke("a", 0f, 50f, 100f, 50f)
        val hits = EraseHitTest.hitStrokeIds(listOf(s), sweep(50f, 0f, 50f, 100f), 5f)
        assertEquals(listOf("a"), hits)
    }

    @Test
    fun `far-away stroke is rejected`() {
        val s = stroke("a", 1000f, 1000f, 1100f, 1000f)
        val hits = EraseHitTest.hitStrokeIds(listOf(s), sweep(0f, 0f, 10f, 10f), 15f)
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `fast sweep crossing a stroke between two samples still hits it`() {
        // Two eraser samples 200px apart on either side of a vertical stroke: every
        // individual sample is far outside the radius, but the sweep segment crosses.
        val s = stroke("a", 100f, -50f, 100f, 50f)
        val hits = EraseHitTest.hitStrokeIds(listOf(s), sweep(0f, 0f, 200f, 0f), 5f)
        assertEquals(listOf("a"), hits)
    }

    @Test
    fun `stroke segment passing between eraser samples is hit`() {
        // The mirror case: a long stroke segment crosses the eraser's short sweep.
        val s = stroke("a", -500f, 10f, 500f, 10f)
        val hits = EraseHitTest.hitStrokeIds(listOf(s), sweep(0f, 0f, 0f, 20f), 3f)
        assertEquals(listOf("a"), hits)
    }

    @Test
    fun `radius boundary is inclusive`() {
        val s = stroke("a", 0f, 10f, 100f, 10f)
        assertEquals(
            listOf("a"),
            EraseHitTest.hitStrokeIds(listOf(s), sweep(50f, 0f), 10f),
        )
        assertEquals(
            emptyList<String>(),
            EraseHitTest.hitStrokeIds(listOf(s), sweep(50f, 0f), 9.9f),
        )
    }

    @Test
    fun `single-point stroke is erasable`() {
        val dot = stroke("dot", 30f, 30f)
        val hits = EraseHitTest.hitStrokeIds(listOf(dot), sweep(25f, 25f, 35f, 35f), 8f)
        assertEquals(listOf("dot"), hits)
    }

    @Test
    fun `single-sample eraser tap works`() {
        val s = stroke("a", 0f, 0f, 100f, 100f)
        val hits = EraseHitTest.hitStrokeIds(listOf(s), sweep(50f, 50f), 5f)
        assertEquals(listOf("a"), hits)
    }

    @Test
    fun `each hit id reported once and misses stay out`() {
        val a = stroke("a", 0f, 50f, 100f, 50f)
        val b = stroke("b", 0f, 60f, 100f, 60f)
        val far = stroke("far", 0f, 500f, 100f, 500f)
        val hits = EraseHitTest.hitStrokeIds(listOf(a, b, far), sweep(50f, 40f, 50f, 70f), 5f)
        assertEquals(listOf("a", "b"), hits)
        assertTrue(hits.toSet().size == hits.size)
    }

    // ── hitContentIds (0.1.4 — whole-object content erase) ──────────────────

    private fun target(id: String, l: Float, t: Float, r: Float, b: Float) =
        id to Bounds(l, t, r, b)

    @Test
    fun `content - empty inputs hit nothing`() {
        assertEquals(
            emptyList<String>(),
            EraseHitTest.hitContentIds(emptyList(), sweep(0f, 0f), 10f),
        )
        assertEquals(
            emptyList<String>(),
            EraseHitTest.hitContentIds(listOf(target("a", 0f, 0f, 10f, 10f)), emptyList(), 10f),
        )
    }

    @Test
    fun `content - sweep through a target hits it whole`() {
        val hits = EraseHitTest.hitContentIds(
            listOf(target("a", 40f, 40f, 120f, 80f)),
            sweep(0f, 60f, 200f, 60f),
            5f,
        )
        assertEquals(listOf("a"), hits)
    }

    @Test
    fun `content - grazing within the radius hits`() {
        // Sweep runs 8px above the target's top edge; radius 10 reaches it.
        val hits = EraseHitTest.hitContentIds(
            listOf(target("a", 40f, 40f, 120f, 80f)),
            sweep(50f, 32f, 110f, 32f),
            10f,
        )
        assertEquals(listOf("a"), hits)
    }

    @Test
    fun `content - outside the radius misses`() {
        val hits = EraseHitTest.hitContentIds(
            listOf(target("a", 40f, 40f, 120f, 80f)),
            sweep(50f, 20f, 110f, 20f),
            10f,
        )
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `content - fast sweep jumping clean across a target still hits`() {
        // Both samples are far outside the target; the segment between them crosses it.
        val hits = EraseHitTest.hitContentIds(
            listOf(target("a", 90f, -10f, 110f, 10f)),
            sweep(0f, 0f, 200f, 0f),
            5f,
        )
        assertEquals(listOf("a"), hits)
    }

    @Test
    fun `content - single-sample tap inside a target hits`() {
        val hits = EraseHitTest.hitContentIds(
            listOf(target("a", 0f, 0f, 100f, 100f)),
            sweep(50f, 50f),
            5f,
        )
        assertEquals(listOf("a"), hits)
    }

    @Test
    fun `content - duplicate target ids report once, misses stay out`() {
        val hits = EraseHitTest.hitContentIds(
            listOf(
                target("a", 40f, 40f, 120f, 80f),
                target("a", 40f, 40f, 120f, 80f),
                target("far", 900f, 900f, 950f, 950f),
            ),
            sweep(0f, 60f, 200f, 60f),
            5f,
        )
        assertEquals(listOf("a"), hits)
    }

    // ── scribbleContentIds — the penetration rule ────────────────────────────

    @Test
    fun `scribble content - empty inputs hit nothing`() {
        assertEquals(
            emptyList<String>(),
            EraseHitTest.scribbleContentIds(emptyList(), sweep(0f, 0f, 100f, 0f), 14f),
        )
        assertEquals(
            emptyList<String>(),
            EraseHitTest.scribbleContentIds(listOf(target("a", 0f, 0f, 100f, 100f)), emptyList(), 14f),
        )
        // A single sample has no segment to measure, so nothing can penetrate.
        assertEquals(
            emptyList<String>(),
            EraseHitTest.scribbleContentIds(
                listOf(target("a", 0f, 0f, 100f, 100f)), sweep(50f, 50f), 14f,
            ),
        )
    }

    @Test
    fun `scribble content - a pass through the middle hits`() {
        // 100 px of travel inside a 100-wide box, well over the 14 px threshold.
        val hits = EraseHitTest.scribbleContentIds(
            listOf(target("a", 0f, 0f, 100f, 100f)),
            sweep(0f, 50f, 50f, 50f, 100f, 50f),
            14f,
        )
        assertEquals(listOf("a"), hits)
    }

    @Test
    fun `scribble content - a corner graze does not hit`() {
        // Clips the bottom-right corner: one endpoint inside, ~4 px of segment.
        val hits = EraseHitTest.scribbleContentIds(
            listOf(target("a", 0f, 0f, 100f, 100f)),
            sweep(96f, 96f, 98f, 98f, 104f, 104f),
            14f,
        )
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `scribble content - travel beside an object leaves it alone`() {
        // The whole gesture is outside the box; the AABBs still overlap on one axis.
        val hits = EraseHitTest.scribbleContentIds(
            listOf(target("a", 0f, 0f, 100f, 100f)),
            sweep(120f, 0f, 120f, 40f, 120f, 100f),
            14f,
        )
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `scribble content - the eraser touch rule would hit where penetration does not`() {
        // The distinction this function exists for: a stroke of ink scribbled out just
        // past a heading's edge. hitContentIds (inflated-rect touch) takes it; the
        // penetration rule does not.
        val targets = listOf(target("heading", 0f, 0f, 100f, 40f))
        val beside = sweep(0f, 46f, 100f, 46f)
        assertEquals(listOf("heading"), EraseHitTest.hitContentIds(targets, beside, 8f))
        assertEquals(emptyList<String>(), EraseHitTest.scribbleContentIds(targets, beside, 14f))
    }

    @Test
    fun `scribble content - penetration accumulates across separate passes`() {
        // Three shallow dips, none of them 14 px on its own, together well past it —
        // which is exactly what scribbling back and forth over a heading looks like.
        val hits = EraseHitTest.scribbleContentIds(
            listOf(target("a", 0f, 0f, 100f, 10f)),
            sweep(10f, 14f, 10f, 6f, 20f, 14f, 30f, 6f, 40f, 14f, 50f, 6f, 60f, 14f),
            14f,
        )
        assertEquals(listOf("a"), hits)
    }

    @Test
    fun `scribble content - threshold is inclusive`() {
        // Exactly 14 px inside a tall box: both endpoints contained, one segment.
        val hits = EraseHitTest.scribbleContentIds(
            listOf(target("a", 0f, 0f, 100f, 100f)),
            sweep(50f, 40f, 50f, 54f),
            14f,
        )
        assertEquals(listOf("a"), hits)
    }

    @Test
    fun `scribble content - duplicate ids report once, order follows targets`() {
        val hits = EraseHitTest.scribbleContentIds(
            listOf(
                target("b", 200f, 0f, 300f, 100f),
                target("a", 0f, 0f, 100f, 100f),
                target("a", 0f, 0f, 100f, 100f),
                target("far", 900f, 900f, 950f, 950f),
            ),
            sweep(0f, 50f, 150f, 50f, 300f, 50f),
            14f,
        )
        assertEquals(listOf("b", "a"), hits)
    }
}
