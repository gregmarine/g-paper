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
        assertEquals(1200, RattaEmr.penSize(StrokeStyle.PEN, 12f))
        assertEquals(1200, RattaEmr.penSize(StrokeStyle.PEN, 40f))
        assertEquals(1200, RattaEmr.penSize(StrokeStyle.PENCIL, 40f))
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
        // the pencil is the only style that may go below the latter.
        assertEquals(120, RattaEmr.EMR_MIN_HAIRLINE)
        assertEquals(200, RattaEmr.EMR_MIN)
        assertEquals(1200, RattaEmr.EMR_MAX)
        assertEquals(RattaEmr.EMR_MIN_HAIRLINE, RattaEmr.penSize(StrokeStyle.PENCIL, 0f))
        assertEquals(RattaEmr.EMR_MIN, RattaEmr.penSize(StrokeStyle.PEN, 0f))
    }
}
