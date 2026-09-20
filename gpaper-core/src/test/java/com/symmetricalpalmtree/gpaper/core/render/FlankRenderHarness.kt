package com.symmetricalpalmtree.gpaper.core.render

import com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
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

        /**
         * The ink/px the first build of the flank laid in cell d — the tone the artist has
         * in front of them, and what every variant is matched to so the grid compares
         * textures rather than tones.
         */
        const val TONE_TARGET = 17.59f

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
     * `GPAPER_FLANK_TOOTH` / `_FINE` / `_COARSE` / `_SKATE` / `_LIGHTEN`.
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
        )
    }

    /** A straight horizontal mark travelling `+x`, at a fixed grip. */
    private fun mark(leanDeg: Float, azimuthDeg: Float, pressure: Float = PRESSURE): List<StrokePoint> {
        val out = ArrayList<StrokePoint>()
        var x = MARK_X0
        while (x <= MARK_X1) {
            out += StrokePoint(
                x = x,
                y = MARK_Y,
                pressure = pressure,
                tilt = deg(leanDeg),
                azimuth = deg(azimuthDeg),
            )
            x += SAMPLE_STEP_PX
        }
        return out
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
     *  - **rows**, top to bottom — the flank's tooth weight `0.55` (the first build's,
     *    i.e. the round lead's), `0.35`, `0.20`, `0.00`.
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

        val weights = floatArrayOf(0.55f, 0.35f, 0.20f, 0f)
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
        val rows = weights.size + 2 // the reference above, the light pass below
        val sheet = RenderSheet(
            columns.size * tileW + (columns.size - 1) * gap,
            rows * tileH + (rows - 1) * gap,
        )
        for (y in 0 until sheet.h) for (x in 0 until sheet.w) sheet.set(x, y, 0, 0, 0)

        val report = StringBuilder(
            "\nflank grit variants — cell d (60 deg ACROSS the lean), lead $LEAD px, " +
                "press $PRESSURE, every cell thinned to $TONE_TARGET ink/px\n" +
                "  rows: tooth weight ${weights.joinToString(" / ")}\n" +
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
                    g.toothWeight, g.toothFine, g.toothCoarse, g.skateDepth, mid,
                )
                if (ink(cellOf(60f, 90f, pressure, at)) > TONE_TARGET) lo = mid else hi = mid
            }
            val l = (lo + hi) * 0.5f
            return GraphiteGrain.Grit(g.toothWeight, g.toothFine, g.toothCoarse, g.skateDepth, l)
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
        for ((r, w) in weights.withIndex()) {
            for ((c, col) in columns.withIndex()) {
                val (name, octaves, skate) = col
                val g = fitted(
                    GraphiteGrain.Grit(w, octaves[0], octaves[1], skate, FIT_FROM), PRESSURE,
                )
                val zoom = render(
                    "d-tooth%.2f-%s".format(w, name), 60f, 90f, PRESSURE, g,
                    "d · tooth %.2f · %-20s lighten %.2f ·".format(w, name, g.lighten),
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
            0, (weights.size + 1) * (tileH + gap),
        )

        val grid = File(outDir, "grit-grid-${ZOOM}x.png")
        grid.writeBytes(sheet.toPng())
        report.append(
            "  grid rows top->bottom: ref-a-upright / " +
                weights.joinToString(" / ") { "tooth %.2f".format(it) } +
                " / g-light-default; cols left->right: " +
                columns.joinToString(" / ") { it.first } + "\n",
        )
        println(report)
        println("grit grid -> ${grid.absolutePath}")
        assertTrue("grid written", grid.length() > 0)
    }
}
