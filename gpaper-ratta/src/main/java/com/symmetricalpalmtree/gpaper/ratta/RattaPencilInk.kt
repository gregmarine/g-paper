package com.symmetricalpalmtree.gpaper.ratta

import com.symmetricalpalmtree.gpaper.core.canvas.PencilInk
import kotlin.math.pow

/**
 * What a pencil shade means on the panel painted directly: **a density of black flecks**
 * (Phase 28, 0.1.41, the second Nomad walk).
 *
 * The first walk shipped the shade as a colour — the lead's own grey, opaque, every fleck the
 * same. It read as laggy for anything but black, and worse the paler the lead: *"shade 0
 * perfect, 5 laggy, 9 more, 13 starts black then switches."* Reading the panel's own frame
 * back while Atelier drew said why. **Every pixel Atelier hands the panel for a light shade is
 * level 0, pure black** — 3401 stroke pixels on a light-grey stroke and not one grey among
 * them. Atelier's sixteen shades are not sixteen inks; they are sixteen *densities* of the one
 * ink. Which follows from the physics the probe had already measured: the 16-grey waveform
 * passes **through** black on its way to a grey, so a grey pixel lands black and lightens over
 * the next second or so, while a black pixel is simply there. A grey fleck cannot help
 * trailing the nib. A black one cannot trail it.
 *
 * So on this path the lead's colour is not painted at all. It is read, and answered with how
 * much black to lay ([PencilInk.density]) — live preview and bake alike, because the two must
 * be the same mark and the bake is what the preview is mirroring at pen-up. The
 * [com.symmetricalpalmtree.gpaper.core.model.Stroke] the host stores keeps the shade the
 * artist picked; this is a rendering decision belonging to one panel, and BOOX, the generic
 * engine and Paintsprout are untouched.
 *
 * Pure Kotlin — no Android imports, JVM-tested.
 */
internal object RattaPencilInk {

    /**
     * At or above this luma the lead is **white** — a lightener rather than a density.
     *
     * The white lead pales the graphite under it (Phase 27, 0.1.40) and lays nothing on bare
     * paper, so there is no scatter to thin: it goes down at full density in white, and white
     * lands as fast as black does. 224 is the compositor's own top band
     * ([RattaPanelTone]: 224–255 all map to level 15), so it is the border between "a grey the
     * panel can still tell from paper" and "paper".
     */
    private const val WHITE_MIN_LUMA = 224f

    /**
     * The fitted density curve's amount, reference luma and exponent —
     * `density = 1 − [FIT_AMOUNT] · (luma / [FIT_REF_LUMA])^[FIT_POWER]`.
     *
     * **Fitted to Atelier's own HB pencil, read off a Nomad's panel frame, 2026-09-19**
     * (`probe-ebc/README.md`, "Atelier read back"): one stroke per shade of its sixteen-step
     * greyscale palette, black panel pixels counted per pixel of stroke length and taken
     * relative to the black lead's 2.67. The fourteen measured points, luma → relative
     * density: 80→0.82 · 96→0.77 · 104→0.72 · 112→0.69 · 128→0.63 · 136→0.58 · 144→0.56 ·
     * 160→0.48 · 170→0.42 · 182→0.37 · 192→0.32 · 200→0.27 · 208→0.25 · 221→0.15. The curve
     * sits within a few percent of every one of them.
     *
     * It is a fit to *Atelier's* pencil, not to ours — the two lay their flecks differently
     * ([com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain] is our own grain), so what
     * is borrowed here is the *shape of the ladder*, how much paler each shade is than the one
     * below it, which is what the hand is actually judging when it picks a shade. Whether our
     * mark at 0.51 looks like the artist's idea of a `#999999` lead is a question for a walk.
     */
    private const val FIT_AMOUNT = 0.85f
    private const val FIT_REF_LUMA = 221f
    private const val FIT_POWER = 1.5f

    /**
     * The palest a lead may be laid, as a fraction of a black one.
     *
     * The curve reaches it at luma 221 (`#DDDDDD`, Atelier's palest non-white shade) and every
     * grey past that is white's business. A floor at all because a density that keeps falling
     * eventually lays nothing — and a lead that draws *nothing* reads as a broken pen, not as
     * a pale one. 0.15 is where the measurement ended, not a limit anything has been pushed
     * against.
     */
    private const val MIN_DENSITY = 0.15f

    /** Opaque white, the lightener lead's ink. */
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Opaque black — every other lead's ink, whatever shade was asked for. */
    private const val BLACK = 0xFF000000.toInt()

    /**
     * How a lead of colour [argb] lays graphite on the panel: white at full density if it is
     * the lightener, otherwise black at the density its luma asks for.
     *
     * Flecks are **opaque** either way: an anti-aliased edge is a ring of light greys, and on
     * this panel that ring trails the nib exactly as a grey fleck would.
     */
    fun of(argb: Int): PencilInk {
        val luma = RattaInkMap.luma(argb)
        if (luma >= WHITE_MIN_LUMA) return PencilInk(WHITE, opaque = true, density = 1f)
        val density = 1f - FIT_AMOUNT * (luma / FIT_REF_LUMA).pow(FIT_POWER)
        return PencilInk(BLACK, opaque = true, density = density.coerceIn(MIN_DENSITY, 1f))
    }
}
