package com.symmetricalpalmtree.gpaper.ratta

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A post cut around the host's chrome zones (Phase 42): the pieces cover exactly the rect
 * minus the holes, and never one another — checked pixel by pixel against a mask, which
 * is the only proof that matters for something that paints a panel.
 */
class PanelClipTest {

    /** Pixel-count the pieces against the rect-minus-holes mask on a small grid. */
    private fun check(rect: IntArray, holes: IntArray) {
        val pieces = PanelClip.subtract(rect, holes)
        assertEquals(0, pieces.size % 4)
        val w = 40
        val h = 40
        val expected = Array(h) { y -> BooleanArray(w) { x -> inside(rect, 0, x, y) && !anyHole(holes, x, y) } }
        val count = Array(h) { IntArray(w) }
        var i = 0
        while (i + 3 < pieces.size) {
            assertTrue(pieces[i + 2] > pieces[i] && pieces[i + 3] > pieces[i + 1])
            for (y in pieces[i + 1] until pieces[i + 3]) for (x in pieces[i] until pieces[i + 2]) count[y][x]++
            i += 4
        }
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals("pixel ($x,$y)", if (expected[y][x]) 1 else 0, count[y][x])
        }
    }

    private fun inside(q: IntArray, at: Int, x: Int, y: Int): Boolean =
        x >= q[at] && x < q[at + 2] && y >= q[at + 1] && y < q[at + 3]

    private fun anyHole(holes: IntArray, x: Int, y: Int): Boolean {
        var i = 0
        while (i + 3 < holes.size) {
            if (holes[i + 2] > holes[i] && holes[i + 3] > holes[i + 1] && inside(holes, i, x, y)) return true
            i += 4
        }
        return false
    }

    @Test
    fun `no holes is the rect itself`() {
        assertArrayEquals(intArrayOf(2, 3, 20, 30), PanelClip.subtract(intArrayOf(2, 3, 20, 30), IntArray(0)))
    }

    @Test
    fun `an empty rect is nothing, and a hole covering the rect leaves nothing`() {
        assertEquals(0, PanelClip.subtract(intArrayOf(5, 5, 5, 9), intArrayOf(0, 0, 9, 9)).size)
        assertEquals(0, PanelClip.subtract(intArrayOf(5, 5, 8, 9), intArrayOf(0, 0, 40, 40)).size)
    }

    @Test
    fun `a hole that misses leaves the rect whole`() {
        check(intArrayOf(2, 3, 20, 30), intArrayOf(25, 0, 39, 39))
        assertArrayEquals(intArrayOf(2, 3, 20, 30), PanelClip.subtract(intArrayOf(2, 3, 20, 30), intArrayOf(25, 0, 39, 39)))
    }

    @Test
    fun `a hole inside, across an edge, across a corner, and a band through`() {
        check(intArrayOf(2, 3, 30, 30), intArrayOf(10, 10, 15, 16)) // inside
        check(intArrayOf(2, 3, 30, 30), intArrayOf(0, 10, 15, 16)) // across the left edge
        check(intArrayOf(2, 3, 30, 30), intArrayOf(25, 25, 39, 39)) // across the corner
        check(intArrayOf(2, 3, 30, 30), intArrayOf(0, 10, 39, 16)) // a band right through
    }

    @Test
    fun `several holes, overlapping, and a degenerate one ignored`() {
        check(intArrayOf(0, 0, 36, 36), intArrayOf(4, 4, 12, 12, 8, 8, 20, 14, 30, 30, 30, 34, 0, 20, 36, 22))
    }
}
