package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class MarkerTrimTest {

    private fun p(x: Float, y: Float) = StrokePoint(x, y)

    @Test
    fun `a straight finish is untouched`() {
        val pts = listOf(p(0f, 0f), p(50f, 0f), p(100f, 0f), p(150f, 0f))
        assertSame(pts, MarkerTrim.trim(pts, 17.5f))
        assertEquals(2, MarkerTrim.stableIndex(pts, 17.5f))
        assertEquals(1, MarkerTrim.stableStartIndex(pts, 17.5f))
    }

    @Test
    fun `the wobble inside half the width of the end is dropped, the end kept`() {
        val pts = listOf(p(0f, 0f), p(50f, 0f), p(100f, 0f), p(104f, 3f), p(101f, 6f), p(105f, 5f))
        val t = MarkerTrim.trim(pts, 17.5f)
        // (100, 0) is seven px from the end, inside the zone too: the finish runs from (50, 0).
        assertEquals(listOf(p(0f, 0f), p(50f, 0f), p(105f, 5f)), t)
        assertEquals(1, MarkerTrim.stableIndex(pts, 17.5f))
    }

    @Test
    fun `the start is trimmed the same way`() {
        val pts = listOf(p(0f, 0f), p(3f, 2f), p(-2f, 4f), p(50f, 0f), p(100f, 0f))
        assertEquals(listOf(p(0f, 0f), p(50f, 0f), p(100f, 0f)), MarkerTrim.trim(pts, 17.5f))
    }

    @Test
    fun `a stroke that never leaves either zone is its two ends`() {
        val pts = listOf(p(0f, 0f), p(3f, 2f), p(5f, 1f), p(8f, 4f))
        assertEquals(listOf(p(0f, 0f), p(8f, 4f)), MarkerTrim.trim(pts, 17.5f))
    }

    @Test
    fun `two points or fewer are left alone`() {
        val two = listOf(p(0f, 0f), p(1f, 1f))
        assertSame(two, MarkerTrim.trim(two, 17.5f))
        assertEquals(0, MarkerTrim.stableIndex(two, 17.5f))
        val one = listOf(p(0f, 0f))
        assertSame(one, MarkerTrim.trim(one, 17.5f))
    }

    @Test
    fun `a sample exactly half the width away is outside the zone`() {
        val pts = listOf(p(0f, 0f), p(50f, 0f), p(82.5f, 0f), p(100f, 0f))
        assertSame(pts, MarkerTrim.trim(pts, 17.5f))
    }

    // ── The dab (0.1.70) ──

    @Test
    fun `one sample is a dab on itself`() {
        assertEquals(p(4f, 5f), MarkerTrim.dab(listOf(p(4f, 5f)), 17.5f))
        assertNull(MarkerTrim.dab(emptyList(), 17.5f))
    }

    @Test
    fun `a small scrub that ends where it began is a dab at its middle, not a sliver`() {
        // Back and forth inside one width, lifting a fraction of a pixel from the start: the
        // trim alone would leave (0,0)-(0.5,0), a sliver.
        val pts = listOf(p(0f, 0f), p(8f, 2f), p(-4f, 6f), p(6f, -2f), p(0.5f, 0f))
        assertEquals(p(2f, 2f), MarkerTrim.dab(pts, 17.5f))
    }

    @Test
    fun `a scrub whose ends are apart by less than half the width is a dab`() {
        val pts = listOf(p(0f, 0f), p(3f, 4f), p(9f, 1f), p(10f, 0f))
        assertEquals(p(5f, 2f), MarkerTrim.dab(pts, 17.5f))
    }

    @Test
    fun `two samples under a pixel apart are a dab`() {
        assertEquals(p(0.25f, 0f), MarkerTrim.dab(listOf(p(0f, 0f), p(0.5f, 0f)), 17.5f))
    }

    @Test
    fun `a real stroke is never a dab`() {
        assertNull(MarkerTrim.dab(listOf(p(0f, 0f), p(50f, 0f), p(100f, 0f)), 17.5f))
        assertNull(MarkerTrim.dab(listOf(p(0f, 0f), p(5f, 0f)), 17.5f))
    }

    @Test
    fun `a fast stroke whose samples sit in the end zones stays a path`() {
        // Three samples, the middle one near the start: every interior sample is in a zone,
        // but the ends are far apart — a straight mark, not a scrub.
        val pts = listOf(p(0f, 0f), p(5f, 0f), p(200f, 0f))
        assertNull(MarkerTrim.dab(pts, 17.5f))
        assertEquals(listOf(p(0f, 0f), p(200f, 0f)), MarkerTrim.trim(pts, 17.5f))
    }

    @Test
    fun `the start is unsettled until a sample other than the newest leaves its zone`() {
        assertTrue(MarkerTrim.startUnsettled(listOf(p(0f, 0f), p(30f, 0f)), 17.5f))
        assertTrue(MarkerTrim.startUnsettled(listOf(p(0f, 0f), p(3f, 0f), p(40f, 0f)), 17.5f))
        assertFalse(MarkerTrim.startUnsettled(listOf(p(0f, 0f), p(30f, 0f), p(60f, 0f), p(90f, 0f)), 17.5f))
    }
}
