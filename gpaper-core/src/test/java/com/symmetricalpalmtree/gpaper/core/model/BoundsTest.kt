package com.symmetricalpalmtree.gpaper.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundsTest {

    @Test
    fun `of empty list is ZERO`() {
        assertEquals(Bounds.ZERO, Bounds.of(emptyList()))
    }

    @Test
    fun `of single point is degenerate rect at that point`() {
        val b = Bounds.of(listOf(StrokePoint(5f, 7f)))
        assertEquals(Bounds(5f, 7f, 5f, 7f), b)
        assertTrue(b.isEmpty)
        // Inflating by a hit radius makes it testable again.
        assertFalse(b.inflated(2f).isEmpty)
        assertTrue(b.inflated(2f).contains(6f, 8f))
    }

    @Test
    fun `of computes tight box over unordered points`() {
        val b = Bounds.of(
            listOf(
                StrokePoint(3f, -1f),
                StrokePoint(-2f, 4f),
                StrokePoint(0f, 0f),
            )
        )
        assertEquals(Bounds(-2f, -1f, 3f, 4f), b)
        assertEquals(5f, b.width, 0f)
        assertEquals(5f, b.height, 0f)
    }

    @Test
    fun `contains is edge-inclusive`() {
        val b = Bounds(0f, 0f, 10f, 10f)
        assertTrue(b.contains(0f, 0f))
        assertTrue(b.contains(10f, 10f))
        assertTrue(b.contains(5f, 5f))
        assertFalse(b.contains(10.001f, 5f))
        assertFalse(b.contains(5f, -0.001f))
    }

    @Test
    fun `intersects is touch-inclusive and symmetric`() {
        val a = Bounds(0f, 0f, 10f, 10f)
        val touching = Bounds(10f, 0f, 20f, 10f)
        val overlapping = Bounds(5f, 5f, 15f, 15f)
        val apart = Bounds(11f, 0f, 20f, 10f)
        assertTrue(a.intersects(touching))
        assertTrue(touching.intersects(a))
        assertTrue(a.intersects(overlapping))
        assertFalse(a.intersects(apart))
        assertFalse(apart.intersects(a))
    }

    @Test
    fun `union covers both rects`() {
        val u = Bounds(0f, 0f, 5f, 5f).union(Bounds(3f, -2f, 9f, 4f))
        assertEquals(Bounds(0f, -2f, 9f, 5f), u)
    }

    @Test
    fun `inflated grows every side and negative shrinks`() {
        assertEquals(Bounds(-1f, -1f, 11f, 11f), Bounds(0f, 0f, 10f, 10f).inflated(1f))
        assertEquals(Bounds(2f, 2f, 8f, 8f), Bounds(0f, 0f, 10f, 10f).inflated(-2f))
    }

    @Test
    fun `offset shifts without resizing`() {
        val b = Bounds(1f, 2f, 4f, 6f).offset(10f, -2f)
        assertEquals(Bounds(11f, 0f, 14f, 4f), b)
        assertEquals(3f, b.width, 0f)
        assertEquals(4f, b.height, 0f)
    }
}
