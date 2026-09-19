package com.symmetricalpalmtree.gpaper.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * What the dither has to be true of for a panel to be handed it: the ends are exact, the
 * middle is the grey it was asked for, and the same pixel always answers the same.
 */
class DitherTest {

    @Test
    fun `pure black is black at every position`() {
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                assertTrue("grey 0 came out white at ($x,$y)", Dither.black(0, x, y))
            }
        }
    }

    @Test
    fun `pure white is white at every position`() {
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                assertTrue("grey 255 came out black at ($x,$y)", !Dither.black(255, x, y))
            }
        }
    }

    @Test
    fun `a flat grey lays that fraction of white over a tile`() {
        for (g in 0..255) {
            var white = 0
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    if (!Dither.black(g, x, y)) white++
                }
            }
            val fraction = white / 4096.0
            val expected = g / 255.0
            assertTrue(
                "grey $g laid ${"%.4f".format(fraction)} white, expected ~${"%.4f".format(expected)}",
                abs(fraction - expected) <= 0.01,
            )
        }
    }

    @Test
    fun `a grey gets darker as it falls, monotonically`() {
        var previous = 0
        for (g in 0..255) {
            var black = 0
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    if (Dither.black(g, x, y)) black++
                }
            }
            if (g > 0) assertTrue("grey $g laid more black than grey ${g - 1}", black <= previous)
            previous = black
        }
    }

    @Test
    fun `the same pixel always answers the same`() {
        for (g in intArrayOf(0, 17, 64, 128, 200, 254, 255)) {
            for (y in intArrayOf(0, 1, 63, 64, 1871)) {
                for (x in intArrayOf(0, 1, 63, 64, 1403)) {
                    val first = Dither.black(g, x, y)
                    repeat(3) { assertEquals(first, Dither.black(g, x, y)) }
                    // And the same as the pixel one tile over, which is what lets a
                    // preview and a later repaint of the same page agree.
                    assertEquals(first, Dither.black(g, x + 64, y + 64))
                }
            }
        }
    }

    @Test
    fun `an out of range grey is clamped rather than thrown`() {
        assertTrue(Dither.black(-40, 3, 7))
        assertTrue(!Dither.black(4000, 3, 7))
        assertEquals(0, Dither.shade(-40))
        assertEquals(255, Dither.shade(4000))
    }

    @Test
    fun `the gamma is linear until a walk says otherwise`() {
        // Not a property of the dither — a record of the value shipped, so that changing
        // it is a deliberate act with a test to update rather than a quiet drift.
        assertEquals(1.0f, Dither.DITHER_GAMMA, 0f)
        for (g in 0..255) assertEquals(g, Dither.shade(g))
    }
}
