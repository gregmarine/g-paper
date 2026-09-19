package com.symmetricalpalmtree.gpaper.ratta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page-open ordering: **one rebuild and one present, however many calls the host
 * makes** (2026-09-19 maintenance).
 *
 * A two-raster page arrives as two `loadPageRaster` calls, each announcing the whole page
 * and each presenting afterwards — which on a Nomad was two whole-page dither rebuilds of
 * ~500 ms with a frame between them showing graphite and no ink. The rules are small
 * enough to state and easy enough to get wrong in the one direction that costs a frame, so
 * they are stated here rather than on a device.
 */
class DitherCoalescerTest {

    @Test
    fun `back to back whole page changes schedule exactly one rebuild`() {
        val c = DitherCoalescer()
        assertEquals(DitherCoalescer.Action.SCHEDULE_WHOLE, c.onWholePage())
        assertEquals(DitherCoalescer.Action.NONE, c.onWholePage())
        assertEquals(DitherCoalescer.Action.NONE, c.onWholePage())
        assertTrue(c.takeScheduled())
        assertFalse(c.scheduled)
        // And the next page turn schedules again — the state is per pending rebuild, not
        // a latch.
        assertEquals(DitherCoalescer.Action.SCHEDULE_WHOLE, c.onWholePage())
    }

    @Test
    fun `a rect is immediate, unless a whole page is already pending`() {
        val c = DitherCoalescer()
        assertEquals(DitherCoalescer.Action.REBUILD_RECT, c.onRect())
        assertEquals(DitherCoalescer.Action.REBUILD_RECT, c.onRect())
        c.onWholePage()
        // Subsumed: the pending rebuild covers the whole page, this rect included.
        assertEquals(DitherCoalescer.Action.NONE, c.onRect())
        assertTrue(c.takeScheduled())
        assertEquals(DitherCoalescer.Action.REBUILD_RECT, c.onRect())
    }

    @Test
    fun `every redraw waits while a rebuild is pending, and only while`() {
        val c = DitherCoalescer()
        assertFalse(c.deferRedraw)
        c.onRect()
        assertFalse(c.deferRedraw)
        c.onWholePage()
        assertTrue(c.deferRedraw)
        c.takeScheduled()
        assertFalse(c.deferRedraw)
    }

    @Test
    fun `a page that goes cancels the pending rebuild`() {
        val c = DitherCoalescer()
        c.onWholePage()
        assertEquals(DitherCoalescer.Action.DROP, c.onPageGone())
        // Nothing left pending: a runnable that had already been posted must find nothing
        // to do, and no redraw may be held back for it.
        assertFalse(c.deferRedraw)
        assertFalse(c.takeScheduled())
    }

    @Test
    fun `a reset releases a held back redraw`() {
        // The panel closing mid-flight is the case that matters: the posted runnable is
        // removed, so nothing will ever call takeScheduled, and a view whose every redraw
        // was swallowed from then on would simply stop painting.
        val c = DitherCoalescer()
        c.onWholePage()
        assertTrue(c.deferRedraw)
        c.reset()
        assertFalse(c.deferRedraw)
        assertFalse(c.takeScheduled())
    }
}
