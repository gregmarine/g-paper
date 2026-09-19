package com.symmetricalpalmtree.gpaper.ratta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compositor's grey → level table, pinned from both sides of every boundary.
 *
 * It is a measurement, not a rule — read back out of the driver's frames on a Nomad and
 * found byte-identical on a Manta — so the only thing a test can do for it is make sure
 * nobody tidies it into a formula. Every band is irregular, and one level is missing
 * entirely; a linear map would agree with this table at both ends and be wrong in between,
 * and being wrong in between means every live mark shifts tone the moment the compositor
 * rewrites the screen.
 */
class RattaPanelToneTest {

    /** Every band as the probe measured it: `low..high` → level. */
    private val bands = listOf(
        Triple(0, 75, 0),
        Triple(76, 87, 1),
        Triple(88, 99, 2),
        Triple(100, 107, 3),
        Triple(108, 119, 4),
        Triple(120, 131, 5),
        Triple(132, 139, 6),
        Triple(140, 151, 7),
        Triple(152, 167, 8),
        Triple(168, 187, 10),
        Triple(188, 195, 11),
        Triple(196, 203, 12),
        Triple(204, 215, 13),
        Triple(216, 223, 14),
        Triple(224, 255, 15),
    )

    @Test
    fun `every band maps as measured, from both of its edges`() {
        for ((low, high, level) in bands) {
            assertEquals("grey $low (band $low..$high)", level, RattaPanelTone.level(low))
            assertEquals("grey $high (band $low..$high)", level, RattaPanelTone.level(high))
        }
    }

    @Test
    fun `every grey from 0 to 255 lands in its measured band`() {
        for (g in 0..255) {
            val band = bands.first { g >= it.first && g <= it.second }
            assertEquals("grey $g", band.third, RattaPanelTone.level(g))
        }
    }

    @Test
    fun `level 9 is never produced`() {
        // The table's own oddity, and the reason it cannot be a formula: 168..187 maps to 10.
        assertFalse((0..255).any { RattaPanelTone.level(it) == 9 })
        assertEquals(10, RattaPanelTone.level(168))
        assertEquals(10, RattaPanelTone.level(187))
    }

    @Test
    fun `the table never goes backwards`() {
        var last = -1
        for (g in 0..255) {
            val level = RattaPanelTone.level(g)
            assertTrue("grey $g went darker than $last", level >= last)
            last = level
        }
    }

    @Test
    fun `greys outside the byte range are clamped rather than thrown`() {
        assertEquals(0, RattaPanelTone.level(-40))
        assertEquals(15, RattaPanelTone.level(999))
    }

    @Test
    fun `a flattened pixel is read at the tone of its luminance`() {
        assertEquals(0, RattaPanelTone.levelOf(0xFF000000.toInt()))
        assertEquals(15, RattaPanelTone.levelOf(0xFFFFFFFF.toInt()))
        // A plain grey must come back as its own band — the rounding case: 188 is the first
        // grey of band 11, and float luma of it is a hair under 188.
        assertEquals(11, RattaPanelTone.levelOf(0xFFBCBCBC.toInt()))
        assertEquals(10, RattaPanelTone.levelOf(0xFFBBBBBB.toInt()))
        // Colour is read at its luminance, because that is what the panel renders it as.
        val lumaOfColour = RattaInkMap.luma(0xFF4080C0.toInt()).toInt()
        assertEquals(RattaPanelTone.level(lumaOfColour), RattaPanelTone.levelOf(0xFF4080C0.toInt()))
    }

    @Test
    fun `the panel takes sixteen levels`() {
        assertEquals(16, RattaPanelTone.LEVELS)
        assertTrue((0..255).all { RattaPanelTone.level(it) in 0 until RattaPanelTone.LEVELS })
    }
}
