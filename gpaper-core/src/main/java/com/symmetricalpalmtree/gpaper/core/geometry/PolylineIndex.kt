package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.floor

/**
 * A **sweep polyline indexed for distance queries** (Phase 44) — the eraser narrow phase's
 * answer to a long gesture. [Geometry.polylineWithinDistance] walks every segment pair of the
 * two polylines, which is right for an eraser sweep of a handful of samples but quadratic for a
 * scribble: a long squiggle over a page of writing (thousands of samples against hundreds of
 * strokes of hundreds of points each) ran the whole page's pairs on the main thread at pen-up
 * — a 15-second ANR on the Supernote Nomad, walked 2026-09-22 in Notesprout SN.
 *
 * Built **once per sweep** and asked once per candidate stroke. The sweep's segments go into a
 * uniform grid of [cell]-sized cells by their bounds inflated by [distance]; a query walks the
 * other polyline's segments, visits only the cells each one's bounds cover, and computes the
 * exact [Geometry.distanceSegmentToSegment] for the sweep segments found there — so a stroke
 * segment far from every part of the sweep costs a cell lookup and nothing else. **Exactly the
 * same answer** as the pairwise test, for every input: the grid decides what to *skip*, never
 * what to hit, and a pair it keeps is measured the pairwise way ([PolylineIndexTest] holds the
 * two to equality over random inputs).
 *
 * Single-point polylines act as degenerate segments and an empty one never matches — the
 * pairwise function's rules, kept.
 */
class PolylineIndex(
    private val points: List<StrokePoint>,
    private val distance: Float,
) {

    private val segments: Int = if (points.isEmpty()) 0 else maxOf(1, points.size - 1)

    /** Cell edge in px: never finer than the query's own tolerance, never so fine that a page
     *  wide sweep makes more cells than segments. */
    private val cell: Float
    private val originX: Float
    private val originY: Float
    private val cols: Int
    private val rows: Int

    /** CSR buckets: segment indices of cell *c* are `items[start[c] until start[c + 1]]`. */
    private val start: IntArray
    private val items: IntArray

    /** Per-segment stamp of the last query segment that measured it — a sweep segment sitting
     *  in several cells is measured once per query segment, not once per cell. */
    private val seen: IntArray

    init {
        if (segments == 0) {
            cell = 1f; originX = 0f; originY = 0f; cols = 0; rows = 0
            start = IntArray(1); items = IntArray(0); seen = IntArray(0)
        } else {
            val bounds = Bounds.of(points).inflated(distance)
            // Coarser cells for a sweep that is short relative to the tolerance keep the grid
            // small; a cap on the cell count keeps a page-wide sweep's grid bounded.
            var edge = maxOf(distance * 2f, MIN_CELL_PX)
            while ((bounds.width / edge + 1) * (bounds.height / edge + 1) > MAX_CELLS) edge *= 2f
            cell = edge
            originX = bounds.left
            originY = bounds.top
            cols = floor(bounds.width / cell).toInt() + 1
            rows = floor(bounds.height / cell).toInt() + 1
            // Two passes: count per cell, then fill — flat arrays, no boxing.
            val counts = IntArray(cols * rows)
            forEachCellOfSegment { c, _ -> counts[c]++ }
            start = IntArray(cols * rows + 1)
            for (c in 0 until cols * rows) start[c + 1] = start[c] + counts[c]
            items = IntArray(start[cols * rows])
            val fill = start.copyOf(cols * rows)
            forEachCellOfSegment { c, j -> items[fill[c]++] = j }
            seen = IntArray(segments) { -1 }
        }
    }

    /** Visit (cell index, segment index) for every cell a sweep segment's inflated bounds cover. */
    private inline fun forEachCellOfSegment(visit: (cell: Int, segment: Int) -> Unit) {
        for (j in 0 until segments) {
            val b1 = points[j]
            val b2 = points[minOf(j + 1, points.size - 1)]
            val c0 = col(minOf(b1.x, b2.x) - distance)
            val c1 = col(maxOf(b1.x, b2.x) + distance)
            val r0 = row(minOf(b1.y, b2.y) - distance)
            val r1 = row(maxOf(b1.y, b2.y) + distance)
            for (r in r0..r1) for (c in c0..c1) visit(r * cols + c, j)
        }
    }

    private fun col(x: Float): Int = floor((x - originX) / cell).toInt().coerceIn(0, cols - 1)
    private fun row(y: Float): Int = floor((y - originY) / cell).toInt().coerceIn(0, rows - 1)

    /**
     * True when any segment of [a] comes within [distance] of any segment of the indexed
     * polyline — [Geometry.polylineWithinDistance]`(a, points, distance)`, answered through the
     * grid. Stops at the first hit.
     */
    fun withinDistanceOf(a: List<StrokePoint>): Boolean {
        if (segments == 0 || a.isEmpty()) return false
        val aSegments = maxOf(1, a.size - 1)
        val gridRight = originX + cols * cell
        val gridBottom = originY + rows * cell
        for (i in 0 until aSegments) {
            val a1 = a[i]
            val a2 = a[minOf(i + 1, a.size - 1)]
            val minX = minOf(a1.x, a2.x); val maxX = maxOf(a1.x, a2.x)
            val minY = minOf(a1.y, a2.y); val maxY = maxOf(a1.y, a2.y)
            // The buckets' bounds are already inflated by the tolerance, so a query segment
            // wholly outside the grid is wholly outside every candidate's reach.
            if (maxX < originX || minX > gridRight || maxY < originY || minY > gridBottom) continue
            val c0 = col(minX); val c1 = col(maxX)
            val r0 = row(minY); val r1 = row(maxY)
            for (r in r0..r1) for (c in c0..c1) {
                val cellIndex = r * cols + c
                for (k in start[cellIndex] until start[cellIndex + 1]) {
                    val j = items[k]
                    if (seen[j] == i) continue
                    seen[j] = i
                    val b1 = points[j]
                    val b2 = points[minOf(j + 1, points.size - 1)]
                    val d = Geometry.distanceSegmentToSegment(a1.x, a1.y, a2.x, a2.y, b1.x, b1.y, b2.x, b2.y)
                    if (d <= distance) {
                        resetSeen()
                        return true
                    }
                }
            }
        }
        resetSeen()
        return false
    }

    /** Stamps are per query: the next query's segment 0 must not read as "already measured". */
    private fun resetSeen() = seen.fill(-1)

    private companion object {
        /** A cell never finer than this, so a hairline tolerance does not make a huge grid. */
        const val MIN_CELL_PX = 24f

        /** The grid's size cap; past it the cell doubles until it fits. */
        const val MAX_CELLS = 16_384
    }
}
