package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The pencil's grain, pinned. Everything here is about one promise: a mark, once drawn,
 * looks the same every time anyone draws it again — while the pen is still down, after the
 * bake, after a reload days later, and through the offline rasterizer. A grain that
 * reshuffles is a drawing that changes behind the artist's back.
 */
class GraphiteGrainTest {

    private fun line(
        n: Int,
        pressure: Float = 0.6f,
        x0: Float = 10f,
        y0: Float = 10f,
        step: Float = 4f,
    ): List<StrokePoint> = (0 until n).map {
        StrokePoint(x = x0 + it * step, y = y0 + it * step * 0.25f, pressure = pressure)
    }

    private fun assertSameGrain(a: GraphiteGrain.Grain, b: GraphiteGrain.Grain) {
        assertEquals("fleck count", a.count, b.count)
        for (i in 0 until a.count) {
            assertEquals("x[$i]", a.xy[i * 2], b.xy[i * 2], 0f)
            assertEquals("y[$i]", a.xy[i * 2 + 1], b.xy[i * 2 + 1], 0f)
            assertEquals("level[$i]", a.level[i], b.level[i])
        }
    }

    @Test
    fun `same stroke re-rendered is fleck-for-fleck identical`() {
        val pts = line(40)
        assertSameGrain(
            GraphiteGrain.of(pts, 6f, "a-stroke-id".hashCode()),
            GraphiteGrain.of(pts, 6f, "a-stroke-id".hashCode()),
        )
    }

    @Test
    fun `a copy of the point list renders the same as the original`() {
        // A host that reloads a page rebuilds its points from storage — different objects,
        // same numbers. The grain must not notice.
        val pts = line(40)
        val reloaded = pts.map { StrokePoint(it.x, it.y, it.pressure, it.tilt, it.timeMillis) }
        assertSameGrain(
            GraphiteGrain.of(pts, 6f, 12345),
            GraphiteGrain.of(reloaded, 6f, 12345),
        )
    }

    @Test
    fun `a different stroke gets different grain`() {
        val pts = line(40)
        val a = GraphiteGrain.of(pts, 6f, "one".hashCode())
        val b = GraphiteGrain.of(pts, 6f, "two".hashCode())
        var same = 0
        val n = minOf(a.count, b.count)
        for (i in 0 until n) if (a.xy[i * 2] == b.xy[i * 2] && a.xy[i * 2 + 1] == b.xy[i * 2 + 1]) same++
        assertTrue("two strokes should not share a texture (matched $same of $n)", same < n / 4)
    }

    @Test
    fun `the grain already laid down does not move as the stroke grows`() {
        // The live preview and the bake are the same stroke, so the flecks behind the pen
        // must not shift when the next sample arrives. Stations are placed at fixed arc
        // length from the first point, which is what makes the prefix stable.
        val seed = "growing".hashCode()
        val short = GraphiteGrain.of(line(10), 6f, seed)
        val long = GraphiteGrain.of(line(40), 6f, seed)
        assertTrue("the longer stroke should carry more graphite", long.count > short.count)
        for (i in 0 until short.count) {
            assertEquals("x[$i]", short.xy[i * 2], long.xy[i * 2], 0f)
            assertEquals("y[$i]", short.xy[i * 2 + 1], long.xy[i * 2 + 1], 0f)
            assertEquals("level[$i]", short.level[i], long.level[i])
        }
    }

    @Test
    fun `pressing harder fills in more of the tooth`() {
        val seed = 99
        val light = GraphiteGrain.of(line(60, pressure = 0.15f), 6f, seed)
        val firm = GraphiteGrain.of(line(60, pressure = 0.9f), 6f, seed)
        assertTrue(
            "firm ${firm.count} should deposit more than light ${light.count}",
            firm.count > light.count * 2,
        )
    }

    @Test
    fun `pressing harder also darkens the flecks that land`() {
        val seed = 99
        fun meanLevel(p: Float): Float {
            val g = GraphiteGrain.of(line(60, pressure = p), 6f, seed)
            var sum = 0
            for (i in 0 until g.count) sum += g.level[i]
            return sum.toFloat() / g.count
        }
        assertTrue("light should sit paler than firm", meanLevel(0.15f) < meanLevel(0.9f))
    }

    @Test
    fun `a broad lead meets more tooth than a fine one`() {
        val pts = line(40)
        val fine = GraphiteGrain.of(pts, 2f, 7)
        val broad = GraphiteGrain.of(pts, 12f, 7)
        assertTrue(
            "broad ${broad.count} should out-deposit fine ${fine.count}",
            broad.count > fine.count * 2,
        )
    }

    @Test
    fun `every fleck lands on the mark`() {
        // Nothing may stray outside the lead's own width plus the jitter that scatters it
        // off the lattice — a pencil does not spray.
        val width = 8f
        val pts = listOf(
            StrokePoint(100f, 100f, 0.8f),
            StrokePoint(300f, 100f, 0.8f),
        )
        val g = GraphiteGrain.of(pts, width, 4242)
        val slack = GraphiteGrain.TOOTH_PITCH_PX + GraphiteGrain.FLECK_MAX_PX
        for (i in 0 until g.count) {
            val x = g.xy[i * 2]
            val y = g.xy[i * 2 + 1]
            assertTrue("x=$x off the near end", x >= 100f - slack)
            assertTrue("x=$x off the far end", x <= 300f + slack)
            assertTrue("y=$y off the mark", abs(y - 100f) <= width / 2f + slack)
        }
    }

    @Test
    fun `no points, no graphite`() {
        assertEquals(0, GraphiteGrain.of(emptyList(), 6f, 1).count)
    }

    @Test
    fun `a tap leaves a disc of grit`() {
        val g = GraphiteGrain.of(listOf(StrokePoint(50f, 50f, 0.9f)), 10f, 3)
        assertTrue("a tap should leave something", g.count > 0)
        val slack = 5f + GraphiteGrain.TOOTH_PITCH_PX + GraphiteGrain.FLECK_MAX_PX
        for (i in 0 until g.count) {
            assertTrue(abs(g.xy[i * 2] - 50f) <= slack)
            assertTrue(abs(g.xy[i * 2 + 1] - 50f) <= slack)
        }
    }

    @Test
    fun `a path shorter than one tooth still leaves a mark`() {
        val g = GraphiteGrain.of(
            listOf(StrokePoint(5f, 5f, 0.7f), StrokePoint(5.4f, 5f, 0.7f)),
            6f,
            8,
        )
        assertTrue("a nudge of the pen is still graphite", g.count > 0)
    }

    @Test
    fun `repeated points are not a divide by zero`() {
        val g = GraphiteGrain.of(List(20) { StrokePoint(11f, 12f, 0.5f) }, 6f, 8)
        assertTrue(g.count > 0)
    }

    @Test
    fun `darkness levels run from the floor to full black`() {
        assertEquals(1f, GraphiteGrain.levelAlpha(GraphiteGrain.LEVELS - 1), 1e-6f)
        assertTrue(GraphiteGrain.levelAlpha(0) < 1f)
        assertTrue(GraphiteGrain.levelAlpha(0) > 0f)
        for (l in 1 until GraphiteGrain.LEVELS) {
            assertTrue(GraphiteGrain.levelAlpha(l) > GraphiteGrain.levelAlpha(l - 1))
        }
        // Out-of-range indices clamp rather than throw — the renderer must never crash a page.
        assertEquals(GraphiteGrain.levelAlpha(0), GraphiteGrain.levelAlpha(-5), 0f)
        assertEquals(GraphiteGrain.levelAlpha(GraphiteGrain.LEVELS - 1), GraphiteGrain.levelAlpha(99), 0f)
    }

    @Test
    fun `pale flecks stand apart and dark ones flood together`() {
        // The whole cure for the "pipe cleaner": a fleck wider than the lattice that spaces it
        // touches its neighbours and the grain becomes chains of little worms instead of specks.
        // So the palest fleck is about one pitch — separate specks, like the panel's own dither —
        // and the darkest is over two, so a hard-pressed line floods solid.
        assertTrue(
            "the palest fleck must not chain",
            GraphiteGrain.fleckPx(0) <= GraphiteGrain.TOOTH_PITCH_PX * 1.05f,
        )
        assertTrue(
            "the darkest fleck must flood",
            GraphiteGrain.fleckPx(GraphiteGrain.LEVELS - 1) > GraphiteGrain.TOOTH_PITCH_PX * 1.8f,
        )
        for (l in 1 until GraphiteGrain.LEVELS) {
            assertTrue(GraphiteGrain.fleckPx(l) > GraphiteGrain.fleckPx(l - 1))
        }
        assertEquals(GraphiteGrain.fleckPx(0), GraphiteGrain.fleckPx(-3), 0f)
        assertEquals(
            GraphiteGrain.fleckPx(GraphiteGrain.LEVELS - 1), GraphiteGrain.fleckPx(99), 0f
        )
    }

    // ── Tilt widens the mark ─────────────────────────────────────────────────

    private fun deg(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()

    @Test
    fun `the width curve matches what the panel's own firmware does`() {
        // Fitted on a NoteAir5C against the artist's eye, at the angles its digitizer reported as
        // 9, 44 and 75 degrees. The bake has to agree with the live ink or the mark changes size
        // when the pen lifts.
        assertEquals(1.0f, GraphiteGrain.widthFactor(deg(9f)), 0.01f)
        assertEquals(4.9f, GraphiteGrain.widthFactor(deg(44f)), 0.4f)
        assertEquals(10.9f, GraphiteGrain.widthFactor(deg(75f)), 0.6f)
    }

    @Test
    fun `the flank of the lead deposits lighter than its point`() {
        // Shading with the side of a pencil comes out grey however hard you lean, because the same
        // graphite is spread over a broader band.
        assertEquals(1f, GraphiteGrain.coverageFactor(0f), 0f)
        assertEquals(1f, GraphiteGrain.coverageFactor(deg(9f)), 0f)
        assertTrue(GraphiteGrain.coverageFactor(deg(44f)) < 1f)
        assertTrue(GraphiteGrain.coverageFactor(deg(75f)) < GraphiteGrain.coverageFactor(deg(44f)))
        // Never so light that a laid-over stroke stops being a mark.
        assertTrue(GraphiteGrain.coverageFactor(deg(90f)) > 0.4f)
    }

    @Test
    fun `a laid-over stroke is paler per unit of paper than an upright one`() {
        fun density(tiltDeg: Float): Float {
            val pts = listOf(
                StrokePoint(100f, 400f, 0.7f, deg(tiltDeg)),
                StrokePoint(500f, 400f, 0.7f, deg(tiltDeg)),
            )
            val g = GraphiteGrain.of(pts, 6f, 31)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until g.count) {
                val y = g.xy[i * 2 + 1]
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            return g.count / ((hi - lo) * 400f)
        }
        assertTrue("flat should be paler per unit area than upright", density(75f) < density(5f))
    }

    @Test
    fun `an upright pencil draws the width it was set to`() {
        // Zero is what every engine reports when it cannot honestly supply an angle, so this is
        // also the guarantee that an unmeasured device still gets a pencil rather than a hairline.
        assertEquals(1f, GraphiteGrain.widthFactor(0f), 0f)
        assertEquals(1f, GraphiteGrain.widthFactor(deg(5f)), 0f)
    }

    @Test
    fun `laying the pen over never narrows the mark`() {
        var previous = 0f
        var d = 0f
        while (d <= 90f) {
            val f = GraphiteGrain.widthFactor(deg(d))
            assertTrue("width went backwards at $d deg", f >= previous - 1e-4f)
            previous = f
            d += 1f
        }
    }

    @Test
    fun `a lean past flat is clamped rather than extrapolated`() {
        val flat = GraphiteGrain.widthFactor(deg(90f))
        assertEquals(flat, GraphiteGrain.widthFactor(deg(120f)), 0f)
        assertEquals(flat, GraphiteGrain.widthFactor(deg(400f)), 0f)
    }

    @Test
    fun `a stroke laid over deposits over a broader band than an upright one`() {
        fun band(tiltDeg: Float): Float {
            val pts = listOf(
                StrokePoint(100f, 200f, 0.7f, deg(tiltDeg)),
                StrokePoint(400f, 200f, 0.7f, deg(tiltDeg)),
            )
            val g = GraphiteGrain.of(pts, 6f, 77)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until g.count) {
                val y = g.xy[i * 2 + 1]
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            return hi - lo
        }
        val upright = band(5f)
        val flat = band(75f)
        assertTrue("flat $flat should be far broader than upright $upright", flat > upright * 3f)
    }

    @Test
    fun `the pen rolling over mid-stroke broadens the mark as it goes`() {
        // A shading stroke is a hand laying the pencil down as it travels. Tilt is read per
        // station for exactly this; taken once at pen-down the mark would come out a uniform bar.
        val pts = listOf(
            StrokePoint(100f, 300f, 0.7f, deg(5f)),
            StrokePoint(500f, 300f, 0.7f, deg(80f)),
        )
        val g = GraphiteGrain.of(pts, 6f, 91)
        fun spreadNear(x: Float): Float {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until g.count) {
                if (abs(g.xy[i * 2] - x) > 30f) continue
                val y = g.xy[i * 2 + 1]
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            return hi - lo
        }
        assertTrue("the far end should be broader than the near end", spreadNear(460f) > spreadNear(140f) * 2f)
    }

    @Test
    fun `tilt does not disturb the grain's determinism`() {
        val pts = (0 until 40).map {
            StrokePoint(10f + it * 4f, 50f, 0.6f, deg(10f + it))
        }
        assertSameGrain(GraphiteGrain.of(pts, 6f, 5), GraphiteGrain.of(pts, 6f, 5))
    }

    @Test
    fun `an absurd stroke degrades instead of stalling the frame`() {
        val huge = (0 until 4000).map { StrokePoint(it * 3f, (it % 40) * 7f, 1f) }
        val g = GraphiteGrain.of(huge, 60f, 5)
        // Bounded well below anything that would stall a frame, and never zero — the cap must
        // degrade a monstrous stroke, not erase it.
        assertTrue("capped, got ${g.count}", g.count in 1..420_000)
        assertNotEquals(0, g.count)
    }
}
