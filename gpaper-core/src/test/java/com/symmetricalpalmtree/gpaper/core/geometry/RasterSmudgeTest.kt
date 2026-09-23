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
        val changed = smudge(px, sweep, RasterSmudging(strength = 1f, spread = 3, feather = 0f, loss = 0f, gamma = 1f, carry = 0f))
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
        smudge(px, listOf(p(32f, 32f), p(36f, 32f)), RasterSmudging(strength = 1f, spread = 4, feather = 0.5f, loss = 0f, gamma = 1f, carry = 0f))
        for (y in 0 until h) for (x in 0 until w) {
            val d = Geometry.distancePointToSegment(x + 0.5f, y + 0.5f, 32f, 32f, 36f, 32f)
            if (d >= radius) assertEquals("($x,$y)", before[y * w + x], px[y * w + x])
        }
    }

    @Test
    fun `graphite inside the corridor is kept when nothing is lost`() {
        val px = hatch()
        // A window well inside the corridor (clear of its rounded ends), four lattice periods
        // wide so it holds exactly the graphite a mean over the lattice spreads across it.
        // (Inside the corridor and clear of the landing too: the one-sided smear drags graphite
        // forward from the untouched paper behind the landing, so the first period past it gains.)
        val x0 = 28; val x1 = 56; val y0 = 30; val y1 = 34
        val before = sum(px, x0, y0, x1, y1)
        smudge(px, listOf(p(20f, 32f), p(50f, 32f)), RasterSmudging(strength = 1f, spread = 3, feather = 0f, loss = 0f, gamma = 1f, carry = 0f))
        val after = sum(px, x0, y0, x1, y1)
        // The hatch is periodic, so what flows out of the window is matched by what flows
        // in — within the per-pixel rounding of the mean, the total stands.
        assertTrue("$before → $after", abs(after - before) < before / 40)
    }

    @Test
    fun `loss pales the tone by the fraction asked`() {
        val solid = IntArray(w * h) { 0xFF505050.toInt() }
        val px = solid.copyOf()
        smudge(px, listOf(p(32f, 32f), p(40f, 32f)), RasterSmudging(strength = 1f, spread = 2, feather = 0f, loss = 0.10f, gamma = 1f, carry = 0f))
        // Solid grey blurs to itself; only the loss shows. 255 × 0.9 = 229.5 → 230.
        assertEquals(230, alphaAt(px, 32, 32))
        assertEquals(0x50, greyAt(px, 32, 32))
        // Outside the radius: untouched.
        assertEquals(255, alphaAt(px, 32, 50))
    }

    @Test
    fun `a bare page is a no-op`() {
        val px = IntArray(w * h)
        assertFalse(smudge(px, listOf(p(32f, 32f), p(40f, 32f)), RasterSmudging(carry = 0f)))
    }

    @Test
    fun `strength scales the pull and feather softens the edge`() {
        val light = hatch()
        val firm = hatch()
        smudge(light, listOf(p(28f, 32f), p(36f, 32f)), RasterSmudging(strength = 0.2f, spread = 3, feather = 0f, loss = 0f, gamma = 1f, carry = 0f))
        smudge(firm, listOf(p(28f, 32f), p(36f, 32f)), RasterSmudging(strength = 0.8f, spread = 3, feather = 0f, loss = 0f, gamma = 1f, carry = 0f))
        // The gap beside the centre line fills more under the firmer pull.
        assertTrue(alphaAt(firm, 31, 32) > alphaAt(light, 31, 32))
        val feathered = hatch()
        smudge(feathered, listOf(p(28f, 32f), p(36f, 32f)), RasterSmudging(strength = 1f, spread = 3, feather = 1f, loss = 0f, gamma = 1f, carry = 0f))
        // At the rim the pull has faded: a gap 10 px out fills less than one at the centre.
        assertTrue(alphaAt(feathered, 45, 32) < alphaAt(feathered, 31, 32))
    }

    @Test
    fun `a transparent neighbour lends no colour`() {
        // One black line beside bare paper: the smudge into the gap must be black, not a
        // grey averaged with the transparent pixels' zero channels.
        val px = IntArray(w * h) { i -> if (i % w == 30) 0xFF000000.toInt() else 0 }
        smudge(px, listOf(p(26f, 32f), p(34f, 32f)), RasterSmudging(strength = 1f, spread = 2, feather = 0f, loss = 0f, gamma = 1f, carry = 0f))
        assertTrue(alphaAt(px, 31, 32) > 0)
        assertEquals(0, greyAt(px, 31, 32))
    }

    @Test
    fun `within a pass a pixel is pulled once, however many batches cross it`() {
        val once = hatch()
        val sweep = listOf(p(28f, 32f), p(36f, 32f))
        smudge(once, sweep, RasterSmudging(strength = 0.5f, spread = 3, feather = 0f, loss = 0f, gamma = 1f, carry = 0f))
        val many = hatch()
        val pass = ByteArray(w * h)
        val s = RasterSmudging(strength = 0.5f, spread = 3, feather = 0f, loss = 0f, gamma = 1f, carry = 0f)
        repeat(6) { smudge(many, sweep, s, pass) }
        for (x in 28..40) assertEquals("x=$x", alphaAt(once, x, 32), alphaAt(many, x, 32))
        // A fresh pass pulls again.
        smudge(many, sweep, s)
        assertTrue(alphaAt(many, 31, 32) > alphaAt(once, 31, 32))
    }

    @Test
    fun `loss is per pass too`() {
        val solid = IntArray(w * h) { 0xFF505050.toInt() }
        val pass = ByteArray(w * h)
        val s = RasterSmudging(strength = 1f, spread = 2, feather = 0f, loss = 0.10f, gamma = 1f, carry = 0f)
        repeat(5) { smudge(solid, listOf(p(32f, 32f), p(40f, 32f)), s, pass) }
        // Read at the sweep's end, whose whole trailing window is inside the corridor — at the
        // landing the one-sided smear pulls the untouched, darker paper behind it forward.
        assertEquals(230, alphaAt(solid, 40, 32))
        RasterSmudge.clearPass(pass, w, 0, 0, w, h)
        smudge(solid, listOf(p(32f, 32f), p(40f, 32f)), s, pass)
        assertEquals(207, alphaAt(solid, 40, 32))
    }

    @Test
    fun `at gamma 2 a hatch settles to its root mean square, denser than its mean, and stays there`() {
        // One full fleck in five: mean 51, RMS 114.
        val px = IntArray(w * h) { i -> if (i % w % 5 == 0) 0xFF000000.toInt() else 0 }
        val s = RasterSmudging(strength = 1f, spread = 2, feather = 0f, loss = 0f, gamma = 2f, carry = 0f)
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
    fun `the finger carries graphite out past the edge and lays it down fading`() {
        // Solid grey on the left third; a rub that starts in it and runs out to the right.
        val px = IntArray(w * h) { i -> if (i % w < 20) 0xFF505050.toInt() else 0 }
        val s = RasterSmudging(strength = 1f, spread = 2, feather = 0f, loss = 0f, gamma = 1f, carry = 20f, deposit = 0.5f)
        val load = RasterSmudge.Load()
        val pass = ByteArray(w * h)
        // Batches of 6 px, radius 12: from x=10 out to x=58.
        var x = 10f
        while (x < 58f) {
            RasterSmudge.smudgeBatch(px, 0, 0, w, h, 0, 0, w, h, w, pass, listOf(p(x, 32f), p(x + 6f, 32f)), radius, s, load = load)
            x += 6f
        }
        val near = alphaAt(px, 26, 32)
        val mid = alphaAt(px, 40, 32)
        val far = alphaAt(px, 56, 32)
        assertTrue("near $near", near > 40)
        assertTrue("near $near > mid $mid", near > mid)
        assertTrue("mid $mid > far $far", mid > far)
        assertTrue("far $far", far > 0)
        // Laid in the graphite's own grey, not black.
        assertTrue(abs(greyAt(px, 40, 32) - 0x50) <= 1)
        // Nothing above or below the corridor.
        assertEquals(0, alphaAt(px, 40, 10))
    }

    @Test
    fun `with no carry a rub out of a mark lays nothing`() {
        val px = IntArray(w * h) { i -> if (i % w < 20) 0xFF505050.toInt() else 0 }
        val s = RasterSmudging(strength = 1f, spread = 2, feather = 0f, loss = 0f, gamma = 1f, carry = 0f)
        val load = RasterSmudge.Load()
        val pass = ByteArray(w * h)
        RasterSmudge.smudgeBatch(px, 0, 0, w, h, 0, 0, w, h, w, pass, listOf(p(30f, 32f), p(50f, 32f)), radius, s, load = load)
        assertEquals(0, alphaAt(px, 45, 32))
    }

    // ── The smear follows the hand (0.1.60) ─────────────────────────────────

    private fun iso(spread: Int, across: Int = 0) =
        RasterSmudging(strength = 1f, spread = spread, feather = 0f, loss = 0f, gamma = 1f, carry = 0f, across = across)

    @Test
    fun `the axis is the line of travel, out and back alike, and nothing for a dwell`() {
        val right = RasterSmudge.axis(listOf(p(10f, 10f), p(30f, 10f)))!!
        assertEquals(1f, abs(right[0]), 1e-4f)
        assertEquals(0f, right[1], 1e-4f)
        val outAndBack = RasterSmudge.axis(listOf(p(10f, 10f), p(30f, 10f), p(12f, 10f)))!!
        assertEquals(1f, abs(outAndBack[0]), 1e-4f)
        val diagonal = RasterSmudge.axis(listOf(p(0f, 0f), p(10f, 10f)))!!
        assertEquals(abs(diagonal[0]), abs(diagonal[1]), 1e-4f)
        assertEquals(null, RasterSmudge.axis(listOf(p(5f, 5f))))
        assertEquals(null, RasterSmudge.axis(listOf(p(5f, 5f), p(5.5f, 5f))))
    }

    @Test
    fun `a rub across the hatch runs it together, a rub along it does not`() {
        // Vertical lines every 7 px; a horizontal rub sees a 7 px window across them.
        val acrossHatch = hatch()
        smudge(acrossHatch, listOf(p(20f, 32f), p(44f, 32f)), iso(spread = 3))
        assertTrue(alphaAt(acrossHatch, 31, 32) > 0)   // a gap pixel filled from the lines
        // A vertical rub over the same lines: the kernel lies along them, and a gap pixel
        // three px from the nearest line sees nothing but gap.
        val alongHatch = hatch()
        smudge(alongHatch, listOf(p(32f, 20f), p(32f, 44f)), iso(spread = 3))
        assertEquals(0, alphaAt(alongHatch, 31, 32))
        assertEquals(0, alphaAt(alongHatch, 32, 32))
        // …and the lines themselves are untouched: their mean along themselves is themselves.
        assertEquals(255, alphaAt(alongHatch, 28, 32))
    }

    @Test
    fun `a diagonal rub smears a dot along the diagonal only`() {
        val px = IntArray(w * h)
        px[32 * w + 32] = 0xFF000000.toInt()
        smudge(px, listOf(p(24f, 24f), p(40f, 40f)), iso(spread = 4))
        // Ahead along the line of travel the dot has spread; behind it and across it, nothing.
        assertTrue(alphaAt(px, 34, 34) > 0)
        assertEquals(0, alphaAt(px, 30, 30))
        assertEquals(0, alphaAt(px, 34, 30))
        assertEquals(0, alphaAt(px, 30, 34))
    }

    @Test
    fun `a nib that has not moved smudges nothing, and nothing behind the landing moves`() {
        val still = hatch()
        assertFalse(smudge(still, listOf(p(32f, 32f)), iso(spread = 3)))
        assertFalse(smudge(still, listOf(p(32f, 32f), p(32.5f, 32f)), iso(spread = 3)))
        assertTrue(still.contentEquals(hatch()))
        // Landing at x=32 and pulling right: the line at 28 and the gap at 24 are inside the
        // corridor's radius but behind the landing — untouched. Ahead, the gap fills.
        val right = hatch()
        smudge(right, listOf(p(32f, 32f), p(44f, 32f)), iso(spread = 3))
        assertEquals(255, alphaAt(right, 28, 32))
        assertEquals(0, alphaAt(right, 24, 32))
        assertTrue(alphaAt(right, 38, 32) > 0)
        // …and pulling left from the same landing, the mirror.
        val leftward = hatch()
        smudge(leftward, listOf(p(32f, 32f), p(20f, 32f)), iso(spread = 3))
        assertEquals(255, alphaAt(leftward, 35, 32))
        assertEquals(0, alphaAt(leftward, 38, 32))
        assertTrue(alphaAt(leftward, 26, 32) > 0)
    }

    @Test
    fun `across widens the smear beside the line of travel`() {
        val narrow = IntArray(w * h).also { it[32 * w + 32] = 0xFF000000.toInt() }
        smudge(narrow, listOf(p(20f, 32f), p(44f, 32f)), iso(spread = 4, across = 0))
        assertEquals(0, alphaAt(narrow, 32, 33))
        val wide = IntArray(w * h).also { it[32 * w + 32] = 0xFF000000.toInt() }
        smudge(wide, listOf(p(20f, 32f), p(44f, 32f)), iso(spread = 4, across = 1))
        assertTrue(alphaAt(wide, 32, 33) > 0)
        assertEquals(0, alphaAt(wide, 32, 34))
    }

    @Test
    fun `the axis is quantized to the lattice's four`() {
        assertTrue(RasterSmudge.quantizeAxis(1f, 0f).contentEquals(intArrayOf(1, 0)))
        assertTrue(RasterSmudge.quantizeAxis(-1f, 0.1f).contentEquals(intArrayOf(1, 0)))
        assertTrue(RasterSmudge.quantizeAxis(0f, 1f).contentEquals(intArrayOf(0, 1)))
        assertTrue(RasterSmudge.quantizeAxis(0.7f, 0.7f).contentEquals(intArrayOf(1, 1)))
        assertTrue(RasterSmudge.quantizeAxis(-0.7f, -0.7f).contentEquals(intArrayOf(1, 1)))
        assertTrue(RasterSmudge.quantizeAxis(-0.7f, 0.7f).contentEquals(intArrayOf(1, -1)))
        assertTrue(RasterSmudge.quantizeAxis(0.7f, -0.7f).contentEquals(intArrayOf(1, -1)))
        // 30° is nearer the diagonal than the row.
        assertTrue(RasterSmudge.quantizeAxis(0.866f, 0.5f).contentEquals(intArrayOf(1, 1)))
    }

    @Test
    fun `a line mean is a clipped running mean along its lattice line, every pixel visited once`() {
        // One full pixel at (1, 1) on a 4 × 4 plane; a (1,1) diagonal mean of half 1.
        val plane = IntArray(16); plane[1 * 4 + 1] = 90
        val tmp = IntArray(16); val line = IntArray(4)
        RasterSmudge.lineMean(plane, tmp, line, 4, 4, 1, 1, 1, 1)
        assertEquals(45, plane[0 * 4 + 0])   // window {(0,0),(1,1)} clipped at the start
        assertEquals(30, plane[1 * 4 + 1])   // {(0,0),(1,1),(2,2)}
        assertEquals(30, plane[2 * 4 + 2])   // {(1,1),(2,2),(3,3)}
        assertEquals(0, plane[3 * 4 + 3])
        assertEquals(0, plane[0 * 4 + 1])    // another line: untouched
        // The anti-diagonal walks every pixel exactly once too: a constant plane stays constant.
        val flat = IntArray(16) { 7 }
        RasterSmudge.lineMean(flat, tmp, line, 4, 4, 1, -1, 2, 2)
        for (v in flat) assertEquals(7, v)
        val rows = IntArray(16) { 7 }
        RasterSmudge.lineMean(rows, tmp, line, 4, 4, 1, 0, 2, 2)
        for (v in rows) assertEquals(7, v)
        val cols = IntArray(16) { 7 }
        RasterSmudge.lineMean(cols, tmp, line, 4, 4, 0, 1, 2, 2)
        for (v in cols) assertEquals(7, v)
        // One-sided: only what is behind (earlier on the line) is gathered.
        val one = IntArray(16); one[1 * 4 + 1] = 90
        RasterSmudge.lineMean(one, tmp, line, 4, 4, 1, 0, 2, 0)
        assertEquals(0, one[1 * 4 + 0])    // nothing behind it
        assertEquals(45, one[1 * 4 + 1])   // {0, 90}, clipped at the line's start
        assertEquals(30, one[1 * 4 + 2])   // {0, 90, 0}
        assertEquals(30, one[1 * 4 + 3])   // {90, 0, 0}
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
