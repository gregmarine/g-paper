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
        val slack = GraphiteGrain.TOOTH_PITCH_PX
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
        val slack = 5f + GraphiteGrain.TOOTH_PITCH_PX
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
    fun `flecks overlap into solid ink at full coverage`() {
        // At the hardest press the tooth fills in, so the mark must read as a line rather
        // than a dotted one: flecks are wider than the pitch that spaces them.
        assertTrue(GraphiteGrain.FLECK_PX > GraphiteGrain.TOOTH_PITCH_PX)
    }

    @Test
    fun `an absurd stroke degrades instead of stalling the frame`() {
        val huge = (0 until 4000).map { StrokePoint(it * 3f, (it % 40) * 7f, 1f) }
        val g = GraphiteGrain.of(huge, 60f, 5)
        assertTrue("capped", g.count in 1..250_000)
        assertNotEquals(0, g.count)
    }
}
