package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.RasterRubbing
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The rubbing eraser on a raster page (0.1.30), in pure Kotlin — what one batch of the
 * sweep does to the pixels under it, and nothing else.
 *
 * 0.1.26's rubber was a hole-cutter: the sweep stroked onto the page in `CLEAR`, and
 * everything within the radius went in one pass. The artist's word for it, after an
 * afternoon: it *cut holes rather than lightened*, and *its edge showed*. A real rubber
 * lifts graphite a little at a time and lifts less at its edge than under its middle, so
 * this object replaces the stroke with arithmetic:
 *
 * - **Coverage** is how much of the rubber is over a pixel: 1 within the corridor's core,
 *   falling linearly to 0 at the radius over the feathered band ([coverage]).
 * - **Lift** is the fraction of what is there that one pass takes, set by pressure
 *   between the light and firm numbers of [RasterRubbing] ([lift]).
 * - **A pass** is one continuous sweep in roughly one direction. Within a pass a pixel is
 *   lifted once, by the *most* the rubber ever covered it — batches arrive every frame and
 *   overlap at their seams, and a rubber that lifted twice wherever two batches met would
 *   leave a bead at every seam of a fast sweep. So each pixel carries the pass's lift so
 *   far (the pass mask), and a batch only ever raises it ([rubBatch]).
 * - **A reversal starts a new pass.** Rubbing is back and forth, and each stroke of the
 *   arm should lift again: when a batch travels against the previous one — more than
 *   120° off — the pass mask is dropped and the next lift applies on top of what the
 *   last pass left. Dwell therefore counts, as the artist asked, and a seam does not.
 *
 * The page keeps the whole history for free. Its alpha is already the product of every
 * pass so far, so raising a pixel's pass value from p0 to p1 multiplies its alpha by
 * (1 − p1) / (1 − p0) — the settled passes cancel out of the ratio and never need to be
 * remembered. One page-sized byte mask per contact is the entire state.
 */
object RasterRub {

    /** Cosine of the turn that counts as rubbing back: 120°. */
    const val REVERSAL_COS = -0.5f

    /** Travel shorter than this within a batch says nothing about direction. */
    const val MIN_TRAVEL_PX = 2f

    /** An alpha under this after a lift is nothing: one part in a hundred, under any panel's greys. */
    const val GONE_BELOW_ALPHA = 3

    /** One pass's lift at [pressure] (0..1; anything outside is the middle of the range). */
    fun lift(pressure: Float, rubbing: RasterRubbing): Float {
        val p = if (pressure.isNaN() || pressure < 0f || pressure > 1f) 0.5f else pressure
        return rubbing.liftLight + (rubbing.liftFirm - rubbing.liftLight) * p
    }

    /**
     * How much of the rubber sits over the point ([x], [y]): 1 inside the core, 0 beyond
     * [radius], linear across the feathered band between. A one-sample sweep is a disc.
     */
    fun coverage(sweep: List<StrokePoint>, radius: Float, feather: Float, x: Float, y: Float): Float {
        if (sweep.isEmpty() || radius <= 0f) return 0f
        var d = Float.MAX_VALUE
        if (sweep.size == 1) {
            val p = sweep[0]
            d = Geometry.distancePointToSegment(x, y, p.x, p.y, p.x, p.y)
        } else {
            for (i in 0 until sweep.size - 1) {
                val a = sweep[i]
                val b = sweep[i + 1]
                val di = Geometry.distancePointToSegment(x, y, a.x, a.y, b.x, b.y)
                if (di < d) d = di
            }
        }
        if (d >= radius) return 0f
        val core = radius * (1f - feather)
        if (d <= core) return 1f
        return (radius - d) / (radius - core)
    }

    /** Unit direction of travel across [sweep], or null when it barely moved. */
    fun direction(sweep: List<StrokePoint>): FloatArray? {
        if (sweep.size < 2) return null
        val dx = sweep.last().x - sweep.first().x
        val dy = sweep.last().y - sweep.first().y
        val len = sqrt(dx * dx + dy * dy)
        if (len < MIN_TRAVEL_PX) return null
        return floatArrayOf(dx / len, dy / len)
    }

    /** Whether travel [next] runs back against [prev]: the arm has reversed. */
    fun isReversal(prev: FloatArray?, next: FloatArray?): Boolean {
        if (prev == null || next == null) return false
        return prev[0] * next[0] + prev[1] * next[1] < REVERSAL_COS
    }

    /**
     * Rub one batch into [pixels], the page's straight-alpha ARGB for the rect at
     * ([left], [top]) of [width] × [height], and raise [pass] — the page-sized byte mask
     * of this pass's lift so far, [pageWidth] to a row — to match. Returns whether any
     * pixel changed.
     *
     * For every pixel under the rubber: the pass value it should now carry is the larger
     * of what it has and `lift × coverage`; if that is a rise, the alpha is scaled by
     * (1 − new) / (1 − old). Colour is left alone — lifted graphite is paler, not a
     * different grey — and a pixel whose pass value is already 1 cannot be lifted further.
     */
    fun rubBatch(
        pixels: IntArray,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        pageWidth: Int,
        pass: ByteArray,
        sweep: List<StrokePoint>,
        radius: Float,
        rubbing: RasterRubbing,
        lift: Float,
    ): Boolean {
        if (sweep.isEmpty() || lift <= 0f) return false
        var changed = false
        for (row in 0 until height) {
            val y = top + row
            val py = y + 0.5f
            for (col in 0 until width) {
                val x = left + col
                val c = coverage(sweep, radius, rubbing.feather, x + 0.5f, py)
                if (c <= 0f) continue
                val maskIndex = y * pageWidth + x
                val p0 = (pass[maskIndex].toInt() and 0xFF) / 255f
                val p1 = max(p0, lift * c)
                if (p1 <= p0 || p0 >= 1f) continue
                val i = row * width + col
                val argb = pixels[i]
                val a0 = argb ushr 24
                if (a0 != 0) {
                    var a1 = (a0 * (1f - p1) / (1f - p0)).roundToInt().coerceIn(0, 255)
                    // Graphite the panel cannot show is graphite that is gone. A lift is a
                    // ratio and a ratio never reaches zero — a line rubbed out by eye would keep
                    // an alpha of one or two for twenty light passes — and a page of such ghosts
                    // is a page the host's blank test calls drawn on, so a rubbed-out leaf would
                    // wear a smudge on the shelf. Below this the pixel is let go entirely.
                    if (a1 < GONE_BELOW_ALPHA) a1 = 0
                    pixels[i] = (a1 shl 24) or (argb and 0x00FFFFFF)
                }
                pass[maskIndex] = (p1 * 255f).roundToInt().coerceIn(0, 255).toByte()
                changed = true
            }
        }
        return changed
    }

    /** Forget the pass over the rect at ([left], [top]) of [width] × [height]. */
    fun clearPass(pass: ByteArray, pageWidth: Int, left: Int, top: Int, width: Int, height: Int) {
        for (row in 0 until height) {
            val start = (top + row) * pageWidth + left
            pass.fill(0, start, start + width)
        }
    }
}
