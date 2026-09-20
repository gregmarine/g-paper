package com.symmetricalpalmtree.gpaper.core.render

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A one-file raster target for the offline render habit: 8-bit RGB, white paper, SrcOver
 * discs, and a PNG writer built on `java.util.zip` alone.
 *
 * **`CLAUDE.md`'s rule — render a new lead to a PNG before it reaches a panel** — needs
 * somewhere to render *to*, and an Android module's unit tests have no AWT, no ImageIO and
 * (in this repo) no Robolectric. Hence this. It lives on its own rather than inside one
 * harness because there is more than one thing to look at now: the leads
 * ([PencilRenderHarness]) and the flank ([FlankRenderHarness], Phase 36). **A third
 * rasteriser is a thing this project already knows the cost of** — Skia into a hardware
 * `RenderNode` and Skia into a software `Canvas(bitmap)` lay the same flecks in the same
 * places and about 40 % apart in tone (measured NA5C, 0.1.13) — so two harnesses sharing
 * one is the point: read **geometry** off these images and read **tone** only as a
 * comparison between cells rendered here.
 */
internal class RenderSheet(val w: Int, val h: Int) {

    private companion object {
        /** Sub-samples per axis when a fleck's disc is covered onto the pixel grid. */
        const val AA_STEPS = 4
    }

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

    fun blit(src: RenderSheet, dx: Int, dy: Int) {
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

    fun sub(x0: Int, y0: Int, w0: Int, h0: Int): RenderSheet {
        val out = RenderSheet(w0, h0)
        for (y in 0 until h0) {
            for (x in 0 until w0) {
                val (r, g, b) = rgb((x0 + x).coerceIn(0, w - 1), (y0 + y).coerceIn(0, h - 1))
                out.set(x, y, r, g, b)
            }
        }
        return out
    }

    /** Nearest-neighbour magnification — a smoothed one erases exactly what is being looked at. */
    fun zoomed(factor: Int): RenderSheet {
        val out = RenderSheet(w * factor, h * factor)
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
