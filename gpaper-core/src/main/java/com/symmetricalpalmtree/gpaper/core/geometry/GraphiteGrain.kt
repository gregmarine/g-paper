package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.abs
import kotlin.math.ceil
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
 * ## Tilt
 *
 * Deliberately unread. A real pencil laid over on its side deposits from the flank of the
 * lead — lighter, broader, streakier — and that is the other half of graphite. But BOOX
 * reports tilt on a scale that differs per model with no `getMaxTilt()` to normalize against
 * (a five-device survey found one model reporting roughly a hundred times the others), so the
 * Onyx engine captures tilt as zero and always will until someone characterizes it per model.
 * A renderer that leaned on tilt would therefore look right on a workbench and flat on every
 * BOOX panel in the field. Pressure carries the mark alone, on purpose.
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
    const val TOOTH_PITCH_PX: Float = 1.7f

    /**
     * Diameter of one fleck of graphite in px. Deliberately larger than [TOOTH_PITCH_PX] —
     * about 1.35× — so that at full coverage the flecks overlap into solid black and a
     * hard-pressed line is a line rather than a dotted one, while an isolated fleck at the
     * pale end is still a speck of grit and not a pinprick.
     */
    const val FLECK_PX: Float = 2.3f

    /**
     * How many darknesses a fleck may have. Three, and few on purpose: tone here is supposed
     * to come from *how many* flecks land, so the darkness ramp is a floor under the pale end
     * — it keeps a barely-touched stroke from being a scatter of pure black dots — rather
     * than the mechanism. More levels would quietly turn this back into a tonal renderer and
     * hand the panel greys to dither after all.
     */
    const val LEVELS: Int = 3

    /** Alpha multiplier of the palest fleck; the darkest is always 1. */
    private const val LEVEL_FLOOR = 0.45f

    /** Coverage at the lightest touch and at the hardest — the fraction of peaks that catch. */
    private const val COVER_MIN = 0.14f
    private const val COVER_MAX = 1.0f

    /**
     * How much coverage the rim of the mark loses relative to its core, and how sharply.
     * A pencil tip is round: its centre bears on the paper and its edge merely grazes it, so
     * a mark thins out toward both sides instead of ending at a wall.
     */
    private const val EDGE_BARE = 0.55f
    private const val EDGE_POW = 1.6f

    /**
     * The skate. A lead riding over tooth catches and lifts in runs longer than a single
     * peak, which is what gives a pencil line its along-the-stroke streakiness; without it a
     * mark is evenly speckled and reads as spray. [SKATE_LEN_PX] is the length of one such
     * run and [SKATE_DEPTH] how much coverage it can steal at its lightest.
     */
    private const val SKATE_LEN_PX = 26f
    private const val SKATE_DEPTH = 0.28f

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
     * Upper bound on flecks for one stroke. A mark long enough or broad enough to pass this
     * has already stopped being legible as grain, and the cap is here so a host that hands us
     * an absurd width or a path with a million points degrades instead of stalling the frame.
     */
    private const val MAX_FLECKS = 240_000

    /**
     * One stroke's worth of graphite: [count] flecks, their centres interleaved in [xy]
     * (`x0, y0, x1, y1, …`) and their darkness index in [level] (`0` palest,
     * `LEVELS - 1` darkest). Two arrays rather than a list of objects because a page reload
     * re-renders every stroke on the sheet and per-fleck allocation is how that becomes
     * visible.
     */
    class Grain(val xy: FloatArray, val level: IntArray, val count: Int)

    private val EMPTY = Grain(FloatArray(0), IntArray(0), 0)

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
        val half = (if (width < MIN_WIDTH_PX) MIN_WIDTH_PX else width) / 2f
        return if (points.size == 1) tap(points[0], half, seed) else sweep(points, half, seed)
    }

    // ── The mark ─────────────────────────────────────────────────────────────

    private fun sweep(points: List<StrokePoint>, half: Float, seed: Int): Grain {
        val lanes = laneCount(half)
        val out = Sink()
        var traveled = 0f
        var station = 0
        var nextAt = 0f
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
                val t = (nextAt - traveled) / segLen
                deposit(
                    out = out,
                    cx = a.x + t * dx,
                    cy = a.y + t * dy,
                    tx = tx,
                    ty = ty,
                    pressure = a.pressure + t * (b.pressure - a.pressure),
                    arc = nextAt,
                    station = station,
                    lanes = lanes,
                    half = half,
                    seed = seed,
                )
                station++
                nextAt += TOOTH_PITCH_PX
                if (out.count >= MAX_FLECKS) return out.grain()
            }
            traveled += segLen
        }
        // A path shorter than one pitch never reaches a station; it still left graphite.
        if (station == 0) return tap(points[0], half, seed)
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
        for (lane in 0 until lanes) {
            val u = laneOffset(lane, lanes)
            val cover = coverage(press, u) * skate
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
    private fun tap(p: StrokePoint, half: Float, seed: Int): Grain {
        val out = Sink()
        val press = p.pressure.coerceIn(0f, 1f).pow(PRESSURE_GAMMA)
        val lanes = laneCount(half)
        for (row in 0 until lanes) {
            val v = laneOffset(row, lanes)
            for (lane in 0 until lanes) {
                val u = laneOffset(lane, lanes)
                val r = sqrt(u * u + v * v)
                if (r > 1f) continue
                val cover = coverage(press, r)
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
    private fun laneOffset(lane: Int, lanes: Int): Float =
        if (lanes <= 1) 0f else -1f + (2f * lane + 1f) / lanes

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
