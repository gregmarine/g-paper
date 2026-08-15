package com.symmetricalpalmtree.gpaper.ratta

/**
 * Armed pen colour (ARGB) → the nearest of the four colour codes the Supernote firmware
 * pen accepts. Pure Kotlin (bit ops, no Android imports) so the thresholds are
 * JVM-testable.
 *
 * This mapping styles ONLY the live firmware overlay: the greyscale panel dithers any
 * colour to a grey whose tone tracks luminance, so the job is to pick the firmware grey
 * closest to the tone the baked stroke will render at — that is what makes the pen-lift
 * handoff (live overlay → baked polyline) invisible. The baked [ …core.model.Stroke.color]
 * always keeps its true ARGB value; a page written on a Supernote opens in full colour
 * elsewhere.
 *
 * Thresholds are midpoints between the tones the four codes actually RENDER at,
 * calibrated by eye on both devices (identical Nomad/Manta): the codes paint far
 * lighter than named — DARK_GRAY ≈ `#AAAAAA` (luma ~170), GRAY ≈ `#CCCCCC` (~204),
 * LIGHT_GRAY ≈ `#F0F0F0` (~240, near-invisible — only near-white ink maps there, which
 * is consistent: near-white baked ink is equally invisible on paper). The panel renders
 * 3 usable live shades; **do not revisit the thresholds** — shifting them only
 * misaligns live from baked to fake variety the panel cannot render.
 */
internal object RattaInkMap {

    /** Ceiling for [SupernoteInk.Color.BLACK] — midpoint of black (0) and DARK_GRAY's ~170. */
    private const val BLACK_MAX_LUMA = 85f

    /** Ceiling for [SupernoteInk.Color.DARK_GRAY] — midpoint of ~170 and GRAY's ~204. */
    private const val DARK_GRAY_MAX_LUMA = 187f

    /** Ceiling for [SupernoteInk.Color.GRAY] (~204 vs LIGHT_GRAY's ~240); above → LIGHT_GRAY. */
    private const val GRAY_MAX_LUMA = 222f

    /** Rec. 601 luma of an ARGB colour (alpha ignored — e-ink has no compositing to honor it). */
    internal fun luma(argb: Int): Float {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /**
     * The firmware colour code whose rendered grey best matches what the panel will
     * show for a baked stroke of colour [argb].
     */
    fun firmwareColorFor(argb: Int): Int {
        val luma = luma(argb)
        return when {
            luma <= BLACK_MAX_LUMA -> SupernoteInk.Color.BLACK
            luma <= DARK_GRAY_MAX_LUMA -> SupernoteInk.Color.DARK_GRAY
            luma <= GRAY_MAX_LUMA -> SupernoteInk.Color.GRAY
            else -> SupernoteInk.Color.LIGHT_GRAY
        }
    }
}
