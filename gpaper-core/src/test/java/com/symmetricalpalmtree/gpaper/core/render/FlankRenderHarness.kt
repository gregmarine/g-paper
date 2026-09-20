package com.symmetricalpalmtree.gpaper.core.render

import com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        val x0 = 80
        val x1 = 300
        val rowMass = FloatArray(sheet.h)
        var total = 0f
        for (y in 0 until sheet.h) {
            var m = 0f
            for (x in x0 until x1) m += sheet.darkness(x, y)
            rowMass[y] = m
            total += m
        }
        if (total <= 0f) return "  %-38s NO INK".format(label)
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
        val extent = (hi - lo + 1).toFloat()
        val perPx = total / (x1 - x0)
        // Darkness averaged over the inked band: what "the flank pales as it widens" means
        // in a number. 1.0 is solid black paper to paper.
        val fill = total / ((x1 - x0) * extent)
        return ("  %-38s extent %5.1f px (%4.1f x lead) · band y %3d..%3d (nib at %d) · " +
            "ink %.2f /px · fill %.2f").format(
            label, extent, extent / LEAD, lo, hi, MARK_Y.toInt(), perPx, fill,
        )
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
        for ((row, c) in cells.withIndex()) {
            val cell = RenderSheet(CELL_W, CELL_H)
            val points = mark(c.leanDeg, c.azimuthDeg, c.pressure)
            val grain = GraphiteGrain.of(
                points, LEAD, c.key.hashCode(), lead = GraphiteGrain.Lead.FLANK,
            )
            grains[c.key] = grain
            draw(cell, grain, LEAD)
            report.append(stats(cell, c.title)).append('\n')
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
            mark(40f, 90f), LEAD, "a-upright".hashCode(), lead = GraphiteGrain.Lead.FLANK,
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

        report.append(
            "  rows top->bottom: " + cells.joinToString(" / ") { it.key } +
                "; the nib travels left to right at y ${MARK_Y.toInt()}\n",
        )
        println(report)
        println("flank renders -> ${outDir.absolutePath}")
        assertTrue("contact sheet written", contact.length() > 0)
    }
}
