package com.symmetricalpalmtree.gpaper.ratta

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live-ink EMR mapping is pure Kotlin so its two floors and its ceiling are pinned
 * on the JVM. The floors are the whole point: `PENCIL` is the raster page's 1.2 px
 * hairline and the general floor of 200 would preview it as a 2 px needle, so the mark
 * would visibly narrow at pen-up — a preview that lies about width, which is the failure
 * the artist reads as the bake being broken.
 *
 * The ceiling is pinned for the opposite reason: since 0.1.37 it is 9600 (96 px), the
 * widest lead the artist's hand has walked, and the sizes between are pinned one by one so
 * that a lead the walk approved can never be silently clamped back to a hairline world.
 */
class RattaEmrTest {

    @Test
    fun `a hairline pencil takes the pencil floor`() {
        // 1.2 px * 100 = 120, which is the hairline floor itself — and would have been
        // raised to 200 before 0.1.32.
        assertEquals(120, RattaEmr.penSize(StrokeStyle.PENCIL, 1.2f))
        // Thinner still cannot go below it.
        assertEquals(120, RattaEmr.penSize(StrokeStyle.PENCIL, 0.4f))
    }

    @Test
    fun `every other style keeps the general floor`() {
        assertEquals(200, RattaEmr.penSize(StrokeStyle.PEN, 1.2f))
        assertEquals(200, RattaEmr.penSize(StrokeStyle.FOUNTAIN, 0.4f))
        // The pencil floor is a pencil floor: a 1.2 px marker is not a hairline, so it
        // is raised to 200 like every other style.
        assertEquals(200, RattaEmr.penSize(StrokeStyle.MARKER, 1.2f))
    }

    @Test
    fun `a wide pen stops at the ceiling`() {
        // 12 px was the whole world until arc 44, and it is nowhere near the ceiling now.
        assertEquals(1200, RattaEmr.penSize(StrokeStyle.PEN, 12f))
        assertEquals(4000, RattaEmr.penSize(StrokeStyle.PEN, 40f))
        assertEquals(4000, RattaEmr.penSize(StrokeStyle.PENCIL, 40f))
        // Past 96 px it clamps, for want of a walk rather than for want of a panel.
        assertEquals(9600, RattaEmr.penSize(StrokeStyle.PEN, 200f))
        assertEquals(9600, RattaEmr.penSize(StrokeStyle.PENCIL, 200f))
    }

    @Test
    fun `every lead the hand walked arms the width it asked for`() {
        // The artist's Nomad walk, 2026-09-17: each of these previewed at the width it
        // baked, so each must reach the firmware untouched by the clamp. 96 px is the
        // widest walked and is the ceiling exactly — one more px would be a guess again.
        assertEquals(1600, RattaEmr.penSize(StrokeStyle.PENCIL, 16f))
        assertEquals(2000, RattaEmr.penSize(StrokeStyle.PENCIL, 20f))
        assertEquals(2400, RattaEmr.penSize(StrokeStyle.PENCIL, 24f))
        assertEquals(3200, RattaEmr.penSize(StrokeStyle.PENCIL, 32f))
        assertEquals(4800, RattaEmr.penSize(StrokeStyle.PENCIL, 48f))
        assertEquals(6400, RattaEmr.penSize(StrokeStyle.PENCIL, 64f))
        assertEquals(9600, RattaEmr.penSize(StrokeStyle.PENCIL, 96f))
    }

    @Test
    fun `between the floor and the ceiling it is width times a hundred`() {
        assertEquals(500, RattaEmr.penSize(StrokeStyle.PEN, 5f))
        assertEquals(300, RattaEmr.penSize(StrokeStyle.PENCIL, 3f))
        assertEquals(250, RattaEmr.penSize(StrokeStyle.CALLIGRAPHY, 2.5f))
    }

    @Test
    fun `the two floors are the two measured numbers`() {
        // Frozen at 0.1.34 when the measurement door closed: the hairline floor is the
        // Nomad's answer and the general floor is the one the Needle array needs, and
        // the pencil is the only style that may go below the latter. Neither moved at
        // 0.1.37 — the ceiling did, and only the ceiling.
        assertEquals(120, RattaEmr.EMR_MIN_HAIRLINE)
        assertEquals(200, RattaEmr.EMR_MIN)
        assertEquals(9600, RattaEmr.EMR_MAX)
        assertEquals(RattaEmr.EMR_MIN_HAIRLINE, RattaEmr.penSize(StrokeStyle.PENCIL, 0f))
        assertEquals(RattaEmr.EMR_MIN, RattaEmr.penSize(StrokeStyle.PEN, 0f))
    }
}
