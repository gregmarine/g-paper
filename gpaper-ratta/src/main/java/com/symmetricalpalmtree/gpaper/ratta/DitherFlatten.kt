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
 * operator: white paper, the graphite image with this contact's flecks over it, and the ink
 * image with this contact's ink over *it*, the ink laid **over** the graphite (`SRC_OVER`,
 * Phase 30 — ink is on top, the way gel ink sits on graphite; until 0.1.43 the two met
 * through `DARKEN`). The display half simply passes live alphas of zero, because by then the
 * mark is in the image it belongs to.
 *
 * **Since Phase 29 the bake is here too** ([srcOver]). The live layer is composited into
 * the page image with the very arithmetic the live flatten applied to it, so the mirror is
 * a property of one function rather than of two that happen to agree: what the panel was
 * painted with under the nib is, pixel for pixel, what the page now holds.
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

    /**
     * The grey (0…255) a page pixel shows, with a live layer over **each** image: the
     * graphite side is the graphite image plus [liveGraphite] flecks in [graphiteColor],
     * the ink side is the ink image plus [liveInk] in [inkColor], and the ink side goes
     * **over** the graphite side. See the class KDoc for the order.
     *
     * Both live alphas are `0` for the display half, where whatever was live has been
     * baked into the image beside it, and at most one of them is ever non-zero under the
     * pen: one contact is one tool and one layer (Phase 29).
     */
    fun luma(
        graphite: Int,
        liveGraphite: Int,
        graphiteColor: Int,
        ink: Int,
        liveInk: Int,
        inkColor: Int,
    ): Int = luma(
        srcOver(graphite, graphiteColor, liveGraphite),
        srcOver(ink, inkColor, liveInk),
    )

    /**
     * The grey (0…255) the two page images alone show: white paper, the graphite image
     * over it, the ink image **over that** (`SRC_OVER` — Phase 30; `DARKEN` before it).
     *
     * **This is the only flatten there is.** The live half above does not have a second
     * one: it composites its live layer into the page pixel with [srcOver] — the very call
     * the bake makes into the page image — and then asks this. So "what the panel is
     * painted with under the nib" and "what the window shows once the mark has baked" are
     * not two formulas that agree, they are one formula applied to one number, and the
     * mirror is exact by construction rather than within a rounding. (It was two, until
     * Phase 29: a live blend beside an over-white blend, agreeing to within a part in 255,
     * which is a dot flipped wherever that part fell across a dither threshold.)
     */
    fun luma(graphite: Int, ink: Int): Int {
        var r = 255
        var g = 255
        var b = 255
        val ga = graphite ushr 24
        if (ga != 0) {
            r = over(graphite ushr 16 and 0xFF, ga)
            g = over(graphite ushr 8 and 0xFF, ga)
            b = over(graphite and 0xFF, ga)
        }
        val ia = ink ushr 24
        if (ia == 255) {
            r = ink ushr 16 and 0xFF
            g = ink ushr 8 and 0xFF
            b = ink and 0xFF
        } else if (ia != 0) {
            val inv = 255 - ia
            r = ((ink ushr 16 and 0xFF) * ia + r * inv) / 255
            g = ((ink ushr 8 and 0xFF) * ia + g * inv) / 255
            b = ((ink and 0xFF) * ia + b * inv) / 255
        }
        return (LUMA_R * r + LUMA_G * g + LUMA_B * b) shr 8
    }

    /** The grey a page pixel shows with a live layer on the **graphite** side only — the
     *  pencil's own call, and what every caller meant before the pen went direct. */
    fun luma(graphite: Int, liveAlpha: Int, liveColor: Int, ink: Int): Int =
        luma(graphite, liveAlpha, liveColor, ink, 0, 0)

    /**
     * Whether page pixel ([x], [y]) shows **black** — the flatten through [Dither], with a
     * live layer over each image (see the six-argument [luma]).
     *
     * [x] and [y] are page coordinates, the same ones both halves count in, which is what
     * makes a pixel dither identically under the nib and after the bake.
     */
    fun black(
        graphite: Int,
        liveGraphite: Int,
        graphiteColor: Int,
        ink: Int,
        liveInk: Int,
        inkColor: Int,
        x: Int,
        y: Int,
    ): Boolean = Dither.black(luma(graphite, liveGraphite, graphiteColor, ink, liveInk, inkColor), x, y)

    /** [black] with a live layer on the **graphite** side only. */
    fun black(graphite: Int, liveAlpha: Int, liveColor: Int, ink: Int, x: Int, y: Int): Boolean =
        black(graphite, liveAlpha, liveColor, ink, 0, 0, x, y)

    /**
     * What page pixel ([x], [y]) **shows**, as black coverage 0…255 — the one display rule
     * since Phase 31 (0.1.45), for the panel under the nib and for the window alike:
     *
     * - **Baked ink shows its true tone.** A pixel the ink image covers (any alpha) and no
     *   live ink is on answers `255 − luma` — the panel can hold sixteen greys and a gel
     *   pen's line reads better solid than as dots (the user's ask: *"can we have it rebake
     *   with the true tone of the pen?"*). Where such a pixel's ink is only partly opaque
     *   (an anti-aliased edge) the tone is of the ink over whatever graphite is under it.
     * - **Everything else dithers** — bare graphite, and **live** ink under the nib —
     *   answering `255` or `0` through [Dither], exactly as [black] does. Live ink stays a
     *   dither on purpose: the panel's waveform reaches a grey only through black, so a
     *   grey painted live trails the nib while black dots land at once. The pen-up bake
     *   re-presents the mark's runs through this same rule, and they land in tone.
     *
     * The graphite is never shown in tone: a pencil's grain is dots already, and a
     * fleck's alpha through the panel's tone table would be a smear where the dither is
     * grain. The two halves of one page therefore differ on purpose — dots under the
     * pencil, tone under the pen — which is what the user saw on Atelier and asked for.
     */
    fun coverage(
        graphite: Int,
        liveGraphite: Int,
        graphiteColor: Int,
        ink: Int,
        liveInk: Int,
        inkColor: Int,
        x: Int,
        y: Int,
    ): Int {
        val g = srcOver(graphite, graphiteColor, liveGraphite)
        val k = srcOver(ink, inkColor, liveInk)
        val grey = luma(g, k)
        if (liveInk == 0 && (k ushr 24) != 0) return 255 - grey
        return if (Dither.black(grey, x, y)) 255 else 0
    }

    /** One channel of an unpremultiplied pixel composited over white paper. */
    private fun over(channel: Int, alpha: Int): Int =
        if (alpha == 255) channel else (channel * alpha + 255 * (255 - alpha)) / 255

    /**
     * [src]'s colour at [srcAlpha] composited `SRC_OVER` onto [dst] — unpremultiplied
     * ARGB in, unpremultiplied ARGB out, which is what `Bitmap.getPixels` gives and
     * `setPixels` takes.
     *
     * **This is the bake on the direct path** (Phase 29). The live layer is one alpha byte
     * a pixel and the mark's colour is one colour, so laying the mark into the page image
     * is this, once per pixel the mark touched — no second `GraphiteGrain`, no second
     * `drawPoints`, no `Canvas` at all. It lives here, beside the live flatten it has to
     * agree with: [luma]'s live half composites the same colour at the same alpha over the
     * same page pixel, so what the panel was painted under the nib and what the window
     * shows afterwards are the same arithmetic on the same numbers, and the mirror is a
     * property of one function rather than of two that happen to match today.
     *
     * A `Canvas` works in premultiplied pixels and may round a channel a hair differently;
     * that difference is a device question and always was (see the class KDoc).
     */
    fun srcOver(dst: Int, src: Int, srcAlpha: Int): Int {
        if (srcAlpha <= 0) return dst
        if (srcAlpha >= 255) return 0xFF000000.toInt() or (src and 0xFFFFFF)
        val da = dst ushr 24
        val outA = srcAlpha + da * (255 - srcAlpha) / 255
        if (outA == 0) return 0
        var out = outA shl 24
        var shift = 0
        while (shift <= 16) {
            val s = src ushr shift and 0xFF
            val d = dst ushr shift and 0xFF
            // Premultiplied add, backed out by the result's alpha — the composite a Canvas
            // performs, in integers.
            val num = s * srcAlpha * 255 + d * da * (255 - srcAlpha)
            val c = (num / (255 * outA)).coerceIn(0, 255)
            out = out or (c shl shift)
            shift += 8
        }
        return out
    }

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
     * coordinates, into [out] as [inked] where the pixel dithers black, [blank] where it
     * dithers paper, and — since Phase 31 — the pixel's **black coverage** (`255 − luma`)
     * where the ink image covers it, so a gel pen's line shows in its true tone
     * ([coverage]'s rule; [inked] is expected to be `0xFF` and [blank] `0` so the three
     * agree as one scale).
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
                    // White paper, the graphite image over it, the ink image OVER that —
                    // [luma]'s own order, written out so nothing is called here.
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
                    if (ia == 255) {
                        r = kp ushr 16 and 0xFF
                        gg = kp ushr 8 and 0xFF
                        b = kp and 0xFF
                    } else if (ia != 0) {
                        val inv = 255 - ia
                        r = ((kp ushr 16 and 0xFF) * ia + r * inv) / 255
                        gg = ((kp ushr 8 and 0xFF) * ia + gg * inv) / 255
                        b = ((kp and 0xFF) * ia + b * inv) / 255
                    }
                    grey = (LUMA_R * r + LUMA_G * gg + LUMA_B * b) shr 8
                }
                out[dst + x] =
                    if (kp ushr 24 != 0) (255 - grey).toByte()
                    else if (limit[grey] < cut[phase]) inked else blank
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
