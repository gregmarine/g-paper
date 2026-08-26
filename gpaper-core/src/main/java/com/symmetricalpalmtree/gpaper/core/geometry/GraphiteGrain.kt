package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
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
 * **Pure Kotlin, no Android.** That is not tidiness — it is what lets the determinism below
 * be *proved* by a JVM test rather than asserted. The renderer's job is only to put ink where
 * this says.
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
     * run and [SKATE_DEPTH] how much coverage it can steal at its lightest.
     */
    private const val SKATE_LEN_PX = 26f
    private const val SKATE_DEPTH = 0.16f

    /**
     * Pressure curve. BOOX digitizers saturate — a firm stroke pins at the ceiling and stays
     * there — so the usable band sits below maximum and a linear map spends most of its range
     * on presses the hand cannot tell apart. The gamma pushes resolution down into the light
     * and middle where drawing actually happens.
     */
    private const val PRESSURE_GAMMA = 0.75f

    /** Fleck displacement from its lattice site, as a fraction of the pitch. Breaks the grid. */
    private const val JITTER = 0.62f

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

    /** Alpha multiplier for darkness index [level]; `LEVEL_FLOOR` at 0, 1 at the top. */
    fun levelAlpha(level: Int): Float =
        if (LEVELS <= 1) 1f
        else LEVEL_FLOOR + (1f - LEVEL_FLOOR) * (level.coerceIn(0, LEVELS - 1).toFloat() / (LEVELS - 1))

    /**
     * The graphite [points] deposited at [width] px, seeded by [seed] (the stroke's stable
     * id — see the determinism note above). A single point is a tap: the lead touched down
     * and lifted, leaving a disc of grit rather than a line.
     */
    fun of(points: List<StrokePoint>, width: Float, seed: Int): Grain {
        if (points.isEmpty()) return EMPTY
        val base = (if (width < MIN_WIDTH_PX) MIN_WIDTH_PX else width) / 2f
        return if (points.size == 1) tap(points[0], base, seed) else sweep(points, base, seed)
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
     * meets the paper as a *disc*, so the ink ends in a half-round of the mark's own half-width.
     * Walking out past the end and shrinking the half-width along a circle is that disc, drawn the
     * only way this renderer knows how.
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
    ) {
        if (half <= TOOTH_PITCH_PX) return
        var d = TOOTH_PITCH_PX
        var k = 0
        while (d < half) {
            val shrunk = sqrt(half * half - d * d)
            if (shrunk >= TOOTH_PITCH_PX * 0.5f) {
                deposit(
                    out = out,
                    cx = cx + sign * tx * d,
                    cy = cy + sign * ty * d,
                    tx = tx,
                    ty = ty,
                    pressure = pressure,
                    lean = lean,
                    arc = arc + sign * d,
                    station = station0 + sign.toInt() * k,
                    lanes = laneCount(shrunk),
                    half = shrunk,
                    seed = seed,
                )
            }
            d += TOOTH_PITCH_PX
            k++
            if (out.count >= MAX_FLECKS) return
        }
    }

    private fun sweep(points: List<StrokePoint>, base: Float, seed: Int): Grain {
        val out = Sink()
        var traveled = 0f
        var station = 0
        var nextAt = 0f
        // Exponential, one pole, walked forward with the stations — so it depends only on the path
        // already covered and a prefix of the stroke renders identically to the whole of it.
        val smoothing = 1f - exp(-TOOTH_PITCH_PX / TILT_SMOOTH_PX)
        var leanTilt = points[0].tilt
        val turning = 1f - exp(-TOOTH_PITCH_PX / TANGENT_SMOOTH_PX)
        // Seed the travelled direction from a chord across the whole smoothing window, never from
        // the first pair of samples.
        //
        // Seeded from one segment, a stroke begins with a **hook**. The first pair of samples is
        // the single noisiest direction measurement there is, and two things are hung on it: the
        // touch-down dome is thrown backwards along it — a half-disc of the mark's half-width,
        // aimed tens of degrees wrong — and the filter then swings for a smoothing window's worth
        // of travel as it converges, sweeping the first cross-sections through a curve. Both
        // errors scale with the half-width, so a fine lead starts cleanly and a lead laid over
        // starts with a comma curling out of it. A chord has no transient to converge from: it is
        // already the answer the filter would have settled on.
        var travelX = 0f
        var travelY = 0f
        run {
            val first = points[0]
            var reach = 0f
            var i = 1
            while (i < points.size - 1 && reach < TANGENT_SMOOTH_PX) {
                val a = points[i - 1]
                val b = points[i]
                reach += sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
                i++
            }
            val ahead = points[i]
            val sx = ahead.x - first.x
            val sy = ahead.y - first.y
            val len = sqrt(sx * sx + sy * sy)
            if (len > 1e-6f) {
                travelX = sx / len
                travelY = sy / len
            }
        }
        // Whatever the last cross-section was, so the finish can be capped with the same lead.
        var lastCx = 0f
        var lastCy = 0f
        var lastPress = 0f
        var lastLean = 0f
        var lastHalf = 0f
        var lastArc = 0f
        var capped = false
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val segLen = sqrt(dx * dx + dy * dy)
            if (segLen <= 0f) continue
            val tx = dx / segLen
            val ty = dy / segLen
            while (nextAt <= traveled + segLen) {
                // The direction a cross-section is laid across is the *travelled* direction, not
                // the one measured between the last two samples — see TANGENT_SMOOTH_PX.
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
                val half = base * widthFactor(leanTilt)
                val coverLean = coverageFactor(leanTilt)
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
                        seed, -1, -1f,
                    )
                }
                station++
                nextAt += TOOTH_PITCH_PX
                if (out.count >= MAX_FLECKS) return out.grain()
            }
            traveled += segLen
        }
        // A path shorter than one pitch never reaches a station; it still left graphite.
        if (station == 0) return tap(points[0], base, seed)
        // And the lifting end gets its dome too.
        cap(
            out, lastCx, lastCy, travelX, travelY, lastPress, lastLean, lastArc, lastHalf,
            seed, station + 1, 1f,
        )
        return out.grain()
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
    ) {
        val nx = -ty
        val ny = tx
        val press = pressure.coerceIn(0f, 1f).pow(PRESSURE_GAMMA)
        val skate = skate(arc, seed)
        // Slide this cross-section's whole comb of lanes sideways by a random fraction of a lane.
        // Without it, the same lane recurs at the same offset station after station, and since a
        // fleck is wider than the pitch that spaces them, consecutive flecks in a lane fuse — the
        // mark comes out as a bundle of little dashes running *along* the stroke, and reads as
        // combed rather than deposited. Graphite has no direction; a random phase per station
        // removes the only thing that gave it one.
        val phase = unit(hash(seed, station, 0x1f7))
        for (lane in 0 until lanes) {
            val u = laneOffset(lane, lanes, phase)
            val cover = coverage(press, u) * skate * lean
            if (cover <= 0f) continue
            if (unit(hash(seed, station, lane xor 0x2af1)) >= cover) continue
            val alongJitter = (unit(hash(seed, station, lane)) - 0.5f) * JITTER * TOOTH_PITCH_PX
            val acrossJitter =
                (unit(hash(seed, station, lane xor 0x5bf0)) - 0.5f) * JITTER * TOOTH_PITCH_PX
            val across = u * half + acrossJitter
            out.add(
                cx + tx * alongJitter + nx * across,
                cy + ty * alongJitter + ny * across,
                levelOf(press, hash(seed, station, lane xor 0x11d7)),
            )
        }
    }

    /** A tap: the same tooth lattice, filled over a disc instead of swept along a path. */
    private fun tap(p: StrokePoint, base: Float, seed: Int): Grain {
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
                val cover = coverage(press, r) * lean
                if (unit(hash(seed, row, lane xor 0x2af1)) >= cover) continue
                val jx = (unit(hash(seed, row, lane)) - 0.5f) * JITTER * TOOTH_PITCH_PX
                val jy = (unit(hash(seed, row, lane xor 0x5bf0)) - 0.5f) * JITTER * TOOTH_PITCH_PX
                out.add(
                    p.x + u * half + jx,
                    p.y + v * half + jy,
                    levelOf(press, hash(seed, row, lane xor 0x11d7)),
                )
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

    /** Smooth value noise along the path: the runs where the lead lifts and catches again. */
    private fun skate(arc: Float, seed: Int): Float {
        val cell = arc / SKATE_LEN_PX
        val i = floor(cell).toInt()
        val f = cell - i
        val a = unit(hash(seed, i, 0x7d1))
        val b = unit(hash(seed, i + 1, 0x7d1))
        val e = f * f * (3f - 2f * f)
        return 1f - SKATE_DEPTH * (a + (b - a) * e)
    }

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
