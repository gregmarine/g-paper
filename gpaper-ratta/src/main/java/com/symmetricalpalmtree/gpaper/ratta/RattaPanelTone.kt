package com.symmetricalpalmtree.gpaper.ratta

import kotlin.math.roundToInt

/**
 * The compositor's grey → 4-bit panel level (Phase 28). Pure Kotlin, no Android imports.
 *
 * **This table is not a guess and not a formula.** It was read back out of the driver's own
 * frames: a card of known greys composed into the window, then frame 2 (the shadow of what
 * was displayed) sampled to see which level each grey had become. It came out **identical on
 * the Nomad and the Manta**, so it belongs to the firmware rather than to a panel, and it is
 * not linear anywhere — the steps are 12, 12, 8, 12, 12, 8, 12, 16, 20, 8, 8, 12, 8, 32 wide,
 * and **level 9 is never produced at all** (168–187 lands on 10).
 *
 * Why it mattered that the mapping is *this* one: the live pencil paints levels straight into
 * the panel, and a moment later the compositor rewrites the same pixels from the window,
 * unasked. If the window's grey maps to the level that was painted, that rewrite is a no-op
 * on the panel and the mark simply stays. If it maps one level off, every mark quietly shifts
 * tone a beat after it is drawn — which reads as the ink settling, and is the exact failure
 * the direct path exists to avoid.
 *
 * **No live pixel goes through this table any more** (Phase 28, the third walk). The panel is
 * now sent a *dither* of the page — black or white and nothing between, both of which land on
 * its first frame — and the window is drawn the same way, so the two agree by arithmetic
 * ([DitherFlatten]) rather than by a table. Black and white are also the two greys this table
 * maps without argument, so nothing it says has been contradicted.
 *
 * It stays because it is a **measurement**, and the only record of one: what the compositor
 * does to a grey is a fact about this firmware that cost a read-back of the driver's own
 * frames on two devices to learn, and the next question asked of this panel will want it.
 */
internal object RattaPanelTone {

    /** Number of levels the panel takes: `0x00` black … `0x0f` white. */
    const val LEVELS = 16

    /**
     * Upper bound of each Android grey band, in order — band `i` is `bounds[i-1]+1 .. bounds[i]`
     * and maps to [LEVEL_OF_BAND] `[i]`.
     */
    private val BAND_MAX = intArrayOf(75, 87, 99, 107, 119, 131, 139, 151, 167, 187, 195, 203, 215, 223, 255)

    /** The level each band maps to. Note the gap: 9 is skipped, 187 → 10. */
    private val LEVEL_OF_BAND = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15)

    /** The 4-bit panel level the compositor turns Android grey [grey] (0…255) into. */
    fun level(grey: Int): Int {
        val g = grey.coerceIn(0, 255)
        for (i in BAND_MAX.indices) {
            if (g <= BAND_MAX[i]) return LEVEL_OF_BAND[i]
        }
        return LEVEL_OF_BAND[LEVEL_OF_BAND.size - 1]
    }

    /**
     * The panel level for a colour already composited over white — Rec. 601 luma (the same
     * measure [RattaInkMap.luma] takes, for the same reason: the greyscale panel renders a
     * colour at the tone of its luminance) through [level].
     *
     * The caller flattens first: white paper, then graphite, then the live flecks, then ink
     * over all of it — exactly what `drawCommittedContent` will draw a moment later. Alpha
     * is not read here; a pixel handed over half-composited would be a bug in the caller.
     *
     * Rounded, never truncated: the three luma weights sum to one, so a plain grey should
     * come back as itself, and in float arithmetic it comes back as itself minus a hair.
     * Truncating that hair drops a boundary grey (188 → 187.99998 → 187) a whole level, and
     * the bands are exactly where a level changes.
     */
    fun levelOf(argbOverWhite: Int): Int = level(RattaInkMap.luma(argbOverWhite).roundToInt())
}
