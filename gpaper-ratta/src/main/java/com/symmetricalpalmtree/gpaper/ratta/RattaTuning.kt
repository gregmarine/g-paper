package com.symmetricalpalmtree.gpaper.ratta

/**
 * **A measurement door, not host API** (0.1.32).
 *
 * The four Supernote raster-page numbers arc 43 "Sketch" had to settle by hand on a Nomad
 * — the raster eraser's redraw cadence, the pencil's EMR floor, its live preview tone, and
 * the pressure its bake gives up to match that preview. They are mutable properties so a
 * walk switches candidates with `setprop` and a restart instead of a rebuild per
 * candidate; a rebuild per candidate is how a judgement of *feel* gets made against a
 * stale memory of the previous one.
 *
 * **Measured 2026-09-15 on the Nomad, the artist's hand: the defaults below are the
 * measurements.** The two doors that were answered with a "no" — a pressure-sensitive
 * pencil preview, and the eraser-pressure log — are gone rather than left lying about; the
 * four that carry a number stay open for arc 43's later walks (K5/K6, on a real page
 * rather than the demo's), and **this object is removed at the arc's close (K8)**, each
 * value freezing into a constant in [RattaEmr] / [RattaPaperView].
 *
 * **Hosts leave every value at its default.** This is not a configuration surface and it
 * carries no compatibility promise. Nothing in `gpaper-core`'s host-facing surface changed
 * to make it possible: the cadence and the bake pressure are `protected open` seams a
 * device engine overrides.
 *
 * Read on the main thread at pen-arming, at each erase batch and at each bake; set them
 * before the paper view is created (the demo does it in `onCreate` from system properties)
 * or flip a tool afterwards so the firmware is re-armed.
 */
object RattaTuning {

    /**
     * The value of [rasterEraseRedrawIntervalMs] meaning "present the sweep once, at its
     * end" — the same sentinel the core seam reads
     * (`CanvasPaperView.RASTER_ERASE_REDRAW_END_ONLY`, private there because nothing but
     * an engine's own override may name a cadence).
     */
    const val RASTER_ERASE_REDRAW_END_ONLY: Long = Long.MAX_VALUE

    /**
     * How often the raster eraser redraws mid-sweep on Supernote, in ms —
     * [RASTER_ERASE_REDRAW_END_ONLY] for not at all.
     *
     * **Measured 2026-09-15: 16 ms, one frame — the same number Onyx uses, arrived at for
     * a different reason.** 100 ms was good (113 frames / 20 % janky), 60 ms better (162 /
     * 22 %), and 16 ms the artist's clear choice — *"I really like the 16 ms… this eraser
     * works better on Ratta hardware than it does on Onyx"* — at 756 frames / 82 % janky
     * over the minute. **The frame count is far worse and the hand says it is best**, which
     * is only a contradiction if the frames were the thing being judged. The
     * frame-silence rule's cost is the *masking* an overlay imposes on frames presented
     * under it, and an erase contact releases the overlay at ACTION_DOWN — so there is no
     * accumulating cost here, only the panel's own update, which the Nomad keeps up with.
     * 250 ms and end-only were not walked: there was no reason to go slower once 16 won.
     */
    @JvmStatic
    @Volatile
    var rasterEraseRedrawIntervalMs: Long = 16L

    /**
     * The firmware EMR floor for `PENCIL` live ink. **Measured 2026-09-15: 120 reads
     * right** — the preview is the width the bake turns out to be. See [RattaEmr] for why
     * the hairline gets a floor of its own at all.
     */
    @JvmStatic
    @Volatile
    var pencilEmrMin: Int = RattaEmr.EMR_MIN_HAIRLINE

    /**
     * The four firmware greys a live preview can be armed as, named for
     * [pencilPreviewGrey]. They render far lighter than their names (see [RattaInkMap]):
     * roughly `#000000` / `#AAAAAA` / `#CCCCCC` / `#F0F0F0`, three usable shades and one
     * near-invisible.
     */
    object Grey {
        const val BLACK: Int = SupernoteInk.Color.BLACK
        const val DARK: Int = SupernoteInk.Color.DARK_GRAY
        const val GRAY: Int = SupernoteInk.Color.GRAY
        const val LIGHT: Int = SupernoteInk.Color.LIGHT_GRAY
    }

    /** The name of a [Grey] level, for a status line or a log. */
    @JvmStatic
    fun greyName(code: Int): String = when (code) {
        Grey.BLACK -> "black"
        Grey.DARK -> "dark"
        Grey.GRAY -> "gray"
        Grey.LIGHT -> "light"
        else -> "?$code"
    }

    /**
     * Which firmware grey `PENCIL`'s live preview is armed as, one of [Grey] — rather
     * than the grey [RattaInkMap] would pick for the ink's true colour (`#505050` maps to
     * BLACK, which is what this exists to escape).
     *
     * **Measured 2026-09-15: [Grey.DARK].** BLACK was too dark, DARK_GRAY an improvement,
     * GRAY tried; but no rung of the ladder could fix it alone, because the firmware
     * paints one tone per armed pen and a soft touch cannot preview softer. DARK_GRAY
     * together with a constant bake ([pencilBakePressure] 0.5) is what the artist called
     * *"spot on"*, on the panel and in a Mac screencap at 3×.
     *
     * `RattaInkMap`'s thresholds are untouched: a `PENCIL`-only exception to the mapping,
     * not a shift in it, and every other style still takes the grey nearest its colour.
     */
    @JvmStatic
    @Volatile
    var pencilPreviewGrey: Int = Grey.DARK

    /**
     * The constant pressure `PENCIL` **bakes** at on a raster page, or `null` for the
     * pressure the digitizer actually reported. **Measured 2026-09-15: 0.5.**
     *
     * **Why the bake gives way and not the preview.** The Supernote firmware paints one
     * tone per armed pen. Neither DARK_GRAY nor GRAY could track a soft touch — a lightly
     * drawn line previewed far too dark at every rung of the ladder — and arming the
     * pressure-sensitive pen code changed nothing, because those codes vary width, not
     * tone. There is nothing on the preview's side left to vary. So on this engine the
     * pencil gives up its tonal range instead: live ink and baked ink agree, and the mark
     * on the panel is the mark that was drawn. The bake was never wrong — it was right
     * about a tone the panel could not show while the pen was down.
     *
     * **Ratta only.** BOOX and Paintsprout keep the pressure pencil: their preview can
     * carry tone, so there is nothing to give up. Stroke mode is untouched on every
     * engine — the pressures a host persists are the ones that were measured.
     */
    @JvmStatic
    @Volatile
    var pencilBakePressure: Float? = 0.5f
}
