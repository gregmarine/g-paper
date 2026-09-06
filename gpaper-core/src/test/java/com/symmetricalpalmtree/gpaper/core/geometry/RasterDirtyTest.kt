package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rect handed to a raster host before a mark lands must cover every pixel the mark
 * can touch — an undo taken from it that misses a fleck leaves a mark that will not go
 * away — and must stay on the page, because it is used to copy pixels out of a bitmap.
 */
class RasterDirtyTest {

    private val page = 100 to 200

    @Test
    fun `the rect covers the bounds pushed out by the width and the margin`() {
        val b = Bounds(10f, 20f, 30f, 40f)
        val r = RasterDirty.of(b, 4f, page.first, page.second)!!
        // 4 + 2 of slack on every side.
        assertEquals(Bounds(4f, 14f, 36f, 46f), r)
    }

    @Test
    fun `the rect snaps outward to whole pixels`() {
        val b = Bounds(10.4f, 20.6f, 30.2f, 40.9f)
        val r = RasterDirty.of(b, 1.2f, page.first, page.second)!!
        // 10.4 - 3.2 = 7.2 → 7; 20.6 - 3.2 = 17.4 → 17; 30.2 + 3.2 = 33.4 → 34; 40.9 + 3.2 = 44.1 → 45
        assertEquals(Bounds(7f, 17f, 34f, 45f), r)
    }

    @Test
    fun `a hairline still gets the margin`() {
        val b = Bounds(50f, 50f, 50f, 50f)
        val r = RasterDirty.of(b, 1.2f, page.first, page.second)!!
        assertEquals(Bounds(46f, 46f, 54f, 54f), r)
    }

    @Test
    fun `the rect is clipped to the page`() {
        val b = Bounds(-5f, 190f, 3f, 260f)
        val r = RasterDirty.of(b, 3f, page.first, page.second)!!
        assertEquals(Bounds(0f, 185f, 8f, 200f), r)
    }

    @Test
    fun `a mark wholly off the page announces nothing`() {
        assertNull(RasterDirty.of(Bounds(150f, 10f, 160f, 20f), 3f, page.first, page.second))
        assertNull(RasterDirty.of(Bounds(10f, -50f, 20f, -40f), 3f, page.first, page.second))
    }

    @Test
    fun `a mark whose slack just reaches the page edge counts`() {
        // Right edge at 100 + 5 slack; the mark itself is off-page but its ink is not.
        val r = RasterDirty.of(Bounds(103f, 10f, 110f, 20f), 3f, page.first, page.second)!!
        assertEquals(Bounds(98f, 5f, 100f, 25f), r)
    }

    @Test
    fun `no page means no rect`() {
        assertNull(RasterDirty.of(Bounds(10f, 10f, 20f, 20f), 3f, 0, 200))
        assertNull(RasterDirty.wholePage(100, 0))
    }

    @Test
    fun `the whole page is the page`() {
        assertEquals(Bounds(0f, 0f, 100f, 200f), RasterDirty.wholePage(100, 200))
    }

    @Test
    fun `a stroke's own bounds feed it`() {
        val pts = listOf(StrokePoint(10f, 10f), StrokePoint(20f, 30f))
        val r = RasterDirty.of(Bounds.of(pts), 2f, page.first, page.second)!!
        assertEquals(Bounds(6f, 6f, 24f, 34f), r)
    }
}
