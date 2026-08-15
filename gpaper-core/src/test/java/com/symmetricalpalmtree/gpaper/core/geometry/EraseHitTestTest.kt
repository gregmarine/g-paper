package com.symmetricalpalmtree.gpaper.core.geometry

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
}
