package com.symmetricalpalmtree.gpaper.core

import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A 0.1.38 raster host, kept compiling (0.1.39).
 *
 * Two rasters split every raster call and both listener callbacks in two, and the
 * promise made in exchange was that **the un-layered forms keep meaning graphite** — so a
 * host written when the page was one image (Paintsprout's Onyx sketchbook, which draws a
 * pencil and nothing else) builds against a re-pin without a line changed. A promise of
 * *source* compatibility cannot be checked by an assertion, because the failure is a
 * build that does not happen; what checks it is a file that would stop compiling. This is
 * that file, and it earns its place the day someone makes the layered forms the only ones.
 *
 * Nothing here runs against Android: [legacyHostCalls] is never called, and compiling it
 * is the whole of its job (the unit-test classpath is the `android.jar` stub, whose
 * methods throw). The assertion below only keeps JUnit's hands on the class.
 */
class LegacyRasterHostTest {

    /** The listener as 0.1.38 hosts write one: the un-layered halves, and nothing else. */
    private class LegacyListener : PaperListener {
        var willChanges = 0
        var changes = 0

        override fun onRasterWillChange(rect: Rect) {
            willChanges++
        }

        override fun onRasterChanged(rect: Rect) {
            changes++
        }
    }

    /**
     * Every raster call a 0.1.38 host makes, in its 0.1.38 shape. **Never invoked** — the
     * compiler is the test. If one of these stops resolving, a released host stops
     * building, and that is a decision somebody has to take deliberately.
     */
    @Suppress("unused")
    private fun legacyHostCalls(
        paper: PaperView,
        rect: Rect,
        patches: List<RasterPatch>,
        bitmap: Bitmap?,
    ): List<Any?> {
        paper.loadPageRaster(bitmap)
        paper.swapPageRaster(patches)
        // Typed on the way out as well, so a return type that changed would fail here too.
        val page: Bitmap? = paper.getPageRaster()
        val region: Bitmap? = paper.copyPageRaster(rect)
        val read: RasterPatch? = paper.readPageRaster(rect)
        return listOf(page, region, read)
    }

    @Test
    fun `a 0_1_38 listener is still a PaperListener and hears nothing until told`() {
        val listener = LegacyListener()
        assertEquals(0, listener.willChanges)
        assertEquals(0, listener.changes)
    }
}
