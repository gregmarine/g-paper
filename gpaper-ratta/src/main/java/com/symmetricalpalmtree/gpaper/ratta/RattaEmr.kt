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
 *
 * **The ceiling was a guess and it was wrong (0.1.37).** [EMR_MAX] carried 1200 from the
 * PoC's private `emrSize` through Phase 19, with the reason "the panel gains nothing above
 * it and the daemon lags" — and **nothing above it had ever been armed.** The widest lead
 * anything asked for was 12 px, so 1200 was never a limit anyone met; it was a plausible
 * number in a hairline world. Arc 44 gave the sketch pencil wide leads and the hand walked
 * them on the Nomad (2026-09-17): **16 / 20 / 24 / 32 / 48 / 64 / 96 px all preview at the
 * width they bake, with no lag at any of them** — "all of those wide-lead sizes work".
 * Supernote's own notes app offers widths around 24 px, well inside that. Note that the walk
 * arms sizes well past the ~2400 top of the Needle penSizeArray above and the firmware
 * renders them: that array is the range Ratta's own app *picks from*, never a bound the
 * daemon enforces — which is the same mistake in miniature, and why the floor argument
 * above rests on what a thin line looks like rather than on where the array starts.
 *
 * So the ceiling is now 9600, and it is worth being exact about what that number is:
 * **96 px is the widest lead a hand has walked, not a width the panel was found to refuse.**
 * Nothing above it has been tried, and nothing measured says the daemon minds. If a host
 * ever needs a wider lead, that is another walk — and a walk that raises this number is
 * expected to succeed, which is the opposite of the old ceiling's story.
 */
internal object RattaEmr {

    /**
     * General floor for the firmware EMR pen size. Below roughly this the Needle array
     * paints a sub-pixel line indistinguishable from a firmware path that never armed.
     */
    const val EMR_MIN = 200

    /**
     * Ceiling: 96 px, the widest lead the artist's hand has walked on the Nomad
     * (2026-09-17) — preview and bake agreed at every size up to it and the daemon kept
     * up. It is a ceiling because nothing wider has been *tried*, not because anything
     * refused. See the object KDoc for why the old 1200 was never a measurement.
     */
    const val EMR_MAX = 9600

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
