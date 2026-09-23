package com.symmetricalpalmtree.gpaper.ratta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The direct stroke path's "is the committed image current" state (Phase 42). */
class CommittedCacheTest {

    @Test
    fun `a redraw with nothing laid rebuilds`() {
        assertFalse(CommittedCache().take())
    }

    @Test
    fun `a laid change lets the next redraw mirror, once`() {
        val c = CommittedCache()
        c.markCurrent()
        assertTrue(c.take())
        assertFalse(c.take())
    }

    @Test
    fun `several laid changes before one redraw still mirror`() {
        val c = CommittedCache()
        c.markCurrent()
        c.markCurrent()
        assertTrue(c.take())
    }

    @Test
    fun `an unlaid change beats a laid one in either order`() {
        val c = CommittedCache()
        c.markCurrent()
        c.invalidate()
        assertFalse(c.take())
        c.invalidate()
        c.markCurrent()
        assertFalse(c.take())
        // And the round after is clean.
        c.markCurrent()
        assertTrue(c.take())
    }
}
