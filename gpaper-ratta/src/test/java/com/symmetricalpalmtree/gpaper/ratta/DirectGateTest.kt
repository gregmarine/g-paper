package com.symmetricalpalmtree.gpaper.ratta

import com.symmetricalpalmtree.gpaper.core.PageMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which pages the Supernote engine paints itself (Phase 42). The stroke gate is an
 * opt-in on top of the raster gate's two preconditions, and the two never both hold.
 */
class DirectGateTest {

    @Test
    fun `a raster page is direct whenever the panel is ours, opt-in or not`() {
        assertTrue(DirectGate.raster(firmware = true, panelOpen = true, mode = PageMode.RASTER))
        assertTrue(DirectGate.any(true, true, PageMode.RASTER, directInk = false))
        assertTrue(DirectGate.any(true, true, PageMode.RASTER, directInk = true))
    }

    @Test
    fun `a stroke page is direct only when the host opted in`() {
        assertFalse(DirectGate.stroke(true, true, PageMode.STROKE, directInk = false))
        assertTrue(DirectGate.stroke(true, true, PageMode.STROKE, directInk = true))
        assertFalse(DirectGate.any(true, true, PageMode.STROKE, directInk = false))
    }

    @Test
    fun `neither gate opens without the firmware or without the panel`() {
        for (mode in PageMode.entries) {
            assertFalse(DirectGate.any(firmware = false, panelOpen = true, mode = mode, directInk = true))
            assertFalse(DirectGate.any(firmware = true, panelOpen = false, mode = mode, directInk = true))
        }
    }

    @Test
    fun `the two gates are exclusive`() {
        for (mode in PageMode.entries) for (opt in listOf(false, true)) {
            assertFalse(
                DirectGate.raster(true, true, mode) && DirectGate.stroke(true, true, mode, opt),
            )
        }
    }
}
