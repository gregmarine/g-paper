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

    /** The two bytes the band kernel writes — the display rebuild passes `ALPHA_8`'s own
     *  opaque/clear. */
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
     * **The bake is [DitherFlatten.srcOver] itself** (Phase 29) — the same function the
     * engine composites the live layer into the page image with — so this is no longer a
     * model of the bake standing in for it, it is the bake. A `Canvas` works in
     * premultiplied pixels and may round a channel a hair differently, and that is the one
     * remaining difference: a device question, as it always was.
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
                                val baked = DitherFlatten.black(
                                    DitherFlatten.srcOver(page, lead, alpha), 0, lead, ink, x, y,
                                )
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

    /**
     * The same mirror for the **pen** (Phase 29): a live ink layer flattened over the ink
     * image, against the ink image the same layer will have been composited into. The gel
     * pen previews through the panel now, and it is the same bargain the pencil struck —
     * what is painted under the nib is what the page will hold.
     */
    @Test
    fun `the live ink pixel and the baked ink pixel dither identically`() {
        val pens = intArrayOf(0xFF000000.toInt(), 0xFF303030.toInt(), 0xFF808080.toInt())
        val pageInks = intArrayOf(0, 0xFF000000.toInt(), 0x90404040.toInt())
        val graphites = intArrayOf(0, 0xFF707070.toInt(), 0x40101010)
        var compared = 0
        for (pen in pens) {
            for (page in pageInks) {
                for (graphite in graphites) {
                    for (alpha in intArrayOf(0, 1, 37, 128, 200, 254, 255)) {
                        for (y in intArrayOf(0, 13, 64, 1871)) {
                            for (x in intArrayOf(0, 29, 64, 1403)) {
                                val live = DitherFlatten.black(graphite, 0, 0, page, alpha, pen, x, y)
                                val baked = DitherFlatten.black(
                                    graphite, 0, 0, DitherFlatten.srcOver(page, pen, alpha), 0, 0, x, y,
                                )
                                assertEquals(
                                    "pen=${Integer.toHexString(pen)} ink=${Integer.toHexString(page)} " +
                                        "graphite=${Integer.toHexString(graphite)} a=$alpha at ($x,$y)",
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
        assertTrue(compared > 3000)
    }

    @Test
    fun `a live layer on one side never touches the other`() {
        // One contact is one tool and one layer: the pen's live ink may not lighten or
        // darken the graphite half, and the pencil's flecks may not reach the ink half.
        val pen = 0xFF000000.toInt()
        val lead = 0xFF808080.toInt()
        val graphite = 0xFF909090.toInt()
        // Live ink over bare ink paper: DARKEN against the graphite side.
        assertEquals(0, DitherFlatten.luma(graphite, 0, lead, 0, 255, pen))
        // …and with no live ink at all the graphite side stands alone.
        assertEquals(
            DitherFlatten.luma(graphite, 0, lead, 0),
            DitherFlatten.luma(graphite, 0, lead, 0, 0, pen),
        )
        // A live fleck on the graphite side, with ink present, still goes through DARKEN.
        val ink = 0xFFC0C0C0.toInt()
        assertEquals(
            DitherFlatten.luma(0xFF404040.toInt(), 0, lead, ink),
            DitherFlatten.luma(0, 255, 0xFF404040.toInt(), ink, 0, pen),
        )
    }

    @Test
    fun `src over is the composite the flatten assumes`() {
        val black = 0xFF000000.toInt()
        // The ends: nothing, and everything.
        assertEquals(0xFF808080.toInt(), DitherFlatten.srcOver(0xFF808080.toInt(), black, 0))
        assertEquals(black, DitherFlatten.srcOver(0, black, 255))
        // Onto bare paper (alpha 0) the result carries the source's own alpha.
        val half = DitherFlatten.srcOver(0, black, 128)
        assertEquals(128, half ushr 24)
        assertEquals(0, half and 0xFF)
        // And it never lightens an opaque black page.
        assertEquals(black, DitherFlatten.srcOver(black, 0xFFFFFFFF.toInt(), 0))
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

    /**
     * [DitherFlatten.srcOver] against an independent floating-point `SRC_OVER`, within
     * **±1/255 per channel** — the tolerance the integer form is allowed and no more.
     *
     * This is where the composite the direct bake performs would be pinned against a
     * `Canvas` if a `Canvas` could be driven here. It cannot: an Android module's unit
     * tests compile against the `android.jar` stub whose every method throws, and
     * Robolectric is a dependency this repo does not have (the same wall
     * `PencilRenderHarness` documents). So what is pinned is the arithmetic against the
     * definition of the operator, and the remaining question — whether Skia rounds a
     * channel the same way in premultiplied pixels — stays a device question, exactly as
     * it was when the mirror above modelled the bake rather than performing it.
     */
    @Test
    fun `a live pixel is the baked pixel, before either is dithered`() {
        // The mirror one level below the dither: not "the same dot" but the same *grey*.
        // It holds because there is only one flatten — the live half composites with
        // [DitherFlatten.srcOver] and then asks the display half — so this is the wiring
        // being pinned, not an arithmetic coincidence.
        val lead = 0xFF555555.toInt()
        val pen = 0xFF000000.toInt()
        var seed = 0x7ea1
        fun next(): Int {
            seed = seed * 1103515245 + 12345
            return (seed ushr 8) and 0xFF
        }
        repeat(3000) {
            val page = (next() shl 24) or (next() shl 16) or (next() shl 8) or next()
            val ink = (next() shl 24) or (next() shl 16) or (next() shl 8) or next()
            val a = next()
            assertEquals(
                DitherFlatten.luma(page, a, lead, ink),
                DitherFlatten.luma(DitherFlatten.srcOver(page, lead, a), ink),
            )
            assertEquals(
                DitherFlatten.luma(page, 0, lead, ink, a, pen),
                DitherFlatten.luma(page, DitherFlatten.srcOver(ink, pen, a)),
            )
        }
    }

    /**
     * [DitherFlatten.srcOver] against an independent floating-point `SRC_OVER`, within
     * **±1/255** — measured **premultiplied**, which is the only form anything here reads.
     *
     * The unpremultiplied channel of a nearly-transparent pixel is the composite's
     * numerator divided by an alpha that has itself been rounded to a byte, so it can sit
     * two or three parts in 255 off the real answer while contributing a fraction of one
     * part to anything that looks at it — and everything that looks at a page pixel looks
     * at it through its own alpha (the flatten over white, a `Canvas` blit, an export).
     * So the bound is stated where the error can be seen.
     */
    @Test
    fun `the integer composite is src over, within one part in 255`() {
        var seed = 0x51ce
        fun next(): Int {
            seed = seed * 1103515245 + 12345
            return (seed ushr 8) and 0xFF
        }
        var checked = 0
        for (round in 0 until 4000) {
            val dst = (next() shl 24) or (next() shl 16) or (next() shl 8) or next()
            val src = 0xFF000000.toInt() or (next() shl 16) or (next() shl 8) or next()
            val a = next()
            val got = DitherFlatten.srcOver(dst, src, a)
            val sa = a / 255.0
            val da = (dst ushr 24) / 255.0
            val outA = sa + da * (1 - sa)
            assertEquals("alpha (round $round)", outA * 255, (got ushr 24).toDouble(), 1.0)
            for (shift in intArrayOf(0, 8, 16)) {
                val s = (src ushr shift and 0xFF) / 255.0
                val d = (dst ushr shift and 0xFF) / 255.0
                // Premultiplied: colour × the alpha it will always be read through.
                val premul = s * sa + d * da * (1 - sa)
                val gotPremul = (got ushr shift and 0xFF) / 255.0 * ((got ushr 24) / 255.0)
                assertEquals(
                    "channel $shift (round $round, dst=${Integer.toHexString(dst)}, " +
                        "src=${Integer.toHexString(src)}, a=$a)",
                    premul * 255,
                    gotPremul * 255,
                    1.0,
                )
                checked++
            }
        }
        assertTrue(checked > 10000)
    }
}
