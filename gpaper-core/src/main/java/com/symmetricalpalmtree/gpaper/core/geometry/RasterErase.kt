package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint

/**
 * The shape of one eraser batch on a raster page (0.1.26), in pure Kotlin.
 *
 * In stroke mode an eraser sweep is a hit test: any mark the sweep's polyline passes
 * within the radius of is taken whole. On a raster page there are no marks to take —
 * only pixels — so the same sweep becomes a *corridor*: every pixel within the radius of
 * the polyline is cleared to transparent, and every pixel outside it is left exactly as
 * it was. That is what a rubber does to graphite. The corridor is drawn on the bitmap as
 * the polyline stroked with a round cap and round join at twice the radius, which is
 * the same set of points this object describes with [covers] — the disc swept along the
 * path — so a JVM test can prove the shape without a bitmap.
 *
 * Two things must hold for the host, and both are cheap to get wrong:
 *
 * - **The batch rect covers the corridor.** The host takes its before-image for undo
 *   from the rect it is handed in `onRasterWillChange`, and a rect that misses the edge
 *   of the corridor leaves a sliver undo cannot put back. So the rect is the polyline's
 *   bounds pushed out by the radius and then by [RasterDirty.MARGIN_PX] more for the
 *   antialiased edge and the rounding out — the same generosity, for the same reason,
 *   as a mark's dirty rect.
 * - **Chained batches leave no gap.** The eraser's samples arrive in batches, and a fast
 *   flick can move a long way between one batch and the next. The caller prepends the
 *   previous batch's last sample to the next batch (as stroke-mode erasing already does),
 *   so the corridors of consecutive batches share a segment and the cleared path is
 *   continuous. [covers] over the chained sweep is what the test checks.
 */
object RasterErase {

    /**
     * The integer, page-clipped rect the corridor of [sweep] at [radius] may touch on a
     * page of [pageWidth] × [pageHeight], or `null` when it cannot touch the page.
     */
    fun batchRect(sweep: List<StrokePoint>, radius: Float, pageWidth: Int, pageHeight: Int): Bounds? {
        if (sweep.isEmpty()) return null
        return RasterDirty.of(Bounds.of(sweep), radius, pageWidth, pageHeight)
    }

    /**
     * Whether the pixel centred at ([x], [y]) lies in the corridor: within [radius] of
     * some point of the [sweep] polyline. A one-sample sweep is a disc. This is the
     * geometric truth the bitmap stroke approximates, and the thing the tests assert.
     */
    fun covers(sweep: List<StrokePoint>, radius: Float, x: Float, y: Float): Boolean {
        if (sweep.isEmpty()) return false
        if (sweep.size == 1) {
            val p = sweep[0]
            return Geometry.distancePointToSegment(x, y, p.x, p.y, p.x, p.y) <= radius
        }
        for (i in 0 until sweep.size - 1) {
            val a = sweep[i]
            val b = sweep[i + 1]
            if (Geometry.distancePointToSegment(x, y, a.x, a.y, b.x, b.y) <= radius) return true
        }
        return false
    }
}
