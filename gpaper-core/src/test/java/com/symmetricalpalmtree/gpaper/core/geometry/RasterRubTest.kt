package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.RasterRubbing
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rubber must lift a fraction and not cut a hole, lift less at its edge than under its
 * middle, lift each pixel once per pass however many batches cross it, and lift again
 * when the arm comes back. Every one of those is arithmetic on arrays, so every one of
 * them is proved here with no bitmap in the room.
 */
class RasterRubTest {

    private val rubbing = RasterRubbing(liftLight = 0.25f, liftFirm = 0.60f, feather = 0.5f)
    private val radius = 10f
    private val pageW = 64
    private val pageH = 64

    private fun p(x: Float, y: Float, pressure: Float = 0.5f) = StrokePoint(x, y, pressure = pressure)

    /** A page rect of solid mid-grey graphite at full alpha. */
    private fun graphite(w: Int, h: Int) = IntArray(w * h) { 0xFF505050.toInt() }

    private fun alphaAt(pixels: IntArray, w: Int, x: Int, y: Int) = pixels[y * w + x] ushr 24

    private fun rub(pixels: IntArray, pass: ByteArray, sweep: List<StrokePoint>, lift: Float) =
        RasterRub.rubBatch(pixels, 0, 0, pageW, pageH, pageW, pass, sweep, radius, rubbing, lift)

    // ── Lift ─────────────────────────────────────────────────────────────────

    @Test
    fun `lift runs from light to firm with pressure, and an unreported pressure is the middle`() {
        assertEquals(0.25f, RasterRub.lift(0f, rubbing), 1e-6f)
        assertEquals(0.60f, RasterRub.lift(1f, rubbing), 1e-6f)
        assertEquals(0.425f, RasterRub.lift(0.5f, rubbing), 1e-6f)
        assertEquals(RasterRub.lift(0.5f, rubbing), RasterRub.lift(Float.NaN, rubbing), 1e-6f)
        assertEquals(RasterRub.lift(0.5f, rubbing), RasterRub.lift(-1f, rubbing), 1e-6f)
    }

    // ── Coverage ─────────────────────────────────────────────────────────────

    @Test
    fun `coverage is full in the core, nothing past the radius, and fades between`() {
        val sweep = listOf(p(32f, 32f))
        assertEquals(1f, RasterRub.coverage(sweep, radius, 0.5f, 32f, 32f), 1e-6f)
        assertEquals(1f, RasterRub.coverage(sweep, radius, 0.5f, 37f, 32f), 1e-6f) // 5 = core edge
        assertEquals(0.5f, RasterRub.coverage(sweep, radius, 0.5f, 39.5f, 32f), 1e-5f) // halfway out
        assertEquals(0f, RasterRub.coverage(sweep, radius, 0.5f, 42f, 32f), 1e-6f)
        assertEquals(0f, RasterRub.coverage(sweep, radius, 0.5f, 43f, 40f), 1e-6f)
    }

    @Test
    fun `no feather is a hard edge`() {
        val sweep = listOf(p(32f, 32f))
        assertEquals(1f, RasterRub.coverage(sweep, radius, 0f, 41.9f, 32f), 1e-6f)
        assertEquals(0f, RasterRub.coverage(sweep, radius, 0f, 42.1f, 32f), 1e-6f)
    }

    // ── One pass ─────────────────────────────────────────────────────────────

    @Test
    fun `one pass lifts the lift fraction under the core and leaves the rest`() {
        val pixels = graphite(pageW, pageH)
        val pass = ByteArray(pageW * pageH)
        assertTrue(rub(pixels, pass, listOf(p(20f, 32f), p(44f, 32f)), 0.4f))
        // Under the middle: 255 × 0.6 = 153.
        assertEquals(153, alphaAt(pixels, pageW, 32, 32))
        // Well outside the corridor: untouched.
        assertEquals(255, alphaAt(pixels, pageW, 32, 50))
        assertEquals(255, alphaAt(pixels, pageW, 5, 5))
        // Colour is left alone.
        assertEquals(0x505050, pixels[32 * pageW + 32] and 0xFFFFFF)
    }

    @Test
    fun `the edge lifts less than the middle`() {
        val pixels = graphite(pageW, pageH)
        val pass = ByteArray(pageW * pageH)
        rub(pixels, pass, listOf(p(20f, 32f), p(44f, 32f)), 0.4f)
        val middle = alphaAt(pixels, pageW, 32, 32)
        val band = alphaAt(pixels, pageW, 32, 39) // 7.5 px out: in the feathered band
        val outside = alphaAt(pixels, pageW, 32, 43)
        assertTrue("middle $middle should be lifted more than the band $band", middle < band)
        assertTrue("the band $band should be lifted at all", band < 255)
        assertEquals(255, outside)
    }

    // ── Seams and passes ─────────────────────────────────────────────────────

    @Test
    fun `two batches of one pass do not lift their seam twice`() {
        val pixels = graphite(pageW, pageH)
        val pass = ByteArray(pageW * pageH)
        // Chained as the engine chains them: the second batch starts on the first's last sample.
        rub(pixels, pass, listOf(p(10f, 32f), p(32f, 32f)), 0.4f)
        rub(pixels, pass, listOf(p(32f, 32f), p(54f, 32f)), 0.4f)
        assertEquals(153, alphaAt(pixels, pageW, 32, 32)) // the seam
        assertEquals(153, alphaAt(pixels, pageW, 20, 32)) // the body of the first
        assertEquals(153, alphaAt(pixels, pageW, 44, 32)) // the body of the second
    }

    @Test
    fun `a second pass lifts again on top of the first`() {
        val pixels = graphite(pageW, pageH)
        val pass = ByteArray(pageW * pageH)
        rub(pixels, pass, listOf(p(10f, 32f), p(54f, 32f)), 0.4f)
        RasterRub.clearPass(pass, pageW, 0, 0, pageW, pageH)
        rub(pixels, pass, listOf(p(54f, 32f), p(10f, 32f)), 0.4f)
        // 255 × 0.6 × 0.6 = 91.8 → 92
        assertEquals(92, alphaAt(pixels, pageW, 32, 32))
    }

    @Test
    fun `a firmer batch in the same pass raises the lift to its own level, never doubles it`() {
        val pixels = graphite(pageW, pageH)
        val pass = ByteArray(pageW * pageH)
        rub(pixels, pass, listOf(p(10f, 32f), p(54f, 32f)), 0.3f)
        rub(pixels, pass, listOf(p(10f, 32f), p(54f, 32f)), 0.5f)
        // Ends at 255 × 0.5 = 127.5 → 128 (via 178.5 × 0.5/0.7), not 255 × 0.7 × 0.5.
        val a = alphaAt(pixels, pageW, 32, 32)
        assertTrue("got $a", a in 127..129)
        // And a lighter batch after it changes nothing.
        assertFalse(rub(pixels, pass, listOf(p(10f, 32f), p(54f, 32f)), 0.2f))
    }

    @Test
    fun `a fully lifted pixel stays at nothing and stays lifted`() {
        val pixels = graphite(pageW, pageH)
        val pass = ByteArray(pageW * pageH)
        rub(pixels, pass, listOf(p(32f, 32f)), 1f)
        assertEquals(0, alphaAt(pixels, pageW, 32, 32))
        RasterRub.clearPass(pass, pageW, 0, 0, pageW, pageH)
        rub(pixels, pass, listOf(p(32f, 32f)), 0.5f)
        assertEquals(0, alphaAt(pixels, pageW, 32, 32))
    }

    // ── Reversal ─────────────────────────────────────────────────────────────

    @Test
    fun `coming back along the sweep is a reversal, turning a corner is not`() {
        val east = RasterRub.direction(listOf(p(0f, 0f), p(10f, 0f)))
        val west = RasterRub.direction(listOf(p(10f, 0f), p(0f, 0f)))
        val south = RasterRub.direction(listOf(p(0f, 0f), p(0f, 10f)))
        val backish = RasterRub.direction(listOf(p(0f, 0f), p(-10f, 4f)))
        assertTrue(RasterRub.isReversal(east, west))
        assertTrue(RasterRub.isReversal(east, backish))
        assertFalse(RasterRub.isReversal(east, south))
        assertFalse(RasterRub.isReversal(null, west))
        assertFalse(RasterRub.isReversal(east, null))
    }

    @Test
    fun `a batch that barely moved has no direction`() {
        assertEquals(null, RasterRub.direction(listOf(p(5f, 5f), p(5.5f, 5f))))
        assertEquals(null, RasterRub.direction(listOf(p(5f, 5f))))
    }

    // ── The pass mask ────────────────────────────────────────────────────────

    @Test
    fun `clearing the pass over a rect forgets only that rect`() {
        val pass = ByteArray(pageW * pageH) { 7 }
        RasterRub.clearPass(pass, pageW, 10, 10, 4, 4)
        assertEquals(0, pass[12 * pageW + 12].toInt())
        assertEquals(7, pass[12 * pageW + 14].toInt())
        assertEquals(7, pass[14 * pageW + 12].toInt())
        assertEquals(7, pass[9 * pageW + 10].toInt())
    }

    @Test
    fun `a rect that is only partly under the rubber lifts only that part`() {
        val pixels = graphite(pageW, pageH)
        val pass = ByteArray(pageW * pageH)
        assertTrue(rub(pixels, pass, listOf(p(0f, 0f)), 0.5f))
        assertEquals(128, alphaAt(pixels, pageW, 0, 0))
        assertEquals(255, alphaAt(pixels, pageW, 20, 20))
        assertFalse(rub(pixels, pass, listOf(p(-50f, -50f)), 0.5f))
    }
}
