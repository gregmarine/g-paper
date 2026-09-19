package com.symmetricalpalmtree.gpaper.ratta

import com.symmetricalpalmtree.gpaper.core.geometry.BlueNoise64
import com.symmetricalpalmtree.gpaper.core.geometry.Dither

/**
 * One page pixel, flattened the way the page is seen and then dithered — **the single
 * place either half of the direct path decides what a pixel shows** (Phase 28, the third
 * Supernote walk).
 *
 * There are two halves, and they must never disagree. While the pen is down the engine
 * paints the panel itself, pixel by pixel, from the page images plus the flecks laid so far
 * on a live alpha layer. At pen-up the mark bakes into the graphite image and the window
 * redraws, and whatever the compositor then rewrites into the panel must be *the pixels
 * already there* — otherwise every mark settles a shade a beat after it is drawn, which is
 * the exact failure the direct path exists to avoid. Two copies of this arithmetic would
 * drift apart the first time one of them was touched, and the drift would show up on a
 * panel rather than in a test. So there is one copy, it is pure, and both halves call it.
 *
 * The flatten is `drawCommittedContent`'s own, in the same order and with the same
 * operator: white paper, the graphite image over it, the live flecks over that, and the
 * ink image through `DARKEN` — the darker of the two per channel, which is commutative, so
 * the pair has no top and no bottom to get wrong. The display half simply passes a live
 * alpha of zero, because by then the flecks are in the graphite image.
 *
 * Pure Kotlin — no Android imports, JVM-tested.
 */
internal object DitherFlatten {

    /**
     * Rec. 601 luma in 8-bit fixed point: `(77·R + 151·G + 28·B) / 256`. The weights sum
     * to exactly 256, so white comes back as 255 and black as 0 — which matters, because
     * those are the two greys [Dither] answers absolutely.
     */
    private const val LUMA_R = 77
    private const val LUMA_G = 151
    private const val LUMA_B = 28

    /** The grey (0…255) a page pixel shows. See the class KDoc for the order. */
    fun luma(graphite: Int, liveAlpha: Int, liveColor: Int, ink: Int): Int {
        // White paper.
        var r = 255
        var g = 255
        var b = 255
        // The graphite image, over the paper.
        val ga = graphite ushr 24
        if (ga != 0) {
            r = over(graphite ushr 16 and 0xFF, ga)
            g = over(graphite ushr 8 and 0xFF, ga)
            b = over(graphite and 0xFF, ga)
        }
        // This contact's live flecks, in the lead's own colour, over that.
        if (liveAlpha != 0) {
            val a = liveAlpha and 0xFF
            r = ((liveColor ushr 16 and 0xFF) * a + r * (255 - a)) / 255
            g = ((liveColor ushr 8 and 0xFF) * a + g * (255 - a)) / 255
            b = ((liveColor and 0xFF) * a + b * (255 - a)) / 255
        }
        // The ink image, through DARKEN.
        val ia = ink ushr 24
        if (ia != 0) {
            val ir = over(ink ushr 16 and 0xFF, ia)
            val ig = over(ink ushr 8 and 0xFF, ia)
            val ib = over(ink and 0xFF, ia)
            if (ir < r) r = ir
            if (ig < g) g = ig
            if (ib < b) b = ib
        }
        return (LUMA_R * r + LUMA_G * g + LUMA_B * b) shr 8
    }

    /**
     * Whether page pixel ([x], [y]) shows **black** — the flatten through [Dither].
     *
     * [x] and [y] are page coordinates, the same ones both halves count in, which is what
     * makes a pixel dither identically under the nib and after the bake.
     */
    fun black(graphite: Int, liveAlpha: Int, liveColor: Int, ink: Int, x: Int, y: Int): Boolean =
        Dither.black(luma(graphite, liveAlpha, liveColor, ink), x, y)

    /** One channel of an unpremultiplied pixel composited over white paper. */
    private fun over(channel: Int, alpha: Int): Int =
        if (alpha == 255) channel else (channel * alpha + 255 * (255 - alpha)) / 255

    // ── The band kernel: the same answer, a page at a time (2026-09-19) ──────
    //
    // [black] is the right shape for one pixel and the wrong one for two and a half
    // million of them. A whole-page rebuild on a page open measured **494–551 ms** on a
    // Nomad asking it per pixel through `getPixels` bands and `setPixels` — half a second
    // of the artist waiting, twice over, because the host loads graphite and ink as two
    // calls. So there is a bulk form, and the two must give the same picture: [band] is
    // tested against [black] pixel for pixel over a synthetic band of every interesting
    // pixel, which is the only thing that makes "faster" safe to say.
    //
    // What makes it faster is all bookkeeping and no arithmetic: the blue-noise row is
    // fetched once per row instead of once per pixel and turned into the compare's
    // right-hand side there; the gamma curve is a 256-entry table rather than a call; the
    // flatten is written out longhand so nothing is called per pixel; a band with no ink
    // on either layer is filled in one go; and bare paper — which is most of most pages —
    // costs one test of two alpha bytes.

    /**
     * `512 · shade(grey)` for every grey — the left-hand side of [Dither]'s compare,
     * precomputed. A table rather than a call because [Dither.shade] is a `pow` the moment
     * [Dither.DITHER_GAMMA] stops being 1, and a walk that bends the gamma must not also
     * make the page turn slow.
     */
    private val LIMIT = IntArray(256) { 512 * Dither.shade(it) }

    /**
     * Flatten and dither a whole band of the page: `[x0, x0 + w) × [y0, y0 + h)` in page
     * coordinates, into [out] as [inked] where the pixel shows black and [blank] where it
     * shows paper.
     *
     * [graphite] and [ink] are the two page images' pixels over exactly that band, row
     * major, [w] to a row — what `Bitmap.getPixels` leaves. Either may be absent
     * ([hasGraphite] / [hasInk] false), in which case the array is not read at all: an ink
     * layer nothing has landed on is the common case for a pencil page and it should cost
     * nothing, not a page-sized zero-fill.
     *
     * [out] is written at `outOffset + y · outStride + x`, so it may be a band of a
     * page-sized buffer (a whole-page rebuild filling the bitmap's own rows) or a
     * standalone `w × h` block (a rect).
     *
     * There is **no live layer** here, deliberately: this is the display half, where a
     * mark is already in the graphite image. The live half stays on [black] — it works a
     * fleck's rect at a time, where none of this bookkeeping would pay for itself.
     */
    fun band(
        graphite: IntArray,
        hasGraphite: Boolean,
        ink: IntArray,
        hasInk: Boolean,
        x0: Int,
        y0: Int,
        w: Int,
        h: Int,
        out: ByteArray,
        outOffset: Int,
        outStride: Int,
        inked: Byte,
        blank: Byte,
    ) {
        if (w <= 0 || h <= 0) return
        val n = w * h
        // A layer whose pixels are all transparent contributes nothing to any pixel of
        // this band, so say so once rather than per pixel. The scan is a shift and a
        // compare per pixel and it buys the whole flatten.
        val g = hasGraphite && anyInk(graphite, n)
        val k = hasInk && anyInk(ink, n)
        if (!g && !k) {
            for (y in 0 until h) {
                val at = outOffset + y * outStride
                out.fill(blank, at, at + w)
            }
            return
        }
        val limit = LIMIT
        val thresholds = ByteArray(BlueNoise64.SIZE)
        val cut = IntArray(BlueNoise64.SIZE)
        for (y in 0 until h) {
            BlueNoise64.row(y0 + y, thresholds)
            for (j in 0 until BlueNoise64.SIZE) {
                cut[j] = 255 * (2 * (thresholds[j].toInt() and 0xFF) + 1)
            }
            val src = y * w
            val dst = outOffset + y * outStride
            var phase = x0 and (BlueNoise64.SIZE - 1)
            var x = 0
            while (x < w) {
                val gp = if (g) graphite[src + x] else 0
                val kp = if (k) ink[src + x] else 0
                var grey = 255
                if ((gp or kp) ushr 24 != 0) {
                    // White paper, the graphite image over it, the ink image through
                    // DARKEN — [luma]'s own order, written out so nothing is called here.
                    var r = 255
                    var gg = 255
                    var b = 255
                    val ga = gp ushr 24
                    if (ga == 255) {
                        r = gp ushr 16 and 0xFF
                        gg = gp ushr 8 and 0xFF
                        b = gp and 0xFF
                    } else if (ga != 0) {
                        val inv = 255 * (255 - ga)
                        r = ((gp ushr 16 and 0xFF) * ga + inv) / 255
                        gg = ((gp ushr 8 and 0xFF) * ga + inv) / 255
                        b = ((gp and 0xFF) * ga + inv) / 255
                    }
                    val ia = kp ushr 24
                    if (ia != 0) {
                        var ir = kp ushr 16 and 0xFF
                        var ig = kp ushr 8 and 0xFF
                        var ib = kp and 0xFF
                        if (ia != 255) {
                            val inv = 255 * (255 - ia)
                            ir = (ir * ia + inv) / 255
                            ig = (ig * ia + inv) / 255
                            ib = (ib * ia + inv) / 255
                        }
                        if (ir < r) r = ir
                        if (ig < gg) gg = ig
                        if (ib < b) b = ib
                    }
                    grey = (LUMA_R * r + LUMA_G * gg + LUMA_B * b) shr 8
                }
                out[dst + x] = if (limit[grey] < cut[phase]) inked else blank
                x++
                phase = (phase + 1) and (BlueNoise64.SIZE - 1)
            }
        }
    }

    /** Whether any pixel of [px]`[0, n)` has a non-zero alpha — see [band]. */
    private fun anyInk(px: IntArray, n: Int): Boolean {
        for (i in 0 until n) if (px[i] ushr 24 != 0) return true
        return false
    }
}
