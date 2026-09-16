package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The patch of page a mark is about to change, in raster mode (0.1.25).
 *
 * A host keeping undo for a raster page needs a before-image of exactly the pixels a
 * mark will touch, and it needs it *before* the mark lands. The engine cannot hand it
 * the pixels — the host owns history — so it hands it this rectangle, in page space,
 * through [com.symmetricalpalmtree.gpaper.core.PaperListener.onRasterWillChange]. It
 * must therefore err outward: a rect that misses one fleck leaves one fleck that undo
 * cannot take back, and the artist sees a mark that will not quite go away.
 *
 * The rect is the mark's point bounds pushed out by its full width plus [MARGIN_PX].
 * The full width rather than half of it because the styles do not all stay inside
 * their nominal width — the graphite fleck sits jittered off its lattice site and is
 * drawn as a round dot of up to the lead's width, and the fountain nib swells with
 * pressure — and the margin covers antialiasing and the rounding to whole pixels. The
 * cost of generosity is a few extra pixels copied; the cost of meanness is a wrong
 * undo. Then it is snapped outward to integers and clipped to the page, and a mark
 * wholly off the page yields `null`: nothing will change, so nothing is announced.
 *
 * **One box is generous in the wrong direction as well (0.1.33).** Padding outward is
 * right; spanning a stroke's corners is not — a hairline drawn corner to corner has a
 * bounding box the size of the page, and a host reading a before-image from it copies
 * the whole page to cover a few thousand pixels of ink. So a mark is announced through
 * [along] as a run of rects that follows the polyline, and the single-box form stays
 * for the changes that really are page-shaped (a load, a clear) and for each run.
 *
 * Pure Kotlin so the rule is proved by a JVM test rather than read off a screenshot.
 */
object RasterDirty {

    /** Slack beyond the width on every side, in px: antialiasing and the rounding out. */
    const val MARGIN_PX: Float = 2f

    /**
     * The integer, page-clipped rect a mark of [width] drawn through [bounds] may
     * touch on a page of [pageWidth] × [pageHeight], or `null` when the mark cannot
     * touch the page at all. Edges are whole numbers stored as floats so callers can
     * bridge to an `android.graphics.Rect` without a second rounding.
     */
    fun of(bounds: Bounds, width: Float, pageWidth: Int, pageHeight: Int): Bounds? {
        if (pageWidth <= 0 || pageHeight <= 0) return null
        val pad = (if (width > 0f) width else 0f) + MARGIN_PX
        val left = floor(bounds.left - pad).coerceAtLeast(0f)
        val top = floor(bounds.top - pad).coerceAtLeast(0f)
        val right = ceil(bounds.right + pad).coerceAtMost(pageWidth.toFloat())
        val bottom = ceil(bounds.bottom + pad).coerceAtMost(pageHeight.toFloat())
        if (left >= right || top >= bottom) return null
        return Bounds(left, top, right, bottom)
    }

    /**
     * The mark of [width] drawn along [points] as a **run of rects** rather than one
     * box (0.1.33) — the same rule as [of], applied to pieces of the polyline instead
     * of the whole of it.
     *
     * [of] is generous on purpose, and on a bounding box generosity has a second cost
     * nobody asked for: a hairline drawn corner to corner spans the whole page, so its
     * one rect *is* the whole page, and a host reading a before-image from it copies
     * every pixel of the page to cover a mark that touched a few thousand. Two such
     * strokes fill an undo budget measured in bytes. The diagonal is only the clearest
     * case — any long stroke that is not axis-aligned pays for the area between itself
     * and its corners.
     *
     * So the polyline is walked in order and cut into runs, each run's rect being
     * exactly what [of] makes of that run's points: same pad ([MARGIN_PX] beyond the
     * full width), same outward snap, same page clip. A run is closed when taking the
     * next point in would push the run's **unpadded** span — the larger of its width
     * and its height — past [maxSpanPx], which bounds each rect at roughly
     * `maxSpanPx + 2 × pad` on a side and makes the announced area follow the ink
     * rather than the corners.
     *
     * **The closing point belongs to both runs.** It is the last point of the run being
     * closed and the first point of the next, so the segment that crosses the boundary
     * lies wholly inside the next run's rect and the two rects overlap by at least the
     * pad around that point. Cutting between two points instead would leave the segment
     * between them announced by neither rect, and a gap in the announcement is the one
     * failure this whole object exists to avoid: the pixels a host did not copy are the
     * pixels its undo cannot put back, and the artist sees a mark that will not quite go
     * away. **Coverage is the invariant** — every pixel a mark can touch lies in at
     * least one returned rect — and every other property here (the span, the cap, the
     * dropping) is a saving taken only where coverage is not at stake.
     *
     * A single segment longer than [maxSpanPx] is still one run: a run of two points
     * cannot be cut further, and one rect too large beats no rect at all. For the same
     * reason the count is capped at [maxRects] and the **last run absorbs everything
     * that remains** when the cap is reached — a pathological polyline degrades to the
     * old behaviour (a bigger rect) rather than to a dropped piece of a mark.
     *
     * Runs whose rect clips to nothing are dropped, so a mark wholly off the page
     * announces nothing at all, exactly as [of] returns `null` for one. An empty list
     * of points yields an empty list of rects, and a stroke short enough never to reach
     * the span yields exactly one rect, identical to `of(Bounds.of(points), …)` — a
     * short mark is announced now as it always was.
     *
     * @param maxSpanPx the unpadded span at which a run is cut; the caller measures it
     *   (a host's before-image grid has a cell size, and there is no gain in rects far
     *   below it).
     * @param maxRects the most rects one mark may be announced as.
     */
    fun along(
        points: List<StrokePoint>,
        width: Float,
        pageWidth: Int,
        pageHeight: Int,
        maxSpanPx: Int = 256,
        maxRects: Int = 64,
    ): List<Bounds> {
        if (points.isEmpty() || pageWidth <= 0 || pageHeight <= 0) return emptyList()
        val span = maxSpanPx.coerceAtLeast(1).toFloat()
        val cap = maxRects.coerceAtLeast(1)
        val out = ArrayList<Bounds>()

        var start = 0
        var minX = points[0].x
        var maxX = minX
        var minY = points[0].y
        var maxY = minY

        for (i in 1 until points.size) {
            val p = points[i]
            val nextMinX = minOf(minX, p.x)
            val nextMaxX = maxOf(maxX, p.x)
            val nextMinY = minOf(minY, p.y)
            val nextMaxY = maxOf(maxY, p.y)
            val nextSpan = maxOf(nextMaxX - nextMinX, nextMaxY - nextMinY)
            // A run of one point cannot be cut (its single segment must go somewhere),
            // and the last run this side of the cap takes the whole tail.
            val canClose = (i - 1) > start && out.size < cap - 1
            if (nextSpan > span && canClose) {
                of(Bounds(minX, minY, maxX, maxY), width, pageWidth, pageHeight)?.let(out::add)
                // The point that closed the run opens the next one: the segment from it
                // to [p] is then covered, and the two rects overlap around it.
                start = i - 1
                val shared = points[start]
                minX = minOf(shared.x, p.x)
                maxX = maxOf(shared.x, p.x)
                minY = minOf(shared.y, p.y)
                maxY = maxOf(shared.y, p.y)
            } else {
                minX = nextMinX
                maxX = nextMaxX
                minY = nextMinY
                maxY = nextMaxY
            }
        }
        of(Bounds(minX, minY, maxX, maxY), width, pageWidth, pageHeight)?.let(out::add)
        return out
    }

    /** The whole page, for a change that replaces it (a load, a clear). */
    fun wholePage(pageWidth: Int, pageHeight: Int): Bounds? =
        if (pageWidth <= 0 || pageHeight <= 0) null
        else Bounds(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
}
