package com.symmetricalpalmtree.gpaper.core.geometry

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A grey, at a place on the page, as **black or white** — an ordered dither against
 * [BlueNoise64] (Phase 28, the third Supernote walk).
 *
 * Why a panel would want this. A 16-grey e-ink waveform reaches black on its **first**
 * frame and a grey only by passing through black and lightening back out of it over the
 * next second or so. So a grey pixel sent to the panel trails the nib and a black one does
 * not — which is what made a grey pencil lag on Supernote's direct path however its flecks
 * were drawn. Supernote's own Atelier never sends the panel a grey for a pencil at all: it
 * **dithers** the stroke into an even pattern of black dots, so a light lead keeps its whole
 * shape and reads as a flat light grey. Thinning the grain instead — fewer flecks for a
 * paler lead — was the wrong answer to the same measurement: it changes the *mark*, and a
 * shade the artist picked came out looking like a fault (*"shade 13 looks like a bug"*).
 * Dithering changes only how the page is **shown**.
 *
 * Which is the whole of this file's job. The stroke keeps its grey, the page image keeps its
 * grey, covers and exports keep their grey; the one thing that changes is what a panel is
 * handed. And because the threshold is **position-keyed and stateless**, two renderers
 * looking at the same grey at the same page pixel always answer the same — which is what
 * lets a live preview painted fleck by fleck and a whole-page repaint after the bake land
 * on the panel identically, with nothing to see at pen-up.
 *
 * Pure Kotlin, no Android imports, JVM-tested.
 */
object Dither {

    /**
     * The one knob: the page's greys are raised to this power before the compare, as
     * `grey' = 255 · (grey / 255)^gamma`.
     *
     * `1.0` is a linear dither — a grey `g` comes out `g/255` white. Above 1 the mid greys
     * darken (more dots), below 1 they lighten. It exists because **Atelier's light shades
     * read darker than linear** on the panel, so the ladder a hand judges may not be the
     * ladder the arithmetic gives; whether ours needs bending is a question for a walk and
     * not something to guess at from a desk. Until a hand says otherwise it is exactly 1,
     * and at exactly 1 the `pow` is skipped, so linear costs nothing.
     */
    const val DITHER_GAMMA: Float = 1.0f

    /**
     * Whether page pixel ([x], [y]) shows black for a page grey of [grey] (0…255, 0 black).
     *
     * The compare is `grey/255 < (threshold + 0.5)/256` in integers — **not** the plain
     * `grey < threshold` an ordered dither is usually written as. The two agree everywhere
     * but the ends, and the ends are what matter here: [BlueNoise64] holds every value
     * 0…255, so under the plain form a page of pure black would come out with sixteen white
     * pixels in every 64 × 64 tile and a black pen's ink would be visibly speckled. Here
     * `grey = 0` is black at every position and `grey = 255` is white at every position, by
     * construction, and a flat grey in between lands within half a percent of `grey/255`
     * white.
     */
    fun black(grey: Int, x: Int, y: Int): Boolean =
        512 * shade(grey) < 255 * (2 * BlueNoise64.threshold(x, y) + 1)

    /** [grey] through [DITHER_GAMMA], clamped to 0…255. Identity while the gamma is 1. */
    fun shade(grey: Int): Int {
        val g = grey.coerceIn(0, 255)
        if (DITHER_GAMMA == 1.0f) return g
        return (255f * (g / 255f).pow(DITHER_GAMMA)).roundToInt().coerceIn(0, 255)
    }
}
