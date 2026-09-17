package com.symmetricalpalmtree.gpaper.core.render

import com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

        /** Sub-samples per axis when a fleck's disc is covered onto the pixel grid. */
        const val AA_STEPS = 4
    }

    // ── A one-file raster target: 8-bit RGB, white paper, SrcOver discs ──────────

    private class Sheet(val w: Int, val h: Int) {
        /** Row-major RGB, three bytes a pixel, starting as bare paper. */
        val px = ByteArray(w * h * 3) { 0xFF.toByte() }

        fun set(x: Int, y: Int, r: Int, g: Int, b: Int) {
            val i = (y * w + x) * 3
            px[i] = r.toByte()
            px[i + 1] = g.toByte()
            px[i + 2] = b.toByte()
        }

        fun rgb(x: Int, y: Int): Triple<Int, Int, Int> {
            val i = (y * w + x) * 3
            return Triple(px[i].toInt() and 0xFF, px[i + 1].toInt() and 0xFF, px[i + 2].toInt() and 0xFF)
        }

        fun hLine(y: Int, v: Int) {
            if (y !in 0 until h) return
            for (x in 0 until w) set(x, y, v, v, v)
        }

        fun vLine(x: Int, v: Int) {
            if (x !in 0 until w) return
            for (y in 0 until h) set(x, y, v, v, v)
        }

        fun blit(src: Sheet, dx: Int, dy: Int) {
            for (y in 0 until src.h) {
                val ty = dy + y
                if (ty !in 0 until h) continue
                for (x in 0 until src.w) {
                    val tx = dx + x
                    if (tx !in 0 until w) continue
                    val (r, g, b) = src.rgb(x, y)
                    set(tx, ty, r, g, b)
                }
            }
        }

        /**
         * One fleck: a disc of diameter [d] centred at ([cx], [cy]), composited SrcOver at
         * [alpha] (0…1). Coverage is sub-sampled [AA_STEPS]² per pixel rather than computed
         * analytically — a fleck is about a pixel across, so what matters is that a partly
         * covered pixel is partly inked at all, and a smoothed edge is what Skia's round cap
         * gives too.
         */
        fun disc(cx: Float, cy: Float, d: Float, r: Int, g: Int, b: Int, alpha: Float) {
            val rad = d / 2f
            val x0 = max(0, floor(cx - rad).toInt())
            val x1 = min(w - 1, ceil(cx + rad).toInt())
            val y0 = max(0, floor(cy - rad).toInt())
            val y1 = min(h - 1, ceil(cy + rad).toInt())
            val step = 1f / AA_STEPS
            val rr = rad * rad
            for (y in y0..y1) {
                for (x in x0..x1) {
                    var hits = 0
                    for (sy in 0 until AA_STEPS) {
                        val py = y + (sy + 0.5f) * step
                        for (sx in 0 until AA_STEPS) {
                            val pxx = x + (sx + 0.5f) * step
                            val dx = pxx - cx
                            val dy = py - cy
                            if (dx * dx + dy * dy <= rr) hits++
                        }
                    }
                    if (hits == 0) continue
                    val a = alpha * hits / (AA_STEPS * AA_STEPS)
                    val (dr, dg, db) = rgb(x, y)
                    set(
                        x, y,
                        (dr + (r - dr) * a).toInt().coerceIn(0, 255),
                        (dg + (g - dg) * a).toInt().coerceIn(0, 255),
                        (db + (b - db) * a).toInt().coerceIn(0, 255),
                    )
                }
            }
        }

        fun sub(x0: Int, y0: Int, w0: Int, h0: Int): Sheet {
            val out = Sheet(w0, h0)
            for (y in 0 until h0) {
                for (x in 0 until w0) {
                    val (r, g, b) = rgb((x0 + x).coerceIn(0, w - 1), (y0 + y).coerceIn(0, h - 1))
                    out.set(x, y, r, g, b)
                }
            }
            return out
        }

        /** Nearest-neighbour magnification — a smoothed one erases exactly what is being looked at. */
        fun zoomed(factor: Int): Sheet {
            val out = Sheet(w * factor, h * factor)
            for (y in 0 until out.h) {
                for (x in 0 until out.w) {
                    val (r, g, b) = rgb(x / factor, y / factor)
                    out.set(x, y, r, g, b)
                }
            }
            return out
        }

        /** Ink darkness on white paper at ([x], [y]): 0 bare … 1 solid. */
        fun darkness(x: Int, y: Int): Float {
            val (r, g, b) = rgb(x, y)
            return 1f - (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        /** The smallest legal PNG that holds this: one IHDR, one IDAT, one IEND, no filtering. */
        fun toPng(): ByteArray {
            val raw = ByteArray(h * (w * 3 + 1))
            var o = 0
            for (y in 0 until h) {
                raw[o++] = 0 // filter type 0 (None) — the encoder's job here is to be obvious
                System.arraycopy(px, y * w * 3, raw, o, w * 3)
                o += w * 3
            }
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val ihdr = ByteArrayOutputStream()
            writeInt(ihdr, w)
            writeInt(ihdr, h)
            ihdr.write(8) // bit depth
            ihdr.write(2) // colour type: truecolour RGB
            ihdr.write(0) // deflate
            ihdr.write(0) // adaptive filtering
            ihdr.write(0) // no interlace
            writeChunk(out, "IHDR", ihdr.toByteArray())
            writeChunk(out, "IDAT", deflate(raw))
            writeChunk(out, "IEND", ByteArray(0))
            return out.toByteArray()
        }

        private fun writeInt(s: ByteArrayOutputStream, v: Int) {
            s.write((v ushr 24) and 0xFF)
            s.write((v ushr 16) and 0xFF)
            s.write((v ushr 8) and 0xFF)
            s.write(v and 0xFF)
        }

        private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            writeInt(out, data.size)
            val typed = type.toByteArray(Charsets.US_ASCII)
            out.write(typed)
            out.write(data)
            val crc = CRC32()
            crc.update(typed)
            crc.update(data)
            writeInt(out, crc.value.toInt())
        }

        private fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_SPEED)
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(data.size / 2)
            val buf = ByteArray(1 shl 16)
            while (!d.finished()) {
                val n = d.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
            d.end()
            return out.toByteArray()
        }
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
