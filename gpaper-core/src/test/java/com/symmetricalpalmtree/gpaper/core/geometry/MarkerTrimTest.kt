package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
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
}
