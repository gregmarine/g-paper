package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.RasterSmudging
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
 *   value to its neighbourhood mean it moves in one **pass**. A pass is one stroke of the
 *   arm, exactly as the rubber counts them ([RasterRub]): batches arrive every frame and
 *   overlap under a fingertip many times over, and a pull that compounded per batch made
 *   a slow hand or a dense digitizer blend the page away (the first Nomad probe: 240
 *   batches for eight strokes of the arm, and 1 % of the tone left). So each pixel
 *   carries the pass's pull so far (the pass mask) and a batch only ever raises it,
 *   moving the pixel the *remaining* fraction toward the mean; a reversal starts a new
 *   pass, and each stroke of the arm blends again.
 * - **Loss** scales the alpha by `1 − loss × pull` per pass, raised the same way; colour is
 *   left as the blend made it.
 * - **Tone** ([RasterSmudging.gamma]): the alpha a pixel is pulled toward is the mean of
 *   its neighbours' alpha raised to `gamma`, brought back by the root — the plain mean at
 *   1, the root mean square at 2. The colour comes from the plain premultiplied mean.
 * - **The load** ([Load], [RasterSmudging.carry] / [RasterSmudging.deposit]): what the
 *   finger carries. Each batch it decays by the sweep's travel and is topped up to the
 *   corridor's tone where that is darker; under the finger a pixel's target is at least
 *   `deposit × load`, in the load's colour where the pixel has none of its own. That is
 *   what lets a rub run out past a mark's edge and dirty the paper beyond it, fading.
 *
 * **Cost is the swept area, never pixels × segments.** The coverage is laid down as a
 * field first — each segment of the sweep visits only its own box, `radius` around it,
 * and raises the field to its coverage — and the pixel loop reads the field. The first
 * build tested every pixel of the batch's rect against every segment; a real finger's
 * event carries dozens of samples, the queue coalesced behind the first slow batch, and
 * the Nomad hung 12 s on one event (an ANR that closed the face). See
 * `CanvasPaperView.smudgeAlong` for the thinning and chunking on the engine side.
 *
 * **The smear follows the hand (0.1.60).** The neighbourhood is a box **turned to the
 * batch's direction of travel** — [RasterSmudging.spread] px to either side along it and
 * [RasterSmudging.across] px to either side across it ([lineMean]) — so graphite is
 * pushed the way the finger goes: a rub left and right runs a vertical hatch together
 * and a rub up and down along its lines only smears each line along itself. The
 * direction is the sweep's **principal axis** ([axis]) — the structure tensor of its
 * segments, so a batch that turns back on itself still reads as one line of travel
 * rather than a net displacement of nothing — and a batch with no travel (a dwell, a
 * single sample) falls back to the plain square box of 0.1.54. The axis is **quantized
 * to the lattice's four** — 0°, 45°, 90°, 135° ([quantizeAxis]) — so the mean can run
 * **separably** as two running sums along lattice lines ([lineMean]): along the axis,
 * then across it. A first cut gathered a precomputed offset list per pixel and cost the
 * Nomad two to three seconds an event at `across` 4; this is O(n) whatever the reach,
 * and a hand cannot tell a 30° streak from a 45° one under a fingertip. **The smear is
 * one-sided (0.1.60, the user's third walk):** along the axis a pixel is pulled toward the
 * mean of what lies *behind* it in the direction of travel, never ahead, the corridor is
 * clipped behind the sweep's first sample, and a batch with no net travel does nothing —
 * so the nib landing on a mark smudges nothing until it moves, and then only the way it
 * moves: "if the smudge stroke is only down, the smudge effect should only go down".
 * The first cut blurred a square around the landing sample and reached back past it.
 *
 * The caller hands over the pixels of a rect **padded by [RasterSmudging.spread]** around
 * the corridor, so the mean at the corridor's edge sees the true neighbours beyond it;
 * only pixels inside the corridor's own rect are written back. The square blur is a
 * separable box on running sums — O(n) in the padded area, whatever the spread — and so
 * is the oriented one, two line passes per plane.
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
        internal var t = IntArray(0)
        internal var r = IntArray(0)
        internal var g = IntArray(0)
        internal var b = IntArray(0)
        internal var tmp = IntArray(0)
        internal var cov = FloatArray(0)
        /** One lattice line's indices for [lineMean] — `max(width, height)` long. */
        internal var line = IntArray(0)
        internal var lutGamma = 0f
        internal val lut = IntArray(256)
        internal fun ensure(n: Int) {
            if (a.size < n) {
                a = IntArray(n); t = IntArray(n); r = IntArray(n); g = IntArray(n); b = IntArray(n)
                tmp = IntArray(n); cov = FloatArray(n)
            }
            if (line.size < n) line = IntArray(n)
        }
        /** `alpha^gamma`, scaled to [TONE_SCALE], one entry per alpha. */
        internal fun toneLut(gamma: Float): IntArray {
            if (lutGamma != gamma) {
                for (i in 0..255) lut[i] = ((i / 255f).toDouble().pow(gamma.toDouble()) * TONE_SCALE).roundToInt()
                lutGamma = gamma
            }
            return lut
        }
    }

    /** The tone plane's full-scale value: 4095, so a 129 × 129 window's sum still fits an Int. */
    const val TONE_SCALE = 4095

    /** Only the finger's core picks graphite up — its feathered rim would read the edge's paper. */
    const val LOAD_PICKUP_COVERAGE = 0.5f

    /**
     * The graphite on the finger across one contact: its tone as an alpha (0..255) and its
     * colour. Fresh at each [reset]; [smudgeBatch] decays and tops it up.
     */
    class Load {
        var alpha = 0f
        var r = 0f
        var g = 0f
        var b = 0f
        fun reset() { alpha = 0f; r = 0f; g = 0f; b = 0f }
    }

    /**
     * The sweep's principal axis as a unit vector, or null when it has no direction — a
     * single sample, or less than [RasterRub.MIN_TRAVEL_PX] of travel in all. It is the
     * eigenvector of the segments' structure tensor (Σdx², Σdxdy, Σdy²), so a batch that
     * runs out and back along one line still answers that line, where a first-to-last
     * displacement would answer nothing. Sign is meaningless: the kernel is symmetric.
     */
    fun axis(sweep: List<StrokePoint>): FloatArray? {
        if (sweep.size < 2) return null
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var len = 0f
        for (i in 1 until sweep.size) {
            val dx = (sweep[i].x - sweep[i - 1].x).toDouble()
            val dy = (sweep[i].y - sweep[i - 1].y).toDouble()
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
            len += kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
        }
        if (len < RasterRub.MIN_TRAVEL_PX) return null
        // The larger eigenvalue's eigenvector of [[sxx, sxy], [sxy, syy]].
        val half = (sxx + syy) / 2.0
        val diff = (sxx - syy) / 2.0
        val root = kotlin.math.sqrt(diff * diff + sxy * sxy)
        val lambda = half + root
        var ux: Double; var uy: Double
        if (kotlin.math.abs(sxy) > 1e-9) { ux = lambda - syy; uy = sxy }
        else if (sxx >= syy) { ux = 1.0; uy = 0.0 }
        else { ux = 0.0; uy = 1.0 }
        val n = kotlin.math.sqrt(ux * ux + uy * uy)
        if (n < 1e-12) return null
        return floatArrayOf((ux / n).toFloat(), (uy / n).toFloat())
    }

    /**
     * The nearest of the four lattice axes to ([ux], [uy]) — 0°, 45°, 90° or 135° — as a
     * unit step `(sx, sy)` in `{(1,0), (1,1), (0,1), (1,-1)}`. The smear is separable only
     * along lines the lattice can walk, and a hand cannot tell a 30° streak from a 45°
     * one under a fingertip; a general gather cost 117 samples a pixel at the defaults and
     * two seconds an event on the Nomad.
     */
    fun quantizeAxis(ux: Float, uy: Float): IntArray {
        // Fold to the upper half-plane (the axis has no sign), then pick by angle.
        val x = if (uy < 0f) -ux else ux
        val y = if (uy < 0f) -uy else uy
        val angle = Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble()))   // 0..180
        return when {
            angle < 22.5 || angle >= 157.5 -> intArrayOf(1, 0)
            angle < 67.5 -> intArrayOf(1, 1)
            angle < 112.5 -> intArrayOf(0, 1)
            else -> intArrayOf(1, -1)
        }
    }

    /**
     * In-place mean of [plane] over a window of [before] samples back and [after] samples
     * on **along the lattice line** of step ([sx], [sy]) through each pixel, on a [width] ×
     * [height] plane — a running sum per line, clipped at the plane's edge and divided by what it
     * covered, as [boxBlur] is. Every pixel lies on exactly one line of each step, so the
     * cost is O(n) whatever [half]. [tmp] is scratch of at least `width × height`;
     * [line] is scratch of at least `max(width, height)`.
     */
    internal fun lineMean(plane: IntArray, tmp: IntArray, line: IntArray, width: Int, height: Int, sx: Int, sy: Int, before: Int, after: Int) {
        if (before <= 0 && after <= 0) return
        fun walk(x0: Int, y0: Int) {
            var n = 0
            var x = x0; var y = y0
            while (x in 0 until width && y in 0 until height) { line[n++] = y * width + x; x += sx; y += sy }
            if (n == 0) return
            var sum = 0; var count = 0
            val prime = min(after, n - 1)
            for (i in 0..prime) { sum += plane[line[i]]; count++ }
            for (i in 0 until n) {
                tmp[line[i]] = (sum + count / 2) / count
                val add = i + after + 1
                if (add < n) { sum += plane[line[add]]; count++ }
                val drop = i - before
                if (drop >= 0) { sum -= plane[line[drop]]; count-- }
            }
        }
        when {
            sx == 1 && sy == 0 -> for (y in 0 until height) walk(0, y)
            sx == 0 && sy == 1 -> for (x in 0 until width) walk(x, 0)
            sx == 1 && sy == 1 -> { for (x in 0 until width) walk(x, 0); for (y in 1 until height) walk(0, y) }
            else -> { for (x in 0 until width) walk(x, height - 1); for (y in 0 until height - 1) walk(0, y) }
        }
        System.arraycopy(tmp, 0, plane, 0, width * height)
    }

    /**
     * The oriented mean, separably and **one-sided**: along the quantized axis each pixel
     * is pulled toward the mean of the `2 × along` px **behind it** in the direction of
     * travel — what the nib has dragged up to it — and nothing ahead, so graphite only
     * ever moves the way the hand goes and nothing spills back past where the nib came
     * from; then the symmetric mean across it ([across] px to either side). [forward] says
     * whether travel runs with the canonical step or against it. On a diagonal the
     * lattice step is √2 px, so the sample counts are scaled to keep the reach in px.
     */
    internal fun orientedMean(plane: IntArray, scratch: Scratch, width: Int, height: Int, axis: IntArray, forward: Boolean, along: Int, across: Int) {
        val diagonal = axis[0] != 0 && axis[1] != 0
        val scale = if (diagonal) 0.70710677f else 1f
        val a = (2 * along * scale).roundToInt()
        val c = (across * scale).roundToInt()
        if (forward) lineMean(plane, scratch.tmp, scratch.line, width, height, axis[0], axis[1], a, 0)
        else lineMean(plane, scratch.tmp, scratch.line, width, height, axis[0], axis[1], 0, a)
        // The perpendicular step: (sx, sy) → (-sy, sx), folded to the four canonical steps.
        val px = -axis[1]; val py = axis[0]
        val (qx, qy) = if (px < 0 || (px == 0 && py < 0)) -px to -py else px to py
        lineMean(plane, scratch.tmp, scratch.line, width, height, qx, qy, c, c)
    }

    /**
     * The sweep's net travel as a unit vector, or null under [RasterRub.MIN_TRAVEL_PX] —
     * the sign the axis lacks. A batch that turns back within itself still reads by where
     * it ended up; the pass reset on reversal takes care of the rest.
     */
    fun heading(sweep: List<StrokePoint>): FloatArray? = RasterRub.direction(sweep)

    /** The sweep's length in px. */
    fun travel(sweep: List<StrokePoint>): Float {
        var d = 0f
        for (i in 1 until sweep.size) {
            val dx = sweep[i].x - sweep[i - 1].x
            val dy = sweep[i].y - sweep[i - 1].y
            d += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return d
    }

    /**
     * Lay the corridor's coverage into [cov] for the [width] × [height] rect at ([left],
     * [top]): the rubber's coverage ([RasterRub.coverage]), but each segment visits only
     * its own box. Pixels the sweep never nears are left at 0 — the caller clears the
     * field over the rect first.
     */
    fun coverageField(
        cov: FloatArray, left: Int, top: Int, width: Int, height: Int,
        sweep: List<StrokePoint>, radius: Float, feather: Float,
        /** With a heading (unit vector), nothing **behind the sweep's first sample** is
         *  covered: the smudge reaches only where the nib is going, never back past where
         *  it came from (the cap the rubber has behind its landing is the rubber's alone). */
        heading: FloatArray? = null,
    ) {
        if (sweep.isEmpty() || radius <= 0f) return
        val core = radius * (1f - feather)
        val band = radius - core
        val n = if (sweep.size == 1) 1 else sweep.size - 1
        val hx = heading?.get(0) ?: 0f
        val hy = heading?.get(1) ?: 0f
        val ox = sweep[0].x
        val oy = sweep[0].y
        for (i in 0 until n) {
            val a = sweep[i]
            val b = if (sweep.size == 1) a else sweep[i + 1]
            val x0 = max(left, floor(min(a.x, b.x) - radius).toInt())
            val x1 = min(left + width - 1, ceil(max(a.x, b.x) + radius).toInt())
            val y0 = max(top, floor(min(a.y, b.y) - radius).toInt())
            val y1 = min(top + height - 1, ceil(max(a.y, b.y) + radius).toInt())
            for (y in y0..y1) {
                val py = y + 0.5f
                val row = (y - top) * width
                for (x in x0..x1) {
                    val px = x + 0.5f
                    if (heading != null && (px - ox) * hx + (py - oy) * hy < 0f) continue
                    val d = Geometry.distancePointToSegment(px, py, a.x, a.y, b.x, b.y)
                    if (d >= radius) continue
                    val c = if (d <= core || band <= 0f) 1f else (radius - d) / band
                    val k = row + (x - left)
                    if (c > cov[k]) cov[k] = c
                }
            }
        }
    }

    /**
     * Smudge one batch into [pixels] — the page's straight-alpha ARGB for the **padded**
     * rect at ([left], [top]) of [width] × [height] — writing back only inside the inner
     * rect [innerLeft], [innerTop], [innerWidth] × [innerHeight] (page coordinates, wholly
     * within the padded one), and raise [pass] — the page-sized byte mask of this pass's
     * pull so far, [pageWidth] to a row — to match. [sweep] is the finger's polyline in
     * page coordinates and [radius] its reach. Returns whether any pixel changed.
     *
     * For every pixel under the finger: the pass value it should now carry is the larger
     * of what it has and `strength × coverage`; if that is a rise from p0 to p1, the pixel
     * moves `(p1 − p0) / (1 − p0)` of the remaining way to its neighbourhood mean — so
     * across a pass it has moved p1 of the way in total, however many batches crossed it —
     * and its alpha is scaled by `(1 − loss·p1) / (1 − loss·p0)`.
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
        pageWidth: Int,
        pass: ByteArray,
        sweep: List<StrokePoint>,
        radius: Float,
        smudging: RasterSmudging,
        scratch: Scratch = Scratch(),
        load: Load = Load(),
    ): Boolean {
        if (sweep.isEmpty() || smudging.strength <= 0f || width <= 0 || height <= 0) return false
        val head = heading(sweep) ?: return false
        val n = width * height
        scratch.ensure(n)
        val pa = scratch.a; val pt = scratch.t; val pr = scratch.r; val pg = scratch.g; val pb = scratch.b
        val lut = scratch.toneLut(smudging.gamma)
        val invGamma = 1.0 / smudging.gamma
        // Premultiply: a transparent pixel contributes nothing but its emptiness.
        for (i in 0 until n) {
            val argb = pixels[i]
            val a = argb ushr 24
            pa[i] = a
            pt[i] = lut[a]
            pr[i] = ((argb shr 16) and 0xFF) * a
            pg[i] = ((argb shr 8) and 0xFF) * a
            pb[i] = (argb and 0xFF) * a
        }
        val s = smudging.spread
        // The smear follows the hand — and only the hand: no travel, no smudge. The box is
        // turned to the batch's line of travel, quantized to the lattice's four axes, run
        // separably and one-sided (behind the nib only), and the corridor stops at the
        // landing. A nib that has not moved yet does nothing at all.
        val dir = axis(sweep) ?: return false
        val axis = quantizeAxis(dir[0], dir[1])
        val forward = axis[0] * head[0] + axis[1] * head[1] >= 0f
        orientedMean(pa, scratch, width, height, axis, forward, s, smudging.across)
        orientedMean(pt, scratch, width, height, axis, forward, s, smudging.across)
        orientedMean(pr, scratch, width, height, axis, forward, s, smudging.across)
        orientedMean(pg, scratch, width, height, axis, forward, s, smudging.across)
        orientedMean(pb, scratch, width, height, axis, forward, s, smudging.across)
        val cov = scratch.cov
        java.util.Arrays.fill(cov, 0, n, 0f)
        coverageField(cov, left, top, width, height, sweep, radius, smudging.feather, head)

        val rowEnd = min(innerTop + innerHeight, top + height)
        val colEnd = min(innerLeft + innerWidth, left + width)

        // The finger's load: decay it by the travel, then top it up to the darkest local
        // tone under the finger's core — a finger picks up where it touches graphite, and
        // the corridor's average would be watered down by the bare paper beside a mark —
        // taking the corridor's colour with it.
        if (smudging.carry > 0f) load.alpha *= exp(-travel(sweep) / smudging.carry) else load.alpha = 0f
        run {
            var maxT = 0; var sumA = 0.0; var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
            for (y in max(innerTop, top) until rowEnd) {
                val row = (y - top) * width
                for (x in max(innerLeft, left) until colEnd) {
                    val i = row + (x - left)
                    if (cov[i] < LOAD_PICKUP_COVERAGE) continue
                    if (pt[i] > maxT) maxT = pt[i]
                    sumA += pa[i]; sumR += pr[i]; sumG += pg[i]; sumB += pb[i]
                }
            }
            if (maxT > 0) {
                val tone = (255.0 * (maxT.toDouble() / TONE_SCALE).pow(invGamma)).toFloat()
                if (tone > load.alpha) {
                    load.alpha = tone
                    if (sumA > 0.0) {
                        load.r = (sumR / sumA).toFloat(); load.g = (sumG / sumA).toFloat(); load.b = (sumB / sumA).toFloat()
                    }
                }
            }
        }
        val laid = if (smudging.carry > 0f) load.alpha * smudging.deposit else 0f

        var changed = false
        for (y in max(innerTop, top) until rowEnd) {
            val row = (y - top) * width
            for (x in max(innerLeft, left) until colEnd) {
                val c = cov[row + (x - left)]
                if (c <= 0f) continue
                val maskIndex = y * pageWidth + x
                val p0 = (pass[maskIndex].toInt() and 0xFF) / 255f
                val p1 = max(p0, smudging.strength * c)
                if (p1 <= p0 || p0 >= 1f) continue
                pass[maskIndex] = (p1 * 255f).roundToInt().coerceIn(0, 255).toByte()
                // The remaining fraction of the way: across the pass the pixel has moved p1.
                val pull = (p1 - p0) / (1f - p0)
                val keep = (1f - smudging.loss * p1) / (1f - smudging.loss * p0)
                val i = row + (x - left)
                val argb = pixels[i]
                val a0 = argb ushr 24
                // The alpha target is the power mean of the neighbourhood's darkness; the
                // colour target is the plain premultiplied mean's colour.
                val aLin = pa[i].toFloat()
                if (a0 == 0 && aLin <= 0f && laid < 1f) continue
                var aMean = if (smudging.gamma == 1f) aLin
                    else (255.0 * (pt[i].toDouble() / TONE_SCALE).pow(invGamma)).toFloat()
                if (laid > aMean) aMean = laid
                val aBlend = a0 + (aMean - a0) * pull
                var a1 = (aBlend * keep).roundToInt().coerceIn(0, 255)
                if (a1 < GONE_BELOW_ALPHA) a1 = 0
                val out = if (a1 == 0) 0 else {
                    // The colour: the pixel's own where it has one, moved `pull` of the way
                    // toward the neighbourhood's (un-premultiplied) colour; a bare pixel
                    // takes the neighbourhood's outright.
                    // A bare neighbourhood has no colour to give: the load's, then.
                    val rM: Float; val gM: Float; val bM: Float
                    if (aLin > 0f) {
                        val invLin = 1f / aLin
                        rM = pr[i] * invLin; gM = pg[i] * invLin; bM = pb[i] * invLin
                    } else { rM = load.r; gM = load.g; bM = load.b }
                    val r0 = ((argb shr 16) and 0xFF).toFloat()
                    val g0 = ((argb shr 8) and 0xFF).toFloat()
                    val b0 = (argb and 0xFF).toFloat()
                    val w = if (a0 == 0) 1f else pull
                    val r1 = (r0 + (rM - r0) * w).roundToInt().coerceIn(0, 255)
                    val g1 = (g0 + (gM - g0) * w).roundToInt().coerceIn(0, 255)
                    val b1 = (b0 + (bM - b0) * w).roundToInt().coerceIn(0, 255)
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

    /** Forget the pass over the rect at ([left], [top]) of [width] × [height]. */
    fun clearPass(pass: ByteArray, pageWidth: Int, left: Int, top: Int, width: Int, height: Int) =
        RasterRub.clearPass(pass, pageWidth, left, top, width, height)

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
