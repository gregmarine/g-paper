package com.symmetricalpalmtree.gpaper.ratta

import com.symmetricalpalmtree.gpaper.core.geometry.Dither
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flatten, and the one property the whole direct path rests on: **what the panel is
 * painted under the nib is what the window shows after the bake.**
 *
 * The live half flattens the page images against a live alpha layer and sends the result to
 * the panel; the display half flattens the page images alone, after the mark has been baked
 * into the graphite image, and draws the result into the window. If those two ever disagreed
 * about a pixel, every mark would change the moment the pen left the paper — quietly, by one
 * dot here and there, on a device and never in a test. So the agreement is asserted here, on
 * the arithmetic, rather than hoped for.
 */
class DitherFlattenTest {

    private val transparent = 0

    /** The two bytes the band kernel writes — the display rebuild uses `ALPHA_8`'s own
     *  opaque/clear, the idle clean the panel's black/white levels. */
    private val ON: Byte = -1
    private val OFF: Byte = 0
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `bare paper is white and never black`() {
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                assertEquals(255, DitherFlatten.luma(transparent, 0, black, transparent))
                assertTrue(!DitherFlatten.black(transparent, 0, black, transparent, x, y))
            }
        }
    }

    @Test
    fun `solid black graphite is black everywhere`() {
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                assertEquals(0, DitherFlatten.luma(black, 0, black, transparent))
                assertTrue(DitherFlatten.black(black, 0, black, transparent, x, y))
            }
        }
    }

    @Test
    fun `a grey lead lays that fraction of dots`() {
        // #999999 — the shade the second walk's density curve was arguing about. It should
        // read as itself: a flat 0x99 of white over a tile, not a thinner mark.
        val lead = 0xFF999999.toInt()
        var white = 0
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                if (!DitherFlatten.black(lead, 0, black, transparent, x, y)) white++
            }
        }
        val fraction = white / 4096.0
        assertTrue("laid $fraction white for #999999", kotlin.math.abs(fraction - 0x99 / 255.0) <= 0.01)
    }

    @Test
    fun `ink darkens and never lightens`() {
        // DARKEN, per channel: the pair has no top and no bottom.
        assertEquals(0, DitherFlatten.luma(transparent, 0, black, black))
        assertEquals(0, DitherFlatten.luma(black, 0, black, white))
        assertEquals(255, DitherFlatten.luma(white, 0, black, transparent))
        val grey = 0xFF808080.toInt()
        assertEquals(
            DitherFlatten.luma(grey, 0, black, transparent),
            DitherFlatten.luma(transparent, 0, black, grey),
        )
        // Order-independent: whichever is darker wins, either way round.
        val dark = 0xFF303030.toInt()
        assertEquals(DitherFlatten.luma(dark, 0, black, grey), DitherFlatten.luma(grey, 0, black, dark))
    }

    @Test
    fun `the live layer paints the lead over the page`() {
        val lead = 0xFF808080.toInt()
        // Full alpha: the pixel is the lead.
        assertEquals(128, DitherFlatten.luma(transparent, 255, lead, transparent))
        // No alpha: the page, untouched.
        assertEquals(255, DitherFlatten.luma(transparent, 0, lead, transparent))
        // Half alpha over white paper: halfway there.
        val half = DitherFlatten.luma(transparent, 128, lead, transparent)
        assertTrue("half-alpha grey lead came out $half", half in 188..196)
    }

    @Test
    fun `black is the flatten through the dither`() {
        for (g in intArrayOf(0, 40, 128, 200, 255)) {
            val pixel = 0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
            for (y in intArrayOf(0, 5, 63, 200)) {
                for (x in intArrayOf(0, 7, 63, 511)) {
                    val luma = DitherFlatten.luma(pixel, 0, black, transparent)
                    assertEquals(
                        Dither.black(luma, x, y),
                        DitherFlatten.black(pixel, 0, black, transparent, x, y),
                    )
                }
            }
        }
    }

    /**
     * **The mirror.** For a synthetic rect of page pixels and a synthetic live mark over
     * it, the live half's answer and the display half's answer — the display half reading
     * the graphite image the bake will have left behind — are the same black-and-white
     * picture, pixel for pixel.
     *
     * The bake is modelled here as the `SRC_OVER` the renderer's flecks composite with.
     * A `Canvas` works in premultiplied pixels and may round a channel a hair differently,
     * so this pins the two *formulas* to each other, which is the half that can drift; the
     * rasteriser's rounding is a device question and always was.
     */
    @Test
    fun `the live pixel and the baked pixel dither identically`() {
        val leads = intArrayOf(0xFF000000.toInt(), 0xFF555555.toInt(), 0xFF999999.toInt(), 0xFFDDDDDD.toInt())
        val pageGreys = intArrayOf(0, 0xFF404040.toInt(), 0xFFB0B0B0.toInt(), 0x80202020.toInt())
        val inks = intArrayOf(0, 0xFF000000.toInt(), 0x60000000)
        var compared = 0
        for (lead in leads) {
            for (page in pageGreys) {
                for (ink in inks) {
                    for (alpha in intArrayOf(0, 1, 37, 128, 200, 254, 255)) {
                        for (y in intArrayOf(0, 13, 64, 1871)) {
                            for (x in intArrayOf(0, 29, 64, 1403)) {
                                val live = DitherFlatten.black(page, alpha, lead, ink, x, y)
                                val baked = DitherFlatten.black(srcOver(page, lead, alpha), 0, lead, ink, x, y)
                                assertEquals(
                                    "lead=${Integer.toHexString(lead)} page=${Integer.toHexString(page)} " +
                                        "ink=${Integer.toHexString(ink)} a=$alpha at ($x,$y)",
                                    live,
                                    baked,
                                )
                                compared++
                            }
                        }
                    }
                }
            }
        }
        assertTrue(compared > 5000)
    }

    // ── The band kernel: the same picture, a page at a time ─────────────────

    @Test
    fun `the band kernel is the per-pixel answer, pixel for pixel`() {
        // The whole of the fast path's licence to exist. [DitherFlatten.band] hoists the
        // blue-noise row, tables the gamma and writes the flatten out longhand to make a
        // page turn under 100 ms instead of 500; none of that is allowed to change a
        // single pixel, and a pixel changed here would show as a page that shifts tone the
        // moment it is turned to.
        val w = 71 // not a multiple of the 64-wide matrix, on purpose
        val h = 37
        val graphite = IntArray(w * h)
        val ink = IntArray(w * h)
        var seed = 0x5ee7
        fun next(): Int {
            seed = seed * 1103515245 + 12345
            return seed ushr 8
        }
        for (i in 0 until w * h) {
            // A real page: mostly bare paper, some opaque ink, plenty of part-covered
            // flecks at every shade, and a few pixels with colour under zero alpha.
            graphite[i] = when (next() % 5) {
                0 -> 0
                1 -> 0xFF000000.toInt() or (next() and 0xFFFFFF)
                2 -> ((next() and 0xFF) shl 24) or (next() and 0xFFFFFF)
                3 -> (next() and 0xFFFFFF)
                else -> 0
            }
            ink[i] = when (next() % 6) {
                0 -> 0xFF000000.toInt()
                1 -> ((next() and 0xFF) shl 24) or (next() and 0xFFFFFF)
                else -> 0
            }
        }
        // An offset origin, an output stride wider than the band, and a non-zero offset:
        // the whole-page rebuild writes the bitmap's own padded rows, so all three are
        // shapes the caller really uses.
        val x0 = 813
        val y0 = 251
        val stride = w + 5
        val offset = stride * 2 + 3
        val out = ByteArray(offset + stride * h)
        DitherFlatten.band(
            graphite, true, ink, true, x0, y0, w, h, out, offset, stride, ON, OFF,
        )
        for (y in 0 until h) {
            for (x in 0 until w) {
                val expected = DitherFlatten.black(
                    graphite[y * w + x], 0, 0, ink[y * w + x], x0 + x, y0 + y,
                )
                assertEquals(
                    "pixel ($x, $y)",
                    if (expected) ON else OFF,
                    out[offset + y * stride + x],
                )
            }
        }
    }

    @Test
    fun `an absent layer is not read, and bare paper is white`() {
        // A pencil page has no ink image at all, and most of a page has nothing on either
        // layer. Both must be answered without touching the array that is not there — the
        // arrays below are deliberately full of black, so a kernel that read one would
        // paint the page solid.
        val w = 64
        val h = 8
        val poison = IntArray(w * h) { 0xFF000000.toInt() }
        val blank = IntArray(w * h)
        val out = ByteArray(w * h)
        DitherFlatten.band(poison, false, poison, false, 0, 0, w, h, out, 0, w, ON, OFF)
        assertTrue(out.all { it == OFF })
        DitherFlatten.band(blank, true, poison, false, 0, 0, w, h, out, 0, w, ON, OFF)
        assertTrue(out.all { it == OFF })
        // And a band whose pixels are all transparent is the same answer as no band.
        DitherFlatten.band(blank, true, blank, true, 0, 0, w, h, out, 0, w, ON, OFF)
        assertTrue(out.all { it == OFF })
    }

    @Test
    fun `the band kernel agrees with itself whatever the banding`() {
        // A whole page is rebuilt in horizontal bands and a rect in one; the seam between
        // two bands is where a row-keyed threshold would go wrong, and it would look like
        // a faint stripe across the page rather than like a bug.
        val w = 40
        val h = 32
        val graphite = IntArray(w * h) { (0xFF000000.toInt() or (it * 7 and 0xFFFFFF)) }
        val ink = IntArray(w * h)
        val whole = ByteArray(w * h)
        DitherFlatten.band(graphite, true, ink, false, 5, 9, w, h, whole, 0, w, ON, OFF)
        val banded = ByteArray(w * h)
        var top = 0
        while (top < h) {
            val bottom = minOf(top + 7, h)
            val rows = bottom - top
            val g = IntArray(w * rows)
            System.arraycopy(graphite, top * w, g, 0, w * rows)
            DitherFlatten.band(
                g, true, ink, false, 5, 9 + top, w, rows, banded, top * w, w, ON, OFF,
            )
            top = bottom
        }
        assertTrue(whole.contentEquals(banded))
    }

    /** [src] at [srcAlpha] over [dst], unpremultiplied — what the bake leaves in the page
     *  image where a fleck landed. */
    private fun srcOver(dst: Int, src: Int, srcAlpha: Int): Int {
        if (srcAlpha == 0) return dst
        if (srcAlpha == 255) return 0xFF000000.toInt() or (src and 0xFFFFFF)
        val da = dst ushr 24
        val outA = srcAlpha + da * (255 - srcAlpha) / 255
        if (outA == 0) return 0
        fun channel(shift: Int): Int {
            val s = src ushr shift and 0xFF
            val d = dst ushr shift and 0xFF
            // Premultiplied add, back out by the result's alpha — the composite a Canvas
            // performs, in integers.
            val num = s * srcAlpha * 255 + d * da * (255 - srcAlpha)
            return (num / (255 * outA)).coerceIn(0, 255)
        }
        return (outA shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
