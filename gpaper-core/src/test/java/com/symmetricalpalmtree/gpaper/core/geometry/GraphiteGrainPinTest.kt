package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The committed pencil, pinned byte for byte.
 *
 * Every other test here states a *property* of the grain — determinism, the prefix
 * invariant, the cap's density — and a property can go on holding while the mark it
 * describes quietly moves. This one pins the mark itself: the exact flecks nine strokes
 * produced on the implementation the artist approved, as a checksum over every coordinate's
 * raw bits and every darkness. It exists because Phase 28's incremental sweep rewrote the
 * station loop to run on a resumable state object so that `of` and `Sweep.extend` could
 * share one loop, and a refactor of the loop that lays every pencil mark on every engine
 * needs something that fails loudly if one fleck lands a bit differently.
 *
 * **If a deliberate change to the grain breaks this, the numbers are re-pinned and the walk
 * is re-run** — they are a record of what the current file makes, not an argument that it is
 * right.
 */
class GraphiteGrainPinTest {

    private fun deg(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()

    private fun straight(n: Int): List<StrokePoint> =
        (0 until n).map { StrokePoint(x = 80f + it * 3f, y = 420f, pressure = 0.65f) }

    private fun curved(n: Int): List<StrokePoint> = (0 until n).map {
        val t = it * 0.045f
        StrokePoint(
            x = 400f + 260f * sin(t),
            y = 500f + 180f * (1f - cos(t)),
            pressure = 0.35f + 0.5f * sin(t * 1.7f) * sin(t * 1.7f),
            tilt = deg(20f + 45f * t),
        )
    }

    private fun slowStart(n: Int): List<StrokePoint> {
        val pts = ArrayList<StrokePoint>()
        for (i in 0 until 12) {
            val t = i / 11f
            pts.add(
                StrokePoint(
                    x = 300f + 9f * sin(t * 3.1f),
                    y = 400f + 5f * cos(t * 3.1f),
                    pressure = 0.5f,
                    tilt = deg(64f),
                )
            )
        }
        for (i in 1 until n) {
            pts.add(StrokePoint(300f + i * 2.5f, 400f + i * 0.4f, 0.7f, deg(64f)))
        }
        return pts
    }

    /** Every fleck's raw bits, folded into one number — FNV-1a, so one moved fleck changes it. */
    private fun digest(g: GraphiteGrain.Grain): String {
        var h = -0x340d631b7bdddcdbL
        fun mix(v: Int) {
            h = h xor (v.toLong() and 0xFFFFFFFFL)
            h *= 0x100000001b3L
        }
        for (i in 0 until g.count) {
            mix(g.xy[i * 2].toRawBits())
            mix(g.xy[i * 2 + 1].toRawBits())
            mix(g.level[i])
        }
        return "${g.count}:${java.lang.Long.toHexString(h)}"
    }

    private fun pin(expected: String, points: List<StrokePoint>, width: Float, seed: Int, prefix: Boolean = false) {
        assertEquals(expected, digest(GraphiteGrain.of(points, width, seed, prefix)))
    }

    @Test
    fun `a straight stroke's grain has not moved`() {
        pin("2301:d609d0d6472ea0b5", straight(90), 6f, "straight".hashCode())
    }

    @Test
    fun `the widest lead's grain has not moved`() {
        // 96 px: the lever arm that LEVER_JITTER exists for, and the one a station-loop
        // refactor would show first.
        pin("38238:2d5c95887ac87645", straight(90), 96f, "wide".hashCode())
    }

    @Test
    fun `a curved, rolling stroke's grain has not moved`() {
        pin("66268:5479e69b98b38436", curved(120), 14f, "curved".hashCode())
    }

    @Test
    fun `a hairline's grain has not moved`() {
        pin("4295:59fa43e8860d3480", curved(80), 1.2f, "hairline".hashCode())
    }

    @Test
    fun `a stroke with a landing excursion has not moved`() {
        pin("23503:159457c192b4fd99", slowStart(120), 12f, "slow-start".hashCode())
    }

    @Test
    fun `a tap and a two-sample stroke have not moved`() {
        pin("36:d39e5147104f0f43", listOf(StrokePoint(100f, 100f, 0.6f)), 6f, 11)
        pin("48:ca914b3f117d8fc7", straight(2), 6f, 5)
    }

    @Test
    fun `prefix mode has not moved either`() {
        pin("64648:acfb845c56ab0e1e", curved(120), 14f, "curved".hashCode(), prefix = true)
        pin("21228:8e5dbcd1460fd1ad", slowStart(120), 12f, "slow-start".hashCode(), prefix = true)
    }
}
