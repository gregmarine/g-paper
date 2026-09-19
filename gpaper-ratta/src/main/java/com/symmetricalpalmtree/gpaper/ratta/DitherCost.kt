package com.symmetricalpalmtree.gpaper.ratta

/**
 * Which way a dither rebuild should land its bytes in the `ALPHA_8` display image — the
 * one arithmetic decision in `RattaPaperView`'s rebuilds, for a single rect and for a
 * mark's runs landed together, kept pure so the rule can be stated by a JVM test rather
 * than guessed at from a page turn (2026-09-19 maintenance).
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

    /**
     * The same question for **one mark's runs landed together**: [runCount] rects whose
     * union is [unionW] × [unionH]. True when they should land through one whole-bitmap
     * copy rather than a `setPixels` each.
     *
     * Two ways to earn the copy, because a batch has two ways to be expensive. It can be
     * *large* — the union from [fraction] of the page upward, which is
     * [preferWholeCopy]'s own rule and wants the memcpy for the same reason a big rect
     * does. Or it can be *many*: `setPixels` costs something fixed per call whatever the
     * rect's size, and past [maxRects] of them the one page copy is simply cheaper than
     * the calls alone. That second half is what a large pencil scribble is — up to
     * sixty-four runs, none of them big, **651 ms** of pen-up on a Nomad with not one
     * rect slow enough to log.
     *
     * Note what this never touches: how much of the page gets *flattened*. That stays the
     * runs' own area, never the union's — the arithmetic per pixel is the dear part and a
     * mark must not start paying for the white space its diagonal spans. This is only
     * about how the bytes already worked out reach the bitmap.
     *
     * False for no runs and for an unsized page: there is nothing to land.
     */
    fun preferWholeCopyForRuns(
        runCount: Int,
        unionW: Int,
        unionH: Int,
        pageW: Int,
        pageH: Int,
        fraction: Float,
        maxRects: Int,
    ): Boolean {
        if (runCount <= 0 || pageW <= 0 || pageH <= 0) return false
        if (runCount > maxRects) return true
        return preferWholeCopy(unionW, unionH, pageW, pageH, fraction)
    }
}
