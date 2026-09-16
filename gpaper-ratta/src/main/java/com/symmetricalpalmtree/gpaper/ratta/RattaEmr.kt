package com.symmetricalpalmtree.gpaper.ratta

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle

/**
 * px → firmware EMR pen size (the PoC formula `width * 100`), clamped to what the
 * panel will actually render. Pure Kotlin (no Android imports) so the floors and the
 * ceiling are pinned by a JVM test rather than by a hand on a Nomad.
 *
 * The clamp is not cosmetic. The Needle penSizeArray runs ~200…2400, and an EMR near 0
 * paints an invisible sub-pixel line that reads exactly like a dead firmware path — so
 * a floor is what keeps a thin pen from looking like a broken session.
 *
 * **The hairline floor (0.1.32).** `PENCIL` on a raster page is a 1.2 px lead: at the
 * general floor of [EMR_MIN] the firmware previews the artist's hairline as a 2 px
 * needle line, and the mark visibly *narrows* at pen-up when the software bake lays the
 * real 1.2 px of graphite. A preview that lies about width is the serious failure —
 * width is what the hand aims with, and the artist reads the collapse as the *bake*
 * being broken (`CLAUDE.md`, learned twice on BOOX). So `PENCIL` gets its own lower
 * floor, [EMR_MIN_HAIRLINE].
 *
 * **120 is measured, not guessed (Nomad, 2026-09-15, the artist's hand).** It was
 * switched between 120 / 150 / 200 on the running device against the baked hairline and
 * 120 is what the eye settled on: the preview is the width the bake turns out to be. The
 * door that switched it closed at 0.1.34 — re-opening the question means another walk,
 * not another knob.
 */
internal object RattaEmr {

    /**
     * General floor for the firmware EMR pen size. Below roughly this the Needle array
     * paints a sub-pixel line indistinguishable from a firmware path that never armed.
     */
    const val EMR_MIN = 200

    /** Ceiling — the panel gains nothing above it and the daemon lags. */
    const val EMR_MAX = 1200

    /**
     * Floor for `PENCIL` only: the hairline lead's preview may go thinner than the
     * general floor because the alternative is a preview that is wrong about width.
     * Measured on the Nomad (see the object KDoc).
     */
    const val EMR_MIN_HAIRLINE = 120

    /**
     * The EMR size to arm for a [style] mark of [widthPx] px: `px * 100` clamped to the
     * style's floor ([EMR_MIN_HAIRLINE] for `PENCIL`, [EMR_MIN] for everything else) and
     * the panel's ceiling.
     */
    fun penSize(style: StrokeStyle, widthPx: Float): Int {
        val min = if (style == StrokeStyle.PENCIL) EMR_MIN_HAIRLINE else EMR_MIN
        return (widthPx * 100f).toInt().coerceIn(min, EMR_MAX)
    }
}
