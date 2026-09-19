package com.symmetricalpalmtree.gpaper.core

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The routing is the whole of [RasterLayer]'s behaviour, and it has to be **total**: a
 * style added to [StrokeStyle] one day must land on a raster by construction, not by
 * someone remembering to come back here. So the test walks `StrokeStyle.entries` rather
 * than a list of styles written out by hand — the same list the enum has, whatever it
 * grows into.
 *
 * The rule is the artist's, and it is one-sided on purpose: graphite is the pencil, and
 * **everything that is not a pencil is ink**, because ink is what the rubber must not
 * lift. A new textured style that ought to rub out would be a decision, and it would fail
 * here first.
 */
class RasterLayerTest {

    @Test
    fun `the pencil is graphite and every other style is ink`() {
        for (style in StrokeStyle.entries) {
            val expected =
                if (style == StrokeStyle.PENCIL) RasterLayer.GRAPHITE else RasterLayer.INK
            assertEquals("$style routes to the wrong raster", expected, RasterLayer.of(style))
        }
    }

    @Test
    fun `every style routes somewhere`() {
        // Not a tautology in the shape that matters: it pins that `of` is total over the
        // enum — no style falls through, throws, or needs a default the caller supplies.
        assertEquals(StrokeStyle.entries.size, StrokeStyle.entries.map { RasterLayer.of(it) }.size)
    }

    @Test
    fun `graphite is exactly one style`() {
        val graphite = StrokeStyle.entries.filter { RasterLayer.of(it) == RasterLayer.GRAPHITE }
        assertEquals(listOf(StrokeStyle.PENCIL), graphite)
    }
}
