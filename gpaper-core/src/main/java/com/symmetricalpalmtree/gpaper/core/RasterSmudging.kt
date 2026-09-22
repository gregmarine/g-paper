package com.symmetricalpalmtree.gpaper.core

/**
 * How a finger smudges graphite on a raster page (0.1.54) — the four numbers the host may
 * set and the artist judges by hand.
 *
 * A smudge is not a rub: nothing is meant to come off. The finger moves graphite sideways
 * on the tooth, so a hatch of separate lines runs together into a tone, and a little of it
 * is carried away on the skin. Under the finger each pixel is pulled toward the mean of
 * its neighbourhood — a box [spread] px to either side — by [strength] per **pass** at
 * full coverage, a pass being one stroke of the arm (a reversal of travel starts the
 * next, as it does for the rubber); a rub goes back and forth, so a few passes build up
 * smoothly rather than one doing everything, and how many samples the digitizer sent
 * makes no difference. [feather] is the fraction of [PaperView.smudgeRadius], measured in
 * from the edge, over which the pull fades to nothing, exactly as the rubber's feather
 * is. [loss] is the fraction of what is there that one full-strength pass carries off —
 * the tone paling a little as it spreads, the way a real smudge does; 0 keeps every
 * grain on the page.
 *
 * [gamma] is the **tone** the blend converges to. The pencil lays sparse dark flecks, and
 * the eye reads a hatch by its flecks, not by their average: spread the plain mean of
 * that alpha evenly across the corridor and the page looks rubbed out (the artist's first
 * walk: "it looks like it is removing it"). Real smudged graphite reads *denser* than the
 * hatch it came from — the grains are crushed into the tooth and cover it. So the mean is
 * taken in darkness raised to [gamma] and brought back: 1 is the plain mean (ink
 * conserved exactly), 2 is the root mean square, which a hatch one fifth covered in full
 * flecks settles to at nearly half tone rather than a fifth. Once the corridor is even,
 * its power mean is itself, so further passes change nothing — the smudge converges and
 * never runs away.
 */
data class RasterSmudging(
    val strength: Float = 0.45f,
    val spread: Int = 6,
    val feather: Float = 0.5f,
    val loss: Float = 0.02f,
    val gamma: Float = 2f,
) {
    init {
        require(strength in 0f..1f) { "strength must be within 0..1" }
        require(spread in 1..64) { "spread must be within 1..64 px" }
        require(feather in 0f..1f) { "feather must be within 0..1" }
        require(loss in 0f..1f) { "loss must be within 0..1" }
        require(gamma in 1f..4f) { "gamma must be within 1..4" }
    }
}
