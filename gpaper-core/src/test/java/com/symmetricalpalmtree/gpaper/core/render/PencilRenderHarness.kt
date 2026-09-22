package com.symmetricalpalmtree.gpaper.core.render

import com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The shared offline raster target — see [RenderSheet]. */
private typealias Sheet = RenderSheet

/**
 * **Render a new lead size to a PNG before it reaches a panel** — `CLAUDE.md`'s rule, and
 * the tool that keeps it. Arc 44 "Pencils" gives NSE · Sketch five lead sizes (1.2 / 2 / 4
 * / 7 / 12 px) where it had one, so five leads reach a Supernote for the first time; this
 * writes a contact sheet of all five, at three shades, straight and curved, into
 * `gpaper-core/build/pencil-renders/` for a human to look at. That habit has already caught
 * two flaws no unit test would have — a fleck wider than the lead that laid it (0.1.24) and
 * the combed grain of 0.1.13 — both invisible in an aggregate statistic and obvious in a
 * picture.
 *
 * **What this is honest about.** It drives the real [GraphiteGrain] — the half that decides
 * *where the graphite lands*, which is pure Kotlin and is where every fault in that list
 * lived. It cannot drive `StrokeRenderer`, which is where the ink is *put* there: that is
 * `android.graphics.Canvas` code and there is no way to run it off a device without
 * Robolectric or an instrumented test, neither of which this repo has a dependency for.
 * (`java.awt` is no help either — an Android module's unit tests compile against
 * `android.jar`, which has no AWT and no ImageIO. Hence the small rasteriser and PNG writer
 * below, built on `java.util.zip` alone: no new dependency, in a repo whose whole test
 * stack is JUnit 4.)
 *
 * So the fleck loop here is a hand-mirror of `StrokeRenderer.drawPencil` — same levels in
 * the same order, same [GraphiteGrain.fleckPx] diameters, same [GraphiteGrain.levelAlpha]
 * alphas, each fleck a round cap, which is a disc of diameter `strokeWidth`. That makes it
 * **a third rasteriser**, and this project already knows what that costs: Skia into a
 * hardware `RenderNode` and Skia into a software `Canvas(bitmap)` lay the same flecks in the
 * same places and about 40 % apart in tone (measured NA5C, 0.1.13). So read **geometry** off
 * these images — where the flecks land, how wide the mark is against its nominal lead,
 * whether anything combs, connects or beads at a cap — and read **tone** only as a
 * comparison between the cells here, never as what a panel will show. The panel's answer
 * comes from the walk.
 *
 * Output is under `build/`, so nothing here is committed. Re-runs are byte-identical: every
 * mark is seeded from its own cell's name, exactly as a real stroke is seeded from its id.
 */
class PencilRenderHarness {

    private companion object {
        /** Arc 44's five lead sizes, in px, as the sketch face will offer them. */
        val LEADS = floatArrayOf(1.2f, 2f, 4f, 7f, 12f)

        /**
         * Three of arc 44's fifteen shade levels (level *n* = the grey `n × 0x11`): the
         * darkest, the default `#555555`, and one from the pale end — one shade from each
         * rung of `RattaInkMap.pencilPreviewFor`'s ladder.
         */
        val SHADE_LEVELS = intArrayOf(0, 5, 10)

        /** The pressure Ratta's `PENCIL` bakes at (`RattaPaperView.PENCIL_BAKE_PRESSURE`). */
        const val BAKE_PRESSURE = 0.5f

        /** Cell geometry (px, 1×): a straight mark over a curved one, with room for a 12 px lead. */
        const val CELL_W = 360
        const val CELL_H = 150
        const val STRAIGHT_Y = 42f
        const val CURVE_Y = 100f
        const val CURVE_AMP = 26f
        const val MARK_X0 = 20f
        const val MARK_X1 = 340f

        /** Sample spacing along a mark, in px — about what a pen delivers at a working pace. */
        const val SAMPLE_STEP_PX = 2f

        /** Magnification for the second sheet: nearest-neighbour, so a fleck stays a fleck. */
        const val ZOOM = 3
    }

    // ── The marks ───────────────────────────────────────────────────────────────

    private fun shadeGrey(level: Int): Int = level * 0x11

    private fun shadeHex(level: Int): String =
        "#%02X%02X%02X".format(shadeGrey(level), shadeGrey(level), shadeGrey(level))

    /** A straight horizontal mark, sampled at [SAMPLE_STEP_PX]. */
    private fun straight(): List<StrokePoint> {
        val out = ArrayList<StrokePoint>()
        var x = MARK_X0
        while (x <= MARK_X1) {
            out += StrokePoint(x = x, y = STRAIGHT_Y, pressure = BAKE_PRESSURE, tilt = 0f)
            x += SAMPLE_STEP_PX
        }
        return out
    }

    /**
     * One period of a sine — both signs of curvature and a steep middle, which is where a
     * cross-section laid across the wrong direction of travel shows itself. Stepped a little
     * shorter in x so the arc-length spacing stays near [SAMPLE_STEP_PX] on the steep part.
     */
    private fun curved(): List<StrokePoint> {
        val out = ArrayList<StrokePoint>()
        val span = MARK_X1 - MARK_X0
        var x = MARK_X0
        while (x <= MARK_X1) {
            val t = (x - MARK_X0) / span
            out += StrokePoint(
                x = x,
                y = CURVE_Y + CURVE_AMP * sin(t * 2f * Math.PI.toFloat()),
                pressure = BAKE_PRESSURE,
                tilt = 0f,
            )
            x += SAMPLE_STEP_PX * 0.8f
        }
        return out
    }

    /**
     * The hand-mirror of `StrokeRenderer.drawPencil`: one pass per darkness level, each
     * fleck a disc of [GraphiteGrain.fleckPx] diameter at the lead's alpha scaled by
     * [GraphiteGrain.levelAlpha]. The pass order matters as little here as it does there —
     * flecks composite normally — but it is kept identical so the mirror stays readable
     * against the original.
     */
    private fun drawPencil(sheet: Sheet, points: List<StrokePoint>, grey: Int, width: Float, seed: Int) {
        val grain = GraphiteGrain.of(points, width, seed)
        if (grain.count == 0) return
        for (level in 0 until GraphiteGrain.LEVELS) {
            val d = GraphiteGrain.fleckPx(level, width)
            val alpha = GraphiteGrain.levelAlpha(level)
            for (i in 0 until grain.count) {
                if (grain.level[i] != level) continue
                sheet.disc(grain.xy[i * 2], grain.xy[i * 2 + 1], d, grey, grey, grey, alpha)
            }
        }
    }

    private fun cell(lead: Float, shadeLevel: Int): Sheet {
        val sheet = Sheet(CELL_W, CELL_H)
        val grey = shadeGrey(shadeLevel)
        drawPencil(sheet, straight(), grey, lead, "lead-$lead-shade-$shadeLevel-straight".hashCode())
        drawPencil(sheet, curved(), grey, lead, "lead-$lead-shade-$shadeLevel-curved".hashCode())
        return sheet
    }

    /**
     * The straight mark's ink, measured threshold-free the way this project settles width
     * arguments: a per-row mass profile across the mark, and the extent holding the middle
     * 98 % of it. A threshold high enough to segment cleanly discards the pale outer flecks
     * and reads ~30 % narrow (0.1.11) — which is the mistake this avoids.
     */
    private fun straightMarkStats(cell: Sheet, lead: Float): String {
        // A band around the straight mark only, and clear of its caps. The curved mark's
        // crest reaches y = 74 at x = 260, and half a 12 px lead either side of that is
        // inside any sane band — so the window stops at x = 200, where the curve is 90 or
        // lower. Getting this wrong reads as the widest lead spreading three times its
        // nominal width, which is what the first run of this said.
        val x0 = 60
        val x1 = 200
        val yTop = 16
        val yBot = 70
        val rowMass = FloatArray(yBot - yTop)
        var total = 0f
        for (y in yTop until yBot) {
            var m = 0f
            for (x in x0 until x1) m += cell.darkness(x, y)
            rowMass[y - yTop] = m
            total += m
        }
        if (total <= 0f) return "  lead %5.2f px → NO INK".format(lead)
        var run = 0f
        var lo = yTop
        for (i in rowMass.indices) {
            run += rowMass[i]
            if (run >= 0.01f * total) { lo = yTop + i; break }
        }
        run = 0f
        var hi = yBot - 1
        for (i in rowMass.indices.reversed()) {
            run += rowMass[i]
            if (run >= 0.01f * total) { hi = yTop + i; break }
        }
        val extent = (hi - lo + 1).toFloat()
        val maxFleck = (0 until GraphiteGrain.LEVELS).maxOf { GraphiteGrain.fleckPx(it, lead) }
        val perPx = total / (x1 - x0)
        return ("  lead %5.2f px → inked extent %4.1f px (%.2f× nominal) · " +
            "max fleck %.2f px · ink mass %.2f /px").format(lead, extent, extent / lead, maxFleck, perPx)
    }

    @Test
    fun `the five lead sizes render to PNGs for a human to look at`() {
        val outDir = File("build/pencil-renders")
        outDir.mkdirs()

        val sheet = Sheet(SHADE_LEVELS.size * CELL_W, LEADS.size * CELL_H)
        val report = StringBuilder(
            "\npencil lead renders — pressure $BAKE_PRESSURE, tilt 0, shade level 0 (#000000)\n",
        )
        for ((row, lead) in LEADS.withIndex()) {
            for ((col, level) in SHADE_LEVELS.withIndex()) {
                val c = cell(lead, level)
                sheet.blit(c, col * CELL_W, row * CELL_H)
                if (level == 0) {
                    report.append(straightMarkStats(c, lead)).append('\n')
                    // The one crop small enough to read a fleck in: the darkest shade's
                    // straight mark and the head of its curve, magnified.
                    val crop = c.sub(20, 10, 220, 130)
                    File(outDir, "pencil-lead-${lead}px-3x.png").writeBytes(crop.zoomed(ZOOM).toPng())
                }
            }
            if (row > 0) sheet.hLine(row * CELL_H, 0xCC)
        }
        for (col in 1 until SHADE_LEVELS.size) sheet.vLine(col * CELL_W, 0xCC)

        val oneX = File(outDir, "pencil-leads-1x.png")
        val threeX = File(outDir, "pencil-leads-3x.png")
        oneX.writeBytes(sheet.toPng())
        threeX.writeBytes(sheet.zoomed(ZOOM).toPng())

        report.append(
            "  sheet: rows top→bottom = leads ${LEADS.joinToString(" / ")} px; " +
                "columns left→right = shades ${SHADE_LEVELS.joinToString(" / ") { shadeHex(it) }}; " +
                "each cell holds a straight mark over a curved one\n",
        )
        println(report)
        println("pencil renders → ${outDir.absolutePath}")

        assertTrue("1× sheet written", oneX.length() > 0)
        assertTrue("3× sheet written", threeX.length() > 0)

        // The rule the habit exists for, pinned while we are here: a fleck is never wider
        // than the lead that lays it (0.1.24), floored so a hairline still bakes as graphite
        // rather than as nothing.
        val floor = GraphiteGrain.fleckPx(0)
        for (lead in LEADS) {
            for (level in 0 until GraphiteGrain.LEVELS) {
                val d = GraphiteGrain.fleckPx(level, lead)
                assertTrue(
                    "fleck $d px on a $lead px lead",
                    d <= max(lead, floor) + 1e-4f && d >= min(floor, lead) - 1e-4f,
                )
            }
        }
    }
}
