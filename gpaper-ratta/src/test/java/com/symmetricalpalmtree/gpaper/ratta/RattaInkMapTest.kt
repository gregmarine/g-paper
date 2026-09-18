package com.symmetricalpalmtree.gpaper.ratta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The live-ink grey mapping is pure Kotlin so its calibrated thresholds (85 / 187 / 222,
 * midpoints between the tones the four firmware codes actually render at) are pinned on
 * the JVM — a threshold shift would silently misalign live ink from the baked stroke.
 *
 * Since 0.1.36 there is a second ladder to pin, `pencilPreviewFor`, with thresholds of its
 * own (42.5 / 110.5) because a pencil bakes as flecks at a constant pressure and reads
 * lighter than a solid line of the same colour. Its rungs were settled by the artist's
 * hand on the Nomad (2026-09-17): 0–2 BLACK, 3–6 DARK_GRAY, 7–14 GRAY, and LIGHT_GRAY
 * trialled for the palest levels and rejected. The `#505050`/`#555555` → DARK_GRAY pairing
 * is Phase 19's and the ladder must keep it.
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

    // ── The PENCIL preview's own ladder (0.1.36) ─────────────────────────────
    //
    // Fifteen shade levels, level n = the grey `n × 0x11`, so an (n,n,n) grey's luma is
    // exactly `n × 17`. Every one of them is pinned to the code it arms, because the
    // whole point of the ladder is that adjacent shades preview differently where they
    // should and identically where the panel gives no choice — a boundary that slips a
    // level is exactly the kind of drift no other assertion here would catch.

    /** Shade level *n* of arc 44's fifteen: the grey `n × 0x11`. */
    private fun shade(level: Int): Int = argb(level * 0x11, level * 0x11, level * 0x11)

    @Test
    fun `the pencil ladder maps all fifteen shade levels`() {
        val expected = intArrayOf(
            // 0 (#000000) … 2 (#222222)
            SupernoteInk.Color.BLACK,
            SupernoteInk.Color.BLACK,
            SupernoteInk.Color.BLACK,
            // 3 (#333333) … 6 (#666666) — the default #555555 (level 5) is in here
            SupernoteInk.Color.DARK_GRAY,
            SupernoteInk.Color.DARK_GRAY,
            SupernoteInk.Color.DARK_GRAY,
            SupernoteInk.Color.DARK_GRAY,
            // 7 (#777777) … 14 (#EEEEEE)
            SupernoteInk.Color.GRAY,
            SupernoteInk.Color.GRAY,
            SupernoteInk.Color.GRAY,
            SupernoteInk.Color.GRAY,
            SupernoteInk.Color.GRAY,
            SupernoteInk.Color.GRAY,
            SupernoteInk.Color.GRAY,
            SupernoteInk.Color.GRAY,
        )
        for (level in 0..14) {
            assertEquals("shade level $level", expected[level], RattaInkMap.pencilPreviewFor(shade(level)))
        }
    }

    @Test
    fun `the pencil ladder's two boundaries fall between adjacent shades`() {
        // A boundary sits at the midpoint of the two levels it separates, so neither of
        // them is ever one rounding away from the other side.
        assertEquals(SupernoteInk.Color.BLACK, RattaInkMap.pencilPreviewFor(shade(2)))
        assertEquals(SupernoteInk.Color.DARK_GRAY, RattaInkMap.pencilPreviewFor(shade(3)))
        assertEquals(SupernoteInk.Color.DARK_GRAY, RattaInkMap.pencilPreviewFor(shade(6)))
        assertEquals(SupernoteInk.Color.GRAY, RattaInkMap.pencilPreviewFor(shade(7)))
        // And on the luma scale itself: 42.5 and 110.5 are inclusive ceilings.
        assertEquals(SupernoteInk.Color.BLACK, RattaInkMap.pencilPreviewFor(argb(42, 42, 42)))
        assertEquals(SupernoteInk.Color.DARK_GRAY, RattaInkMap.pencilPreviewFor(argb(43, 43, 43)))
        assertEquals(SupernoteInk.Color.DARK_GRAY, RattaInkMap.pencilPreviewFor(argb(110, 110, 110)))
        assertEquals(SupernoteInk.Color.GRAY, RattaInkMap.pencilPreviewFor(argb(111, 111, 111)))
    }

    @Test
    fun `the settled pairing is unchanged - both mid greys preview DARK_GRAY`() {
        // #555555 is arc 44's default lead (level 5); #505050 is arc 43's single lead,
        // the one the Nomad walk paired with the 0.5 bake and called "spot on". The
        // ladder must not move the one answer that was actually measured.
        assertEquals(SupernoteInk.Color.DARK_GRAY, RattaInkMap.pencilPreviewFor(argb(0x55, 0x55, 0x55)))
        assertEquals(SupernoteInk.Color.DARK_GRAY, RattaInkMap.pencilPreviewFor(argb(0x50, 0x50, 0x50)))
        // The solid-line ladder answers BLACK for both — which is what the pencil's own
        // ladder exists to escape, and proof the two are genuinely separate.
        assertEquals(SupernoteInk.Color.BLACK, RattaInkMap.firmwareColorFor(argb(0x50, 0x50, 0x50)))
    }

    @Test
    fun `the pencil ladder never answers LIGHT_GRAY`() {
        // LIGHT_GRAY renders near-invisibly (~#F0F0F0). A pale lead's bake may be faint,
        // but the line under the hand while it is being drawn may not be — so no input
        // reaches that code, not white, not a paler-than-white-is-possible overflow.
        assertEquals(SupernoteInk.Color.GRAY, RattaInkMap.pencilPreviewFor(argb(255, 255, 255)))
        assertEquals(SupernoteInk.Color.GRAY, RattaInkMap.pencilPreviewFor(argb(0xF0, 0xF0, 0xF0)))
        for (v in 0..255) {
            assertNotEquals(
                "grey $v",
                SupernoteInk.Color.LIGHT_GRAY,
                RattaInkMap.pencilPreviewFor(argb(v, v, v)),
            )
        }
    }

    @Test
    fun `the pencil ladder ignores alpha`() {
        assertEquals(
            RattaInkMap.pencilPreviewFor(shade(5)),
            RattaInkMap.pencilPreviewFor((0x40 shl 24) or (0x55 shl 16) or (0x55 shl 8) or 0x55),
        )
    }
}
