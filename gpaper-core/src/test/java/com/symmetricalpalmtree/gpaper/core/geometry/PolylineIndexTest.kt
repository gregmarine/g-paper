package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Phase 44: the grid answers exactly what the pairwise test answers, on every shape of input,
 * and stops multiplying a long scribble by a page of writing.
 */
class PolylineIndexTest {

    private fun pts(vararg xy: Float): List<StrokePoint> =
        (xy.indices step 2).map { StrokePoint(xy[it], xy[it + 1]) }

    private fun squiggle(rnd: Random, n: Int, w: Float, h: Float, step: Float): List<StrokePoint> {
        var x = rnd.nextFloat() * w
        var y = rnd.nextFloat() * h
        return List(n) {
            x = (x + (rnd.nextFloat() - 0.5f) * step).coerceIn(0f, w)
            y = (y + (rnd.nextFloat() - 0.5f) * step).coerceIn(0f, h)
            StrokePoint(x, y)
        }
    }

    @Test
    fun `empty and degenerate inputs match the pairwise rules`() {
        assertFalse(PolylineIndex(emptyList(), 5f).withinDistanceOf(pts(0f, 0f)))
        assertFalse(PolylineIndex(pts(0f, 0f), 5f).withinDistanceOf(emptyList()))
        // Two single points: a point-to-point distance.
        assertTrue(PolylineIndex(pts(0f, 0f), 5f).withinDistanceOf(pts(3f, 4f)))
        assertFalse(PolylineIndex(pts(0f, 0f), 4.9f).withinDistanceOf(pts(3f, 4f)))
        // A single point against a segment, both ways round.
        assertTrue(PolylineIndex(pts(5f, 4f), 4f).withinDistanceOf(pts(0f, 0f, 10f, 0f)))
        assertTrue(PolylineIndex(pts(0f, 0f, 10f, 0f), 4f).withinDistanceOf(pts(5f, 4f)))
        assertFalse(PolylineIndex(pts(0f, 0f, 10f, 0f), 3.9f).withinDistanceOf(pts(5f, 4f)))
    }

    @Test
    fun `a crossing far from every sample still hits, and a near miss still misses`() {
        // A long sweep segment crossed by a long stroke segment, no sample near the other.
        val sweep = pts(0f, 0f, 1000f, 1000f)
        assertTrue(PolylineIndex(sweep, 1f).withinDistanceOf(pts(1000f, 0f, 0f, 1000f)))
        // Parallel, 10 px apart.
        assertFalse(PolylineIndex(sweep, 9f).withinDistanceOf(pts(0f, 14.15f, 1000f, 1014.15f)))
        assertTrue(PolylineIndex(sweep, 10.1f).withinDistanceOf(pts(0f, 14.14f, 1000f, 1014.14f)))
    }

    @Test
    fun `the grid agrees with the pairwise test over random polylines`() {
        val rnd = Random(4407)
        var hits = 0
        repeat(600) {
            val distance = 1f + rnd.nextFloat() * 20f
            val sweep = squiggle(rnd, 1 + rnd.nextInt(60), 400f, 400f, 40f)
            val stroke = squiggle(rnd, 1 + rnd.nextInt(60), 400f, 400f, 40f)
            val expected = Geometry.polylineWithinDistance(stroke, sweep, distance)
            val index = PolylineIndex(sweep, distance)
            assertEquals("case $it", expected, index.withinDistanceOf(stroke))
            // A second query on the same index answers the same (the stamps were reset).
            assertEquals("case $it, repeated", expected, index.withinDistanceOf(stroke))
            if (expected) hits++
        }
        // Both answers exercised.
        assertTrue(hits in 50..550)
    }

    @Test
    fun `a page-wide scribble over a page of writing finishes in bounded time`() {
        val rnd = Random(9)
        val strokes = List(300) { i ->
            Stroke(id = "s$i", points = squiggle(rnd, 300, 1404f, 1872f, 12f))
        }
        val scribble = squiggle(rnd, 4000, 1404f, 1872f, 60f)
        val started = System.nanoTime()
        val hits = EraseHitTest.hitStrokeIds(strokes, scribble, 24f)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(hits.isNotEmpty())
        // The pairwise walk is ~360 M segment pairs here; the grid is a few million lookups.
        assertTrue("took $ms ms", ms < 2000)
        // Same answer as the pairwise test, stroke for stroke (spot-checked: it is the slow one).
        val expected = strokes.filter { Geometry.polylineWithinDistance(it.points, scribble.take(400), 24f) }.map { it.id }
        val viaIndex = EraseHitTest.hitStrokeIds(strokes, scribble.take(400), 24f)
        assertEquals(expected, viaIndex)
    }
}
