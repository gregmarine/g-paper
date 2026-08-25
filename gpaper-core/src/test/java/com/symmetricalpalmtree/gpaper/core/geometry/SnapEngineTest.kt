package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 1000 × 2000 page with a 100 px margin and a 20 px threshold throughout, so every
 * expected guide is a round number: x = 0 / 100 / 500 / 900 / 1000, y = 0 / 100 / 1000 /
 * 1900 / 2000.
 */
class SnapEngineTest {

    private val pageW = 1000f
    private val pageH = 2000f
    private val margin = 100f
    private val threshold = 20f

    private fun snap(
        box: Bounds,
        dx: Float,
        dy: Float,
        targets: List<Bounds> = emptyList(),
        pageWidth: Float = pageW,
        pageHeight: Float = pageH,
        marginPx: Float = margin,
        thresholdPx: Float = threshold,
    ) = SnapEngine.computeSnap(
        box, dx, dy, pageWidth, pageHeight, marginPx, thresholdPx, targets,
    )

    private fun vertical(r: SnapResult): Float? =
        (r.guides.firstOrNull { it is SnapGuide.Vertical } as SnapGuide.Vertical?)?.x

    private fun horizontal(r: SnapResult): Float? =
        (r.guides.firstOrNull { it is SnapGuide.Horizontal } as SnapGuide.Horizontal?)?.y

    /** A 200 × 100 box parked far from every guide, so a test only sees what it asks for. */
    private fun box(left: Float = 300f, top: Float = 700f) =
        Bounds(left, top, left + 200f, top + 100f)

    // ── Page guides ──────────────────────────────────────────────────────────

    @Test
    fun `left edge catches the page's left edge`() {
        // left 300 + dx -290 = 10, which is 10 from x = 0.
        val r = snap(box(), dx = -290f, dy = 0f)
        assertEquals(-300f, r.dx, 0.001f)
        assertEquals(0f, vertical(r))
    }

    @Test
    fun `left edge catches the left margin`() {
        // left 300 + dx -190 = 110, which is 10 from x = 100.
        val r = snap(box(), dx = -190f, dy = 0f)
        assertEquals(-200f, r.dx, 0.001f)
        assertEquals(100f, vertical(r))
    }

    @Test
    fun `centre catches the page's vertical centre`() {
        // centreX 400 + dx 105 = 505, which is 5 from x = 500.
        val r = snap(box(), dx = 105f, dy = 0f)
        assertEquals(100f, r.dx, 0.001f)
        assertEquals(500f, vertical(r))
    }

    @Test
    fun `right edge catches the right margin and the right edge`() {
        // A 300-wide box: with the default 200 the centre sits exactly one margin behind
        // the right edge, so every right-edge catch ties with a centre catch.
        val wide = Bounds(300f, 700f, 600f, 800f)

        // right 600 + dx 305 = 905, 5 from x = 900.
        val atMargin = snap(wide, dx = 305f, dy = 0f)
        assertEquals(300f, atMargin.dx, 0.001f)
        assertEquals(900f, vertical(atMargin))

        // right 600 + dx 395 = 995, 5 from x = 1000.
        val atEdge = snap(wide, dx = 395f, dy = 0f)
        assertEquals(400f, atEdge.dx, 0.001f)
        assertEquals(1000f, vertical(atEdge))
    }

    @Test
    fun `top edge catches the top margin - the toolbar case`() {
        // top 700 + dy -590 = 110, 10 from y = 100: the object lands flush under the bar.
        val r = snap(box(), dx = 0f, dy = -590f)
        assertEquals(-600f, r.dy, 0.001f)
        assertEquals(100f, horizontal(r))
        assertEquals(100f, box().top + r.dy, 0.001f)
    }

    @Test
    fun `bottom edge catches the bottom margin`() {
        // bottom 800 + dy 1095 = 1895, 5 from y = 1900.
        val r = snap(box(), dx = 0f, dy = 1095f)
        assertEquals(1100f, r.dy, 0.001f)
        assertEquals(1900f, horizontal(r))
    }

    @Test
    fun `horizontal centre catches`() {
        // centreY 750 + dy 258 = 1008, 8 from y = 1000.
        val r = snap(box(), dx = 0f, dy = 258f)
        assertEquals(250f, r.dy, 0.001f)
        assertEquals(1000f, horizontal(r))
    }

    // ── Threshold ────────────────────────────────────────────────────────────

    @Test
    fun `nothing within the threshold passes the raw delta through untouched`() {
        val r = snap(box(), dx = 37f, dy = -13f)
        assertEquals(37f, r.dx, 0.001f)
        assertEquals(-13f, r.dy, 0.001f)
        assertTrue(r.guides.isEmpty())
    }

    @Test
    fun `dragging past the threshold releases the guide - nothing is clamped`() {
        // left lands 19 from the margin: caught. 21 away: free, and it keeps going.
        val caught = snap(box(), dx = -181f, dy = 0f)
        assertEquals(100f, vertical(caught))

        val released = snap(box(), dx = -179f, dy = 0f)
        assertNull(vertical(released))
        assertEquals(-179f, released.dx, 0.001f)
    }

    @Test
    fun `exactly at the threshold does not catch`() {
        // left 300 + dx -180 = 120, exactly 20 from x = 100.
        val r = snap(box(), dx = -180f, dy = 0f)
        assertNull(vertical(r))
    }

    @Test
    fun `a non-positive threshold disables snapping entirely`() {
        val r = snap(box(), dx = -299f, dy = 0f, thresholdPx = 0f)
        assertEquals(-299f, r.dx, 0.001f)
        assertTrue(r.guides.isEmpty())
    }

    // ── Nearest wins, axes are independent ───────────────────────────────────

    @Test
    fun `the nearest guide wins when two are in range`() {
        // A target edge at 105 and the page margin at 100, with left at 104: 105 is nearer.
        val target = Bounds(105f, 1500f, 305f, 1600f)
        val r = snap(box(), dx = -196f, dy = 0f, targets = listOf(target))
        assertEquals(105f, vertical(r))
        assertEquals(-195f, r.dx, 0.001f)
    }

    @Test
    fun `x and y snap independently`() {
        // X catches the page centre; Y is nowhere near anything.
        val r = snap(box(), dx = 105f, dy = 43f)
        assertEquals(500f, vertical(r))
        assertNull(horizontal(r))
        assertEquals(100f, r.dx, 0.001f)
        assertEquals(43f, r.dy, 0.001f)
    }

    @Test
    fun `both axes can catch at once`() {
        val r = snap(box(), dx = -190f, dy = -590f)
        assertEquals(100f, vertical(r))
        assertEquals(100f, horizontal(r))
        assertEquals(2, r.guides.size)
    }

    // ── Object guides ────────────────────────────────────────────────────────

    @Test
    fun `left edges align to another object's left edge`() {
        val target = Bounds(600f, 1500f, 800f, 1600f)
        // left 300 + dx 295 = 595, 5 from the target's left.
        val r = snap(box(), dx = 295f, dy = 0f, targets = listOf(target))
        assertEquals(600f, vertical(r))
        assertEquals(300f, r.dx, 0.001f)
    }

    @Test
    fun `centres align to another object's centre`() {
        // Wider than the dragged box, so a centre catch cannot tie with an edge catch.
        val target = Bounds(600f, 1500f, 900f, 1600f)   // centreX 750
        // centreX 400 + dx 348 = 748, 2 from 750.
        val r = snap(box(), dx = 348f, dy = 0f, targets = listOf(target))
        assertEquals(750f, vertical(r))
        assertEquals(350f, r.dx, 0.001f)
    }

    @Test
    fun `stacking below a neighbour catches one margin from its bottom edge`() {
        val target = Bounds(300f, 400f, 500f, 500f)     // bottom 500
        // Proximity guide at 500 + 100 = 600. top 700 + dy -105 = 595, 5 away.
        val r = snap(box(), dx = 0f, dy = -105f, targets = listOf(target))
        assertEquals(600f, horizontal(r))
        assertEquals(-100f, r.dy, 0.001f)
        // The gap between the two objects is now exactly one margin.
        assertEquals(margin, (box().top + r.dy) - target.bottom, 0.001f)
    }

    @Test
    fun `the proximity guide above a neighbour catches the dragged bottom edge`() {
        val target = Bounds(300f, 1350f, 500f, 1450f)   // top 1350, proximity 1250
        // bottom 800 + dy 445 = 1245, 5 from 1250.
        val r = snap(box(), dx = 0f, dy = 445f, targets = listOf(target))
        assertEquals(1250f, horizontal(r))
        assertEquals(450f, r.dy, 0.001f)
        assertEquals(margin, target.top - (box().bottom + r.dy), 0.001f)
    }

    @Test
    fun `several targets all contribute guides`() {
        val a = Bounds(600f, 300f, 800f, 400f)
        val b = Bounds(150f, 1500f, 250f, 1600f)
        // Catches b's right edge (250) with the dragged left edge: 300 + dx -55 = 245.
        val r = snap(box(), dx = -55f, dy = 0f, targets = listOf(a, b))
        assertEquals(250f, vertical(r))
    }

    @Test
    fun `with no targets only the page guides exist`() {
        // 600 would be a target proximity guide, but nothing is on the page.
        val r = snap(box(), dx = 295f, dy = 0f)
        assertNull(vertical(r))
        assertEquals(295f, r.dx, 0.001f)
    }

    // ── Degenerate inputs ────────────────────────────────────────────────────

    @Test
    fun `a non-positive page size drops that axis's page guides but keeps object ones`() {
        // Parked far right, so none of its guides can stand in for a page guide.
        val target = Bounds(1500f, 1500f, 1560f, 1600f)

        // centreX 400 + 105 = 505 would have caught the page centre; without a page it is
        // 995 from the nearest guide there is.
        val r = snap(box(), dx = 105f, dy = 0f, targets = listOf(target), pageWidth = 0f)
        assertNull(vertical(r))
        assertEquals(105f, r.dx, 0.001f)

        // Object guides survive: left 300 + 1205 = 1505, 5 from the target's left edge.
        val onObject = snap(box(), dx = 1205f, dy = 0f, targets = listOf(target), pageWidth = 0f)
        assertEquals(1500f, vertical(onObject))
        assertEquals(1200f, onObject.dx, 0.001f)
    }

    @Test
    fun `a zero margin collapses the margin guides onto the edges`() {
        // With margin 0 the only left-side guide is x = 0, so a drag to 110 catches nothing.
        val r = snap(box(), dx = -190f, dy = 0f, marginPx = 0f)
        assertNull(vertical(r))

        val atEdge = snap(box(), dx = -290f, dy = 0f, marginPx = 0f)
        assertEquals(0f, vertical(atEdge))
    }

    @Test
    fun `a zero-size selection still snaps on its single point`() {
        val point = Bounds(300f, 700f, 300f, 700f)
        val r = snap(point, dx = -195f, dy = 0f)
        assertEquals(100f, vertical(r))
        assertEquals(-200f, r.dx, 0.001f)
    }

    @Test
    fun `page guides win ties over object guides`() {
        // A target edge exactly on the page margin: both are 10 away, the page wins.
        val target = Bounds(100f, 1500f, 300f, 1600f)
        val r = snap(box(), dx = -190f, dy = 0f, targets = listOf(target))
        assertEquals(100f, vertical(r))
        assertEquals(1, r.guides.size)
    }
}
