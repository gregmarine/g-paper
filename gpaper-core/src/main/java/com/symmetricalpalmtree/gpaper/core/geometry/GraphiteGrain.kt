package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where the graphite lands.
 *
 * A pencil does not lay down a line. It drags a lump of soft mineral across a sheet whose
 * surface is a field of tiny peaks and grooves, and the mineral only ever reaches the peaks
 * — which is why a real pencil mark, looked at closely, is a scatter of dark flecks with
 * bare paper showing between them, and why leaning harder makes a mark *darker* by filling
 * in more of the grooves rather than by staining the paper more deeply. This object is that
 * scatter: given a path and how hard the hand pressed along it, it says which specks of
 * tooth caught graphite and how much each one caught.
 *
 * The three constants that decide *how much* graphite lands — [EDGE_BARE], [SKATE_DEPTH] and
 * the fleck size — were set by photographing three strokes live on a NoteAir5C's panel and again after
 * they baked, then comparing total ink per unit length. The two covered the same width; the bake
 * was laying down about 30% less inside it. They were raised together, by a factor flat across the
 * whole pressure range, so the light-to-hard response the artist had already approved did not move.
 *
 * **The texture is spatial, not tonal, and that is the whole point on e-ink.** A panel with
 * a handful of grey levels will dither any continuous grey we hand it, inventing a texture
 * of its own on top of ours; a mark already built out of black flecks and white paper needs
 * no dithering and survives the quantization intact. It also means the pencil carries its
 * own tooth, which matters because the paper it draws on may be plain white with no surface
 * model behind it at all.
 *
 * **The sheet is in here too.** The tooth is not only a pitch: it has peaks and hollows in
 * patches, the same patches under every stroke, and the mark is mottled by them — see
 * [TOOTH_CELL_PX]. Without it a mark is an even spray of independent flecks, which is static,
 * not paper.
 *
 * **Pure Kotlin, no Android.** That is not tidiness — it is what lets the determinism below
 * be *proved* by a JVM test rather than asserted, and what lets a texture fault be reproduced
 * and measured offline (dump `of` from a JVM test, render it, histogram it) without a panel.
 * The renderer's job is only to put ink where this says.
 *
 * ## Determinism, and why it is the hard constraint
 *
 * A host reloading a page re-renders every committed stroke from scratch. If the grain came
 * from a running RNG, every reopen would reshuffle every mark on the sheet — a drawing that
 * changes behind the artist's back, which is worse than a drawing that looks wrong, because
 * it cannot be worked with. So every random-looking quantity here comes from an integer hash
 * of (`seed`, station, lane): same seed and same points in, same flecks out, on any device,
 * in any process, in any order. There is no state between calls.
 *
 * The stations are placed at fixed multiples of arc length **measured from the first point**,
 * never at input-point indices. That is what makes a stroke still being drawn agree with the
 * same stroke once it is committed: pen samples arrive at whatever rate the hand and the
 * digitizer agree on, but the first 40 mm of a path is the first 40 mm of it whether four
 * more samples have arrived or four hundred.
 *
 * ## The prefix invariant (Phase 28)
 *
 * **A prefix's flecks are the first N of the whole stroke's** — same coordinates, same
 * darknesses, same order — which is what lets a live preview lay each fleck *once* and never
 * move it again. `of(points.take(k), width, seed, prefix = true)` is an exact ordered prefix
 * of `of(points, width, seed)` minus the whole's end cap, for every `k`.
 *
 * Two things were needed for that, and both are here rather than in the caller. [prefix]
 * omits the **end cap**, because the dome at the lifting end is the tip of the lead and
 * travels with the pen. And nothing may be decided from further ahead than the first
 * [SEED_WINDOW_PX] of travel: the arrival trim already looked exactly that far
 * (`2 × LANDING_TRIM_PX`), and the two filter seeds now look no further either — see
 * [SEED_WINDOW_PX]. Until a prefix carries that much settled path, prefix mode returns
 * nothing at all, because what it would lay is not yet decidable and a fleck laid in the
 * wrong place cannot be taken back off a panel that was painted directly.
 *
 * ## Tilt widens the mark, where the engine can supply it
 *
 * A real pencil laid over on its side draws with the flank of the lead instead of its point, and
 * the mark gets dramatically broader — it is how anyone shades. So tilt drives **width** here and
 * pressure drives **darkness**, which is the same division of labour Paintsprout's Wacom app
 * arrived at against real pencils.
 *
 * The curve is not invented, and it is deliberately *not* the Wacom app's. It was fitted on a BOOX
 * NoteAir5C, against the artist's eye, at three angles the digitizer reported as 9°, 44° and 75° —
 * roughly 1×, 4.9× and 10.9× wide. It blooms **earlier** than the Wacom pencil's profile, which
 * stays thin until the pen is nearly flat; matching this panel mattered more than matching the
 * sibling app, because there the live ink is drawn by the device's own firmware and the bake by this
 * file, and a preview that disagrees with what commits is worse than either being slightly wrong.
 *
 * And the flank deposits **lighter**, not just broader: the same graphite spread over a wider band
 * leaves less of itself on any one peak, which is why shading with the side of a pencil comes out
 * grey however hard you lean on it.
 *
 * **A renderer here must still look right at `tilt = 0`, and always will.** Engines report zero
 * whenever they cannot honestly supply an angle — on BOOX that is every model nobody has measured,
 * because the SDK has no `getMaxTilt()` and a five-device survey found the raw numbers on wildly
 * different scales. Zero simply means a pencil held upright, which is a pencil, so the degradation
 * is a fixed-width mark rather than a broken one.
 */
object GraphiteGrain {

    /**
     * The paper's tooth pitch in px: how far apart the peaks sit. **A property of the sheet,
     * not of the lead** — a broad pencil crossing this paper meets more peaks, it does not
     * meet bigger ones — so a wider stroke gets more lanes of flecks at this same spacing
     * rather than a scaled-up version of the same pattern. Fixed px rather than anything
     * physical because core knows nothing about screen density; a host on a coarser panel
     * gets a proportionally coarser tooth, which is the right way round.
     */
    const val TOOTH_PITCH_PX: Float = 0.8f

    /**
     * Diameter of one fleck of graphite in px, at the palest darkness and at the darkest.
     *
     * **A fleck wider than the lattice that spaces it cannot help but touch its neighbours**, and
     * once flecks touch they stop being specks and become chains — little worms a fleck thick and
     * several long. At half a millimetre on a 300 dpi panel that reads unmistakably as bristle, and
     * an artist called it a pipe cleaner. It is the same failure Paintsprout's Wacom app has from
     * the other direction, where the grain is drawn as continuous lanes running along the stroke:
     * both put graphite down as *connected geometry*, and connected geometry looks like hair.
     *
     * So the fleck is sized against [TOOTH_PITCH_PX] rather than fixed. At the pale end it is about
     * one pitch — specks that mostly stand alone, which is what the panel's own charcoal looks like
     * under magnification, essentially a one-pixel dither. At the dark end it is over two, so the
     * flecks flood together and a hard-pressed line is solid rather than a grey mesh. Between the
     * two the chains that do form are ~1 px thick, which is under the eye's reach at this density.
     *
     * It carries pressure as well as coverage, and that is deliberate: coverage saturates once the
     * tooth is full, so past that point a growing fleck is the only thing left to darken with.
     *
     * **A fleck is never wider than the lead that lays it** — see [fleckPx] with a width. On any
     * lead of ordinary size this changes nothing: the darkest fleck is 1.6 px and a lead is several
     * times that. It exists for the hairline. A 1.2 px lead whose darkest flecks are 1.6 px bakes
     * at more than twice the width of the live line it was previewed as (0.1.24, rendered offline
     * before it reached a panel), and a preview that lies about width is the one lie that matters.
     * The floor stays at [FLECK_MIN_PX] so a lead below it still gets grain rather than dust.
     */
    const val FLECK_MIN_PX: Float = 0.75f
    const val FLECK_MAX_PX: Float = 1.6f

    /**
     * How many darknesses a fleck may have. Tone here still comes chiefly from *how many* flecks
     * land — the darkness ramp is support under the pale end, keeping a barely-touched stroke from
     * being a scatter of pure black dots, rather than the mechanism.
     *
     * Three at first, on the argument that more levels would quietly turn a spatial texture back
     * into a tonal one. Raised to six when the artist reported the pressure ramp stepping where the
     * panel's own ink graded smoothly: coverage saturates once the tooth is full, so above that
     * point the darkness ramp is the *only* thing left carrying pressure, and three steps cannot
     * carry it. The flecks' own scatter dithers across the extra levels, so this reads as a smoother
     * gradient rather than as more bands.
     */
    const val LEVELS: Int = 6

    /** Alpha multiplier of the palest fleck; the darkest is always 1. */
    private const val LEVEL_FLOOR = 0.45f

    /** Coverage at the lightest touch and at the hardest — the fraction of peaks that catch. */
    private const val COVER_MIN = 0.14f
    private const val COVER_MAX = 1.0f

    /**
     * How much coverage the rim of the mark loses relative to its core, and how sharply.
     * A pencil tip is round: its centre bears on the paper and its edge merely grazes it, so
     * a mark thins out toward both sides instead of ending at a wall.
     *
     * Softened from 0.55 after photographing the same three strokes live on the panel and again
     * after the bake: the two covered the **same width**, and the bake simply had less graphite
     * inside it. Most of the shortfall was here — a mark that gives up half its coverage at the
     * rim spends a lot of its width on almost nothing.
     */
    private const val EDGE_BARE = 0.32f
    private const val EDGE_POW = 1.6f

    /**
     * The skate. A lead riding over tooth catches and lifts in runs longer than a single
     * peak, which is what gives a pencil line its along-the-stroke streakiness; without it a
     * mark is evenly speckled and reads as spray. [SKATE_LEN_PX] is the length of one such
     * run, [SKATE_WIDTH_PX] how broad it is, and [SKATE_DEPTH] how much coverage it can steal
     * at its lightest.
     *
     * **A run is a streak, not a band.** The skate was first a function of arc alone, so it lifted
     * and dropped a whole cross-section at once — on a fine lead that *is* a streak, because the
     * lead is narrower than a run is long; on a 96 px lead it is a bar across the mark every 26 px,
     * and a mark made of bars across it looks manufactured. Real graphite streaks *along* the
     * stroke: a facet of the lead, a groove in the sheet, each a few pixels wide. So the field is
     * two-dimensional, long along the travel and short across it.
     */
    private const val SKATE_LEN_PX = 26f
    private const val SKATE_WIDTH_PX = 5f
    private const val SKATE_DEPTH = 0.16f

    /**
     * Pressure curve. BOOX digitizers saturate — a firm stroke pins at the ceiling and stays
     * there — so the usable band sits below maximum and a linear map spends most of its range
     * on presses the hand cannot tell apart. The gamma pushes resolution down into the light
     * and middle where drawing actually happens.
     */
    private const val PRESSURE_GAMMA = 0.75f

    /**
     * Fleck displacement from its lattice site, as a fraction of the pitch — in **both** directions.
     *
     * At 0.62 the lattice showed through. Each station's comb of lanes was slid sideways as one
     * rigid unit, which broke the along-the-stroke chains, but the comb itself stayed a comb: lanes
     * at an exact pitch, flecks wider than that pitch, and each fleck free to wander only a quarter
     * of a pixel. So every cross-section was a short solid line *across* the mark. On a 5 px lead
     * there is nothing to see — the line is three flecks long. On a 96 px lead it is a hundred
     * flecks long and the mark is a stack of them: the artist called it "a series of tiny lines"
     * rather than graphite, and it was. A full cell of freedom in both axes leaves no direction for
     * a fleck to line up along.
     */
    private const val JITTER = 1f

    /**
     * Extra along-the-stroke freedom a fleck gets per px of its distance from the centre line.
     *
     * A cross-section is a rigid comb turned to the travelled direction, and the travelled
     * direction wobbles — a couple of degrees after smoothing, as [TANGENT_SMOOTH_PX] says. Two
     * degrees at the centre line is nothing. Two degrees on a fleck 48 px out is 1.7 px of
     * movement *along* the stroke, twice the station pitch: consecutive combs pile onto each other
     * there and part again a few stations later. Measured on a 96 px lead, the centre of the mark
     * was evenly laid and the rim came in bunches with a ~4 px period, every bunch a short bar
     * across the mark — the "series of tiny lines".
     *
     * Smoothing the direction harder would need a window of a thousand px to hold that lever arm
     * still, which would drag every curve. So the flecks are let loose instead, by an amount that
     * grows with the lever arm that shakes them: a fleck at the rim may land a few px ahead of or
     * behind its station, which is more than the wobble can move it, and the bunching averages
     * out. A fleck at the centre keeps its cell. The flank of a real lead deposits with exactly this
     * looseness — the mark's edge is its least precise part.
     */
    private const val LEVER_JITTER = 0.08f

    /**
     * The sheet's tooth, as a field over the page: where its peaks and hollows are.
     *
     * Every constant above decides *how much* graphite lands. None of them decided *where*, beyond
     * the lattice — each site caught or not on its own coin toss, independent of its neighbours,
     * and a mark built that way is white noise: an even spray that reads as static, not as a lead
     * dragged over paper. Real tooth is not independent site to site. The hollows and peaks of a
     * sheet come in patches a few tenths of a millimetre across — the fibres, the calendering —
     * and a mark laid over them is mottled at that scale, darker where the lead rode a ridge and
     * bare in the hollows beside it. And because the tooth belongs to the *sheet*, two strokes over
     * the same spot share the same hollows: a crossing stays bare where the paper is bare.
     *
     * So the field lives in **page** coordinates, seeded by a constant rather than the stroke —
     * one sheet under everything drawn on it — and a site's catch is a blend of its own coin toss
     * and the tooth height under it, [TOOTH_WEIGHT] deciding how much the sheet gets to say.
     * Two octaves, [TOOTH_CELL_PX] and three times that: fibre and patch. At full coverage the
     * field is overruled and the mark goes solid, which is what a hard press does to any paper.
     */
    private const val TOOTH_CELL_PX = 3.5f
    private const val TOOTH_WEIGHT = 0.55f
    private const val TOOTH_SEED = 0x5ee7

    /** A stroke narrower than this still gets one lane of flecks rather than none. */
    private const val MIN_WIDTH_PX = 1f

    /**
     * How far the lean is averaged over, in px of arc length, before it is allowed to set a width.
     *
     * **A digitizer's tilt reading is noisy and the pen's actual angle is not.** A hand cannot roll
     * a pencil several degrees in a fraction of a millimetre, but the reading does exactly that —
     * one measured stroke on a NoteAir5C swung 65.7° to 85.6° along its length, which through the
     * width curve is a 9× to 13× swing in how broad the mark should be. Fed in raw, that becomes
     * *geometry*: the mark's edges ripple at the sample rate and the stroke grows a fringe of fine
     * hairs down both sides. An artist called it a pipe cleaner, and reported the same look in
     * Paintsprout's Wacom app — which drives its pencil width from raw tilt too. Different
     * renderers, one shared mistake: **noise that becomes shape has to be smoothed; noise that
     * becomes tone does not.**
     *
     * Which is why pressure is deliberately left raw. It sets darkness, and darkness noise reads as
     * grain — it is doing the same job the tooth is.
     *
     * The average is **causal**, over what has already been drawn and never over what comes next.
     * A centred window would give a point one answer while the pen is still travelling and a
     * different one once the stroke is finished, so the mark would re-shape itself at pen-up and
     * again on every reload. Roughly 3 mm at this panel's density — far shorter than a deliberate
     * roll of the wrist, far longer than the jitter.
     */
    private const val TILT_SMOOTH_PX = 40f

    /**
     * How far the lean is averaged over before it decides **darkness** — much further than before
     * it decides width.
     *
     * Tilt drives two things in opposite directions: laying the pen over makes a mark broader and
     * paler, bringing it upright makes it narrower and denser. Read from the same instant, those
     * compound — a moment of near-upright inside a laid-over stroke comes out ten times narrower
     * *and* nearly twice as dark, which is a hard black nub, and touch-down is exactly where the
     * pen is most likely to be caught upright. Real graphite does that, but only in the shape of
     * the mark; the paleness of side-of-lead shading is a property of how the lead is being *held*,
     * not of a single sample.
     *
     * So darkness follows the lean the hand has settled into rather than its every flicker. A
     * stroke drawn flat throughout is still paler than one drawn upright throughout — the effect
     * the artist asked for is untouched — but a stroke does not flash dark where the pen happens
     * to pass through vertical. Long enough that touching down cannot move it, short enough that a
     * deliberate roll across a long stroke still lightens as it goes.
     */
    private const val COVER_SMOOTH_PX = 150f

    /**
     * How far the *direction of travel* is averaged over, in px of arc, before a cross-section is
     * laid perpendicular to it.
     *
     * This is the same lesson as [TILT_SMOOTH_PX] and it bites far harder, because the error is
     * multiplied by the width of the mark. A cross-section is drawn across the pen's direction, and
     * taking that direction from one adjacent pair of samples measures the *jitter* rather than the
     * travel: at 2 px sample spacing, a third of a pixel of digitizer noise swings the computed
     * angle with a standard deviation of ~14°, ranging past ±35°. Each comb of flecks is then
     * rotated by that much, and on a lead laid over — half a width of 80-odd px — a 30° error throws
     * its flecks tens of pixels out of line. The mark grows bristles radiating from a core, and it
     * is unmistakably a pipe cleaner.
     *
     * It survives any amount of work on the grain itself, because the grain was never wrong: the
     * *frame it is laid in* was. It hides at high magnification, where one pixel of a bristle looks
     * like ordinary speckle, and is obvious at life size. Paintsprout's Wacom app builds its mesh
     * normals the same way and has always looked the same.
     *
     * Causal, like the lean, so a prefix still renders like the whole stroke. Averaging the
     * direction over ~10 px of arc cuts the angular noise to a couple of degrees; the price is that
     * the cross-section trails the path slightly through a tight curve, which is a far smaller error
     * than the one it removes.
     */
    private const val TANGENT_SMOOTH_PX = 10f

    /**
     * How far into a stroke to look for the pen still arriving, in px of arc.
     *
     * **A pen landing is not a mark.** It touches, the hand settles, and the recorded path takes a
     * small excursion — out and back, or a little loop — before the stroke sets off. On a fine lead
     * nobody would ever see it. On a lead laid over, ten times broader, the mark **folds across
     * itself** there, and graphite laid twice on the same paper composites to solid black: a knot at
     * the start of every broad stroke, shaped like a Y where the mark crosses back over its own
     * edge. It is neither the grain nor the cap, which is why work on both left it untouched.
     *
     * It cannot be filtered away either. Damping the path was tried and reverted: a plain average
     * lags, which shortens every stroke and pulls its end cap inside the mark, and a trend term that
     * cancels the lag makes the filter *track* the excursion rather than absorb it. A filter can lag
     * or it can damp a sustained excursion — not both.
     *
     * So the arrival is dropped instead of smoothed. Inside this window the last sample at which the
     * pen was travelling **against** the direction the stroke turned out to go is found, and the mark
     * starts after it: whatever the hand did while landing is discarded, and everything from the
     * moment the stroke committed is kept exactly. A clean touch-down never travels backwards, so it
     * trims nothing at all.
     */
    private const val LANDING_TRIM_PX = 25f

    /**
     * How far ahead of a stroke's start anything here is allowed to look, in px of arc —
     * `2 × LANDING_TRIM_PX`, the reach the arrival trim already needed.
     *
     * The running filters are causal by construction ([TILT_SMOOTH_PX], [COVER_SMOOTH_PX],
     * [TANGENT_SMOOTH_PX] all walk forward with the stations), but their **seeds** were not:
     * each is the mean over the first window px of travel, and the darkness seed's window is
     * 150 px. A seed read from 150 px ahead is a lookahead like any other — it makes the
     * opening of a mark depend on path the pen has not travelled yet, so the same stroke
     * renders one way while it is being drawn and another once it is finished. That never
     * showed while every renderer drew the whole stroke at once; Phase 28's live panel
     * preview draws prefixes, and a fleck already on the panel cannot be moved.
     *
     * So both seeds are capped here, in **both** modes — a prefix and a whole stroke must not
     * take different paths through this file, or the invariant is a property of the caller
     * rather than of the grain. The cost is that the darkness filter starts from the mean lean
     * over the first 50 px instead of the first 150: a slightly different opening on strokes
     * whose grip changes early, invisible on any stroke drawn at one angle, and nothing at all
     * past the first filter window. (Phase 28. The alternative was a preview that lays nothing
     * for the first 150 px of every stroke — a centimetre of dead hand on a 300 ppi panel.)
     */
    private const val SEED_WINDOW_PX = LANDING_TRIM_PX * 2f

    /**
     * Below this lean the mark does not widen at all. A pencil held "upright" is never at zero —
     * a hand deliberately holding one vertical measured a mean of 9° — and a mark that visibly
     * breathed with the last few degrees of an ordinary grip would read as instability rather than
     * as tilt.
     */
    private const val TILT_UPRIGHT_DEG = 9f

    /** Flat on the paper. Past this the lead is not drawing with its flank, it is lying down. */
    private const val TILT_FLAT_DEG = 90f

    /**
     * How much broader the flank of the lead is than its point, and how the two blend.
     *
     * Refitted against the artist's eye on a NoteAir5C: **1× at 9°, ≈4.9× at 44°, ≈10.9× at 75°.**
     * The first fit came from estimating the firmware's live widths and landed at half of this —
     * upright was already right, so the correction doubled the tilted end and left the origin
     * pinned, which is why the exponent moved too rather than the gain alone. Held upright, the
     * lead draws exactly the width it was set to; that anchor is not negotiable, because it is the
     * one the artist chose from the tin.
     */
    private const val TILT_GAIN = 13.4f
    private const val TILT_POW = 1.46f

    /**
     * How much lighter the flank of the lead deposits than its point, at full lean.
     *
     * The same graphite spread over a broader band leaves less of itself on any given peak, which
     * is why shading with the side of a pencil comes out grey rather than black however hard you
     * lean. Not the full `1/width` the naive reading suggests — a laid-over lead also puts far more
     * of its surface on the paper, so there is more graphite available to give. The figure comes
     * from Paintsprout's Wacom app, which judged it against real pencils.
     *
     * It reduces **coverage**, not fleck darkness, because that is the whole premise of this file:
     * tone comes from how many specks of tooth catch, not from how grey each one is.
     */
    private const val TILT_LIGHTEN = 0.45f

    /**
     * Upper bound on flecks for one stroke. A mark long enough or broad enough to pass this
     * has already stopped being legible as grain, and the cap is here so a host that hands us
     * an absurd width or a path with a million points degrades instead of stalling the frame.
     */
    private const val MAX_FLECKS = 400_000

    /**
     * One stroke's worth of graphite: [count] flecks, their centres interleaved in [xy]
     * (`x0, y0, x1, y1, …`) and their darkness index in [level] (`0` palest,
     * `LEVELS - 1` darkest). Two arrays rather than a list of objects because a page reload
     * re-renders every stroke on the sheet and per-fleck allocation is how that becomes
     * visible.
     */
    class Grain(val xy: FloatArray, val level: IntArray, val count: Int)

    private val EMPTY = Grain(FloatArray(0), IntArray(0), 0)

    /** Fleck diameter in px for darkness index [level] — see [FLECK_MIN_PX]. */
    fun fleckPx(level: Int): Float =
        if (LEVELS <= 1) FLECK_MAX_PX
        else FLECK_MIN_PX + (FLECK_MAX_PX - FLECK_MIN_PX) *
            (level.coerceIn(0, LEVELS - 1).toFloat() / (LEVELS - 1))

    /**
     * Fleck diameter in px for darkness index [level] on a lead [width] px wide: [fleckPx], capped
     * at the lead's own width so a hairline bakes as a hairline, and floored at [FLECK_MIN_PX] so
     * it still bakes as graphite. Renderers should use this one; the cap only bites below 1.6 px.
     */
    fun fleckPx(level: Int, width: Float): Float =
        min(fleckPx(level), max(width, FLECK_MIN_PX))

    /** Alpha multiplier for darkness index [level]; `LEVEL_FLOOR` at 0, 1 at the top. */
    fun levelAlpha(level: Int): Float =
        if (LEVELS <= 1) 1f
        else LEVEL_FLOOR + (1f - LEVEL_FLOOR) * (level.coerceIn(0, LEVELS - 1).toFloat() / (LEVELS - 1))

    /**
     * The graphite [points] deposited at [width] px, seeded by [seed] (the stroke's stable
     * id — see the determinism note above). A single point is a tap: the lead touched down
     * and lifted, leaving a disc of grit rather than a line.
     *
     * With [prefix] set, [points] is a stroke **still under the pen** and what comes back is
     * an exact ordered prefix of what the finished stroke will produce: no end cap, and
     * nothing at all until the path has settled past [SEED_WINDOW_PX] (see the prefix
     * invariant above). A caller drawing successive prefixes therefore draws each fleck once,
     * from `count` of the previous call to `count` of this one, and never has to take one
     * back — which is the whole point on a panel painted directly.
     *
     * [density] (`0`..`1`, clamped) thins the mark: it scales the **coverage** every site is
     * tested against, and nothing else. Not the levels, not the fleck sizes, not where the
     * stations fall — so a paler mark is the same mark with fewer of its flecks, and because
     * [catches] weighs a site's own fixed toss against that coverage, **the flecks at density
     * `d` are an ordered subset of the flecks at density `1`**: the same specks, in the same
     * places, at the same darknesses, with some of them left out. (Below [MAX_FLECKS], which
     * a thinned mark reaches later than a full one if it reaches it at all.)
     *
     * That is what a shade *is* on a panel whose greys arrive late. A 16-grey e-ink waveform
     * lands black on its first frame and reaches a grey only by passing through black, so a
     * pale grey fleck trails the nib while a black one is simply there; a pencil that asks
     * for a lighter lead by laying **fewer black flecks** can be previewed truthfully and a
     * pencil that asks for it by laying paler ones cannot (Phase 28, the second Nomad walk:
     * every pixel Atelier hands the panel for a light shade is level 0, pure black). Engines
     * whose preview can carry tone pass `1` and grade the flecks by alpha as they always have.
     */
    fun of(
        points: List<StrokePoint>,
        width: Float,
        seed: Int,
        prefix: Boolean = false,
        density: Float = 1f,
    ): Grain {
        if (points.isEmpty()) return EMPTY
        val d = density.coerceIn(0f, 1f)
        val base = (if (width < MIN_WIDTH_PX) MIN_WIDTH_PX else width) / 2f
        if (points.size == 1) return if (prefix) EMPTY else tap(points[0], base, seed, d)
        return sweep(points, base, seed, prefix, d)
    }

    /**
     * How much of the lead is meeting the paper, as a multiple of its point, at [tilt] radians
     * from vertical. `1` upright; a lead laid right over draws several times broader.
     *
     * Measured per station rather than per stroke, because a hand rolls the pen over *during* a
     * shading stroke and the mark has to broaden with it — a single tilt taken at pen-down would
     * make every stroke uniform and lose the exact gesture this exists to render.
     */
    fun widthFactor(tilt: Float): Float = 1f + TILT_GAIN * lean(tilt).pow(TILT_POW)

    /**
     * How much of the graphite still lands, as a fraction, at [tilt] radians from vertical.
     * `1` upright; a lead laid right over leaves a paler mark for the same press.
     */
    fun coverageFactor(tilt: Float): Float = 1f - TILT_LIGHTEN * lean(tilt)

    /**
     * The lean to start a filter at: the mean over the first [window] px of travel, so there is no
     * transient to climb out of. Weighted by arc length rather than by sample, because a pen
     * that slows down delivers many samples over very little paper and would otherwise dominate.
     */
    private fun seedLean(points: List<StrokePoint>, from: Int, window: Float): Float {
        var reach = 0f
        var weighted = 0f
        var i = from + 1
        while (i < points.size && reach < window) {
            val a = points[i - 1]
            val b = points[i]
            val step = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
            if (step > 0f) {
                weighted += (a.tilt + b.tilt) * 0.5f * step
                reach += step
            }
            i++
        }
        return if (reach > 0f) weighted / reach else points[from].tilt
    }

    /** How far past upright the pen is leaned, `0`..`1`. */
    private fun lean(tilt: Float): Float {
        val degrees = Math.toDegrees(tilt.toDouble()).toFloat()
        if (degrees <= TILT_UPRIGHT_DEG) return 0f
        return ((degrees - TILT_UPRIGHT_DEG) / (TILT_FLAT_DEG - TILT_UPRIGHT_DEG)).coerceIn(0f, 1f)
    }

    // ── The mark ─────────────────────────────────────────────────────────────

    /**
     * The dome a round tip leaves where it touches down and lifts.
     *
     * A stroke that simply stops at its last cross-section ends in a straight cut clean across the
     * mark, corners and all — a chisel, not a pencil. Nothing in a pencil is straight: the lead
     * meets the paper as a patch, and the ink ends in the shape of that patch.
     *
     * **A cap's strips get narrow, and narrow strips need their density corrected or the outline
     * draws itself.** [laneCount] adds one lane so that a mark thinner than a single tooth still
     * gets grain at all — harmless in the body, where lanes number in the dozens, and badly wrong
     * here: as the cap closes, that one extra lane doubles or triples the candidates in a strip
     * only a tooth or two wide. Every station then over-deposits, and because their outermost lanes
     * sit on the cap's edge by construction, the excess accumulates along the outline as a dark
     * arc — a bead of ink drawn round the end of the stroke. [laneDensity] cancels it by asking for
     * coverage per unit of area rather than per lane.
     *
     * The cap also stops while its strips are still a tooth wide rather than chasing them to
     * nothing. The last fraction of a millimetre of a dome is invisible; a column of near-degenerate
     * strips at the very tip is not.
     *
     * [sign] is `+1` to cap the finish and `-1` to cap the start; [station0] seeds the hashing away
     * from the body's own stations so a cap never repeats a cross-section that is already there.
     */
    private fun cap(
        out: Sink,
        cx: Float,
        cy: Float,
        tx: Float,
        ty: Float,
        pressure: Float,
        lean: Float,
        arc: Float,
        half: Float,
        seed: Int,
        station0: Int,
        sign: Float,
        density: Float,
    ) {
        if (half <= TOOTH_PITCH_PX) return
        var d = TOOTH_PITCH_PX
        var k = 0
        while (d < half) {
            val shrunk = sqrt(half * half - d * d)
            if (shrunk >= TOOTH_PITCH_PX) {
                val lanes = laneCount(shrunk)
                deposit(
                    out = out,
                    cx = cx + sign * tx * d,
                    cy = cy + sign * ty * d,
                    tx = tx,
                    ty = ty,
                    pressure = pressure,
                    lean = lean * laneDensity(shrunk, lanes),
                    arc = arc + sign * d,
                    station = station0 + sign.toInt() * k,
                    lanes = lanes,
                    half = shrunk,
                    seed = seed,
                    density = density,
                )
            }
            d += TOOTH_PITCH_PX
            k++
            if (out.total >= MAX_FLECKS) return
        }
    }

    /**
     * What a strip's coverage must be multiplied by so that its graphite lands at the same rate per
     * unit of paper as the body's does, whatever [laneCount] rounded its lane count up to. `1` for
     * any strip wide enough that the rounding does not matter, which is all of the body.
     */
    private fun laneDensity(half: Float, lanes: Int): Float {
        if (lanes <= 1) return 1f
        val exact = 2f * half / TOOTH_PITCH_PX
        return (exact / lanes).coerceIn(0f, 1f)
    }

    /**
     * Unit direction of the chord from `points[from]` to the sample [window] px of arc later —
     * the direction a stretch of path is *going*, rather than what one pair of samples measured.
     * Returns `0, 0` for a path with no length. Writes into [into] to avoid an allocation per call.
     */
    private fun chordDirection(
        points: List<StrokePoint>,
        from: Int,
        window: Float,
        into: FloatArray,
    ) {
        into[0] = 0f
        into[1] = 0f
        if (from >= points.size - 1) return
        var reach = 0f
        var i = from + 1
        while (i < points.size - 1 && reach < window) {
            val a = points[i - 1]
            val b = points[i]
            reach += sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
            i++
        }
        val first = points[from]
        val ahead = points[i]
        val sx = ahead.x - first.x
        val sy = ahead.y - first.y
        val len = sqrt(sx * sx + sy * sy)
        if (len > 1e-6f) {
            into[0] = sx / len
            into[1] = sy / len
        }
    }

    /**
     * Where the stroke actually begins: past the pen's arrival. See [LANDING_TRIM_PX].
     * [dirX]/[dirY] is the direction the stroke turned out to go.
     */
    private fun landingEnd(points: List<StrokePoint>, dirX: Float, dirY: Float): Int {
        var reach = 0f
        var last = 0
        var i = 1
        while (i < points.size && reach < LANDING_TRIM_PX) {
            val a = points[i - 1]
            val b = points[i]
            val sx = b.x - a.x
            val sy = b.y - a.y
            val step = sqrt(sx * sx + sy * sy)
            if (step > 0f) {
                // Anything more than sixty degrees off where the stroke is going is the pen still
                // arriving. Backward steps are the obvious case; the *kink* where the path rejoins
                // the stroke's line is the one that catches you out, because trimming only the
                // backward part leaves a corner sharp enough to fold the mark over itself all over
                // again.
                if ((sx * dirX + sy * dirY) / step < 0.5f) last = i
                reach += step
            }
            i++
        }
        return last
    }

    /** Arc length from the first point to `points[index]` (clamped to the path). */
    private fun arcTo(points: List<StrokePoint>, index: Int): Float {
        var reach = 0f
        var i = 1
        val last = min(index, points.size - 1)
        while (i <= last) {
            val a = points[i - 1]
            val b = points[i]
            reach += sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
            i++
        }
        return reach
    }

    /**
     * Whether a prefix ending at these points has travelled far enough that everything the
     * sweep decides from the path ahead is already decided — and decided the same way the
     * finished stroke will decide it.
     *
     * Two conditions, each the exact reach of one lookahead. The **settled** arc is measured
     * to the second-to-last point rather than the last, because every window here is walked
     * with a `while (i < size …)` bound: a window that runs out of samples stops early and
     * answers from a shorter stretch than the whole stroke will, which is precisely the
     * disagreement to avoid. So the arrival chord and the landing trim need
     * [SEED_WINDOW_PX] of settled path from the **start**, and the two filter seeds need
     * another [SEED_WINDOW_PX] past wherever the arrival trim put the beginning.
     */
    private fun prefixDecidable(points: List<StrokePoint>, from: Int): Boolean {
        val settled = arcTo(points, points.size - 2)
        if (settled < SEED_WINDOW_PX) return false
        return settled - arcTo(points, from) >= SEED_WINDOW_PX
    }

    /**
     * Everything one sweep of the lead carries from station to station: where the stroke
     * began, how far it has travelled, and the running state of every filter.
     *
     * It exists so that a whole stroke and a stroke still under the pen go through **one**
     * station loop ([advance]) rather than two copies of it. A second implementation of
     * this loop — however carefully written the day it was written — is a thing that drifts:
     * every later constant, every later filter has to be put into both, and the day one of
     * them is missed the live preview and the bake stop being the same mark, which is the
     * one failure this whole path exists to prevent.
     *
     * [next] is the index of the next point to be consumed as the end of a segment, which is
     * what makes the loop resumable: the caller keeps the whole stroke so far and this says
     * how much of it has already been laid.
     */
    private class SweepState(val base: Float, val seed: Int, val density: Float) {
        /** Set once the arrival trim and the two filter seeds have been decided. */
        var seeded = false
        var from = 0
        var next = 0
        var traveled = 0f
        var station = 0
        var nextAt = 0f
        var leanTilt = 0f
        var coverTilt = 0f
        var travelX = 0f
        var travelY = 0f
        var lastCx = 0f
        var lastCy = 0f
        var lastPress = 0f
        var lastLean = 0f
        var lastHalf = 0f
        var lastArc = 0f
        var capped = false
        /** [MAX_FLECKS] reached: this stroke lays no more graphite, ever. */
        var full = false
        /** Flecks already handed to the caller — only a [Sweep] ever has any. */
        var laid = 0
    }

    /**
     * A stroke still under the pen, swept **incrementally**: [extend] lays the graphite the
     * mark has newly decided and returns only that.
     *
     * `of(points, …, prefix = true)` answers the same question, but it answers it about the
     * whole stroke every time it is asked, and a live preview asks once per MotionEvent: a
     * 1252-sample stroke drawn slowly on a Nomad spent 4352 ms of the UI thread inside it —
     * 3.5 ms an event and climbing with the length, which the hand feels as the ink lagging
     * behind the nib (measured 2026-09-18, Phase 28). The work is quadratic in the length of
     * the stroke for no reason: every call re-walks stations that were decided and drawn
     * long ago, and re-decides them to exactly the same answer, because that is what the
     * prefix invariant promises.
     *
     * So the sweep is resumed instead of restarted. The caller keeps the stroke's points (it
     * has them anyway) and passes **the whole stroke so far** at every call; this remembers
     * how much of it has been consumed and every scrap of filter state, and lays only the new
     * stations. The concatenation of every [extend] is exactly
     * `of(points, width, seed, prefix = true)` on the final list — element for element,
     * whatever sizes the points arrived in — which is pinned by `GraphiteGrainIncrementalTest`.
     *
     * Semantics are prefix mode's, unchanged: nothing at all until the path has settled past
     * [SEED_WINDOW_PX], and never the end cap. There is no `finish` — a committed stroke is
     * baked by [of] on the whole path, as it always was.
     *
     * Not thread-safe, and not meant to be: it belongs to one contact.
     */
    class Sweep internal constructor(width: Float, seed: Int, density: Float) {

        private val state = SweepState(
            base = (if (width < MIN_WIDTH_PX) MIN_WIDTH_PX else width) / 2f,
            seed = seed,
            density = density.coerceIn(0f, 1f),
        )

        /** How many flecks this sweep has handed out since it began. */
        val count: Int get() = state.laid

        /**
         * The graphite decided since the last call, given the whole stroke so far in
         * [points]. Empty when no new station has been reached — including when nothing
         * new has arrived at all, and while the mark is still too young to be decidable.
         */
        fun extend(points: List<StrokePoint>): Grain {
            if (points.size < 2) return EMPTY
            val out = Sink()
            out.carried = state.laid
            if (!advance(state, points, out, prefix = true)) return EMPTY
            if (out.count == 0) return EMPTY
            state.laid += out.count
            return out.grain()
        }
    }

    /**
     * Begin a resumable sweep of a [width] px lead seeded by [seed] — the stroke's stable id,
     * the same one [of] will be called with when the mark commits — at [density], the same
     * one [of] will be called with too. See [Sweep] and [of].
     */
    fun begin(width: Float, seed: Int, density: Float = 1f): Sweep = Sweep(width, seed, density)

    private fun sweep(
        points: List<StrokePoint>,
        base: Float,
        seed: Int,
        prefix: Boolean,
        density: Float,
    ): Grain {
        val state = SweepState(base, seed, density)
        val out = Sink()
        // A prefix that has not travelled far enough to decide these things the way the
        // finished stroke will lays nothing: see [prefixDecidable].
        if (!advance(state, points, out, prefix)) return EMPTY
        // A path shorter than one pitch never reaches a station; it still left graphite —
        // but a tap is not a prefix of a sweep, so prefix mode waits for the first station.
        if (state.station == 0) return if (prefix) EMPTY else tap(points[state.from], base, seed, density)
        // And the lifting end gets its dome too — except under the pen, where that end is
        // the tip of the lead and has not come to rest anywhere yet. (Nor past MAX_FLECKS,
        // where the sweep gave up mid-stroke and a dome would cap nothing.)
        if (!prefix && !state.full) {
            cap(
                out, state.lastCx, state.lastCy, state.travelX, state.travelY,
                state.lastPress, state.lastLean, state.lastArc, state.lastHalf,
                seed, state.station + 1, 1f, state.density,
            )
        }
        return out.grain()
    }

    /**
     * The station loop — the one copy of it. Walks [points] from wherever [state] left off
     * to the end of the list, appending each new cross-section's flecks to [out] and leaving
     * [state] ready to be resumed when more of the stroke arrives.
     *
     * Returns `false` only for a [prefix] too young to be decidable, which lays nothing and
     * leaves the state unseeded so the next call asks again.
     *
     * Everything here is **causal** — each station is decided from the path already covered
     * and never from what comes after it — which is what makes resuming legitimate rather
     * than merely convenient: a station laid now is the station the finished stroke will
     * have, so there is nothing to revisit.
     */
    private fun advance(
        state: SweepState,
        points: List<StrokePoint>,
        out: Sink,
        prefix: Boolean,
    ): Boolean {
        val seed = state.seed
        if (!state.seeded) {
            // The arrival is found first, with a chord long enough to see past it, and
            // everything after is seeded from where the stroke actually begins — otherwise
            // the seeds are themselves measured across the wobble they exist to be immune to.
            val dir = FloatArray(2)
            chordDirection(points, 0, SEED_WINDOW_PX, dir)
            val from = landingEnd(points, dir[0], dir[1])
            if (prefix && !prefixDecidable(points, from)) return false
            state.from = from
            state.next = from + 1
            // Seeded from the mean lean over the smoothing window (capped at SEED_WINDOW_PX
            // so nothing is decided from further ahead than the arrival trim already looks),
            // never from the first sample.
            //
            // Same transient as the travelled direction, and it shows up as a wedge instead
            // of a hook. A digitizer's tilt at the instant of touch-down is the least
            // trustworthy reading it produces — the pen is barely on the glass — and seeding
            // the filter there makes the mark begin at whatever that first sample happened to
            // say and take a whole window to climb to the angle the pen is really held at.
            // Read low, and a stroke the artist began with the lead already laid over starts
            // narrow and dark and flares out over the next few millimetres: an arrowhead with
            // a dense nub on the point, which is exactly as much like graphite as it sounds.
            state.leanTilt = seedLean(points, from, min(TILT_SMOOTH_PX, SEED_WINDOW_PX))
            state.coverTilt = seedLean(points, from, min(COVER_SMOOTH_PX, SEED_WINDOW_PX))
            // Seed the travelled direction from a chord across the whole smoothing window,
            // never from the first pair of samples.
            //
            // Seeded from one segment, a stroke begins with a **hook**. The first pair of
            // samples is the single noisiest direction measurement there is, and two things
            // are hung on it: the touch-down dome is thrown backwards along it — a half-disc
            // of the mark's half-width, aimed tens of degrees wrong — and the filter then
            // swings for a smoothing window's worth of travel as it converges, sweeping the
            // first cross-sections through a curve. Both errors scale with the half-width, so
            // a fine lead starts cleanly and a lead laid over starts with a comma curling out
            // of it. A chord has no transient to converge from: it is already the answer the
            // filter would have settled on.
            chordDirection(points, from, TANGENT_SMOOTH_PX, dir)
            state.travelX = dir[0]
            state.travelY = dir[1]
            state.seeded = true
        }
        if (state.full) return true
        val base = state.base
        val density = state.density
        // Exponential, one pole, walked forward with the stations — so each depends only on
        // the path already covered and a prefix of the stroke renders identically to the
        // whole of it.
        val smoothing = 1f - exp(-TOOTH_PITCH_PX / TILT_SMOOTH_PX)
        val covering = 1f - exp(-TOOTH_PITCH_PX / COVER_SMOOTH_PX)
        val turning = 1f - exp(-TOOTH_PITCH_PX / TANGENT_SMOOTH_PX)
        var traveled = state.traveled
        var station = state.station
        var nextAt = state.nextAt
        var leanTilt = state.leanTilt
        var coverTilt = state.coverTilt
        var travelX = state.travelX
        var travelY = state.travelY
        // Whatever the last cross-section was, so the finish can be capped with the same lead.
        var lastCx = state.lastCx
        var lastCy = state.lastCy
        var lastPress = state.lastPress
        var lastLean = state.lastLean
        var lastHalf = state.lastHalf
        var lastArc = state.lastArc
        var capped = state.capped
        var full = false
        var i = state.next
        segments@ while (i < points.size) {
            val a = points[i - 1]
            val b = points[i]
            i++
            val dx = b.x - a.x
            val dy = b.y - a.y
            val segLen = sqrt(dx * dx + dy * dy)
            if (segLen <= 0f) continue
            val tx = dx / segLen
            val ty = dy / segLen
            while (nextAt <= traveled + segLen) {
                // The direction a cross-section is laid across is the *travelled* direction,
                // not the one measured between the last two samples — see TANGENT_SMOOTH_PX.
                if (travelX == 0f && travelY == 0f) {
                    travelX = tx
                    travelY = ty
                } else {
                    travelX += (tx - travelX) * turning
                    travelY += (ty - travelY) * turning
                    val len = sqrt(travelX * travelX + travelY * travelY)
                    if (len > 1e-6f) {
                        travelX /= len
                        travelY /= len
                    } else {
                        travelX = tx
                        travelY = ty
                    }
                }
                val t = (nextAt - traveled) / segLen
                // Both the lean and the press are read at this station, not at the stroke's
                // start: a shading stroke is a hand rolling the pencil over as it travels, and
                // taking either once would render the gesture as a uniform bar.
                val tilt = a.tilt + t * (b.tilt - a.tilt)
                leanTilt += (tilt - leanTilt) * smoothing
                coverTilt += (tilt - coverTilt) * covering
                val half = base * widthFactor(leanTilt)
                val coverLean = coverageFactor(coverTilt)
                val cx = a.x + t * dx
                val cy = a.y + t * dy
                val pressure = a.pressure + t * (b.pressure - a.pressure)
                deposit(
                    out = out,
                    cx = cx,
                    cy = cy,
                    tx = travelX,
                    ty = travelY,
                    pressure = pressure,
                    lean = coverLean,
                    arc = nextAt,
                    station = station,
                    lanes = laneCount(half),
                    half = half,
                    seed = seed,
                    density = density,
                )
                lastCx = cx
                lastCy = cy
                lastPress = pressure
                lastLean = coverLean
                lastHalf = half
                lastArc = nextAt
                // The touch-down dome, laid before the body so everything already on the paper
                // keeps its place as the stroke grows — only the lifting end moves with the pen.
                if (!capped) {
                    capped = true
                    cap(
                        out, cx, cy, travelX, travelY, pressure, coverLean, nextAt, half,
                        seed, -1, -1f, density,
                    )
                }
                station++
                nextAt += TOOTH_PITCH_PX
                if (out.total >= MAX_FLECKS) {
                    full = true
                    break@segments
                }
            }
            traveled += segLen
        }
        state.next = i
        state.traveled = traveled
        state.station = station
        state.nextAt = nextAt
        state.leanTilt = leanTilt
        state.coverTilt = coverTilt
        state.travelX = travelX
        state.travelY = travelY
        state.lastCx = lastCx
        state.lastCy = lastCy
        state.lastPress = lastPress
        state.lastLean = lastLean
        state.lastHalf = lastHalf
        state.lastArc = lastArc
        state.capped = capped
        state.full = full
        return true
    }

    /**
     * One cross-section of the mark: the lanes of tooth the lead spans at this instant.
     * The normal is the travel direction turned a quarter turn, so [half] is measured
     * across the stroke however it is heading.
     */
    private fun deposit(
        out: Sink,
        cx: Float,
        cy: Float,
        tx: Float,
        ty: Float,
        pressure: Float,
        lean: Float,
        arc: Float,
        station: Int,
        lanes: Int,
        half: Float,
        seed: Int,
        density: Float,
    ) {
        val nx = -ty
        val ny = tx
        val press = pressure.coerceIn(0f, 1f).pow(PRESSURE_GAMMA)
        // Slide this cross-section's whole comb of lanes sideways by a random fraction of a lane.
        // Without it, the same lane recurs at the same offset station after station, and since a
        // fleck is wider than the pitch that spaces them, consecutive flecks in a lane fuse — the
        // mark comes out as a bundle of little dashes running *along* the stroke, and reads as
        // combed rather than deposited. Graphite has no direction; a random phase per station
        // removes the only thing that gave it one.
        val phase = unit(hash(seed, station, 0x1f7))
        for (lane in 0 until lanes) {
            val u = laneOffset(lane, lanes, phase)
            val site = u * half
            // [density] multiplies the coverage and only the coverage — see [of]. A site's own
            // toss in [catches] does not move with it, so thinning a mark removes flecks and
            // never relocates one.
            val cover = coverage(press, u) * skate(arc, site, seed) * lean * density
            if (cover <= 0f) continue
            val alongJitter = (unit(hash(seed, station, lane)) - 0.5f) *
                (JITTER * TOOTH_PITCH_PX + 2f * LEVER_JITTER * abs(site))
            val acrossJitter =
                (unit(hash(seed, station, lane xor 0x5bf0)) - 0.5f) * JITTER * TOOTH_PITCH_PX
            val across = site + acrossJitter
            val x = cx + tx * alongJitter + nx * across
            val y = cy + ty * alongJitter + ny * across
            if (!catches(cover, x, y, hash(seed, station, lane xor 0x2af1))) continue
            out.add(x, y, levelOf(press, hash(seed, station, lane xor 0x11d7)))
        }
    }

    /** A tap: the same tooth lattice, filled over a disc instead of swept along a path. */
    private fun tap(p: StrokePoint, base: Float, seed: Int, density: Float): Grain {
        val out = Sink()
        val half = base * widthFactor(p.tilt)
        val lean = coverageFactor(p.tilt)
        val press = p.pressure.coerceIn(0f, 1f).pow(PRESSURE_GAMMA)
        val lanes = laneCount(half)
        for (row in 0 until lanes) {
            val v = laneOffset(row, lanes, unit(hash(seed, row, 0x1f7)))
            for (lane in 0 until lanes) {
                val u = laneOffset(lane, lanes, unit(hash(seed, row, 0x2e8)))
                val r = sqrt(u * u + v * v)
                if (r > 1f) continue
                val cover = coverage(press, r) * lean * density
                if (cover <= 0f) continue
                val jx = (unit(hash(seed, row, lane)) - 0.5f) * JITTER * TOOTH_PITCH_PX
                val jy = (unit(hash(seed, row, lane xor 0x5bf0)) - 0.5f) * JITTER * TOOTH_PITCH_PX
                val x = p.x + u * half + jx
                val y = p.y + v * half + jy
                if (!catches(cover, x, y, hash(seed, row, lane xor 0x2af1))) continue
                out.add(x, y, levelOf(press, hash(seed, row, lane xor 0x11d7)))
            }
        }
        return out.grain()
    }

    /** Peaks per cross-section: the sheet's tooth counted across the lead, never scaled by it. */
    private fun laneCount(half: Float): Int {
        val n = ceil(2f * half / TOOTH_PITCH_PX).toInt() + 1
        return if (n < 1) 1 else n
    }

    /**
     * Where lane [lane] of [lanes] sits across the mark, as a fraction of the half-width.
     *
     * Cell centres, not endpoints — so the outermost lane lies *inside* the lead's edge
     * rather than exactly on it. Spread endpoint-to-endpoint instead, the two outermost
     * lanes take the full brunt of the rim falloff, which is survivable on a broad lead
     * with seven lanes between them and ruinous on a fine one where those two lanes are
     * two thirds of the entire mark: a hard-pressed fine lead came out patchy and grey
     * instead of a firm dark line.
     */
    private fun laneOffset(lane: Int, lanes: Int, phase: Float): Float =
        if (lanes <= 1) 0f else -1f + 2f * (lane + phase) / lanes

    /** Fraction of peaks that catch graphite at this pressure, [u] px across the mark (-1..1). */
    private fun coverage(press: Float, u: Float): Float {
        val core = COVER_MIN + (COVER_MAX - COVER_MIN) * press
        val rim = 1f - EDGE_BARE * abs(u).coerceAtMost(1f).pow(EDGE_POW)
        return core * rim
    }

    /** Darkness index for one fleck: pressure chooses, the hash scatters it a little. */
    private fun levelOf(press: Float, h: Int): Int {
        if (LEVELS <= 1) return 0
        val d = press * 0.82f + unit(h) * 0.18f
        return (d * (LEVELS - 1)).roundToInt().coerceIn(0, LEVELS - 1)
    }

    /**
     * Whether the site at page position ([x], [y]) catches graphite at this [cover], given the
     * site's own coin toss [h]. The toss and the sheet's tooth under the site are blended by
     * [TOOTH_WEIGHT]; a hollow needs more coverage to fill than a peak does, and at full coverage
     * everything fills.
     *
     * **The toss is independent of [cover]**, which is what makes the whole thing monotone in
     * coverage: lower the coverage and a site that caught may stop catching, but no site that
     * did not catch can start. That is the property `density` rests on ([of]) — and it is a
     * property of this shape, not an accident, so a future `catches` that mixed the coverage
     * into the toss would break a thinned mark's promise to be a subset of the full one.
     */
    private fun catches(cover: Float, x: Float, y: Float, h: Int): Boolean {
        val draw = unit(h) * (1f - TOOTH_WEIGHT) + (1f - tooth(x, y)) * TOOTH_WEIGHT
        return draw < cover
    }

    /**
     * The sheet's tooth height at page position ([x], [y]), `0` a hollow to `1` a peak — see
     * [TOOTH_CELL_PX]. A property of the page, so the same under every stroke on it.
     */
    private fun tooth(x: Float, y: Float): Float {
        val fine = valueNoise(x / TOOTH_CELL_PX, y / TOOTH_CELL_PX, TOOTH_SEED)
        val coarse = valueNoise(x / (TOOTH_CELL_PX * 3f), y / (TOOTH_CELL_PX * 3f), TOOTH_SEED + 1)
        return fine * 0.6f + coarse * 0.4f
    }

    /** Smooth value noise on a unit lattice: bilinear over four hashed corners, `0`..`1`. */
    private fun valueNoise(u: Float, v: Float, seed: Int): Float {
        val i = floor(u).toInt()
        val j = floor(v).toInt()
        val fu = u - i
        val fv = v - j
        val eu = fu * fu * (3f - 2f * fu)
        val ev = fv * fv * (3f - 2f * fv)
        val a = unit(hash(seed, i, j))
        val b = unit(hash(seed, i + 1, j))
        val c = unit(hash(seed, i, j + 1))
        val d = unit(hash(seed, i + 1, j + 1))
        val top = a + (b - a) * eu
        val bottom = c + (d - c) * eu
        return top + (bottom - top) * ev
    }

    /**
     * Smooth value noise over the mark — [arc] px along it, [across] px out from its centre
     * line: the runs where the lead lifts and catches again. Long cells along, short across, so
     * the runs are streaks in the direction of travel — see [SKATE_WIDTH_PX].
     */
    private fun skate(arc: Float, across: Float, seed: Int): Float =
        1f - SKATE_DEPTH * valueNoise(arc / SKATE_LEN_PX, across / SKATE_WIDTH_PX, seed xor 0x7d1)

    // ── Deterministic noise ──────────────────────────────────────────────────

    /**
     * A stateless integer mix of three coordinates. Not a random number generator: called
     * with the same arguments it returns the same bits, which is exactly the property the
     * whole file rests on. The constants are a standard 32-bit avalanche finalizer.
     */
    private fun hash(a: Int, b: Int, c: Int): Int {
        var h = a * -0x61c88647
        h = h xor (b * -0x3361d2af)
        h = h xor (c * 0x27d4eb2f)
        h = h xor (h ushr 15)
        h *= -0x7ee3623b
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        h = h xor (h ushr 16)
        return h
    }

    /** The top 24 bits of a hash as a float in `[0, 1)`. */
    private fun unit(h: Int): Float = (h ushr 8) * (1f / (1 shl 24))

    /** Growable interleaved store; the arrays are handed to the renderer, never reused here. */
    private class Sink {
        var xy = FloatArray(512)
        var level = IntArray(256)
        var count = 0

        /**
         * Flecks this stroke laid before this batch — zero for a whole stroke, and what an
         * incremental [Sweep] has already handed out. [MAX_FLECKS] is a bound on the *mark*,
         * not on one batch of it, so every test of it asks [total].
         */
        var carried = 0

        val total: Int get() = carried + count

        fun add(x: Float, y: Float, lvl: Int) {
            if (count * 2 + 2 > xy.size) {
                xy = xy.copyOf(xy.size * 2)
                level = level.copyOf(level.size * 2)
            }
            xy[count * 2] = x
            xy[count * 2 + 1] = y
            level[count] = lvl
            count++
        }

        fun grain(): Grain = Grain(xy, level, count)
    }
}
