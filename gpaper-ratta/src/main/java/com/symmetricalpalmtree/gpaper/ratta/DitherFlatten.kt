package com.symmetricalpalmtree.gpaper.ratta

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
}
