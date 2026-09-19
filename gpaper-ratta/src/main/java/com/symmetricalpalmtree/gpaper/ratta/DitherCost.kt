package com.symmetricalpalmtree.gpaper.ratta

/**
 * Which way a dither rebuild should land its bytes in the `ALPHA_8` display image — the
 * one arithmetic decision in `RattaPaperView.regenDither`, kept pure so the rule can be
 * stated by a JVM test rather than guessed at from a page turn (2026-09-19 maintenance).
 *
 * There are two ways to get bytes into that bitmap and they scale differently.
 * `copyPixelsFromBuffer` copies the **whole** bitmap in one memcpy — a few milliseconds
 * on a page, whatever the rect cost to flatten. `setPixels` takes a sub-rect but wants
 * `Int`s, so a rect pays an expansion loop plus Skia taking one alpha byte out of every
 * four; measured through NSE · Sketch, that path runs several times the per-pixel cost of
 * the buffer copy, and it is where a pen-up of one long mark spent **848 ms** on a Nomad.
 *
 * So: a small rect keeps `setPixels`, because a whole-page memcpy to land a stroke's
 * bounds would be pure waste; a large one takes the copy, because past some fraction of
 * the page the fixed memcpy is cheaper than the per-pixel expansion. The fraction is the
 * caller's (`DITHER_WHOLE_COPY_FRACTION`) and this object never names one — the number is
 * a judgement about two devices, the rule is not.
 */
internal object DitherCost {

    /**
     * Whether a rebuild of a [rectW] × [rectH] rect of a [pageW] × [pageH] page should
     * land through one whole-bitmap copy rather than a `setPixels` of just the rect.
     *
     * True from [fraction] of the page's area upward, and always for a rect that covers
     * the page (a whole-page rebuild is the case this path was written for). False for an
     * empty rect or an unsized page — there is nothing to land.
     */
    fun preferWholeCopy(
        rectW: Int,
        rectH: Int,
        pageW: Int,
        pageH: Int,
        fraction: Float,
    ): Boolean {
        if (rectW <= 0 || rectH <= 0 || pageW <= 0 || pageH <= 0) return false
        val rect = rectW.toLong() * rectH.toLong()
        val page = pageW.toLong() * pageH.toLong()
        if (rect >= page) return true
        return rect.toDouble() >= page.toDouble() * fraction
    }
}
