package com.symmetricalpalmtree.gpaper.ratta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Screen → panel, pinned on both measured devices.
 *
 * The Nomad's panel is a landscape 1872 × 1404 buffer under a portrait 1404 × 1872 screen;
 * the Manta's panel is the screen. Both were read back out of the driver's own frames. A
 * rotation applied the wrong way is not subtle on a panel — it is a mark drawn in another
 * quarter of the screen — but it is also not something a device walk should have to find.
 */
class EbcGeometryTest {

    private companion object {
        // The Nomad, as the driver reports it: GETINFO says 1872 wide by 1404 high.
        const val NOMAD_PANEL_W = 1872
        const val NOMAD_PANEL_H = 1404
        const val NOMAD_SCREEN_W = 1404
        const val NOMAD_SCREEN_H = 1872

        // The Manta (A5 X2): panel and screen are the same buffer, no rotation anywhere.
        const val MANTA_W = 1920
        const val MANTA_H = 2560
    }

    @Test
    fun `the Nomad's panel is a quarter turn from its screen`() {
        assertTrue(
            EbcGeometry.isRotated(NOMAD_PANEL_W, NOMAD_PANEL_H, NOMAD_SCREEN_W, NOMAD_SCREEN_H)
        )
    }

    @Test
    fun `the Manta's panel is its screen`() {
        assertFalse(EbcGeometry.isRotated(MANTA_W, MANTA_H, MANTA_W, MANTA_H))
    }

    @Test
    fun `a turned panel maps screen x and y as measured`() {
        // panelX = screenY, panelY = panelH - 1 - screenX.
        fun index(x: Int, y: Int) =
            EbcGeometry.panelIndex(true, NOMAD_PANEL_W, NOMAD_PANEL_H, x, y)
        assertEquals((NOMAD_PANEL_H - 1) * NOMAD_PANEL_W + 0, index(0, 0))
        assertEquals((NOMAD_PANEL_H - 1 - 100) * NOMAD_PANEL_W + 200, index(100, 200))
        // The screen's bottom-right corner is the end of the panel's FIRST row.
        assertEquals(NOMAD_PANEL_W - 1, index(NOMAD_SCREEN_W - 1, NOMAD_SCREEN_H - 1))
    }

    @Test
    fun `an identity panel indexes row-major, straight through`() {
        assertEquals(
            300 * MANTA_W + 40,
            EbcGeometry.panelIndex(false, MANTA_W, MANTA_H, 40, 300),
        )
    }

    @Test
    fun `every pixel of a turned panel lands somewhere inside the frame, exactly once`() {
        // A small stand-in panel, walked whole: the map must be a bijection, or a mark
        // either disappears into an unwritten row or writes over another one.
        val panelW = 7
        val panelH = 5
        val seen = HashSet<Int>()
        for (y in 0 until panelW) { // the screen is the panel turned: w and h swap
            for (x in 0 until panelH) {
                val i = EbcGeometry.panelIndex(true, panelW, panelH, x, y)
                assertTrue("index $i out of frame", i in 0 until panelW * panelH)
                assertTrue("index $i written twice", seen.add(i))
            }
        }
        assertEquals(panelW * panelH, seen.size)
    }

    @Test
    fun `a turned rect keeps its area and stays the right way up`() {
        val out = IntArray(4)
        EbcGeometry.panelRect(true, NOMAD_PANEL_H, 100, 200, 140, 260, out)
        // screen top/bottom become panel left/right; screen right/left become panel top/bottom
        // counted from the far edge.
        assertEquals(200, out[0])
        assertEquals(NOMAD_PANEL_H - 140, out[1])
        assertEquals(260, out[2])
        assertEquals(NOMAD_PANEL_H - 100, out[3])
        assertTrue("the rect must not come out inside out", out[2] > out[0] && out[3] > out[1])
        assertEquals(40 * 60, (out[2] - out[0]) * (out[3] - out[1]))
    }

    @Test
    fun `an identity rect is handed straight through`() {
        val out = IntArray(4)
        EbcGeometry.panelRect(false, MANTA_H, 100, 200, 140, 260, out)
        assertEquals(100, out[0])
        assertEquals(200, out[1])
        assertEquals(140, out[2])
        assertEquals(260, out[3])
    }

    @Test
    fun `the corner pixel of a turned rect is inside the turned rect`() {
        // The two halves of the mapping have to agree: whatever pixel index a screen point
        // gets must fall within the panel rect that same screen rect turns into.
        val out = IntArray(4)
        EbcGeometry.panelRect(true, NOMAD_PANEL_H, 100, 200, 140, 260, out)
        for ((x, y) in listOf(100 to 200, 139 to 259, 120 to 230)) {
            val i = EbcGeometry.panelIndex(true, NOMAD_PANEL_W, NOMAD_PANEL_H, x, y)
            val px = i % NOMAD_PANEL_W
            val py = i / NOMAD_PANEL_W
            assertTrue("($x,$y) → ($px,$py) outside ${out.toList()}", px >= out[0] && px < out[2])
            assertTrue("($x,$y) → ($px,$py) outside ${out.toList()}", py >= out[1] && py < out[3])
        }
    }

    // ── The stylus's axes live in the panel's frame too (Phase 36) ───────────

    /** [EbcGeometry.screenAzimuth] in degrees, which is how the measurement was written down. */
    private fun screenAzimuthDeg(rotated: Boolean, tiltX: Float, tiltY: Float): Float {
        val r = EbcGeometry.screenAzimuth(rotated, tiltX, tiltY)
        return Math.toDegrees(r.toDouble()).toFloat()
    }

    /** A lean of [deg] in the raw axes, as the HAL reports it: signed tilt-X, signed tilt-Y. */
    private fun rawLean(deg: Float): Pair<Float, Float> {
        val r = Math.toRadians(deg.toDouble())
        return cos(r).toFloat() * 30f to sin(r).toFloat() * 30f
    }

    @Test
    fun `the same shading grip reads the same on a Manta and on a Nomad`() {
        // **The measurement, pinned** (the user's hand, `probe-tilt`, 2026-09-19). One
        // right-handed shading grip, two devices: the Manta's panel *is* the screen and the
        // raw axes read ~42 degrees; the Nomad's panel is a quarter turn away and the same
        // grip reads ~137. Only the turn `(x, y) -> (y, -x)` brings 137 to ~47 and makes
        // them one grip — which is why the direction of this turn is the hand's finding and
        // not a composition of the pixel rule's inverse, which turns the other way.
        val (mx, my) = rawLean(42f)
        assertEquals(42f, screenAzimuthDeg(false, mx, my), 0.5f)
        val (nx, ny) = rawLean(137f)
        assertEquals(47f, screenAzimuthDeg(true, nx, ny), 0.5f)
        // Which is the whole point: the two are the same direction on the glass to within
        // the hand's own steadiness.
        assertEquals(
            screenAzimuthDeg(false, mx, my),
            screenAzimuthDeg(true, nx, ny),
            5.5f,
        )
    }

    @Test
    fun `an identity panel hands the lean straight through`() {
        for (deg in floatArrayOf(0f, 42f, 90f, 179f, -3f, -120f)) {
            val (x, y) = rawLean(deg)
            assertEquals(deg, screenAzimuthDeg(false, x, y), 0.01f)
        }
    }

    @Test
    fun `a turned panel takes exactly a quarter off the lean`() {
        for (deg in floatArrayOf(0f, 42f, 137f, -90f)) {
            val (x, y) = rawLean(deg)
            val turned = screenAzimuthDeg(true, x, y)
            // Compared as a direction, because an azimuth wraps and -180 is 180.
            val delta = Math.toRadians((turned - (deg - 90f)).toDouble())
            assertEquals("$deg deg turned to $turned", 1.0, cos(delta), 1e-4)
        }
    }

    @Test
    fun `the lean's magnitude does not care how the panel is turned`() {
        // Which is why only the *direction* needs any of this: `hypot` is rotation-blind,
        // so the polar lean is the one reading both devices agree on without help.
        val (x, y) = rawLean(137f)
        assertEquals(hypot(x.toDouble(), y.toDouble()), 30.0, 1e-3)
    }
}
