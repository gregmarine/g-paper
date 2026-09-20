package com.symmetricalpalmtree.gpaper.core.render

import com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * **Render the flank to a PNG before it reaches a panel** (Phase 36) — the same habit
 * `PencilRenderHarness` keeps for the lead sizes, pointed at the one thing about this lead
 * that cannot be judged from a number: whether a shading sweep looks like graphite or like
 * a slab.
 *
 * It exists because the flank is the first lead in this file whose mark depends on **which
 * way the hand travels**. Drawn across the lean it lays a band twenty lead-widths wide;
 * drawn along the lean it stays a hairline; and in between it does something no aggregate
 * statistic describes. Five cells, which are the five questions:
 *
 *  - **a** upright (8°) — the reference mark.
 *  - **b** a writing grip (40°) — **must be pixel-identical to a**, which is the user's
 *    decision 1 and the whole reason the flank is safe to leave armed. The test asserts it
 *    as well as drawing it.
 *  - **c** mid-bloom (50°) — the smoothstep half out.
 *  - **d** a shading sweep at 60° **across** the lean — the band.
 *  - **e** the same 60° drawn **along** the lean — still a hairline, because the strip
 *    retraces itself.
 *
 * Output goes to `$GPAPER_RENDER_DIR` when that is set and `build/flank-renders` otherwise,
 * so a reviewer can point it at a scratch directory without editing anything. Nothing here
 * is committed and every mark is seeded from its own cell's name, so re-runs are identical.
 *
 * Read **geometry** off these images and not tone — see [RenderSheet] for why.
 */
class FlankRenderHarness {

    private companion object {
        /** Arc 46's one pencil lead. */
        const val LEAD = 4f

        /** Ratta's live pencil is black; the flank's paleness has to come from the grain. */
        const val GREY = 0x00

        const val PRESSURE = 0.65f

        /** Cell geometry (px, 1×): room below the mark for an 80 px band. */
        const val CELL_W = 380
        const val CELL_H = 170
        const val MARK_Y = 40f
        const val MARK_X0 = 20f
        const val MARK_X1 = 360f

        /** Sample spacing along a mark, in px — about what a pen delivers at a working pace. */
        const val SAMPLE_STEP_PX = 2f

        const val ZOOM = 3

        /** The wavelength of [mark]'s optional hand-wander, in px. */
        const val WOBBLE_PERIOD_PX = 24f

        /**
         * The ink/px cell d lays as this file stands — what every variant of the grid is
         * matched to, so it compares textures rather than tones.
         *
         * It was 17.59 through the first walk: the tone of the first build of the flank,
         * held as the thing not to move. The second walk moved it deliberately (see
         * `GraphiteGrain.FLANK_TOOTH_DEPTH`), because the tone worth holding turned out to
         * be the one on the **hand's own strokes** at the hand's own pressure, and a
         * synthetic sweep at 0.65 is not that. Matching those put `FLANK_LIGHTEN` at 0.84
         * and cell d here.
         */
        const val TONE_TARGET = 11.67f

        /** Where each variant's `lighten` bisection starts from — any value in range does. */
        const val FIT_FROM = 0.5f

        /** The columns every figure is measured over: the mark's body, clear of both caps. */
        const val X0 = 80
        const val X1 = 300
    }

    private class Cell(
        val key: String,
        val title: String,
        val leanDeg: Float,
        /** Screen-space lean direction: 90° is down the screen, across a left-to-right mark. */
        val azimuthDeg: Float,
        val pressure: Float = PRESSURE,
    )

    private val cells = listOf(
        Cell("a-upright", "a · upright, 8 deg", 8f, 90f),
        Cell("b-writing", "b · writing grip, 40 deg", 40f, 90f),
        Cell("c-bloom", "c · mid-bloom, 50 deg", 50f, 90f),
        Cell("d-shading", "d · shading, 60 deg ACROSS the lean", 60f, 90f),
        Cell("e-along", "e · shading, 60 deg ALONG the lean", 60f, 0f),
        // "Grey however hard it is pressed" is the decision; this is where it is looked at.
        Cell("f-hard", "f · shading, 60 deg ACROSS, pressed hard", 60f, 90f, pressure = 1f),
        Cell("g-light", "g · shading, 60 deg ACROSS, barely pressed", 60f, 90f, pressure = 0.3f),
    )

    private fun deg(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()

    /**
     * The flank's grit for this run: the file's own ([GraphiteGrain.FLANK_GRIT]) unless the
     * environment names another, so a candidate off the variant grid can be looked at in
     * every one of the seven cells without editing anything —
     * `GPAPER_FLANK_TOOTH` / `_FINE` / `_COARSE` / `_SKATE` / `_LIGHTEN` / `_DEPTH`.
     *
     * A door, in the Phase 21 sense: it is here for a walk and it opens onto a **test**
     * only. Nothing in the library reads an environment variable.
     */
    private fun grit(): GraphiteGrain.Grit {
        val d = GraphiteGrain.FLANK_GRIT
        fun env(name: String, fallback: Float) =
            System.getenv(name)?.trim()?.toFloatOrNull() ?: fallback
        return GraphiteGrain.Grit(
            toothWeight = env("GPAPER_FLANK_TOOTH", d.toothWeight),
            toothFine = env("GPAPER_FLANK_FINE", d.toothFine),
            toothCoarse = env("GPAPER_FLANK_COARSE", d.toothCoarse),
            skateDepth = env("GPAPER_FLANK_SKATE", d.skateDepth),
            lighten = env("GPAPER_FLANK_LIGHTEN", d.lighten),
            toothDepth = env("GPAPER_FLANK_DEPTH", d.toothDepth),
        )
    }

    /**
     * A straight horizontal mark travelling `+x`, at a fixed grip — and, with [wobble], a
     * mark that **wanders** the way a hand's does: a cross-track sine of that amplitude in
     * px at [WOBBLE_PERIOD_PX].
     *
     * The wander is not decoration. A cross-section is laid across the travelled direction,
     * so a mark that turns by `dTheta` between stations moves its far rim by
     * `site x dTheta` — at the flank's eighty px of reach, a quarter of a degree is a third
     * of a station pitch, and the combs crowd and part as the hand breathes. The straight
     * mark has exactly none of that, which is why it was the wrong thing to fit against
     * (Phase 36, the second walk).
     */
    private fun mark(
        leanDeg: Float,
        azimuthDeg: Float,
        pressure: Float = PRESSURE,
        wobble: Float = 0f,
    ): List<StrokePoint> {
        val out = ArrayList<StrokePoint>()
        var x = MARK_X0
        while (x <= MARK_X1) {
            out += StrokePoint(
                x = x,
                y = MARK_Y + wobble * kotlin.math.sin(
                    (2.0 * Math.PI * x / WOBBLE_PERIOD_PX).toFloat(),
                ),
                pressure = pressure,
                tilt = deg(leanDeg),
                azimuth = deg(azimuthDeg),
            )
            x += SAMPLE_STEP_PX
        }
        return out
    }

    /**
     * A contiguous stretch of [arc] px from the middle of a stroke — one pass of a sweep,
     * clear of the turnarounds where it crosses what it has already laid.
     */
    private fun window(points: List<StrokePoint>, arc: Float): List<StrokePoint> {
        val total = arcOf(points)
        if (total <= arc) return points
        var at = 0f
        var from = 0
        val skip = (total - arc) * 0.5f
        for (i in 1 until points.size) {
            at += hypot(
                (points[i].x - points[i - 1].x).toDouble(),
                (points[i].y - points[i - 1].y).toDouble(),
            ).toFloat()
            if (at >= skip) { from = i; break }
        }
        var run = 0f
        var to = points.size - 1
        for (i in from + 1 until points.size) {
            run += hypot(
                (points[i].x - points[i - 1].x).toDouble(),
                (points[i].y - points[i - 1].y).toDouble(),
            ).toFloat()
            if (run >= arc) { to = i; break }
        }
        return points.subList(from, to + 1)
    }

    /** The hand-mirror of `StrokeRenderer.drawPencil` — see [PencilRenderHarness]. */
    private fun draw(sheet: RenderSheet, grain: GraphiteGrain.Grain, width: Float) {
        for (level in 0 until GraphiteGrain.LEVELS) {
            val d = GraphiteGrain.fleckPx(level, width)
            val alpha = GraphiteGrain.levelAlpha(level)
            for (i in 0 until grain.count) {
                if (grain.level[i] != level) continue
                sheet.disc(grain.xy[i * 2], grain.xy[i * 2 + 1], d, GREY, GREY, GREY, alpha)
            }
        }
    }

    /**
     * The mark's ink, measured threshold-free the way this project settles width arguments
     * (see `PencilRenderHarness.straightMarkStats`): a per-row mass profile clear of the
     * caps, the extent holding the middle 98 % of it, and — because the flank is one-sided —
     * **where** that extent sits relative to the nib.
     */
    private fun stats(sheet: RenderSheet, label: String): String {
        val rowMass = FloatArray(sheet.h)
        var total = 0f
        for (y in 0 until sheet.h) {
            var m = 0f
            for (x in X0 until X1) m += sheet.darkness(x, y)
            rowMass[y] = m
            total += m
        }
        if (total <= 0f) return "  %-44s NO INK".format(label)
        val (lo, hi) = band(sheet)
        val extent = (hi - lo + 1).toFloat()
        val perPx = total / (X1 - X0)
        // Darkness averaged over the inked band: what "the flank pales as it widens" means
        // in a number. 1.0 is solid black paper to paper.
        val fill = total / ((X1 - X0) * extent)
        return ("  %-44s extent %5.1f px (%4.1f x lead) · band y %3d..%3d (nib at %d) · " +
            "ink %.2f /px · fill %.2f · clump %.2f").format(
            label, extent, extent / LEAD, lo, hi, MARK_Y.toInt(), perPx, fill, clump(sheet),
        )
    }

    /**
     * The rows holding the middle 98 % of the mark's ink — the band, threshold-free, and the
     * only window any of these figures is read over. Bare paper below a band is not part of
     * the mark and inflates every statistic that includes it.
     */
    private fun band(sheet: RenderSheet): Pair<Int, Int> {
        val rowMass = FloatArray(sheet.h)
        var total = 0f
        for (y in 0 until sheet.h) {
            var m = 0f
            for (x in X0 until X1) m += sheet.darkness(x, y)
            rowMass[y] = m
            total += m
        }
        if (total <= 0f) return 0 to sheet.h - 1
        var run = 0f
        var lo = 0
        for (y in rowMass.indices) {
            run += rowMass[y]
            if (run >= 0.01f * total) { lo = y; break }
        }
        run = 0f
        var hi = sheet.h - 1
        for (y in rowMass.indices.reversed()) {
            run += rowMass[y]
            if (run >= 0.01f * total) { hi = y; break }
        }
        return lo to hi
    }

    /** The mark's ink per px of travel, measured exactly as [stats] reports it. */
    private fun ink(sheet: RenderSheet): Float {
        var total = 0f
        for (y in 0 until sheet.h) for (x in X0 until X1) total += sheet.darkness(x, y)
        return total / (X1 - X0)
    }

    /**
     * **How blotchy the mark is, as one number** — the first walk's finding made falsifiable.
     *
     * *"The grain got bigger instead of just being wider overall"* is not a statement about
     * fleck size (nothing moved it) but about **correlation**: whether neighbouring sites
     * decide together. So measure exactly that. Tile the inked band, take the standard
     * deviation of the tiles' mean darkness, and divide it by the deviation the same pixels
     * would show if each were independent (`pixel sigma / sqrt(pixels per tile)`).
     *
     * **1 is white noise; larger is clumped.** A fleck is about a pixel across, so a mark that
     * is honestly an even spray still scores a little above 1 and the figure is only worth
     * reading as a **comparison between cells rendered here** — like tone, and for the same
     * reason ([RenderSheet]).
     */
    private fun clump(sheet: RenderSheet): Float {
        val (lo, hi) = band(sheet)
        val tile = 5
        if (hi - lo + 1 < tile) return Float.NaN
        var pixelSum = 0.0
        var pixelSq = 0.0
        var pixels = 0
        val means = ArrayList<Double>()
        var ty = lo
        while (ty + tile <= hi + 1) {
            var tx = X0
            while (tx + tile <= X1) {
                var m = 0.0
                for (y in ty until ty + tile) {
                    for (x in tx until tx + tile) {
                        val d = sheet.darkness(x, y).toDouble()
                        m += d
                        pixelSum += d
                        pixelSq += d * d
                        pixels++
                    }
                }
                means.add(m / (tile * tile))
                tx += tile
            }
            ty += tile
        }
        if (pixels == 0 || means.size < 2) return Float.NaN
        val pixelMean = pixelSum / pixels
        val pixelVar = (pixelSq / pixels - pixelMean * pixelMean).coerceAtLeast(0.0)
        if (pixelVar <= 0.0) return Float.NaN
        val tileMean = means.average()
        val tileVar = means.sumOf { (it - tileMean) * (it - tileMean) } / (means.size - 1)
        val independent = sqrt(pixelVar / (tile * tile))
        return (sqrt(tileVar) / independent).toFloat()
    }

    @Test
    fun `the flank renders to PNGs for a human to look at`() {
        val outDir = File(System.getenv("GPAPER_RENDER_DIR") ?: "build/flank-renders")
        outDir.mkdirs()

        val report = StringBuilder(
            "\nflank renders — lead $LEAD px, mark travels +x at y ${MARK_Y.toInt()}, " +
                "pressure $PRESSURE unless said\n",
        )
        val sheet = RenderSheet(CELL_W, cells.size * CELL_H)
        val grains = LinkedHashMap<String, GraphiteGrain.Grain>()
        val clumps = LinkedHashMap<String, Float>()
        for ((row, c) in cells.withIndex()) {
            val cell = RenderSheet(CELL_W, CELL_H)
            val points = mark(c.leanDeg, c.azimuthDeg, c.pressure)
            val grain = GraphiteGrain.of(
                points, LEAD, c.key.hashCode(), false, 1f, GraphiteGrain.Lead.FLANK, grit(),
            )
            grains[c.key] = grain
            draw(cell, grain, LEAD)
            report.append(stats(cell, c.title)).append('\n')
            clumps[c.key] = clump(cell)
            File(outDir, "flank-${c.key}-1x.png").writeBytes(cell.toPng())
            File(outDir, "flank-${c.key}-3x.png").writeBytes(cell.sub(20, 0, 240, CELL_H).zoomed(ZOOM).toPng())
            sheet.blit(cell, 0, row * CELL_H)
            if (row > 0) sheet.hLine(row * CELL_H, 0xCC)
        }
        val contact = File(outDir, "flank-contact-sheet-1x.png")
        contact.writeBytes(sheet.toPng())

        // The decision the pictures are there to confirm, pinned while we are here: below
        // the threshold the flank is the upright mark, fleck for fleck. If this ever fails,
        // an ordinary writing grip has started to thicken — the 10-15x bloom of Phase 22,
        // coming back by a different road.
        val upright = grains["a-upright"]!!
        val writing = GraphiteGrain.of(
            mark(40f, 90f), LEAD, "a-upright".hashCode(), false, 1f,
            GraphiteGrain.Lead.FLANK, grit(),
        )
        assertTrue("a writing grip should lay graphite at all", upright.count > 0)
        org.junit.Assert.assertEquals(
            "a 40 deg writing grip must be the upright mark, fleck for fleck",
            upright.count, writing.count,
        )
        for (i in 0 until upright.count) {
            org.junit.Assert.assertEquals(upright.xy[i * 2], writing.xy[i * 2], 0f)
            org.junit.Assert.assertEquals(upright.xy[i * 2 + 1], writing.xy[i * 2 + 1], 0f)
            org.junit.Assert.assertEquals(upright.level[i], writing.level[i])
        }

        // The first walk's finding, pinned: *"the dabs/flecks seem too blotchy … like each
        // particle just got bigger."* Nothing about a fleck had moved — what had is how much
        // of the decision belonged to the sheet at the coverage a band works at, and this is
        // that, measured (see [clump] and `GraphiteGrain.FLANK_TOOTH_WEIGHT`). The build the
        // hand rejected scored 2.89 here and 1.10 on the hairline it was meant to be a broad
        // version of; the chosen grit scores 1.85 against a structural floor of ~1.5 for a
        // band this tall. The bound is between them, generously, because what is being held
        // is the decision — a band is an even spray, not a field of patches — and not a
        // third decimal place.
        val blotch = clumps["d-shading"]!!
        assertTrue(
            "the shading band came back clumped at $blotch (the rejected build scored 2.89)",
            blotch < 2.2f,
        )

        report.append(
            "  rows top->bottom: " + cells.joinToString(" / ") { it.key } +
                "; the nib travels left to right at y ${MARK_Y.toInt()}\n",
        )
        println(report)
        println("flank renders -> ${outDir.absolutePath}")
        assertTrue("contact sheet written", contact.length() > 0)
    }

    /**
     * **The variant grid** — the first walk's question rendered rather than argued about
     * (Phase 36, 2026-09-19). *"The dabs/flecks seem too blotchy … like each particle just
     * got bigger."* Nothing about a fleck had moved; what had moved was how much of the
     * decision belongs to the **sheet** at the coverage the flank works at, so the grid
     * sweeps exactly that and nothing else — see `GraphiteGrain.FLANK_TOOTH_WEIGHT`.
     *
     * Twelve cells of the shading sweep (**d**: 60° across the lean, 4 px lead, press 0.65):
     *
     *  - **rows**, top to bottom — the flank's tooth **depth** `0.25`, `0.5` (the file's),
     *    `0.75`, `1.0`: how much more readily a peak catches than a hollow, as a fraction
     *    of whatever ink is being laid (`GraphiteGrain.FLANK_TOOTH_DEPTH`). The first walk
     *    swept the tooth *weight* here instead; at a full sweep that number no longer
     *    decides anything, which the grid itself will show — every row of it fitted the
     *    same `lighten` once the sheet's question became a proportional one.
     *  - **columns**, left to right — the patch octave **on** (the first build's 0.6/0.4),
     *    the patch octave **off** (fibre only), and the patch octave off **with the skate
     *    off too**.
     *
     * Above them, spanning the first column, the upright hairline (**a**) at the same zoom:
     * the grit the artist has already approved, which is what the band is supposed to look
     * like once it is spread wide. Below them, the light shading pass (**g**, press 0.3) at
     * whatever `FLANK_GRIT` currently holds.
     *
     * **Every cell is tone-matched before it is looked at**, and that is not a nicety. A
     * smoother `catches` fills *more* sites at the same coverage — the twelve variants run
     * from 17.6 to 40.6 ink/px at a fixed `FLANK_LIGHTEN` — and a sparser mark looks
     * blotchier whatever its correlation is, so an unmatched grid would be a comparison of
     * tones pretending to be a comparison of textures. Each cell therefore **fits its own
     * `lighten` back to [TONE_TARGET]** by bisection, and the fitted figure is printed: the
     * chosen cell hands over its constant along with its picture.
     *
     * Every cell is also written on its own, named for its variant, and the printed table
     * carries `ink /px`, `fill` and [clump] for each. Skipped unless `GPAPER_RENDER_DIR`
     * asks for it: this is a measurement, not a check, and it costs a few seconds.
     */
    @Test
    fun `the grit variants render as a grid for a human to choose from`() {
        val dir = System.getenv("GPAPER_RENDER_DIR")
        Assume.assumeTrue("set GPAPER_RENDER_DIR to render the variant grid", dir != null)
        val outDir = File(dir!!)
        outDir.mkdirs()

        val depths = floatArrayOf(0.25f, 0.5f, 0.75f, 1f)
        val columns = listOf(
            Triple("coarse-on", floatArrayOf(0.6f, 0.4f), 0.16f),
            Triple("coarse-off", floatArrayOf(1f, 0f), 0.16f),
            Triple("coarse-off-skate-off", floatArrayOf(1f, 0f), 0f),
        )

        // The crop every tile is taken from: the nib and the whole band below it.
        val cropX = 20
        val cropY = 28
        val cropW = 240
        val cropH = 122
        val tileW = cropW * ZOOM
        val tileH = cropH * ZOOM
        val gap = 4
        val rows = depths.size + 2 // the reference above, the light pass below
        val sheet = RenderSheet(
            columns.size * tileW + (columns.size - 1) * gap,
            rows * tileH + (rows - 1) * gap,
        )
        for (y in 0 until sheet.h) for (x in 0 until sheet.w) sheet.set(x, y, 0, 0, 0)

        val report = StringBuilder(
            "\nflank grit variants — cell d (60 deg ACROSS the lean), lead $LEAD px, " +
                "press $PRESSURE, every cell thinned to $TONE_TARGET ink/px\n" +
                "  rows: tooth depth ${depths.joinToString(" / ")}\n" +
                "  cols: ${columns.joinToString(" / ") { it.first }}\n",
        )

        fun cellOf(
            leanDeg: Float, azimuthDeg: Float, pressure: Float, g: GraphiteGrain.Grit,
        ): RenderSheet {
            val cell = RenderSheet(CELL_W, CELL_H)
            draw(
                cell,
                GraphiteGrain.of(
                    mark(leanDeg, azimuthDeg, pressure), LEAD, "d-shading".hashCode(), false,
                    1f, GraphiteGrain.Lead.FLANK, g,
                ),
                LEAD,
            )
            return cell
        }

        /** `lighten` lands more ink the smaller it is, so [TONE_TARGET] bisects cleanly. */
        fun fitted(g: GraphiteGrain.Grit, pressure: Float): GraphiteGrain.Grit {
            var lo = 0f
            var hi = 0.95f
            repeat(12) {
                val mid = (lo + hi) * 0.5f
                val at = GraphiteGrain.Grit(
                    g.toothWeight, g.toothFine, g.toothCoarse, g.skateDepth, mid, g.toothDepth,
                )
                if (ink(cellOf(60f, 90f, pressure, at)) > TONE_TARGET) lo = mid else hi = mid
            }
            val l = (lo + hi) * 0.5f
            return GraphiteGrain.Grit(
                g.toothWeight, g.toothFine, g.toothCoarse, g.skateDepth, l, g.toothDepth,
            )
        }

        fun render(
            key: String, leanDeg: Float, azimuthDeg: Float, pressure: Float,
            g: GraphiteGrain.Grit, label: String,
        ): RenderSheet {
            val cell = cellOf(leanDeg, azimuthDeg, pressure, g)
            report.append(stats(cell, label)).append('\n')
            val zoom = cell.sub(cropX, cropY, cropW, cropH).zoomed(ZOOM)
            File(outDir, "grit-$key-${ZOOM}x.png").writeBytes(zoom.toPng())
            return zoom
        }

        // The reference: the upright hairline, the grit the hand already approved. Never
        // thinned — it is the tone the whole argument is measured against.
        sheet.blit(
            render(
                "ref-a-upright", 8f, 90f, PRESSURE, GraphiteGrain.FLANK_GRIT,
                "ref · a upright 8 deg (round regime)",
            ),
            0, 0,
        )
        for ((r, d) in depths.withIndex()) {
            for ((c, col) in columns.withIndex()) {
                val (name, octaves, skate) = col
                val g = fitted(
                    GraphiteGrain.Grit(
                        GraphiteGrain.FLANK_GRIT.toothWeight, octaves[0], octaves[1], skate,
                        FIT_FROM, d,
                    ),
                    PRESSURE,
                )
                val zoom = render(
                    "d-depth%.2f-%s".format(d, name), 60f, 90f, PRESSURE, g,
                    "d · depth %.2f · %-20s lighten %.2f ·".format(d, name, g.lighten),
                )
                sheet.blit(zoom, c * (tileW + gap), (r + 1) * (tileH + gap))
            }
        }
        // And the light pass at whatever the file currently defaults to — untouched, because
        // what is being asked there is exactly whether a light touch is too light.
        sheet.blit(
            render(
                "g-light-default", 60f, 90f, 0.3f, GraphiteGrain.FLANK_GRIT,
                "g · light pass 0.3, FLANK_GRIT as built",
            ),
            0, (depths.size + 1) * (tileH + gap),
        )

        val grid = File(outDir, "grit-grid-${ZOOM}x.png")
        grid.writeBytes(sheet.toPng())
        report.append(
            "  grid rows top->bottom: ref-a-upright / " +
                depths.joinToString(" / ") { "depth %.2f".format(it) } +
                " / g-light-default; cols left->right: " +
                columns.joinToString(" / ") { it.first } + "\n",
        )
        println(report)
        println("grit grid -> ${grid.absolutePath}")
        assertTrue("grid written", grid.length() > 0)
    }

    // ── The device's own input (Phase 36, the second walk) ───────────────────

    /**
     * **The Manta's own shading strokes, rendered offline** — the second walk's finding made
     * measurable (2026-09-19).
     *
     * The first walk's fix was fitted on [mark]: a straight synthetic sweep at press 0.65,
     * travelling exactly across a perfectly steady lean. The hand then reported the band
     * *still* clumpy — *"the individual graphite feels clumpy and not natural at all"* — while
     * the upright hairline on the same page was the fine even grit it should be. A crop of the
     * settled panel showed worm-like filaments and blobs 3–6 px across with bare paper between
     * them. **So the synthetic stroke was not the input the device sees**, and no amount of
     * retuning against it would have closed the gap.
     *
     * This reads the real thing: `probe-tilt`'s CSV, written by the user's own hand on the
     * panel, decoded exactly as [RattaPaperView][com.symmetricalpalmtree.gpaper.ratta] decodes
     * it live — the HAL's degrees undone out of the CSV's `Math.toDegrees`, the polar lean as
     * their hypotenuse, the azimuth as `atan2(tiltY, tiltX)` on an unturned (Manta) panel.
     *
     * It renders each stroke of the `shading` and `flat` passes, the writing pass beside them
     * as the reference grit, and — the point of the exercise — a **substitution grid**: the
     * same real stroke with one column at a time replaced by the synthetic value the harness
     * used to assume. Whichever substitution takes the clumping away is the column that was
     * carrying it.
     *
     * Skipped unless `GPAPER_PROBE_CSV` names the CSV and `GPAPER_RENDER_DIR` says where the
     * pictures go. Nothing here is a check: it is a measurement with pictures.
     */
    @Test
    fun `the probe's own strokes render as the device drew them`() {
        val dir = System.getenv("GPAPER_RENDER_DIR")
        val csv = System.getenv("GPAPER_PROBE_CSV")
        Assume.assumeTrue("set GPAPER_RENDER_DIR and GPAPER_PROBE_CSV", dir != null && csv != null)
        val file = File(csv!!)
        Assume.assumeTrue("probe CSV ${file.absolutePath} not found", file.isFile)
        val outDir = File(dir!!)
        outDir.mkdirs()

        val passes = probeStrokes(file)
        val report = StringBuilder("\nprobe strokes — lead $LEAD px, FLANK, the file's own grit\n")

        // Every stroke of the two shading passes, as the device delivered it.
        val real = LinkedHashMap<String, List<StrokePoint>>()
        for (pass in listOf("shading", "flat")) {
            for ((i, s) in (passes[pass] ?: emptyList()).withIndex()) {
                if (arcOf(s) < 200f) continue
                real["$pass-$i"] = s
            }
        }
        for ((key, s) in real) {
            report.append(renderProbe(outDir, "probe-$key", s, describe(s))).append('\n')
        }

        // The writing pass: the grit the band is supposed to be a broad version of. Short
        // strokes, so they go on one sheet together rather than one file each.
        val writing = (passes["writing"] ?: emptyList()).filter { arcOf(it) > 40f }.take(12)
        if (writing.isNotEmpty()) {
            report.append(renderProbeSheet(outDir, "probe-writing", writing)).append('\n')
        }

        // The substitution grid. On a **window** of one sweep rather than a whole pass:
        // a shading sweep crosses its own earlier passes dozens of times, and ink laid twice
        // over the same paper is a fact about the gesture, not about the grain. One pass of
        // it, clear of its own turnarounds, is the mark to compare with the synthetic one.
        val subject = real.values.maxByOrNull { arcOf(it) }!!
        val subjectKey = real.entries.first { it.value === subject }.key
        val window = window(subject, 700f)
        // And one pass on its own: the longest stretch the hand held one direction for, so
        // the mark cannot cross anything it has already laid.
        val onePass = straightRun(subject)
        report.append("\n  substitutions on a single pass of $subjectKey (${describe(window)})\n")
        val pressMedian = median(window.map { it.pressure })
        val tiltMedian = median(window.map { it.tilt })
        val azMedian = medianAngle(window.map { it.azimuth })
        val variants = listOf<Pair<String, List<StrokePoint>>>(
            "a-real" to window,
            "b-press-0.65" to window.map { it.copy(pressure = PRESSURE) },
            "c-press-flat" to window.map { it.copy(pressure = pressMedian) },
            "d-tilt-flat" to window.map { it.copy(tilt = tiltMedian) },
            "e-azimuth-flat" to window.map { it.copy(azimuth = azMedian) },
            "f-path-smoothed" to smoothed(window, 4),
            "g-path-resampled" to resampled(window, SAMPLE_STEP_PX),
            "h-path-straight" to straightened(window),
            "i-path-straight-even" to resampled(straightened(window), SAMPLE_STEP_PX),
            "j-straight-all-flat" to resampled(straightened(window), SAMPLE_STEP_PX)
                .map { it.copy(pressure = pressMedian, tilt = tiltMedian, azimuth = azMedian) },
            "k-unwound" to unwound(window),
            "l-unwound-even" to unwound(resampled(window, SAMPLE_STEP_PX)),
            "m-one-pass" to onePass,
            "n-one-pass-unwound" to unwound(onePass),
        )
        for ((name, pts) in variants) {
            report.append(renderProbe(outDir, "sub-$name", pts, name)).append('\n')
        }

        // And the mechanism the other way round: the synthetic straight mark with the real
        // path's own wander put back into it, at the real pass's pressure and at 0.65.
        report.append("\n  the synthetic mark, with and without a hand's wander\n")
        for (p in listOf(pressMedian, PRESSURE)) {
            for (wobble in listOf(0f, 0.3f, 0.6f)) {
                val pts = mark(60f, 90f, p, wobble)
                report.append(
                    renderProbe(
                        outDir, "synth-d-press%.2f-wobble%.1f".format(p, wobble), pts,
                        "synthetic d, press %.2f, wobble %.1f px".format(p, wobble),
                    ),
                ).append('\n')
            }
        }

        // The live path and the committed path must be the same mark — the whole premise of
        // Phase 28's preview. Asserted on the real input, in the batches a MotionEvent
        // stream actually arrives in.
        assertSameAsSweep(subject)

        println(report)
        println("probe renders -> ${outDir.absolutePath}")
    }

    /** Every `hover = 0` stroke of each pass, split at `ACTION_DOWN`, decoded as Ratta does. */
    private fun probeStrokes(file: File): Map<String, List<List<StrokePoint>>> {
        val out = LinkedHashMap<String, MutableList<MutableList<StrokePoint>>>()
        var cols: Map<String, Int>? = null
        var cur: MutableList<StrokePoint>? = null
        file.forEachLine { line ->
            if (line.startsWith("#") || line.isBlank()) return@forEachLine
            val f = line.split(",")
            val c = cols
            if (c == null) {
                cols = f.withIndex().associate { (i, k) -> k.trim() to i }
                return@forEachLine
            }
            fun s(k: String) = f[c.getValue(k)].trim()
            if (s("hover") != "0") return@forEachLine
            val action = s("action")
            if (action !in setOf("ACTION_DOWN", "ACTION_MOVE", "ACTION_UP")) return@forEachLine
            val pass = s("pass")
            val list = out.getOrPut(pass) { ArrayList() }
            if (action == "ACTION_DOWN") {
                cur = ArrayList()
                list.add(cur!!)
            }
            val c2 = cur ?: return@forEachLine
            // The CSV wrote `Math.toDegrees(raw)` of axes the HAL sends in **degrees**, so
            // the raw reading comes back by undoing exactly that. The lean is their
            // hypotenuse and the azimuth their angle — RattaPaperView's own decode, on an
            // unturned (Manta) panel.
            val tiltX = Math.toRadians(s("tiltDeg").toDouble())
            val tiltY = Math.toRadians(s("orientDeg").toDouble())
            c2.add(
                StrokePoint(
                    x = s("x").toFloat(),
                    y = s("y").toFloat(),
                    pressure = s("pressure").toFloat(),
                    tilt = Math.toRadians(hypot(tiltX, tiltY)).toFloat(),
                    azimuth = atan2(tiltY, tiltX).toFloat(),
                ),
            )
        }
        return out.mapValues { (_, v) -> v.filter { it.size >= 2 } }
    }

    private fun arcOf(points: List<StrokePoint>): Float {
        var arc = 0f
        for (i in 1 until points.size) {
            arc += hypot(
                (points[i].x - points[i - 1].x).toDouble(),
                (points[i].y - points[i - 1].y).toDouble(),
            ).toFloat()
        }
        return arc
    }

    private fun median(v: List<Float>): Float = v.sorted()[v.size / 2]

    /** A median direction, taken as a vector so the seam at ±π cannot average to its opposite. */
    private fun medianAngle(v: List<Float>): Float {
        var sx = 0.0
        var sy = 0.0
        for (a in v) {
            sx += kotlin.math.cos(a.toDouble())
            sy += kotlin.math.sin(a.toDouble())
        }
        return atan2(sy, sx).toFloat()
    }

    /** What the hand actually did, in the four numbers the grain reads. */
    private fun describe(s: List<StrokePoint>): String {
        val steps = (1 until s.size).map {
            hypot((s[it].x - s[it - 1].x).toDouble(), (s[it].y - s[it - 1].y).toDouble()).toFloat()
        }
        return ("n %d · arc %.0f px · step %.2f px · press %.3f · lean %.1f deg · " +
            "az %.0f deg · across %.2f").format(
            s.size, arcOf(s), median(steps), median(s.map { it.pressure }),
            Math.toDegrees(median(s.map { it.tilt }).toDouble()),
            Math.toDegrees(medianAngle(s.map { it.azimuth }).toDouble()),
            acrossTravel(s),
        )
    }

    /**
     * How much of the lean falls **across** the travel, averaged over the stroke — the
     * `spread` the sweep works at, and therefore how far along every blend in the grain the
     * mark sits. The synthetic cell d is 1.00 by construction; a hand is not.
     */
    private fun acrossTravel(s: List<StrokePoint>): Float {
        var sum = 0f
        var arc = 0f
        for (i in 1 until s.size) {
            val dx = s[i].x - s[i - 1].x
            val dy = s[i].y - s[i - 1].y
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len <= 0f) continue
            val tx = dx / len
            val ty = dy / len
            val a = s[i].azimuth
            sum += kotlin.math.abs(kotlin.math.cos(a) * -ty + kotlin.math.sin(a) * tx) * len
            arc += len
        }
        return if (arc > 0f) sum / arc else 0f
    }

    /** The same path with its positions averaged over ±[half] samples; everything else real. */
    private fun smoothed(s: List<StrokePoint>, half: Int): List<StrokePoint> =
        s.indices.map { i ->
            var sx = 0f
            var sy = 0f
            var n = 0
            for (k in (i - half).coerceAtLeast(0)..(i + half).coerceAtMost(s.size - 1)) {
                sx += s[k].x
                sy += s[k].y
                n++
            }
            s[i].copy(x = sx / n, y = sy / n)
        }

    /** The same path walked at an exactly even [step], as the synthetic mark is sampled. */
    private fun resampled(s: List<StrokePoint>, step: Float): List<StrokePoint> {
        val out = ArrayList<StrokePoint>()
        out.add(s[0])
        var carry = 0f
        for (i in 1 until s.size) {
            val a = s[i - 1]
            val b = s[i]
            val len = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
            if (len <= 0f) continue
            var at = step - carry
            while (at <= len) {
                val t = at / len
                out.add(
                    StrokePoint(
                        x = a.x + t * (b.x - a.x),
                        y = a.y + t * (b.y - a.y),
                        pressure = a.pressure + t * (b.pressure - a.pressure),
                        tilt = a.tilt + t * (b.tilt - a.tilt),
                        azimuth = a.azimuth + t * (b.azimuth - a.azimuth),
                    ),
                )
                at += step
            }
            carry = len - (at - step)
        }
        return out
    }

    /**
     * The same samples laid along a straight line — the stroke's own step lengths kept, its
     * wander thrown away. Pressure, lean and azimuth stay exactly as the hand delivered them.
     */
    private fun straightened(s: List<StrokePoint>): List<StrokePoint> {
        val dirX: Float
        val dirY: Float
        val span = hypot((s.last().x - s[0].x).toDouble(), (s.last().y - s[0].y).toDouble()).toFloat()
        if (span > 1e-3f) {
            dirX = (s.last().x - s[0].x) / span
            dirY = (s.last().y - s[0].y) / span
        } else {
            dirX = 1f
            dirY = 0f
        }
        var at = 0f
        return s.mapIndexed { i, p ->
            if (i > 0) {
                at += hypot(
                    (s[i].x - s[i - 1].x).toDouble(), (s[i].y - s[i - 1].y).toDouble(),
                ).toFloat()
            }
            p.copy(x = s[0].x + dirX * at, y = s[0].y + dirY * at)
        }
    }


    /**
     * The same stroke with its **wander taken out and nothing else** — the substitution the
     * whole grid turns on.
     *
     * Straightening a path naively moves the travel direction, which moves the lean's angle
     * to it, which moves the spread, the width, the paleness and the tone: the mark that
     * comes back is a different mark and proves nothing. So the path is laid straight along
     * `+x` **keeping each sample's own step length**, and each sample's azimuth is carried
     * over as the angle it made with the travel at that instant. Pressure, lean, spacing and
     * the lean-to-travel angle are the hand's, sample for sample; only the turning is gone.
     */
    private fun unwound(s: List<StrokePoint>): List<StrokePoint> {
        val out = ArrayList<StrokePoint>(s.size)
        var at = 0f
        for (i in s.indices) {
            val a = s[(i - 1).coerceAtLeast(0)]
            val b = s[(i + 1).coerceAtMost(s.size - 1)]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val travel = atan2(dy.toDouble(), dx.toDouble()).toFloat()
            if (i > 0) {
                at += hypot(
                    (s[i].x - s[i - 1].x).toDouble(), (s[i].y - s[i - 1].y).toDouble(),
                ).toFloat()
            }
            out.add(s[i].copy(x = 100f + at, y = 300f, azimuth = s[i].azimuth - travel))
        }
        return out
    }


    /**
     * The longest stretch of a stroke the hand held roughly one direction for — one pass of
     * a sweep, which is the only part of a real shading gesture that can be compared with a
     * synthetic straight mark at all. (The user's shading passes turn around every 150–270
     * px; everything longer crosses ink it has already laid.)
     */
    private fun straightRun(points: List<StrokePoint>): List<StrokePoint> {
        var bestArc = 0f
        var bestFrom = 0
        var bestTo = 0
        var i = 0
        while (i < points.size - 1) {
            var sx = 0f
            var sy = 0f
            var arc = 0f
            var j = i + 1
            while (j < points.size) {
                val dx = points[j].x - points[j - 1].x
                val dy = points[j].y - points[j - 1].y
                val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (len > 0f) {
                    val mean = atan2((sy + dy).toDouble(), (sx + dx).toDouble()).toFloat()
                    val ang = atan2(dy.toDouble(), dx.toDouble()).toFloat()
                    var d = ang - mean
                    while (d > Math.PI) d -= (2.0 * Math.PI).toFloat()
                    while (d < -Math.PI) d += (2.0 * Math.PI).toFloat()
                    if (kotlin.math.abs(d) > deg(35f) && arc > 0f) break
                    sx += dx
                    sy += dy
                    arc += len
                }
                j++
            }
            if (arc > bestArc) {
                bestArc = arc
                bestFrom = i
                bestTo = j - 1
            }
            i = maxOf(i + 1, j - 1)
        }
        return points.subList(bestFrom, (bestTo + 1).coerceAtMost(points.size))
    }

    /** One real stroke on its own sheet, with a 3x crop of its middle beside it. */
    private fun renderProbe(
        outDir: File,
        key: String,
        points: List<StrokePoint>,
        label: String,
    ): String {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < x0) x0 = p.x
            if (p.y < y0) y0 = p.y
            if (p.x > x1) x1 = p.x
            if (p.y > y1) y1 = p.y
        }
        val pad = 110f
        val shifted = points.map { it.copy(x = it.x - x0 + pad, y = it.y - y0 + pad) }
        val w = (x1 - x0 + 2 * pad).toInt().coerceIn(64, 2600)
        val h = (y1 - y0 + 2 * pad).toInt().coerceIn(64, 2600)
        val sheet = RenderSheet(w, h)
        val grain = GraphiteGrain.of(
            shifted, LEAD, key.hashCode(), false, 1f, GraphiteGrain.Lead.FLANK, grit(),
        )
        draw(sheet, grain, LEAD)
        File(outDir, "$key-1x.png").writeBytes(sheet.toPng())
        // The crop: a window on the middle of the stroke, where the hand had settled.
        val mid = shifted[shifted.size / 2]
        val cw = 200
        val ch = 140
        val cx = (mid.x.toInt() - cw / 2).coerceIn(0, (w - cw).coerceAtLeast(0))
        val cy = (mid.y.toInt() - ch / 2).coerceIn(0, (h - ch).coerceAtLeast(0))
        File(outDir, "$key-${ZOOM}x.png")
            .writeBytes(sheet.sub(cx, cy, cw.coerceAtMost(w), ch.coerceAtMost(h)).zoomed(ZOOM).toPng())
        val patch = patch(sheet)
        return ("  %-26s %-92s flecks %6d · ink %5.2f /px · fill %.2f · clump %.2f").format(
            key, label, grain.count, inkPerPx(sheet, arcOf(points)), patch.fill, patch.clump,
        )
    }

    /** A pass's short strokes, all on one sheet, at their own positions. */
    private fun renderProbeSheet(
        outDir: File,
        key: String,
        strokes: List<List<StrokePoint>>,
    ): String {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (s in strokes) for (p in s) {
            if (p.x < x0) x0 = p.x
            if (p.y < y0) y0 = p.y
            if (p.x > x1) x1 = p.x
            if (p.y > y1) y1 = p.y
        }
        val pad = 30f
        val w = (x1 - x0 + 2 * pad).toInt().coerceIn(64, 2600)
        val h = (y1 - y0 + 2 * pad).toInt().coerceIn(64, 2600)
        val sheet = RenderSheet(w, h)
        var flecks = 0
        var arc = 0f
        for ((i, s) in strokes.withIndex()) {
            val shifted = s.map { it.copy(x = it.x - x0 + pad, y = it.y - y0 + pad) }
            val grain = GraphiteGrain.of(
                shifted, LEAD, "$key-$i".hashCode(), false, 1f, GraphiteGrain.Lead.FLANK, grit(),
            )
            draw(sheet, grain, LEAD)
            flecks += grain.count
            arc += arcOf(s)
        }
        File(outDir, "$key-1x.png").writeBytes(sheet.toPng())
        File(outDir, "$key-${ZOOM}x.png").writeBytes(sheet.zoomed(ZOOM).toPng())
        val patch = patch(sheet)
        return ("  %-26s %-92s flecks %6d · ink %5.2f /px · fill %.2f · clump %.2f").format(
            key, "%d strokes of the writing grip".format(strokes.size), flecks,
            inkPerPx(sheet, arc), patch.fill, patch.clump,
        )
    }

    /** Every drop of darkness on the sheet, per px of the path that laid it. */
    private fun inkPerPx(sheet: RenderSheet, arc: Float): Float {
        var total = 0f
        for (y in 0 until sheet.h) for (x in 0 until sheet.w) total += sheet.darkness(x, y)
        return if (arc > 0f) total / arc else 0f
    }

    private class Patch(val fill: Float, val clump: Float, val tiles: Int)

    /**
     * [clump] and the mark's fill, for a mark that goes **where the hand went** rather than
     * straight across a cell — measured over the mark's own interior.
     *
     * The straight-mark figures above take a fixed window of rows; a real stroke wanders, so
     * the window has to be found. A tile counts as interior when it and all eight of its
     * neighbours carry ink, which keeps the band's own edges (and the bare paper beyond
     * them) out of a figure that is about texture inside the mark. Otherwise it is exactly
     * [clump]'s measure: the tiles' deviation over the deviation independent pixels would
     * give, so 1 is an even spray and larger is patchy.
     */
    private fun patch(sheet: RenderSheet, tile: Int = 5, floor: Float = 0.02f): Patch {
        val tw = sheet.w / tile
        val th = sheet.h / tile
        if (tw < 3 || th < 3) return Patch(Float.NaN, Float.NaN, 0)
        val means = FloatArray(tw * th)
        for (ty in 0 until th) {
            for (tx in 0 until tw) {
                var m = 0f
                for (y in ty * tile until (ty + 1) * tile) {
                    for (x in tx * tile until (tx + 1) * tile) m += sheet.darkness(x, y)
                }
                means[ty * tw + tx] = m / (tile * tile)
            }
        }
        var pixelSum = 0.0
        var pixelSq = 0.0
        var pixels = 0
        val inside = ArrayList<Double>()
        for (ty in 1 until th - 1) {
            for (tx in 1 until tw - 1) {
                var ok = true
                for (dy in -1..1) for (dx in -1..1) {
                    if (means[(ty + dy) * tw + (tx + dx)] <= floor) ok = false
                }
                if (!ok) continue
                inside.add(means[ty * tw + tx].toDouble())
                for (y in ty * tile until (ty + 1) * tile) {
                    for (x in tx * tile until (tx + 1) * tile) {
                        val d = sheet.darkness(x, y).toDouble()
                        pixelSum += d
                        pixelSq += d * d
                        pixels++
                    }
                }
            }
        }
        if (inside.size < 8 || pixels == 0) return Patch(Float.NaN, Float.NaN, inside.size)
        val pixelMean = pixelSum / pixels
        val pixelVar = (pixelSq / pixels - pixelMean * pixelMean).coerceAtLeast(1e-12)
        val tileMean = inside.average()
        val tileVar = inside.sumOf { (it - tileMean) * (it - tileMean) } / (inside.size - 1)
        return Patch(
            tileMean.toFloat(),
            (sqrt(tileVar) / sqrt(pixelVar / (tile * tile))).toFloat(),
            inside.size,
        )
    }

    /**
     * The live path and the committed path lay the same mark on the same input — asserted
     * here on the **device's** samples and in the batches an event stream arrives in, not
     * on a synthetic line. `GraphiteGrainIncrementalTest` pins the invariant; this is the
     * one place it meets a real hand's jitter, where a filter that drifted between the two
     * would have somewhere to hide.
     */
    private fun assertSameAsSweep(points: List<StrokePoint>) {
        val whole = GraphiteGrain.of(points, LEAD, 0x5e17, false, 1f, GraphiteGrain.Lead.FLANK)
        val sweep = GraphiteGrain.begin(LEAD, 0x5e17, 1f, GraphiteGrain.Lead.FLANK)
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val ls = ArrayList<Int>()
        fun take(g: GraphiteGrain.Grain) {
            for (i in 0 until g.count) {
                xs.add(g.xy[i * 2])
                ys.add(g.xy[i * 2 + 1])
                ls.add(g.level[i])
            }
        }
        var at = 1
        var batch = 1
        while (at < points.size) {
            val end = (at + batch).coerceAtMost(points.size)
            take(sweep.extend(points.subList(0, end)))
            at = end
            batch = batch % 3 + 1
        }
        take(sweep.finish(points))
        org.junit.Assert.assertEquals(
            "the live sweep and the committed mark must lay the same flecks",
            whole.count, xs.size,
        )
        for (i in 0 until whole.count) {
            org.junit.Assert.assertEquals(whole.xy[i * 2], xs[i], 0f)
            org.junit.Assert.assertEquals(whole.xy[i * 2 + 1], ys[i], 0f)
            org.junit.Assert.assertEquals(whole.level[i], ls[i])
        }
    }

}
