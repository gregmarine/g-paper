package com.symmetricalpalmtree.gpaper.ratta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shade ladder on the panel painted directly (Phase 28).
 *
 * A pencil shade here is a **density of black flecks**, fitted to Atelier's own HB pencil read
 * back off a Nomad's panel frame (2026-09-19 — `probe-ebc/README.md`, "Atelier read back", and
 * [RattaPencilInk]'s KDoc). These are the fit's own points, pinned: the curve is a starting
 * value for a hand walk, not a measurement of *our* grain, and the walk that re-tunes it must
 * move these numbers deliberately rather than discover they have drifted.
 */
class RattaPencilInkTest {

    private fun grey(level: Int): Int = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    private fun assertBlackAt(expected: Float, argb: Int, what: String) {
        val ink = RattaPencilInk.of(argb)
        assertEquals("$what: the ink must be opaque black", 0xFF000000.toInt(), ink.color)
        assertTrue("$what: flecks are opaque on this path", ink.opaque)
        assertEquals("$what: density", expected, ink.density, 0.01f)
    }

    @Test
    fun `a black lead lays every fleck`() {
        assertBlackAt(1f, 0xFF000000.toInt(), "black")
    }

    @Test
    fun `the fitted rungs`() {
        // luma 85, 153, 204, 221 — the shades the brief names, against the curve
        // 1 - 0.85 * (luma / 221)^1.5.
        assertBlackAt(0.80f, grey(0x55), "#555555 (luma 85)")
        assertBlackAt(0.51f, grey(0x99), "#999999 (luma 153)")
        assertBlackAt(0.24f, grey(0xCC), "#CCCCCC (luma 204)")
        assertBlackAt(0.15f, grey(0xDD), "#DDDDDD (luma 221) — the floor, reached by the curve")
    }

    @Test
    fun `the white lead is a lightener, not a density`() {
        // It pales the graphite under it and lays nothing on bare paper, so there is no
        // scatter to thin — and white lands on this panel as fast as black does.
        for (argb in listOf(0xFFFFFFFF.toInt(), grey(0xF0), grey(0xE0))) {
            val ink = RattaPencilInk.of(argb)
            assertEquals("white ink", 0xFFFFFFFF.toInt(), ink.color)
            assertTrue("opaque", ink.opaque)
            assertEquals("full density", 1f, ink.density, 0f)
        }
    }

    @Test
    fun `the band below white is still a black density`() {
        // 224 is the compositor's own top band; one step under it is the palest grey lead.
        val ink = RattaPencilInk.of(grey(0xDF)) // luma 223
        assertEquals(0xFF000000.toInt(), ink.color)
        assertEquals(0.15f, ink.density, 0.01f)
    }

    @Test
    fun `the ladder never rises and never leaves the paper bare`() {
        var previous = 1.01f
        for (level in 0..0xDF) {
            val ink = RattaPencilInk.of(grey(level))
            assertTrue("grey $level: density must not rise with luma", ink.density <= previous + 1e-6f)
            assertTrue("grey $level: a lead that lays nothing reads as a broken pen", ink.density >= 0.15f)
            assertTrue("grey $level: density is a fraction", ink.density <= 1f)
            previous = ink.density
        }
    }

    @Test
    fun `the whole fit, against the fourteen read-back points`() {
        // Atelier's measured shades and their black-pixels-per-px relative to the black lead
        // (probe-ebc/README.md). The fit is stated as "within a few percent"; 0.03 is that.
        val measured = listOf(
            0x50 to 0.82f, 0x60 to 0.77f, 0x68 to 0.72f, 0x70 to 0.69f,
            0x80 to 0.63f, 0x88 to 0.58f, 0x90 to 0.56f, 0xA0 to 0.48f,
            0xAA to 0.42f, 0xB6 to 0.37f, 0xC0 to 0.32f, 0xC8 to 0.27f,
            0xD0 to 0.25f, 0xDD to 0.15f,
        )
        for ((level, expected) in measured) {
            val density = RattaPencilInk.of(grey(level)).density
            assertTrue(
                "grey ${Integer.toHexString(level)}: fitted $density vs measured $expected",
                kotlin.math.abs(density - expected) <= 0.035f,
            )
        }
    }

    @Test
    fun `colour is read as luma, like every other mapping here`() {
        // Nothing on this panel is a colour; a coloured lead is whatever grey it weighs.
        val ink = RattaPencilInk.of(0xFF3366CC.toInt())
        val same = RattaPencilInk.of(grey(RattaInkMap.luma(0xFF3366CC.toInt()).toInt()))
        assertEquals(ink.color, same.color)
        assertEquals(ink.density, same.density, 0.01f)
    }
}
