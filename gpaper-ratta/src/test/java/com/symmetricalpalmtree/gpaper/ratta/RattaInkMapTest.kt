package com.symmetricalpalmtree.gpaper.ratta

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live-ink grey mapping is pure Kotlin so its calibrated thresholds (85 / 187 / 222,
 * midpoints between the tones the four firmware codes actually render at) are pinned on
 * the JVM — a threshold shift would silently misalign live ink from the baked stroke.
 */
class RattaInkMapTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `black and dark inks map to BLACK`() {
        assertEquals(SupernoteInk.Color.BLACK, RattaInkMap.firmwareColorFor(argb(0, 0, 0)))
        assertEquals(SupernoteInk.Color.BLACK, RattaInkMap.firmwareColorFor(argb(64, 64, 64)))
        // Saturated primaries with low luma stay black-ink territory.
        assertEquals(SupernoteInk.Color.BLACK, RattaInkMap.firmwareColorFor(argb(0, 0, 255)))
        assertEquals(SupernoteInk.Color.BLACK, RattaInkMap.firmwareColorFor(argb(255, 0, 0)))
    }

    @Test
    fun `mid greys map to DARK_GRAY`() {
        // Luma 128 grey sits between the BLACK ceiling (85) and DARK_GRAY ceiling (187).
        assertEquals(SupernoteInk.Color.DARK_GRAY, RattaInkMap.firmwareColorFor(argb(128, 128, 128)))
        // Pure green: luma ≈ 150.
        assertEquals(SupernoteInk.Color.DARK_GRAY, RattaInkMap.firmwareColorFor(argb(0, 255, 0)))
    }

    @Test
    fun `light greys map to GRAY`() {
        assertEquals(SupernoteInk.Color.GRAY, RattaInkMap.firmwareColorFor(argb(200, 200, 200)))
        // Yellow: luma ≈ 226 — just past the GRAY ceiling (222).
        assertEquals(SupernoteInk.Color.LIGHT_GRAY, RattaInkMap.firmwareColorFor(argb(255, 255, 0)))
    }

    @Test
    fun `near-white maps to LIGHT_GRAY`() {
        assertEquals(SupernoteInk.Color.LIGHT_GRAY, RattaInkMap.firmwareColorFor(argb(255, 255, 255)))
        assertEquals(SupernoteInk.Color.LIGHT_GRAY, RattaInkMap.firmwareColorFor(argb(240, 240, 240)))
    }

    @Test
    fun `threshold boundaries are inclusive ceilings`() {
        // Luma of an (n,n,n) grey is exactly n.
        assertEquals(SupernoteInk.Color.BLACK, RattaInkMap.firmwareColorFor(argb(85, 85, 85)))
        assertEquals(SupernoteInk.Color.DARK_GRAY, RattaInkMap.firmwareColorFor(argb(86, 86, 86)))
        assertEquals(SupernoteInk.Color.DARK_GRAY, RattaInkMap.firmwareColorFor(argb(187, 187, 187)))
        assertEquals(SupernoteInk.Color.GRAY, RattaInkMap.firmwareColorFor(argb(188, 188, 188)))
        assertEquals(SupernoteInk.Color.GRAY, RattaInkMap.firmwareColorFor(argb(222, 222, 222)))
        assertEquals(SupernoteInk.Color.LIGHT_GRAY, RattaInkMap.firmwareColorFor(argb(223, 223, 223)))
    }

    @Test
    fun `alpha is ignored`() {
        assertEquals(
            RattaInkMap.firmwareColorFor(argb(128, 128, 128)),
            RattaInkMap.firmwareColorFor((0x40 shl 24) or (128 shl 16) or (128 shl 8) or 128),
        )
    }

    @Test
    fun `luma is Rec 601`() {
        assertEquals(76.245f, RattaInkMap.luma(argb(255, 0, 0)), 0.01f)
        assertEquals(149.685f, RattaInkMap.luma(argb(0, 255, 0)), 0.01f)
        assertEquals(29.07f, RattaInkMap.luma(argb(0, 0, 255)), 0.01f)
    }
}
