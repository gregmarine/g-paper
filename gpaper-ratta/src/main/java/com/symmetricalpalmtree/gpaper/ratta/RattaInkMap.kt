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
 *
 * Two ladders live here, not one. [firmwareColorFor] is the ladder above, for styles
 * that bake a solid line. [pencilPreviewFor] (0.1.36) is `PENCIL`'s own, with its own
 * thresholds, because graphite bakes as flecks at a constant pressure and reads lighter
 * than its nominal colour — a different question, answered separately rather than by
 * bending the first one.
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

    // ── The PENCIL preview's own ladder (0.1.36) ─────────────────────────────
    //
    // A second ladder, not a shift in the first one. [firmwareColorFor] answers the
    // question "which firmware grey renders nearest the colour this stroke will bake
    // at", and for every style that lays a solid line that is the right question. The
    // pencil does not lay a solid line: it bakes as a scatter of flecks with bare paper
    // between them, and on this engine it bakes at a constant pressure 0.5 and upright
    // (`RattaPaperView.PENCIL_BAKE_PRESSURE` / `bakeTilt`), so what lands on the page
    // reads markedly lighter than the nominal colour a solid line of it would. Run
    // through the nearest-grey ladder a `#505050` lead asks for BLACK — which is what
    // arc 43 discovered the hard way, and why a `PENCIL`-only exception existed at all
    // before this ladder replaced it. So the pencil's preview is offset pale-ward, and
    // it stops one rung short at the top.

    /**
     * Ceiling for [SupernoteInk.Color.BLACK] on the pencil ladder — the midpoint
     * between shade levels 2 (`#222222`, luma 34) and 3 (`#333333`, luma 51). The
     * starting value, and the Nomad walk of 2026-09-17 let it stand: levels 0–2 agreed
     * with their bake as BLACK and level 3 as DARK_GRAY.
     */
    private const val PENCIL_BLACK_MAX_LUMA = 42.5f

    /**
     * Ceiling for [SupernoteInk.Color.DARK_GRAY] on the pencil ladder — the midpoint
     * between shade levels 6 (`#666666`, luma 102) and 7 (`#777777`, luma 119).
     * **Measured on the Nomad, 2026-09-17**: the first guess was 161.5 (between levels
     * 9 and 10), and the artist's hand found levels 7–9 previewing darker than they
     * baked; at 110.5 they preview GRAY and agree. It sits far dark-ward of
     * [DARK_GRAY_MAX_LUMA] (187) — that gap is the measure of how much paler graphite
     * bakes than a solid line of the same colour.
     */
    private const val PENCIL_DARK_GRAY_MAX_LUMA = 110.5f

    /**
     * Ceiling for [SupernoteInk.Color.GRAY] on the pencil ladder — the midpoint between shade
     * level 14 (`#EEEEEE`, luma 238) and white (luma 255), so **only a white lead** reaches
     * LIGHT_GRAY (Phase 27, 0.1.40). Every pale grey the hand rejected LIGHT_GRAY for on the
     * 2026-09-17 walk (levels 12–14) still previews GRAY; white is the one lead whose bake is
     * paler than every panel tone, so a near-invisible trail is the honest preview of it.
     */
    private const val PENCIL_GRAY_MAX_LUMA = 246.5f

    /**
     * The firmware colour code `PENCIL`'s **live preview** is armed as for a lead of
     * colour [argb] — the nearest *usable* firmware tone, which is not the same thing
     * as the nearest tone.
     *
     * **Why the pencil needs a ladder of its own.** [firmwareColorFor] is calibrated so
     * the live overlay and the baked stroke read alike at pen-lift, and it assumes the
     * bake lays a solid line of the colour it was given. Graphite does not: it lands as
     * flecks with bare paper between them, and on this engine at a constant pressure
     * 0.5 and with no lean, so the mark reads lighter than its nominal colour. Feeding
     * a pencil through the solid-line ladder therefore previews it too dark at every
     * shade — arc 43's `#505050` lead asked for BLACK, which is the mismatch the
     * (now removed) single `PENCIL_PREVIEW_GREY` constant existed to escape. This is a
     * `PENCIL`-only ladder beside that one, **not** a revision of it: [firmwareColorFor]
     * and its three thresholds are untouched and stay "do not revisit".
     *
     * **Why one constant is no longer enough.** DARK_GRAY was the measured answer on the
     * Nomad (2026-09-15) for the one shade the sketch pencil had — BLACK was too dark for
     * `#505050`, GRAY was tried, and the pair of DARK_GRAY with the 0.5 bake was the
     * artist's *"spot on"*. It was one constant because there was one lead. NSE · Sketch
     * now offers fifteen (`#000000 … #EEEEEE` in `0x11` steps, level *n* = grey
     * `n × 0x11`), so a black lead and a pale one would preview identically, and the
     * preview would be lying about the one thing it is for. The firmware still paints
     * exactly one tone per arming — that has not changed and cannot be worked around —
     * so what a ladder buys is agreement *between* shades, not within one.
     *
     * **LIGHT_GRAY only for white.** That code renders around `#F0F0F0` (luma ~240) and is
     * near-invisible on the panel. For a solid line that is consistent — near-white baked
     * ink is equally invisible — but a pencil is drawn *to be watched while it is drawn*,
     * and a pale grey lead must still show a line under the hand even if the bake is faint.
     * So every grey on the ladder tops out at GRAY. The one exception (Phase 27, 0.1.40) is
     * a **white** lead: it lays nothing on bare paper and exists to lighten graphite already
     * there (flecks go down over the raster, so white ones pale what is under them), and a
     * GRAY trail that vanished at pen-lift would preview the opposite of what it does. It
     * takes LIGHT_GRAY — the faintest tone the panel has, which is the truthful one.
     *
     * **The rungs, as the hand settled them (Nomad, 2026-09-17).** Levels 0–2 → BLACK,
     * 3–6 → DARK_GRAY, 7–14 → GRAY, white → LIGHT_GRAY, with the thresholds at the
     * midpoints between adjacent shades so a level never sits on a boundary. DARK_GRAY
     * stays on the default `#555555` (level 5) and on arc 43's `#505050`, so the pairing
     * Phase 19 settled is unchanged. The walk also reported levels 12–14 previewing
     * darker as GRAY than they bake, and **LIGHT_GRAY was trialled for them and
     * rejected by the same hand** — the line could not be followed while it was drawn —
     * so the pale end's mismatch is the accepted cost of a preview that can be seen, and
     * "never LIGHT_GRAY for a grey" is a measurement as well as an argument.
     */
    fun pencilPreviewFor(argb: Int): Int {
        val luma = luma(argb)
        return when {
            luma <= PENCIL_BLACK_MAX_LUMA -> SupernoteInk.Color.BLACK
            luma <= PENCIL_DARK_GRAY_MAX_LUMA -> SupernoteInk.Color.DARK_GRAY
            luma <= PENCIL_GRAY_MAX_LUMA -> SupernoteInk.Color.GRAY
            else -> SupernoteInk.Color.LIGHT_GRAY
        }
    }
}
