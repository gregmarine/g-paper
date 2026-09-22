package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.RasterSmudging
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The finger smudge on a raster page (0.1.54), in pure Kotlin — what one batch of the
 * sweep does to the pixels under it, and nothing else.
 *
 * The rubber ([RasterRub]) lifts; this **moves**. A pencil hatch is a set of separate
 * lines with bare paper between them, and the artist wants to drag a finger across it and
 * have it run together into a tone — blended, not clumped, and not taken off. So under
 * the finger every pixel is pulled toward the **mean of its neighbourhood**: a box
 * [RasterSmudging.spread] px to either side, computed on premultiplied channels so a
 * transparent neighbour lends no colour, only its emptiness. Graphite therefore flows
 * from the lines into the gaps and the sum over the corridor stays what it was; the only
 * graphite that leaves is what [RasterSmudging.loss] carries off, and what the blur pushes
 * past the corridor's feathered edge, where nothing is written — a hatch smudged at its
 * border pales there, as it does on paper.
 *
 * - **Coverage** is the rubber's own ([RasterRub.coverage]): 1 within the corridor's core,
 *   falling to 0 at the radius across the feathered band.
 * - **Pull** is `strength × coverage` — the fraction of the way from the pixel's own
 *   value to its neighbourhood mean it moves in this batch. Every batch pulls; there is
 *   no pass mask, because a finger that dwells and wiggles *should* keep blending, and a
 *   seam blended twice is not a bead the eye can find the way a lift is.
 * - **Loss** scales the alpha down by `loss × pull` after the blend; colour is left as the
 *   blend made it.
 *
 * The caller hands over the pixels of a rect **padded by [RasterSmudging.spread]** around
 * the corridor, so the mean at the corridor's edge sees the true neighbours beyond it;
 * only pixels inside the corridor's own rect are written back. The blur is a separable
 * box on running sums — O(n) in the padded area, whatever the spread.
 */
object RasterSmudge {

    /** An alpha under this after a smudge is nothing — the rubber's own line. */
    const val GONE_BELOW_ALPHA = RasterRub.GONE_BELOW_ALPHA

    /**
     * Reusable scratch for the blur — four premultiplied planes and a row/column
     * accumulator, grown to the largest padded rect seen. One per contact is enough.
     */
    class Scratch {
        internal var a = IntArray(0)
        internal var r = IntArray(0)
        internal var g = IntArray(0)
        internal var b = IntArray(0)
        internal var tmp = IntArray(0)
        internal fun ensure(n: Int) {
            if (a.size < n) {
                a = IntArray(n); r = IntArray(n); g = IntArray(n); b = IntArray(n); tmp = IntArray(n)
            }
        }
    }

    /**
     * Smudge one batch into [pixels] — the page's straight-alpha ARGB for the **padded**
     * rect at ([left], [top]) of [width] × [height] — writing back only inside the inner
     * rect [innerLeft], [innerTop], [innerWidth] × [innerHeight] (page coordinates, wholly
     * within the padded one). [sweep] is the finger's polyline in page coordinates and
     * [radius] its reach. Returns whether any pixel changed.
     */
    fun smudgeBatch(
        pixels: IntArray,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        innerLeft: Int,
        innerTop: Int,
        innerWidth: Int,
        innerHeight: Int,
        sweep: List<StrokePoint>,
        radius: Float,
        smudging: RasterSmudging,
        scratch: Scratch = Scratch(),
    ): Boolean {
        if (sweep.isEmpty() || smudging.strength <= 0f || width <= 0 || height <= 0) return false
        val n = width * height
        scratch.ensure(n)
        val pa = scratch.a; val pr = scratch.r; val pg = scratch.g; val pb = scratch.b
        // Premultiply: a transparent pixel contributes nothing but its emptiness.
        for (i in 0 until n) {
            val argb = pixels[i]
            val a = argb ushr 24
            pa[i] = a
            pr[i] = ((argb shr 16) and 0xFF) * a
            pg[i] = ((argb shr 8) and 0xFF) * a
            pb[i] = (argb and 0xFF) * a
        }
        val s = smudging.spread
        boxBlur(pa, scratch.tmp, width, height, s)
        boxBlur(pr, scratch.tmp, width, height, s)
        boxBlur(pg, scratch.tmp, width, height, s)
        boxBlur(pb, scratch.tmp, width, height, s)

        var changed = false
        val rowEnd = min(innerTop + innerHeight, top + height)
        val colEnd = min(innerLeft + innerWidth, left + width)
        for (y in max(innerTop, top) until rowEnd) {
            val py = y + 0.5f
            val row = (y - top) * width
            for (x in max(innerLeft, left) until colEnd) {
                val c = RasterRub.coverage(sweep, radius, smudging.feather, x + 0.5f, py)
                if (c <= 0f) continue
                val pull = smudging.strength * c
                val i = row + (x - left)
                val argb = pixels[i]
                val a0 = argb ushr 24
                // The blurred planes are premultiplied; the mean alpha is the target.
                val aMean = pa[i].toFloat()
                if (a0 == 0 && aMean <= 0f) continue
                val r0 = ((argb shr 16) and 0xFF) * a0
                val g0 = ((argb shr 8) and 0xFF) * a0
                val b0 = (argb and 0xFF) * a0
                val aBlend = a0 + (aMean - a0) * pull
                val rBlend = r0 + (pr[i] - r0) * pull
                val gBlend = g0 + (pg[i] - g0) * pull
                val bBlend = b0 + (pb[i] - b0) * pull
                var a1 = (aBlend * (1f - smudging.loss * pull)).roundToInt().coerceIn(0, 255)
                if (a1 < GONE_BELOW_ALPHA) a1 = 0
                val out = if (a1 == 0) 0 else {
                    // Un-premultiply against the blended alpha (before the loss, which pales
                    // the pixel without changing what colour its graphite is).
                    val inv = 1f / max(aBlend, 1f)
                    val r1 = (rBlend * inv).roundToInt().coerceIn(0, 255)
                    val g1 = (gBlend * inv).roundToInt().coerceIn(0, 255)
                    val b1 = (bBlend * inv).roundToInt().coerceIn(0, 255)
                    (a1 shl 24) or (r1 shl 16) or (g1 shl 8) or b1
                }
                if (out != argb) {
                    pixels[i] = out
                    changed = true
                }
            }
        }
        return changed
    }

    /**
     * In-place separable box mean of half-width [s] over a [width] × [height] plane. The
     * window is clipped at the plane's edge and divided by the pixels it actually covered,
     * so an edge pixel is the mean of what is there, not of what is there plus nothing.
     */
    internal fun boxBlur(plane: IntArray, tmp: IntArray, width: Int, height: Int, s: Int) {
        // Rows → tmp.
        for (y in 0 until height) {
            val row = y * width
            var sum = 0
            var count = 0
            // Prime the window over [0, s].
            for (x in 0..min(s, width - 1)) { sum += plane[row + x]; count++ }
            for (x in 0 until width) {
                tmp[row + x] = (sum + count / 2) / count
                val add = x + s + 1
                if (add < width) { sum += plane[row + add]; count++ }
                val drop = x - s
                if (drop >= 0) { sum -= plane[row + drop]; count-- }
            }
        }
        // Columns → plane.
        for (x in 0 until width) {
            var sum = 0
            var count = 0
            for (y in 0..min(s, height - 1)) { sum += tmp[y * width + x]; count++ }
            for (y in 0 until height) {
                plane[y * width + x] = (sum + count / 2) / count
                val add = y + s + 1
                if (add < height) { sum += tmp[add * width + x]; count++ }
                val drop = y - s
                if (drop >= 0) { sum -= tmp[drop * width + x]; count-- }
            }
        }
    }
}
