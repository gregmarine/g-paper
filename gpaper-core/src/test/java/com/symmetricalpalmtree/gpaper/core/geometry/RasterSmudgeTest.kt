package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.RasterSmudging
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A smudge must blend, not lift: separate lines under the finger run together, the
 * graphite between them is what was on the lines, the total inside the corridor is
 * (nearly) kept, nothing beyond the finger moves, and the little that goes is the loss
 * asked for. Arithmetic on arrays, proved with no bitmap in the room.
 */
class RasterSmudgeTest {

    private val w = 64
    private val h = 64
    private val radius = 12f

    private fun p(x: Float, y: Float) = StrokePoint(x, y, pressure = 0.5f)

    private fun alphaAt(px: IntArray, x: Int, y: Int) = px[y * w + x] ushr 24
    private fun greyAt(px: IntArray, x: Int, y: Int) = px[y * w + x] and 0xFF

    /** Vertical hatch lines every 7 px across the page, one px wide, mid-grey, full alpha. */
    private fun hatch(): IntArray = IntArray(w * h) { i ->
        val x = i % w
        if (x % 7 == 0) 0xFF505050.toInt() else 0
    }

    private fun sum(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int): Long {
        var s = 0L
        for (y in y0 until y1) for (x in x0 until x1) s += alphaAt(px, x, y)
        return s
    }

    private fun smudge(
        px: IntArray, sweep: List<StrokePoint>, smudging: RasterSmudging,
        pass: ByteArray = ByteArray(w * h),
    ) = RasterSmudge.smudgeBatch(px, 0, 0, w, h, 0, 0, w, h, w, pass, sweep, radius, smudging)

    @Test
    fun `lines under the finger run together and the gaps fill from them`() {
        val px = hatch()
        val sweep = listOf(p(20f, 32f), p(44f, 32f))
        val changed = smudge(px, sweep, RasterSmudging(strength = 1f, spread = 3, feather = 0f, loss = 0f, gamma = 1f))
        assertTrue(changed)
        // A gap pixel under the core now carries graphite, in the lines' own grey.
        assertTrue(alphaAt(px, 33, 32) > 0)
        assertTrue(abs(greyAt(px, 33, 32) - 0x50) <= 1)
        // And the line pixel is paler than it was: it gave to its neighbours.
        assertTrue(alphaAt(px, 28, 32) < 255)
        // Across the corridor the tone is flatter: a full-pull box mean with spread 3
        // over a 7 px lattice is exactly one line per window, so every pixel is the same.
        val a = alphaAt(px, 28, 32)
        for (x in 26..38) assertTrue("x=$x", abs(alphaAt(px, x, 32) - a) <= 1)
    }

    @Test
    fun `nothing beyond the finger moves`() {
        val px = hatch()
        val before = px.copyOf()
        smudge(px, listOf(p(32f, 32f)), RasterSmudging(strength = 1f, spread = 4, feather = 0.5f, loss = 0f, gamma = 1f))
        for (y in 0 until h) for (x in 0 until w) {
            val d = Math.hypot((x + 0.5 - 32.0), (y + 0.5 - 32.0))
            if (d >= radius) assertEquals("($x,$y)", before[y * w + x], px[y * w + x])
        }
    }

    @Test
    fun `graphite inside the corridor is kept when nothing is lost`() {
        val px = hatch()
        // A window well inside the corridor (clear of its rounded ends), four lattice periods
        // wide so it holds exactly the graphite a mean over the lattice spreads across it.
        val x0 = 21; val x1 = 49; val y0 = 30; val y1 = 34
        val before = sum(px, x0, y0, x1, y1)
        smudge(px, listOf(p(26f, 32f), p(38f, 32f)), RasterSmudging(strength = 1f, spread = 3, feather = 0f, loss = 0f, gamma = 1f))
        val after = sum(px, x0, y0, x1, y1)
        // The hatch is periodic, so what flows out of the window is matched by what flows
        // in — within the per-pixel rounding of the mean, the total stands.
        assertTrue("$before → $after", abs(after - before) < before / 40)
    }

    @Test
    fun `loss pales the tone by the fraction asked`() {
        val solid = IntArray(w * h) { 0xFF505050.toInt() }
        val px = solid.copyOf()
        smudge(px, listOf(p(32f, 32f)), RasterSmudging(strength = 1f, spread = 2, feather = 0f, loss = 0.10f, gamma = 1f))
        // Solid grey blurs to itself; only the loss shows. 255 × 0.9 = 229.5 → 230.
        assertEquals(230, alphaAt(px, 32, 32))
        assertEquals(0x50, greyAt(px, 32, 32))
        // Outside the radius: untouched.
        assertEquals(255, alphaAt(px, 32, 50))
    }

    @Test
    fun `a bare page is a no-op`() {
        val px = IntArray(w * h)
        assertFalse(smudge(px, listOf(p(32f, 32f), p(40f, 32f)), RasterSmudging()))
    }

    @Test
    fun `strength scales the pull and feather softens the edge`() {
        val light = hatch()
        val firm = hatch()
        smudge(light, listOf(p(32f, 32f)), RasterSmudging(strength = 0.2f, spread = 3, feather = 0f, loss = 0f, gamma = 1f))
        smudge(firm, listOf(p(32f, 32f)), RasterSmudging(strength = 0.8f, spread = 3, feather = 0f, loss = 0f, gamma = 1f))
        // The gap beside the centre line fills more under the firmer pull.
        assertTrue(alphaAt(firm, 31, 32) > alphaAt(light, 31, 32))
        val feathered = hatch()
        smudge(feathered, listOf(p(32f, 32f)), RasterSmudging(strength = 1f, spread = 3, feather = 1f, loss = 0f, gamma = 1f))
        // At the rim the pull has faded: a gap 10 px out fills less than one at the centre.
        assertTrue(alphaAt(feathered, 41, 32) < alphaAt(feathered, 31, 32))
    }

    @Test
    fun `a transparent neighbour lends no colour`() {
        // One black line beside bare paper: the smudge into the gap must be black, not a
        // grey averaged with the transparent pixels' zero channels.
        val px = IntArray(w * h) { i -> if (i % w == 30) 0xFF000000.toInt() else 0 }
        smudge(px, listOf(p(30f, 32f)), RasterSmudging(strength = 1f, spread = 2, feather = 0f, loss = 0f, gamma = 1f))
        assertTrue(alphaAt(px, 31, 32) > 0)
        assertEquals(0, greyAt(px, 31, 32))
    }

    @Test
    fun `within a pass a pixel is pulled once, however many batches cross it`() {
        val once = hatch()
        smudge(once, listOf(p(32f, 32f)), RasterSmudging(strength = 0.5f, spread = 3, feather = 0f, loss = 0f, gamma = 1f))
        val many = hatch()
        val pass = ByteArray(w * h)
        val s = RasterSmudging(strength = 0.5f, spread = 3, feather = 0f, loss = 0f, gamma = 1f)
        repeat(6) { smudge(many, listOf(p(32f, 32f)), s, pass) }
        for (x in 26..38) assertEquals("x=$x", alphaAt(once, x, 32), alphaAt(many, x, 32))
        // A fresh pass pulls again.
        smudge(many, listOf(p(32f, 32f)), s)
        assertTrue(alphaAt(many, 31, 32) > alphaAt(once, 31, 32))
    }

    @Test
    fun `loss is per pass too`() {
        val solid = IntArray(w * h) { 0xFF505050.toInt() }
        val pass = ByteArray(w * h)
        val s = RasterSmudging(strength = 1f, spread = 2, feather = 0f, loss = 0.10f, gamma = 1f)
        repeat(5) { smudge(solid, listOf(p(32f, 32f)), s, pass) }
        assertEquals(230, alphaAt(solid, 32, 32))
        RasterSmudge.clearPass(pass, w, 0, 0, w, h)
        smudge(solid, listOf(p(32f, 32f)), s, pass)
        assertEquals(207, alphaAt(solid, 32, 32))
    }

    @Test
    fun `at gamma 2 a hatch settles to its root mean square, denser than its mean, and stays there`() {
        // One full fleck in five: mean 51, RMS 114.
        val px = IntArray(w * h) { i -> if (i % w % 5 == 0) 0xFF000000.toInt() else 0 }
        val s = RasterSmudging(strength = 1f, spread = 2, feather = 0f, loss = 0f, gamma = 2f)
        // Window 5 = one period: every pixel's neighbourhood is the same.
        smudge(px, listOf(p(20f, 32f), p(44f, 32f)), s)
        val a = alphaAt(px, 32, 32)
        assertTrue("$a", a in 112..116)
        // Even now; a fresh pass on an even field is a fixed point.
        smudge(px, listOf(p(20f, 32f), p(44f, 32f)), s)
        assertTrue(abs(alphaAt(px, 32, 32) - a) <= 1)
        assertTrue(abs(alphaAt(px, 30, 32) - a) <= 1)
    }

    @Test
    fun `the coverage field is the rubber's coverage, laid down per segment`() {
        val sweep = listOf(p(20f, 30f), p(40f, 34f), p(38f, 20f))
        val cov = FloatArray(w * h)
        RasterSmudge.coverageField(cov, 0, 0, w, h, sweep, radius, 0.5f)
        for (y in 0 until h) for (x in 0 until w) {
            val want = RasterRub.coverage(sweep, radius, 0.5f, x + 0.5f, y + 0.5f)
            assertEquals("($x,$y)", want, cov[y * w + x], 1e-5f)
        }
    }

    @Test
    fun `box blur is a clipped mean at the edge`() {
        val plane = IntArray(4 * 1) { 100 }
        plane[0] = 0
        val tmp = IntArray(4)
        RasterSmudge.boxBlur(plane, tmp, 4, 1, 1)
        // x=0 sees {0,100} → 50; x=1 sees {0,100,100} → 67; x=3 sees {100,100} → 100.
        assertEquals(50, plane[0])
        assertEquals(67, plane[1])
        assertEquals(100, plane[3])
    }
}
