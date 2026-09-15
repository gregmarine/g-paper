package com.symmetricalpalmtree.gpaper.core

/**
 * How hard the rubber rubs on a raster page (0.1.30) — the three numbers the host may
 * set and the artist judges by hand.
 *
 * A real eraser lifts graphite off the tooth a little at a time: a light pass softens a
 * line, a few firm passes take it out, and the edge of the rubber lifts less than its
 * middle. [liftLight] and [liftFirm] are the fraction of what is there that one pass
 * takes at pressure 0 and pressure 1, interpolated between; [feather] is the fraction of
 * [PaperView.eraserRadius], measured in from the edge, over which the lift falls from
 * full to nothing — 0 is a hard-edged corridor, 1 fades from the centre line outward.
 *
 * The defaults are the ones the artist chose at the start of the rubbing phase: a
 * quarter at a light touch, three fifths firm, and half the radius feathered.
 */
data class RasterRubbing(
    val liftLight: Float = 0.25f,
    val liftFirm: Float = 0.60f,
    val feather: Float = 0.5f,
) {
    init {
        require(liftLight in 0f..1f && liftFirm in 0f..1f) { "lift fractions must be within 0..1" }
        require(feather in 0f..1f) { "feather must be within 0..1" }
    }
}
