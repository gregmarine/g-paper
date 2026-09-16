package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rect handed to a raster host before a mark lands must cover every pixel the mark
 * can touch — an undo taken from it that misses a fleck leaves a mark that will not go
 * away — and must stay on the page, because it is used to copy pixels out of a bitmap.
 */
class RasterDirtyTest {

    private val page = 100 to 200

    @Test
    fun `the rect covers the bounds pushed out by the width and the margin`() {
        val b = Bounds(10f, 20f, 30f, 40f)
        val r = RasterDirty.of(b, 4f, page.first, page.second)!!
        // 4 + 2 of slack on every side.
        assertEquals(Bounds(4f, 14f, 36f, 46f), r)
    }

    @Test
    fun `the rect snaps outward to whole pixels`() {
        val b = Bounds(10.4f, 20.6f, 30.2f, 40.9f)
        val r = RasterDirty.of(b, 1.2f, page.first, page.second)!!
        // 10.4 - 3.2 = 7.2 → 7; 20.6 - 3.2 = 17.4 → 17; 30.2 + 3.2 = 33.4 → 34; 40.9 + 3.2 = 44.1 → 45
        assertEquals(Bounds(7f, 17f, 34f, 45f), r)
    }

    @Test
    fun `a hairline still gets the margin`() {
        val b = Bounds(50f, 50f, 50f, 50f)
        val r = RasterDirty.of(b, 1.2f, page.first, page.second)!!
        assertEquals(Bounds(46f, 46f, 54f, 54f), r)
    }

    @Test
    fun `the rect is clipped to the page`() {
        val b = Bounds(-5f, 190f, 3f, 260f)
        val r = RasterDirty.of(b, 3f, page.first, page.second)!!
        assertEquals(Bounds(0f, 185f, 8f, 200f), r)
    }

    @Test
    fun `a mark wholly off the page announces nothing`() {
        assertNull(RasterDirty.of(Bounds(150f, 10f, 160f, 20f), 3f, page.first, page.second))
        assertNull(RasterDirty.of(Bounds(10f, -50f, 20f, -40f), 3f, page.first, page.second))
    }

    @Test
    fun `a mark whose slack just reaches the page edge counts`() {
        // Right edge at 100 + 5 slack; the mark itself is off-page but its ink is not.
        val r = RasterDirty.of(Bounds(103f, 10f, 110f, 20f), 3f, page.first, page.second)!!
        assertEquals(Bounds(98f, 5f, 100f, 25f), r)
    }

    @Test
    fun `no page means no rect`() {
        assertNull(RasterDirty.of(Bounds(10f, 10f, 20f, 20f), 3f, 0, 200))
        assertNull(RasterDirty.wholePage(100, 0))
    }

    @Test
    fun `the whole page is the page`() {
        assertEquals(Bounds(0f, 0f, 100f, 200f), RasterDirty.wholePage(100, 200))
    }

    @Test
    fun `a stroke's own bounds feed it`() {
        val pts = listOf(StrokePoint(10f, 10f), StrokePoint(20f, 30f))
        val r = RasterDirty.of(Bounds.of(pts), 2f, page.first, page.second)!!
        assertEquals(Bounds(6f, 6f, 24f, 34f), r)
    }

    // ── along: a mark announced as runs, not as its bounding box (0.1.33) ────

    /** A Nomad page, the size the diagonal case was costed on. */
    private val bigPage = 1404 to 1685

    private val hairline = 1.2f
    private val hairlinePad = hairline + RasterDirty.MARGIN_PX

    private fun line(
        x0: Float, y0: Float, x1: Float, y1: Float, stepPx: Float,
    ): List<StrokePoint> {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy)
        val n = kotlin.math.max(1, (len / stepPx).toInt())
        return (0..n).map { i ->
            val t = i.toFloat() / n
            StrokePoint(x0 + dx * t, y0 + dy * t)
        }
    }

    /** True when every page pixel within [pad] of ([x], [y]) lies inside one rect. */
    private fun covers(
        rects: List<Bounds>, x: Float, y: Float, pad: Float, pw: Int, ph: Int,
    ): Boolean {
        val l = (x - pad).coerceAtLeast(0f)
        val t = (y - pad).coerceAtLeast(0f)
        val r = (x + pad).coerceAtMost(pw.toFloat())
        val b = (y + pad).coerceAtMost(ph.toFloat())
        // Nothing of this sample's ink can land on the page: nothing to cover.
        if (l >= r || t >= b) return true
        return rects.any { it.left <= l && it.top <= t && it.right >= r && it.bottom >= b }
    }

    /**
     * The invariant, checked the only honest way: walk every segment at a quarter pixel
     * and demand that the ink a sample could put on the page lies in some rect. A gap
     * here is a before-image a host never took, and a mark its undo cannot lift.
     */
    private fun assertCovered(
        rects: List<Bounds>, pts: List<StrokePoint>, pad: Float, pw: Int, ph: Int,
    ) {
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val len = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            val steps = kotlin.math.max(1, (len * 4f).toInt())
            for (k in 0..steps) {
                val t = k.toFloat() / steps
                val x = a.x + (b.x - a.x) * t
                val y = a.y + (b.y - a.y) * t
                if (!covers(rects, x, y, pad, pw, ph)) {
                    throw AssertionError("uncovered sample at $x,$y among ${rects.size} rects")
                }
            }
        }
    }

    @Test
    fun `no points means no rects`() {
        assertTrue(RasterDirty.along(emptyList(), 2f, page.first, page.second).isEmpty())
        assertTrue(RasterDirty.along(listOf(StrokePoint(5f, 5f)), 2f, 0, 200).isEmpty())
    }

    @Test
    fun `a single point is the single rect`() {
        val pts = listOf(StrokePoint(50f, 50f))
        val runs = RasterDirty.along(pts, hairline, page.first, page.second)
        assertEquals(listOf(RasterDirty.of(Bounds.of(pts), hairline, page.first, page.second)!!), runs)
    }

    @Test
    fun `a mark that never reaches the span is announced exactly as it always was`() {
        val pts = line(100f, 100f, 300f, 220f, stepPx = 7f)
        val runs = RasterDirty.along(pts, 2f, bigPage.first, bigPage.second)
        // The whole point of the equivalence: a word-sized mark still costs one read.
        assertEquals(1, runs.size)
        assertEquals(RasterDirty.of(Bounds.of(pts), 2f, bigPage.first, bigPage.second), runs[0])
    }

    @Test
    fun `a long line is cut into runs that overlap on the point they share`() {
        val pts = line(100f, 800f, 1300f, 800f, stepPx = 10f)
        val runs = RasterDirty.along(pts, hairline, bigPage.first, bigPage.second)
        assertTrue("expected several runs, got ${runs.size}", runs.size >= 4)
        val budget = 256f + 2f * hairlinePad + 2f // + the outward snap to whole pixels
        for (r in runs) {
            assertTrue("run too wide: ${r.width}", r.width <= budget)
            assertTrue("run too tall: ${r.height}", r.height <= budget)
        }
        // Consecutive runs share a point, so their rects overlap by at least the pad.
        for (i in 0 until runs.size - 1) {
            val overlap = runs[i].right - runs[i + 1].left
            assertTrue("runs $i/${i + 1} overlap only $overlap", overlap >= hairlinePad)
        }
        assertCovered(runs, pts, hairlinePad, bigPage.first, bigPage.second)
    }

    @Test
    fun `a corner-to-corner hairline covers itself for a fraction of the page`() {
        val pts = line(2f, 2f, 1400f, 1682f, stepPx = 5f)
        val runs = RasterDirty.along(pts, hairline, bigPage.first, bigPage.second)
        assertCovered(runs, pts, hairlinePad, bigPage.first, bigPage.second)
        val pageArea = bigPage.first.toLong() * bigPage.second.toLong()
        val announced = runs.sumOf { (it.width * it.height).toLong() }
        // One box would have been the whole page — that is the bug this method is.
        assertTrue("announced $announced of $pageArea", announced < pageArea / 4)
        assertTrue(runs.size >= 5)
    }

    @Test
    fun `at the cap the last run absorbs the tail`() {
        val pts = line(100f, 800f, 1300f, 800f, stepPx = 10f)
        val runs = RasterDirty.along(pts, hairline, bigPage.first, bigPage.second, maxRects = 3)
        assertEquals(3, runs.size)
        assertCovered(runs, pts, hairlinePad, bigPage.first, bigPage.second)
        // Graceful degradation: a bigger rect, never a dropped one.
        assertTrue(runs.last().width > runs.first().width)
    }

    @Test
    fun `runs off the page are dropped and a stroke wholly off it announces nothing`() {
        assertTrue(
            RasterDirty.along(line(-2000f, 50f, -200f, 50f, 10f), hairline, page.first, page.second)
                .isEmpty(),
        )
        val pts = line(-1000f, 50f, 60f, 50f, stepPx = 10f)
        val runs = RasterDirty.along(pts, hairline, page.first, page.second)
        assertTrue(runs.isNotEmpty())
        for (r in runs) {
            assertTrue(r.left >= 0f && r.top >= 0f)
            assertTrue(r.right <= page.first.toFloat() && r.bottom <= page.second.toFloat())
        }
        assertCovered(runs, pts, hairlinePad, page.first, page.second)
    }

    @Test
    fun `a stroke that doubles back stays inside the span budget`() {
        val out = line(100f, 400f, 700f, 400f, stepPx = 10f)
        val back = line(700f, 404f, 100f, 404f, stepPx = 10f)
        val pts = out + back
        val runs = RasterDirty.along(pts, hairline, bigPage.first, bigPage.second)
        val budget = 256f + 2f * hairlinePad + 2f
        for (r in runs) {
            assertTrue("run too wide: ${r.width}", r.width <= budget)
            assertTrue("run too tall: ${r.height}", r.height <= budget)
        }
        assertCovered(runs, pts, hairlinePad, bigPage.first, bigPage.second)
    }
}
