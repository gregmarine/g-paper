package com.symmetricalpalmtree.gpaper.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The threshold matrix itself: that it is the thing it claims to be (every value once per
 * sixteen cells, no gaps) and that it tiles.
 *
 * A dither matrix with a missing or repeated value is not visibly broken — it is *slightly*
 * wrong everywhere, which is the kind of fault a hand would report as "the pale shades look
 * off" and nobody would find. So it is counted here rather than trusted.
 */
class BlueNoise64Test {

    @Test
    fun `the matrix is 64 by 64`() {
        assertEquals(64, BlueNoise64.SIZE)
    }

    @Test
    fun `every value 0 to 255 appears exactly sixteen times`() {
        val counts = IntArray(256)
        for (y in 0 until BlueNoise64.SIZE) {
            for (x in 0 until BlueNoise64.SIZE) {
                val t = BlueNoise64.threshold(x, y)
                assertEquals("threshold at ($x,$y) out of range: $t", t, t.coerceIn(0, 255))
                counts[t]++
            }
        }
        // 4096 cells over 256 values.
        for (v in 0..255) {
            assertEquals("value $v appears ${counts[v]} times, not 16", 16, counts[v])
        }
        assertEquals(4096, counts.sum())
    }

    @Test
    fun `threshold tiles the page in both directions`() {
        for (y in 0 until BlueNoise64.SIZE) {
            for (x in 0 until BlueNoise64.SIZE) {
                val t = BlueNoise64.threshold(x, y)
                assertEquals(t, BlueNoise64.threshold(x + 64, y))
                assertEquals(t, BlueNoise64.threshold(x, y + 64))
                assertEquals(t, BlueNoise64.threshold(x + 640, y + 1280))
            }
        }
    }

    @Test
    fun `a row slice is the row`() {
        // The band renderer fetches a row once and indexes it per pixel; if that ever
        // stopped being threshold(x, y) the page would dither differently from the live
        // preview painted on the panel, which is the one thing the direct path cannot
        // survive.
        val row = ByteArray(BlueNoise64.SIZE)
        for (y in -130 until 200) {
            BlueNoise64.row(y, row)
            for (x in 0 until 512) {
                assertEquals(
                    "row $y, x $x",
                    BlueNoise64.threshold(x, y),
                    row[x and (BlueNoise64.SIZE - 1)].toInt() and 0xFF,
                )
            }
        }
    }

    @Test
    fun `threshold wraps around on negative coordinates`() {
        // A page pixel is never negative, but a rect padded outward by a fleck radius at
        // the page edge is — and an index that threw or read the wrong cell there would
        // show as a seam along the top and left of every page.
        for (y in 0 until BlueNoise64.SIZE) {
            for (x in 0 until BlueNoise64.SIZE) {
                val t = BlueNoise64.threshold(x, y)
                assertEquals(t, BlueNoise64.threshold(x - 64, y - 64))
                assertEquals(t, BlueNoise64.threshold(x - 6400, y - 128))
            }
        }
    }
}
